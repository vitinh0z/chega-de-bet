package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.MotivoFalhaScraping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

/**
 * Decide se a pré-análise pode abrir conexão para uma URL.
 * <p>
 * Este é o componente mais sensível do scraper. Sem ele, a pré-análise é um SSRF aberto:
 * qualquer pessoa capaz de mandar um {@code POST /api/denuncias} escolhe um endereço, e o
 * <b>nosso servidor</b> faz a requisição a partir de dentro da rede, com a confiança que
 * a rede dá a ele. Denunciar {@code 169.254.169.254} entregaria as credenciais de nuvem
 * da VM; denunciar {@code 10.0.0.5} varreria a rede interna. O denunciante nem precisa
 * ver a resposta — o tempo até o erro já diz se a porta está aberta.
 *
 * <h2>A regra é lista de negação sobre o endereço, não sobre o nome</h2>
 * Validar o texto do host não serve para nada: {@code interno.exemplo.com} pode resolver
 * para {@code 10.0.0.5}, e {@code 0x7f.1} é loopback escrito de outro jeito. A checagem
 * acontece depois da resolução de DNS, sobre os bytes do endereço.
 *
 * <h2>Todos os endereços, não o primeiro</h2>
 * Um nome pode ter vários registros A e AAAA. Validar só o primeiro deixa passar o alvo
 * que devolve um IP público e um interno na mesma resposta e conta com o cliente sortear.
 * Se qualquer endereço do conjunto for proibido, o nome inteiro é recusado.
 *
 * <h2>O que este desenho NÃO cobre</h2>
 * Resta uma janela de <i>DNS rebinding</i>: nós resolvemos o nome aqui, e logo depois o
 * {@code HttpClient} resolve de novo por conta própria para conectar. Um alvo que sirva
 * um IP público na primeira consulta e um IP interno na segunda, com TTL zero, passa
 * entre as duas.
 * <p>
 * Fechar essa janela exigiria conectar no endereço já validado em vez de no nome — o que
 * o {@code java.net.http.HttpClient} não permite, porque não aceita um resolvedor próprio.
 * As saídas seriam falar HTTP sobre um {@code Socket} na mão, ou conectar pelo IP e
 * mandar o {@code Host} no cabeçalho, e a segunda quebra a validação do certificado TLS.
 * <p>
 * Assumimos a janela conscientemente, porque o custo de explorá-la é alto (exige
 * autoridade de DNS e acerto de corrida) e porque as camadas que sobram continuam de pé:
 * a resposta do alvo nunca volta para quem denunciou, e cada hop de redirecionamento é
 * revalidado. O caminho para fechá-la de verdade é trocar o cliente HTTP, e isso está
 * registrado aqui para quem for fazer essa troca saber o motivo.
 */
@Component
public class GuardaSsrf {

    private static final Logger log = LoggerFactory.getLogger(GuardaSsrf.class);

    /**
     * Avalia uma URL antes de qualquer conexão.
     *
     * @return vazio se a pré-análise pode seguir; o motivo da recusa caso contrário
     */
    public Optional<MotivoFalhaScraping> avaliar(URI uri) {
        Optional<MotivoFalhaScraping> problemaDeEsquema = validarEsquema(uri);
        if (problemaDeEsquema.isPresent()) {
            return problemaDeEsquema;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // Acontece com URL malformada e com formas exóticas que o parser aceita mas
            // não sabe nomear. Sem host não há o que validar, então não há o que permitir.
            log.warn("Pré-análise recusada: URL sem host resolvível ({})", uri);
            return Optional.of(MotivoFalhaScraping.ESQUEMA_INVALIDO);
        }

        InetAddress[] enderecos;
        try {
            enderecos = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return Optional.of(MotivoFalhaScraping.DNS_NAO_RESOLVE);
        }

        for (InetAddress endereco : enderecos) {
            if (proibido(endereco)) {
                // Log em WARN, com o host e o endereço: alguém tentando alcançar a rede
                // interna pela fila de denúncia é incidente de segurança, não ruído. O
                // denunciante não aparece aqui — a pré-análise nunca soube quem foi.
                log.warn("SSRF bloqueado: host {} resolveu para endereço não roteável {}",
                        host, endereco.getHostAddress());
                return Optional.of(MotivoFalhaScraping.SSRF_BLOQUEADO);
            }
        }
        return Optional.empty();
    }

    /**
     * Só {@code http} e {@code https} passam.
     * <p>
     * {@code file:} leria disco do servidor, {@code jar:} e {@code ftp:} alcançam outros
     * protocolos, e {@code gopher:} é o clássico para forjar requisição arbitrária contra
     * um Redis ou Memcached interno. Nenhum deles tem uso legítimo na pré-análise de uma
     * página pública, então a lista é de permissão, e não de negação.
     */
    private Optional<MotivoFalhaScraping> validarEsquema(URI uri) {
        String esquema = uri.getScheme();
        if (esquema == null) {
            return Optional.of(MotivoFalhaScraping.ESQUEMA_INVALIDO);
        }
        String normalizado = esquema.toLowerCase(Locale.ROOT);
        if (normalizado.equals("http") || normalizado.equals("https")) {
            return Optional.empty();
        }
        log.warn("Pré-análise recusada: esquema {} não é http nem https", normalizado);
        return Optional.of(MotivoFalhaScraping.ESQUEMA_INVALIDO);
    }

    /**
     * As faixas IPv4 que a pré-análise nunca alcança, como CIDR de verdade.
     * <p>
     * Pares {@code (rede, prefixo)} achatados em um {@code int[]}: um bloco contíguo de
     * memória, sem objeto por faixa e sem boxing. A checagem de cada faixa é
     * {@code (ip & mascara) == rede} — que é a definição de CIDR, não uma aproximação
     * dela. A versão anterior comparava octeto a octeto com intervalos escritos à mão
     * ({@code segundo >= 64 && segundo <= 127}), e cada linha dessas é uma chance de
     * errar o limite da faixa em um.
     * <p>
     * A ordem é a da probabilidade de acerto: privado e loopback primeiro, exóticos
     * depois. Em um endereço público a varredura percorre a lista inteira, e são doze
     * comparações de inteiro — trabalho irrelevante ao lado da resolução de DNS que
     * acabou de acontecer.
     */
    private static final int[] FAIXAS_IPV4_PROIBIDAS = {
            ip(10, 0, 0, 0), 8,          // RFC 1918 — rede privada
            ip(172, 16, 0, 0), 12,       // RFC 1918 — rede privada
            ip(192, 168, 0, 0), 16,      // RFC 1918 — rede privada
            ip(127, 0, 0, 0), 8,         // Loopback: o próprio backend
            ip(169, 254, 0, 0), 16,      // Link-local. Contém o 169.254.169.254 de metadados
            ip(0, 0, 0, 0), 8,           // "Esta rede". Vários sistemas tratam como loopback
            ip(100, 64, 0, 0), 10,       // CGNAT (RFC 6598). O JDK não conhece esta faixa
            ip(192, 0, 0, 0), 24,        // Atribuições de protocolo da IETF (NAT64 well-known)
            ip(198, 18, 0, 0), 15,       // Faixa de benchmark, roteada para dentro em labs
            ip(192, 0, 2, 0), 24,        // TEST-NET-1 (RFC 5737)
            ip(198, 51, 100, 0), 24,     // TEST-NET-2
            ip(203, 0, 113, 0), 24,      // TEST-NET-3
            ip(224, 0, 0, 0), 4,         // Multicast
            ip(240, 0, 0, 0), 4          // Reservado, inclui 255.255.255.255
    };

    /**
     * Se este endereço está fora do que a pré-análise pode alcançar.
     * <p>
     * IPv4 é resolvido inteiramente pela tabela CIDR acima. Para IPv6 continuamos usando
     * os predicados do {@link InetAddress}, mais a faixa {@code fc00::/7} que ele não
     * conhece.
     */
    boolean proibido(InetAddress endereco) {
        byte[] bytes = endereco.getAddress();
        return bytes.length == 4 ? proibidoIpv4(bytes) : proibidoIpv6(endereco, bytes);
    }

    /**
     * Os quatro octetos viram um {@code int} e a comparação é de máscara.
     * <p>
     * Um endereço IPv4 <b>é</b> um inteiro de 32 bits — trabalhar com ele como tal é a
     * representação natural, e não um truque. O deslocamento aritmético
     * {@code -1 << (32 - prefixo)} monta a máscara do prefixo: para {@code /10} dá
     * {@code 0xFFC00000}, exatamente os 10 bits mais significativos.
     * <p>
     * O caso {@code prefixo == 0} não aparece na tabela, e nem poderia: {@code -1 << 32}
     * em Java desloca por {@code 32 & 31 == 0} e devolveria {@code -1}, uma máscara que
     * não casa nada. Nenhuma faixa de {@code /0} é bloqueável de qualquer forma, porque
     * {@code /0} é a internet inteira.
     */
    private boolean proibidoIpv4(byte[] b) {
        int ip = (b[0] & 0xFF) << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);

        for (int i = 0; i < FAIXAS_IPV4_PROIBIDAS.length; i += 2) {
            int mascara = -1 << (32 - FAIXAS_IPV4_PROIBIDAS[i + 1]);
            if ((ip & mascara) == FAIXAS_IPV4_PROIBIDAS[i]) {
                return true;
            }
        }
        return false;
    }

    private boolean proibidoIpv6(InetAddress endereco, byte[] b) {
        // fc00::/7 — Unique Local Address, o equivalente IPv6 da RFC 1918. O
        // isSiteLocalAddress do Java só conhece fec0::/10, que foi depreciado e
        // substituído justamente por esta faixa.
        if ((b[0] & 0xFE) == 0xFC) {
            return true;
        }
        return endereco.isLoopbackAddress()        // ::1
                || endereco.isAnyLocalAddress()    // ::
                || endereco.isLinkLocalAddress()   // fe80::/10
                || endereco.isSiteLocalAddress()   // fec0::/10, depreciado mas ainda existe
                || endereco.isMulticastAddress();  // ff00::/8
    }

    /** Monta o inteiro de 32 bits de um endereço IPv4 escrito em octetos. */
    private static int ip(int a, int b, int c, int d) {
        return a << 24 | b << 16 | c << 8 | d;
    }
}

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
     * Se este endereço está fora do que a pré-análise pode alcançar.
     * <p>
     * O {@link InetAddress} responde a parte disso sozinho, mas não a tudo — as faixas
     * checadas na mão abaixo são justamente as que ele ignora, e cada uma delas é um
     * caminho real para dentro da infraestrutura.
     */
    boolean proibido(InetAddress endereco) {
        if (endereco.isLoopbackAddress()      // 127.0.0.0/8, ::1 — o próprio backend
                || endereco.isAnyLocalAddress()   // 0.0.0.0, :: — resolve para "esta máquina"
                || endereco.isLinkLocalAddress()  // 169.254.0.0/16 inclui o endpoint de metadados
                || endereco.isSiteLocalAddress()  // RFC 1918: 10/8, 172.16/12, 192.168/16
                || endereco.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = endereco.getAddress();
        if (bytes.length == 4) {
            return proibidoIpv4(bytes);
        }
        return proibidoIpv6(bytes);
    }

    private boolean proibidoIpv4(byte[] b) {
        int primeiro = Byte.toUnsignedInt(b[0]);
        int segundo = Byte.toUnsignedInt(b[1]);

        // 100.64.0.0/10 — CGNAT (RFC 6598). Operadoras usam esta faixa entre o cliente e
        // a internet, e em algumas nuvens ela alcança serviços internos. isSiteLocalAddress
        // não a conhece, porque ela é posterior à RFC 1918.
        if (primeiro == 100 && segundo >= 64 && segundo <= 127) {
            return true;
        }
        // 192.0.0.0/24 — atribuições de protocolo da IETF, entre elas o NAT64 well-known.
        if (primeiro == 192 && segundo == 0 && Byte.toUnsignedInt(b[2]) == 0) {
            return true;
        }
        // 198.18.0.0/15 — faixa de benchmark, roteada para dentro em muitos laboratórios.
        if (primeiro == 198 && (segundo == 18 || segundo == 19)) {
            return true;
        }
        // 0.0.0.0/8 — "esta rede". Vários sistemas operacionais tratam como loopback.
        return primeiro == 0;
    }

    private boolean proibidoIpv6(byte[] b) {
        int primeiro = Byte.toUnsignedInt(b[0]);

        // fc00::/7 — Unique Local Address, o equivalente IPv6 da RFC 1918. O
        // isSiteLocalAddress do Java só conhece fec0::/10, que foi depreciado e
        // substituído justamente por esta faixa.
        return (primeiro & 0xFE) == 0xFC;
    }
}

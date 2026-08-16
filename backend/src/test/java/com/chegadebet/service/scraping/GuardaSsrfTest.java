package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.MotivoFalhaScraping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A defesa contra SSRF, testada endereço a endereço.
 * <p>
 * É o teste mais importante do scraper. Sem esta barreira, qualquer pessoa capaz de mandar
 * uma denúncia escolhe um endereço e faz o <b>nosso</b> servidor requisitá-lo de dentro da
 * rede — com a confiança que a rede dá a ele, e não a quem denunciou.
 * <p>
 * Os endereços aqui são literais, e não nomes. É de propósito: um teste que dependesse de
 * um domínio resolvendo para um IP privado dependeria do DNS de quem roda o teste, e
 * passaria a falhar em CI por um motivo que não tem nada a ver com o código.
 */
class GuardaSsrfTest {

    private final GuardaSsrf guarda = new GuardaSsrf();

    // ===== Endereços que não podem ser alcançados =====

    @ParameterizedTest(name = "recusa {0}")
    @DisplayName("Recusa loopback, link-local, RFC 1918 e as faixas que o JDK não conhece")
    @ValueSource(strings = {
            // O próprio backend. O clássico: alcançar o Actuator na porta de gestão.
            "https://127.0.0.1/",
            "https://127.0.0.53/",
            // O endpoint de metadados de nuvem. Devolve credencial da VM sem autenticação
            // nenhuma — é o alvo número um de todo SSRF em nuvem.
            "https://169.254.169.254/latest/meta-data/",
            "https://169.254.170.2/",
            // RFC 1918, as três faixas.
            "https://10.0.0.5/",
            "https://172.16.0.1/",
            "https://192.168.1.1/",
            // "esta máquina".
            "https://0.0.0.0/",
            // CGNAT (RFC 6598). isSiteLocalAddress do JDK não conhece esta faixa.
            "https://100.64.0.1/",
            "https://100.127.255.254/",
            // Atribuições de protocolo da IETF e faixa de benchmark.
            "https://192.0.0.1/",
            "https://198.18.0.1/",
            "https://198.19.255.255/",
            // IPv6: loopback e Unique Local Address (fc00::/7), que substituiu o
            // fec0::/10 que o isSiteLocalAddress do JDK ainda procura.
            "https://[::1]/",
            "https://[fd00::1]/",
            "https://[fc00::1]/",
            "https://[fe80::1]/"
    })
    void recusaEnderecoNaoRoteavel(String url) {
        assertThat(guarda.avaliar(URI.create(url)))
                .contains(MotivoFalhaScraping.SSRF_BLOQUEADO);
    }

    @Test
    @DisplayName("Recusa o endereço de metadados escrito em decimal")
    void recusaEnderecoOfuscado() {
        // 2852039166 = 169.254.169.254. A checagem é sobre os bytes do endereço resolvido,
        // não sobre o texto, então a forma de escrever não muda o resultado.
        assertThat(guarda.avaliar(URI.create("https://2852039166/")))
                .contains(MotivoFalhaScraping.SSRF_BLOQUEADO);
    }

    // ===== Esquemas =====

    @ParameterizedTest(name = "recusa o esquema de {0}")
    @DisplayName("Só http e https passam")
    @ValueSource(strings = {
            // Leria o disco do servidor.
            "file:///etc/passwd",
            // O clássico para forjar uma requisição contra um Redis interno.
            "gopher://exemplo.com:6379/_SET%20chave%20valor",
            "ftp://exemplo.com/arquivo",
            "jar:file:///app.jar!/",
            "ldap://exemplo.com/",
            "dict://exemplo.com:11211/stat"
    })
    void recusaEsquemaForaDeHttp(String url) {
        assertThat(guarda.avaliar(URI.create(url)))
                .contains(MotivoFalhaScraping.ESQUEMA_INVALIDO);
    }

    @Test
    @DisplayName("URL sem host não é liberada por falta de informação")
    void recusaUrlSemHost() {
        assertThat(guarda.avaliar(URI.create("https:///caminho"))).isPresent();
    }

    // ===== O que deve passar =====

    @ParameterizedTest(name = "recusa a faixa de documentação {0}")
    @DisplayName("Faixas de documentação (RFC 5737) também ficam de fora")
    @ValueSource(strings = {
            "https://192.0.2.1/",
            "https://198.51.100.7/",
            "https://203.0.113.42/"
    })
    void recusaFaixasDeDocumentacao(String url) {
        // Passaram a ser bloqueadas ao reescrever a checagem como tabela CIDR. Não têm uso
        // legítimo na internet, e em laboratório costumam apontar para dentro.
        assertThat(guarda.avaliar(URI.create(url)))
                .contains(MotivoFalhaScraping.SSRF_BLOQUEADO);
    }

    @ParameterizedTest(name = "libera o vizinho de faixa {0}")
    @DisplayName("O bloqueio para exatamente na borda da faixa, sem pegar o vizinho")
    @ValueSource(strings = {
            // 172.16/12 termina em 172.31.255.255 — 172.32 é público.
            "https://172.32.0.1/",
            // 100.64/10 termina em 100.127.255.255 — 100.128 é público.
            "https://100.128.0.1/",
            // 198.18/15 cobre só 198.18 e 198.19.
            "https://198.20.0.1/",
            // 169.254/16 não se estende para 169.255.
            "https://169.255.0.1/",
            // 192.0.0.0/24 e 192.0.2.0/24 são recortes: 192.0.1.1 fica fora dos dois.
            "https://192.0.1.1/",
            // 9.0.0.1 e 11.0.0.1 abraçam o 10/8 sem entrar nele.
            "https://11.0.0.1/"
    })
    void naoTransbordaAFaixa(String url) {
        // Errar o limite de uma faixa em um endereço é o defeito clássico desse tipo de
        // checagem, e era mais fácil de cometer quando ela era escrita octeto a octeto,
        // com intervalos à mão. Com máscara CIDR o limite sai do prefixo.
        assertThat(guarda.avaliar(URI.create(url))).isEmpty();
    }

    @Test
    @DisplayName("Endereço público é liberado")
    void liberaEnderecoPublico() {
        // Literal, para não depender de DNS: 8.8.8.8 é roteável e está fora de toda faixa
        // reservada. O teste não abre conexão nenhuma — só passa pelo guarda.
        assertThat(guarda.avaliar(URI.create("https://8.8.8.8/"))).isEmpty();
    }

    @Test
    @DisplayName("Nome que não resolve é falha de DNS, não bloqueio de SSRF")
    void distingueDnsQuebradoDeBloqueio() {
        // A distinção importa: "SSRF bloqueado" é alerta de segurança e vai para WARN;
        // "DNS não resolve" é o domínio denunciado ter saído do ar, que é rotina.
        String inexistente = "nao-existe-" + System.nanoTime() + ".invalid";

        assertThat(guarda.avaliar(URI.create("https://" + inexistente + "/")))
                .contains(MotivoFalhaScraping.DNS_NAO_RESOLVE);
    }

    @Test
    @DisplayName("http também passa: o bloqueio é de esquema exótico, não de falta de TLS")
    void liberaHttp() {
        // O cliente só monta https por conta própria. O guarda precisa aceitar http mesmo
        // assim, porque um redirecionamento pode apontar para ele.
        assertThat(guarda.avaliar(URI.create("http://8.8.8.8/"))).isEmpty();
    }
}

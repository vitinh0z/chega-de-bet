package com.chegadebet.service.scraping;

import com.chegadebet.config.ScraperProperties;
import com.chegadebet.domain.enums.MotivoFalhaScraping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O cliente HTTP contra um servidor local de verdade, sem mock e sem internet.
 *
 * <h2>Por que o guarda de SSRF é frouxo aqui</h2>
 * O servidor de teste escuta em {@code 127.0.0.1}, que é exatamente o endereço que o
 * {@link GuardaSsrf} existe para recusar. O teste libera o loopback e <b>só</b> ele:
 * qualquer outro endereço continua passando pela regra de produção inteira. É o que
 * permite o cenário de redirecionamento para o endpoint de metadados ser um teste de
 * verdade, e não uma encenação — o bloqueio ali vem do código de produção.
 */
class DomainScraperClientTest {

    /** Nunca conhecido: força o cliente a seguir todos os hops. */
    private static final Predicate<String> NADA_CONHECIDO = host -> false;

    private ServidorDeTeste servidor;
    private DomainScraperClient cliente;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = new ServidorDeTeste();
        cliente = new DomainScraperClient(new GuardaComLoopbackLiberado(), propriedades());
    }

    @AfterEach
    void derrubarServidor() {
        servidor.close();
    }

    /**
     * Teto de 2 KB e timeout de 1 s. Valores minúsculos de propósito: o que está sob teste
     * é o comportamento no limite, e com os defaults de produção (100 KB, 15 s) cada
     * cenário de corte precisaria de um alvo enorme ou de uma espera longa.
     */
    private ScraperProperties propriedades() {
        return new ScraperProperties(
                true, Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(1),
                DataSize.ofKilobytes(2), "ChegaDeBet-Teste/1.0", 3, 1,
                Duration.ZERO, Duration.ofHours(24), 2, 50);
    }

    // ===== Caminho feliz =====

    @Test
    @DisplayName("Baixa a home e devolve o HTML")
    void baixaAHome() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/ok"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Documento.class, documento -> {
            assertThat(documento.html()).contains("Cassino online");
            assertThat(documento.bytesBaixados()).isPositive();
            assertThat(documento.cortadoEmHopConhecido()).isFalse();
        });
    }

    // ===== Limite de bytes (issues #170 e #134) =====

    @Test
    @DisplayName("Pede só o primeiro pedaço via cabeçalho Range")
    void mandaOCabecalhoRange() {
        cliente.buscar(servidor.uri("/ok"), NADA_CONHECIDO);

        // 2 KB de teto: pede os bytes 0 a 2047, e não o documento inteiro.
        assertThat(servidor.ultimoRange()).isEqualTo("bytes=0-2047");
    }

    @Test
    @DisplayName("Servidor que suporta Range devolve 206 e o cliente aceita")
    void aceitaRespostaParcial() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/range"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Documento.class, documento ->
                assertThat(documento.bytesBaixados()).isEqualTo(2048));
    }

    @Test
    @DisplayName("Servidor que ignora Range não fura o teto: o corte é na leitura")
    void truncaQuandoOServidorIgnoraORange() {
        // 50 KB oferecidos, 2 KB de teto. Este é o fallback da issue #134.
        RespostaScraping resposta = cliente.buscar(servidor.uri("/grande"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Documento.class, documento -> {
            assertThat(documento.bytesBaixados()).isEqualTo(2048);
            // O sinal está no rodapé, depois do teto. Não ter descido é a prova de que o
            // corte aconteceu — e é também a limitação aceita do desenho.
            assertThat(documento.html()).doesNotContain("Curaçao");
        });
    }

    @Test
    @DisplayName("Servidor sem Content-Length também respeita o teto")
    void respeitaOTetoSemContentLength() {
        // Content-Length é um número que o alvo declara. Só a contagem na leitura vale.
        RespostaScraping resposta = cliente.buscar(servidor.uri("/mentiroso"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Documento.class, documento ->
                assertThat(documento.bytesBaixados()).isEqualTo(2048));
    }

    // ===== Redirecionamento (issue #133) =====

    @Test
    @DisplayName("Segue a cadeia de redirecionamento até o documento final")
    void segueACadeiaDeRedirecionamento() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/redirect-1"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Documento.class, documento -> {
            assertThat(documento.html()).contains("Cassino online");
            // A URL final é a evidência: o domínio denunciado não é onde a cadeia termina.
            assertThat(documento.urlFinal()).endsWith("/ok");
        });
        assertThat(servidor.pedidosEm("/redirect-2")).isEqualTo(1);
        assertThat(servidor.pedidosEm("/ok")).isEqualTo(1);
    }

    @Test
    @DisplayName("Laço de redirecionamento para no teto de hops")
    void cortaOLacoDeRedirecionamento() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/laco"), NADA_CONHECIDO);

        assertThat(resposta).isEqualTo(new RespostaScraping.Falha(
                MotivoFalhaScraping.REDIRECT_EXCEDIDO, servidor.uri("/laco").toString()));
        // max-redirects = 3, e a primeira requisição não é um redirecionamento seguido:
        // 4 idas ao servidor e nenhuma a mais.
        assertThat(servidor.pedidosEm("/laco")).isEqualTo(4);
    }

    @Test
    @DisplayName("Para no primeiro hop já conhecido, sem baixar o corpo")
    void cortaNoPrimeiroHopConhecido() {
        RespostaScraping resposta = cliente.buscar(
                servidor.uri("/para-conhecido"), host -> host.equals("ja-conhecido.invalid"));

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Documento.class, documento -> {
            assertThat(documento.cortadoEmHopConhecido()).isTrue();
            assertThat(documento.bytesBaixados()).isZero();
            assertThat(documento.urlFinal()).isEqualTo("http://ja-conhecido.invalid/pagina");
        });
        // O destino é um domínio .invalid, que nunca resolve. Ter terminado em sucesso é a
        // prova de que o cliente parou antes de tentar conectar nele.
    }

    @Test
    @DisplayName("3xx sem Location é resposta quebrada, não redirecionamento")
    void trataRedirecionamentoSemDestino() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/sem-location"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Falha.class, falha ->
                assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.ERRO_DE_LEITURA));
    }

    // ===== SSRF no meio da cadeia (issue #181) =====

    @Test
    @DisplayName("Redirecionamento para o endpoint de metadados é bloqueado no segundo hop")
    void bloqueiaRedirecionamentoParaEnderecoInterno() {
        // O primeiro hop é legítimo e passa. É o cenário que a validação só na entrada
        // deixaria passar inteiro.
        RespostaScraping resposta = cliente.buscar(servidor.uri("/para-metadados"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Falha.class, falha -> {
            assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.SSRF_BLOQUEADO);
            assertThat(falha.urlFinal()).contains("169.254.169.254");
        });
    }

    // ===== Falhas de rede (issue #177) =====

    @Test
    @DisplayName("Resposta lenta estoura o timeout total")
    void cortaAConexaoLenta() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/lento"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Falha.class, falha ->
                assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.TIMEOUT));
    }

    @Test
    @DisplayName("4xx e 5xx são classificados separadamente")
    void separa4xxDe5xx() {
        // A distinção não é cosmética: 5xx é transitório e vale repetir, 4xx não.
        assertThat(cliente.buscar(servidor.uri("/404"), NADA_CONHECIDO))
                .isInstanceOfSatisfying(RespostaScraping.Falha.class, falha ->
                        assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.HTTP_4XX));

        assertThat(cliente.buscar(servidor.uri("/503"), NADA_CONHECIDO))
                .isInstanceOfSatisfying(RespostaScraping.Falha.class, falha ->
                        assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.HTTP_5XX));
    }

    @Test
    @DisplayName("Conteúdo que não é HTML nem chega a ser baixado")
    void recusaConteudoQueNaoEHtml() {
        RespostaScraping resposta = cliente.buscar(servidor.uri("/pdf"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Falha.class, falha ->
                assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.CONTEUDO_NAO_HTML));
    }

    @Test
    @DisplayName("Conexão recusada é distinguida de timeout")
    void distingueConexaoRecusadaDeTimeout() {
        // Porta 1 do loopback: nada escuta ali, e o sistema recusa na hora.
        RespostaScraping resposta = cliente.buscar(
                java.net.URI.create("http://127.0.0.1:1/"), NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Falha.class, falha ->
                assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.CONEXAO_RECUSADA));
    }

    // ===== A regra de https na entrada pública =====

    @Test
    @DisplayName("A entrada pública monta https e nunca visita outro caminho além da raiz")
    void aEntradaPublicaUsaHttpsNaRaiz() {
        // .invalid nunca resolve, então a falha é de DNS — e a URL registrada prova o
        // esquema e o caminho que o cliente montou sozinho.
        RespostaScraping resposta = cliente.buscar("alvo-inexistente.invalid", NADA_CONHECIDO);

        assertThat(resposta).isInstanceOfSatisfying(RespostaScraping.Falha.class, falha -> {
            assertThat(falha.motivo()).isEqualTo(MotivoFalhaScraping.DNS_NAO_RESOLVE);
            assertThat(falha.urlFinal()).isEqualTo("https://alvo-inexistente.invalid/");
        });
    }

    /**
     * O guarda de produção, com uma única exceção: o loopback do servidor de teste.
     * <p>
     * Sobrescrever {@code proibido} e não {@code avaliar} é o ponto: toda a resolução de
     * DNS, a validação de esquema e a checagem das demais faixas continuam sendo o código
     * de produção. Só a resposta sobre {@code 127.0.0.1} muda.
     */
    private static class GuardaComLoopbackLiberado extends GuardaSsrf {
        @Override
        boolean proibido(InetAddress endereco) {
            return !endereco.isLoopbackAddress() && super.proibido(endereco);
        }
    }
}

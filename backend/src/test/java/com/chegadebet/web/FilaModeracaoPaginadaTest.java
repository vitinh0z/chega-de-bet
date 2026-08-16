package com.chegadebet.web;

import com.chegadebet.TestcontainersConfiguration;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.Moderador;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.ModeradorRepository;
import com.chegadebet.repository.SinalScrapingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paginação da fila de moderação, por HTTP de verdade.
 * <p>
 * As anotações são idênticas às de {@code SegurancaModeracaoTest} de propósito: com a
 * mesma configuração, o Spring reaproveita o contexto já em cache em vez de subir um
 * segundo — e subir contexto é o que domina o tempo desta suíte.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class FilaModeracaoPaginadaTest {

    private static final String SENHA = "senha-de-teste-longa";

    @LocalServerPort
    private int porta;

    @Autowired
    private ModeradorRepository moderadorRepository;
    @Autowired
    private DominioRepository dominioRepository;
    @Autowired
    private DenunciaRepository denunciaRepository;
    @Autowired
    private SinalScrapingRepository sinalScrapingRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void preparar() {
        sinalScrapingRepository.deleteAll();
        denunciaRepository.deleteAll();
        dominioRepository.deleteAll();
        moderadorRepository.deleteAll();

        Moderador moderador = new Moderador();
        moderador.setLogin("ana");
        moderador.setSenhaHash(passwordEncoder.encode(SENHA));
        moderador.setAtivo(true);
        moderadorRepository.save(moderador);
    }

    @Test
    @DisplayName("A fila vem paginada, com os totais para o cliente navegar")
    void filaVemPaginada() throws Exception {
        criarFila(7);

        JsonNode corpo = json.readTree(pedirFila("?pagina=0&tamanho=5").body());

        assertThat(corpo.get("itens")).hasSize(5);
        assertThat(corpo.get("pagina").asInt()).isZero();
        assertThat(corpo.get("tamanho").asInt()).isEqualTo(5);
        assertThat(corpo.get("totalDeItens").asLong()).isEqualTo(7);
        assertThat(corpo.get("totalDePaginas").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("A última página traz o resto, e não uma página cheia")
    void ultimaPaginaTrazOResto() throws Exception {
        criarFila(7);

        JsonNode corpo = json.readTree(pedirFila("?pagina=1&tamanho=5").body());

        assertThat(corpo.get("itens")).hasSize(2);
        assertThat(corpo.get("pagina").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sem parâmetro nenhum, a resposta continua sendo uma página válida")
    void temDefaultsSensatos() throws Exception {
        criarFila(3);

        JsonNode corpo = json.readTree(pedirFila("").body());

        assertThat(corpo.get("itens")).hasSize(3);
        assertThat(corpo.get("tamanho").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("Nenhum domínio aparece em duas páginas nem some entre elas")
    void paginacaoNaoDuplicaNemPerdeDominio() throws Exception {
        // Todos com o MESMO score: é o caso em que só o desempate por data separa as
        // páginas. Sem ele, o Postgres não promete ordem entre linhas de mesma chave, e
        // um domínio pode sair nas duas páginas — ou em nenhuma. Um domínio que some da
        // fila é um site de aposta que ninguém analisa.
        criarFila(10);

        List<String> vistos = new ArrayList<>();
        for (int pagina = 0; pagina < 5; pagina++) {
            JsonNode corpo = json.readTree(pedirFila("?pagina=" + pagina + "&tamanho=2").body());
            corpo.get("itens").forEach(item -> vistos.add(item.get("host").asText()));
        }

        assertThat(vistos).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Score maior vem primeiro; entre empates, o mais antigo")
    void ordenaPorScoreEDesempataPeloMaisAntigo() throws Exception {
        // O do meio tem score maior e foi criado por último: precisa liderar mesmo assim.
        criarDominio("antigo-empatado.com", 10, Instant.now().minusSeconds(3_600));
        criarDominio("novo-empatado.com", 10, Instant.now());
        criarDominio("lider.com", 99, Instant.now());

        JsonNode itens = json.readTree(pedirFila("?tamanho=10").body()).get("itens");

        assertThat(itens).hasSize(3);
        assertThat(itens.get(0).get("host").asText()).isEqualTo("lider.com");
        assertThat(itens.get(1).get("host").asText()).isEqualTo("antigo-empatado.com");
        assertThat(itens.get(2).get("host").asText()).isEqualTo("novo-empatado.com");
    }

    @Test
    @DisplayName("Tamanho de página acima do teto é recusado com 400, não atendido")
    void recusaTamanhoAcimaDoTeto() throws Exception {
        // Sem o teto, ?tamanho=1000000 reproduz exatamente o problema que a paginação veio
        // resolver, e qualquer pessoa autenticada derruba a aplicação com uma URL.
        HttpResponse<String> response = pedirFila("?tamanho=1000000");

        assertThat(response.statusCode()).isEqualTo(400);
        // 400 e não 500: o pedido é que estava fora do limite, o servidor não quebrou.
        assertThat(json.readTree(response.body()).get("mensagem").asText())
                .contains("200");
    }

    @Test
    @DisplayName("Página negativa é recusada com 400")
    void recusaPaginaNegativa() throws Exception {
        assertThat(pedirFila("?pagina=-1").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("Tamanho zero é recusado: uma página sem itens não é uma página")
    void recusaTamanhoZero() throws Exception {
        assertThat(pedirFila("?tamanho=0").statusCode()).isEqualTo(400);
    }

    // ===== Apoio =====

    private void criarFila(int quantos) {
        for (int i = 0; i < quantos; i++) {
            criarDominio("alvo-" + i + ".com", 10, Instant.now().plusSeconds(i));
        }
    }

    /**
     * Cria um domínio com data de criação controlada.
     * <p>
     * A data vai por SQL depois do insert porque {@code Dominio.criadoEm} é
     * {@code @CreationTimestamp}: o Hibernate sobrescreve, no flush, qualquer valor que a
     * entidade traga. Em produção é o que se quer — ninguém escolhe a data de entrada na
     * fila. Aqui o teste precisa dela fixa, senão o desempate testaria o relógio.
     */
    private void criarDominio(String host, int score, Instant criadoEm) {
        Dominio dominio = new Dominio();
        dominio.setHost(host);
        dominio.setStatus(StatusDominio.EM_ANALISE);
        dominio.setScore(score);
        Dominio salvo = dominioRepository.save(dominio);

        jdbcTemplate.update("UPDATE dominio SET criado_em = ? WHERE id = ?",
                java.sql.Timestamp.from(criadoEm), salvo.getId());
    }

    private HttpResponse<String> pedirFila(String query) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + porta + "/api/moderacao/fila" + query))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(("ana:" + SENHA).getBytes(StandardCharsets.UTF_8)))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

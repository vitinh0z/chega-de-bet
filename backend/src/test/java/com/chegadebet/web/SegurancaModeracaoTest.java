package com.chegadebet.web;

import com.chegadebet.TestcontainersConfiguration;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.Moderador;
import com.chegadebet.repository.DecisaoModeracaoRepository;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.ModeradorRepository;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Segurança testada por HTTP de verdade, com a cadeia de filtros inteira no caminho.
 * <p>
 * O Boot 4 tirou {@code @WebMvcTest} e {@code TestRestTemplate} do
 * {@code spring-boot-starter-test}, então o cliente aqui é o {@code HttpClient} do
 * próprio JDK — sem dependência nova e sem mock nenhum entre o teste e o filtro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 0 = porta de gestão aleatória: a 8081 fixa do application.yml colidiria
        // entre execuções paralelas.
        properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class SegurancaModeracaoTest {

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
    private DecisaoModeracaoRepository decisaoRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void limparEPreparar() {
        decisaoRepository.deleteAll();
        denunciaRepository.deleteAll();
        dominioRepository.deleteAll();
        moderadorRepository.deleteAll();

        criarModerador("ana", true);
        criarModerador("desligada", false);
    }

    private void criarModerador(String login, boolean ativo) {
        Moderador moderador = new Moderador();
        moderador.setLogin(login);
        moderador.setSenhaHash(passwordEncoder.encode(SENHA));
        moderador.setAtivo(ativo);
        moderadorRepository.save(moderador);
    }

    private Dominio dominioEmAnalise(String host) {
        Dominio dominio = new Dominio();
        dominio.setHost(host);
        dominio.setStatus(StatusDominio.EM_ANALISE);
        dominio.setScore(0);
        return dominioRepository.save(dominio);
    }

    private String basic(String login, String senha) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((login + ":" + senha).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> enviar(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder para(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
    }

    @Test
    @DisplayName("a fila de moderação recusa quem não se autentica")
    void filaExigeAutenticacao() throws Exception {
        HttpResponse<String> response = enviar(para("/api/moderacao/fila").GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("senha errada não entra")
    void senhaErradaRecusada() throws Exception {
        HttpResponse<String> response = enviar(para("/api/moderacao/fila")
                .header("Authorization", basic("ana", "senha-errada"))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("login inexistente não entra")
    void loginInexistenteRecusado() throws Exception {
        HttpResponse<String> response = enviar(para("/api/moderacao/fila")
                .header("Authorization", basic("ninguem", SENHA))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("moderador desligado perde o acesso sem precisar apagar a conta")
    void moderadorInativoRecusado() throws Exception {
        HttpResponse<String> response = enviar(para("/api/moderacao/fila")
                .header("Authorization", basic("desligada", SENHA))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("moderador ativo enxerga a fila")
    void moderadorAtivoAcessaFila() throws Exception {
        HttpResponse<String> response = enviar(para("/api/moderacao/fila")
                .header("Authorization", basic("ana", SENHA))
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a decisão registra quem se autenticou, e o X-Moderador é ignorado")
    void identidadeVemDaAutenticacaoNaoDoCabecalho() throws Exception {
        Dominio dominio = dominioEmAnalise("apostaqui.com");

        HttpResponse<String> response = enviar(
                para("/api/moderacao/dominios/" + dominio.getId() + "/aprovacao")
                        .header("Authorization", basic("ana", SENHA))
                        // O cabeçalho antigo continua sendo enviado de propósito: o teste
                        // existe para provar que ele não decide mais nada.
                        .header("X-Moderador", "bruno")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(decisaoRepository.existsByDominioAndModerador(dominio, "ana")).isTrue();
        assertThat(decisaoRepository.existsByDominioAndModerador(dominio, "bruno")).isFalse();
    }

    @Test
    @DisplayName("aprovar sem autenticação não muda o status do domínio")
    void aprovarSemAutenticacaoNaoAlteraNada() throws Exception {
        Dominio dominio = dominioEmAnalise("apostaqui.com");

        HttpResponse<String> response = enviar(
                para("/api/moderacao/dominios/" + dominio.getId() + "/aprovacao")
                        .header("X-Moderador", "invasor")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(dominioRepository.findById(dominio.getId()))
                .get()
                .extracting(Dominio::getStatus)
                .isEqualTo(StatusDominio.EM_ANALISE);
    }

    @Test
    @DisplayName("emitir token continua anônimo")
    void emissaoDeTokenSegueAnonima() throws Exception {
        HttpResponse<String> response = enviar(para("/api/tokens")
                .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("denunciar continua anônimo: erro de validação, nunca 401")
    void denunciaSegueAnonima() throws Exception {
        HttpResponse<String> response = enviar(para("/api/denuncias")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build());

        // 400 pela validação do corpo prova que a requisição passou pela segurança.
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("consulta pública de domínio continua anônima")
    void consultaDeDominioSegueAnonima() throws Exception {
        dominioEmAnalise("apostaqui.com");

        HttpResponse<String> response = enviar(
                para("/api/denuncias/dominios/apostaqui.com").GET().build());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("o Actuator não responde mais na porta pública")
    void actuatorForaDaPortaPublica() throws Exception {
        HttpResponse<String> response = enviar(para("/actuator/prometheus").GET().build());

        // Serve só na porta de gestão, que o compose não publica.
        assertThat(response.statusCode()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("rota desconhecida não vaza: nada nasce liberado")
    void rotaDesconhecidaNegada() throws Exception {
        HttpResponse<String> response = enviar(
                para("/api/qualquer-coisa/" + UUID.randomUUID()).GET().build());

        assertThat(response.statusCode()).isIn(401, 403, 404);
    }
}

package com.chegadebet.service.scraping;

import com.chegadebet.TestcontainersConfiguration;
import com.chegadebet.config.ScraperProperties;
import com.chegadebet.domain.enums.MotivoFalhaScraping;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.enums.TipoSinalScraping;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.SinalScraping;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.SinalScrapingRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O fluxo inteiro: domínio na fila, worker roda, evidência gravada, score reordenado.
 * <p>
 * Postgres de verdade via Testcontainers, e um servidor HTTP local no lugar da internet.
 * Nenhuma requisição sai da máquina.
 *
 * <h2>O que este teste protege</h2>
 * A garantia central do projeto: <b>nenhuma medição muda o status de um domínio</b>. Os
 * testes de unidade provam cada peça isoladamente; só aqui dá para afirmar que a
 * composição delas — cliente, matcher, persistência e recálculo de score — também não
 * abre um caminho automático até a blocklist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({TestcontainersConfiguration.class, ScrapingWorkerFimAFimTest.ConfiguracaoDeTeste.class})
@TestPropertySource(properties = {
        // Intervalo longo: o ciclo é disparado à mão nos testes. Com o intervalo de
        // produção, uma execução agendada cairia no meio de uma asserção.
        "chegadebet.scraper.intervalo=PT1H",
        // Uma tentativa só: o cenário de falha usa 503, que é transitório, e a repetição
        // com espera só faria o teste demorar sem provar nada a mais.
        "chegadebet.scraper.max-tentativas=1",
        "chegadebet.scraper.cooldown=PT24H"
})
class ScrapingWorkerFimAFimTest {

    private static final String ALVO_COM_SINAIS = "alvo-teste-um.com";
    private static final String ALVO_QUE_FALHA = "quebrado-teste.com";
    private static final String ALVO_PROTEGIDO = "noticias.gov.br";

    /**
     * Para onde cada host de teste aponta no servidor local.
     * <p>
     * É o único ponto em que o teste se afasta da produção: o {@code DomainScraperClient}
     * de verdade continua fazendo HTTP de verdade, seguindo redirecionamento de verdade e
     * cortando no teto de bytes de verdade — só o mapeamento host para URL é substituído,
     * porque a alternativa seria depender de DNS público.
     */
    private static final Map<String, String> ROTAS = Map.of(
            ALVO_COM_SINAIS, "/ok",
            ALVO_QUE_FALHA, "/503");

    private static ServidorDeTeste servidor;

    @Autowired
    private ScrapingWorker worker;
    @Autowired
    private DominioRepository dominioRepository;
    @Autowired
    private SinalScrapingRepository sinalScrapingRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void subirServidor() throws IOException {
        servidor = new ServidorDeTeste();
    }

    @AfterAll
    static void derrubarServidor() {
        servidor.close();
    }

    @BeforeEach
    void limpar() {
        sinalScrapingRepository.deleteAll();
        dominioRepository.deleteAll();
    }

    // ===== Cenário 1: sinais fortes sobem o score e NÃO mudam o status =====

    @Test
    @DisplayName("Domínio com assinaturas conhecidas ganha score e continua EM_ANALISE")
    void medicaoComSinaisSobeOScoreSemDecidirNada() {
        Dominio dominio = criarEmAnalise(ALVO_COM_SINAIS);

        worker.executarCiclo();

        SinalScraping sinal = ultimaMedicaoDe(dominio);
        assertThat(sinal.isSucesso()).isTrue();
        assertThat(sinal.getBytesBaixados()).isPositive();
        assertThat(sinal.getAssinaturas())
                .extracting(AssinaturaEncontrada::tipo)
                // O HTML do servidor de teste traz os três: "Cassino online" no título,
                // "depósito via Pix" e "Pragmatic Play" no corpo.
                .contains(TipoSinalScraping.PALAVRA_CHAVE,
                        TipoSinalScraping.KYC_DEPOSITO,
                        TipoSinalScraping.PROVEDOR_SLOTS);

        Dominio recarregado = dominioRepository.findById(dominio.getId()).orElseThrow();
        // 5 (palavra-chave) + 15 (KYC/depósito) + 30 (provedor de slots). O número exato
        // está aqui de propósito: mudar a política de pesos é uma decisão de moderação, e
        // deve exigir mexer neste teste conscientemente.
        assertThat(recarregado.getScore()).isEqualTo(50);

        // A afirmação que sustenta o projeto: score alto, zero decisão.
        assertThat(recarregado.getStatus()).isEqualTo(StatusDominio.EM_ANALISE);
    }

    // ===== Cenário 2: falha técnica é neutra e o cooldown vale =====

    @Test
    @DisplayName("Falha técnica não mexe no score e fica registrada como falha")
    void falhaTecnicaNaoPenalizaODominio() {
        Dominio dominio = criarEmAnalise(ALVO_QUE_FALHA);

        worker.executarCiclo();

        SinalScraping sinal = ultimaMedicaoDe(dominio);
        assertThat(sinal.isSucesso()).isFalse();
        assertThat(sinal.getMotivoFalha()).isEqualTo(MotivoFalhaScraping.HTTP_5XX);

        Dominio recarregado = dominioRepository.findById(dominio.getId()).orElseThrow();
        // Zero, e não um valor negativo: uma casa de aposta atrás de um servidor instável
        // não é menos casa de aposta por isso.
        assertThat(recarregado.getScore()).isZero();
        assertThat(recarregado.getStatus()).isEqualTo(StatusDominio.EM_ANALISE);
    }

    @Test
    @DisplayName("Dentro do cooldown o mesmo domínio não é medido de novo")
    void respeitaOCooldownEntreCiclos() {
        Dominio dominio = criarEmAnalise(ALVO_QUE_FALHA);

        worker.executarCiclo();
        worker.executarCiclo();

        // Sem cooldown, a fila EM_ANALISE seria inteiramente re-raspada a cada ciclo: ela
        // não esvazia sozinha, porque só o moderador tira domínio de lá.
        assertThat(sinalScrapingRepository.findByDominioOrderByCriadoEmDesc(dominio)).hasSize(1);
    }

    @Test
    @DisplayName("Passado o cooldown, o domínio volta a ser elegível")
    void voltaAFilaDepoisDoCooldown() {
        Dominio dominio = criarEmAnalise(ALVO_QUE_FALHA);
        worker.executarCiclo();

        // Envelhece a medição para além do cooldown, em vez de esperar 24 horas.
        envelhecerMedicoes(dominio);
        worker.executarCiclo();

        assertThat(sinalScrapingRepository.findByDominioOrderByCriadoEmDesc(dominio)).hasSize(2);
    }

    // ===== Cenário 3: a allowlist de proteção nunca é raspada =====

    @Test
    @DisplayName("Domínio sob a allowlist de proteção não entra na fila de pré-análise")
    void naoRaspaDominioProtegido() {
        Dominio protegido = criarEmAnalise(ALVO_PROTEGIDO);

        worker.executarCiclo();

        // Nem uma tentativa. Jornalismo, saúde, apoio a dependente químico e órgão público
        // não são medidos "só para conferir": bloquear um deles por engano é a falha
        // crítica do projeto, e a pré-análise não tem nada a acrescentar sobre eles.
        assertThat(sinalScrapingRepository.findByDominioOrderByCriadoEmDesc(protegido)).isEmpty();
        assertThat(dominioRepository.findById(protegido.getId()).orElseThrow().getScore()).isZero();
    }

    @Test
    @DisplayName("Aprovado e rejeitado ficam fora da fila: já têm decisão humana")
    void soMedeDominioEmAnalise() {
        Dominio aprovado = criar(ALVO_COM_SINAIS, StatusDominio.APROVADO);
        Dominio rejeitado = criar(ALVO_QUE_FALHA, StatusDominio.REJEITADO);

        worker.executarCiclo();

        assertThat(sinalScrapingRepository.findByDominioOrderByCriadoEmDesc(aprovado)).isEmpty();
        assertThat(sinalScrapingRepository.findByDominioOrderByCriadoEmDesc(rejeitado)).isEmpty();
    }

    // ===== Um alvo ruim não derruba o lote =====

    @Test
    @DisplayName("Falha em um domínio não impede a medição dos outros do lote")
    void umaFalhaNaoDerrubaOLote() {
        Dominio bom = criarEmAnalise(ALVO_COM_SINAIS);
        Dominio ruim = criarEmAnalise(ALVO_QUE_FALHA);

        worker.executarCiclo();

        assertThat(ultimaMedicaoDe(bom).isSucesso()).isTrue();
        assertThat(ultimaMedicaoDe(ruim).isSucesso()).isFalse();
    }

    // ===== Apoio =====

    private Dominio criarEmAnalise(String host) {
        return criar(host, StatusDominio.EM_ANALISE);
    }

    private Dominio criar(String host, StatusDominio status) {
        Dominio dominio = new Dominio();
        dominio.setHost(host);
        dominio.setStatus(status);
        dominio.setScore(0);
        return dominioRepository.save(dominio);
    }

    private SinalScraping ultimaMedicaoDe(Dominio dominio) {
        List<SinalScraping> historico = sinalScrapingRepository.findByDominioOrderByCriadoEmDesc(dominio);
        assertThat(historico).as("medição de %s", dominio.getHost()).isNotEmpty();
        return historico.getFirst();
    }

    /**
     * Empurra as medições do domínio para trás do cooldown.
     * <p>
     * Direto em SQL porque {@code criado_em} é {@code @CreationTimestamp updatable=false}:
     * a entidade recusa a alteração, e é isso que se quer em produção — a data de uma
     * medição gravada não pode ser reescrita depois. O teste precisa contornar a regra, e
     * não afrouxá-la.
     */
    private void envelhecerMedicoes(Dominio dominio) {
        jdbcTemplate.update("UPDATE sinal_scraping SET criado_em = ? WHERE dominio_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(2))),
                dominio.getId());
    }

    @TestConfiguration
    static class ConfiguracaoDeTeste {

        /**
         * O cliente de produção, apontado para o servidor local.
         * <p>
         * Sobrescreve apenas a montagem da URL. Todo o resto — o guarda de SSRF a cada
         * hop, o teto de bytes, o {@code Range}, o tratamento de status — continua sendo o
         * código que roda em produção.
         */
        @Bean
        @Primary
        DomainScraperClient clienteApontadoParaOServidorLocal(ScraperProperties propriedades) {
            return new DomainScraperClient(new GuardaComLoopbackLiberado(), propriedades) {
                @Override
                public RespostaScraping buscar(String host, Predicate<String> hostConhecido) {
                    // Host sem rota cai em 404, que é o que um domínio fora do ar faria.
                    return buscar(servidor.uri(ROTAS.getOrDefault(host, "/404")), hostConhecido);
                }
            };
        }
    }

    /** Igual ao de {@code DomainScraperClientTest}: só o loopback do servidor local passa. */
    private static class GuardaComLoopbackLiberado extends GuardaSsrf {
        @Override
        boolean proibido(InetAddress endereco) {
            return !endereco.isLoopbackAddress() && super.proibido(endereco);
        }
    }
}

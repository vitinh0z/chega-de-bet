package com.chegadebet.service.scraping;

import com.chegadebet.config.ScraperProperties;
import com.chegadebet.domain.enums.MotivoFalhaScraping;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import com.chegadebet.domain.scraping.ResultadoScraping;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.service.ProtecaoAllowlist;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drena a fila de domínios em {@code EM_ANALISE}, mede cada um e grava a evidência.
 *
 * <h2>O que este worker não faz</h2>
 * Ele não aprova, não rejeita e não muda o status de domínio nenhum. Não existe caminho de
 * código daqui até {@code ModeracaoService.aprovar} ou {@code rejeitar} — o único efeito
 * que uma medição tem sobre a fila é reordená-la pelo score. Essa ausência é o desenho, e
 * não uma etapa que faltou implementar: um site legítimo bloqueado por engano é a falha
 * crítica deste projeto, e nenhuma heurística é boa o bastante para causá-la sozinha.
 *
 * <h2>Rede em paralelo, banco em sequência</h2>
 * A parte cara é a espera de rede, e ela roda em virtual threads limitadas por
 * {@code concorrencia}. A gravação acontece depois, um domínio por transação, na thread do
 * próprio worker. A separação não é estilo: entidades JPA e transações são presas à
 * thread, e persistir de dentro das threads de rede espalharia o contexto de persistência
 * por threads que o Spring não controla.
 *
 * <h2>Falha de um não derruba os outros</h2>
 * Cada domínio é medido e gravado em isolamento. Uma exceção inesperada em um deles vira
 * uma linha de log e um contador de erro; os demais itens do lote seguem. Um worker
 * agendado que morre por causa de um alvo hostil pararia de rodar em silêncio até alguém
 * notar semanas depois.
 */
@Component
public class ScrapingWorker {

    private static final Logger log = LoggerFactory.getLogger(ScrapingWorker.class);

    private final DominioRepository dominioRepository;
    private final DomainScraperClient scraperClient;
    private final AssinaturaMatcher matcher;
    private final ProtecaoAllowlist protecaoAllowlist;
    private final RegistroPreAnalise registro;
    private final ScraperProperties propriedades;

    private final MeterRegistry metricas;
    private final Timer duracaoDaAnalise;
    private final DistributionSummary bytesBaixados;
    private final Counter errosInesperados;
    // Gauge alimentado a cada ciclo, e não por consulta sob demanda: um gauge que consulta
    // o banco é executado toda vez que o Prometheus raspa a métrica, e passaria a fazer um
    // COUNT na fila de moderação a cada quinze segundos, para sempre.
    private final AtomicInteger tamanhoDaFila = new AtomicInteger();

    public ScrapingWorker(DominioRepository dominioRepository,
                          DomainScraperClient scraperClient,
                          AssinaturaMatcher matcher,
                          ProtecaoAllowlist protecaoAllowlist,
                          RegistroPreAnalise registro,
                          ScraperProperties propriedades,
                          MeterRegistry metricas) {
        this.dominioRepository = dominioRepository;
        this.scraperClient = scraperClient;
        this.matcher = matcher;
        this.protecaoAllowlist = protecaoAllowlist;
        this.registro = registro;
        this.propriedades = propriedades;
        this.metricas = metricas;

        this.duracaoDaAnalise = Timer.builder("chegadebet.preanalise.duracao")
                .description("Tempo de uma pré-análise, incluindo repetições")
                .register(metricas);
        this.bytesBaixados = DistributionSummary.builder("chegadebet.preanalise.bytes")
                .description("Bytes baixados por pré-análise. Comparado ao teto, mostra quem está sendo cortado")
                .baseUnit("bytes")
                .register(metricas);
        this.errosInesperados = Counter.builder("chegadebet.preanalise.erros")
                .description("Exceções não previstas durante uma pré-análise. Deve ficar em zero")
                .register(metricas);
        metricas.gauge("chegadebet.preanalise.fila", tamanhoDaFila, AtomicInteger::get);
    }

    /**
     * Um ciclo: seleciona o lote elegível, mede e grava.
     * <p>
     * {@code fixedDelay} e não {@code fixedRate}: o intervalo conta a partir do <b>fim</b>
     * da execução anterior. Com {@code fixedRate}, um lote lento sobreporia o próximo, e a
     * concorrência real viraria um múltiplo da configurada — exatamente o que o limite
     * existe para impedir.
     * <p>
     * Depende de {@code @EnableScheduling} na classe de aplicação. Sem ela, este método
     * nunca roda e não há erro nenhum no log.
     */
    @Scheduled(fixedDelayString = "${chegadebet.scraper.intervalo}")
    public void executarCiclo() {
        if (!propriedades.habilitado()) {
            return;
        }

        List<Alvo> alvos = selecionarLote();
        if (alvos.isEmpty()) {
            return;
        }

        log.info("Pré-análise: ciclo iniciado com {} domínios", alvos.size());
        Instant inicioDoCiclo = Instant.now();

        for (Medicao medicao : medirEmParalelo(alvos)) {
            gravarComIsolamento(medicao);
        }

        log.info("Pré-análise: ciclo concluído em {} ms",
                Duration.between(inicioDoCiclo, Instant.now()).toMillis());
    }

    /**
     * Escolhe os domínios do ciclo.
     * <p>
     * O cooldown e a ordem vêm do banco (ver
     * {@code DominioRepository.buscarElegiveisParaPreAnalise}). A allowlist de proteção é
     * aplicada aqui, em memória, porque ela casa por sufixo — {@code gov.br} protege
     * {@code noticias.gov.br} — e isso não vira um {@code WHERE} sem um {@code LIKE} por
     * entrada da lista.
     * <p>
     * Sem {@code @Transactional} de propósito. A anotação seria ignorada, porque este
     * método é chamado de dentro da própria classe e não passa pelo proxy do Spring — e
     * uma anotação que não faz nada é pior que nenhuma, porque parece garantir algo. Não
     * há o que garantir aqui: é uma consulta só, que abre a própria transação, e o método
     * devolve {@link Alvo}, que são valores puros. Nenhuma entidade JPA atravessa para as
     * threads de rede.
     */
    private List<Alvo> selecionarLote() {
        Instant limite = Instant.now().minus(propriedades.cooldown());
        List<Dominio> elegiveis = dominioRepository.buscarElegiveisParaPreAnalise(
                StatusDominio.EM_ANALISE, limite, PageRequest.of(0, propriedades.loteMaximo()));

        tamanhoDaFila.set(elegiveis.size());

        List<Alvo> alvos = new ArrayList<>(elegiveis.size());
        for (Dominio dominio : elegiveis) {
            if (protecaoAllowlist.protege(dominio.getHost())) {
                // Um site de jornalismo, de saúde ou um órgão público não é raspado, nem
                // para "só conferir". Bloquear um deles por engano é a falha crítica do
                // projeto, e a pré-análise não tem nada a acrescentar sobre eles.
                log.debug("Pré-análise pulada: {} está sob a allowlist de proteção", dominio.getHost());
                continue;
            }
            alvos.add(new Alvo(dominio.getId(), dominio.getHost()));
        }
        return alvos;
    }

    /**
     * Mede todos os alvos, no máximo {@code concorrencia} ao mesmo tempo.
     * <p>
     * O executor tem escopo do ciclo: {@code close()} espera as tarefas terminarem, e o
     * try-with-resources garante isso mesmo se algo estourar no meio. Um executor de campo,
     * vivo entre ciclos, precisaria de desligamento próprio no shutdown da aplicação.
     */
    private List<Medicao> medirEmParalelo(List<Alvo> alvos) {
        List<Medicao> medicoes = new ArrayList<>(alvos.size());

        // Virtual threads: a tarefa é quase toda espera de rede. Com threads de plataforma,
        // 'concorrencia' viraria esse número de threads do sistema paradas esperando socket.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Semaphore vagas =
                    new java.util.concurrent.Semaphore(propriedades.concorrencia());

            List<Future<Medicao>> pendentes = alvos.stream()
                    .map(alvo -> executor.submit(() -> {
                        // O semáforo, e não o tamanho do pool, é o limite: um executor de
                        // virtual threads não tem tamanho, ele cria uma thread por tarefa.
                        vagas.acquire();
                        try {
                            return new Medicao(alvo, analisarComRepeticao(alvo));
                        } finally {
                            vagas.release();
                        }
                    }))
                    .toList();

            for (Future<Medicao> pendente : pendentes) {
                try {
                    medicoes.add(pendente.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    // A medição de um alvo estourou de um jeito que analisarComRepeticao
                    // não previu. Fica sem resultado, e o lote continua.
                    errosInesperados.increment();
                    log.error("Pré-análise falhou de forma inesperada", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Pré-análise interrompida: o ciclo será encerrado");
                    break;
                }
            }
        }
        return medicoes;
    }

    /**
     * Mede um domínio, repetindo apenas quando vale a pena.
     * <p>
     * A decisão de repetir é do próprio motivo da falha
     * ({@link MotivoFalhaScraping#isTransitoria()}), e não de uma lista aqui dentro.
     * Timeout e conexão recusada são rede ruim e passam; DNS que não resolve e 403 não
     * mudam por insistência — e no caso do 403 a insistência é hostil com o alvo.
     */
    private ResultadoScraping analisarComRepeticao(Alvo alvo) {
        Instant inicio = Instant.now();
        ResultadoScraping resultado = null;

        for (int tentativa = 1; tentativa <= propriedades.maxTentativas(); tentativa++) {
            resultado = analisar(alvo, inicio);

            if (resultado.sucesso() || !resultado.motivoFalha().isTransitoria()) {
                break;
            }
            if (tentativa < propriedades.maxTentativas()) {
                if (!esperar(propriedades.esperaEntreTentativas())) {
                    break;
                }
                log.debug("Pré-análise de {}: repetindo após {}", alvo.host(), resultado.motivoFalha());
            }
        }

        registrarMetricas(resultado);
        return resultado;
    }

    private ResultadoScraping analisar(Alvo alvo, Instant inicio) {
        // Assinaturas do nome do domínio: custo zero, e valem mesmo quando a requisição
        // falha. Um domínio .bet.br que recusa a conexão continua sendo um .bet.br.
        List<AssinaturaEncontrada> assinaturas = new ArrayList<>(matcher.analisarHost(alvo.host()));

        RespostaScraping resposta = scraperClient.buscar(alvo.host(), this::hostJaAprovado);

        return switch (resposta) {
            case RespostaScraping.Falha(MotivoFalhaScraping motivo, String urlFinal) ->
                    new ResultadoScraping(alvo.host(), false, motivo, assinaturas, 0, urlFinal,
                            false, Instant.now(), Duration.between(inicio, Instant.now()));

            case RespostaScraping.Documento(String html, String urlFinal, int bytes, boolean cortado) -> {
                assinaturas.addAll(matcher.analisarConteudo(html));
                // O destino do redirecionamento também é um host, e um host revela tanto
                // quanto o conteúdo: uma página de afiliado inocente que termina em um
                // .bet.br entregou a informação mais importante na URL, não no HTML.
                assinaturas.addAll(matcher.analisarHost(hostDe(urlFinal, alvo.host())));

                // Corte em hop conhecido não baixou corpo nenhum, então perguntar se o
                // documento está vazio não faz sentido: não há documento.
                boolean vazio = !cortado && matcher.pareceDocumentoVazio(html);

                yield new ResultadoScraping(alvo.host(), true, null, assinaturas, bytes, urlFinal,
                        vazio, Instant.now(), Duration.between(inicio, Instant.now()));
            }
        };
    }

    /**
     * Grava uma medição sem deixar a falha escapar para o laço do ciclo.
     * <p>
     * O {@code catch (RuntimeException)} é largo de propósito. Aqui o custo de engolir um
     * erro é uma medição perdida e uma linha de log; o custo de deixá-lo subir é o ciclo
     * inteiro morrer e o worker parar de medir a fila — em silêncio, porque
     * {@code @Scheduled} apenas agenda a próxima execução.
     */
    private void gravarComIsolamento(Medicao medicao) {
        try {
            registro.registrar(medicao.alvo().id(), medicao.resultado());
            registrarLog(medicao.resultado());
        } catch (RuntimeException e) {
            errosInesperados.increment();
            log.error("Falha ao gravar a pré-análise de {}", medicao.alvo().host(), e);
        }
    }

    /**
     * Se este host já é um domínio aprovado do projeto.
     * <p>
     * Chamado a cada hop de redirecionamento. Quando responde sim, o cliente para ali sem
     * baixar corpo nenhum: já sabemos o que há no destino, e o que interessa da cadeia é
     * onde ela termina.
     */
    private boolean hostJaAprovado(String host) {
        return dominioRepository.existsByHostAndStatus(
                host.toLowerCase(java.util.Locale.ROOT), StatusDominio.APROVADO);
    }

    private String hostDe(String url, String padrao) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? padrao : host;
        } catch (IllegalArgumentException e) {
            return padrao;
        }
    }

    private boolean esperar(Duration espera) {
        try {
            Thread.sleep(espera);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void registrarMetricas(ResultadoScraping resultado) {
        duracaoDaAnalise.record(resultado.duracao());
        bytesBaixados.record(resultado.bytesBaixados());

        // Uma tag por motivo, com "sucesso" como valor quando deu certo. Uma métrica só,
        // com cardinalidade limitada pelo enum, em vez de um contador por caso.
        String motivo = resultado.sucesso() ? "sucesso" : resultado.motivoFalha().name();
        Counter.builder("chegadebet.preanalise.execucoes")
                .description("Pré-análises concluídas, por desfecho")
                .tag("motivo", motivo)
                .tag("indeterminado", String.valueOf(resultado.indeterminado()))
                .register(metricas)
                .increment();
    }

    /**
     * Uma linha por medição, para o Loki.
     * <p>
     * Domínio, desfecho e duração. Não há como um dado de denunciante entrar aqui: o
     * worker parte de um {@link Alvo}, que tem id e host, e nunca chegou perto de uma
     * denúncia ou de um token.
     */
    private void registrarLog(ResultadoScraping resultado) {
        log.info("Pré-análise concluída host={} sucesso={} motivo={} assinaturas={} bytes={} duracao_ms={} indeterminado={}",
                resultado.host(),
                resultado.sucesso(),
                resultado.motivoFalha(),
                resultado.assinaturas().size(),
                resultado.bytesBaixados(),
                resultado.duracao().toMillis(),
                resultado.indeterminado());
    }

    /** Um domínio a medir, já fora do contexto de persistência. */
    private record Alvo(UUID id, String host) {
    }

    /** O par alvo/resultado, entre a fase de rede e a de gravação. */
    private record Medicao(Alvo alvo, ResultadoScraping resultado) {
    }
}

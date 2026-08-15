package com.chegadebet.service;

import com.chegadebet.config.ModeracaoProperties;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.enums.TipoDecisao;
import com.chegadebet.domain.enums.TipoSinalScraping;
import com.chegadebet.domain.model.DecisaoModeracao;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.SinalScraping;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import com.chegadebet.exception.EstadoInvalidoException;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.mapper.DominioMapper;
import com.chegadebet.repository.ContagemDenunciantes;
import com.chegadebet.repository.DecisaoModeracaoRepository;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.SinalScrapingRepository;
import com.chegadebet.web.dto.DominioResponse;
import com.chegadebet.web.dto.PaginaResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Regra de negócio da moderação: o que entra na blocklist e o que é descartado.
 * <p>
 * Princípio permanente: um domínio só sai de {@link StatusDominio#EM_ANALISE} por decisão
 * humana. Nenhuma rotina automática pode chamar {@link #aprovar} ou {@link #rejeitar}.
 *
 * <h2>Score não é quórum</h2>
 * São grandezas diferentes e nenhum método aqui as mistura:
 * <ul>
 *   <li><b>Score</b> ordena a fila. Vem de quantas pessoas denunciaram e serve para o
 *       moderador saber o que olhar primeiro. Nunca decide status sozinho — um domínio
 *       com score 900 e zero votos continua {@code EM_ANALISE}.</li>
 *   <li><b>Quórum</b> é quantos moderadores humanos precisam concordar. Vem de decisões
 *       registradas em {@code decisao_moderacao}, não de denúncias.</li>
 * </ul>
 *
 * <h2>Cada decisão é uma linha</h2>
 * O histórico vive em {@code decisao_moderacao} (quem, quando, o quê, por quê), criada na
 * migration {@code V3}. Guardar só o status final em {@code dominio} seria mais curto,
 * mas perderia a auditoria e não identificaria quem votou — e sem isso o quórum não teria
 * como contar moderadores distintos.
 */
@Service
public class ModeracaoService {

    // Fórmula do score: peso do volume + peso da recência + peso da pré-análise.
    //   score = (denunciantes distintos x 10)
    //         + (denunciantes distintos nos últimos 7 dias x 5)
    //         + (peso do tipo de cada sinal da última pré-análise)
    // Os pesos são arbitrários, mas a ordem entre eles é deliberada: o volume total é o
    // sinal principal e a recência entra como desempate — um domínio ativo esta semana
    // sobe na fila sem ultrapassar um caso muito mais denunciado.
    private static final int PESO_VOLUME = 10;
    private static final int PESO_RECENCIA = 5;
    private static final Duration JANELA_RECENCIA = Duration.ofDays(7);

    /**
     * A ordem da fila: maior score primeiro, e entre empates o mais antigo.
     * <p>
     * Constante e não parâmetro: qual domínio o moderador vê primeiro é regra de
     * moderação, não preferência de quem chama a API. Aceitar um {@code sort} da query
     * string permitiria pedir a fila pelo <i>menor</i> score — e a fila deixaria de
     * significar prioridade.
     * <p>
     * O índice {@code ix_dominio_fila_moderacao} espelha exatamente estas colunas, nesta
     * ordem e nestes sentidos. Mudar um sem o outro faz o banco voltar a ordenar em
     * memória, em silêncio.
     */
    private static final Sort ORDEM_DA_FILA =
            Sort.by(Sort.Order.desc("score"), Sort.Order.asc("criadoEm"));

    /**
     * Quanto cada tipo de evidência da pré-análise soma no score.
     * <p>
     * A escala segue a força medida do sinal, e não a intuição sobre a palavra. O sufixo
     * {@code .bet.br} é o topo porque é um fato registrado no domínio de primeiro nível,
     * não uma inferência. Provedor de slots e licenciadora vêm logo abaixo porque são
     * integrações e selos contratados — ninguém carrega o script do Pragmatic Play por
     * engano. A palavra-chave genérica fica no chão porque a medição a encontrou seis
     * vezes em um site de jornalismo.
     * <p>
     * Nada aqui muda status. O score só ordena a fila: um domínio com o topo da escala em
     * todos os tipos continua {@code EM_ANALISE} até um moderador decidir.
     */
    private static final Map<TipoSinalScraping, Integer> PESO_POR_SINAL = Map.of(
            TipoSinalScraping.DOMINIO_BET_BR, 40,
            TipoSinalScraping.PROVEDOR_SLOTS, 30,
            TipoSinalScraping.LICENCIADORA, 30,
            TipoSinalScraping.KYC_DEPOSITO, 15,
            TipoSinalScraping.PALAVRA_CHAVE, 5);

    private final DominioRepository dominioRepository;
    private final DenunciaRepository denunciaRepository;
    private final DecisaoModeracaoRepository decisaoRepository;
    private final SinalScrapingRepository sinalScrapingRepository;
    private final DominioMapper dominioMapper;
    private final ModeracaoProperties properties;
    private final ProtecaoAllowlist protecaoAllowlist;
    private final BlocklistPublisher blocklistPublisher;

    public ModeracaoService(DominioRepository dominioRepository,
                            DenunciaRepository denunciaRepository,
                            DecisaoModeracaoRepository decisaoRepository,
                            SinalScrapingRepository sinalScrapingRepository,
                            DominioMapper dominioMapper,
                            ModeracaoProperties properties,
                            ProtecaoAllowlist protecaoAllowlist,
                            BlocklistPublisher blocklistPublisher) {
        this.dominioRepository = dominioRepository;
        this.denunciaRepository = denunciaRepository;
        this.decisaoRepository = decisaoRepository;
        this.sinalScrapingRepository = sinalScrapingRepository;
        this.dominioMapper = dominioMapper;
        this.properties = properties;
        this.protecaoAllowlist = protecaoAllowlist;
        this.blocklistPublisher = blocklistPublisher;
    }

    /**
     * Uma página da fila de moderação: domínios em quarentena, do maior score para o menor.
     *
     * <h2>Por que o desempate por data é obrigatório</h2>
     * Ordenar só por {@code score} não define uma ordem total, porque scores empatam com
     * frequência — a fórmula é feita de múltiplos de 10 e 5. E SQL não promete ordem
     * nenhuma entre linhas de mesma chave: o Postgres pode devolver dois domínios de score
     * 30 em ordens diferentes em duas execuções da mesma consulta.
     * <p>
     * Sem paginação isso era inofensivo, porque a resposta trazia todo mundo. Com
     * {@code LIMIT/OFFSET} vira erro de verdade: se a ordem muda entre a página 0 e a
     * página 1, um domínio pode aparecer nas duas — ou em nenhuma. Um domínio que some da
     * fila de moderação é um site de aposta que ninguém analisa.
     * <p>
     * {@code criadoEm} desempata e ainda escolhe o critério justo: entre iguais, o que
     * está esperando há mais tempo vem primeiro.
     *
     * <h2>O que esta paginação não resolve</h2>
     * O score muda enquanto o moderador navega — cada denúncia nova e cada pré-análise
     * recalculam. Um domínio pode subir de página entre uma requisição e outra e ser visto
     * duas vezes, ou descer e ser pulado. Resolver isso pediria paginação por cursor sobre
     * uma chave imutável, e o preço seria perder o "pule para a página 5".
     * <p>
     * Para uma fila de trabalho humano a troca compensa: o moderador processa o topo, e o
     * que escapar de uma passagem continua na fila para a próxima — nada é perdido de
     * forma permanente, porque só a decisão humana tira domínio de {@code EM_ANALISE}.
     *
     * @param pagina  índice começando em zero
     * @param tamanho quantos itens por página
     */
    @Transactional(readOnly = true)
    public PaginaResponse<DominioResponse> listarFila(int pagina, int tamanho) {
        Pageable pageable = PageRequest.of(pagina, tamanho, ORDEM_DA_FILA);

        return PaginaResponse.de(
                dominioRepository.findByStatus(StatusDominio.EM_ANALISE, pageable)
                        .map(dominioMapper::toResponse));
    }

    /**
     * Registra o voto de aprovação deste moderador e, se o quórum fechar, aprova o
     * domínio — ele entra na blocklist na próxima publicação.
     * <p>
     * Domínio comum aprova com um voto. Domínio da allowlist de proteção exige
     * {@code chegadebet.moderacao.quorum-minimo} moderadores distintos: bloquear
     * jornalismo ou órgão público por engano é falha crítica.
     * <p>
     * É idempotente: chamar de novo em um domínio já aprovado devolve o estado atual sem
     * republicar na blocklist. O painel repete cliques.
     *
     * @param moderador quem decidiu — necessário para o quórum contar pessoas distintas
     * @throws RecursoNaoEncontradoException se o domínio não existir
     * @throws EstadoInvalidoException       se o domínio já tiver sido rejeitado. Aprovar
     *                                       por cima de uma rejeição auditada apagaria a
     *                                       decisão anterior em silêncio; o caminho certo
     *                                       é a reabertura descrita em {@link #rejeitar}.
     */
    @Transactional
    public DominioResponse aprovar(UUID dominioId, String moderador) {
        Dominio dominio = buscar(dominioId);

        if (dominio.getStatus() == StatusDominio.APROVADO) {
            return dominioMapper.toResponse(dominio);
        }
        if (dominio.getStatus() == StatusDominio.REJEITADO) {
            throw new EstadoInvalidoException(
                    "Domínio já rejeitado por decisão de moderação: " + dominio.getHost());
        }

        registrarDecisao(dominio, moderador, TipoDecisao.APROVACAO, null);

        long votos = decisaoRepository.countModeradoresDistintos(dominio, TipoDecisao.APROVACAO);
        int minimo = exigeQuorum(dominio) ? properties.quorumMinimo() : 1;

        // Quórum aberto: o voto ficou registrado, mas o domínio segue EM_ANALISE.
        // Repare que o score não entrou em nenhum passo desta decisão.
        if (votos < minimo) {
            return dominioMapper.toResponse(dominio);
        }

        dominio.setStatus(StatusDominio.APROVADO);
        Dominio salvo = dominioRepository.save(dominio);
        blocklistPublisher.publicar(salvo);
        return dominioMapper.toResponse(salvo);
    }

    /**
     * Rejeita o domínio: ele sai da fila e não entra na blocklist. Um voto basta.
     * <p>
     * A assimetria com {@link #aprovar} é deliberada. Aprovar bloqueia um site para todo
     * mundo, e o erro ali é falso positivo — falha crítica. Rejeitar só mantém o domínio
     * fora da blocklist, e o custo do erro é um site de aposta continuar acessível até
     * alguém denunciar de novo. Proteção maior vai no lado que causa dano maior.
     *
     * <h2>Redenúncia depois da rejeição</h2>
     * A rejeição não é definitiva: um site rejeitado hoje pode mudar de dono amanhã.
     * {@link #recalcularScore} devolve o domínio para {@code EM_ANALISE} quando
     * {@code chegadebet.moderacao.limiar-reabertura} denunciantes distintos novos
     * aparecem <b>depois</b> da última rejeição. O recorte é a data da decisão, e não o
     * score total, para que a mesma rejeição não seja reaberta na hora seguinte pelas
     * denúncias que já existiam quando ela foi tomada.
     * <p>
     * Isso não contraria "humano no loop": a regra move o domínio <i>para dentro</i> da
     * fila de análise, e quem decide continua sendo o moderador.
     *
     * @param motivo justificativa obrigatória, para a decisão ser auditável
     * @throws RecursoNaoEncontradoException se o domínio não existir
     * @throws EstadoInvalidoException       se o domínio já tiver sido aprovado — tirar
     *                                       da blocklist é um fluxo de desbloqueio, não
     *                                       um efeito colateral da rejeição
     */
    @Transactional
    public DominioResponse rejeitar(UUID dominioId, String moderador, String motivo) {
        Dominio dominio = buscar(dominioId);

        if (dominio.getStatus() == StatusDominio.REJEITADO) {
            return dominioMapper.toResponse(dominio);
        }
        if (dominio.getStatus() == StatusDominio.APROVADO) {
            throw new EstadoInvalidoException(
                    "Domínio já aprovado e publicado na blocklist: " + dominio.getHost());
        }

        registrarDecisao(dominio, moderador, TipoDecisao.REJEICAO, motivo);

        dominio.setStatus(StatusDominio.REJEITADO);
        return dominioMapper.toResponse(dominioRepository.save(dominio));
    }

    /**
     * Recalcula o score que ordena a fila. Sinal auxiliar de prioridade: ajuda o moderador
     * a decidir o que olhar primeiro, e nunca bloqueia um domínio sozinho.
     * <p>
     * A fórmula é
     * {@code (denunciantes distintos x 10) + (denunciantes distintos nos últimos 7 dias x 5)}.
     * Conta denunciantes <b>distintos</b>, nunca o total bruto de denúncias: o total é
     * fácil de inflar, e pseudônimos diferentes são o sinal anti-sabotagem. A parcela de
     * recência existe porque volume sozinho não basta — 50 denúncias de três anos atrás
     * não são mais urgentes que 10 desta semana.
     * <p>
     * Aproveita a passagem para reavaliar a rejeição (ver {@link #rejeitar}).
     *
     * <h2>A parcela da pré-análise</h2>
     * As evidências da <b>última</b> medição somam ao score, com peso por tipo de sinal
     * ({@link #PESO_POR_SINAL}). É recalculado tanto quando chega uma denúncia nova quanto
     * quando chega uma medição nova, e por isso as duas parcelas sempre refletem o mesmo
     * instante.
     * <p>
     * Só a última medição conta. Somar o histórico faria um domínio raspado dez vezes
     * valer dez vezes mais que o mesmo domínio raspado uma vez — o score mediria a
     * frequência do nosso worker, não o domínio.
     */
    @Transactional
    public void recalcularScore(Dominio dominio) {
        // Sem medição em mãos: lê a última do banco. É o caminho da denúncia nova, que
        // não sabe nada sobre pré-análise.
        recalcularScore(dominio, pontuarUltimaPreAnalise(dominio));
    }

    /**
     * Recalcula o score com a pontuação da pré-análise já calculada.
     * <p>
     * Existe para o caminho do worker, que <b>acabou de</b> produzir a medição. A versão
     * de um argumento releria do banco a linha que a transação corrente escreveu segundos
     * antes — uma consulta por domínio, a cada ciclo, para chegar a um valor que já estava
     * em memória.
     *
     * @param pontosDaPreAnalise quanto as evidências da medição valem. Ver
     *                           {@link #pontuar(SinalScraping)}
     */
    @Transactional
    public void recalcularScore(Dominio dominio, int pontosDaPreAnalise) {
        ContagemDenunciantes denunciantes = denunciaRepository.contarDenunciantes(
                dominio, Instant.now().minus(JANELA_RECENCIA));

        long total = denunciantes.total() * PESO_VOLUME
                + denunciantes.recentes() * PESO_RECENCIA
                + pontosDaPreAnalise;

        dominio.setScore((int) total);
        reabrirSeVoltouAoRadar(dominio);
        dominioRepository.save(dominio);
    }

    /**
     * Quanto uma medição soma ao score.
     * <p>
     * Cada <b>tipo</b> de sinal conta uma vez, por mais evidências daquele tipo que a
     * medição tenha encontrado. Sem esse corte, uma página que repete "cassino" quarenta
     * vezes ultrapassaria uma que exibe o selo de uma licenciadora — e a segunda é
     * incomparavelmente mais forte que a primeira.
     * <p>
     * Falha técnica vale zero, e não um valor negativo. Uma casa de aposta atrás de um WAF
     * que recusa robôs não é menos casa de aposta por isso.
     * <p>
     * A soma usa um bitset de tipos em vez de {@code stream().distinct()}: são cinco
     * valores de enum, e um {@code int} com um bit por tipo responde "já contei este?" sem
     * montar o conjunto de hash que o {@code distinct} precisa por trás.
     */
    public static int pontuar(SinalScraping sinal) {
        if (sinal == null || !sinal.isSucesso()) {
            return 0;
        }
        int tiposJaContados = 0;
        int pontos = 0;

        for (AssinaturaEncontrada assinatura : sinal.getAssinaturas()) {
            int bit = 1 << assinatura.tipo().ordinal();
            if ((tiposJaContados & bit) == 0) {
                tiposJaContados |= bit;
                pontos += PESO_POR_SINAL.getOrDefault(assinatura.tipo(), 0);
            }
        }
        return pontos;
    }

    private int pontuarUltimaPreAnalise(Dominio dominio) {
        return pontuar(sinalScrapingRepository.findTopByDominioOrderByCriadoEmDesc(dominio)
                .orElse(null));
    }

    /**
     * Um domínio é sensível quando bloqueá-lo por engano causa dano grave: jornalismo,
     * saúde, apoio a dependente químico, órgão público. Falso positivo é falha crítica,
     * então esses casos exigem dois moderadores em vez de um.
     */
    private boolean exigeQuorum(Dominio dominio) {
        return protecaoAllowlist.protege(dominio.getHost());
    }

    /**
     * Devolve à fila o domínio que continuou sendo denunciado depois de rejeitado.
     * Só conta o que chegou após a data da última rejeição.
     */
    private void reabrirSeVoltouAoRadar(Dominio dominio) {
        if (dominio.getStatus() != StatusDominio.REJEITADO) {
            return;
        }
        decisaoRepository.findTopByDominioAndDecisaoOrderByCriadoEmDesc(dominio, TipoDecisao.REJEICAO)
                .map(DecisaoModeracao::getCriadoEm)
                .filter(rejeitadoEm -> denunciaRepository.countDenunciantesDistintosDesde(dominio, rejeitadoEm)
                        >= properties.limiarReabertura())
                .ifPresent(rejeitadoEm -> dominio.setStatus(StatusDominio.EM_ANALISE));
    }

    /**
     * Um moderador, um voto por domínio — o índice único {@code ux_decisao_dominio_moderador}
     * é a garantia no banco, e esta checagem evita que o clique repetido do painel vire erro.
     */
    private void registrarDecisao(Dominio dominio, String moderador, TipoDecisao tipo, String motivo) {
        if (decisaoRepository.existsByDominioAndModerador(dominio, moderador)) {
            return;
        }
        DecisaoModeracao decisao = new DecisaoModeracao();
        decisao.setDominio(dominio);
        decisao.setModerador(moderador);
        decisao.setDecisao(tipo);
        decisao.setMotivo(motivo);
        decisaoRepository.save(decisao);
    }

    // findById, nunca getReferenceById: o proxy preguiçoso só estoura EntityNotFoundException
    // quando alguém toca um campo, fora do nosso controle e com status 500. O
    // GlobalExceptionHandler espera RecursoNaoEncontradoException para responder 404.
    private Dominio buscar(UUID dominioId) {
        return dominioRepository.findById(dominioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Domínio não encontrado: " + dominioId));
    }
}

package com.chegadebet.service;

import com.chegadebet.config.ModeracaoProperties;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.enums.TipoDecisao;
import com.chegadebet.domain.model.DecisaoModeracao;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.exception.EstadoInvalidoException;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.mapper.DominioMapper;
import com.chegadebet.repository.DecisaoModeracaoRepository;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.web.dto.DominioResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

    // Fórmula do score: peso do volume + peso da recência.
    //   score = (denunciantes distintos x 10) + (denunciantes distintos nos últimos 7 dias x 5)
    // Os pesos são arbitrários, mas a ordem entre eles é deliberada: o volume total é o
    // sinal principal e a recência entra como desempate — um domínio ativo esta semana
    // sobe na fila sem ultrapassar um caso muito mais denunciado.
    private static final int PESO_VOLUME = 10;
    private static final int PESO_RECENCIA = 5;
    private static final Duration JANELA_RECENCIA = Duration.ofDays(7);

    private final DominioRepository dominioRepository;
    private final DenunciaRepository denunciaRepository;
    private final DecisaoModeracaoRepository decisaoRepository;
    private final DominioMapper dominioMapper;
    private final ModeracaoProperties properties;
    private final BlocklistPublisher blocklistPublisher;

    public ModeracaoService(DominioRepository dominioRepository,
                            DenunciaRepository denunciaRepository,
                            DecisaoModeracaoRepository decisaoRepository,
                            DominioMapper dominioMapper,
                            ModeracaoProperties properties,
                            BlocklistPublisher blocklistPublisher) {
        this.dominioRepository = dominioRepository;
        this.denunciaRepository = denunciaRepository;
        this.decisaoRepository = decisaoRepository;
        this.dominioMapper = dominioMapper;
        this.properties = properties;
        this.blocklistPublisher = blocklistPublisher;
    }

    /**
     * Fila de moderação: os domínios em quarentena, do maior score para o menor.
     */
    // TODO: paginar. A fila cresce sem limite e devolver tudo de uma vez não escala.
    //       Troque por Page<DominioResponse> aqui e por um método que receba Pageable
    //       no DominioRepository — só mudar o tipo de retorno não pagina nada, porque
    //       a consulta continuaria trazendo a fila inteira do banco.
    @Transactional(readOnly = true)
    public List<DominioResponse> listarFila() {
        return dominioRepository.findByStatusOrderByScoreDesc(StatusDominio.EM_ANALISE)
                .stream()
                .map(dominioMapper::toResponse)
                .toList();
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
     */
    @Transactional
    public void recalcularScore(Dominio dominio) {
        long distintos = denunciaRepository.countDenunciantesDistintos(dominio);
        long recentes = denunciaRepository.countDenunciantesDistintosDesde(
                dominio, Instant.now().minus(JANELA_RECENCIA));

        dominio.setScore((int) (distintos * PESO_VOLUME + recentes * PESO_RECENCIA));
        reabrirSeVoltouAoRadar(dominio);
        dominioRepository.save(dominio);
    }

    /**
     * Um domínio é sensível quando bloqueá-lo por engano causa dano grave: jornalismo,
     * saúde, apoio a dependente químico, órgão público. Falso positivo é falha crítica,
     * então esses casos exigem dois moderadores em vez de um.
     */
    private boolean exigeQuorum(Dominio dominio) {
        String host = dominio.getHost().toLowerCase(Locale.ROOT);
        return properties.allowlist().stream()
                .map(entrada -> entrada.toLowerCase(Locale.ROOT))
                // O "." antes da entrada não é detalhe: com endsWith(entrada) puro, o host
                // malgov.br casaria com gov.br e ganharia uma proteção que não é dele.
                .anyMatch(entrada -> host.equals(entrada) || host.endsWith("." + entrada));
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

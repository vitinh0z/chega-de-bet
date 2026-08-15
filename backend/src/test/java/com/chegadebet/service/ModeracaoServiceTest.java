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
import com.chegadebet.repository.ContagemDenunciantes;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.SinalScrapingRepository;
import com.chegadebet.web.dto.DominioResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito puro: nenhuma destas regras depende do banco.
 * <p>
 * O que está sob teste é a separação entre score e quórum, a idempotência das decisões e
 * a proteção da allowlist. São exatamente as regressões que custam caro em produção.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModeracaoServiceTest {

    private static final String MODERADOR = "ana";
    private static final String OUTRO_MODERADOR = "bruno";

    @Mock
    private DominioRepository dominioRepository;
    @Mock
    private DenunciaRepository denunciaRepository;
    @Mock
    private DecisaoModeracaoRepository decisaoRepository;
    @Mock
    private SinalScrapingRepository sinalScrapingRepository;
    @Mock
    private BlocklistPublisher blocklistPublisher;

    private ModeracaoService moderacaoService;

    @BeforeEach
    void setUp() {
        // Mapper real: o DTO faz parte do contrato que os testes verificam.
        DominioMapper dominioMapper = dominio -> new DominioResponse(
                dominio.getId(), dominio.getHost(), dominio.getStatus(),
                dominio.getScore(), dominio.getCriadoEm());

        ModeracaoProperties properties = new ModeracaoProperties(
                2, List.of("gov.br", "jus.br"), 5);

        // ProtecaoAllowlist real: é uma função pura sobre a mesma configuração, e um mock
        // dela apagaria justamente a regra de sufixo que estes testes verificam.
        moderacaoService = new ModeracaoService(dominioRepository, denunciaRepository,
                decisaoRepository, sinalScrapingRepository, dominioMapper, properties,
                new ProtecaoAllowlist(properties), blocklistPublisher);

        when(dominioRepository.save(any(Dominio.class))).thenAnswer(chamada -> chamada.getArgument(0));
        // Sem pré-análise por padrão: os testes daqui são sobre denúncia, quórum e
        // allowlist. Quem precisa de medição a declara no próprio teste.
        when(sinalScrapingRepository.findTopByDominioOrderByCriadoEmDesc(any()))
                .thenReturn(Optional.empty());
    }

    private Dominio dominioEmAnalise(String host) {
        Dominio dominio = new Dominio();
        dominio.setId(UUID.randomUUID());
        dominio.setHost(host);
        dominio.setStatus(StatusDominio.EM_ANALISE);
        dominio.setCriadoEm(Instant.now());
        when(dominioRepository.findById(dominio.getId())).thenReturn(Optional.of(dominio));
        return dominio;
    }

    /** As duas contagens agora vêm juntas, de uma consulta só. */
    private void contagem(Dominio dominio, long total, long recentes) {
        when(denunciaRepository.contarDenunciantes(eq(dominio), any()))
                .thenReturn(new ContagemDenunciantes(total, recentes));
    }

    private void votosDeAprovacao(long quantidade) {
        when(decisaoRepository.countModeradoresDistintos(any(), any())).thenReturn(quantidade);
    }

    @Test
    @DisplayName("domínio comum: um voto aprova e publica na blocklist")
    void dominioComumAprovaComUmVoto() {
        Dominio dominio = dominioEmAnalise("apostaqui.com");
        votosDeAprovacao(1);

        DominioResponse response = moderacaoService.aprovar(dominio.getId(), MODERADOR);

        assertThat(response.status()).isEqualTo(StatusDominio.APROVADO);
        verify(blocklistPublisher).publicar(dominio);
    }

    @Test
    @DisplayName("domínio da allowlist: um voto NÃO aprova, segue EM_ANALISE")
    void dominioSensivelNaoAprovaComUmVoto() {
        Dominio dominio = dominioEmAnalise("noticias.gov.br");
        votosDeAprovacao(1);

        DominioResponse response = moderacaoService.aprovar(dominio.getId(), MODERADOR);

        assertThat(response.status()).isEqualTo(StatusDominio.EM_ANALISE);
        verify(blocklistPublisher, never()).publicar(any());
    }

    @Test
    @DisplayName("domínio da allowlist: dois moderadores distintos aprovam")
    void dominioSensivelAprovaComQuorum() {
        Dominio dominio = dominioEmAnalise("noticias.gov.br");
        votosDeAprovacao(2);

        DominioResponse response = moderacaoService.aprovar(dominio.getId(), OUTRO_MODERADOR);

        assertThat(response.status()).isEqualTo(StatusDominio.APROVADO);
        verify(blocklistPublisher).publicar(dominio);
    }

    @Test
    @DisplayName("domínio da allowlist: o mesmo moderador votando duas vezes não forma quórum")
    void mesmoModeradorNaoFormaQuorumSozinho() {
        Dominio dominio = dominioEmAnalise("noticias.gov.br");
        // Já votou: o voto repetido não vira uma segunda linha...
        when(decisaoRepository.existsByDominioAndModerador(dominio, MODERADOR)).thenReturn(true);
        // ...e a contagem de PESSOAS distintas continua em 1.
        votosDeAprovacao(1);

        DominioResponse response = moderacaoService.aprovar(dominio.getId(), MODERADOR);

        assertThat(response.status()).isEqualTo(StatusDominio.EM_ANALISE);
        verify(decisaoRepository, never()).save(any());
        verify(blocklistPublisher, never()).publicar(any());
    }

    @Test
    @DisplayName("exigeQuorum: malgov.br não é protegido por gov.br")
    void allowlistNaoProtegeSufixoColado() {
        Dominio dominio = dominioEmAnalise("malgov.br");
        votosDeAprovacao(1);

        // Se o endsWith fosse sem o ponto, este domínio exigiria quórum indevidamente.
        assertThat(moderacaoService.aprovar(dominio.getId(), MODERADOR).status())
                .isEqualTo(StatusDominio.APROVADO);
    }

    @Test
    @DisplayName("aprovar domínio já APROVADO não republica na blocklist")
    void aprovarEIdempotente() {
        Dominio dominio = dominioEmAnalise("apostaqui.com");
        dominio.setStatus(StatusDominio.APROVADO);

        DominioResponse response = moderacaoService.aprovar(dominio.getId(), MODERADOR);

        assertThat(response.status()).isEqualTo(StatusDominio.APROVADO);
        verify(blocklistPublisher, never()).publicar(any());
        verify(decisaoRepository, never()).save(any());
    }

    @Test
    @DisplayName("aprovar domínio já REJEITADO é conflito, não sobrescrita silenciosa")
    void aprovarSobreRejeicaoEConflito() {
        Dominio dominio = dominioEmAnalise("jornal.com.br");
        dominio.setStatus(StatusDominio.REJEITADO);

        assertThatThrownBy(() -> moderacaoService.aprovar(dominio.getId(), MODERADOR))
                .isInstanceOf(EstadoInvalidoException.class);
        verify(blocklistPublisher, never()).publicar(any());
    }

    @Test
    @DisplayName("aprovar domínio inexistente lança RecursoNaoEncontradoException")
    void aprovarDominioInexistente() {
        UUID id = UUID.randomUUID();
        when(dominioRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderacaoService.aprovar(id, MODERADOR))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("score alto sozinho não aprova: quórum conta votos, não denúncias")
    void scoreNaoDecideStatus() {
        Dominio dominio = dominioEmAnalise("noticias.gov.br");
        dominio.setScore(900);
        votosDeAprovacao(1);

        assertThat(moderacaoService.aprovar(dominio.getId(), MODERADOR).status())
                .isEqualTo(StatusDominio.EM_ANALISE);
    }

    @Test
    @DisplayName("rejeitar grava o motivo e não exige quórum")
    void rejeitarGravaMotivo() {
        Dominio dominio = dominioEmAnalise("noticias.gov.br");

        DominioResponse response = moderacaoService.rejeitar(dominio.getId(), MODERADOR, "site de notícias");

        assertThat(response.status()).isEqualTo(StatusDominio.REJEITADO);

        ArgumentCaptor<DecisaoModeracao> capturada = ArgumentCaptor.forClass(DecisaoModeracao.class);
        verify(decisaoRepository).save(capturada.capture());
        assertThat(capturada.getValue().getMotivo()).isEqualTo("site de notícias");
        assertThat(capturada.getValue().getDecisao()).isEqualTo(TipoDecisao.REJEICAO);
        assertThat(capturada.getValue().getModerador()).isEqualTo(MODERADOR);
    }

    @Test
    @DisplayName("rejeitar domínio já REJEITADO é idempotente")
    void rejeitarEIdempotente() {
        Dominio dominio = dominioEmAnalise("jornal.com.br");
        dominio.setStatus(StatusDominio.REJEITADO);

        assertThat(moderacaoService.rejeitar(dominio.getId(), MODERADOR, "motivo").status())
                .isEqualTo(StatusDominio.REJEITADO);
        verify(decisaoRepository, never()).save(any());
    }

    @Test
    @DisplayName("recalcularScore usa denunciantes distintos, não o total bruto")
    void recalcularScoreUsaDenunciantesDistintos() {
        Dominio dominio = dominioEmAnalise("apostaqui.com");
        // 10 denúncias do mesmo hash = 1 denunciante distinto.
        contagem(dominio, 1L, 1L);

        moderacaoService.recalcularScore(dominio);

        // (1 x 10) + (1 x 5)
        assertThat(dominio.getScore()).isEqualTo(15);
        verify(denunciaRepository, never()).countByDominio(any());
    }

    @Test
    @DisplayName("recalcularScore não muda o status de um domínio em análise")
    void recalcularScoreNaoDecideStatus() {
        Dominio dominio = dominioEmAnalise("apostaqui.com");
        contagem(dominio, 90L, 90L);

        moderacaoService.recalcularScore(dominio);

        assertThat(dominio.getScore()).isEqualTo(1350);
        assertThat(dominio.getStatus()).isEqualTo(StatusDominio.EM_ANALISE);
    }

    @Test
    @DisplayName("domínio rejeitado volta à fila quando denunciantes novos passam do limiar")
    void rejeitadoReabreAcimaDoLimiar() {
        Dominio dominio = dominioEmAnalise("mudoudedono.com");
        dominio.setStatus(StatusDominio.REJEITADO);
        Instant rejeitadoEm = Instant.now().minusSeconds(86_400);

        DecisaoModeracao rejeicao = new DecisaoModeracao();
        rejeicao.setCriadoEm(rejeitadoEm);
        when(decisaoRepository.findTopByDominioAndDecisaoOrderByCriadoEmDesc(dominio, TipoDecisao.REJEICAO))
                .thenReturn(Optional.of(rejeicao));
        contagem(dominio, 20L, 6L);
        // 6 denunciantes novos depois da rejeição, limiar é 5.
        when(denunciaRepository.countDenunciantesDistintosDesde(dominio, rejeitadoEm)).thenReturn(6L);

        moderacaoService.recalcularScore(dominio);

        assertThat(dominio.getStatus()).isEqualTo(StatusDominio.EM_ANALISE);
    }

    @Test
    @DisplayName("domínio rejeitado continua rejeitado abaixo do limiar de reabertura")
    void rejeitadoNaoReabreAbaixoDoLimiar() {
        Dominio dominio = dominioEmAnalise("rejeitado.com");
        dominio.setStatus(StatusDominio.REJEITADO);
        Instant rejeitadoEm = Instant.now().minusSeconds(86_400);

        DecisaoModeracao rejeicao = new DecisaoModeracao();
        rejeicao.setCriadoEm(rejeitadoEm);
        when(decisaoRepository.findTopByDominioAndDecisaoOrderByCriadoEmDesc(dominio, TipoDecisao.REJEICAO))
                .thenReturn(Optional.of(rejeicao));
        contagem(dominio, 30L, 2L);
        when(denunciaRepository.countDenunciantesDistintosDesde(dominio, rejeitadoEm)).thenReturn(2L);

        moderacaoService.recalcularScore(dominio);

        // Score alto não reabre nada: só conta o que chegou DEPOIS da rejeição.
        assertThat(dominio.getStatus()).isEqualTo(StatusDominio.REJEITADO);
    }

    @Test
    @DisplayName("listarFila devolve os domínios EM_ANALISE, do maior score para o menor")
    void listarFila() {
        Dominio primeiro = new Dominio();
        primeiro.setId(UUID.randomUUID());
        primeiro.setHost("a.com");
        primeiro.setStatus(StatusDominio.EM_ANALISE);
        primeiro.setScore(50);
        when(dominioRepository.findByStatusOrderByScoreDesc(StatusDominio.EM_ANALISE))
                .thenReturn(List.of(primeiro));

        assertThat(moderacaoService.listarFila())
                .singleElement()
                .satisfies(response -> assertThat(response.host()).isEqualTo("a.com"));
    }

    @Test
    @DisplayName("aprovar registra o voto com o moderador que chamou")
    void aprovarRegistraVotoDoModerador() {
        Dominio dominio = dominioEmAnalise("noticias.gov.br");
        votosDeAprovacao(1);

        moderacaoService.aprovar(dominio.getId(), MODERADOR);

        ArgumentCaptor<DecisaoModeracao> capturada = ArgumentCaptor.forClass(DecisaoModeracao.class);
        verify(decisaoRepository).save(capturada.capture());
        assertThat(capturada.getValue().getModerador()).isEqualTo(MODERADOR);
        assertThat(capturada.getValue().getDecisao()).isEqualTo(TipoDecisao.APROVACAO);
    }

    @Test
    @DisplayName("rejeitar domínio já APROVADO é conflito")
    void rejeitarSobreAprovacaoEConflito() {
        Dominio dominio = dominioEmAnalise("apostaqui.com");
        dominio.setStatus(StatusDominio.APROVADO);

        assertThatThrownBy(() -> moderacaoService.rejeitar(dominio.getId(), MODERADOR, "motivo"))
                .isInstanceOf(EstadoInvalidoException.class);
    }

    @Test
    @DisplayName("rejeitar domínio inexistente lança RecursoNaoEncontradoException")
    void rejeitarDominioInexistente() {
        UUID id = UUID.randomUUID();
        when(dominioRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderacaoService.rejeitar(id, MODERADOR, "motivo"))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("moderador com voto registrado não gera segunda linha de decisão")
    void votoRepetidoNaoDuplicaDecisao() {
        Dominio dominio = dominioEmAnalise("apostaqui.com");
        when(decisaoRepository.existsByDominioAndModerador(any(), anyString())).thenReturn(true);
        votosDeAprovacao(1);

        moderacaoService.aprovar(dominio.getId(), MODERADOR);

        verify(decisaoRepository, never()).save(any());
    }
}

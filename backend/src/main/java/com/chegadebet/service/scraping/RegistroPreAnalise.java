package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.SinalScraping;
import com.chegadebet.domain.scraping.ResultadoScraping;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.SinalScrapingRepository;
import com.chegadebet.service.ModeracaoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Grava o resultado de uma pré-análise e reordena a fila com ele.
 * <p>
 * Existe separado do {@link ScrapingWorker} pelo mesmo motivo que {@code RegistroDenuncia}
 * existe separado de {@code DenunciaService}: a transação precisa de um limite próprio.
 * O worker roda a parte de rede em virtual threads, fora de qualquer transação, e uma
 * chamada {@code @Transactional} dentro da própria classe não passaria pelo proxy do
 * Spring — o {@code @Transactional} seria ignorado em silêncio e cada save abriria a sua
 * própria transação implícita.
 * <p>
 * Uma transação por domínio, e não uma por lote, é o que atende ao critério de que a
 * falha em um domínio não derruba os demais: um erro aqui desfaz a gravação daquele
 * domínio e só dele.
 */
@Service
public class RegistroPreAnalise {

    private static final Logger log = LoggerFactory.getLogger(RegistroPreAnalise.class);

    private final DominioRepository dominioRepository;
    private final SinalScrapingRepository sinalScrapingRepository;
    private final ModeracaoService moderacaoService;

    public RegistroPreAnalise(DominioRepository dominioRepository,
                              SinalScrapingRepository sinalScrapingRepository,
                              ModeracaoService moderacaoService) {
        this.dominioRepository = dominioRepository;
        this.sinalScrapingRepository = sinalScrapingRepository;
        this.moderacaoService = moderacaoService;
    }

    /**
     * Persiste a medição e recalcula o score do domínio.
     * <p>
     * <b>Não</b> muda o status. Este método pode gravar as cinco evidências mais fortes do
     * dicionário de uma vez e o domínio continua {@code EM_ANALISE} — não existe caminho
     * daqui até {@code aprovar} ou {@code rejeitar}, e é essa ausência que garante o
     * humano no loop.
     *
     * @param dominioId qual domínio. Recebe o id, e não a entidade, porque a entidade
     *                  carregada no ciclo anterior pertence a outra transação já encerrada
     */
    @Transactional
    public void registrar(UUID dominioId, ResultadoScraping resultado) {
        Dominio dominio = dominioRepository.findById(dominioId).orElse(null);
        if (dominio == null) {
            // O domínio sumiu entre a seleção do lote e a gravação. Não é erro: um lote
            // pode levar minutos, e nesse tempo alguém pode ter limpado a base.
            log.debug("Pré-análise descartada: domínio {} não existe mais", dominioId);
            return;
        }

        // Segunda verificação, depois da rede. Entre a seleção do lote e a gravação um
        // moderador pode ter decidido o domínio, e gravar uma medição em cima de uma
        // decisão humana poluiria a auditoria com evidência que ninguém usou.
        if (dominio.getStatus() != StatusDominio.EM_ANALISE) {
            log.debug("Pré-análise descartada: {} saiu de EM_ANALISE durante o ciclo", dominio.getHost());
            return;
        }

        sinalScrapingRepository.save(comoEntidade(dominio, resultado));

        // A ordem importa: o recálculo lê a última medição do banco, então ele precisa
        // acontecer depois do save. Como os dois estão na mesma transação, o Hibernate
        // faz o flush antes da consulta e a medição recém-gravada já é visível.
        moderacaoService.recalcularScore(dominio);
    }

    private SinalScraping comoEntidade(Dominio dominio, ResultadoScraping resultado) {
        SinalScraping sinal = new SinalScraping();
        sinal.setDominio(dominio);
        sinal.setSucesso(resultado.sucesso());
        sinal.setMotivoFalha(resultado.motivoFalha());
        sinal.setAssinaturas(resultado.assinaturas());
        sinal.setBytesBaixados(resultado.bytesBaixados());
        sinal.setUrlFinal(resultado.urlFinal());
        sinal.setDocumentoVazio(resultado.documentoVazio());
        sinal.setDuracaoMs(resultado.duracao().toMillis());
        return sinal;
    }
}

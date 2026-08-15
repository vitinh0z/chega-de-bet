package com.chegadebet.repository;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DominioRepository extends JpaRepository<Dominio, UUID> {

    // Procura pela URL denunciada
    Optional<Dominio> findByHost(String host);

    /**
     * Uma página da fila de um status.
     * <p>
     * A ordenação <b>não</b> vem do nome do método, e sim do {@code Pageable} montado em
     * {@code ModeracaoService}. É deliberado: a ordem da fila de moderação é regra de
     * negócio, não preferência de quem chama, e um {@code OrderBy} aqui daria a impressão
     * de que o serviço pode passar outra e ela valeria.
     */
    Page<Dominio> findByStatus(StatusDominio status, Pageable pageable);

    // Usado pela pré-análise a cada hop de redirecionamento: se o destino já é um domínio
    // aprovado, não há o que descobrir baixando a página dele. Ver DomainScraperClient.
    boolean existsByHostAndStatus(String host, StatusDominio status);

    /**
     * Os próximos domínios a passar pela pré-análise.
     * <p>
     * Três regras, nesta ordem:
     * <ol>
     *   <li><b>Só {@code EM_ANALISE}.</b> Aprovado e rejeitado já têm decisão humana, e
     *       raspar de novo não muda nada — só gasta rede e incomoda o alvo.</li>
     *   <li><b>Cooldown.</b> Fica de fora quem foi tentado depois de {@code limite}. Sem
     *       isso, a fila {@code EM_ANALISE} seria inteiramente re-raspada a cada ciclo do
     *       worker: ela não esvazia sozinha, porque só o moderador tira domínio de lá.</li>
     *   <li><b>Nunca raspado primeiro.</b> {@code NULLS FIRST} sobre a data da última
     *       tentativa. Um domínio sem nenhuma medição é onde a pré-análise ainda pode
     *       ajudar o moderador; um já medido só ganharia uma segunda opinião.</li>
     * </ol>
     * O score entra só como desempate final, para o moderador receber evidência primeiro
     * sobre o que já está no topo da fila dele.
     * <p>
     * A allowlist de proteção <b>não</b> é filtrada aqui: ela casa por sufixo
     * ({@code gov.br} protege {@code noticias.gov.br}), e SQL não expressa isso sem um
     * {@code LIKE} por entrada. O corte fica no worker, via {@code ProtecaoAllowlist}.
     *
     * <h2>Sem subconsulta</h2>
     * A versão anterior perguntava as duas coisas ao {@code SinalScraping}, por
     * subconsulta correlacionada: um {@code NOT EXISTS} no filtro e um {@code MAX} no
     * {@code ORDER BY}. O segundo era o caro — uma chave de ordenação que só existe depois
     * de agregar outra tabela não cabe em índice nenhum, então o banco materializava a
     * fila inteira e ordenava em memória a cada ciclo.
     * <p>
     * Com {@code ultimaPreAnaliseEm} no próprio domínio, filtro e ordenação saem os dois
     * de {@code ix_dominio_fila_pre_analise}, em uma varredura de faixa.
     */
    @Query("""
            SELECT d FROM Dominio d
            WHERE d.status = :status
              AND (d.ultimaPreAnaliseEm IS NULL OR d.ultimaPreAnaliseEm <= :limite)
            ORDER BY d.ultimaPreAnaliseEm ASC NULLS FIRST, d.score DESC
            """)
    List<Dominio> buscarElegiveisParaPreAnalise(@Param("status") StatusDominio status,
                                                @Param("limite") Instant limite,
                                                Pageable pageable);
}

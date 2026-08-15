package com.chegadebet.repository;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
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

    // Lista os domínios de um status, do maior score para o menor.
    List<Dominio> findByStatusOrderByScoreDesc(StatusDominio status);

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
     */
    @Query("""
            SELECT d FROM Dominio d
            WHERE d.status = :status
              AND NOT EXISTS (
                  SELECT 1 FROM SinalScraping s
                  WHERE s.dominio = d AND s.criadoEm > :limite
              )
            ORDER BY (SELECT MAX(s2.criadoEm) FROM SinalScraping s2 WHERE s2.dominio = d) ASC NULLS FIRST,
                     d.score DESC
            """)
    List<Dominio> buscarElegiveisParaPreAnalise(@Param("status") StatusDominio status,
                                                @Param("limite") Instant limite,
                                                Pageable pageable);
}

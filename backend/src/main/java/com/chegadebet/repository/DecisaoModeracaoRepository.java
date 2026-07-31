package com.chegadebet.repository;

import com.chegadebet.domain.enums.TipoDecisao;
import com.chegadebet.domain.model.DecisaoModeracao;
import com.chegadebet.domain.model.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DecisaoModeracaoRepository extends JpaRepository<DecisaoModeracao, UUID> {

    // Quórum: conta PESSOAS distintas, não linhas. É o que impede um moderador de
    // formar quórum sozinho votando várias vezes.
    @Query("select count(distinct d.moderador) from DecisaoModeracao d " +
           "where d.dominio = :dominio and d.decisao = :decisao")
    long countModeradoresDistintos(@Param("dominio") Dominio dominio, @Param("decisao") TipoDecisao decisao);

    // Idempotência do voto: este moderador já se manifestou sobre este domínio?
    boolean existsByDominioAndModerador(Dominio dominio, String moderador);

    // Marco temporal da última rejeição, usado para decidir a reabertura da análise.
    Optional<DecisaoModeracao> findTopByDominioAndDecisaoOrderByCriadoEmDesc(Dominio dominio, TipoDecisao decisao);
}

package com.chegadebet.repository;

import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.SinalScraping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SinalScrapingRepository extends JpaRepository<SinalScraping, UUID> {

    // A medição mais recente de um domínio. É o que o cooldown consulta e o que o
    // recálculo de score usa: só o último resultado pesa, senão um domínio raspado dez
    // vezes valeria dez vezes mais que o mesmo domínio raspado uma vez.
    Optional<SinalScraping> findTopByDominioOrderByCriadoEmDesc(Dominio dominio);

    // Histórico completo, do mais novo para o mais antigo. Alimenta a tela de auditoria:
    // é aqui que se vê um site limpo em janeiro aparecer com selo de licenciadora em junho.
    List<SinalScraping> findByDominioOrderByCriadoEmDesc(Dominio dominio);
}

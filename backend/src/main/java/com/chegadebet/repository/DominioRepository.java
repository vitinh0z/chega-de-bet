package com.chegadebet.repository;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DominioRepository extends JpaRepository<Dominio, UUID> {

    // Procura pela URL denunciada
    Optional<Dominio> findByHost(String host);

    // Lista os domínios de um status, do maior score para o menor.
    List<Dominio> findByStatusOrderByScoreDesc(StatusDominio status);
}

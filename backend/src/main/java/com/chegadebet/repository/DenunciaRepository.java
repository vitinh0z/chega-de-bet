package com.chegadebet.repository;

import com.chegadebet.domain.model.Denuncia;
import com.chegadebet.domain.model.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DenunciaRepository extends JpaRepository<Denuncia, UUID> {

    // Dedup: mesmo denunciante (pseudônimo) já denunciou esse domínio antes?
    Optional<Denuncia> findByDominioAndDenuncianteHash(Dominio dominio, String denuncianteHash);

    // Total de denúncias do domínio (todas as linhas, com repetição).
    long countByDominio(Dominio dominio);

    // Denunciantes DISTINTOS do domínio (sinal anti-sabotagem): conta pseudônimos
    // anônimos diferentes, não o total bruto de denúncias.
    @Query("select count(distinct d.denuncianteHash) from Denuncia d where d.dominio = :dominio")
    long countDenunciantesDistintos(@Param("dominio") Dominio dominio);

    // Recorte temporal do sinal: 50 denúncias de três anos atrás não são mais urgentes
    // que 10 desta semana. Também mede o que entrou depois de uma rejeição.
    @Query("select count(distinct d.denuncianteHash) from Denuncia d " +
           "where d.dominio = :dominio and d.criadoEm >= :desde")
    long countDenunciantesDistintosDesde(@Param("dominio") Dominio dominio, @Param("desde") Instant desde);
}

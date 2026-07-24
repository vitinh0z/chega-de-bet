package com.chegadebet.repository;

import com.chegadebet.domain.model.Denuncia;
import com.chegadebet.domain.model.Dominio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DenunciaRepository extends JpaRepository<Denuncia, UUID> {

    // Total de denúncias do domínio (todas as linhas, com repetição).
    long countByDominio(Dominio dominio);

    // Denunciantes DISTINTOS do domínio (sinal anti-sabotagem): conta pseudônimos
    // anônimos diferentes, não o total bruto de denúncias.
    @Query("select count(distinct d.denuncianteHash) from Denuncia d where d.dominio = :dominio")
    long countDenunciantesDistintos(@Param("dominio") Dominio dominio);
}

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

    // Recorte temporal do sinal: 50 denúncias de três anos atrás não são mais urgentes
    // que 10 desta semana. Também mede o que entrou depois de uma rejeição.
    @Query("select count(distinct d.denuncianteHash) from Denuncia d " +
           "where d.dominio = :dominio and d.criadoEm >= :desde")
    long countDenunciantesDistintosDesde(@Param("dominio") Dominio dominio, @Param("desde") Instant desde);

    /**
     * As duas contagens que o score precisa, em uma consulta só.
     * <p>
     * Conta denunciantes <b>distintos</b>, nunca o total bruto de denúncias: o total é
     * fácil de inflar, e pseudônimos diferentes são o sinal anti-sabotagem.
     * <p>
     * Eram duas consultas, e as duas varriam exatamente as mesmas linhas pelo mesmo índice
     * ({@code ux_denuncia_dominio_denunciante}) — a segunda só descartava as antigas. O
     * {@code CASE} move esse descarte para dentro da agregação, e o banco faz uma passagem
     * em vez de duas. Em um ciclo de 50 domínios, são 50 ida-e-voltas a menos.
     *
     */
    @Query("""
            select new com.chegadebet.repository.ContagemDenunciantes(
                       count(distinct d.denuncianteHash),
                       count(distinct case when d.criadoEm >= :desde then d.denuncianteHash end))
            from Denuncia d
            where d.dominio = :dominio
            """)
    ContagemDenunciantes contarDenunciantes(@Param("dominio") Dominio dominio,
                                            @Param("desde") Instant desde);
}

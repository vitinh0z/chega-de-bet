package com.chegadebet.repository;

import com.chegadebet.domain.model.TokenEfemero;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TokenRepository extends JpaRepository<TokenEfemero, UUID> {

    Optional<TokenEfemero> findByValorHash(String valorHash);

    // Expurgo em lote dos tokens expirados (um único DELETE). Chamar dentro de contexto @Transactional.
    // clearAutomatically: o DELETE em lote passa por fora do contexto de persistência, então
    // as entidades já carregadas ficariam obsoletas. Limpar evita ler token apagado na mesma transação.
    @Modifying(clearAutomatically = true)
    @Query("delete from TokenEfemero t where t.expiraEm < :momento")
    int deleteExpirados(@Param("momento") Instant momento);
}

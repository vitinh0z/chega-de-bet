package com.chegadebet.repository;

// TODO: estender JpaRepository<TokenEfemero, UUID>.
//   Queries sugeridas:
//     - Optional<TokenEfemero> findByValorHash(String valorHash)
//     - long deleteByExpiraEmBefore(Instant momento)  (expurgo dos expirados)
public interface TokenRepository {
}

package com.chegadebet.web.dto;

import java.time.Instant;

/**
 * DTO de saída da emissão de token efêmero.
 * <p>
 * O campo {@code token} é o único momento em que o valor trafega em claro. O banco
 * guarda apenas o seu hash SHA-256, então um token perdido não pode ser recuperado —
 * a extensão pede outro. Por ser resposta, não leva Bean Validation.
 */
public record TokenResponse(
        String token,
        Instant expiraEm
) {
}

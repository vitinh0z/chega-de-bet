package com.chegadebet.web.dto;

import com.chegadebet.domain.enums.StatusDominio;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de saída do domínio. Usado, por exemplo, na fila de moderação
 * (listagem por status). Por ser resposta, não leva Bean Validation.
 */
public record DominioResponse(
        UUID id,
        String host,
        StatusDominio status,
        int score,
        Instant criadoEm
) {
}

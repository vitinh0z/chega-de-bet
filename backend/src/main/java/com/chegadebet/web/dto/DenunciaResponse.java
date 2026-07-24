package com.chegadebet.web.dto;

import com.chegadebet.domain.enums.StatusDominio;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de saída da denúncia. Por ser resposta (o servidor produz), não leva
 * Bean Validation — as restrições ficam no {@link DenunciaRequest}.
 */
public record DenunciaResponse(
        UUID id,
        String host,
        StatusDominio status,
        Instant criadoEm
) {
}

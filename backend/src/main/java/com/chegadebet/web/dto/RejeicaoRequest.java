package com.chegadebet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO de entrada da rejeição de um domínio na moderação.
 * <p>
 * O motivo é obrigatório de propósito: rejeição sem justificativa quebra a
 * moderação auditável. O controller deve anotar o corpo com {@code @Valid}.
 */
public record RejeicaoRequest(

        @NotBlank(message = "O motivo da rejeição é obrigatório.")
        @Size(max = 500, message = "O motivo excede o tamanho máximo de 500 caracteres.")
        String motivo
) {
}

package com.chegadebet.web.dto;

import com.chegadebet.domain.enums.CategoriaAposta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO de entrada da denúncia. Validado com Bean Validation — o controller
 * deve anotar o corpo com {@code @Valid} para as restrições valerem.
 */
public record DenunciaRequest(

        @NotBlank(message = "O host é obrigatório.")
        @Size(max = 253, message = "O host excede o tamanho máximo de um domínio (253 caracteres).")
        // Apenas o domínio: labels alfanuméricas separadas por ponto, TLD com 2+ letras.
        // Rejeita protocolo (http://), caminho (/x), porta (:8080), espaços e hífen nas pontas.
        @Pattern(
                regexp = "^([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$",
                message = "Host inválido: informe só o domínio (ex.: exemplo.com), sem http://, caminho ou porta."
        )
        String host,

        @NotNull(message = "A categoria é obrigatória.")
        CategoriaAposta categoria,

        @NotBlank(message = "O token é obrigatório.")
        @Size(max = 512, message = "Token inválido.")
        String token
) {
}

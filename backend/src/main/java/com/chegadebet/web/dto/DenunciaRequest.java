package com.chegadebet.web.dto;

// TODO: DTO de entrada da denúncia (record).
//   Campos sugeridos: String host, CategoriaAposta categoria, String token
//   Validação (Bean Validation): @NotBlank no host, @NotNull na categoria,
//   @Pattern para validar formato de domínio.
//   Ex.: public record DenunciaRequest(String host, CategoriaAposta categoria, String token) {}
public record DenunciaRequest() {
}

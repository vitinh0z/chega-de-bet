package com.chegadebet.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Uma página de resultados, no formato que a API promete.
 * <p>
 * Existe em vez de serializar o {@code Page} do Spring Data direto. O {@code PageImpl} não
 * tem contrato de JSON estável — o próprio Spring Boot emite um aviso na subida quando
 * alguém o devolve de um controller, porque a estrutura já mudou entre versões e pode
 * mudar de novo. Uma API pública não pode ter o formato de resposta amarrado à
 * representação interna de uma biblioteca.
 * <p>
 * Os campos são os que um cliente precisa para navegar, e nada além: não expomos
 * {@code sort}, {@code pageable} nem {@code first}/{@code last}, que o {@code PageImpl}
 * despeja e ninguém usa.
 *
 * @param itens         os resultados desta página
 * @param pagina        índice da página atual, começando em zero
 * @param tamanho       quantos itens cabem por página
 * @param totalDeItens  quantos itens existem na fila inteira
 * @param totalDePaginas quantas páginas existem no total
 */
public record PaginaResponse<T>(
        List<T> itens,
        int pagina,
        int tamanho,
        long totalDeItens,
        int totalDePaginas
) {

    /** Converte um {@link Page} do Spring Data para o formato da API. */
    public static <T> PaginaResponse<T> de(Page<T> pagina) {
        return new PaginaResponse<>(
                pagina.getContent(),
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages());
    }
}

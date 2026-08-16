package com.chegadebet.domain.enums;

/**
 * Onde no documento a assinatura foi encontrada.
 * <p>
 * A mesma palavra vale coisas diferentes conforme o lugar. "cassino" no {@code <title>}
 * é o site se apresentando; "cassino" no meio de um parágrafo pode ser uma reportagem
 * sobre cassino. O moderador precisa dessa diferença na tela, e a medição em
 * {@code docs/pre-analise-scraping.md} confirmou que o sinal útil se concentra no
 * {@code <head>}.
 * <p>
 * Repare no que <b>não</b> está aqui: {@code <script>} e {@code <style>}. Eles não são
 * uma origem de menor peso, são descartados antes do matcher — é lá que moram os falsos
 * positivos medidos (nome de arquivo, variável CSS, lista de ícones do Material Symbols).
 */
public enum TrechoDocumento {

    /** {@code <title>} ou {@code og:title}. */
    TITULO,

    /** {@code <meta name="description">}, {@code keywords} e demais {@code og:}. */
    META,

    /** Texto visível da página, já sem script, sem style e sem atributo. */
    CORPO,

    /** O próprio nome do host. Não vem do documento: sai da URL, sem abrir conexão. */
    HOST
}

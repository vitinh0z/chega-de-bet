package com.chegadebet.domain.enums;

/**
 * O que uma assinatura encontrada pela pré-análise significa.
 * <p>
 * O tipo existe para separar sinais de força muito diferente. "aposta" no corpo de uma
 * página é quase nada — a medição encontrou o termo seis vezes no {@code g1.globo.com},
 * que é jornalismo. Já o script do Pragmatic Play carregado na página é um provedor de
 * cassino contratado, e ninguém contrata isso por engano.
 * <p>
 * O peso de cada tipo no score vive em {@code ModeracaoService}, não aqui: este enum
 * classifica a evidência, e a política de quanto ela vale é decisão de moderação.
 */
public enum TipoSinalScraping {

    /**
     * Termo genérico de aposta ("cassino", "roleta", "apostas esportivas") no texto da
     * página. Peso baixo: aparece em jornalismo, em conteúdo educativo sobre vício em
     * jogo e em qualquer página que fale <b>sobre</b> aposta sem ser uma casa de aposta.
     */
    PALAVRA_CHAVE,

    /**
     * O host termina em {@code .bet.br}. Sinal decisivo e de custo zero: o domínio de
     * primeiro nível {@code bet.br} é reservado às casas de aposta autorizadas pelo
     * Ministério da Fazenda, e ninguém mais consegue registrar sob ele.
     * <p>
     * É o único sinal que dispensa abrir conexão — sai do próprio nome do host.
     */
    DOMINIO_BET_BR,

    /**
     * Script ou marca de um provedor de jogos de cassino (Pragmatic Play, Evolution,
     * PG Soft). Peso alto: é integração contratada, não menção casual.
     */
    PROVEDOR_SLOTS,

    /**
     * Selo de licenciadora de jogo (Curaçao eGaming, Malta Gaming Authority). Peso alto:
     * o site está declarando a própria licença de operar aposta, normalmente no rodapé,
     * porque é exigência da licenciadora.
     */
    LICENCIADORA,

    /**
     * Texto de cadastro, depósito ou saque ("depósito via Pix", "saque mínimo",
     * "verificação de identidade"). Peso médio: indica que a página movimenta dinheiro da
     * pessoa, o que separa uma casa de aposta de um site que só fala de aposta.
     */
    KYC_DEPOSITO
}

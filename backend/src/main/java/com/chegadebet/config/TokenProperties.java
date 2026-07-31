package com.chegadebet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuração do token efêmero.
 *
 * @param ttl       por quanto tempo um token emitido continua válido. Formato ISO-8601
 *                  ({@code PT24H} = 24 horas). Fica em configuração porque encurtar o
 *                  prazo é uma resposta a incidente: não pode exigir recompilar.
 * @param rateLimit limite de emissão por origem.
 */
@ConfigurationProperties(prefix = "chegadebet.token")
public record TokenProperties(Duration ttl, RateLimit rateLimit) {

    /**
     * Limite de emissão de token por endereço de origem.
     *
     * @param capacidade quantas emissões cabem na janela. Um uso normal precisa de um
     *                   token por dia (o TTL é de 24h), então a folga aqui é para IP
     *                   compartilhado, não para uso individual.
     * @param janela     em quanto tempo a capacidade é reposta por inteiro.
     * @param maxChaves  teto de origens acompanhadas ao mesmo tempo, para o mapa em
     *                   memória não crescer sem limite sob enxurrada.
     */
    public record RateLimit(int capacidade, Duration janela, int maxChaves) {
    }
}

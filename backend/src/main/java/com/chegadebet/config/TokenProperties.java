package com.chegadebet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuração do token efêmero.
 *
 * @param ttl por quanto tempo um token emitido continua válido. Formato ISO-8601
 *            ({@code PT24H} = 24 horas). Fica em configuração porque encurtar o prazo
 *            é uma resposta a incidente: não pode exigir recompilar.
 */
@ConfigurationProperties(prefix = "chegadebet.token")
public record TokenProperties(Duration ttl) {
}

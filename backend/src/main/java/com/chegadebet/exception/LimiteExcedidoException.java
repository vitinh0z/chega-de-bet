package com.chegadebet.exception;

import java.time.Duration;

/**
 * A origem estourou o limite de requisições da janela.
 * <p>
 * O {@code GlobalExceptionHandler} traduz em 429 Too Many Requests e usa
 * {@link #getEsperar()} para preencher o cabeçalho {@code Retry-After}.
 */
public class LimiteExcedidoException extends RuntimeException {

    private final Duration esperar;

    public LimiteExcedidoException(Duration esperar) {
        super("Muitas solicitações. Tente novamente em instantes.");
        this.esperar = esperar;
    }

    public Duration getEsperar() {
        return esperar;
    }
}

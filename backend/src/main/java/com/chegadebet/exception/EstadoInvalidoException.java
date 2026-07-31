package com.chegadebet.exception;

/**
 * A operação não cabe no estado atual do recurso — por exemplo, aprovar um domínio que
 * já foi rejeitado por um moderador.
 * <p>
 * O {@code GlobalExceptionHandler} traduz em 409 Conflict: não é erro de quem chamou
 * (400) nem recurso ausente (404), é conflito com uma decisão já registrada.
 */
public class EstadoInvalidoException extends RuntimeException {

    public EstadoInvalidoException(String mensagem) {
        super(mensagem);
    }
}

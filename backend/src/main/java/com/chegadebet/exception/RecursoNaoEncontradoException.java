package com.chegadebet.exception;

// TODO: exceção de domínio para recurso inexistente (ex.: domínio não encontrado).
//   Usada pelo GlobalExceptionHandler para responder 404.
public class RecursoNaoEncontradoException extends RuntimeException {

    public RecursoNaoEncontradoException(String mensagem) {
        super(mensagem);
    }
}

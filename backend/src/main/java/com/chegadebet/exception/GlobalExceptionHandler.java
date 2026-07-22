package com.chegadebet.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Tratamento global de exceções.
 * Estende ResponseEntityExceptionHandler para que os erros próprios do Spring MVC
 * (JSON malformado, parâmetro ausente, método não suportado...) mantenham o status
 * correto em vez de caírem no 500 genérico.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Falhas de @Valid no corpo da requisição -> 400 listando os campos inválidos. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String mensagem = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return handleExceptionInternal(ex, erro(status, mensagem, request), headers, status, request);
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ErroResponse> handleNaoEncontrado(RecursoNaoEncontradoException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(erro(HttpStatus.NOT_FOUND, ex.getMessage(), request));
    }

    /**
     * Rede de segurança para o que não foi tratado acima: a causa vai para o log
     * (e daí para o Loki) e o cliente recebe apenas uma mensagem genérica.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErroResponse> handleGenerica(Exception ex, WebRequest request) {
        log.error("Erro não tratado em {}", path(request), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(erro(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", request));
    }

    /** Padroniza no formato ErroResponse também os erros tratados pela classe base. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        Object corpo = (body instanceof ErroResponse) ? body : erro(statusCode, mensagemDe(ex, statusCode), request);
        return super.handleExceptionInternal(ex, corpo, headers, statusCode, request);
    }

    /**
     * Usa o "detail" que o próprio Spring monta para suas exceções — já é uma
     * descrição destinada ao cliente, sem detalhe interno. Sem ele, o texto do status.
     */
    private String mensagemDe(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof ErrorResponse er && er.getBody().getDetail() != null) {
            return er.getBody().getDetail();
        }
        return (statusCode instanceof HttpStatus status) ? status.getReasonPhrase() : "Erro";
    }

    private ErroResponse erro(HttpStatusCode statusCode, String mensagem, WebRequest request) {
        return new ErroResponse(Instant.now(), statusCode.value(), mensagem, path(request));
    }

    private String path(WebRequest request) {
        return request.getDescription(false).replace("uri=", "");
    }

    public record ErroResponse(Instant timestamp, int status, String mensagem, String path) {
    }
}

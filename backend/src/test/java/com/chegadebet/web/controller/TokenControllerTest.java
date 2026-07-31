package com.chegadebet.web.controller;

import com.chegadebet.exception.GlobalExceptionHandler;
import com.chegadebet.exception.LimiteExcedidoException;
import com.chegadebet.service.TokenRateLimiter;
import com.chegadebet.service.TokenService;
import com.chegadebet.web.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O contrato que a extensão consome: 201 na emissão, 429 com Retry-After no estouro.
 * <p>
 * Sem contexto Spring — o Boot 4 tirou o slice {@code @WebMvcTest} do
 * {@code spring-boot-starter-test}, e um teste direto do controller e do handler cobre
 * o mesmo comportamento sem trazer dependência nova só para isto.
 */
@ExtendWith(MockitoExtension.class)
class TokenControllerTest {

    @Mock
    private TokenService tokenService;
    @Mock
    private TokenRateLimiter tokenRateLimiter;
    @Mock
    private HttpServletRequest httpRequest;
    @Mock
    private WebRequest webRequest;

    @Test
    @DisplayName("emissão dentro do limite responde 201 com o token em claro")
    void emiteDentroDoLimite() {
        Instant expiraEm = Instant.parse("2026-08-01T12:00:00Z");
        when(tokenService.emitir()).thenReturn(new TokenResponse("valor-em-claro", expiraEm));
        when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.10");

        ResponseEntity<TokenResponse> response =
                new TokenController(tokenService, tokenRateLimiter).emitir(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isEqualTo("valor-em-claro");
        assertThat(response.getBody().expiraEm()).isEqualTo(expiraEm);
    }

    @Test
    @DisplayName("o limite é cobrado ANTES de emitir: token recusado não chega ao banco")
    void limiteEhCobradoAntesDeEmitir() {
        when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.10");
        doThrow(new LimiteExcedidoException(Duration.ofSeconds(90)))
                .when(tokenRateLimiter).registrarEmissao(anyString());

        TokenController controller = new TokenController(tokenService, tokenRateLimiter);

        assertThatThrownBy(() -> controller.emitir(httpRequest))
                .isInstanceOf(LimiteExcedidoException.class);
        verify(tokenService, never()).emitir();
    }

    @Test
    @DisplayName("o endereço usado no limite é o remoteAddr da requisição")
    void usaRemoteAddrComoOrigem() {
        when(tokenService.emitir()).thenReturn(new TokenResponse("t", Instant.now()));
        when(httpRequest.getRemoteAddr()).thenReturn("198.51.100.7");

        new TokenController(tokenService, tokenRateLimiter).emitir(httpRequest);

        InOrder ordem = inOrder(tokenRateLimiter, tokenService);
        ordem.verify(tokenRateLimiter).registrarEmissao("198.51.100.7");
        ordem.verify(tokenService).emitir();
    }

    @Test
    @DisplayName("LimiteExcedidoException vira 429 com Retry-After em segundos")
    void limiteExcedidoVira429() {
        when(webRequest.getDescription(false)).thenReturn("uri=/api/tokens");

        ResponseEntity<GlobalExceptionHandler.ErroResponse> response = new GlobalExceptionHandler()
                .handleLimiteExcedido(new LimiteExcedidoException(Duration.ofSeconds(90)), webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("90");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(429);
        assertThat(response.getBody().path()).isEqualTo("/api/tokens");
    }

    @Test
    @DisplayName("espera abaixo de um segundo vira Retry-After 1, nunca 0")
    void retryAfterNuncaZero() {
        when(webRequest.getDescription(false)).thenReturn("uri=/api/tokens");

        // "Tente de novo em 0 segundos" convidaria o cliente a repetir na hora e a levar
        // outro 429.
        ResponseEntity<GlobalExceptionHandler.ErroResponse> response = new GlobalExceptionHandler()
                .handleLimiteExcedido(new LimiteExcedidoException(Duration.ofMillis(120)), webRequest);

        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    @DisplayName("a mensagem do 429 não conta quanto restou nem qual é o limite")
    void mensagemNaoVazaOLimite() {
        when(webRequest.getDescription(false)).thenReturn("uri=/api/tokens");

        ResponseEntity<GlobalExceptionHandler.ErroResponse> response = new GlobalExceptionHandler()
                .handleLimiteExcedido(new LimiteExcedidoException(Duration.ofSeconds(42)), webRequest);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().mensagem()).doesNotContainPattern("\\d");
    }
}

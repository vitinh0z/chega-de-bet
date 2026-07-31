package com.chegadebet.service;

import com.chegadebet.config.TokenProperties;
import com.chegadebet.exception.LimiteExcedidoException;
import com.chegadebet.repository.TokenRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mockito puro: o limite é aritmética de balde em memória, não precisa de contexto Spring.
 */
@ExtendWith(MockitoExtension.class)
class TokenRateLimiterTest {

    private static final String ORIGEM = "203.0.113.10";
    private static final String OUTRA_ORIGEM = "203.0.113.11";

    @Mock
    private TokenRepository tokenRepository;

    private TokenRateLimiter limiterCom(int capacidade, int maxChaves) {
        TokenProperties properties = new TokenProperties(
                Duration.ofHours(24),
                new TokenProperties.RateLimit(capacidade, Duration.ofHours(1), maxChaves));
        // TokenService real: é dele que sai o hash usado como chave do balde.
        TokenService tokenService = new TokenService(tokenRepository, properties);
        return new TokenRateLimiter(properties, tokenService, new SimpleMeterRegistry(), "none");
    }

    @Test
    @DisplayName("deixa passar até a capacidade da janela")
    void permiteAteACapacidade() {
        TokenRateLimiter limiter = limiterCom(3, 100);

        assertThatCode(() -> {
            limiter.registrarEmissao(ORIGEM);
            limiter.registrarEmissao(ORIGEM);
            limiter.registrarEmissao(ORIGEM);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a requisição seguinte à capacidade recebe LimiteExcedidoException")
    void recusaAcimaDaCapacidade() {
        TokenRateLimiter limiter = limiterCom(2, 100);
        limiter.registrarEmissao(ORIGEM);
        limiter.registrarEmissao(ORIGEM);

        assertThatThrownBy(() -> limiter.registrarEmissao(ORIGEM))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    @DisplayName("o limite é por origem: uma esgotada não afeta a outra")
    void limiteEPorOrigem() {
        TokenRateLimiter limiter = limiterCom(1, 100);
        limiter.registrarEmissao(ORIGEM);

        assertThatThrownBy(() -> limiter.registrarEmissao(ORIGEM))
                .isInstanceOf(LimiteExcedidoException.class);
        assertThatCode(() -> limiter.registrarEmissao(OUTRA_ORIGEM)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a espera devolvida é positiva, para virar Retry-After útil")
    void esperaEPositiva() {
        TokenRateLimiter limiter = limiterCom(1, 100);
        limiter.registrarEmissao(ORIGEM);

        assertThatThrownBy(() -> limiter.registrarEmissao(ORIGEM))
                .isInstanceOf(LimiteExcedidoException.class)
                .extracting(erro -> ((LimiteExcedidoException) erro).getEsperar())
                .satisfies(espera -> assertThat((Duration) espera).isPositive());
    }

    @Test
    @DisplayName("o teto de chaves recusa origens novas em vez de crescer sem limite")
    void tetoDeChavesRecusaOrigemNova() {
        TokenRateLimiter limiter = limiterCom(10, 2);
        limiter.registrarEmissao("198.51.100.1");
        limiter.registrarEmissao("198.51.100.2");

        // As duas já conhecidas continuam funcionando...
        assertThatCode(() -> limiter.registrarEmissao("198.51.100.1")).doesNotThrowAnyException();
        // ...e a terceira origem não entra no mapa.
        assertThatThrownBy(() -> limiter.registrarEmissao("198.51.100.3"))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    @DisplayName("o expurgo não derruba balde em uso recente")
    void expurgoPreservaOQueEstaEmUso() {
        TokenRateLimiter limiter = limiterCom(1, 100);
        limiter.registrarEmissao(ORIGEM);

        limiter.expurgarOciosos();

        // O balde sobreviveu, então a contagem continua valendo.
        assertThatThrownBy(() -> limiter.registrarEmissao(ORIGEM))
                .isInstanceOf(LimiteExcedidoException.class);
    }

    @Test
    @DisplayName("janela vencida repõe a capacidade")
    void janelaVencidaRepoeCapacidade() {
        TokenProperties properties = new TokenProperties(
                Duration.ofHours(24),
                // Janela de 1ms: o refill greedy repõe o balde quase imediatamente.
                new TokenProperties.RateLimit(1, Duration.ofMillis(1), 100));
        TokenRateLimiter limiter = new TokenRateLimiter(
                properties, new TokenService(tokenRepository, properties), new SimpleMeterRegistry(), "none");

        limiter.registrarEmissao(ORIGEM);

        assertThatCode(() -> {
            Thread.sleep(20);
            limiter.registrarEmissao(ORIGEM);
        }).doesNotThrowAnyException();
    }
}

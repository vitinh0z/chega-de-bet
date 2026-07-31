package com.chegadebet.service;

import com.chegadebet.config.TokenProperties;
import com.chegadebet.domain.model.TokenEfemero;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.repository.TokenRepository;
import com.chegadebet.web.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Mockito puro: a regra do token não depende do contexto Spring nem do banco.
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private TokenRepository tokenRepository;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(tokenRepository, new TokenProperties(Duration.ofHours(24)));
    }

    private void devolveOQueFoiSalvo() {
        when(tokenRepository.save(any(TokenEfemero.class))).thenAnswer(chamada -> chamada.getArgument(0));
    }

    @Test
    @DisplayName("emitir devolve token não vazio e expiração no futuro")
    void emitirDevolveTokenValido() {
        devolveOQueFoiSalvo();

        TokenResponse response = tokenService.emitir();

        assertThat(response.token()).isNotBlank();
        assertThat(response.expiraEm()).isAfter(Instant.now());
        // Base64 URL-safe sem padding: nada de '+', '/' ou '=' para escapar em query string.
        assertThat(response.token()).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("emitir duas vezes devolve valores diferentes")
    void emitirDevolveValoresDiferentes() {
        devolveOQueFoiSalvo();

        assertThat(tokenService.emitir().token()).isNotEqualTo(tokenService.emitir().token());
    }

    @Test
    @DisplayName("o que vai para o banco é o hash, nunca o token em claro")
    void persisteApenasOHash() {
        devolveOQueFoiSalvo();

        TokenResponse response = tokenService.emitir();

        ArgumentCaptor<TokenEfemero> capturado = ArgumentCaptor.forClass(TokenEfemero.class);
        org.mockito.Mockito.verify(tokenRepository).save(capturado.capture());

        TokenEfemero salvo = capturado.getValue();
        assertThat(salvo.getValorHash())
                .isNotEqualTo(response.token())
                .isEqualTo(tokenService.hashDe(response.token()))
                .hasSize(64);
    }

    @Test
    @DisplayName("validar de token inexistente lança RecursoNaoEncontradoException")
    void validarTokenInexistente() {
        when(tokenRepository.findByValorHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.validar("nao-existe"))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessage("Token inválido ou expirado.");
    }

    @Test
    @DisplayName("token expirado falha com a MESMA mensagem do inexistente")
    void validarTokenExpiradoUsaMesmaMensagem() {
        TokenEfemero expirado = new TokenEfemero();
        expirado.setValorHash(tokenService.hashDe("expirado"));
        expirado.setExpiraEm(Instant.now().minusSeconds(60));
        when(tokenRepository.findByValorHash(any())).thenReturn(Optional.of(expirado));

        // Mensagens diferentes contariam a um atacante se o token existe no banco.
        assertThatThrownBy(() -> tokenService.validar("expirado"))
                .isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessage("Token inválido ou expirado.");
    }

    @Test
    @DisplayName("validar de token vigente devolve a entidade com o valorHash")
    void validarTokenVigente() {
        TokenEfemero vigente = new TokenEfemero();
        vigente.setValorHash(tokenService.hashDe("valido"));
        vigente.setExpiraEm(Instant.now().plusSeconds(3600));
        when(tokenRepository.findByValorHash(tokenService.hashDe("valido"))).thenReturn(Optional.of(vigente));

        assertThat(tokenService.validar("valido").getValorHash()).isEqualTo(vigente.getValorHash());
    }

    @Test
    @DisplayName("hashDe é estável e cabe em VARCHAR(64)")
    void hashDeEstavel() {
        assertThat(tokenService.hashDe("abc"))
                .hasSize(64)
                .isEqualTo(tokenService.hashDe("abc"))
                .isNotEqualTo(tokenService.hashDe("abd"));
    }

    @Test
    @DisplayName("expurgarExpirados devolve a contagem do repositório")
    void expurgarDevolveContagem() {
        when(tokenRepository.deleteExpirados(any(Instant.class))).thenReturn(7);

        assertThat(tokenService.expurgarExpirados()).isEqualTo(7);
    }
}

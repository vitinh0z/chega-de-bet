package com.chegadebet.config;

import com.chegadebet.domain.model.Moderador;
import com.chegadebet.repository.ModeradorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BootstrapModeradorTest {

    private static final String SENHA_BOA = "senha-longa-o-suficiente";

    @Mock
    private ModeradorRepository moderadorRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private BootstrapModerador bootstrap(String login, String senha) {
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}$2a$10$hashdementira");
        return new BootstrapModerador(moderadorRepository, passwordEncoder, login, senha);
    }

    @Test
    @DisplayName("tabela vazia e ambiente configurado: cria o moderador com a senha codificada")
    void criaQuandoTabelaVazia() {
        when(moderadorRepository.count()).thenReturn(0L);

        bootstrap("ana", SENHA_BOA).run(null);

        ArgumentCaptor<Moderador> capturado = ArgumentCaptor.forClass(Moderador.class);
        verify(moderadorRepository).save(capturado.capture());
        assertThat(capturado.getValue().getLogin()).isEqualTo("ana");
        assertThat(capturado.getValue().isAtivo()).isTrue();
        // O que vai para o banco é o hash, nunca a senha digitada.
        assertThat(capturado.getValue().getSenhaHash())
                .isNotEqualTo(SENHA_BOA)
                .startsWith("{bcrypt}");
    }

    @Test
    @DisplayName("já existindo moderador, não recria nem sobrescreve")
    void naoRecriaQuandoJaExiste() {
        when(moderadorRepository.count()).thenReturn(1L);

        bootstrap("ana", SENHA_BOA).run(null);

        verify(moderadorRepository, never()).save(any());
    }

    @Test
    @DisplayName("sem ambiente configurado, apenas avisa: a app sobe e recusa a moderação")
    void semAmbienteApenasAvisa() {
        when(moderadorRepository.count()).thenReturn(0L);

        assertThatCode(() -> bootstrap("", "").run(null)).doesNotThrowAnyException();
        verify(moderadorRepository, never()).save(any());
    }

    @Test
    @DisplayName("senha curta derruba a subida em vez de deixar a moderação fraca")
    void senhaCurtaFalhaAlto() {
        when(moderadorRepository.count()).thenReturn(0L);

        assertThatThrownBy(() -> bootstrap("ana", "curta").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12");
        verify(moderadorRepository, never()).save(any());
    }
}

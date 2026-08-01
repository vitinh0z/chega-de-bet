package com.chegadebet.service;

import com.chegadebet.domain.model.Moderador;
import com.chegadebet.repository.ModeradorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeradorDetailsServiceTest {

    @Mock
    private ModeradorRepository moderadorRepository;

    @InjectMocks
    private ModeradorDetailsService moderadorDetailsService;

    private Moderador moderador(String login, boolean ativo) {
        Moderador moderador = new Moderador();
        moderador.setLogin(login);
        moderador.setSenhaHash("{bcrypt}$2a$10$hashdementira");
        moderador.setAtivo(ativo);
        return moderador;
    }

    @Test
    @DisplayName("moderador ativo recebe ROLE_MODERADOR e o hash gravado")
    void moderadorAtivo() {
        when(moderadorRepository.findByLogin("ana")).thenReturn(Optional.of(moderador("ana", true)));

        UserDetails detalhes = moderadorDetailsService.loadUserByUsername("ana");

        assertThat(detalhes.getUsername()).isEqualTo("ana");
        assertThat(detalhes.getPassword()).isEqualTo("{bcrypt}$2a$10$hashdementira");
        assertThat(detalhes.isEnabled()).isTrue();
        assertThat(detalhes.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_MODERADOR");
    }

    @Test
    @DisplayName("moderador desligado vem desabilitado, sem apagar a conta")
    void moderadorInativo() {
        when(moderadorRepository.findByLogin("desligada"))
                .thenReturn(Optional.of(moderador("desligada", false)));

        assertThat(moderadorDetailsService.loadUserByUsername("desligada").isEnabled()).isFalse();
    }

    @Test
    @DisplayName("login inexistente usa mensagem genérica, para não revelar quem existe")
    void loginInexistente() {
        when(moderadorRepository.findByLogin("ninguem")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderadorDetailsService.loadUserByUsername("ninguem"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("Credenciais inválidas.");
    }
}

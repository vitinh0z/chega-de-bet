package com.chegadebet.service;

import com.chegadebet.domain.model.Moderador;
import com.chegadebet.repository.ModeradorRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liga a tabela {@code moderador} ao Spring Security.
 * <p>
 * O papel concedido é sempre {@code ROLE_MODERADOR} — o projeto tem um perfil só, e
 * inventar hierarquia antes de precisar dela só cria caminho para errar.
 */
@Service
public class ModeradorDetailsService implements UserDetailsService {

    private final ModeradorRepository moderadorRepository;

    public ModeradorDetailsService(ModeradorRepository moderadorRepository) {
        this.moderadorRepository = moderadorRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        Moderador moderador = moderadorRepository.findByLogin(login)
                // Mensagem genérica e igual à de senha errada: dizer "esse login não
                // existe" entregaria a lista de moderadores a quem tentasse adivinhar.
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));

        return User.withUsername(moderador.getLogin())
                .password(moderador.getSenhaHash())
                .disabled(!moderador.isAtivo())
                .roles("MODERADOR")
                .build();
    }
}

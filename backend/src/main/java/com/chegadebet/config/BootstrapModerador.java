package com.chegadebet.config;

import com.chegadebet.domain.model.Moderador;
import com.chegadebet.repository.ModeradorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria o primeiro moderador na subida, a partir de variável de ambiente.
 * <p>
 * Resolve o problema do ovo e da galinha: a moderação exige conta, e não há tela para
 * criar a primeira. As duas saídas óbvias são piores —
 * <ul>
 *   <li><b>INSERT na migration</b> gravaria uma senha no repositório, que é público. Uma
 *       senha versionada é uma senha vazada, mesmo que trocada depois.</li>
 *   <li><b>Usuário fixo no código</b> teria o mesmo problema, com o agravante de vir
 *       igual em toda instalação.</li>
 * </ul>
 * Aqui a senha só existe no ambiente de quem sobe a aplicação, e nunca é registrada em
 * log. Só age quando a tabela está vazia: reiniciar não recria nem sobrescreve conta.
 *
 * <pre>
 * CHEGADEBET_MODERACAO_BOOTSTRAP_LOGIN=ana
 * CHEGADEBET_MODERACAO_BOOTSTRAP_SENHA=&lt;senha longa, vinda do gerenciador de segredos&gt;
 * </pre>
 */
@Component
public class BootstrapModerador implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapModerador.class);

    // Não é política de senha completa: é o piso que impede "admin/admin" virar produção.
    private static final int TAMANHO_MINIMO_DA_SENHA = 12;

    private final ModeradorRepository moderadorRepository;
    private final PasswordEncoder passwordEncoder;
    private final String login;
    private final String senha;

    public BootstrapModerador(ModeradorRepository moderadorRepository,
                              PasswordEncoder passwordEncoder,
                              @Value("${chegadebet.moderacao.bootstrap.login:}") String login,
                              @Value("${chegadebet.moderacao.bootstrap.senha:}") String senha) {
        this.moderadorRepository = moderadorRepository;
        this.passwordEncoder = passwordEncoder;
        this.login = login;
        this.senha = senha;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (moderadorRepository.count() > 0) {
            return;
        }

        if (login.isBlank() || senha.isBlank()) {
            log.warn("Nenhum moderador cadastrado e nenhum bootstrap configurado. "
                    + "/api/moderacao/** vai recusar todo mundo com 401. Defina "
                    + "CHEGADEBET_MODERACAO_BOOTSTRAP_LOGIN e CHEGADEBET_MODERACAO_BOOTSTRAP_SENHA "
                    + "e suba de novo.");
            return;
        }

        if (senha.length() < TAMANHO_MINIMO_DA_SENHA) {
            // Falha alto: seguir em frente deixaria a moderação com uma senha fraca, e
            // quem subiu não perceberia até alguém entrar.
            throw new IllegalStateException("A senha de bootstrap do moderador precisa de pelo menos "
                    + TAMANHO_MINIMO_DA_SENHA + " caracteres.");
        }

        Moderador moderador = new Moderador();
        moderador.setLogin(login.trim());
        moderador.setSenhaHash(passwordEncoder.encode(senha));
        moderador.setAtivo(true);
        moderadorRepository.save(moderador);

        // O login aparece; a senha, nunca. Log é dado persistido.
        log.info("Moderador inicial criado a partir do ambiente: {}. "
                + "Troque a senha e remova as variáveis de bootstrap.", moderador.getLogin());
    }
}

package com.chegadebet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Quem pode o quê.
 * <p>
 * A regra de fundo do projeto é que denunciar é <b>anônimo</b> e moderar é
 * <b>identificado</b>. Este arquivo é onde essa frase vira comportamento.
 *
 * <h2>Por que cada rota anônima aparece aqui explicitamente</h2>
 * Ter o Spring Security no classpath tranca tudo por padrão. Isso é bom — o padrão
 * seguro é negar —, mas significa que denúncia e emissão de token param de funcionar se
 * ninguém as liberar. Elas estão listadas uma a uma, e não por um curinga, para que
 * abrir uma rota nova seja sempre uma decisão consciente que aparece no diff.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(rota -> rota
                        // Moderar exige identidade real. É isto que faz o quórum contar
                        // pessoas: antes, o X-Moderador era declarado pelo cliente e uma
                        // pessoa só formava quórum sozinha mandando dois nomes.
                        .requestMatchers("/api/moderacao/**").hasRole("MODERADOR")

                        // Denunciar é anônimo por projeto: quem denuncia só prova que tem
                        // um token efêmero válido. Exigir login aqui mataria a premissa.
                        .requestMatchers(HttpMethod.POST, "/api/tokens").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/denuncias").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/denuncias/dominios/*").permitAll()

                        // Documentação pública: descrever a API não é vulnerabilidade, e
                        // a extensão precisa dela para ser auditável por terceiros.
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()

                        // O Actuator vive numa porta de gestão separada, que o
                        // docker-compose não publica: só alcança quem está na rede
                        // interna (o Prometheus). Ver management.server.port.
                        .requestMatchers("/actuator/**").permitAll()

                        // Rota nova nasce negada até alguém decidir o contrário.
                        .anyRequest().denyAll())

                .httpBasic(Customizer.withDefaults())

                // Sem sessão: cada requisição carrega a própria credencial. Não há
                // cookie de sessão para roubar nem estado de login para replicar entre
                // instâncias.
                .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CSRF desligado porque nada aqui depende de credencial que o navegador
                // reenvia sozinho a partir de um cookie de sessão.
                // REVISAR quando existir painel web de verdade: o navegador guarda
                // credencial Basic e pode reenviá-la em requisição de outro site. Nesse
                // dia, ou o CSRF volta, ou a autenticação passa a um token que o
                // navegador não anexa por conta própria.
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * BCrypt com prefixo ({@code {bcrypt}...}), via {@code DelegatingPasswordEncoder}.
     * <p>
     * O prefixo parece detalhe, mas é o que permite trocar de algoritmo daqui a alguns
     * anos sem invalidar as senhas já gravadas: hashes antigos continuam sendo lidos pelo
     * codificador certo enquanto os novos já saem no formato novo.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

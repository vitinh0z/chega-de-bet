package com.chegadebet.web.controller;

import com.chegadebet.service.TokenRateLimiter;
import com.chegadebet.service.TokenService;
import com.chegadebet.web.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emissão do token efêmero que a extensão usa antes de denunciar.
 * <p>
 * Endpoint anônimo: não exige login e não recebe nenhum dado da pessoa.
 *
 * <h2>Ao colocar um proxy reverso na frente</h2>
 * O limite usa {@code getRemoteAddr()}, que hoje é o endereço real porque a aplicação
 * atende direto na 8080. Assim que entrar nginx, Traefik ou um balanceador, esse valor
 * passa a ser o <b>IP do proxy</b> — e aí o mundo inteiro cai em um balde só, derrubando
 * a emissão de token para todo mundo, sem erro nenhum no log. O bloco pronto para
 * descomentar está em {@code application-prod.yml}. Em resumo:
 * <ol>
 *   <li>{@code server.forward-headers-strategy: native}, que usa o {@code RemoteIpValve}
 *       do Tomcat — e <b>não</b> {@code framework}, que aceita o cabeçalho venha de onde
 *       vier, sem lista de proxies confiáveis;</li>
 *   <li>{@code server.tomcat.remoteip.internal-proxies} estreitado ao IP do proxy. O
 *       padrão do Tomcat confia em toda faixa privada, o que deixa qualquer vizinho de
 *       rede interna forjar a origem;</li>
 *   <li>o proxy precisa <b>sobrescrever</b> {@code X-Forwarded-For}, não acrescentar ao
 *       que o cliente mandou.</li>
 * </ol>
 * Ler o cabeçalho na mão, sem proxy confiável na frente, seria pior que não ter limite:
 * qualquer um trocaria de "origem" a cada requisição e passaria direto.
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    private final TokenService tokenService;
    private final TokenRateLimiter tokenRateLimiter;

    public TokenController(TokenService tokenService, TokenRateLimiter tokenRateLimiter) {
        this.tokenService = tokenService;
        this.tokenRateLimiter = tokenRateLimiter;
    }

    /**
     * Emite um token efêmero. O valor em claro aparece só nesta resposta.
     * <p>
     * O limite é cobrado antes de emitir: token recusado não chega a existir no banco.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> emitir(HttpServletRequest request) {
        tokenRateLimiter.registrarEmissao(request.getRemoteAddr());

        TokenResponse response = tokenService.emitir();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

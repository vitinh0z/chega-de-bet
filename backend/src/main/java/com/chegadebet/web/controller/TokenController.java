package com.chegadebet.web.controller;

import com.chegadebet.service.TokenService;
import com.chegadebet.web.dto.TokenResponse;
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
 * <h2>Dívida conhecida: falta rate-limit</h2>
 * Sem limite por IP, qualquer um pede tokens infinitos e fura a dedup por denunciante do
 * {@link com.chegadebet.service.DenunciaService}. Bucket4j não está no {@code pom.xml},
 * então isso é trabalho próprio. Quando for feito, o IP não pode ser persistido: contador
 * em memória ou Redis com TTL curto.
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Emite um token efêmero. O valor em claro aparece só nesta resposta.
     */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> emitir() {
        TokenResponse response = tokenService.emitir();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}

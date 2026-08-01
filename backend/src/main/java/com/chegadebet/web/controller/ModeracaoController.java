package com.chegadebet.web.controller;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.service.ModeracaoService;
import com.chegadebet.web.dto.DominioResponse;
import com.chegadebet.web.dto.RejeicaoRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints do painel de moderação.
 * <p>
 * Exigem autenticação: {@code /api/moderacao/**} é restrito a quem tem
 * {@code ROLE_MODERADOR} (ver {@code SecurityConfig}). A identidade de quem decidiu vem
 * do {@link Authentication}, nunca de um cabeçalho.
 * <p>
 * Isso não é formalidade: o quórum conta moderadores <b>distintos</b>. Enquanto o nome
 * vinha do cabeçalho {@code X-Moderador}, declarado pelo cliente, uma pessoa só formava
 * quórum sozinha mandando dois nomes diferentes — a proteção da allowlist era decorativa.
 */
@RestController
@RequestMapping("/api/moderacao")
public class ModeracaoController {

    private final ModeracaoService moderacaoService;

    public ModeracaoController(ModeracaoService moderacaoService) {
        this.moderacaoService = moderacaoService;
    }

    /**
     * Fila priorizada de domínios em quarentena. Fila vazia também é 200, nunca 404:
     * a fila existe, só está sem itens.
     */
    @GetMapping(path = "/fila", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DominioResponse>> listarFila() {
        return ResponseEntity.ok(moderacaoService.listarFila());
    }

    /**
     * Registra o voto de aprovação. Se o quórum fechar, o domínio entra na blocklist na
     * próxima publicação.
     * <p>
     * Responde 202 Accepted quando o voto entrou mas o quórum ainda não fechou — o
     * domínio volta {@code EM_ANALISE}, ou seja, o pedido foi aceito e o efeito ainda não
     * aconteceu. Quando a aprovação se concretiza, 200 OK.
     */
    @PostMapping(path = "/dominios/{dominioId}/aprovacao", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DominioResponse> aprovar(@PathVariable UUID dominioId,
                                                   Authentication moderador) {
        DominioResponse response = moderacaoService.aprovar(dominioId, moderador.getName());
        HttpStatus status = (response.status() == StatusDominio.EM_ANALISE)
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Rejeita um domínio, com motivo obrigatório. Rejeição não exige quórum: um voto
     * basta, porque manter um site fora da blocklist causa dano menor que bloqueá-lo por
     * engano.
     */
    @PostMapping(path = "/dominios/{dominioId}/rejeicao", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DominioResponse> rejeitar(@PathVariable UUID dominioId,
                                                    Authentication moderador,
                                                    @Valid @RequestBody RejeicaoRequest request) {
        return ResponseEntity.ok(moderacaoService.rejeitar(dominioId, moderador.getName(), request.motivo()));
    }
}

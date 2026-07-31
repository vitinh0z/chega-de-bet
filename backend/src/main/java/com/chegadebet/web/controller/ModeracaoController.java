package com.chegadebet.web.controller;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.service.ModeracaoService;
import com.chegadebet.web.dto.DominioResponse;
import com.chegadebet.web.dto.RejeicaoRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoints do painel de moderação.
 *
 * <h2>Atenção: estes endpoints estão abertos</h2>
 * O projeto ainda não tem Spring Security no {@code pom.xml}. Qualquer um que alcance a
 * API consegue aprovar ou rejeitar domínio. Antes de subir isto em ambiente exposto:
 * <ol>
 *   <li>Adicione {@code spring-boot-starter-security}.</li>
 *   <li>Restrinja {@code /api/moderacao/**} a quem tem papel de moderador.</li>
 *   <li>Troque o header {@code X-Moderador} pela identidade autenticada de verdade —
 *       hoje ele é declarado pelo cliente, então não prova nada e o quórum é burlável.</li>
 * </ol>
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
                                                   @RequestHeader("X-Moderador") String moderador) {
        DominioResponse response = moderacaoService.aprovar(dominioId, moderador);
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
                                                    @RequestHeader("X-Moderador") String moderador,
                                                    @Valid @RequestBody RejeicaoRequest request) {
        return ResponseEntity.ok(moderacaoService.rejeitar(dominioId, moderador, request.motivo()));
    }
}

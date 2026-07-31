package com.chegadebet.web.controller;

import com.chegadebet.service.DenunciaService;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import com.chegadebet.web.dto.DominioResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/denuncias")
public class DenunciaController {

    private final DenunciaService denunciaService;

    public DenunciaController(DenunciaService denunciaService) {
        this.denunciaService = denunciaService;
    }

    // Anônimo, sem login: quem denuncia só prova que tem um token efêmero válido.
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DenunciaResponse> registrar(@Valid @RequestBody DenunciaRequest request) {
        DenunciaResponse response = denunciaService.registrar(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Consulta pública do status de um domínio. Serve ao dashboard de transparência e à
     * tela de "por que este site foi bloqueado".
     * <p>
     * O host vem na URL porque isto é uma leitura, não um envio: GET não leva corpo.
     * Quando o host nunca foi denunciado, o GlobalExceptionHandler traduz a
     * RecursoNaoEncontradoException em 404.
     */
    @GetMapping(path = "/dominios/{host}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DominioResponse> consultarDominio(@PathVariable String host) {
        return ResponseEntity.ok(denunciaService.consultarPorHost(host));
    }
}

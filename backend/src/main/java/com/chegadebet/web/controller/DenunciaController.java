package com.chegadebet.web.controller;

import com.chegadebet.service.DenunciaService;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
}

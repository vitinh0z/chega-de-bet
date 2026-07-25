package com.chegadebet.service;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Denuncia;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.TokenEfemero;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.mapper.DenunciaMapper;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.repository.TokenRepository;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class DenunciaService {

    private final DenunciaRepository denunciaRepository;
    private final DominioRepository dominioRepository;
    private final TokenRepository tokenRepository;
    private final DenunciaMapper denunciaMapper;

    public DenunciaService(DenunciaRepository denunciaRepository,
                            DominioRepository dominioRepository,
                            TokenRepository tokenRepository,
                            DenunciaMapper denunciaMapper) {
        this.denunciaRepository = denunciaRepository;
        this.dominioRepository = dominioRepository;
        this.tokenRepository = tokenRepository;
        this.denunciaMapper = denunciaMapper;
    }

    @Transactional
    public DenunciaResponse registrar(DenunciaRequest request) {
        String denuncianteHash = sha256Hex(request.token());
        validarToken(denuncianteHash);

        Dominio dominio = dominioRepository.findByHost(request.host())
                .orElseGet(() -> criarDominio(request.host()));

        Denuncia existente = denunciaRepository.findByDominioAndDenuncianteHash(dominio, denuncianteHash)
                .orElse(null);
        if (existente != null) {
            return denunciaMapper.toResponse(existente);
        }

        Denuncia denuncia = denunciaMapper.toEntity(request);
        denuncia.setDominio(dominio);
        denuncia.setDenuncianteHash(denuncianteHash);

        Denuncia salva = denunciaRepository.save(denuncia);
        return denunciaMapper.toResponse(salva);
    }

    private void validarToken(String denuncianteHash) {
        TokenEfemero token = tokenRepository.findByValorHash(denuncianteHash)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Token inválido ou expirado."));
        if (token.getExpiraEm().isBefore(Instant.now())) {
            throw new RecursoNaoEncontradoException("Token inválido ou expirado.");
        }
    }

    // Domínio novo NASCE em EM_ANALISE — aprovação/rejeição é sempre decisão humana.
    private Dominio criarDominio(String host) {
        Dominio dominio = new Dominio();
        dominio.setHost(host);
        dominio.setStatus(StatusDominio.EM_ANALISE);
        dominio.setScore(0);
        return dominioRepository.save(dominio);
    }

    // Mesmo algoritmo usado para gravar TokenEfemero.valorHash: o token nunca é
    // guardado em claro, só o seu hash — tanto para validar quanto para pseudonimizar.
    private String sha256Hex(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não disponível", e);
        }
    }
}

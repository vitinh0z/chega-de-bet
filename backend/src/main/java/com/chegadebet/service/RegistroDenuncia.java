package com.chegadebet.service;

import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Denuncia;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.TokenEfemero;
import com.chegadebet.mapper.DenunciaMapper;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Núcleo transacional do registro de denúncia: valida o token, encontra ou cria o
 * domínio, grava a denúncia e recalcula o score — tudo em uma transação só.
 * <p>
 * Existe separado do {@link DenunciaService} por causa das corridas. Dois índices únicos
 * podem estourar aqui ({@code ux_dominio_host} e {@code ux_denuncia_dominio_denunciante})
 * quando duas requisições simultâneas gravam o mesmo host ou a mesma denúncia. Tratar
 * isso <b>dentro</b> da transação não funcionaria: a violação só aparece no flush, o
 * Hibernate marca a transação para rollback e o contexto de persistência fica
 * inutilizável — capturar a exceção ali daria {@code UnexpectedRollbackException} no
 * commit. A recuperação precisa de uma transação nova, e por isso mora um nível acima,
 * em {@link DenunciaService#registrar}.
 */
@Service
class RegistroDenuncia {

    private final DenunciaRepository denunciaRepository;
    private final DominioRepository dominioRepository;
    private final DenunciaMapper denunciaMapper;
    private final TokenService tokenService;
    private final ModeracaoService moderacaoService;

    RegistroDenuncia(DenunciaRepository denunciaRepository,
                     DominioRepository dominioRepository,
                     DenunciaMapper denunciaMapper,
                     TokenService tokenService,
                     ModeracaoService moderacaoService) {
        this.denunciaRepository = denunciaRepository;
        this.dominioRepository = dominioRepository;
        this.denunciaMapper = denunciaMapper;
        this.tokenService = tokenService;
        this.moderacaoService = moderacaoService;
    }

    @Transactional
    DenunciaResponse executar(DenunciaRequest request) {
        // O hash sai de dentro do token validado, não de uma segunda derivação: assim o
        // denunciante_hash é exatamente o valor que já está no banco.
        TokenEfemero token = tokenService.validar(request.token());
        String denuncianteHash = token.getValorHash();

        String host = DenunciaService.normalizarHost(request.host());
        Dominio dominio = dominioRepository.findByHost(host)
                .orElseGet(() -> criarDominio(host));

        // Dedup: o mesmo pseudônimo denunciando de novo devolve a denúncia que já existe.
        // O índice único é a garantia no banco; esta consulta evita o erro no caminho comum.
        Denuncia existente = denunciaRepository.findByDominioAndDenuncianteHash(dominio, denuncianteHash)
                .orElse(null);
        if (existente != null) {
            return denunciaMapper.toResponse(existente);
        }

        Denuncia denuncia = denunciaMapper.toEntity(request);
        denuncia.setDominio(dominio);
        denuncia.setDenuncianteHash(denuncianteHash);
        Denuncia salva = denunciaRepository.save(denuncia);

        // Sem isto o score nasce 0 e fica 0, e a fila de moderação ordena um empate geral.
        moderacaoService.recalcularScore(dominio);

        return denunciaMapper.toResponse(salva);
    }

    // Domínio novo NASCE em EM_ANALISE — aprovação/rejeição é sempre decisão humana.
    private Dominio criarDominio(String host) {
        Dominio dominio = new Dominio();
        dominio.setHost(host);
        dominio.setStatus(StatusDominio.EM_ANALISE);
        dominio.setScore(0);
        return dominioRepository.save(dominio);
    }
}

package com.chegadebet.service;

import com.chegadebet.domain.model.Dominio;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.mapper.DominioMapper;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import com.chegadebet.web.dto.DominioResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Porta de entrada da denúncia anônima.
 * <p>
 * O registro em si vive em {@link RegistroDenuncia}, que é a transação. Esta classe cuida
 * do que precisa acontecer <b>fora</b> dela: a recuperação da corrida entre duas
 * requisições simultâneas.
 */
@Service
public class DenunciaService {

    private static final Logger log = LoggerFactory.getLogger(DenunciaService.class);

    private final RegistroDenuncia registroDenuncia;
    private final DominioRepository dominioRepository;
    private final DominioMapper dominioMapper;

    public DenunciaService(RegistroDenuncia registroDenuncia,
                           DominioRepository dominioRepository,
                           DominioMapper dominioMapper) {
        this.registroDenuncia = registroDenuncia;
        this.dominioRepository = dominioRepository;
        this.dominioMapper = dominioMapper;
    }

    /**
     * Registra uma denúncia anônima. Quem denuncia só prova que tem um token efêmero
     * válido — nenhum dado da pessoa entra aqui.
     *
     * @throws RecursoNaoEncontradoException se o token não existir ou já tiver expirado
     */
    public DenunciaResponse registrar(DenunciaRequest request) {
        try {
            return registroDenuncia.executar(request);
        } catch (DataIntegrityViolationException e) {
            // Corrida perdida: outra requisição simultânea gravou o mesmo host novo ou a
            // mesma denúncia primeiro, e um dos índices únicos rejeitou esta. A transação
            // anterior foi desfeita inteira, então refazer agora encontra o que a outra
            // gravou e devolve o mesmo resultado, em vez de um 500 na cara de quem
            // denunciou. Uma tentativa basta: na segunda passagem a linha já existe.
            log.debug("Corrida no registro de denúncia; refazendo sobre o que a outra requisição gravou");
            return registroDenuncia.executar(request);
        }
    }

    /**
     * Consulta pública do status de um domínio. Só lê: quem chama informa o host e
     * recebe o que o banco já sabe sobre ele.
     *
     * @throws RecursoNaoEncontradoException se o host nunca foi denunciado
     */
    @Transactional(readOnly = true)
    public DominioResponse consultarPorHost(String host) {
        Dominio dominio = dominioRepository.findByHost(normalizarHost(host))
                .orElseThrow(() -> new RecursoNaoEncontradoException("Domínio não encontrado: " + host));
        return dominioMapper.toResponse(dominio);
    }

    /**
     * Normaliza o host na entrada de todo caminho que consulta {@code findByHost}.
     * <p>
     * Sem isto, {@code Exemplo.com} e {@code exemplo.com} viram dois domínios diferentes,
     * com duas filas de moderação e dois scores — e um pode ser aprovado enquanto o outro
     * não. {@link Locale#ROOT} é obrigatório: {@code toLowerCase()} sem locale usa o da
     * JVM, e em turco o {@code I} vira {@code ı} (i sem ponto), então {@code INDIA.com}
     * viraria {@code ındia.com} e nunca casaria com o registro salvo.
     */
    static String normalizarHost(String host) {
        return host.trim().toLowerCase(Locale.ROOT);
    }
}

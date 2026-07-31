package com.chegadebet.service;

import com.chegadebet.config.TokenProperties;
import com.chegadebet.domain.model.TokenEfemero;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.repository.TokenRepository;
import com.chegadebet.web.dto.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Emissão, validação e expurgo do token efêmero.
 * <p>
 * O token é uma identidade anônima, usada só para rate-limit e para contar denunciantes
 * distintos. Ele nunca se liga a uma pessoa real: o banco guarda apenas o hash SHA-256
 * em {@link TokenEfemero}, nunca o valor em claro.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    // Campo estático de propósito: instanciar SecureRandom é caro e semear de novo a
    // cada chamada não acrescenta segurança nenhuma.
    private static final SecureRandom RANDOM = new SecureRandom();

    // 32 bytes = 256 bits de entropia. Em Base64 sem padding viram 43 caracteres,
    // dentro do @Size(max = 512) do DenunciaRequest.
    private static final int TAMANHO_EM_BYTES = 32;

    // Mensagem única para token inexistente e para token expirado. Diferenciar as duas
    // contaria a um atacante se aquele token existe no banco — é enumeração de credencial.
    private static final String TOKEN_INVALIDO = "Token inválido ou expirado.";

    private final TokenRepository tokenRepository;
    private final TokenProperties properties;

    public TokenService(TokenRepository tokenRepository, TokenProperties properties) {
        this.tokenRepository = tokenRepository;
        this.properties = properties;
    }

    /**
     * Emite um token efêmero novo. Não recebe nem grava nenhum dado da pessoa.
     *
     * @return o token em claro (única vez que ele sai daqui) e o instante de expiração
     */
    @Transactional
    public TokenResponse emitir() {
        byte[] bytes = new byte[TAMANHO_EM_BYTES];
        RANDOM.nextBytes(bytes);

        // URL-safe e sem padding: o token viaja em JSON e pode acabar em query string,
        // onde '+', '/' e '=' quebram.
        String valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        TokenEfemero token = new TokenEfemero();
        token.setValorHash(hashDe(valor));
        token.setExpiraEm(Instant.now().plus(properties.ttl()));
        TokenEfemero salvo = tokenRepository.save(token);

        // O valor em claro sai daqui uma única vez, nesta resposta. Nunca logue: log é
        // dado persistido, e um token logado deixa de ser anônimo.
        return new TokenResponse(valor, salvo.getExpiraEm());
    }

    /**
     * Valida um token recebido em claro e devolve o registro correspondente.
     * <p>
     * Devolve a entidade inteira, não um booleano: o {@link DenunciaService} precisa do
     * {@code valorHash} que está dentro dela para pseudonimizar o denunciante — assim o
     * hash gravado na denúncia é exatamente o mesmo que já está no banco, e não uma
     * segunda derivação que poderia divergir.
     *
     * @throws RecursoNaoEncontradoException se o token não existir ou já tiver expirado
     */
    @Transactional(readOnly = true)
    public TokenEfemero validar(String tokenEmClaro) {
        TokenEfemero token = tokenRepository.findByValorHash(hashDe(tokenEmClaro))
                .orElseThrow(() -> new RecursoNaoEncontradoException(TOKEN_INVALIDO));

        if (token.getExpiraEm().isBefore(Instant.now())) {
            throw new RecursoNaoEncontradoException(TOKEN_INVALIDO);
        }
        return token;
    }

    /**
     * SHA-256 em hexadecimal: 64 caracteres, exatamente o tamanho de
     * {@code token_efemero.valor_hash} e de {@code denuncia.denunciante_hash}.
     * <p>
     * É público porque o {@link DenunciaService} precisa do mesmo algoritmo para derivar
     * o pseudônimo do denunciante. Uma segunda implementação em outra classe seria risco
     * real: se as duas divergissem, a validação do token e a dedup de denúncia parariam
     * de casar, e o sintoma só apareceria em produção.
     */
    public String hashDe(String valor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não disponível", e);
        }
    }

    /**
     * Apaga os tokens já expirados. Token expirado não serve para nada e guardá-lo
     * contraria o princípio de reter o mínimo.
     * <p>
     * Roda de hora em hora. O agendamento só funciona porque a classe de aplicação está
     * anotada com {@code @EnableScheduling} — sem ela, o {@code @Scheduled} é ignorado
     * em silêncio, sem erro nenhum.
     *
     * @return quantos tokens saíram
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public int expurgarExpirados() {
        int removidos = tokenRepository.deleteExpirados(Instant.now());
        // Só a contagem: os valores nunca entram no log.
        log.info("Expurgo de tokens efêmeros: {} removidos", removidos);
        return removidos;
    }
}

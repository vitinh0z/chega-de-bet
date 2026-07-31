package com.chegadebet.service;

import com.chegadebet.domain.enums.CategoriaAposta;
import com.chegadebet.domain.enums.StatusDominio;
import com.chegadebet.domain.model.Denuncia;
import com.chegadebet.domain.model.Dominio;
import com.chegadebet.domain.model.TokenEfemero;
import com.chegadebet.exception.RecursoNaoEncontradoException;
import com.chegadebet.mapper.DenunciaMapperImpl;
import com.chegadebet.mapper.DominioMapper;
import com.chegadebet.repository.DenunciaRepository;
import com.chegadebet.repository.DominioRepository;
import com.chegadebet.web.dto.DenunciaRequest;
import com.chegadebet.web.dto.DenunciaResponse;
import com.chegadebet.web.dto.DominioResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito puro, com um mapa fazendo as vezes do banco para os domínios: é o que permite
 * verificar que duas grafias do mesmo host convergem em UM registro.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DenunciaServiceTest {

    @Mock
    private DenunciaRepository denunciaRepository;
    @Mock
    private DominioRepository dominioRepository;
    @Mock
    private TokenService tokenService;
    @Mock
    private ModeracaoService moderacaoService;

    private RegistroDenuncia registroDenuncia;
    private DenunciaService denunciaService;

    /** Domínios "persistidos", indexados por host — o índice único do banco em memória. */
    private final Map<String, Dominio> dominios = new HashMap<>();

    @BeforeEach
    void setUp() {
        DominioMapper dominioMapper = dominio -> new DominioResponse(
                dominio.getId(), dominio.getHost(), dominio.getStatus(),
                dominio.getScore(), dominio.getCriadoEm());

        registroDenuncia = new RegistroDenuncia(denunciaRepository, dominioRepository,
                new DenunciaMapperImpl(), tokenService, moderacaoService);
        denunciaService = new DenunciaService(registroDenuncia, dominioRepository, dominioMapper);

        when(dominioRepository.findByHost(anyString()))
                .thenAnswer(chamada -> Optional.ofNullable(dominios.get(chamada.<String>getArgument(0))));
        when(dominioRepository.save(any(Dominio.class))).thenAnswer(chamada -> {
            Dominio dominio = chamada.getArgument(0);
            if (dominio.getId() == null) {
                dominio.setId(UUID.randomUUID());
            }
            dominios.put(dominio.getHost(), dominio);
            return dominio;
        });

        when(denunciaRepository.findByDominioAndDenuncianteHash(any(), anyString())).thenReturn(Optional.empty());
        when(denunciaRepository.save(any(Denuncia.class))).thenAnswer(chamada -> chamada.getArgument(0));
    }

    /** Faz o token valer e devolver o hash pedido, como o TokenService devolveria. */
    private void tokenValido(String tokenEmClaro, String hash) {
        TokenEfemero token = new TokenEfemero();
        token.setValorHash(hash);
        token.setExpiraEm(Instant.now().plusSeconds(3600));
        when(tokenService.validar(tokenEmClaro)).thenReturn(token);
    }

    private DenunciaRequest pedido(String host, String token) {
        return new DenunciaRequest(host, CategoriaAposta.ESPORTIVA, token);
    }

    @Test
    @DisplayName("Exemplo.COM e exemplo.com viram UM domínio só")
    void hostNormalizadoNaoDuplicaDominio() {
        tokenValido("token-a", "hash-a");
        tokenValido("token-b", "hash-b");

        denunciaService.registrar(pedido("Exemplo.COM", "token-a"));
        denunciaService.registrar(pedido("exemplo.com", "token-b"));

        assertThat(dominios).containsOnlyKeys("exemplo.com");
        verify(dominioRepository, times(1)).save(any(Dominio.class));
    }

    @Test
    @DisplayName("o host guardado é o normalizado, não o que veio na requisição")
    void hostGuardadoEmMinusculas() {
        tokenValido("token-a", "hash-a");

        DenunciaResponse response = denunciaService.registrar(pedido("  ApostaAqui.COM  ", "token-a"));

        assertThat(response.host()).isEqualTo("apostaaqui.com");
    }

    @Test
    @DisplayName("mesmo token no mesmo host não cria segunda denúncia")
    void dedupPorDenunciante() {
        tokenValido("token-a", "hash-a");

        denunciaService.registrar(pedido("apostaqui.com", "token-a"));

        Dominio dominio = dominios.get("apostaqui.com");
        Denuncia jaExiste = new Denuncia();
        jaExiste.setId(UUID.randomUUID());
        jaExiste.setDominio(dominio);
        jaExiste.setDenuncianteHash("hash-a");
        jaExiste.setCategoriaAposta(CategoriaAposta.ESPORTIVA);
        when(denunciaRepository.findByDominioAndDenuncianteHash(dominio, "hash-a"))
                .thenReturn(Optional.of(jaExiste));

        DenunciaResponse response = denunciaService.registrar(pedido("apostaqui.com", "token-a"));

        assertThat(response.id()).isEqualTo(jaExiste.getId());
        verify(denunciaRepository, times(1)).save(any(Denuncia.class));
    }

    @Test
    @DisplayName("domínio novo nasce EM_ANALISE e com o score recalculado")
    void dominioNovoNasceEmAnaliseComScore() {
        tokenValido("token-a", "hash-a");

        DenunciaResponse response = denunciaService.registrar(pedido("apostaqui.com", "token-a"));

        assertThat(response.status()).isEqualTo(StatusDominio.EM_ANALISE);
        verify(moderacaoService).recalcularScore(dominios.get("apostaqui.com"));
    }

    @Test
    @DisplayName("o denunciante_hash vem de dentro do token validado")
    void hashVemDoToken() {
        tokenValido("token-a", "hash-do-banco");

        denunciaService.registrar(pedido("apostaqui.com", "token-a"));

        org.mockito.ArgumentCaptor<Denuncia> capturada = org.mockito.ArgumentCaptor.forClass(Denuncia.class);
        verify(denunciaRepository).save(capturada.capture());
        assertThat(capturada.getValue().getDenuncianteHash()).isEqualTo("hash-do-banco");
    }

    @Test
    @DisplayName("token inválido não cria denúncia nem domínio")
    void tokenInvalidoNaoGravaNada() {
        when(tokenService.validar("token-ruim"))
                .thenThrow(new RecursoNaoEncontradoException("Token inválido ou expirado."));

        assertThatThrownBy(() -> denunciaService.registrar(pedido("apostaqui.com", "token-ruim")))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        verify(dominioRepository, never()).save(any(Dominio.class));
        verify(denunciaRepository, never()).save(any(Denuncia.class));
        assertThat(dominios).isEmpty();
    }

    @Test
    @DisplayName("corrida no índice único é refeita, não vira 500")
    void corridaERefeita() {
        tokenValido("token-a", "hash-a");

        // Primeira passagem: outra requisição gravou o mesmo host antes desta.
        // Segunda: já existe e o registro segue normalmente.
        Dominio jaGravado = new Dominio();
        jaGravado.setId(UUID.randomUUID());
        jaGravado.setHost("apostaqui.com");
        jaGravado.setStatus(StatusDominio.EM_ANALISE);

        when(dominioRepository.save(any(Dominio.class)))
                .thenThrow(new DataIntegrityViolationException("ux_dominio_host"))
                .thenAnswer(chamada -> chamada.getArgument(0));

        DenunciaResponse response = denunciaService.registrar(pedido("apostaqui.com", "token-a"));

        assertThat(response.host()).isEqualTo("apostaqui.com");
        verify(dominioRepository, times(2)).save(any(Dominio.class));
    }

    @Test
    @DisplayName("consultarPorHost normaliza antes de procurar")
    void consultarNormalizaHost() {
        Dominio dominio = new Dominio();
        dominio.setId(UUID.randomUUID());
        dominio.setHost("apostaqui.com");
        dominio.setStatus(StatusDominio.APROVADO);
        dominios.put("apostaqui.com", dominio);

        assertThat(denunciaService.consultarPorHost("ApostaQui.COM").status())
                .isEqualTo(StatusDominio.APROVADO);
    }

    @Test
    @DisplayName("consultarPorHost de host nunca denunciado lança RecursoNaoEncontradoException")
    void consultarHostInexistente() {
        assertThatThrownBy(() -> denunciaService.consultarPorHost("nunca-visto.com"))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }
}

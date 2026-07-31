package com.chegadebet.service;

import com.chegadebet.config.TokenProperties;
import com.chegadebet.exception.LimiteExcedidoException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de emissão de token por origem, em memória.
 * <p>
 * Sem ele, qualquer um pede tokens infinitos e fura a dedup por denunciante: cada token
 * novo é um pseudônimo novo, e um domínio pode ser inflado por uma pessoa só. O limite é
 * o que faz "denunciantes distintos" significar alguma coisa.
 *
 * <h2>Nada é persistido</h2>
 * Os baldes vivem no heap e a chave é o <b>hash</b> do endereço, nunca o endereço em
 * claro. Isso não é anonimização — o espaço de IPv4 é pequeno o bastante para força bruta
 * — mas evita IP legível na memória, em dump ou em qualquer log. Reiniciar a aplicação
 * zera os contadores, o que é aceitável para uma janela curta.
 *
 * <h2>O que este desenho não cobre</h2>
 * <ul>
 *   <li><b>Mais de uma instância.</b> Cada uma tem seus próprios baldes, então N réplicas
 *       multiplicam o limite efetivo por N. Enquanto a app roda em uma instância só, isto
 *       basta; ao escalar horizontalmente é o momento de trocar por um backend
 *       compartilhado (Redis com TTL curto), e não antes.</li>
 *   <li><b>IP compartilhado.</b> CGNAT de operadora móvel põe muita gente atrás do mesmo
 *       endereço, e é justamente de celular que vem boa parte das denúncias. Por isso a
 *       capacidade é folgada e configurável: apertar demais bloqueia denunciante real, que
 *       é o erro caro deste projeto. Resolver de verdade pede identidade por instalação ou
 *       prova de trabalho, não um contador por IP.</li>
 * </ul>
 */
@Component
public class TokenRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenRateLimiter.class);

    private final Map<String, Balde> baldes = new ConcurrentHashMap<>();
    private final TokenProperties.RateLimit config;
    private final TokenService tokenService;
    private final Counter recusasPorOrigem;
    private final Counter recusasPorTeto;

    public TokenRateLimiter(TokenProperties properties,
                            TokenService tokenService,
                            MeterRegistry meterRegistry,
                            @Value("${server.forward-headers-strategy:none}") String estrategiaDeEncaminhamento) {
        this.config = properties.rateLimit();
        this.tokenService = tokenService;
        anunciarOrigemUsada(estrategiaDeEncaminhamento);

        // Dois motivos bem diferentes de recusa, separados por rótulo: "origem" é o limite
        // funcionando; "teto" é a memória no limite e merece alarme. Nenhum rótulo
        // identifica quem chamou — a métrica conta, não identifica.
        this.recusasPorOrigem = recusas(meterRegistry, "origem");
        this.recusasPorTeto = recusas(meterRegistry, "teto");
        meterRegistry.gauge("chegadebet.token.rate_limit.origens", baldes, Map::size);
    }

    /**
     * Diz na subida de onde vem o endereço que separa os baldes.
     * <p>
     * Existe porque a falha aqui é silenciosa: com um proxy reverso na frente e nenhuma
     * configuração de encaminhamento, {@code getRemoteAddr()} devolve sempre o IP do
     * proxy, todo mundo cai em um balde só e a emissão de token morre para todos — sem
     * erro nenhum no log. Esta linha faz o modo efetivo aparecer no Loki desde o start.
     */
    private static void anunciarOrigemUsada(String estrategia) {
        if ("none".equalsIgnoreCase(estrategia)) {
            log.info("Rate-limit separando por endereço direto do peer "
                    + "(server.forward-headers-strategy=none). Se houver proxy reverso na frente, "
                    + "TODAS as requisições caem em um balde só — ver o javadoc do TokenController.");
        } else {
            log.info("Rate-limit separando por endereço encaminhado "
                    + "(server.forward-headers-strategy={}). O proxy precisa SOBRESCREVER "
                    + "X-Forwarded-For, e a lista de proxies confiáveis precisa ser estreita.", estrategia);
        }
    }

    private static Counter recusas(MeterRegistry meterRegistry, String motivo) {
        return Counter.builder("chegadebet.token.rate_limit.recusas")
                .description("Emissões de token recusadas pelo rate-limit")
                .tag("motivo", motivo)
                .register(meterRegistry);
    }

    /**
     * Contabiliza uma emissão para esta origem.
     *
     * @param origem endereço de quem chamou. Usado só para derivar a chave do balde.
     * @throws LimiteExcedidoException se a origem estourou a janela
     */
    public void registrarEmissao(String origem) {
        Balde balde = baldeDe(tokenService.hashDe(origem));

        ConsumptionProbe consumo = balde.bucket.tryConsumeAndReturnRemaining(1);
        if (!consumo.isConsumed()) {
            recusasPorOrigem.increment();
            throw new LimiteExcedidoException(Duration.ofNanos(consumo.getNanosToWaitForRefill()));
        }
    }

    private Balde baldeDe(String chave) {
        Balde existente = baldes.get(chave);
        if (existente != null) {
            existente.tocar();
            return existente;
        }

        // Origem nova: só aceita entrar se ainda houver espaço no mapa.
        if (baldes.size() >= config.maxChaves()) {
            expurgarOciosos();
        }
        if (baldes.size() >= config.maxChaves()) {
            // Teto atingido mesmo depois do expurgo. Recusa em vez de crescer sem limite:
            // memória é o recurso que não dá para recuperar depois. Encher o mapa exige
            // endereços reais e distintos (TCP não deixa forjar origem), então chegar aqui
            // é sinal de enxurrada de verdade — e o log existe para isso aparecer.
            recusasPorTeto.increment();
            log.warn("Teto de origens do rate-limit atingido ({}). Recusando emissões de origens novas.",
                    config.maxChaves());
            throw new LimiteExcedidoException(config.janela());
        }

        return baldes.computeIfAbsent(chave, ignorada -> new Balde(novoBucket()));
    }

    private Bucket novoBucket() {
        // refillGreedy: a capacidade volta aos poucos ao longo da janela, em vez de tudo
        // de uma vez no fim dela. Evita a rajada sincronizada na virada do período.
        return Bucket.builder()
                .addLimit(limite -> limite
                        .capacity(config.capacidade())
                        .refillGreedy(config.capacidade(), config.janela()))
                .build();
    }

    /**
     * Descarta as origens paradas há pelo menos uma janela inteira.
     * <p>
     * Um balde ocioso por esse tempo já se recompôs por completo, então jogá-lo fora não
     * perde contagem nenhuma — só devolve memória e para de guardar o hash de quem não
     * está mais usando a API. Guardar para sempre seria vazamento e retenção sem motivo.
     * <p>
     * Roda a cada dez minutos e também sob demanda, quando o teto de chaves é alcançado.
     * É público para não depender de {@code @Scheduled} enxergar método de visibilidade
     * reduzida: o expurgo que não roda é justamente a falha silenciosa que interessa evitar.
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void expurgarOciosos() {
        long limite = System.nanoTime() - config.janela().toNanos();
        int antes = baldes.size();

        // Corrida possível e inofensiva: uma requisição pode estar consumindo um balde que
        // acabou de sair do mapa. Como só sai o que está cheio, o pior caso é uma emissão
        // a mais para aquela origem.
        baldes.values().removeIf(balde -> balde.ociosoDesde(limite));

        int removidos = antes - baldes.size();
        if (removidos > 0) {
            log.debug("Expurgo de baldes de rate-limit: {} origens ociosas removidas", removidos);
        }
    }

    /** Balde com a marca do último uso, que é o que permite expurgar por ociosidade. */
    private static final class Balde {

        private final Bucket bucket;
        private volatile long ultimoUso;

        private Balde(Bucket bucket) {
            this.bucket = bucket;
            this.ultimoUso = System.nanoTime();
        }

        private void tocar() {
            this.ultimoUso = System.nanoTime();
        }

        private boolean ociosoDesde(long limite) {
            return ultimoUso < limite;
        }
    }
}

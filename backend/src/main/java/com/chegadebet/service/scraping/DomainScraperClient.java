package com.chegadebet.service.scraping;

import com.chegadebet.config.ScraperProperties;
import com.chegadebet.domain.enums.MotivoFalhaScraping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busca o HTML da home pública de um domínio, dentro de limites duros de tempo e tamanho.
 * <p>
 * Tudo o que este cliente faz é uma requisição {@code GET} para a raiz do site — a mesma
 * página que qualquer pessoa vê ao digitar o endereço no navegador. Não manda cookie, não
 * manda credencial, não segue formulário e não visita caminho nenhum além de {@code /}.
 *
 * <h2>Os três limites, e por que cada um existe</h2>
 * <ul>
 *   <li><b>Tamanho.</b> Pedimos {@code Range: bytes=0-N} e, além disso, contamos os bytes
 *       na leitura. O cabeçalho é um pedido; o contador é a garantia.</li>
 *   <li><b>Tempo.</b> Timeout de conexão e timeout total. O primeiro pega o host que não
 *       atende; só o segundo pega o host que atende e entrega um byte por segundo.</li>
 *   <li><b>Hops.</b> Redirecionamento é seguido na mão, com teto, e cada destino volta
 *       para o guarda de SSRF antes de virar uma conexão nova.</li>
 * </ul>
 *
 * <h2>Por que os redirects não são automáticos</h2>
 * {@code Redirect.NORMAL} entregaria o resultado final e esconderia o caminho. Duas coisas
 * se perdem aí. A primeira é segurança: o {@code HttpClient} seguiria um {@code Location}
 * apontando para {@code 10.0.0.5} sem consultar ninguém, e toda a validação feita na URL
 * de entrada valeria só para o primeiro hop. A segunda é economia: uma página de afiliado
 * que redireciona para uma casa de aposta que o projeto já conhece pode ser encerrada no
 * cabeçalho do primeiro hop, sem baixar corpo nenhum.
 */
@Component
public class DomainScraperClient {

    private static final Logger log = LoggerFactory.getLogger(DomainScraperClient.class);

    // Só o que o alvo precisa para responder a home. Nada de cookie, nada de credencial.
    private static final String ACCEPT = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1";
    private static final String ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9";

    // charset do Content-Type: "text/html; charset=UTF-8"
    private static final Pattern CHARSET_NO_CABECALHO =
            Pattern.compile("charset\\s*=\\s*\"?([\\w.:-]+)\"?", Pattern.CASE_INSENSITIVE);

    // <meta charset="..."> e <meta http-equiv="Content-Type" content="...charset=...">.
    // Procurado só no começo do documento, que é onde o padrão exige que ele esteja.
    private static final Pattern CHARSET_NA_META =
            Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([\\w.:-]+)", Pattern.CASE_INSENSITIVE);
    private static final int BYTES_PARA_FAREJAR_META = 4096;

    private final HttpClient http;
    private final GuardaSsrf guardaSsrf;
    private final ScraperProperties propriedades;

    public DomainScraperClient(GuardaSsrf guardaSsrf, ScraperProperties propriedades) {
        this.guardaSsrf = guardaSsrf;
        this.propriedades = propriedades;
        this.http = HttpClient.newBuilder()
                .connectTimeout(propriedades.timeoutConexao())
                // A decisão central deste cliente. Ver o javadoc da classe.
                .followRedirects(HttpClient.Redirect.NEVER)
                // Virtual threads: cada domínio prende uma thread durante a espera de
                // rede, que é quase toda a duração da chamada. Com threads de plataforma,
                // a concorrência configurada viraria esse mesmo número de threads
                // sistemicamente ociosas.
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Baixa a home pública de {@code host}.
     *
     * @param host          o domínio, já normalizado, sem esquema e sem caminho
     * @param hostConhecido pergunta feita a cada destino de redirecionamento: este domínio
     *                      já é conhecido do projeto? Quando a resposta é sim, a leitura
     *                      para no cabeçalho, sem baixar o corpo. É a economia de banda
     *                      mais cedo possível — antes até do teto de bytes
     * @return o documento ou o motivo de não haver um. Nunca lança
     */
    public RespostaScraping buscar(String host, Predicate<String> hostConhecido) {
        URI uri;
        try {
            // IDN.toASCII converte domínio acentuado para punycode. Sem isto,
            // "apostaç.com" estoura no construtor de URI, e a pré-análise devolveria
            // "esquema inválido" para um domínio que só tem acento no nome.
            String hostAscii = java.net.IDN.toASCII(host, java.net.IDN.ALLOW_UNASSIGNED);
            // Só HTTPS, e só a raiz. Não há tentativa de cair para HTTP: o fallback
            // transformaria um certificado inválido — que é exatamente o sinal de que
            // alguém está no meio do caminho — em um motivo para desistir da criptografia.
            uri = new URI("https", hostAscii, "/", null);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new RespostaScraping.Falha(MotivoFalhaScraping.ESQUEMA_INVALIDO, host);
        }
        return buscar(uri, hostConhecido);
    }

    /**
     * O laço de hops sobre uma URL já montada.
     * <p>
     * Visível no pacote para o teste poder apontar o cliente a um servidor local em
     * {@code http://localhost}. A alternativa seria subir um servidor HTTPS de teste com
     * certificado próprio, e trocar o {@code SSLContext} padrão da JVM para confiar nele —
     * um teste que mexe em estado global da JVM para provar um comportamento de rede.
     * <p>
     * A costura não abre buraco nenhum: quem entra por aqui passa pelo mesmo
     * {@link GuardaSsrf} a cada hop, então {@code file:}, {@code gopher:} e endereço
     * interno continuam recusados. A única regra que este caminho pula é a de montar a
     * URL como {@code https://host/}, e essa é a regra que {@link #buscar(String, Predicate)}
     * tem como única responsabilidade.
     */
    RespostaScraping buscar(URI uriInicial, Predicate<String> hostConhecido) {
        URI uri = uriInicial;
        for (int hop = 0; hop <= propriedades.maxRedirects(); hop++) {
            // Revalidado a cada volta, e não só na entrada: sem isto, um alvo público
            // devolveria um Location para a rede interna e o segundo hop já estaria
            // dentro dela.
            Optional<MotivoFalhaScraping> bloqueio = guardaSsrf.avaliar(uri);
            if (bloqueio.isPresent()) {
                return new RespostaScraping.Falha(bloqueio.get(), uri.toString());
            }

            switch (umHop(uri, hostConhecido)) {
                case ResultadoHop.Concluido(RespostaScraping resposta) -> {
                    return resposta;
                }
                case ResultadoHop.Segue(URI destino) -> uri = destino;
            }
        }
        // Passamos do teto de hops. Um site legítimo não precisa de quatro saltos para
        // mostrar a própria home; isto aqui é laço de redirecionamento ou armadilha.
        return new RespostaScraping.Falha(MotivoFalhaScraping.REDIRECT_EXCEDIDO, uri.toString());
    }

    /**
     * Um salto: manda a requisição, e devolve o resultado final ou o próximo destino.
     * <p>
     * O caso do redirecionamento é interno ao cliente e por isso não faz parte de
     * {@link RespostaScraping} — quem chama {@link #buscar} nunca vê um hop intermediário.
     */
    private ResultadoHop umHop(URI uri, Predicate<String> hostConhecido) {
        HttpRequest.Builder requisicao = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(propriedades.timeoutTotal())
                .header("User-Agent", propriedades.userAgent())
                .header("Accept", ACCEPT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                // Pede só o pedaço que vamos usar. Quando o alvo respeita (a maioria dos
                // sites atrás de CDN respeita), a economia acontece na rede dele e na
                // nossa; quando ignora e manda 200 com tudo, o contador de bytes na
                // leitura corta assim mesmo. O cabeçalho é otimização, não garantia.
                .header("Range", "bytes=0-" + (propriedades.maxBytesComoInt() - 1));

        try {
            HttpResponse<InputStream> resposta =
                    http.send(requisicao.build(), HttpResponse.BodyHandlers.ofInputStream());
            return interpretar(resposta, uri, hostConhecido);
        } catch (HttpConnectTimeoutException e) {
            // Antes de HttpTimeoutException de propósito: é subclasse dela, e o
            // compilador exige a mais específica primeiro. Os dois viram TIMEOUT — a
            // distinção entre "não conectou a tempo" e "não terminou a tempo" não muda
            // nada para o moderador nem para a decisão de repetir.
            return falha(MotivoFalhaScraping.TIMEOUT, uri);
        } catch (HttpTimeoutException e) {
            return falha(MotivoFalhaScraping.TIMEOUT, uri);
        } catch (SSLException e) {
            log.debug("Falha de TLS em {}: {}", uri.getHost(), e.getMessage());
            return falha(MotivoFalhaScraping.CERTIFICADO_INVALIDO, uri);
        } catch (ConnectException e) {
            return falha(MotivoFalhaScraping.CONEXAO_RECUSADA, uri);
        } catch (UnknownHostException e) {
            return falha(MotivoFalhaScraping.DNS_NAO_RESOLVE, uri);
        } catch (IOException e) {
            log.debug("Erro de leitura em {}: {}", uri.getHost(), e.getMessage());
            return falha(MotivoFalhaScraping.ERRO_DE_LEITURA, uri);
        } catch (InterruptedException e) {
            // Restaurar a flag é obrigatório: quem interrompeu está desligando o worker, e
            // engolir a interrupção aqui faria o lote inteiro continuar rodando no
            // desligamento da aplicação.
            Thread.currentThread().interrupt();
            return falha(MotivoFalhaScraping.ERRO_DE_LEITURA, uri);
        }
    }

    private ResultadoHop interpretar(HttpResponse<InputStream> resposta,
                                     URI uri,
                                     Predicate<String> hostConhecido) throws IOException {
        int status = resposta.statusCode();

        if (status >= 300 && status < 400) {
            return redirecionar(resposta, uri, hostConhecido);
        }
        if (status >= 400 && status < 500) {
            fechar(resposta);
            return falha(MotivoFalhaScraping.HTTP_4XX, uri);
        }
        if (status >= 500) {
            fechar(resposta);
            return falha(MotivoFalhaScraping.HTTP_5XX, uri);
        }
        if (!pareceHtml(resposta)) {
            // PDF, imagem ou JSON. Baixar seria gastar rede em algo que o matcher não tem
            // como interpretar.
            fechar(resposta);
            return falha(MotivoFalhaScraping.CONTEUDO_NAO_HTML, uri);
        }

        Corpo corpo = lerAteOTeto(resposta);
        String html = decodificar(corpo, resposta);
        return new ResultadoHop.Concluido(
                new RespostaScraping.Documento(html, uri.toString(), corpo.tamanho(), false));
    }

    private ResultadoHop falha(MotivoFalhaScraping motivo, URI uri) {
        return new ResultadoHop.Concluido(new RespostaScraping.Falha(motivo, uri.toString()));
    }

    /**
     * Resolve o {@code Location} do hop atual e decide se vale a pena seguir.
     * <p>
     * O {@code Location} pode ser relativo ({@code /br/}), e {@code URI.resolve} é quem
     * transforma isso na URL absoluta correta em relação ao hop atual.
     */
    private ResultadoHop redirecionar(HttpResponse<InputStream> resposta,
                                      URI uri,
                                      Predicate<String> hostConhecido) throws IOException {
        fechar(resposta);

        String location = resposta.headers().firstValue("location").orElse(null);
        if (location == null || location.isBlank()) {
            // 3xx sem destino não é redirecionamento, é resposta quebrada.
            return falha(MotivoFalhaScraping.ERRO_DE_LEITURA, uri);
        }

        URI destino;
        try {
            destino = uri.resolve(location);
        } catch (IllegalArgumentException e) {
            return falha(MotivoFalhaScraping.ESQUEMA_INVALIDO, uri);
        }

        // O corte do primeiro hop conhecido. Chega antes do teto de bytes na ordem de
        // economia: aqui nem o corpo desta resposta é lido.
        if (destino.getHost() != null && hostConhecido.test(destino.getHost())) {
            log.debug("Pré-análise encerrada no hop {}: destino {} já é conhecido do projeto",
                    uri.getHost(), destino.getHost());
            return new ResultadoHop.Concluido(
                    new RespostaScraping.Documento("", destino.toString(), 0, true));
        }
        return new ResultadoHop.Segue(destino);
    }

    /**
     * Lê no máximo {@code max-bytes}, contando byte a byte.
     * <p>
     * O contador é a garantia real do limite. O cabeçalho {@code Range} é um pedido que o
     * alvo pode ignorar, e {@code Content-Length} é um número que o alvo declara — um
     * servidor hostil declara 1 KB e manda um fluxo infinito. Só a contagem na leitura
     * sabe quanto entrou de fato.
     * <p>
     * Fechar o {@code InputStream} no meio derruba a conexão e faz o resto da resposta
     * nunca ser transferido, que é onde a economia acontece de verdade.
     */
    private Corpo lerAteOTeto(HttpResponse<InputStream> resposta) throws IOException {
        int teto = propriedades.maxBytesComoInt();
        byte[] destino = new byte[capacidadeInicial(resposta, teto)];
        int total = 0;

        try (InputStream entrada = resposta.body()) {
            int lidos;
            while (total < teto && (lidos = entrada.read(destino, total, destino.length - total)) != -1) {
                total += lidos;
                if (total == destino.length && total < teto) {
                    // Só cresce quando o alvo mentiu no Content-Length ou não declarou
                    // nada. Dobrar, com o teto como limite, faz no máximo mais uma cópia.
                    destino = Arrays.copyOf(destino, Math.min(destino.length << 1, teto));
                }
            }
        }
        return new Corpo(destino, total);
    }

    /**
     * Quanto alocar antes de começar a ler.
     * <p>
     * Com {@code Content-Length} confiável, o array nasce do tamanho exato e a leitura não
     * copia nada. Sem ele, começa em 16 KB — que cobre a home da maioria dos alvos medidos
     * — e dobra até o teto.
     * <p>
     * O {@code Content-Length} é usado apenas para <b>dimensionar</b>, nunca para decidir
     * quando parar: um servidor hostil declara 1 KB e manda um fluxo infinito. Quem para a
     * leitura é o contador de bytes, e é por isso que o valor declarado ser mentira não
     * causa problema nenhum aqui.
     */
    private int capacidadeInicial(HttpResponse<InputStream> resposta, int teto) {
        long declarado = resposta.headers().firstValueAsLong("content-length").orElse(-1);
        if (declarado > 0) {
            return (int) Math.min(declarado, teto);
        }
        return Math.min(16 * 1024, teto);
    }

    /**
     * Os bytes lidos e quantos deles valem.
     * <p>
     * O array pode ser maior que o conteúdo, e é de propósito: aparar com
     * {@code Arrays.copyOf} para devolver um array do tamanho exato seria mais uma cópia
     * de até 100 KB, para produzir algo que só vai ser decodificado em seguida — e
     * {@code new String(bytes, offset, length, charset)} aceita o comprimento direto.
     */
    private record Corpo(byte[] bytes, int tamanho) {
    }

    /**
     * Descobre o charset e transforma os bytes em texto.
     * <p>
     * Ordem: cabeçalho {@code Content-Type}, depois {@code <meta charset>} no começo do
     * documento, e UTF-8 no fim. A meta entra porque é comum o servidor mandar
     * {@code text/html} pelado e declarar o charset só dentro do HTML — e com o charset
     * errado o termo "Curaçao" vira lixo e nenhuma licenciadora é encontrada.
     * <p>
     * O truncamento no teto pode partir um caractere multibyte ao meio. O decodificador
     * troca a sobra por um caractere de substituição, que não casa com nada — é o
     * comportamento certo, e por isso o modo permissivo em vez de exceção.
     */
    private String decodificar(Corpo corpo, HttpResponse<InputStream> resposta) {
        Charset charset = resposta.headers().firstValue("content-type")
                .flatMap(tipo -> extrair(CHARSET_NO_CABECALHO, tipo))
                .or(() -> extrair(CHARSET_NA_META, prefixoAscii(corpo)))
                .orElse(StandardCharsets.UTF_8);
        return new String(corpo.bytes(), 0, corpo.tamanho(), charset);
    }

    private Optional<Charset> extrair(Pattern padrao, String texto) {
        Matcher matcher = padrao.matcher(texto);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Charset.forName(matcher.group(1)));
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            // Charset inventado ou com erro de digitação no HTML do alvo. Cai no default.
            return Optional.empty();
        }
    }

    /**
     * O começo do documento lido como ASCII, só para procurar a declaração de charset.
     * <p>
     * ISO-8859-1 mapeia todo byte para um caractere, então a leitura nunca falha nem
     * inventa substituições — e a declaração de charset é ASCII puro em qualquer
     * codificação que valha a pena considerar.
     */
    private String prefixoAscii(Corpo corpo) {
        return new String(corpo.bytes(), 0, Math.min(corpo.tamanho(), BYTES_PARA_FAREJAR_META),
                StandardCharsets.ISO_8859_1);
    }

    private boolean pareceHtml(HttpResponse<InputStream> resposta) {
        // Sem Content-Type, tentamos: servidor mal configurado que serve HTML é comum, e
        // recusar por isso descartaria alvo legítimo da análise.
        return resposta.headers().firstValue("content-type")
                .map(tipo -> tipo.toLowerCase(Locale.ROOT))
                .map(tipo -> tipo.contains("html") || tipo.contains("xml") || tipo.contains("text/plain"))
                .orElse(true);
    }

    /**
     * Descarta o corpo de uma resposta que não vamos ler.
     * <p>
     * Sem isto a conexão fica presa no pool esperando um corpo que ninguém consome, e a
     * concorrência configurada vaza aos poucos até o worker parar de fazer requisições.
     */
    private void fechar(HttpResponse<InputStream> resposta) throws IOException {
        resposta.body().close();
    }

    /**
     * O que um hop produziu. Estado interno do laço: nunca sai desta classe.
     * <p>
     * Existe separado de {@link RespostaScraping} porque "siga para o próximo destino" é
     * um caso que só o laço de hops entende. Enfiá-lo na interface pública obrigaria todo
     * consumidor a tratar um estado que ele nunca vai receber.
     */
    private sealed interface ResultadoHop {

        /** Acabou aqui: há um documento ou uma falha para devolver. */
        record Concluido(RespostaScraping resposta) implements ResultadoHop {
        }

        /** Ainda não acabou: o alvo mandou seguir para outro lugar. */
        record Segue(URI destino) implements ResultadoHop {
        }
    }
}

package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.TipoSinalScraping;
import com.chegadebet.domain.enums.TrechoDocumento;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ahocorasick.trie.PayloadEmit;
import org.ahocorasick.trie.PayloadTrie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Procura assinaturas de aposta no nome do domínio e no texto da página.
 * <p>
 * Dois autômatos Aho-Corasick, construídos uma única vez na subida e imutáveis desde
 * então. Imutáveis é o que os torna seguros para as virtual threads do worker
 * compartilharem sem sincronização nenhuma — e um autômato por requisição custaria a
 * construção inteira do dicionário a cada domínio da fila.
 *
 * <h2>Por que dois, e não um</h2>
 * O nome do domínio e o texto da página são universos diferentes. {@code bet} no host é
 * sinal; {@code bet} no meio de um parágrafo quase não é. E as regras de casamento não
 * são as mesmas: o host quebra em ponto e hífen, o texto quebra em espaço e pontuação.
 * Um autômato só obrigaria a escolher uma das duas regras e errar na outra.
 *
 * <h2>As três defesas contra falso positivo</h2>
 * <ol>
 *   <li><b>Palavra inteira.</b> {@code bet} não casa dentro de {@code diabetes}, de
 *       {@code Betim} nem de {@code Roberto}.</li>
 *   <li><b>Sem script, sem style, sem atributo.</b> O Jsoup entrega só o texto que uma
 *       pessoa lê. É o que descarta os falsos positivos medidos:
 *       {@code betbr-blaze-prodfavicon.ico}, a variável CSS {@code bet-win-color} e a
 *       lista de ícones {@code icon_names=...,casino,...} do Material Symbols.</li>
 *   <li><b>Acento normalizado dos dois lados.</b> {@code Curaçao}, {@code Curacao} e
 *       {@code CURAÇAO} são a mesma coisa para o autômato, e o moderador continua vendo a
 *       grafia correta na tela.</li>
 * </ol>
 *
 * <h2>Custo</h2>
 * A medição mostrou a normalização respondendo por 55% do tempo de analisar um documento
 * de 100 KB, e o parse do Jsoup acontecendo duas vezes por documento. As duas coisas
 * foram resolvidas: {@link #normalizar} faz uma passagem só, e {@link #analisar} devolve
 * as assinaturas e o veredito de documento vazio a partir de um único parse.
 */
@Component
public class AssinaturaMatcher {

    private static final Logger log = LoggerFactory.getLogger(AssinaturaMatcher.class);

    private static final String CAMINHO_DICIONARIO = "scraper/assinaturas.json";

    /**
     * O domínio de primeiro nível reservado às casas de aposta autorizadas pelo Ministério
     * da Fazenda. É a regra mais barata e mais precisa que existe hoje no Brasil: ninguém
     * além de uma casa autorizada consegue registrar sob ele.
     * <p>
     * Fica em código, e não no dicionário, porque é regra de sufixo — não de palavra.
     */
    private static final String SUFIXO_BET_BR = ".bet.br";

    /** Primeiro caractere do bloco Unicode "Combining Diacritical Marks" (U+0300). */
    private static final char PRIMEIRO_DIACRITICO = 0x0300;

    /** Tamanho do bloco: U+0300 a U+036F, os 112 caracteres que o NFD gera para acentos. */
    private static final int TAMANHO_BLOCO_DIACRITICOS = 0x0070;

    /**
     * Abaixo disto o HTML é shell de SPA, não página.
     * <p>
     * A medição usou 15 KB: {@code esportesdasorte.com} entrega 10 KB de Angular com
     * {@code <title>} vazio, e {@code blaze.com} cabe em 24 KB com conteúdo de verdade.
     */
    private static final int TAMANHO_MINIMO_DE_DOCUMENTO = 15 * 1024;

    /** Abaixo disto, o texto visível não é página — é shell esperando o JavaScript. */
    private static final int TEXTO_MINIMO_DE_DOCUMENTO = 200;

    private final PayloadTrie<AssinaturaConhecida> automatoDeHost;
    private final PayloadTrie<AssinaturaConhecida> automatoDeConteudo;

    /**
     * Quantos termos distintos o dicionário tem, para dimensionar o bitset de dedup.
     * <p>
     * Cada termo recebe um id sequencial na construção, e a deduplicação usa esse id em
     * vez do texto. Ver {@link Coletor}.
     */
    private final int totalDeTermos;

    public AssinaturaMatcher() throws IOException {
        // O ObjectMapper é criado aqui, e não injetado: ele lê um arquivo estático do
        // classpath uma única vez, na subida. Injetar o mapper da aplicação amarraria a
        // pré-análise à configuração de serialização da API REST — duas coisas sem
        // relação — e faria este componente depender de um bean que nem sempre existe:
        // com webEnvironment = NONE, o Boot não registra ObjectMapper.
        Dicionario dicionario = carregar(new ObjectMapper());

        // Índice compartilhado pelos dois autômatos: o mesmo termo em host e em conteúdo
        // recebe o mesmo id, então o bitset de dedup vale para os dois.
        Map<String, AssinaturaConhecida> indice = new HashMap<>();
        this.automatoDeHost = montar(dicionario.host(), indice);
        this.automatoDeConteudo = montar(dicionario.conteudo(), indice);
        this.totalDeTermos = indice.size();

        log.info("Dicionário da pré-análise carregado: {} termos de host, {} termos de conteúdo",
                contar(dicionario.host()), contar(dicionario.conteudo()));
    }

    /**
     * Assinaturas no nome do domínio. Não abre conexão nenhuma.
     * <p>
     * É a parte da pré-análise que custa zero: dá para rodar em todo domínio da fila antes
     * de decidir quais valem uma requisição.
     */
    public List<AssinaturaEncontrada> analisarHost(String host) {
        Coletor coletor = new Coletor(totalDeTermos);

        // A regra do sufixo precisa dos pontos, então ela olha o host normalizado como
        // TEXTO. Casar ".bet.br" contra a versão com separadores trocados por espaço
        // nunca daria positivo — os pontos não existem mais lá.
        if (normalizar(host, Separador.TEXTO).endsWith(SUFIXO_BET_BR)) {
            coletor.adicionarAvulso(new AssinaturaEncontrada(
                    SUFIXO_BET_BR, TipoSinalScraping.DOMINIO_BET_BR, TrechoDocumento.HOST));
        }

        // Já o casamento de termos precisa dos separadores virados espaço: é o que faz o
        // "onlyWholeWords" enxergar as partes do host como palavras. Sem isso,
        // "bet.nacional.com" seria uma palavra só e o termo "bet" não casaria com nada.
        // São duas passagens sobre no máximo 253 caracteres — o host é curto por definição.
        coletor.casar(automatoDeHost, normalizar(host, Separador.HOST), TrechoDocumento.HOST);
        return coletor.resultado();
    }

    /**
     * Analisa o conteúdo da página em um parse só.
     * <p>
     * Recebe o HTML cru — inteiro ou já truncado no teto de bytes, tanto faz. O corte no
     * meio de uma tag não atrapalha: o Jsoup fecha o que ficou aberto e devolve o texto
     * que deu para ler.
     * <p>
     * Devolve as duas respostas juntas de propósito. Elas vinham de dois métodos públicos,
     * e o worker chamava os dois — o que significava parsear 100 KB de HTML duas vezes por
     * domínio. O parse respondia por 15% do custo total, metade dele jogada fora.
     *
     * @param html o documento como veio do alvo
     */
    public LeituraDeConteudo analisar(String html) {
        if (html == null || html.isBlank()) {
            return new LeituraDeConteudo(List.of(), true);
        }
        Document documento = Jsoup.parse(html);
        String corpo = corpo(documento);

        Coletor coletor = new Coletor(totalDeTermos);
        // A ordem importa: o coletor mantém a primeira ocorrência de cada termo, então
        // procurar no título antes do corpo faz a evidência mais forte ser a que sobra.
        coletor.casar(automatoDeConteudo, normalizar(titulo(documento), Separador.TEXTO), TrechoDocumento.TITULO);
        coletor.casar(automatoDeConteudo, normalizar(metas(documento), Separador.TEXTO), TrechoDocumento.META);
        coletor.casar(automatoDeConteudo, normalizar(corpo, Separador.TEXTO), TrechoDocumento.CORPO);

        return new LeituraDeConteudo(coletor.resultado(), vazio(html, documento, corpo));
    }

    /**
     * Se o HTML é shell de SPA: pequeno demais e sem título nem texto.
     * <p>
     * Sem esta pergunta, uma casa de aposta feita em Angular seria indistinguível de um
     * site limpo — as duas devolvem lista vazia de assinaturas. Metade da amostra medida
     * caiu exatamente nesse caso, então tratá-lo como "nada encontrado" seria enganar o
     * moderador na metade das vezes.
     */
    private boolean vazio(String html, Document documento, String corpo) {
        return html.length() < TAMANHO_MINIMO_DE_DOCUMENTO
                && documento.title().isBlank()
                && corpo.length() < TEXTO_MINIMO_DE_DOCUMENTO;
    }

    // ===== Extração de texto =====

    private String titulo(Document documento) {
        return documento.title() + ' ' + conteudoDaMeta(documento, "meta[property=og:title]");
    }

    private String metas(Document documento) {
        return conteudoDaMeta(documento, "meta[name=description]")
                + ' ' + conteudoDaMeta(documento, "meta[name=keywords]")
                + ' ' + conteudoDaMeta(documento, "meta[property=og:description]")
                + ' ' + conteudoDaMeta(documento, "meta[property=og:site_name]");
    }

    /**
     * O texto visível, e só ele.
     * <p>
     * {@code Element.text()} do Jsoup já ignora {@code <script>} e {@code <style>} e não
     * devolve valor de atributo. Isso é o descarte de ruído da issue #135 saindo de graça:
     * nome de arquivo, variável CSS e lista de ícones vivem justamente nesses três lugares.
     */
    private String corpo(Document documento) {
        Element body = documento.body();
        return body == null ? "" : body.text();
    }

    private String conteudoDaMeta(Document documento, String seletor) {
        Element meta = documento.selectFirst(seletor);
        return meta == null ? "" : meta.attr("content");
    }

    // ===== Normalização =====

    /** O que conta como separador de palavra em cada universo. */
    private enum Separador {
        /** Texto corrido: só espaço em branco separa. */
        TEXTO,
        /** Host: ponto, hífen e sublinhado também separam ({@code bet.nacional.com}). */
        HOST
    }

    /**
     * Minúsculas, sem acento e com o espaço colapsado — em uma passagem só.
     * <p>
     * A versão anterior fazia quatro: {@code Normalizer.normalize}, uma regex para tirar
     * os diacríticos, {@code toLowerCase} e outra regex para colapsar espaço. Cada uma
     * percorria o texto inteiro e alocava uma String nova, então analisar 100 KB alocava
     * meio megabyte de lixo e respondia por 55% do custo total.
     * <p>
     * O {@code NFD} continua sendo necessário — é ele que separa {@code ç} em {@code c}
     * mais a cedilha combinante, e sem essa separação não há acento para descartar. Mas o
     * resto virou um laço sobre {@code char}, escrevendo direto em um buffer do tamanho
     * certo.
     *
     * <h2>Três correções que vieram junto</h2>
     * <ul>
     *   <li><b>{@code &nbsp;} agora separa palavras.</b> A regex {@code \s} do Java
     *       <b>não</b> casa {@code U+00A0}, e o Jsoup preserva esse caractere no valor de
     *       um atributo. Na prática, {@code Pragmatic&nbsp;Play} em uma
     *       {@code meta description} não casava com o termo do dicionário — e meta tag é
     *       exatamente onde estava o único sinal do {@code blaze.com} na medição.</li>
     *   <li><b>Sem dependência de locale.</b> {@code Character.toLowerCase} é definido
     *       pelo Unicode, não pelo locale da JVM, então o problema do {@code I} turco
     *       deixa de existir em vez de ser contornado com {@code Locale.ROOT}.</li>
     *   <li><b>O host usa o mesmo caminho.</b> Antes ele passava por uma regex própria
     *       para trocar {@code . - _} por espaço; agora é o mesmo laço, com um teste a
     *       mais.</li>
     * </ul>
     */
    private static String normalizar(String texto, Separador separador) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        // O NFD só realoca quando há algo a decompor. Em texto puramente ASCII — a maior
        // parte de um HTML — o JDK devolve a mesma instância sem copiar nada.
        String decomposto = Normalizer.isNormalized(texto, Normalizer.Form.NFD)
                ? texto
                : Normalizer.normalize(texto, Normalizer.Form.NFD);

        char[] saida = new char[decomposto.length()];
        int destino = 0;
        boolean espacoPendente = false;

        for (int i = 0; i < decomposto.length(); i++) {
            char c = decomposto.charAt(i);

            // Teste de faixa sem dois comparadores: o unsigned wrap-around de char faz
            // qualquer coisa abaixo de U+0300 virar um valor enorme, que não passa no
            // limite. É o bloco U+0300–U+036F, o mesmo que a regex \p{InCombiningDiacriticalMarks}
            // casava — só que sem montar um autômato de regex para isso.
            if ((char) (c - PRIMEIRO_DIACRITICO) < TAMANHO_BLOCO_DIACRITICOS) {
                continue;
            }

            if (ehSeparador(c, separador)) {
                // Marca em vez de escrever: assim uma sequência de separadores vira um
                // espaço só, e o espaço final nunca chega a ser escrito — o que dispensa
                // o trim() que existia no fim.
                espacoPendente = destino > 0;
                continue;
            }
            if (espacoPendente) {
                saida[destino++] = ' ';
                espacoPendente = false;
            }
            saida[destino++] = Character.toLowerCase(c);
        }
        return new String(saida, 0, destino);
    }

    /**
     * O espaço não separável. Escrito pelo número de propósito: como
     * literal ele é invisível no código e some no primeiro editor que 'limpar' espaços
     * em branco — mesma convenção de PRIMEIRO_DIACRITICO.
     */
    private static final char ESPACO_NAO_SEPARAVEL = 0x00A0;

    private static boolean ehSeparador(char c, Separador separador) {
        // c <= ' ' pega espaço, tabulação, quebra de linha e todo caractere de controle em
        // um comparador só, porque todos ficam abaixo de U+0020. O não separável fica fora
        // dessa faixa e precisa do teste próprio — é exatamente o que o \s da regex ignorava.
        if (c <= ' ' || c == ESPACO_NAO_SEPARAVEL) {
            return true;
        }
        return separador == Separador.HOST && (c == '.' || c == '-' || c == '_');
    }

    // ===== Coleta =====

    /**
     * Junta as assinaturas encontradas, sem repetir termo.
     * <p>
     * A deduplicação usa um bitset indexado pelo id do termo, e não um {@code HashSet} de
     * String. O dicionário é fechado e conhecido na subida, então cada termo já tem um id
     * — e comparar um bit custa menos que calcular o hash de {@code "apostas esportivas"}
     * a cada ocorrência, em um texto que pode repetir o mesmo termo centenas de vezes.
     * <p>
     * {@code long[]} e não {@code BitSet}: são no máximo dois {@code long} para os 95
     * termos de hoje, e o {@code BitSet} embrulharia isso em um objeto com verificação de
     * limite a cada acesso.
     */
    private static final class Coletor {

        private final long[] vistos;
        private final List<AssinaturaEncontrada> encontradas = new ArrayList<>();

        Coletor(int totalDeTermos) {
            // Divisão por 64 arredondando para cima, sem divisão: (n + 63) >>> 6.
            this.vistos = new long[(totalDeTermos + 63) >>> 6];
        }

        void casar(PayloadTrie<AssinaturaConhecida> automato, String texto, TrechoDocumento trecho) {
            if (texto.isEmpty()) {
                return;
            }
            for (PayloadEmit<AssinaturaConhecida> emit : automato.parseText(texto)) {
                AssinaturaConhecida conhecida = emit.getPayload();
                if (marcar(conhecida.id())) {
                    encontradas.add(new AssinaturaEncontrada(
                            conhecida.termo(), conhecida.tipo(), trecho));
                }
            }
        }

        /** Sinal que não vem do dicionário, como o sufixo {@code .bet.br}. */
        void adicionarAvulso(AssinaturaEncontrada assinatura) {
            encontradas.add(assinatura);
        }

        /** Marca o termo e devolve se ele ainda não tinha sido visto. */
        private boolean marcar(int id) {
            int palavra = id >>> 6;          // qual long da tabela
            long bit = 1L << (id & 63);      // qual bit dentro dele; o & 63 é o módulo 64
            if ((vistos[palavra] & bit) != 0) {
                return false;
            }
            vistos[palavra] |= bit;
            return true;
        }

        List<AssinaturaEncontrada> resultado() {
            return List.copyOf(encontradas);
        }
    }

    // ===== Construção =====

    private PayloadTrie<AssinaturaConhecida> montar(Map<TipoSinalScraping, List<String>> termos,
                                                    Map<String, AssinaturaConhecida> indice) {
        PayloadTrie.PayloadTrieBuilder<AssinaturaConhecida> builder = PayloadTrie.builder();
        builder.ignoreCase()
                // A defesa contra "bet" dentro de "diabetes". Sem ela o dicionário inteiro
                // vira uma máquina de falso positivo.
                .onlyWholeWords()
                // Fica só o casamento mais longo quando dois termos se sobrepõem:
                // "apostas esportivas" ganha de "apostas", que é o termo mais fraco.
                .ignoreOverlaps();

        if (termos != null) {
            for (Map.Entry<TipoSinalScraping, List<String>> entrada : termos.entrySet()) {
                for (String termo : entrada.getValue()) {
                    String chave = normalizar(termo, Separador.TEXTO);

                    // O mesmo termo pode estar em mais de um tipo (por exemplo "cassino"
                    // em host e em conteúdo). A primeira classificação vence, e as duas
                    // ocorrências compartilham o mesmo id — que é o que faz o bitset de
                    // dedup valer para os dois autômatos.
                    //
                    // get + put em vez de computeIfAbsent: o id novo é o tamanho atual do
                    // mapa, e ler o tamanho de dentro da função de mapeamento do
                    // computeIfAbsent é justamente o que o contrato do HashMap proíbe.
                    AssinaturaConhecida conhecida = indice.get(chave);
                    if (conhecida == null) {
                        conhecida = new AssinaturaConhecida(indice.size(), termo, entrada.getKey());
                        indice.put(chave, conhecida);
                    }

                    // O payload viaja com o autômato: o Emit devolve o objeto direto, sem
                    // uma consulta a mapa por ocorrência encontrada no texto.
                    builder.addKeyword(chave, conhecida);
                }
            }
        }
        return builder.build();
    }

    private Dicionario carregar(ObjectMapper objectMapper) throws IOException {
        try (InputStream entrada = new ClassPathResource(CAMINHO_DICIONARIO).getInputStream()) {
            return objectMapper.readValue(entrada, Dicionario.class);
        }
        // Sem tratamento de erro aqui de propósito: dicionário ausente ou malformado
        // derruba a subida da aplicação. A alternativa seria subir com um dicionário
        // vazio, e aí a pré-análise rodaria sem nunca encontrar nada — um sistema que
        // parece funcionar e não faz nada é pior que um que não sobe.
    }

    private int contar(Map<TipoSinalScraping, List<String>> termos) {
        return termos == null ? 0 : termos.values().stream().mapToInt(List::size).sum();
    }

    /**
     * O que a análise de um documento produziu.
     *
     * @param assinaturas    as evidências, sem repetição de termo
     * @param documentoVazio shell de SPA: não há o que ler porque o conteúdo depende de
     *                       JavaScript, que a pré-análise não executa
     */
    public record LeituraDeConteudo(List<AssinaturaEncontrada> assinaturas, boolean documentoVazio) {
    }

    /**
     * Um termo do dicionário.
     *
     * @param id    posição no bitset de deduplicação
     * @param termo a grafia que o moderador vê, não a normalizada
     * @param tipo  o que a presença dele indica
     */
    private record AssinaturaConhecida(int id, String termo, TipoSinalScraping tipo) {
    }

    /**
     * O arquivo {@code assinaturas.json}.
     * <p>
     * {@code ignoreUnknown} existe para o campo {@code leiaMe}, que é documentação para
     * quem edita o arquivo e não tem par nenhum aqui.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Dicionario(Map<TipoSinalScraping, List<String>> host,
                              Map<TipoSinalScraping, List<String>> conteudo) {
    }
}

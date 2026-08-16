package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.TipoSinalScraping;
import com.chegadebet.domain.enums.TrechoDocumento;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** Ponto e hífen são o que separa palavras dentro de um host. */
    private static final Pattern SEPARADOR_DE_HOST = Pattern.compile("[.\\-_]+");

    /** Sequências de espaço, quebra de linha e tabulação viram um espaço só. */
    private static final Pattern ESPACO_REPETIDO = Pattern.compile("\\s+");

    private static final Pattern DIACRITICOS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * Abaixo disto o HTML é shell de SPA, não página.
     * <p>
     * A medição usou 15 KB: {@code esportesdasorte.com} entrega 10 KB de Angular com
     * {@code <title>} vazio, e {@code blaze.com} cabe em 24 KB com conteúdo de verdade.
     */
    private static final int TAMANHO_MINIMO_DE_DOCUMENTO = 15 * 1024;

    private final Trie automatoDeHost;
    private final Trie automatoDeConteudo;

    /**
     * Chave normalizada para o termo na grafia do dicionário.
     * <p>
     * O autômato casa sobre texto sem acento e em minúsculas, então {@code Emit} devolve
     * {@code "curacao egaming"}. O moderador precisa ver {@code "Curaçao eGaming"}, e é
     * este mapa que faz o caminho de volta.
     */
    private final Map<String, AssinaturaConhecida> porChave;

    /**
     * O {@link ObjectMapper} é criado aqui, e não injetado.
     * <p>
     * Ele lê um arquivo estático do classpath uma única vez, na subida. Injetar o mapper
     * da aplicação amarraria a pré-análise à configuração de serialização da API REST —
     * duas coisas sem relação nenhuma — e faria este componente depender de um bean que
     * nem sempre existe: com {@code webEnvironment = NONE}, o Boot não registra
     * {@code ObjectMapper}, e o contexto de teste quebrava por causa disso.
     */
    public AssinaturaMatcher() throws IOException {
        Dicionario dicionario = carregar(new ObjectMapper());

        Map<String, AssinaturaConhecida> indice = new HashMap<>();
        this.automatoDeHost = montar(dicionario.host(), indice);
        this.automatoDeConteudo = montar(dicionario.conteudo(), indice);
        this.porChave = Map.copyOf(indice);

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
        String normalizado = normalizar(host);
        List<AssinaturaEncontrada> encontradas = new ArrayList<>();

        if (normalizado.endsWith(SUFIXO_BET_BR)) {
            encontradas.add(new AssinaturaEncontrada(
                    SUFIXO_BET_BR, TipoSinalScraping.DOMINIO_BET_BR, TrechoDocumento.HOST));
        }

        // Trocar os separadores por espaço é o que faz "onlyWholeWords" enxergar as partes
        // do host como palavras. Sem isto, "bet.nacional.com" seria uma palavra só e o
        // termo "bet" não casaria com nada.
        String comoTexto = SEPARADOR_DE_HOST.matcher(normalizado).replaceAll(" ");
        encontradas.addAll(casar(automatoDeHost, comoTexto, TrechoDocumento.HOST));

        return dedupe(encontradas);
    }

    /**
     * Assinaturas no conteúdo da página.
     * <p>
     * Recebe o HTML cru — inteiro ou já truncado no teto de bytes, tanto faz. O corte no
     * meio de uma tag não atrapalha: o Jsoup fecha o que ficou aberto e devolve o texto
     * que deu para ler.
     *
     * @param html o documento como veio do alvo
     * @return as evidências, sem repetição, cada uma com o trecho onde apareceu
     */
    public List<AssinaturaEncontrada> analisarConteudo(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        Document documento = Jsoup.parse(html);

        List<AssinaturaEncontrada> encontradas = new ArrayList<>();
        // A ordem importa: dedupe() mantém a primeira ocorrência de cada termo, então
        // procurar no título antes do corpo faz a evidência mais forte ser a que sobra.
        encontradas.addAll(casar(automatoDeConteudo, titulo(documento), TrechoDocumento.TITULO));
        encontradas.addAll(casar(automatoDeConteudo, metas(documento), TrechoDocumento.META));
        encontradas.addAll(casar(automatoDeConteudo, corpo(documento), TrechoDocumento.CORPO));

        return dedupe(encontradas);
    }

    /**
     * Se o HTML é shell de SPA: pequeno demais e sem título útil.
     * <p>
     * Sem esta pergunta, uma casa de aposta feita em Angular seria indistinguível de um
     * site limpo — as duas devolvem lista vazia de assinaturas. Metade da amostra medida
     * caiu exatamente nesse caso, então tratá-lo como "nada encontrado" seria enganar o
     * moderador na metade das vezes.
     */
    public boolean pareceDocumentoVazio(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        if (html.length() >= TAMANHO_MINIMO_DE_DOCUMENTO) {
            return false;
        }
        Document documento = Jsoup.parse(html);
        return documento.title().isBlank() && corpo(documento).length() < 200;
    }

    // ===== Extração de texto =====

    private String titulo(Document documento) {
        return juntar(documento.title(), conteudoDaMeta(documento, "meta[property=og:title]"));
    }

    private String metas(Document documento) {
        return juntar(
                conteudoDaMeta(documento, "meta[name=description]"),
                conteudoDaMeta(documento, "meta[name=keywords]"),
                conteudoDaMeta(documento, "meta[property=og:description]"),
                conteudoDaMeta(documento, "meta[property=og:site_name]"));
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

    private String juntar(String... partes) {
        return String.join(" ", partes);
    }

    // ===== Casamento =====

    private List<AssinaturaEncontrada> casar(Trie automato, String texto, TrechoDocumento trecho) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        List<AssinaturaEncontrada> encontradas = new ArrayList<>();
        for (Emit emit : automato.parseText(normalizar(texto))) {
            AssinaturaConhecida conhecida = porChave.get(emit.getKeyword());
            if (conhecida != null) {
                encontradas.add(new AssinaturaEncontrada(conhecida.termo(), conhecida.tipo(), trecho));
            }
        }
        return encontradas;
    }

    /**
     * Uma linha por termo, mantendo a primeira aparição.
     * <p>
     * Repetição não é evidência nova. Uma página que diz "cassino" quarenta vezes não é
     * mais suspeita que uma que diz uma vez — e sem esta limpeza a lista de evidências na
     * tela do moderador viraria centenas de linhas iguais.
     */
    private List<AssinaturaEncontrada> dedupe(List<AssinaturaEncontrada> encontradas) {
        Set<String> vistos = new LinkedHashSet<>();
        List<AssinaturaEncontrada> unicas = new ArrayList<>();
        for (AssinaturaEncontrada assinatura : encontradas) {
            if (vistos.add(assinatura.termo())) {
                unicas.add(assinatura);
            }
        }
        return List.copyOf(unicas);
    }

    /**
     * Minúsculas, sem acento e com o espaço colapsado.
     * <p>
     * Os três passos, aplicados igualmente ao dicionário e ao texto, são o que faz
     * {@code CURAÇAO} casar com {@code curacao}. O colapso de espaço não é detalhe: o
     * texto de um HTML vem cheio de quebra de linha e indentação, e sem ele o termo
     * {@code "Pragmatic Play"} nunca casaria com {@code "Pragmatic\n     Play"}.
     * <p>
     * {@link Locale#ROOT} é obrigatório no {@code toLowerCase}, pelo mesmo motivo já
     * documentado em {@code DenunciaService.normalizarHost}: em turco o {@code I} vira
     * um {@code i} sem ponto, e o dicionário deixaria de casar conforme o locale da JVM.
     */
    private static String normalizar(String texto) {
        String semAcento = DIACRITICOS.matcher(
                Normalizer.normalize(texto, Normalizer.Form.NFD)).replaceAll("");
        return ESPACO_REPETIDO.matcher(semAcento.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    // ===== Construção =====

    private Trie montar(Map<TipoSinalScraping, List<String>> termos,
                        Map<String, AssinaturaConhecida> indice) {
        Trie.TrieBuilder builder = Trie.builder()
                .ignoreCase()
                // A defesa contra "bet" dentro de "diabetes". Sem ela o dicionário inteiro
                // vira uma máquina de falso positivo.
                .onlyWholeWords()
                // Fica só o casamento mais longo quando dois termos se sobrepõem:
                // "apostas esportivas" ganha de "apostas", que é o termo mais fraco.
                .ignoreOverlaps();

        if (termos != null) {
            termos.forEach((tipo, lista) -> lista.forEach(termo -> {
                String chave = normalizar(termo);
                builder.addKeyword(chave);
                // putIfAbsent: o mesmo termo pode estar em mais de um tipo (por exemplo
                // "cassino" em host e em conteúdo). A primeira classificação vence, e a
                // segunda não sobrescreve em silêncio.
                indice.putIfAbsent(chave, new AssinaturaConhecida(termo, tipo));
            }));
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

    /** Um termo do dicionário: a grafia que o moderador vê e o que ele significa. */
    private record AssinaturaConhecida(String termo, TipoSinalScraping tipo) {
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

package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.TipoSinalScraping;
import com.chegadebet.domain.enums.TrechoDocumento;
import com.chegadebet.domain.scraping.AssinaturaEncontrada;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O matcher contra o dicionário de verdade, sem mock.
 * <p>
 * Testar contra um dicionário inventado verificaria o algoritmo e deixaria passar o que
 * custa caro aqui: um termo mal escolhido em {@code assinaturas.json}. O falso positivo é
 * a falha crítica do projeto, e ele nasce no dicionário, não no autômato.
 */
class AssinaturaMatcherTest {

    private static AssinaturaMatcher matcher;

    @BeforeAll
    static void carregarDicionario() throws IOException {
        matcher = new AssinaturaMatcher();
    }

    /** Atalho: a maioria dos testes só olha as assinaturas, não o veredito de vazio. */
    private static List<AssinaturaEncontrada> assinaturasDe(String html) {
        return matcher.analisar(html).assinaturas();
    }

    // ===== Detecção por tipo de assinatura =====

    @Test
    @DisplayName("Encontra o selo da licenciadora no rodapé")
    void encontraLicenciadora() {
        String html = """
                <html><body>
                  <footer>Licenciado por Curaçao eGaming, licença 1668/JAZ.</footer>
                </body></html>
                """;

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::tipo)
                .contains(TipoSinalScraping.LICENCIADORA);
    }

    @Test
    @DisplayName("Encontra o provedor de slots citado na página")
    void encontraProvedorDeSlots() {
        String html = "<html><body><p>Jogos de Pragmatic Play e Evolution Gaming</p></body></html>";

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::termo)
                .contains("Pragmatic Play", "Evolution Gaming");
    }

    @Test
    @DisplayName("Encontra termos de KYC e de depósito por Pix")
    void encontraKycEDeposito() {
        String html = """
                <html><body>
                  <p>Faça seu depósito via Pix. O saque mínimo é de R$ 20.</p>
                  <p>Conclua a verificação de identidade para sacar.</p>
                </body></html>
                """;

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::tipo)
                .contains(TipoSinalScraping.KYC_DEPOSITO);
    }

    @Test
    @DisplayName("Marca o trecho: o termo no título é evidência mais forte que no corpo")
    void registraOTrechoOndeOTermoApareceu() {
        String html = """
                <html><head><title>Cassino online e apostas esportivas</title></head>
                <body><p>bem-vindo</p></body></html>
                """;

        assertThat(assinaturasDe(html))
                .isNotEmpty()
                .allMatch(assinatura -> assinatura.trecho() == TrechoDocumento.TITULO);
    }

    @Test
    @DisplayName("Lê as meta tags, que é onde o sinal do blaze.com estava")
    void encontraNasMetaTags() {
        // Reproduz a medição: no blaze.com o único sinal útil estava em meta keywords.
        String html = """
                <html><head>
                  <meta name="keywords" content="esports betting casino dice roulette">
                </head><body></body></html>
                """;

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::trecho)
                .contains(TrechoDocumento.META);
    }

    // ===== Os dois autômatos não se misturam (issue #135) =====

    @Test
    @DisplayName("Assinatura de conteúdo não é procurada no host")
    void assinaturaDeConteudoNaoVazaParaOHost() {
        // "Pragmatic Play" é termo de conteúdo. Mesmo escrito no nome do domínio, o
        // autômato de host não o conhece.
        assertThat(matcher.analisarHost("pragmatic-play.com")).isEmpty();
    }

    @Test
    @DisplayName("O sufixo .bet.br é sinal decisivo e não custa conexão")
    void reconheceOSufixoBetBr() {
        assertThat(matcher.analisarHost("casadeaposta.bet.br"))
                .extracting(AssinaturaEncontrada::tipo)
                .contains(TipoSinalScraping.DOMINIO_BET_BR);
    }

    @Test
    @DisplayName("bet.br fora do sufixo não vale sinal decisivo")
    void naoConfundeBetBrNoMeioDoNome() {
        // O sinal vale porque bet.br é domínio de primeiro nível reservado. Um host que
        // apenas contém as letras não herda essa garantia.
        assertThat(matcher.analisarHost("bet.br.exemplo.com"))
                .extracting(AssinaturaEncontrada::tipo)
                .doesNotContain(TipoSinalScraping.DOMINIO_BET_BR);
    }

    @Test
    @DisplayName("O host casa por partes separadas em ponto e hífen")
    void casaPartesDoHost() {
        assertThat(matcher.analisarHost("mega-cassino.com"))
                .extracting(AssinaturaEncontrada::termo)
                .contains("cassino");
    }

    // ===== Ausência de falso positivo =====

    @Test
    @DisplayName("HTML sem nenhuma assinatura devolve lista vazia")
    void htmlLimpoNaoProduzSinal() {
        String html = """
                <html><head><title>Receita de bolo de cenoura</title></head>
                <body><p>Misture a farinha e os ovos. Leve ao forno por 40 minutos.</p></body></html>
                """;

        assertThat(assinaturasDe(html)).isEmpty();
    }

    @Test
    @DisplayName("bet não casa dentro de diabetes, Betim nem Roberto")
    void exigeFronteiraDePalavra() {
        // A medição encontrou 'bet' 32 vezes no uol.com.br sem fronteira de palavra.
        String html = """
                <html><body>
                  <p>Roberto mora em Betim e trata diabetes tipo 2. Beta-bloqueador e Tibet.</p>
                </body></html>
                """;

        assertThat(assinaturasDe(html)).isEmpty();
    }

    @Test
    @DisplayName("Não casa dentro de script, style nem de atributo")
    void descartaOsFalsosPositivosMedidos() {
        // Os três ruídos medidos em docs/pre-analise-scraping.md, na mesma página.
        String html = """
                <html><head>
                  <title>Loja de bicicletas</title>
                  <style>:root { --bet-win-color: #0f0; }</style>
                  <link href="https://fonts.googleapis.com/icon?icon_names=account_balance,casino,check">
                </head>
                <body>
                  <img src="/betbr-blaze-prodfavicon.ico" alt="logo">
                  <script>var cassino = window.roleta;</script>
                  <p>Peças e acessórios para bicicleta.</p>
                </body></html>
                """;

        assertThat(assinaturasDe(html)).isEmpty();
    }

    @Test
    @DisplayName("Uma reportagem sobre aposta não vira casa de aposta")
    void jornalismoSobreApostaNaoExplodeEmSinais() {
        String html = """
                <html><head><title>Governo regulamenta o setor</title></head>
                <body><p>A nova regra atinge as casas de apostas esportivas do país.</p></body></html>
                """;

        // Casa "apostas esportivas" e "casa de apostas": o matcher não tem como saber que
        // o texto é jornalístico. O que o teste fixa é o LIMITE do dano — só termos do
        // tipo mais fraco, que é o de menor peso no score. Nenhuma licenciadora, nenhum
        // provedor, nenhum termo de depósito. É por isso que o peso é escalonado.
        assertThat(assinaturasDe(html))
                .isNotEmpty()
                .allMatch(assinatura -> assinatura.tipo() == TipoSinalScraping.PALAVRA_CHAVE);
    }

    // ===== Normalização =====

    @Test
    @DisplayName("Acento e caixa não impedem o casamento, e o termo sai na grafia correta")
    void normalizaAcentoECaixa() {
        String html = "<html><body><p>Licenciado por CURACAO EGAMING</p></body></html>";

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::termo)
                // O site escreveu sem cedilha e em maiúsculas; o moderador vê a grafia
                // do dicionário.
                .contains("Curaçao eGaming");
    }

    @Test
    @DisplayName("Termo de duas palavras casa mesmo quebrado por espaço e nova linha")
    void colapsaEspacoEntreAsPalavrasDoTermo() {
        String html = """
                <html><body><p>Pragmatic
                       Play</p></body></html>
                """;

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::termo)
                .contains("Pragmatic Play");
    }

    @Test
    @DisplayName("Espaço não separável (&nbsp;) em meta tag não impede o casamento")
    void colapsaEspacoNaoSeparavel() {
        // Regressão real, encontrada ao trocar a normalização por uma passagem única.
        // O Jsoup converte &nbsp; para espaço comum no texto do corpo, MAS preserva o
        // U+00A0 no valor de um atributo. E a classe \s da regex do Java não casa U+00A0.
        // Resultado: este termo não era encontrado — e meta tag é exatamente onde estava
        // o único sinal do blaze.com na medição.
        String html = """
                <html><head>
                  <meta name="description" content="Jogos de Pragmatic&nbsp;Play e roleta ao&nbsp;vivo">
                </head><body></body></html>
                """;

        assertThat(assinaturasDe(html))
                .extracting(AssinaturaEncontrada::termo)
                .contains("Pragmatic Play", "roleta ao vivo");
    }

    @Test
    @DisplayName("Termo repetido aparece uma vez só na evidência")
    void naoRepeteOMesmoTermo() {
        String html = "<html><body><p>cassino cassino cassino cassino</p></body></html>";

        assertThat(assinaturasDe(html))
                .filteredOn(assinatura -> assinatura.termo().equals("cassino"))
                .hasSize(1);
    }

    // ===== Shell de SPA =====

    @Test
    @DisplayName("Shell de SPA é marcado como documento vazio, não como site limpo")
    void reconheceOShellDeSpa() {
        // O caso do esportesdasorte.com: 10 KB de Angular, <title> vazio, zero sinais.
        String html = "<html><head><title></title></head><body><app-root></app-root></body></html>";

        assertThat(assinaturasDe(html)).isEmpty();
        assertThat(matcher.analisar(html).documentoVazio()).isTrue();
    }

    @Test
    @DisplayName("Página pequena mas com conteúdo real não é documento vazio")
    void paginaPequenaComConteudoNaoEDocumentoVazio() {
        String html = """
                <html><head><title>Padaria do Zé</title></head>
                <body><p>Pão francês, bolos e salgados. Rua das Flores, 100.</p></body></html>
                """;

        assertThat(matcher.analisar(html).documentoVazio()).isFalse();
    }

    // ===== Desempenho =====

    @Test
    @DisplayName("HTML no tamanho do teto é varrido em poucos milissegundos")
    void naoDegradaComDocumentoNoTeto() {
        // 100 KB, o teto de max-bytes, com o sinal no fim para forçar a varredura inteira.
        String recheio = "Lorem ipsum dolor sit amet consectetur. ".repeat(2600);
        String html = "<html><body><p>" + recheio + " Pragmatic Play</p></body></html>";
        assertThat(html.length()).isGreaterThan(100 * 1024);

        // Aquece a JVM: a primeira passagem paga JIT e não mede o autômato.
        assinaturasDe(html);

        Instant inicio = Instant.now();
        List<AssinaturaEncontrada> encontradas = assinaturasDe(html);
        Duration decorrido = Duration.between(inicio, Instant.now());

        assertThat(encontradas).extracting(AssinaturaEncontrada::termo).contains("Pragmatic Play");
        // A medição registrou 0,136 ms para varrer 605 KB. O limite aqui é folgado de
        // propósito: o teste existe para pegar uma regressão de ordem de grandeza — um
        // casamento que virasse quadrático — e não para cravar um número de máquina.
        assertThat(decorrido).isLessThan(Duration.ofMillis(500));
    }
}

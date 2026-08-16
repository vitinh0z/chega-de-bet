package com.chegadebet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Configuração da pré-análise de domínio (scraper).
 * <p>
 * Todo valor aqui é um limite contra um domínio hostil. O scraper abre conexão para um
 * endereço que um terceiro anônimo escolheu, então cada campo responde à pergunta "o que
 * acontece se o alvo for malicioso ou estiver quebrado?".
 * <p>
 * Os defaults vêm das medições em {@code docs/pre-analise-scraping.md}, feitas contra
 * quatro sites de aposta reais e dois sites legítimos de controle.
 *
 * @param habilitado      liga e desliga o worker sem redeploy. Existe porque a pré-análise
 *                        faz requisição para fora: se ela começar a incomodar um alvo ou a
 *                        estourar o orçamento de rede da VM, desligar precisa ser uma
 *                        mudança de configuração, não um hotfix.
 * @param intervalo       de quanto em quanto tempo o worker acorda para drenar a fila.
 *                        A mesma chave é lida pelo {@code @Scheduled} do worker — este
 *                        campo existe para documentá-la junto das demais.
 * @param timeoutConexao  teto para o aperto de mão TCP/TLS. O pior TTFB medido foi 0,73 s
 *                        ({@code esportesdasorte.com}); 5 s dá folga para rede ruim sem
 *                        segurar uma virtual thread por minutos em host que não responde.
 * @param timeoutTotal    teto para a requisição inteira, do connect ao último byte lido.
 *                        Sem ele um alvo que entrega 1 byte por segundo prende a conexão
 *                        para sempre — o timeout de conexão sozinho não pega esse caso.
 * @param maxBytes        quanto do HTML descemos, no máximo. A medição mostrou o sinal nos
 *                        primeiros 0,2% a 0,4% do documento (byte 1.220 de 605.127 em
 *                        {@code betnacional.com}), porque ele mora no {@code <head>}.
 *                        100 KB cobre o {@code <head>} de qualquer site com folga larga.
 * @param userAgent       identificação nos logs do alvo. Traz a URL do projeto de
 *                        propósito: quem for raspado consegue descobrir quem raspou e
 *                        reclamar. Um scraper anônimo seria incoerente com um projeto que
 *                        promete moderação auditável.
 * @param maxRedirects    quantos hops seguimos antes de desistir. O corte existe contra
 *                        laço de redirecionamento; cada hop é revalidado contra o guarda
 *                        de SSRF, então o número também limita o custo dessa checagem.
 * @param maxTentativas   quantas vezes tentamos o mesmo domínio na mesma execução, contando
 *                        a primeira. 2 = uma repetição. Só vale para falha transitória:
 *                        insistir em 403 ou 429 é hostil com o alvo e não muda o resultado.
 * @param esperaEntreTentativas pausa antes de repetir. Curta de propósito: a virtual thread
 *                        fica ocupada durante a espera, e a fila inteira espera junto.
 * @param cooldown        quanto tempo um domínio fica inelegível depois de uma tentativa,
 *                        com ou sem sucesso. Sem isso a fila de {@code EM_ANALISE} — que é
 *                        justamente a fila que não esvazia sozinha, porque só humano tira
 *                        domínio de lá — seria re-raspada a cada ciclo do worker.
 * @param concorrencia    quantos domínios são raspados ao mesmo tempo. Protege a VM da
 *                        Fase 1 e, principalmente, evita que o projeto vire uma enxurrada
 *                        de requisições na perspectiva de quem recebe.
 * @param loteMaximo      teto de domínios buscados do banco por ciclo. Limita a memória de
 *                        uma execução e faz o worker devolver o controle com regularidade.
 */
@ConfigurationProperties(prefix = "chegadebet.scraper")
public record ScraperProperties(
        boolean habilitado,
        Duration intervalo,
        Duration timeoutConexao,
        Duration timeoutTotal,
        DataSize maxBytes,
        String userAgent,
        int maxRedirects,
        int maxTentativas,
        Duration esperaEntreTentativas,
        Duration cooldown,
        int concorrencia,
        int loteMaximo) {

    /**
     * Teto de leitura em bytes, já como {@code int}.
     * <p>
     * O resto do código conta bytes lidos de um buffer, onde {@code long} só atrapalharia.
     * A conversão é segura por construção: um teto que não cabe em {@code int} seria
     * maior que 2 GB, e a validação abaixo derruba a subida bem antes disso.
     */
    public int maxBytesComoInt() {
        return (int) maxBytes.toBytes();
    }

    /**
     * Falha rápido na subida se a configuração for incoerente.
     * <p>
     * Um valor errado aqui não quebra nada visivelmente: {@code maxBytes: 0} faria toda
     * pré-análise devolver "nenhum sinal", e o sintoma seria uma fila de moderação sem
     * evidência nenhuma, semanas depois, sem um erro sequer no log.
     */
    public ScraperProperties {
        if (maxBytes == null || maxBytes.toBytes() <= 0 || maxBytes.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "chegadebet.scraper.max-bytes precisa ser positivo e caber em um int: " + maxBytes);
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException(
                    "chegadebet.scraper.user-agent é obrigatório: a pré-análise não se apresenta como anônima");
        }
        if (concorrencia <= 0) {
            throw new IllegalArgumentException(
                    "chegadebet.scraper.concorrencia precisa ser positiva: " + concorrencia);
        }
        if (maxTentativas <= 0) {
            throw new IllegalArgumentException(
                    "chegadebet.scraper.max-tentativas precisa ser ao menos 1: " + maxTentativas);
        }
    }
}

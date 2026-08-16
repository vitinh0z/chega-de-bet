package com.chegadebet.domain.scraping;

import com.chegadebet.domain.enums.MotivoFalhaScraping;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * O que uma pré-análise produziu sobre um domínio.
 * <p>
 * É evidência, nunca veredito. Não existe aqui um campo "é site de aposta": a pré-análise
 * conta o que viu, e quem conclui é o moderador.
 * <p>
 * Modelagem pura, sem regra de negócio — os únicos métodos são atalhos de leitura sobre
 * os próprios campos.
 *
 * @param host                 o domínio analisado, já normalizado
 * @param sucesso              se conseguimos olhar a página. {@code false} não significa
 *                             "nada encontrado": significa que não houve o que olhar
 * @param motivoFalha          preenchido quando, e apenas quando, {@code sucesso} é
 *                             {@code false}
 * @param assinaturas          as evidências encontradas, de host e de conteúdo juntas.
 *                             Cada uma sabe de onde veio pelo seu {@code trecho}
 * @param bytesBaixados        quanto do documento descemos. Vira métrica: comparado com o
 *                             teto, mostra quantos alvos estão sendo cortados pelo limite
 * @param urlFinal             onde a cadeia de redirecionamento terminou. Guardado porque
 *                             o destino final costuma ser mais revelador que o domínio
 *                             denunciado — encurtadores e páginas de afiliado apontam
 *                             para a casa de aposta de verdade
 * @param documentoVazio       o HTML veio pequeno demais e sem {@code <title>} útil. É o
 *                             shell de uma SPA: o conteúdo só existe depois que o
 *                             JavaScript roda, e a pré-análise não roda JavaScript. Sem
 *                             esta marca, uma SPA de aposta seria indistinguível de um
 *                             site limpo — e metade da amostra medida caiu nesse caso
 * @param executadoEm          quando a tentativa aconteceu
 * @param duracao              quanto ela levou, do início ao fim, incluindo repetições
 */
public record ResultadoScraping(
        String host,
        boolean sucesso,
        MotivoFalhaScraping motivoFalha,
        List<AssinaturaEncontrada> assinaturas,
        int bytesBaixados,
        String urlFinal,
        boolean documentoVazio,
        Instant executadoEm,
        Duration duracao) {

    public ResultadoScraping {
        // Defensiva de imutabilidade: o resultado é passado adiante para persistência e
        // para o cálculo de score, e uma lista compartilhada mutável entre esses dois
        // caminhos seria um bug de reprodutibilidade difícil de enxergar.
        assinaturas = assinaturas == null ? List.of() : List.copyOf(assinaturas);
    }

    /**
     * Se a análise rodou e não encontrou nenhuma assinatura.
     * <p>
     * Não confunda com falha: aqui a página foi lida inteira, dentro do teto, e não tinha
     * sinal nenhum. Isso é evidência a favor do domínio — fraca, mas evidência.
     */
    public boolean semSinais() {
        return sucesso && assinaturas.isEmpty();
    }

    /**
     * Se a pré-análise não ajudou o moderador.
     * <p>
     * Cobre os dois casos em que o resultado não é informação: a falha técnica e o shell
     * vazio de SPA sem nenhum sinal. Ambos vão para a fila de moderação com a mesma
     * prioridade de um domínio suspeito, porque o humano vai decidir sem ajuda.
     */
    public boolean indeterminado() {
        return !sucesso || (documentoVazio && assinaturas.isEmpty());
    }
}

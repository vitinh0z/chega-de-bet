package com.chegadebet.service.scraping;

import com.chegadebet.domain.enums.MotivoFalhaScraping;

/**
 * O que o {@link DomainScraperClient} devolve: ou um documento, ou o motivo de não ter um.
 * <p>
 * Interface selada em vez de um objeto com campos anuláveis. A diferença aparece em quem
 * consome: com {@code switch} sobre os dois casos, esquecer de tratar a falha é erro de
 * compilação. Com {@code documento == null ? ... : ...} seria um {@code NullPointerException}
 * em produção, no meio de um lote, no dia em que um alvo qualquer começar a dar timeout.
 */
public sealed interface RespostaScraping {

    /**
     * A página foi lida.
     *
     * @param html                  o HTML já decodificado, possivelmente truncado no teto
     * @param urlFinal              onde a cadeia de redirecionamento terminou
     * @param bytesBaixados         quantos bytes desceram de fato
     * @param cortadoEmHopConhecido o redirecionamento apontou para um domínio que o
     *                              projeto já conhece, e paramos ali sem baixar o corpo.
     *                              Nesse caso {@code html} vem vazio de propósito: a
     *                              evidência é o destino, não o conteúdo
     */
    record Documento(String html,
                     String urlFinal,
                     int bytesBaixados,
                     boolean cortadoEmHopConhecido) implements RespostaScraping {
    }

    /**
     * Não houve o que ler.
     *
     * @param motivo   por quê. Ver {@link MotivoFalhaScraping#isTransitoria()} antes de repetir
     * @param urlFinal até onde chegamos antes de falhar. Guardado porque uma falha no
     *                 terceiro hop conta uma história diferente de uma falha no primeiro
     */
    record Falha(MotivoFalhaScraping motivo, String urlFinal) implements RespostaScraping {
    }
}

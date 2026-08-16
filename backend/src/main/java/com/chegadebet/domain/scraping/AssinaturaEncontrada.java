package com.chegadebet.domain.scraping;

import com.chegadebet.domain.enums.TipoSinalScraping;
import com.chegadebet.domain.enums.TrechoDocumento;

/**
 * Uma evidência: qual termo apareceu, o que ele significa e onde estava.
 * <p>
 * O termo em claro faz parte da evidência de propósito. O moderador precisa ver
 * <i>"encontrei 'Pragmatic Play' no rodapé"</i>, não <i>"encontrei um sinal de peso
 * alto"</i> — a segunda forma pede fé no sistema, e este projeto pede o contrário.
 *
 * @param termo  o texto do dicionário que casou, na grafia do dicionário e não na do site
 * @param tipo   o que a presença dele indica
 * @param trecho em que parte do documento ele estava
 */
public record AssinaturaEncontrada(String termo, TipoSinalScraping tipo, TrechoDocumento trecho) {
}

package com.chegadebet.repository;

/**
 * As duas contagens de denunciantes distintos que o score usa, lidas de uma vez.
 * <p>
 * Existe como projeção, e não como um par de {@code long} soltos, porque as duas precisam
 * vir da <b>mesma</b> consulta: lê-las em momentos diferentes deixaria as duas parcelas do
 * score refletindo instantes diferentes, e uma denúncia que chegasse entre as duas leituras
 * apareceria em uma e não na outra.
 *
 * @param total    denunciantes distintos de todos os tempos
 * @param recentes denunciantes distintos dentro da janela de recência
 */
public record ContagemDenunciantes(long total, long recentes) {
}

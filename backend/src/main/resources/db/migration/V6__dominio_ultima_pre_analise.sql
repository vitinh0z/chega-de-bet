-- Denormaliza no domínio a data da última pré-análise.
--
-- A seleção do lote perguntava duas coisas ao sinal_scraping, as duas por subconsulta
-- correlacionada — uma por linha candidata:
--
--   WHERE NOT EXISTS (SELECT 1 FROM sinal_scraping s WHERE s.dominio_id = d.id AND ...)
--   ORDER BY (SELECT MAX(s2.criado_em) FROM sinal_scraping s2 WHERE s2.dominio_id = d.id) ...
--
-- O ORDER BY é o pior dos dois: ele não tem como ser resolvido por índice, porque a chave
-- de ordenação só existe depois de agregar outra tabela. O planejador precisa materializar
-- a fila EM_ANALISE inteira, calcular o MAX de cada linha e só então ordenar. A fila
-- EM_ANALISE é justamente a que não esvazia sozinha — só o moderador tira domínio de lá —
-- então ela cresce sem teto, e o custo dessa consulta cresce junto.
--
-- Com a coluna aqui, filtro e ordenação passam a sair do mesmo índice, em uma varredura de
-- faixa. Nenhuma subconsulta sobra.
--
-- O preço é manter a coluna em dia: ela é escrita na MESMA transação que grava o
-- sinal_scraping (ver RegistroPreAnalise). Sair de sincronia significaria raspar de novo
-- antes da hora, ou nunca mais raspar — por isso os dois writes não podem se separar.

ALTER TABLE dominio
    ADD COLUMN ultima_pre_analise_em TIMESTAMPTZ;

-- Backfill do que já existe. NULL continua significando "nunca raspado", que é quem tem
-- prioridade na fila.
UPDATE dominio d
SET ultima_pre_analise_em = (SELECT MAX(s.criado_em)
                             FROM sinal_scraping s
                             WHERE s.dominio_id = d.id)
WHERE EXISTS (SELECT 1 FROM sinal_scraping s WHERE s.dominio_id = d.id);

-- O índice que serve a consulta inteira do worker.
--
-- A ordem das colunas é a da consulta, e não é intercambiável:
--   status                -> igualdade, então vem primeiro e corta a maior parte da tabela;
--   ultima_pre_analise_em -> faixa E primeira chave de ordenação;
--   score                 -> desempate.
--
-- NULLS FIRST espelha o ORDER BY: sem isso o índice estaria ordenado ao contrário na
-- ponta que mais importa, que é a dos domínios nunca raspados. Eles são o caso em que a
-- pré-análise ainda tem o que dizer ao moderador.
CREATE INDEX ix_dominio_fila_pre_analise
    ON dominio (status, ultima_pre_analise_em ASC NULLS FIRST, score DESC);

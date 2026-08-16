-- Ajusta o índice da fila de moderação ao desempate que a paginação exigiu.
--
-- O índice antigo era (status, score DESC). Ele bastava enquanto GET /api/moderacao/fila
-- devolvia a fila inteira: a ordem entre domínios de mesmo score não importava, porque
-- todos vinham na mesma resposta.
--
-- Com LIMIT/OFFSET, a ordem entre empates passa a decidir o CONTEÚDO de cada página. E
-- SQL não promete ordem nenhuma para linhas de mesma chave de ordenação: duas execuções
-- da mesma consulta podem devolver dois domínios de score 30 em ordens diferentes. Se
-- isso acontecer entre a página 0 e a página 1, um domínio aparece nas duas — ou em
-- nenhuma. Um domínio que some da fila é um site de aposta que ninguém analisa.
--
-- Por isso ModeracaoService.ORDEM_DA_FILA passou a ser (score DESC, criado_em ASC), e o
-- índice precisa espelhar isso coluna a coluna, na mesma ordem e nos mesmos sentidos.
-- Com só (status, score DESC), o Postgres usaria o índice para o filtro e ainda faria um
-- passo de sort para resolver o desempate — em silêncio, sem erro nenhum, só mais lento
-- conforme a fila cresce.
--
-- O desempate por data também é o critério justo: entre domínios igualmente pontuados,
-- quem está esperando há mais tempo é analisado primeiro.

DROP INDEX IF EXISTS ix_dominio_status_score;

CREATE INDEX ix_dominio_fila_moderacao ON dominio (status, score DESC, criado_em ASC);

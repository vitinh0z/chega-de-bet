-- Moderação auditável: cada decisão vira uma linha, com quem decidiu, quando e por quê.
-- Guardar só o status final em `dominio` perderia o histórico e, pior, não identificaria
-- quem votou — e sem isso o quórum não tem como contar moderadores distintos.
CREATE TABLE decisao_moderacao
(
    id         UUID         NOT NULL,
    dominio_id UUID         NOT NULL,
    moderador  VARCHAR(120) NOT NULL,
    decisao    VARCHAR(20)  NOT NULL,
    motivo     VARCHAR(500),
    criado_em  TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_decisao_dominio FOREIGN KEY (dominio_id) REFERENCES dominio (id),
    -- Espelha TipoDecisao. Mudar o enum exige uma nova migration, de propósito.
    CONSTRAINT ck_decisao_tipo CHECK (decisao IN ('APROVACAO', 'REJEICAO')),
    -- Rejeição sem motivo quebra a auditoria: o banco também garante isso.
    CONSTRAINT ck_decisao_motivo CHECK (decisao <> 'REJEICAO' OR motivo IS NOT NULL)
);

-- Um moderador, um voto por domínio. É o que impede a mesma pessoa de formar quórum sozinha.
CREATE UNIQUE INDEX ux_decisao_dominio_moderador ON decisao_moderacao (dominio_id, moderador);

-- Fecha a dedup de denúncia no banco. Como índice comum, ele só acelerava a consulta:
-- a checagem em código deixava passar duas requisições simultâneas do mesmo denunciante.
DROP INDEX ix_denuncia_dominio_denunciante;
CREATE UNIQUE INDEX ux_denuncia_dominio_denunciante ON denuncia (dominio_id, denunciante_hash);

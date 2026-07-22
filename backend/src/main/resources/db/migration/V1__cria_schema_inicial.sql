-- Schema inicial: domínios, denúncias e tokens efêmeros.

CREATE TABLE dominio
(
    id            UUID         NOT NULL,
    -- 253 é o comprimento máximo de um nome de domínio (RFC 1035)
    host          VARCHAR(253) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    score         INTEGER      NOT NULL DEFAULT 0,
    criado_em     TIMESTAMPTZ  NOT NULL,
    atualizado_em TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (id),
    -- Espelha StatusDominio. Mudar o enum exige uma nova migration, de propósito.
    CONSTRAINT ck_dominio_status CHECK (status IN ('APROVADO', 'REJEITADO', 'EM_ANALISE'))
);

CREATE UNIQUE INDEX ux_dominio_host ON dominio (host);

CREATE INDEX ix_dominio_status_score ON dominio (status, score DESC);

CREATE TABLE denuncia
(
    id               UUID        NOT NULL,
    categoria_aposta VARCHAR(20) NOT NULL,
    criado_em        TIMESTAMPTZ NOT NULL,
    dominio_id       UUID        NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_denuncia_dominio FOREIGN KEY (dominio_id) REFERENCES dominio (id),
    -- Espelha CategoriaAposta
    CONSTRAINT ck_denuncia_categoria_aposta CHECK (categoria_aposta IN ('ESPORTIVA', 'CASSINO'))
);

-- Contagem de denúncias por domínio
CREATE INDEX ix_denuncia_dominio_id ON denuncia (dominio_id);

-- Identidade anônima para rate-limit. Nenhuma coluna aqui liga a uma pessoa real:
-- guardamos apenas o hash do token, nunca o token em claro.
CREATE TABLE token_efemero
(
    id         UUID        NOT NULL,
    valor_hash VARCHAR(64) NOT NULL,
    criado_em  TIMESTAMPTZ NOT NULL,
    expira_em  TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_token_efemero_valor_hash ON token_efemero (valor_hash);

-- Expurgo periódico dos expirados
CREATE INDEX ix_token_efemero_expira_em ON token_efemero (expira_em);

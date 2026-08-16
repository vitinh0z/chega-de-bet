-- Histórico da pré-análise de domínio.
--
-- Uma linha por tentativa, nunca sobrescrita. É o que permite responder "por que este
-- domínio foi bloqueado em março?" com a evidência que o moderador tinha naquele dia, e
-- é o que mostra um site mudar de ramo ao longo do tempo.
--
-- Nenhuma coluna aqui liga a medição a uma pessoa. O vínculo é só com o domínio, que é
-- público por natureza — por isso a tabela pode alimentar o dashboard de transparência
-- sem passar por anonimização.

CREATE TABLE sinal_scraping
(
    id              UUID        NOT NULL,
    dominio_id      UUID        NOT NULL,

    -- Falso não significa "site limpo": significa que não houve o que medir.
    sucesso         BOOLEAN     NOT NULL,
    -- Preenchido só quando sucesso = FALSE. Espelha MotivoFalhaScraping.
    motivo_falha    VARCHAR(30),

    -- Evidências: [{"termo": "...", "tipo": "...", "trecho": "..."}]
    -- JSONB e não tabela filha: a lista é sempre lida inteira junto com a medição e não
    -- se relaciona com mais nada. JSONB e não TEXT porque o dashboard vai agregar por
    -- tipo de sinal, e jsonb_array_elements faz isso aqui dentro.
    assinaturas     JSONB       NOT NULL DEFAULT '[]'::jsonb,

    bytes_baixados  INTEGER     NOT NULL DEFAULT 0,
    -- Destino final do redirecionamento. 2048 é o teto prático de URL nos navegadores.
    url_final       VARCHAR(2048),
    -- Shell de SPA: HTML sem conteúdo porque o conteúdo depende de JavaScript.
    documento_vazio BOOLEAN     NOT NULL DEFAULT FALSE,
    duracao_ms      BIGINT      NOT NULL DEFAULT 0,
    criado_em       TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_sinal_scraping_dominio FOREIGN KEY (dominio_id) REFERENCES dominio (id),

    -- Espelha MotivoFalhaScraping. Mudar o enum exige uma nova migration, de propósito:
    -- o mesmo acordo já usado em ck_dominio_status.
    CONSTRAINT ck_sinal_scraping_motivo_falha CHECK (
        motivo_falha IS NULL OR motivo_falha IN (
            'DNS_NAO_RESOLVE', 'SSRF_BLOQUEADO', 'ESQUEMA_INVALIDO', 'REDIRECT_EXCEDIDO',
            'CERTIFICADO_INVALIDO', 'HTTP_4XX', 'CONTEUDO_NAO_HTML', 'TIMEOUT',
            'CONEXAO_RECUSADA', 'HTTP_5XX', 'ERRO_DE_LEITURA'
        )
    ),

    -- Motivo sem falha, ou falha sem motivo, é medição que ninguém consegue interpretar
    -- depois. O banco recusa as duas na entrada em vez de deixar a auditoria mentir.
    CONSTRAINT ck_sinal_scraping_falha_coerente CHECK (
        (sucesso = TRUE AND motivo_falha IS NULL) OR (sucesso = FALSE AND motivo_falha IS NOT NULL)
    )
);

-- Consulta quente do worker: "qual foi a última tentativa deste domínio?", feita a cada
-- ciclo para decidir o cooldown. DESC porque é sempre a mais recente que interessa.
CREATE INDEX ix_sinal_scraping_dominio_criado_em ON sinal_scraping (dominio_id, criado_em DESC);

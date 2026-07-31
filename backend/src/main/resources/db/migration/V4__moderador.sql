-- Identidade de quem modera. Existe para o quórum contar PESSOAS reais: enquanto o
-- moderador vinha do cabeçalho X-Moderador, declarado pelo cliente, uma pessoa só
-- formava quórum sozinha mandando dois nomes diferentes.
CREATE TABLE moderador
(
    id            UUID         NOT NULL,
    login         VARCHAR(120) NOT NULL,
    -- BCrypt com o prefixo do DelegatingPasswordEncoder: "{bcrypt}" + 60 caracteres.
    -- A folga permite migrar de algoritmo depois sem mexer no schema.
    senha_hash    VARCHAR(100) NOT NULL,
    -- Desligar em vez de apagar: apagar levaria junto a trilha de auditoria.
    ativo         BOOLEAN      NOT NULL DEFAULT TRUE,
    criado_em     TIMESTAMPTZ  NOT NULL,
    atualizado_em TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX ux_moderador_login ON moderador (login);

-- Sem INSERT de moderador aqui de propósito: uma senha em migration é uma senha
-- pública, já que este repositório é aberto. O primeiro acesso é criado na subida a
-- partir de variável de ambiente (ver BootstrapModerador).

-- decisao_moderacao.moderador continua guardando o login como texto, sem FK. É trilha
-- de auditoria: precisa sobreviver à remoção da conta e registrar quem era a pessoa no
-- momento da decisão, não quem ela é hoje.

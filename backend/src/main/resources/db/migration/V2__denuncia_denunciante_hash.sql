-- Pseudônimo anônimo do denunciante: hash do token efêmero no momento da denúncia.
-- Permite contar denunciantes DISTINTOS por domínio (sinal anti-sabotagem), sem armazenar PII.
-- NOT NULL sem default: seguro porque o schema ainda é pré-produção (tabela sem dados).
-- Havendo dados no futuro, esta coluna exigiria backfill antes do NOT NULL.
ALTER TABLE denuncia
    ADD COLUMN denunciante_hash VARCHAR(64) NOT NULL;

-- Acelera o COUNT(DISTINCT denunciante_hash) filtrado por domínio.
CREATE INDEX ix_denuncia_dominio_denunciante ON denuncia (dominio_id, denunciante_hash);

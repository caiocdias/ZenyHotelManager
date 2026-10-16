-- Ciclo de vida dos anexos (M2 E11, H10 a H12 do roadmap do M2):
-- - received_sha256: SHA-256 do conteúdo como chegou no upload (antes do reencode das finalidades que
--   regravam imagens; nulo nas linhas anteriores, em que é o próprio sha256). Confere a repetição de
--   um upload com o mesmo clientFileId;
-- - detached_at: quando o vínculo com o registro dono foi desfeito pela última vez (o anexo é órfão
--   desde então; sem desvínculo, desde created_at);
-- - deleted_at: quando o anexo foi apagado (FilesApi.delete ou expurgo de órfão); o objeto sai do
--   armazenamento depois do commit, e a linha fica porque a trilha de auditoria a referencia.
ALTER TABLE files.stored_file
    ADD COLUMN received_sha256 varchar(64),
    ADD COLUMN detached_at     timestamptz,
    ADD COLUMN deleted_at      timestamptz,
    ADD CONSTRAINT stored_file_received_sha256_ck CHECK (received_sha256 IS NULL OR received_sha256 ~ '^[0-9a-f]{64}$');

-- Os candidatos do expurgo: órfãos vivos, pelo instante desde o qual estão sem vínculo.
CREATE INDEX stored_file_orphan_idx ON files.stored_file ((COALESCE(detached_at, created_at)), id)
    WHERE owner_entity_id IS NULL AND deleted_at IS NULL;

-- Apoio só de teste (não vai para produção): notas de exemplo como fonte da sincronização offline, com dono
-- (escopo OWN), desativação (tombstone derivado) e exclusão física (tombstone em shared.sync_tombstone);
-- M2 E05. O trigger usa a função genérica do shared, como as tabelas sincronizáveis dos módulos.
CREATE TABLE test_support.sync_note
(
    id          UUID                     NOT NULL PRIMARY KEY,
    command_id  UUID,
    owner_id    UUID                     NOT NULL,
    text        VARCHAR(200)             NOT NULL,
    active      BOOLEAN                  NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE,
    version     BIGINT                   NOT NULL,
    change_seq  BIGINT                   NOT NULL,
    change_xid  XID8                     NOT NULL
);

CREATE INDEX sync_note_command_idx ON test_support.sync_note (command_id);

CREATE TRIGGER sync_note_offline_change
    BEFORE INSERT OR UPDATE
    ON test_support.sync_note
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

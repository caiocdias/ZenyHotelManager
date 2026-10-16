-- A UH como fonte da sincronização offline (ARCHITECTURE §9, M2 E05, H6): posição da última mudança,
-- gravada pelo trigger com a função genérica do shared a cada INSERT ou UPDATE da linha (cadastro, status
-- ou desativação), sem depender de cada caminho de escrita da aplicação.
ALTER TABLE rooms.room
    ADD COLUMN change_seq BIGINT,
    ADD COLUMN change_xid XID8;

CREATE TRIGGER room_offline_change
    BEFORE INSERT OR UPDATE
    ON rooms.room
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

-- As UHs já cadastradas recebem a posição pelo próprio trigger.
UPDATE rooms.room
SET change_seq = NULL;

ALTER TABLE rooms.room
    ALTER COLUMN change_seq SET NOT NULL,
    ALTER COLUMN change_xid SET NOT NULL;

CREATE INDEX room_offline_change_idx ON rooms.room (change_xid, change_seq);

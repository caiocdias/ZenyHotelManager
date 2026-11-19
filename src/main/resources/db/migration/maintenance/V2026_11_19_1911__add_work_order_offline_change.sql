-- A ordem de serviço como fonte da sincronização offline (`maintenance-work-orders`, M6 E08, D4; ARCHITECTURE §9):
-- posição da última mudança, gravada pelo trigger com a função genérica do shared a cada INSERT ou UPDATE da linha
-- (abertura, edição, ação), sem depender de cada caminho de escrita da aplicação.
ALTER TABLE maintenance.work_order
    ADD COLUMN change_seq BIGINT,
    ADD COLUMN change_xid XID8;

CREATE TRIGGER work_order_offline_change
    BEFORE INSERT OR UPDATE
    ON maintenance.work_order
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

-- As ordens já gravadas recebem a posição pelo próprio trigger.
UPDATE maintenance.work_order
SET change_seq = NULL;

ALTER TABLE maintenance.work_order
    ALTER COLUMN change_seq SET NOT NULL,
    ALTER COLUMN change_xid SET NOT NULL;

CREATE INDEX work_order_offline_change_idx ON maintenance.work_order (change_xid, change_seq);

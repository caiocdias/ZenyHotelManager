-- A tarefa de governança como fonte da sincronização offline (`housekeeping-tasks`, M6 E19, D6; ARCHITECTURE §9):
-- posição da última mudança, gravada pelo trigger com a função genérica do shared a cada INSERT ou UPDATE da linha
-- (geração, atribuição, transição), sem depender de cada caminho de escrita da aplicação.
ALTER TABLE housekeeping.housekeeping_task
    ADD COLUMN change_seq BIGINT,
    ADD COLUMN change_xid XID8;

CREATE TRIGGER housekeeping_task_offline_change
    BEFORE INSERT OR UPDATE
    ON housekeeping.housekeeping_task
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

-- As tarefas já gravadas recebem a posição pelo próprio trigger.
UPDATE housekeeping.housekeeping_task
SET change_seq = NULL;

ALTER TABLE housekeeping.housekeeping_task
    ALTER COLUMN change_seq SET NOT NULL,
    ALTER COLUMN change_xid SET NOT NULL;

CREATE INDEX housekeeping_task_offline_change_idx ON housekeeping.housekeeping_task (change_xid, change_seq);

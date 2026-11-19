-- A inspeção de UH (M6 E05, D2; RF-GOV-06, SPEC §8.2.10): ligada a uma tarefa de governança (nula só na inspeção
-- voluntária) e, havendo modelo de checklist de contexto INSPECTION, à execução dele. Ciclo IN_PROGRESS -> COMPLETED: a
-- conclusão grava o resultado e, na reprovação, as chaves dos itens reprovados do checklist (vazio na aprovação). Não é
-- só de inserção (a conclusão altera a linha), mas nunca é apagada (o trigger no fim recusa a exclusão). Os ids de UH
-- (rooms), de execução de checklist (checklists) e de usuário (iam) são opacos, sem FK entre schemas; a tarefa é do
-- próprio schema, com FK. Sem texto livre e sem dado de hóspede (RN-13).

CREATE TABLE housekeeping.inspection
(
    id                     UUID                     NOT NULL,
    task_id                UUID,
    room_id                UUID                     NOT NULL,
    checklist_execution_id UUID,
    status                 VARCHAR(20)              NOT NULL,
    result                 VARCHAR(20),
    rejected_items         TEXT[]                   NOT NULL DEFAULT '{}',
    -- O usuário que inspecionou: quem iniciou, trocado por quem concluiu.
    inspector_id           UUID                     NOT NULL,
    started_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT inspection_pk PRIMARY KEY (id),
    CONSTRAINT inspection_task_fk FOREIGN KEY (task_id) REFERENCES housekeeping.housekeeping_task (id),
    CONSTRAINT inspection_status_ck CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT inspection_result_ck CHECK (result IN ('APPROVED', 'REJECTED')),
    -- Concluída se e só se tem resultado e conclusão.
    CONSTRAINT inspection_completion_ck CHECK ((status = 'COMPLETED') = (result IS NOT NULL)
        AND (status = 'COMPLETED') = (completed_at IS NOT NULL)),
    -- A aprovação não tem itens reprovados.
    CONSTRAINT inspection_approved_items_ck
        CHECK (result IS DISTINCT FROM 'APPROVED' OR cardinality(rejected_items) = 0),
    CONSTRAINT inspection_completed_after_start_ck CHECK (completed_at IS NULL OR completed_at >= started_at)
);

-- Uma inspeção aberta por tarefa (D2): a segunda abertura simultânea falha aqui, além da trava da tarefa.
CREATE UNIQUE INDEX inspection_open_task_uk ON housekeeping.inspection (task_id) WHERE status = 'IN_PROGRESS';
-- As inspeções de uma tarefa (a última reprovada, a FK).
CREATE INDEX inspection_task_idx ON housekeeping.inspection (task_id, started_at) WHERE task_id IS NOT NULL;

CREATE TRIGGER inspection_never_deleted
    BEFORE DELETE
    ON housekeeping.inspection
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

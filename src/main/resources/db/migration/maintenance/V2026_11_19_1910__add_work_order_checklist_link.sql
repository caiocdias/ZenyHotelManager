-- O vínculo da ordem de serviço ao item de checklist que a abriu (M6 E08, D2): a execução real que abriu a ordem
-- (checklist_execution_id), o item (checklist_item_key) e a execução de referência (checklist_reference_execution_id =
-- a execução da rotina de que a registrada à parte, duplicateOf, é cópia, ou a própria). O índice único da referência
-- com o item faz a reentrega do evento e a execução registrada à parte do mesmo item não abrirem uma segunda ordem.
-- Colunas anuláveis: as ordens que não vêm de checklist (e as já gravadas) ficam sem vínculo, e NULLs não colidem.
ALTER TABLE maintenance.work_order
    ADD COLUMN checklist_execution_id           UUID,
    ADD COLUMN checklist_item_key               VARCHAR(40),
    ADD COLUMN checklist_reference_execution_id UUID,
    ADD CONSTRAINT work_order_checklist_ck CHECK (
        num_nonnulls(checklist_execution_id, checklist_item_key, checklist_reference_execution_id) IN (0, 3)
        AND (origin = 'CHECKLIST') = (checklist_execution_id IS NOT NULL));

CREATE UNIQUE INDEX work_order_checklist_uk
    ON maintenance.work_order (checklist_reference_execution_id, checklist_item_key);

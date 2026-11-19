-- O checklist da tarefa de governança (M6 E04, D5): a execução aberta no início com a versão publicada vigente do
-- modelo (o refazer abre outra). Id opaco do `checklists`, sem FK entre schemas; nula numa tarefa sem checklist e nas
-- gravadas antes da E04. Só acrescenta a coluna, nada é apagado.

ALTER TABLE housekeeping.housekeeping_task
    ADD COLUMN checklist_execution_id UUID;

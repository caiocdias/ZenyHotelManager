-- A camareira e a posição publicadas na tarefa de governança (M6 E03, D12-E03): a publicação do plano do dia grava
-- nelas quem faz a tarefa e em que ordem, para que o app da camareira (E04) mantenha a ordem publicada mesmo depois de
-- um novo rascunho. O id do colaborador (staff) é opaco, sem FK entre schemas; nenhum nome (RN-13). As duas colunas
-- andam juntas, a posição começa em 1 e a tarefa PENDING nunca tem camareira (D7-E03: a retirada volta a PENDING sem
-- ela). Os outros status não são amarrados aqui: as transições da E04 decidem o que guardam.

ALTER TABLE housekeeping.housekeeping_task
    ADD COLUMN assigned_employee_id UUID,
    ADD COLUMN assignment_position  INT,
    ADD CONSTRAINT housekeeping_task_assignee_ck
        CHECK ((assigned_employee_id IS NULL) = (assignment_position IS NULL)),
    ADD CONSTRAINT housekeeping_task_assignment_position_ck CHECK (assignment_position >= 1),
    ADD CONSTRAINT housekeeping_task_pending_unassigned_ck
        CHECK (status <> 'PENDING' OR assigned_employee_id IS NULL);


-- O histórico de posse da tarefa de governança (M6 E19, D3): uma linha a cada vez que a tarefa passa a uma camareira
-- (atribuição, ou reatribuição a outra), gravada pela publicação do plano do dia; mudar só a posição não grava. A
-- retirada e a camareira atual vêm da própria tarefa. Serve ao conflito offline (RN-22): "o colaborador X já foi dono
-- da tarefa T?". Só de inserção (sem motivo nem fim); o id do colaborador (staff) é opaco, sem FK entre schemas, e
-- nenhum nome (RN-13).

CREATE TABLE housekeeping.task_assignment
(
    id          UUID                     NOT NULL,
    task_id     UUID                     NOT NULL,
    employee_id UUID                     NOT NULL,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT task_assignment_pk PRIMARY KEY (id),
    CONSTRAINT task_assignment_task_fk FOREIGN KEY (task_id) REFERENCES housekeeping.housekeeping_task (id)
);

-- A pergunta da posse (tarefa, colaborador).
CREATE INDEX task_assignment_task_employee_idx ON housekeeping.task_assignment (task_id, employee_id);

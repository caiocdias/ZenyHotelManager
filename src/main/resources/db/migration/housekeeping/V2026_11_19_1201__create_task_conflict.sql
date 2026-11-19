-- O registro dos conflitos offline da tarefa de governança (M6 E19, D4; RN-22): uma linha por comando de uma ex-dona
-- (camareira com escopo OWN que já teve a tarefa) numa tarefa reatribuída, retirada ou cancelada. Só de inserção e sem
-- texto livre (ARCHITECTURE §12: o registro só de inserção não admite expurgo): o resumo estruturado do que a ex-dona
-- fez ("summary") leva só valores não textuais (marcação, quantidade, número, chaves de item e de opção), o texto e a
-- observação reduzidos a "tinha texto" e as fotos só pela contagem, sem o id delas (ficam órfãs e saem no expurgo do
-- files). Os ids de usuário (iam) e de colaborador (staff) são opacos, sem FK entre schemas, e nenhum nome (RN-13).

CREATE TABLE housekeeping.task_conflict
(
    id           UUID                     NOT NULL,
    task_id      UUID                     NOT NULL,
    command_id   UUID                     NOT NULL,
    command_type VARCHAR(64)              NOT NULL,
    user_id      UUID                     NOT NULL,
    employee_id  UUID                     NOT NULL,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    task_status  VARCHAR(32)              NOT NULL,
    summary      JSONB                    NOT NULL,
    recorded_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT task_conflict_pk PRIMARY KEY (id),
    CONSTRAINT task_conflict_task_fk FOREIGN KEY (task_id) REFERENCES housekeeping.housekeeping_task (id),
    CONSTRAINT task_conflict_command_uk UNIQUE (command_id),
    CONSTRAINT task_conflict_summary_ck CHECK (jsonb_typeof(summary) = 'object')
);

-- O primeiro conflito de cada par (tarefa, colaborador) avisa a governança (D5).
CREATE INDEX task_conflict_task_employee_idx ON housekeeping.task_conflict (task_id, employee_id);

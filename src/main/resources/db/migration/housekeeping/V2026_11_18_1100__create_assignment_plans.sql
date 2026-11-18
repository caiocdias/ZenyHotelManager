-- Plano do dia da distribuição das tarefas de governança (M6 E03, RF-GOV-03, D6-E03): um rascunho por data operacional
-- (`business_date` único), refeito pela sugestão e ajustado pela governanta, com o status (rascunho ou publicado), a
-- versão da concorrência otimista (If-Match) e o carimbo da última publicação. Cada item é uma tarefa do plano (no
-- máximo uma vez por plano), com a camareira (colaborador do staff, id opaco sem FK entre schemas; nula: a tarefa fica
-- no plano sem camareira) e a posição na ordem de execução. Nenhuma coluna de dado pessoal (RN-13). O plano nunca é
-- apagado (o trigger recusa); os itens são apagados e regravados pela sugestão e pelo ajuste.

CREATE TABLE housekeeping.assignment_plan
(
    id            UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    version       BIGINT                   NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    -- A última publicação (Task 3 da E03); nulas enquanto o plano nunca foi publicado.
    published_at  TIMESTAMP WITH TIME ZONE,
    published_by  UUID,
    CONSTRAINT assignment_plan_pk PRIMARY KEY (id),
    CONSTRAINT assignment_plan_date_uk UNIQUE (business_date),
    CONSTRAINT assignment_plan_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED'))
);

CREATE TRIGGER assignment_plan_never_deleted
    BEFORE DELETE
    ON housekeeping.assignment_plan
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

CREATE TABLE housekeeping.assignment_plan_item
(
    plan_id     UUID NOT NULL,
    task_id     UUID NOT NULL,
    employee_id UUID,
    position    INT  NOT NULL,
    CONSTRAINT assignment_plan_item_pk PRIMARY KEY (plan_id, task_id),
    CONSTRAINT assignment_plan_item_plan_fk FOREIGN KEY (plan_id) REFERENCES housekeeping.assignment_plan (id),
    CONSTRAINT assignment_plan_item_task_fk FOREIGN KEY (task_id) REFERENCES housekeeping.housekeeping_task (id),
    CONSTRAINT assignment_plan_item_position_ck CHECK (position >= 1)
);

-- A FK para a tarefa e a busca dos planos de uma tarefa.
CREATE INDEX assignment_plan_item_task_idx ON housekeeping.assignment_plan_item (task_id);

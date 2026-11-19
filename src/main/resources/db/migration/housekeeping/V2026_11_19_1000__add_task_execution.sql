-- A execução da tarefa de governança (M6 E04, SPEC §8.4, D6-E04): o primeiro início, a conclusão, o tempo trabalhado
-- (a soma dos trechos em andamento, em segundos), o início do trecho corrente, a última transição (o piso do instante
-- das transições seguintes, Review Focus 1), quem concluiu (usuário do iam, opaco, sem FK entre schemas) e o motivo da
-- recusa. Nenhuma coluna de nome, documento, contato ou id de hóspede ou de reserva (RN-13). Só acrescenta colunas,
-- nada é apagado; a última transição das tarefas existentes é a última alteração delas.
--
-- Os CHECKs amarram a coerência num sentido só (o dado de execução só existe no status que o admite), para não
-- invalidar as tarefas que já andaram sem estes dados.

ALTER TABLE housekeeping.housekeeping_task
    ADD COLUMN started_at         TIMESTAMP WITH TIME ZONE,
    ADD COLUMN finished_at        TIMESTAMP WITH TIME ZONE,
    ADD COLUMN worked_seconds     BIGINT                   NOT NULL DEFAULT 0,
    ADD COLUMN in_progress_since  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_transition_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN cleaned_by         UUID,
    ADD COLUMN refusal_reason     VARCHAR(20),
    ADD CONSTRAINT housekeeping_task_worked_seconds_ck CHECK (worked_seconds >= 0),
    -- O trecho corrente só em andamento, e só depois do primeiro início.
    ADD CONSTRAINT housekeeping_task_in_progress_ck
        CHECK (in_progress_since IS NULL OR (status = 'IN_PROGRESS' AND started_at IS NOT NULL
            AND in_progress_since >= started_at)),
    -- A conclusão (com quem concluiu) só nos status depois dela, e nunca antes do início.
    ADD CONSTRAINT housekeeping_task_finished_ck
        CHECK (finished_at IS NULL OR (status IN ('AWAITING_INSPECTION', 'REJECTED', 'DONE')
            AND started_at IS NOT NULL AND finished_at >= started_at)),
    ADD CONSTRAINT housekeeping_task_cleaned_by_ck CHECK ((cleaned_by IS NULL) = (finished_at IS NULL)),
    -- O motivo só na recusada.
    ADD CONSTRAINT housekeeping_task_refusal_reason_ck
        CHECK (refusal_reason IS NULL OR (status = 'REFUSED' AND refusal_reason IN ('DO_NOT_DISTURB', 'GUEST_REFUSED')));

UPDATE housekeeping.housekeeping_task
SET last_transition_at = updated_at;

-- Execuções de checklist e rotinas (M2 E07, RF-CHK-05, 07 e 08, SPEC §8.2.11, H14). Uma execução guarda a
-- versão usada (RF-CHK-06), o sujeito (tipo e id opacos de outro módulo, H3), o executor, os horários reais
-- (os do dispositivo corrigidos pela defasagem, RN-22) e as respostas por chave de item. Uma rotina gera, na
-- virada da data operacional, uma execução pendente por data, com os candidatos escalados no setor; o primeiro
-- que inicia a assume. Execução e rotina nunca são apagadas (trigger checklists.refuse_delete); as respostas
-- de uma execução em andamento são trocadas pelo executor e, concluída, ficam como estão (domínio).

CREATE TABLE checklists.checklist_routine
(
    id           uuid         NOT NULL,
    name         varchar(120) NOT NULL,
    template_id  uuid         NOT NULL,
    department_id uuid        NOT NULL,
    frequency    varchar(10)  NOT NULL,
    weekdays     varchar(70)  NOT NULL DEFAULT '',
    due_at       time         NOT NULL,
    subject_type varchar(60),
    subject_id   uuid,
    active       boolean      NOT NULL DEFAULT true,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL,
    CONSTRAINT checklist_routine_pk PRIMARY KEY (id),
    CONSTRAINT checklist_routine_template_fk FOREIGN KEY (template_id)
        REFERENCES checklists.checklist_template (id),
    CONSTRAINT checklist_routine_name_ck CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 120),
    CONSTRAINT checklist_routine_frequency_ck CHECK (frequency IN ('DAILY', 'WEEKLY')),
    -- Diária sem dias da semana; semanal com ao menos um.
    CONSTRAINT checklist_routine_weekdays_ck CHECK ((frequency = 'DAILY') = (weekdays = '')),
    CONSTRAINT checklist_routine_due_at_ck CHECK (date_trunc('minute', due_at) = due_at),
    CONSTRAINT checklist_routine_subject_ck CHECK ((subject_type IS NULL) = (subject_id IS NULL)),
    CONSTRAINT checklist_routine_version_ck CHECK (version >= 0)
);

CREATE INDEX checklist_routine_name_idx ON checklists.checklist_routine (name, id);

CREATE TABLE checklists.checklist_execution
(
    id                  uuid        NOT NULL,
    template_id         uuid        NOT NULL,
    version_id          uuid,
    version_number      integer,
    context             varchar(20) NOT NULL,
    subject_type        varchar(60),
    subject_id          uuid,
    routine_id          uuid,
    business_date       date,
    due_at              timestamptz,
    candidate_ids       uuid[]      NOT NULL DEFAULT '{}',
    duplicate_of        uuid,
    status              varchar(12) NOT NULL,
    executor_id         uuid,
    started_at          timestamptz,
    completed_at        timestamptz,
    device_session_id   uuid,
    start_command_id    uuid,
    complete_command_id uuid,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL,
    change_seq          bigint      NOT NULL,
    change_xid          xid8        NOT NULL,
    CONSTRAINT checklist_execution_pk PRIMARY KEY (id),
    CONSTRAINT checklist_execution_template_fk FOREIGN KEY (template_id)
        REFERENCES checklists.checklist_template (id),
    CONSTRAINT checklist_execution_version_fk FOREIGN KEY (version_id)
        REFERENCES checklists.checklist_version (id),
    CONSTRAINT checklist_execution_routine_fk FOREIGN KEY (routine_id)
        REFERENCES checklists.checklist_routine (id),
    CONSTRAINT checklist_execution_duplicate_fk FOREIGN KEY (duplicate_of)
        REFERENCES checklists.checklist_execution (id),
    CONSTRAINT checklist_execution_context_ck CHECK (context IN ('HOUSEKEEPING', 'INSPECTION', 'MAINTENANCE',
                                                                 'COMMON_AREA', 'RESTAURANT', 'EVENT', 'OTHER')),
    CONSTRAINT checklist_execution_status_ck CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT checklist_execution_subject_ck CHECK ((subject_type IS NULL) = (subject_id IS NULL)),
    -- Pendente só numa rotina, sem executor nem versão; iniciada, com executor, versão e início.
    CONSTRAINT checklist_execution_pending_ck CHECK (
        (status = 'PENDING') = (executor_id IS NULL)
            AND (status = 'PENDING') = (version_id IS NULL)
            AND (status = 'PENDING') = (started_at IS NULL)
            AND (status <> 'PENDING' OR routine_id IS NOT NULL)),
    CONSTRAINT checklist_execution_version_number_ck CHECK ((version_id IS NULL) = (version_number IS NULL)),
    CONSTRAINT checklist_execution_completed_ck CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT checklist_execution_times_ck CHECK (completed_at IS NULL OR completed_at >= started_at),
    CONSTRAINT checklist_execution_routine_ck CHECK ((routine_id IS NULL) = (business_date IS NULL)
        AND (routine_id IS NULL) = (due_at IS NULL)),
    CONSTRAINT checklist_execution_duplicate_ck CHECK (duplicate_of IS NULL OR routine_id IS NOT NULL),
    CONSTRAINT checklist_execution_version_ck CHECK (version >= 0)
);

-- Uma execução por rotina e data (H14): a geração repetida (evento reentregue) não duplica. A execução
-- offline de uma rotina já assumida por outra pessoa fica à parte, ligada à original (duplicate_of, RN-22).
CREATE UNIQUE INDEX checklist_execution_routine_day_uk
    ON checklists.checklist_execution (routine_id, business_date)
    WHERE routine_id IS NOT NULL AND duplicate_of IS NULL;

CREATE INDEX checklist_execution_list_idx ON checklists.checklist_execution (created_at DESC, id DESC);
CREATE INDEX checklist_execution_executor_idx ON checklists.checklist_execution (executor_id);
CREATE INDEX checklist_execution_candidates_idx ON checklists.checklist_execution USING gin (candidate_ids);
CREATE INDEX checklist_execution_duplicate_idx ON checklists.checklist_execution (duplicate_of);

CREATE TABLE checklists.checklist_answer
(
    execution_id  uuid          NOT NULL,
    item_key      varchar(40)   NOT NULL,
    item_type     varchar(20)   NOT NULL,
    check_value   varchar(20),
    quantity      numeric(9, 3),
    number_value  numeric(14, 4),
    text_value    varchar(2000),
    choice_values varchar(40)[],
    photo_file_id uuid,
    photo_missing boolean       NOT NULL DEFAULT false,
    note          varchar(500),
    answered_at   timestamptz   NOT NULL,
    CONSTRAINT checklist_answer_pk PRIMARY KEY (execution_id, item_key),
    CONSTRAINT checklist_answer_execution_fk FOREIGN KEY (execution_id)
        REFERENCES checklists.checklist_execution (id),
    CONSTRAINT checklist_answer_type_ck CHECK (item_type IN ('CHECK', 'QUANTITY', 'NUMBER', 'TEXT', 'SINGLE_CHOICE',
                                                             'MULTI_CHOICE', 'PHOTO')),
    CONSTRAINT checklist_answer_check_ck CHECK (check_value IS NULL OR check_value IN ('OK', 'NOT_OK', 'NOT_APPLICABLE')),
    CONSTRAINT checklist_answer_quantity_ck CHECK (quantity IS NULL OR quantity >= 0),
    CONSTRAINT checklist_answer_photo_ck CHECK (NOT (photo_missing AND photo_file_id IS NOT NULL))
);

CREATE INDEX checklist_answer_photo_idx ON checklists.checklist_answer (photo_file_id) WHERE photo_file_id IS NOT NULL;

CREATE TRIGGER checklist_routine_never_deleted
    BEFORE DELETE
    ON checklists.checklist_routine
    FOR EACH ROW
EXECUTE FUNCTION checklists.refuse_delete();

CREATE TRIGGER checklist_execution_never_deleted
    BEFORE DELETE
    ON checklists.checklist_execution
    FOR EACH ROW
EXECUTE FUNCTION checklists.refuse_delete();

-- Fontes da sincronização offline (ARCHITECTURE §9, H6): a posição da última mudança de cada execução e de
-- cada modelo (o modelo vai ao app com a última versão publicada), gravada pelo trigger com a função genérica
-- do shared a cada INSERT ou UPDATE da linha.
CREATE TRIGGER checklist_execution_offline_change
    BEFORE INSERT OR UPDATE
    ON checklists.checklist_execution
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

CREATE INDEX checklist_execution_offline_change_idx ON checklists.checklist_execution (change_xid, change_seq);

ALTER TABLE checklists.checklist_template
    ADD COLUMN change_seq BIGINT,
    ADD COLUMN change_xid XID8;

CREATE TRIGGER checklist_template_offline_change
    BEFORE INSERT OR UPDATE
    ON checklists.checklist_template
    FOR EACH ROW
EXECUTE FUNCTION shared.mark_offline_change();

-- Os modelos já cadastrados recebem a posição pelo próprio trigger.
UPDATE checklists.checklist_template
SET change_seq = NULL;

ALTER TABLE checklists.checklist_template
    ALTER COLUMN change_seq SET NOT NULL,
    ALTER COLUMN change_xid SET NOT NULL;

CREATE INDEX checklist_template_offline_change_idx ON checklists.checklist_template (change_xid, change_seq);

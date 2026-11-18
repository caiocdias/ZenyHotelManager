-- Tarefas de governança (M6 E02, RF-GOV-02, SPEC §8.2.10): uma limpeza de um tipo numa UH ou numa área comum
-- (exatamente um local) numa data operacional, com a origem (saída, troca de UH, permanência, pré-chegada, faxina
-- programada ou avulsa), o status, a prioridade e os créditos do tipo (D2), a marca de chegada prevista (retrato do
-- momento da geração, D8), a faxina programada que a gerou e as observações da recepção. Os ids de UH e de área (rooms)
-- e de usuário (iam) são opacos, sem FK entre schemas; o tipo e a faxina são do próprio schema, com FK. Nenhuma coluna
-- de nome, documento, contato ou id de hóspede ou de reserva (RN-13). A tarefa nunca é apagada (o trigger no fim recusa
-- a exclusão); as colunas de mudança da sincronização offline entram com a E04 (D13-E02).

CREATE TABLE housekeeping.housekeeping_task
(
    id                    UUID                     NOT NULL,
    room_id               UUID,
    area_id               UUID,
    business_date         DATE                     NOT NULL,
    cleaning_type_id      UUID                     NOT NULL,
    source                VARCHAR(20)              NOT NULL,
    status                VARCHAR(30)              NOT NULL,
    priority              INT                      NOT NULL,
    credits               INT                      NOT NULL,
    -- Retrato do momento da geração (D8): alocação, cancelamento ou no-show posteriores não o atualizam.
    arrival_expected      BOOLEAN                  NOT NULL,
    scheduled_cleaning_id UUID,
    -- Texto livre da recepção; nunca vai à trilha, a eventos nem a logs (RN-13).
    notes                 VARCHAR(500),
    -- Quem solicitou a avulsa; nulo na geração automática (o sistema).
    created_by            UUID,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT                   NOT NULL,
    CONSTRAINT housekeeping_task_pk PRIMARY KEY (id),
    -- Uma tarefa por (local, data, tipo), em qualquer status (D5): é a chave da idempotência da geração.
    CONSTRAINT housekeeping_task_location_date_type_uk
        UNIQUE NULLS NOT DISTINCT (room_id, area_id, business_date, cleaning_type_id),
    CONSTRAINT housekeeping_task_type_fk FOREIGN KEY (cleaning_type_id) REFERENCES housekeeping.cleaning_type (id),
    CONSTRAINT housekeeping_task_schedule_fk FOREIGN KEY (scheduled_cleaning_id)
        REFERENCES housekeeping.scheduled_cleaning (id),
    CONSTRAINT housekeeping_task_location_ck CHECK (num_nonnulls(room_id, area_id) = 1),
    CONSTRAINT housekeeping_task_status_ck CHECK (status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PAUSED',
                                                            'AWAITING_INSPECTION', 'REJECTED', 'DONE', 'REFUSED',
                                                            'CANCELLED')),
    CONSTRAINT housekeeping_task_source_ck CHECK (source IN ('CHECKOUT', 'ROOM_MOVE', 'STAYOVER', 'PRE_ARRIVAL',
                                                            'SCHEDULED', 'AD_HOC')),
    -- Só a tarefa de faxina programada leva a faxina, e sempre a leva.
    CONSTRAINT housekeeping_task_schedule_ck CHECK ((source = 'SCHEDULED') = (scheduled_cleaning_id IS NOT NULL)),
    CONSTRAINT housekeeping_task_priority_ck CHECK (priority BETWEEN 1 AND 5),
    CONSTRAINT housekeeping_task_credits_ck CHECK (credits BETWEEN 0 AND 100)
);

-- A lista do dia, na ordem (chegada prevista desc, prioridade desc, id).
CREATE INDEX housekeeping_task_day_idx
    ON housekeeping.housekeeping_task (business_date, arrival_expected DESC, priority DESC, id);
-- A última tarefa de uma UH (pré-chegada) e a tarefa aberta de uma faxina programada.
CREATE INDEX housekeeping_task_room_date_idx ON housekeeping.housekeeping_task (room_id, business_date)
    WHERE room_id IS NOT NULL;
CREATE INDEX housekeeping_task_schedule_idx ON housekeeping.housekeeping_task (scheduled_cleaning_id)
    WHERE scheduled_cleaning_id IS NOT NULL;
CREATE INDEX housekeeping_task_type_idx ON housekeeping.housekeeping_task (cleaning_type_id);

CREATE TRIGGER housekeeping_task_never_deleted
    BEFORE DELETE
    ON housekeeping.housekeeping_task
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

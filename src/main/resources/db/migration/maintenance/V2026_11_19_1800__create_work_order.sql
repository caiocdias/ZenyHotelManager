-- As ordens de serviço corretivas (M6 E07, D2; RF-MAN-01 e RF-MAN-02, SPEC §5.15): numa UH ou numa área (um dos dois),
-- com número legível (OS-AAAA-NNNNNN, pela sequence abaixo), categoria, prioridade, origem, descrição (texto livre do
-- operador, sem dado de hóspede, RN-13), responsável (colaborador do staff) e terceiro opcionais, estado (OPEN,
-- IN_PROGRESS, ON_HOLD, DONE, CANCELLED), motivo da espera, três custos (de 0 a 99 999,99), quem abriu e quando, o
-- prazo, o início e o fim, as notas de conclusão, o motivo do cancelamento e o período OOO/OOS aberto para a ordem
-- (out_of_order_id, usado na Task 3). Os ids de UH e área (rooms), de colaborador (staff), de período (rooms) e de
-- usuário (iam) são opacos, sem FK entre schemas. Alterada pelas ações e pela edição, nunca apagada (o papel da
-- aplicação não tem DELETE).

CREATE SEQUENCE maintenance.work_order_number_seq;

CREATE TABLE maintenance.work_order
(
    id                    UUID                     NOT NULL,
    number                VARCHAR(20)              NOT NULL,
    room_id               UUID,
    area_id               UUID,
    category              VARCHAR(20)              NOT NULL,
    priority              VARCHAR(10)              NOT NULL,
    description           VARCHAR(2000)            NOT NULL,
    origin                VARCHAR(20)              NOT NULL,
    assignee_employee_id  UUID,
    third_party_name      VARCHAR(200),
    status                VARCHAR(20)              NOT NULL,
    hold_reason           VARCHAR(500),
    labor_cost            NUMERIC(7, 2)            NOT NULL,
    third_party_cost      NUMERIC(7, 2)            NOT NULL,
    parts_cost            NUMERIC(7, 2)            NOT NULL,
    opened_by             UUID                     NOT NULL,
    opened_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    due_at                TIMESTAMP WITH TIME ZONE,
    started_at            TIMESTAMP WITH TIME ZONE,
    closed_at             TIMESTAMP WITH TIME ZONE,
    completion_notes      VARCHAR(2000),
    cancellation_reason   VARCHAR(500),
    out_of_order_id       UUID,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    version               BIGINT                   NOT NULL,
    CONSTRAINT work_order_pk PRIMARY KEY (id),
    CONSTRAINT work_order_number_uk UNIQUE (number),
    CONSTRAINT work_order_location_ck CHECK (num_nonnulls(room_id, area_id) = 1),
    CONSTRAINT work_order_description_ck CHECK (btrim(description) <> ''),
    CONSTRAINT work_order_category_ck CHECK (category IN
        ('ELECTRICAL', 'PLUMBING', 'AIR_CONDITIONING', 'FURNITURE', 'OTHER')),
    CONSTRAINT work_order_priority_ck CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT work_order_origin_ck CHECK (origin IN ('RECEPTION', 'HOUSEKEEPING', 'MAINTENANCE', 'CHECKLIST')),
    CONSTRAINT work_order_status_ck CHECK (status IN ('OPEN', 'IN_PROGRESS', 'ON_HOLD', 'DONE', 'CANCELLED')),
    CONSTRAINT work_order_costs_ck CHECK (labor_cost BETWEEN 0 AND 99999.99
        AND third_party_cost BETWEEN 0 AND 99999.99 AND parts_cost BETWEEN 0 AND 99999.99),
    -- O motivo da espera só em ON_HOLD; o do cancelamento só em CANCELLED.
    CONSTRAINT work_order_hold_reason_ck CHECK ((status = 'ON_HOLD') = (hold_reason IS NOT NULL)),
    CONSTRAINT work_order_cancellation_ck CHECK ((status = 'CANCELLED') = (cancellation_reason IS NOT NULL)),
    -- O fim só nos estados finais.
    CONSTRAINT work_order_closed_ck CHECK ((status IN ('DONE', 'CANCELLED')) = (closed_at IS NOT NULL))
);

-- A listagem, da mais recente para a mais antiga.
CREATE INDEX work_order_opened_idx ON maintenance.work_order (opened_at DESC, id DESC);
-- O escopo OWN: as que o usuário abriu e as de que o colaborador é responsável.
CREATE INDEX work_order_opened_by_idx ON maintenance.work_order (opened_by, opened_at DESC, id DESC);
CREATE INDEX work_order_assignee_idx ON maintenance.work_order (assignee_employee_id, opened_at DESC, id DESC)
    WHERE assignee_employee_id IS NOT NULL;
-- Os filtros por estado e por local.
CREATE INDEX work_order_status_idx ON maintenance.work_order (status, opened_at DESC, id DESC);
CREATE INDEX work_order_room_idx ON maintenance.work_order (room_id) WHERE room_id IS NOT NULL;
CREATE INDEX work_order_area_idx ON maintenance.work_order (area_id) WHERE area_id IS NOT NULL;

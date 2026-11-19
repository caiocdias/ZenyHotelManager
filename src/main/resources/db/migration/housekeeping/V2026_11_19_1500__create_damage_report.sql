-- Os danos (M6 E06, D4; RF-GOV-09, SPEC §8.2.10): numa UH ou numa área (um dos dois), com a descrição (texto livre,
-- até 500), o custo estimado (opcional, de 0 a 99 999,99), o instante do fato, quem registrou (o dono no escopo OWN) e
-- a reserva vinculada (sugerida no registro pelo instante do fato, só em UH; visível só a quem revisa). Ciclo
-- REPORTED -> REVIEWED -> CHARGED | WAIVED: a revisão grava o valor final (> 0), o código de lançamento, quem e
-- quando; a cobrança grava a conta e o lançamento do billing; a dispensa, o motivo; as duas, quando e por quem. A
-- origem é MANUAL, OFFLINE ou CHECKLIST (com a execução e a chave do item). Os ids de UH e área (rooms), de reserva
-- (reservations), de código, conta e lançamento (billing), de execução (checklists) e de usuário (iam) são opacos, sem
-- FK entre schemas. Alterado pela revisão, cobrança e dispensa, nunca apagado (o trigger no fim recusa a exclusão).

CREATE TABLE housekeeping.damage_report
(
    id                     UUID                     NOT NULL,
    room_id                UUID,
    area_id                UUID,
    description            VARCHAR(500)             NOT NULL,
    estimated_cost         NUMERIC(7, 2),
    occurred_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    reported_by            UUID                     NOT NULL,
    source                 VARCHAR(20)              NOT NULL,
    checklist_execution_id UUID,
    checklist_item_key     VARCHAR(40),
    reservation_id         UUID,
    status                 VARCHAR(20)              NOT NULL,
    final_amount           NUMERIC(7, 2),
    transaction_code_id    UUID,
    reviewed_at            TIMESTAMP WITH TIME ZONE,
    reviewed_by            UUID,
    waiver_reason          VARCHAR(500),
    folio_id               UUID,
    posting_id             UUID,
    resolved_at            TIMESTAMP WITH TIME ZONE,
    resolved_by            UUID,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT damage_report_pk PRIMARY KEY (id),
    CONSTRAINT damage_report_location_ck CHECK (num_nonnulls(room_id, area_id) = 1),
    CONSTRAINT damage_report_description_ck CHECK (btrim(description) <> ''),
    CONSTRAINT damage_report_estimated_cost_ck CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
    CONSTRAINT damage_report_status_ck CHECK (status IN ('REPORTED', 'REVIEWED', 'CHARGED', 'WAIVED')),
    CONSTRAINT damage_report_source_ck CHECK (source IN ('MANUAL', 'OFFLINE', 'CHECKLIST')),
    -- A origem do checklist leva a execução e o item; as outras, nenhum dos dois.
    CONSTRAINT damage_report_checklist_ck CHECK ((source = 'CHECKLIST') = (checklist_execution_id IS NOT NULL)
        AND (checklist_execution_id IS NULL) = (checklist_item_key IS NULL)),
    -- Só um dano de UH tem reserva vinculada.
    CONSTRAINT damage_report_reservation_ck CHECK (reservation_id IS NULL OR room_id IS NOT NULL),
    -- A revisão vem inteira (valor > 0, código, quem e quando) e existe em REVIEWED e CHARGED.
    CONSTRAINT damage_report_review_ck CHECK (num_nonnulls(final_amount, transaction_code_id, reviewed_at, reviewed_by)
        IN (0, 4) AND (final_amount IS NULL OR final_amount > 0)
        AND (status NOT IN ('REVIEWED', 'CHARGED') OR final_amount IS NOT NULL)),
    -- A cobrança (conta e lançamento) só em CHARGED, sempre com a reserva; o motivo só em WAIVED.
    CONSTRAINT damage_report_charge_ck CHECK ((status = 'CHARGED') = (posting_id IS NOT NULL)
        AND (posting_id IS NULL) = (folio_id IS NULL) AND (posting_id IS NULL OR reservation_id IS NOT NULL)),
    CONSTRAINT damage_report_waiver_ck CHECK ((status = 'WAIVED') = (waiver_reason IS NOT NULL)),
    -- Cobrado ou dispensado, quando e por quem.
    CONSTRAINT damage_report_resolution_ck CHECK ((status IN ('CHARGED', 'WAIVED')) = (resolved_at IS NOT NULL)
        AND (resolved_at IS NULL) = (resolved_by IS NULL))
);

-- A listagem, do fato mais recente para o mais antigo.
CREATE INDEX damage_report_occurred_idx ON housekeeping.damage_report (occurred_at DESC, id DESC);
-- O escopo OWN: os registros de quem registrou.
CREATE INDEX damage_report_reporter_idx ON housekeeping.damage_report (reported_by, occurred_at DESC, id DESC);

CREATE TRIGGER damage_report_never_deleted
    BEFORE DELETE
    ON housekeeping.damage_report
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

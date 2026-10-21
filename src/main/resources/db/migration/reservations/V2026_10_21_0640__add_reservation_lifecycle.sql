-- Alteração, cancelamento e reativação de reservas (M4 E08; RF-RES-05 e 06, RF-TAR-07, RN-14, RN-15, SPEC §8.4).
--
-- reservation ganha o cancelamento corrente (só numa reserva CANCELLED): o motivo, a observação (texto livre do
-- operador: fica só aqui, nunca vai à trilha, ao histórico, a eventos nem a log), a situação anterior, quando e por
-- quem, e a multa pela política copiada (o prazo, o valor devido, se foi isentada com aprovação de superior e o
-- lançamento na conta da reserva no código CANCELLATION_FEE). penalties_charged soma as multas já lançadas em todos os
-- cancelamentos da reserva: um cancelamento depois de uma reativação lança só o que falta, nunca a multa de novo.
--
-- reservation_history: o histórico da reserva (RF-RES-05), uma linha por alteração, cancelamento ou reativação, com a
-- data operacional, o instante, quem, a aprovação de superior, os campos mudados e o retrato antes e depois (sem texto
-- livre nem dado de hóspede) e a versão da reserva depois. Append-only: o papel da aplicação só tem SELECT e INSERT
-- (REVOKE no fim, bean AppendOnlyTables do módulo) e um trigger recusa UPDATE e DELETE até do dono.

ALTER TABLE reservations.reservation
    ADD COLUMN cancellation_reason          varchar(30),
    ADD COLUMN cancellation_notes           varchar(500),
    ADD COLUMN cancellation_previous_status varchar(20),
    ADD COLUMN cancelled_at                 timestamptz,
    ADD COLUMN cancelled_by                 uuid,
    ADD COLUMN penalty_deadline             timestamptz,
    ADD COLUMN penalty_amount               numeric(14, 2),
    ADD COLUMN penalty_waived               boolean,
    ADD COLUMN penalty_posting_id           uuid,
    ADD COLUMN penalty_waiver_approval_id   uuid,
    ADD COLUMN penalty_waiver_approved_by   uuid,
    ADD COLUMN penalties_charged            numeric(14, 2) NOT NULL DEFAULT 0;

ALTER TABLE reservations.reservation
    ADD CONSTRAINT reservation_cancellation_ck CHECK (
        (status = 'CANCELLED') = (cancellation_reason IS NOT NULL)
        AND (cancellation_reason IS NULL OR (
            cancellation_previous_status IN ('PROVISIONAL', 'CONFIRMED')
            AND cancelled_at IS NOT NULL AND penalty_deadline IS NOT NULL
            AND penalty_amount IS NOT NULL AND penalty_amount >= 0 AND penalty_waived IS NOT NULL
            AND NOT (penalty_waived AND penalty_posting_id IS NOT NULL)
            AND (penalty_posting_id IS NULL OR penalty_amount > 0)
            AND (NOT penalty_waived OR penalty_amount > 0)
        ))
        AND (cancellation_reason IS NOT NULL OR (
            cancellation_notes IS NULL AND cancellation_previous_status IS NULL AND cancelled_at IS NULL
            AND cancelled_by IS NULL AND penalty_deadline IS NULL AND penalty_amount IS NULL
            AND penalty_waived IS NULL AND penalty_posting_id IS NULL AND penalty_waiver_approval_id IS NULL
        ))
        AND (cancellation_notes IS NULL OR char_length(cancellation_notes) BETWEEN 1 AND 500)
    ),
    ADD CONSTRAINT reservation_penalty_waiver_ck
        CHECK ((penalty_waiver_approval_id IS NULL) = (penalty_waiver_approved_by IS NULL)
            AND (penalty_waiver_approval_id IS NULL OR penalty_waived)),
    ADD CONSTRAINT reservation_penalties_charged_ck CHECK (penalties_charged >= 0);

-- Um lançamento de multa pertence a um único cancelamento.
CREATE UNIQUE INDEX reservation_penalty_posting_uk
    ON reservations.reservation (penalty_posting_id) WHERE penalty_posting_id IS NOT NULL;

CREATE TABLE reservations.reservation_history (
    id             uuid        NOT NULL,
    reservation_id uuid        NOT NULL,
    kind           varchar(20) NOT NULL,
    business_date  date        NOT NULL,
    occurred_at    timestamptz NOT NULL,
    actor_id       uuid,
    approval_id    uuid,
    approved_by    uuid,
    changed_fields text[]      NOT NULL DEFAULT '{}',
    before_state   jsonb       NOT NULL,
    after_state    jsonb       NOT NULL,
    version        bigint      NOT NULL,
    CONSTRAINT reservation_history_pk PRIMARY KEY (id),
    CONSTRAINT reservation_history_reservation_fk
        FOREIGN KEY (reservation_id) REFERENCES reservations.reservation (id),
    CONSTRAINT reservation_history_kind_ck CHECK (kind IN ('CHANGE', 'CANCEL', 'REACTIVATE')),
    CONSTRAINT reservation_history_approval_ck CHECK ((approval_id IS NULL) = (approved_by IS NULL))
);

CREATE INDEX reservation_history_reservation_idx
    ON reservations.reservation_history (reservation_id, occurred_at DESC, id DESC);

CREATE FUNCTION reservations.refuse_history_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'O histórico das reservas (reservations.reservation_history) é imutável'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER reservation_history_immutable
    BEFORE UPDATE OR DELETE
    ON reservations.reservation_history
    FOR EACH ROW
EXECUTE FUNCTION reservations.refuse_history_change();

-- O ALTER DEFAULT PRIVILEGES do schema deu DML à tabela nova: o histórico é só SELECT e INSERT.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE reservations.reservation_history FROM "${app_role}";

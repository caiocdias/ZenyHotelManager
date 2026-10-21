-- Alocação de UH a reservas (M4 E10; RF-RES-04, RN-04, RN-07, RF-UH-05, SPEC §8.2.6 RoomAssignment, H5 e H11).
--
-- room_assignment: a UH (id opaco do rooms, sem FK entre schemas) alocada a uma reserva num período, de starts_on a
-- ends_on (a saída da reserva). stay é o período em datas, para leitura e para cruzar com os OOO (que bloqueiam a data
-- inteira): [starts_on, ends_on) no pernoite e [data, data] (guardado como [data, data + 1)) no day use.
--
-- slots é o que a alocação ocupa de fato na UH, em meios-dias (H5): com k = data - 2000-01-01, o meio-dia 2k é o dia k
-- entre o check-out e o check-in, e o 2k+1 é a noite k. O pernoite [chegada a, saída s) ocupa [2a+1, 2s): as noites e os
-- dias do meio da estadia, nunca o dia da chegada nem o da saída; o day use na data d ocupa [2d, 2d+1), só o dia d.
-- Assim a saída de uma reserva e a chegada de outra no mesmo dia não conflitam; o day use não conflita com a saída nem
-- com a chegada de outras reservas na data, só com a estadia que atravessa o dia (chegada < d < saída) e com outro day
-- use da data (H5).
--
-- RN-04 no banco: room_assignment_overlap_ex recusa duas alocações ATIVAS (released_at nulo) da mesma UH cujos slots se
-- cruzam (EXCLUDE USING gist, btree_gist do shared); a alocação solta (desalocada, realocada, de reserva cancelada ou
-- expirada, ou solta pela alteração) não bloqueia mais a UH, mas continua gravada (o histórico das UHs da reserva).
-- Uma reserva tem no máximo uma alocação ativa (room_assignment_active_uk).
--
-- do_not_move: "não mover" (RF-RES-04): a aplicação não troca nem solta a UH sem tirar a marca antes (o cancelamento
-- solta mesmo assim).
-- reallocation_*: a marca "requer realocação" (RN-07, H11): a UH entrou num OOO que atinge a estadia; gravada pelo
-- listener de OutOfOrderChanged, que a tira quando o OOO deixa de atingir a estadia.
--
-- Coerência com a reserva (gatilhos adiados, conferidos no commit): uma alocação ativa só pertence a uma reserva
-- PROVISIONAL, CONFIRMED ou IN_HOUSE, e uma reserva cancelada (ou no-show) não fica com alocação ativa. Uma alocação
-- solta não volta a valer nem muda de UH ou de reserva (room_assignment_guarded). Nada é apagado.

CREATE TABLE reservations.room_assignment (
    id                           uuid        NOT NULL,
    reservation_id               uuid        NOT NULL,
    room_id                      uuid        NOT NULL,
    starts_on                    date        NOT NULL,
    ends_on                      date        NOT NULL,
    stay                         daterange GENERATED ALWAYS AS (
        daterange(starts_on, GREATEST(ends_on, starts_on + 1), '[)')) STORED,
    slots                        int4range GENERATED ALWAYS AS (
        CASE WHEN ends_on = starts_on
            THEN int4range(2 * (starts_on - DATE '2000-01-01'), 2 * (starts_on - DATE '2000-01-01') + 1, '[)')
            ELSE int4range(2 * (starts_on - DATE '2000-01-01') + 1, 2 * (ends_on - DATE '2000-01-01'), '[)')
        END) STORED,
    reason                       varchar(30) NOT NULL,
    do_not_move                  boolean     NOT NULL DEFAULT false,
    reallocation_required_at     timestamptz,
    reallocation_out_of_order_id uuid,
    assigned_at                  timestamptz NOT NULL,
    assigned_by                  uuid,
    released_at                  timestamptz,
    released_by                  uuid,
    release_reason               varchar(30),
    CONSTRAINT room_assignment_pk PRIMARY KEY (id),
    CONSTRAINT room_assignment_reservation_fk
        FOREIGN KEY (reservation_id) REFERENCES reservations.reservation (id),
    CONSTRAINT room_assignment_period_ck CHECK (ends_on >= starts_on AND ends_on - starts_on <= 365),
    CONSTRAINT room_assignment_reason_ck
        CHECK (reason IN ('INITIAL', 'GUEST_REQUEST', 'OUT_OF_ORDER', 'ROOM_ISSUE', 'OPERATIONAL')),
    CONSTRAINT room_assignment_release_ck CHECK (
        (released_at IS NULL) = (release_reason IS NULL)
        AND (released_by IS NULL OR released_at IS NOT NULL)
        AND (release_reason IS NULL OR release_reason IN
            ('UNASSIGNED', 'REALLOCATED', 'RESERVATION_CANCELLED', 'RESERVATION_CHANGED'))
    ),
    CONSTRAINT room_assignment_reallocation_ck
        CHECK ((reallocation_required_at IS NULL) = (reallocation_out_of_order_id IS NULL)),
    -- RN-04: a mesma UH nunca tem duas alocações ativas que ocupam o mesmo meio-dia.
    CONSTRAINT room_assignment_overlap_ex
        EXCLUDE USING gist (room_id WITH =, slots WITH &&) WHERE (released_at IS NULL)
);

-- No máximo uma alocação ativa por reserva.
CREATE UNIQUE INDEX room_assignment_active_uk
    ON reservations.room_assignment (reservation_id) WHERE released_at IS NULL;
-- O histórico das alocações de uma reserva e a lista das que requerem realocação.
CREATE INDEX room_assignment_reservation_idx ON reservations.room_assignment (reservation_id, assigned_at);
CREATE INDEX room_assignment_reallocation_idx
    ON reservations.room_assignment (starts_on, id)
    WHERE released_at IS NULL AND reallocation_required_at IS NOT NULL;

CREATE TRIGGER room_assignment_refuse_delete
    BEFORE DELETE ON reservations.room_assignment
    FOR EACH ROW EXECUTE FUNCTION reservations.refuse_delete();

-- Uma alocação solta nunca volta a valer nem muda; a ativa não muda de reserva nem de UH (realocar é outra linha).
CREATE FUNCTION reservations.guard_room_assignment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'reservations: a alocação % já foi solta e não muda', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.reservation_id <> OLD.reservation_id OR NEW.room_id <> OLD.room_id
        OR NEW.assigned_at <> OLD.assigned_at OR NEW.reason <> OLD.reason THEN
        RAISE EXCEPTION 'reservations: a alocação % não muda de reserva, de UH nem de origem', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER room_assignment_guarded
    BEFORE UPDATE ON reservations.room_assignment
    FOR EACH ROW EXECUTE FUNCTION reservations.guard_room_assignment();

-- Uma alocação ativa só pertence a uma reserva ativa (no commit).
CREATE FUNCTION reservations.require_active_reservation_for_assignment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.released_at IS NULL AND NOT EXISTS (
        SELECT 1 FROM reservations.reservation r
        WHERE r.id = NEW.reservation_id AND r.status IN ('PROVISIONAL', 'CONFIRMED', 'IN_HOUSE')
    ) THEN
        RAISE EXCEPTION 'reservations: a alocação % pertence a uma reserva que não está ativa', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER room_assignment_reservation_active
    AFTER INSERT OR UPDATE ON reservations.room_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION reservations.require_active_reservation_for_assignment();

-- Uma reserva cancelada ou no-show não fica com alocação ativa (no commit).
CREATE FUNCTION reservations.require_released_assignment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (
        SELECT 1 FROM reservations.room_assignment a
        WHERE a.reservation_id = NEW.id AND a.released_at IS NULL
    ) THEN
        RAISE EXCEPTION 'reservations: a reserva % não está ativa e ainda tem UH alocada', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER reservation_assignment_released
    AFTER UPDATE OF status ON reservations.reservation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status IN ('CANCELLED', 'NO_SHOW'))
    EXECUTE FUNCTION reservations.require_released_assignment();

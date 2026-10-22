-- Transições de hospedagem (M5 E01; SPEC §8.2.6 e §8.4, RF-REC-01, 04, 05 e 08, RN-04, RN-24, H10 e H16 do M5).
--
-- reservation ganha o check-in e o check-out: a data operacional, o instante e quem (nulo = sistema). O check-in só
-- existe numa reserva IN_HOUSE ou CHECKED_OUT (desfeito o check-in, a reserva volta a CONFIRMED sem ele); o check-out
-- só numa CHECKED_OUT.
--
-- reservation_guest ganha os dados de check-in de cada hóspede da estadia (H10): o motivo de viagem e o meio de
-- transporte (códigos opacos dos domínios da FNRH, conferidos pelo fnrh no envio, H21), os instantes de entrada e de
-- saída e a comprovação do menor (RN-24, MinorAuthorization): o tipo (PARENT_PRESENT, WRITTEN_AUTHORIZATION ou
-- COURT_ORDER), o responsável (outro hóspede da mesma estadia, adulto: a aplicação confere), o parentesco (código
-- opaco), o anexo (id opaco de um anexo do files, obrigatório na autorização escrita e na ordem judicial) e a validade
-- opcional. Nenhum texto livre: tudo é código ou id. A regra "nenhum menor hospedado sem comprovação" fica no domínio
-- da reserva (o banco não conhece a idade do hóspede).
--
-- O histórico ganha os tipos CHECK_IN, UNDO_CHECK_IN, CHECK_OUT e ROOM_MOVE; a alocação, os motivos de soltura
-- CHECKED_OUT (o check-out, inclusive o antecipado, encerra a alocação na mesma transação) e ROOM_MOVED (a troca de UH
-- do hospedado encerra a alocação na data operacional e abre outra).
--
-- Gatilhos adiados novos (no commit): uma reserva encerrada (CHECKED_OUT) não fica com alocação ativa (como a
-- cancelada e o no-show), e uma reserva hospedada (IN_HOUSE) sempre tem uma alocação ativa (o check-in exige a UH, e a
-- troca de UH solta uma e abre outra na mesma transação).

ALTER TABLE reservations.reservation
    ADD COLUMN checked_in_on  date,
    ADD COLUMN checked_in_at  timestamptz,
    ADD COLUMN checked_in_by  uuid,
    ADD COLUMN checked_out_on date,
    ADD COLUMN checked_out_at timestamptz,
    ADD COLUMN checked_out_by uuid;

ALTER TABLE reservations.reservation
    ADD CONSTRAINT reservation_check_in_ck CHECK (
        (status IN ('IN_HOUSE', 'CHECKED_OUT')) = (checked_in_at IS NOT NULL)
        AND (checked_in_at IS NULL) = (checked_in_on IS NULL)
        AND (checked_in_by IS NULL OR checked_in_at IS NOT NULL)
    ),
    ADD CONSTRAINT reservation_check_out_ck CHECK (
        (status = 'CHECKED_OUT') = (checked_out_at IS NOT NULL)
        AND (checked_out_at IS NULL) = (checked_out_on IS NULL)
        AND (checked_out_by IS NULL OR checked_out_at IS NOT NULL)
        AND (checked_out_on IS NULL OR checked_out_on >= checked_in_on)
    );

ALTER TABLE reservations.reservation_guest
    ADD COLUMN travel_reason              varchar(40),
    ADD COLUMN transport_mode             varchar(40),
    ADD COLUMN checked_in_at              timestamptz,
    ADD COLUMN checked_out_at             timestamptz,
    ADD COLUMN minor_proof_type           varchar(30),
    ADD COLUMN minor_responsible_guest_id uuid,
    ADD COLUMN minor_relationship         varchar(40),
    ADD COLUMN minor_attachment_id        uuid,
    ADD COLUMN minor_valid_until          date;

ALTER TABLE reservations.reservation_guest
    ADD CONSTRAINT reservation_guest_codes_ck CHECK (
        (travel_reason IS NULL OR travel_reason ~ '^[A-Za-z0-9_.-]{1,40}$')
        AND (transport_mode IS NULL OR transport_mode ~ '^[A-Za-z0-9_.-]{1,40}$')
    ),
    ADD CONSTRAINT reservation_guest_check_out_ck CHECK (checked_out_at IS NULL OR checked_in_at IS NOT NULL),
    ADD CONSTRAINT reservation_guest_minor_ck CHECK (
        (minor_proof_type IS NULL AND minor_responsible_guest_id IS NULL AND minor_relationship IS NULL
            AND minor_attachment_id IS NULL AND minor_valid_until IS NULL)
        OR (minor_proof_type IN ('PARENT_PRESENT', 'WRITTEN_AUTHORIZATION', 'COURT_ORDER')
            AND minor_responsible_guest_id IS NOT NULL AND minor_responsible_guest_id <> guest_id
            AND minor_relationship ~ '^[A-Za-z0-9_.-]{1,40}$'
            AND (minor_proof_type = 'PARENT_PRESENT') = (minor_attachment_id IS NULL))
    );

-- O listener de GuestsMerged reponta também o responsável das comprovações.
CREATE INDEX reservation_guest_minor_responsible_idx
    ON reservations.reservation_guest (minor_responsible_guest_id) WHERE minor_responsible_guest_id IS NOT NULL;

ALTER TABLE reservations.reservation_history DROP CONSTRAINT reservation_history_kind_ck;
ALTER TABLE reservations.reservation_history
    ADD CONSTRAINT reservation_history_kind_ck CHECK (kind IN ('CHANGE', 'CANCEL', 'REACTIVATE', 'CONFIRM',
        'GUARANTEE', 'CHECK_IN', 'UNDO_CHECK_IN', 'CHECK_OUT', 'ROOM_MOVE'));

ALTER TABLE reservations.room_assignment DROP CONSTRAINT room_assignment_release_ck;
ALTER TABLE reservations.room_assignment
    ADD CONSTRAINT room_assignment_release_ck CHECK (
        (released_at IS NULL) = (release_reason IS NULL)
        AND (released_by IS NULL OR released_at IS NOT NULL)
        AND (release_reason IS NULL OR release_reason IN
            ('UNASSIGNED', 'REALLOCATED', 'RESERVATION_CANCELLED', 'RESERVATION_CHANGED', 'CHECKED_OUT',
             'ROOM_MOVED'))
    );

-- Uma reserva encerrada não fica com alocação ativa (no commit; a mesma regra da cancelada e do no-show).
CREATE CONSTRAINT TRIGGER reservation_checked_out_assignment_released
    AFTER UPDATE OF status ON reservations.reservation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'CHECKED_OUT')
    EXECUTE FUNCTION reservations.require_released_assignment();

-- Uma reserva hospedada sempre tem uma alocação ativa (no commit).
CREATE FUNCTION reservations.require_in_house_assignment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    target uuid;
BEGIN
    IF TG_TABLE_NAME = 'reservation' THEN
        target := NEW.id;
    ELSE
        target := NEW.reservation_id;
    END IF;
    IF EXISTS (SELECT 1 FROM reservations.reservation r WHERE r.id = target AND r.status = 'IN_HOUSE')
        AND NOT EXISTS (
            SELECT 1 FROM reservations.room_assignment a WHERE a.reservation_id = target AND a.released_at IS NULL
        ) THEN
        RAISE EXCEPTION 'reservations: a reserva hospedada % ficou sem UH alocada', target
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER reservation_in_house_assigned
    AFTER UPDATE OF status ON reservations.reservation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.status = 'IN_HOUSE')
    EXECUTE FUNCTION reservations.require_in_house_assignment();

CREATE CONSTRAINT TRIGGER room_assignment_in_house_kept
    AFTER UPDATE OF released_at ON reservations.room_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.released_at IS NOT NULL)
    EXECUTE FUNCTION reservations.require_in_house_assignment();

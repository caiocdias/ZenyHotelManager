-- O no-show (M6 E11): o fechamento do dia marca a reserva confirmada que não chegou. O histórico ganha o tipo
-- NO_SHOW e a alocação solta ganha o motivo RESERVATION_NO_SHOW; os valores atuais continuam. Nenhuma coluna nova: a
-- multa fica no lançamento, em penalties_charged e no histórico. O gatilho adiado reservation_assignment_released já
-- cobre NO_SHOW (uma reserva no-show não fica com alocação ativa).
ALTER TABLE reservations.reservation_history DROP CONSTRAINT reservation_history_kind_ck;
ALTER TABLE reservations.reservation_history
    ADD CONSTRAINT reservation_history_kind_ck CHECK (kind IN ('CHANGE', 'CANCEL', 'REACTIVATE', 'CONFIRM',
        'GUARANTEE', 'CHECK_IN', 'UNDO_CHECK_IN', 'CHECK_OUT', 'ROOM_MOVE', 'NO_SHOW'));

ALTER TABLE reservations.room_assignment DROP CONSTRAINT room_assignment_release_ck;
ALTER TABLE reservations.room_assignment
    ADD CONSTRAINT room_assignment_release_ck CHECK (
        (released_at IS NULL) = (release_reason IS NULL)
        AND (released_by IS NULL OR released_at IS NOT NULL)
        AND (release_reason IS NULL OR release_reason IN
            ('UNASSIGNED', 'REALLOCATED', 'RESERVATION_CANCELLED', 'RESERVATION_CHANGED', 'CHECKED_OUT',
             'ROOM_MOVED', 'RESERVATION_NO_SHOW'))
    );

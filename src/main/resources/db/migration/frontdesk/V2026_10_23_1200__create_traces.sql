-- Recados da recepção (M5 E06; RF-REC-07, RN-13 e H10 do M3). Primeiro estado próprio do módulo frontdesk.
-- Ids de outros módulos (setor, reserva, UH, usuários) são opacos: sem FK entre schemas.
--
-- trace: um recado para um setor, com o vencimento (data operacional), o texto livre do operador, o vínculo com uma
-- reserva OU com uma UH (exatamente um), o responsável opcional (um usuário) e a resolução (quando e por quem). O texto
-- nunca vai à trilha de auditoria, a eventos, a logs nem a problemas (RN-13): a trilha leva só ids, setor, datas,
-- situação e o tamanho do texto. A situação (aberto ou resolvido) é calculada de resolved_at. Recados nunca são
-- apagados (a resolução os encerra).

CREATE TABLE frontdesk.trace (
    id               uuid         NOT NULL,
    department_id    uuid         NOT NULL,
    due_on           date         NOT NULL,
    text             varchar(500) NOT NULL,
    reservation_id   uuid,
    room_id          uuid,
    assignee_user_id uuid,
    created_at       timestamptz  NOT NULL,
    created_by       uuid         NOT NULL,
    updated_at       timestamptz  NOT NULL,
    resolved_at      timestamptz,
    resolved_by      uuid,
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT trace_pk PRIMARY KEY (id),
    CONSTRAINT trace_text_ck CHECK (length(btrim(text)) BETWEEN 1 AND 500),
    CONSTRAINT trace_link_ck CHECK ((reservation_id IS NULL) <> (room_id IS NULL)),
    CONSTRAINT trace_resolution_ck CHECK ((resolved_at IS NULL) = (resolved_by IS NULL))
);

CREATE INDEX trace_due_idx ON frontdesk.trace (due_on, id);
CREATE INDEX trace_department_due_idx ON frontdesk.trace (department_id, due_on, id);
CREATE INDEX trace_reservation_idx ON frontdesk.trace (reservation_id) WHERE reservation_id IS NOT NULL;
CREATE INDEX trace_room_idx ON frontdesk.trace (room_id) WHERE room_id IS NOT NULL;

-- Recados nunca são apagados: a resolução os encerra.
CREATE FUNCTION frontdesk.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'frontdesk: % não é apagado', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trace_refuse_delete
    BEFORE DELETE ON frontdesk.trace
    FOR EACH ROW EXECUTE FUNCTION frontdesk.refuse_delete();

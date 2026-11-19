-- Os achados e perdidos (M6 E06, D2; RF-GOV-08, SPEC §8.2.10): achado numa UH ou numa área (um dos dois), com a
-- descrição (texto livre, até 500), o local de guarda (texto curto, opcional), quem registrou (o dono no escopo OWN) e
-- quando foi achado. Ciclo STORED -> RETURNED | DISCARDED | DONATED: a devolução grava a quem (texto livre, até 200,
-- sem ligação com hóspede ou reserva), o descarte e a doação gravam o motivo; os três, quando e por quem. O vencimento
-- (data local de found_at no fuso do hotel mais o prazo de guarda, contra a data operacional) é calculado na consulta,
-- sem coluna. A origem é MANUAL, OFFLINE ou CHECKLIST (com a execução e a chave do item). Os ids de UH e área (rooms),
-- de execução de checklist (checklists) e de usuário (iam) são opacos, sem FK entre schemas. Alterada pelo destino,
-- nunca apagada (o trigger no fim recusa a exclusão).

CREATE TABLE housekeeping.lost_and_found_item
(
    id                     UUID                     NOT NULL,
    room_id                UUID,
    area_id                UUID,
    description            VARCHAR(500)             NOT NULL,
    storage_place          VARCHAR(100),
    found_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    reported_by            UUID                     NOT NULL,
    source                 VARCHAR(20)              NOT NULL,
    checklist_execution_id UUID,
    checklist_item_key     VARCHAR(40),
    status                 VARCHAR(20)              NOT NULL,
    returned_to            VARCHAR(200),
    disposal_reason        VARCHAR(500),
    resolved_at            TIMESTAMP WITH TIME ZONE,
    resolved_by            UUID,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT lost_and_found_item_pk PRIMARY KEY (id),
    CONSTRAINT lost_and_found_item_location_ck CHECK (num_nonnulls(room_id, area_id) = 1),
    CONSTRAINT lost_and_found_item_description_ck CHECK (btrim(description) <> ''),
    CONSTRAINT lost_and_found_item_status_ck CHECK (status IN ('STORED', 'RETURNED', 'DISCARDED', 'DONATED')),
    CONSTRAINT lost_and_found_item_source_ck CHECK (source IN ('MANUAL', 'OFFLINE', 'CHECKLIST')),
    -- A origem do checklist leva a execução e o item; as outras, nenhum dos dois.
    CONSTRAINT lost_and_found_item_checklist_ck CHECK ((source = 'CHECKLIST') = (checklist_execution_id IS NOT NULL)
        AND (checklist_execution_id IS NULL) = (checklist_item_key IS NULL)),
    -- Fora de STORED, quando e por quem; a quem só na devolução; o motivo só no descarte e na doação.
    CONSTRAINT lost_and_found_item_resolution_ck CHECK ((status = 'STORED') = (resolved_at IS NULL)
        AND (resolved_at IS NULL) = (resolved_by IS NULL)),
    CONSTRAINT lost_and_found_item_returned_ck CHECK ((status = 'RETURNED') = (returned_to IS NOT NULL)),
    CONSTRAINT lost_and_found_item_disposal_ck CHECK ((status IN ('DISCARDED', 'DONATED')) = (disposal_reason IS NOT NULL))
);

-- A listagem, da achada mais recente para a mais antiga, e os vencidos (STORED achados antes do corte).
CREATE INDEX lost_and_found_item_found_idx ON housekeeping.lost_and_found_item (found_at DESC, id DESC);
-- O escopo OWN: os registros de quem registrou.
CREATE INDEX lost_and_found_item_reporter_idx ON housekeeping.lost_and_found_item (reported_by, found_at DESC, id DESC);

CREATE TRIGGER lost_and_found_item_never_deleted
    BEFORE DELETE
    ON housekeeping.lost_and_found_item
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

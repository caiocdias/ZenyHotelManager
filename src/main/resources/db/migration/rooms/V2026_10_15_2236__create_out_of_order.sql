-- Períodos fora de ordem (OOO) e fora de serviço (OOS) de uma UH (SPEC §5.4 e §8.2.3, RF-UH-05, RN-05,
-- RN-07; M2 E03): tipo, período em datas de negócio (inclusive nas duas pontas), motivo, responsável,
-- status de governança de retorno, quem abriu e a liberação. Nada é apagado: liberar marca a data
-- operacional da liberação (released_on), e o período deixa de bloquear a UH a partir dela.

CREATE TABLE rooms.out_of_order
(
    id             UUID                     NOT NULL,
    room_id        UUID                     NOT NULL,
    kind           VARCHAR(20)              NOT NULL,
    starts_on      DATE                     NOT NULL,
    ends_on        DATE                     NOT NULL,
    reason         VARCHAR(500)             NOT NULL,
    responsible    VARCHAR(120)             NOT NULL,
    return_status  VARCHAR(10)              NOT NULL,
    opened_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    opened_by      UUID,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    released_on    DATE,
    released_at    TIMESTAMP WITH TIME ZONE,
    released_by    UUID,
    version        BIGINT                   NOT NULL,
    -- Os dias que o período bloqueia de fato: de starts_on até ends_on, ou até a véspera da liberação
    -- (vazio se liberado antes de começar). Calculado pelo banco; a aplicação não o grava.
    blocked_period DATERANGE GENERATED ALWAYS AS (
        daterange(starts_on,
                  GREATEST(starts_on, LEAST(ends_on + 1, COALESCE(released_on, ends_on + 1))),
                  '[)')) STORED,
    CONSTRAINT out_of_order_pk PRIMARY KEY (id),
    CONSTRAINT out_of_order_room_fk FOREIGN KEY (room_id) REFERENCES rooms.room (id),
    CONSTRAINT out_of_order_kind_ck CHECK (kind IN ('OUT_OF_ORDER', 'OUT_OF_SERVICE')),
    -- Até um ano (365 dias, as duas pontas inclusive).
    CONSTRAINT out_of_order_period_ck CHECK (ends_on >= starts_on AND ends_on - starts_on < 365),
    CONSTRAINT out_of_order_reason_ck CHECK (length(btrim(reason)) > 0),
    CONSTRAINT out_of_order_responsible_ck CHECK (length(btrim(responsible)) > 0),
    CONSTRAINT out_of_order_return_status_ck CHECK (return_status IN ('DIRTY', 'CLEAN', 'INSPECTED')),
    CONSTRAINT out_of_order_release_ck CHECK ((released_on IS NULL) = (released_at IS NULL)
        AND (released_by IS NULL OR released_on IS NOT NULL)),
    -- Dois períodos (OOO ou OOS) da mesma UH nunca bloqueiam o mesmo dia.
    CONSTRAINT out_of_order_overlap_ex EXCLUDE USING gist (room_id WITH =, blocked_period WITH &&)
);

CREATE INDEX out_of_order_period_idx ON rooms.out_of_order (starts_on, id);

-- Nada é apagado (o papel da aplicação recebe DML pelo ALTER DEFAULT PRIVILEGES do rooms, mas o
-- trigger recusa a exclusão).
CREATE TRIGGER out_of_order_never_deleted
    BEFORE DELETE
    ON rooms.out_of_order
    FOR EACH ROW
EXECUTE FUNCTION rooms.refuse_delete();

-- Status da UH (SPEC §5.4 e §8.4, RF-UH-04 a 08, RN-05, RN-08, RN-09; M2 E02): ocupação com o número de
-- hóspedes informado pela recepção, status de governança com a inspeção pendente e sinalização do
-- hóspede, o histórico de mudanças e as observações da governança (discrepâncias).

-- Dimensões do status na própria UH. A versão (lock otimista) é a mesma do cadastro. A UH nasce Vaga e
-- Suja (SPEC §8.4); as já cadastradas também.
ALTER TABLE rooms.room
    ADD COLUMN occupancy            VARCHAR(10)              NOT NULL DEFAULT 'VACANT',
    ADD COLUMN guest_count          INT,
    ADD COLUMN housekeeping_status  VARCHAR(10)              NOT NULL DEFAULT 'DIRTY',
    ADD COLUMN inspection_pending   BOOLEAN                  NOT NULL DEFAULT FALSE,
    ADD COLUMN service_flag         VARCHAR(20)              NOT NULL DEFAULT 'NONE',
    ADD COLUMN status_changed_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN occupancy_changed_at TIMESTAMP WITH TIME ZONE;

UPDATE rooms.room
SET status_changed_at    = created_at,
    occupancy_changed_at = created_at;

-- Os padrões ficam: uma UH inserida sem o status nasce Vaga e Suja, agora.
ALTER TABLE rooms.room
    ALTER COLUMN status_changed_at SET DEFAULT now(),
    ALTER COLUMN status_changed_at SET NOT NULL,
    ALTER COLUMN occupancy_changed_at SET DEFAULT now(),
    ALTER COLUMN occupancy_changed_at SET NOT NULL,
    ADD CONSTRAINT room_occupancy_ck CHECK (occupancy IN ('VACANT', 'OCCUPIED')),
    -- Ocupada sempre com o número de hóspedes (1 a 40); vaga, sem ele.
    ADD CONSTRAINT room_guest_count_ck CHECK (
        (occupancy = 'OCCUPIED' AND guest_count BETWEEN 1 AND 40)
            OR (occupancy = 'VACANT' AND guest_count IS NULL)),
    ADD CONSTRAINT room_housekeeping_status_ck CHECK (housekeeping_status IN ('DIRTY', 'CLEAN', 'INSPECTED')),
    -- "Limpo — inspeção pendente" só existe com a UH limpa.
    ADD CONSTRAINT room_inspection_pending_ck CHECK (NOT inspection_pending OR housekeeping_status = 'CLEAN'),
    -- A sinalização do hóspede só vale numa UH ocupada.
    ADD CONSTRAINT room_service_flag_ck CHECK (
        service_flag = 'NONE' OR (service_flag IN ('DO_NOT_DISTURB', 'MAKE_UP_ROOM') AND occupancy = 'OCCUPIED'));

-- Histórico de mudanças de status (RF-UH-06, RN-08): um registro por campo que mudou, com quem (nulo =
-- o sistema), quando, a origem e a versão da UH depois da mudança. Append-only para o papel da aplicação.
CREATE TABLE rooms.room_status_change
(
    id           UUID                     NOT NULL,
    room_id      UUID                     NOT NULL,
    field        VARCHAR(30)              NOT NULL,
    from_value   VARCHAR(20),
    to_value     VARCHAR(20),
    changed_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    changed_by   UUID,
    source       VARCHAR(20)              NOT NULL,
    room_version BIGINT                   NOT NULL,
    CONSTRAINT room_status_change_pk PRIMARY KEY (id),
    CONSTRAINT room_status_change_room_fk FOREIGN KEY (room_id) REFERENCES rooms.room (id),
    CONSTRAINT room_status_change_field_ck CHECK (field IN
        ('OCCUPANCY', 'GUEST_COUNT', 'HOUSEKEEPING_STATUS', 'INSPECTION_PENDING', 'SERVICE_FLAG')),
    CONSTRAINT room_status_change_source_ck CHECK (source IN
        ('MANUAL', 'TASK', 'CHECK_IN', 'CHECK_OUT', 'END_OF_DAY')),
    CONSTRAINT room_status_change_values_ck CHECK (from_value IS DISTINCT FROM to_value)
);

CREATE INDEX room_status_change_room_idx ON rooms.room_status_change (room_id, changed_at, id);

-- O que a governança encontrou na UH (RF-UH-08): ocupação e, se contou, o número de pessoas. Comparada
-- com a ocupação informada pela recepção, mostra as discrepâncias. Sem texto livre (nada de dado de
-- hóspede). Append-only para o papel da aplicação.
CREATE TABLE rooms.housekeeping_observation
(
    id          UUID                     NOT NULL,
    room_id     UUID                     NOT NULL,
    occupancy   VARCHAR(10)              NOT NULL,
    guest_count INT,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_by UUID                     NOT NULL,
    CONSTRAINT housekeeping_observation_pk PRIMARY KEY (id),
    CONSTRAINT housekeeping_observation_room_fk FOREIGN KEY (room_id) REFERENCES rooms.room (id),
    CONSTRAINT housekeeping_observation_occupancy_ck CHECK (occupancy IN ('VACANT', 'OCCUPIED')),
    CONSTRAINT housekeeping_observation_guest_count_ck CHECK (
        guest_count IS NULL OR (occupancy = 'OCCUPIED' AND guest_count BETWEEN 1 AND 40))
);

CREATE INDEX housekeeping_observation_room_idx ON rooms.housekeeping_observation (room_id, observed_at, id);

-- O papel da aplicação já recebe DML nas tabelas novas do schema (ALTER DEFAULT PRIVILEGES da migração
-- de privilégios do rooms); o histórico e as observações ficam só com SELECT e INSERT (a partida confere,
-- pelo bean AppendOnlyTables do rooms).
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE rooms.room_status_change FROM "${app_role}";
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE rooms.housekeeping_observation FROM "${app_role}";
GRANT SELECT, INSERT ON TABLE rooms.room_status_change TO "${app_role}";
GRANT SELECT, INSERT ON TABLE rooms.housekeeping_observation TO "${app_role}";

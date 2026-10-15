-- UHs e áreas (SPEC §5.4 e §8.2.3, RF-UH-01 a 03): características, tipos de UH, UHs e áreas comuns.
-- Nada daqui é apagado: só desativado (o trigger no fim recusa a exclusão).

-- Características (vista, acessível, conjugada…), usadas pelos tipos de UH e pelas UHs. O código
-- fica em maiúsculas e é único. As três abaixo vêm pré-carregadas, com a chave que as identifica para
-- os outros módulos (decisão do usuário, M2 E01); o gestor as renomeia, desativa e cria outras (sem chave).
CREATE TABLE rooms.room_feature
(
    id         UUID                     NOT NULL,
    system_key VARCHAR(20),
    code       VARCHAR(20)              NOT NULL,
    name       VARCHAR(80)              NOT NULL,
    active     BOOLEAN                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT room_feature_pk PRIMARY KEY (id),
    CONSTRAINT room_feature_code_uk UNIQUE (code),
    CONSTRAINT room_feature_system_key_uk UNIQUE (system_key),
    CONSTRAINT room_feature_system_key_ck CHECK (system_key IS NULL OR system_key IN
        ('VIEW', 'ACCESSIBLE', 'CONNECTING')),
    CONSTRAINT room_feature_code_ck CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT room_feature_name_ck CHECK (length(btrim(name)) > 0)
);

INSERT INTO rooms.room_feature (id, system_key, code, name, active, created_at, updated_at, version)
VALUES ('019b76da-b000-7000-8000-000000000001', 'VIEW', 'VIEW', 'Vista', TRUE, now(), now(), 0),
       ('019b76da-b001-7001-8000-000000000002', 'ACCESSIBLE', 'ACCESSIBLE', 'Acessível', TRUE, now(), now(), 0),
       ('019b76da-b002-7002-8000-000000000003', 'CONNECTING', 'CONNECTING', 'Conjugada', TRUE, now(), now(), 0);

-- Tipos de UH (RF-UH-01): código único em maiúsculas, ocupação padrão e máxima de adultos e crianças
-- (a máxima nunca abaixo da padrão; ao menos um adulto), camas, características e ordem de exibição.
CREATE TABLE rooms.room_type
(
    id                UUID                     NOT NULL,
    code              VARCHAR(20)              NOT NULL,
    name              VARCHAR(80)              NOT NULL,
    description       VARCHAR(1000),
    standard_adults   INT                      NOT NULL,
    standard_children INT                      NOT NULL,
    max_adults        INT                      NOT NULL,
    max_children      INT                      NOT NULL,
    bed_setup         VARCHAR(200),
    display_order     INT                      NOT NULL,
    active            BOOLEAN                  NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT                   NOT NULL,
    CONSTRAINT room_type_pk PRIMARY KEY (id),
    CONSTRAINT room_type_code_uk UNIQUE (code),
    CONSTRAINT room_type_code_ck CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT room_type_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT room_type_occupancy_ck CHECK (standard_adults >= 1 AND standard_children >= 0
        AND max_adults >= standard_adults AND max_children >= standard_children
        AND max_adults <= 20 AND max_children <= 20),
    CONSTRAINT room_type_display_order_ck CHECK (display_order BETWEEN 0 AND 9999)
);

CREATE INDEX room_type_display_order_idx ON rooms.room_type (display_order, id);

CREATE TABLE rooms.room_type_feature_link
(
    room_type_id UUID NOT NULL,
    feature_id   UUID NOT NULL,
    CONSTRAINT room_type_feature_link_pk PRIMARY KEY (room_type_id, feature_id),
    CONSTRAINT room_type_feature_link_type_fk FOREIGN KEY (room_type_id) REFERENCES rooms.room_type (id),
    CONSTRAINT room_type_feature_link_feature_fk FOREIGN KEY (feature_id) REFERENCES rooms.room_feature (id)
);

-- UHs (RF-UH-02): número único (sem diferenciar maiúsculas), andar ou ala, tipo, características,
-- observações e ativa/inativa.
CREATE TABLE rooms.room
(
    id           UUID                     NOT NULL,
    number       VARCHAR(10)              NOT NULL,
    floor        VARCHAR(40),
    room_type_id UUID                     NOT NULL,
    notes        VARCHAR(1000),
    active       BOOLEAN                  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT room_pk PRIMARY KEY (id),
    CONSTRAINT room_room_type_fk FOREIGN KEY (room_type_id) REFERENCES rooms.room_type (id),
    CONSTRAINT room_number_ck CHECK (number ~ '^[0-9A-Za-z][0-9A-Za-z-]{0,9}$')
);

CREATE UNIQUE INDEX room_number_uk ON rooms.room (lower(number));
CREATE INDEX room_room_type_idx ON rooms.room (room_type_id) WHERE active;

CREATE TABLE rooms.room_feature_link
(
    room_id    UUID NOT NULL,
    feature_id UUID NOT NULL,
    CONSTRAINT room_feature_link_pk PRIMARY KEY (room_id, feature_id),
    CONSTRAINT room_feature_link_room_fk FOREIGN KEY (room_id) REFERENCES rooms.room (id),
    CONSTRAINT room_feature_link_feature_fk FOREIGN KEY (feature_id) REFERENCES rooms.room_feature (id)
);

-- Áreas comuns (RF-UH-03): local de checklists, tarefas e manutenção. Nome único (sem diferenciar
-- maiúsculas).
CREATE TABLE rooms.area
(
    id          UUID                     NOT NULL,
    name        VARCHAR(80)              NOT NULL,
    kind        VARCHAR(20)              NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN                  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT area_pk PRIMARY KEY (id),
    CONSTRAINT area_kind_ck CHECK (kind IN ('LOBBY', 'POOL', 'KITCHEN', 'HALL', 'CORRIDOR', 'OTHER')),
    CONSTRAINT area_name_ck CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX area_name_uk ON rooms.area (lower(name));

-- Características, tipos de UH, UHs e áreas nunca são apagados (RF-UH-01 a 03): só desativados. Os
-- vínculos com características (room_type_feature_link, room_feature_link) saem quando a edição os retira.
CREATE FUNCTION rooms.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de rooms.% nunca são apagados, só desativados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER room_feature_never_deleted
    BEFORE DELETE
    ON rooms.room_feature
    FOR EACH ROW
EXECUTE FUNCTION rooms.refuse_delete();

CREATE TRIGGER room_type_never_deleted
    BEFORE DELETE
    ON rooms.room_type
    FOR EACH ROW
EXECUTE FUNCTION rooms.refuse_delete();

CREATE TRIGGER room_never_deleted
    BEFORE DELETE
    ON rooms.room
    FOR EACH ROW
EXECUTE FUNCTION rooms.refuse_delete();

CREATE TRIGGER area_never_deleted
    BEFORE DELETE
    ON rooms.area
    FOR EACH ROW
EXECUTE FUNCTION rooms.refuse_delete();

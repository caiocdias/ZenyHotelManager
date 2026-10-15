-- Pessoas e escala (SPEC §5.3 e §8.2.2, RF-PES-01 a 04, H6, H7): setores, colaboradores, turnos,
-- padrões de escala e as entradas da escala por colaborador e data.

-- Setores (H6). Os seis da SPEC §5.3 vêm abaixo, com a chave que os identifica para os outros
-- módulos; o administrador os renomeia e cria outros (sem chave).
CREATE TABLE staff.department
(
    id         UUID                     NOT NULL,
    system_key VARCHAR(30),
    name       VARCHAR(80)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT department_pk PRIMARY KEY (id),
    CONSTRAINT department_system_key_uk UNIQUE (system_key),
    CONSTRAINT department_system_key_ck CHECK (system_key IS NULL OR system_key IN
        ('RECEPTION', 'HOUSEKEEPING', 'MAINTENANCE', 'FOOD_AND_BEVERAGE', 'EVENTS', 'ADMINISTRATION')),
    CONSTRAINT department_name_ck CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX department_name_uk ON staff.department (lower(name));

INSERT INTO staff.department (id, system_key, name, created_at, updated_at, version)
VALUES ('019b76da-a800-7000-8000-000000000001', 'RECEPTION', 'Recepção', now(), now(), 0),
       ('019b76da-a801-7001-8000-000000000002', 'HOUSEKEEPING', 'Governança', now(), now(), 0),
       ('019b76da-a802-7002-8000-000000000003', 'MAINTENANCE', 'Manutenção', now(), now(), 0),
       ('019b76da-a803-7003-8000-000000000004', 'FOOD_AND_BEVERAGE', 'A&B', now(), now(), 0),
       ('019b76da-a804-7004-8000-000000000005', 'EVENTS', 'Eventos', now(), now(), 0),
       ('019b76da-a805-7005-8000-000000000006', 'ADMINISTRATION', 'Administração', now(), now(), 0);

-- Colaboradores (RF-PES-01): o vínculo com um usuário do iam (H7, sem FK entre schemas) é único, e
-- nenhum colaborador é apagado (RF-ACS-12): só desativado.
CREATE TABLE staff.employee
(
    id            UUID                     NOT NULL,
    department_id UUID                     NOT NULL,
    name          VARCHAR(120)             NOT NULL,
    position      VARCHAR(80)              NOT NULL,
    phone         VARCHAR(30),
    email         VARCHAR(254),
    user_id       UUID,
    active        BOOLEAN                  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT employee_pk PRIMARY KEY (id),
    CONSTRAINT employee_department_fk FOREIGN KEY (department_id) REFERENCES staff.department (id),
    CONSTRAINT employee_user_uk UNIQUE (user_id),
    CONSTRAINT employee_name_ck CHECK (length(btrim(name)) > 0 AND length(btrim(position)) > 0)
);

CREATE INDEX employee_department_idx ON staff.employee (department_id);
CREATE INDEX employee_name_idx ON staff.employee (lower(name), id);

-- Turnos por setor (RF-PES-02), em horas e minutos no fuso do hotel. Fim antes do início: o turno
-- atravessa a meia-noite (a entrada da escala é da data em que ele começa).
CREATE TABLE staff.shift
(
    id            UUID                     NOT NULL,
    department_id UUID                     NOT NULL,
    name          VARCHAR(60)              NOT NULL,
    start_time    TIME                     NOT NULL,
    end_time      TIME                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT shift_pk PRIMARY KEY (id),
    CONSTRAINT shift_department_fk FOREIGN KEY (department_id) REFERENCES staff.department (id),
    CONSTRAINT shift_times_ck CHECK (start_time <> end_time
        AND date_trunc('minute', start_time) = start_time AND date_trunc('minute', end_time) = end_time),
    CONSTRAINT shift_name_ck CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX shift_name_uk ON staff.shift (department_id, lower(name));

-- Padrões de escala (RF-PES-03): 12x36, 6x1 ou fixo (dias da semana num bitmask, segunda = 1). Dois
-- padrões ativos do mesmo colaborador nunca se sobrepõem.
CREATE TABLE staff.schedule_pattern
(
    id           UUID                     NOT NULL,
    employee_id  UUID                     NOT NULL,
    shift_id     UUID                     NOT NULL,
    kind         VARCHAR(20)              NOT NULL,
    starts_on    DATE                     NOT NULL,
    ends_on      DATE,
    weekday_mask INT                      NOT NULL,
    active       BOOLEAN                  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT schedule_pattern_pk PRIMARY KEY (id),
    CONSTRAINT schedule_pattern_employee_fk FOREIGN KEY (employee_id) REFERENCES staff.employee (id),
    CONSTRAINT schedule_pattern_shift_fk FOREIGN KEY (shift_id) REFERENCES staff.shift (id),
    CONSTRAINT schedule_pattern_kind_ck CHECK (kind IN ('TWELVE_THIRTY_SIX', 'SIX_ON_ONE_OFF', 'FIXED')),
    CONSTRAINT schedule_pattern_period_ck CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT schedule_pattern_weekdays_ck CHECK (weekday_mask BETWEEN 0 AND 127
        AND (kind = 'FIXED') = (weekday_mask > 0)),
    CONSTRAINT schedule_pattern_overlap_ex EXCLUDE USING gist (
        employee_id WITH =,
        daterange(starts_on, ends_on, '[]') WITH &&
        ) WHERE (active)
);

CREATE INDEX schedule_pattern_employee_idx ON staff.schedule_pattern (employee_id);

-- Escala (RF-PES-03): uma entrada por colaborador e data. As do padrão (source PATTERN) a geração
-- refaz; as lançadas à mão (MANUAL: atribuição, troca, folga, falta, férias) prevalecem. Trabalho e
-- troca têm turno; folga, falta e férias não. O setor é o do turno (ou o do colaborador, na ausência).
CREATE TABLE staff.schedule_entry
(
    id            UUID                     NOT NULL,
    employee_id   UUID                     NOT NULL,
    work_date     DATE                     NOT NULL,
    kind          VARCHAR(20)              NOT NULL,
    source        VARCHAR(10)              NOT NULL,
    shift_id      UUID,
    department_id UUID                     NOT NULL,
    pattern_id    UUID,
    note          VARCHAR(200),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT schedule_entry_pk PRIMARY KEY (id),
    CONSTRAINT schedule_entry_day_uk UNIQUE (employee_id, work_date),
    CONSTRAINT schedule_entry_employee_fk FOREIGN KEY (employee_id) REFERENCES staff.employee (id),
    CONSTRAINT schedule_entry_shift_fk FOREIGN KEY (shift_id) REFERENCES staff.shift (id),
    CONSTRAINT schedule_entry_department_fk FOREIGN KEY (department_id) REFERENCES staff.department (id),
    CONSTRAINT schedule_entry_pattern_fk FOREIGN KEY (pattern_id) REFERENCES staff.schedule_pattern (id),
    CONSTRAINT schedule_entry_kind_ck CHECK (kind IN ('SHIFT', 'SWAP', 'DAY_OFF', 'ABSENCE', 'VACATION')),
    CONSTRAINT schedule_entry_source_ck CHECK (source IN ('PATTERN', 'MANUAL')),
    CONSTRAINT schedule_entry_shift_ck CHECK ((kind IN ('SHIFT', 'SWAP')) = (shift_id IS NOT NULL)),
    CONSTRAINT schedule_entry_pattern_ck CHECK ((source = 'PATTERN') = (pattern_id IS NOT NULL)
        AND (source = 'MANUAL' OR kind = 'SHIFT'))
);

CREATE INDEX schedule_entry_day_idx ON staff.schedule_entry (work_date, department_id);

-- Setores e colaboradores nunca são apagados (RF-ACS-12, H6): só renomeados ou desativados.
CREATE FUNCTION staff.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de staff.% nunca são apagados, só desativados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER department_never_deleted
    BEFORE DELETE
    ON staff.department
    FOR EACH ROW
EXECUTE FUNCTION staff.refuse_delete();

CREATE TRIGGER employee_never_deleted
    BEFORE DELETE
    ON staff.employee
    FOR EACH ROW
EXECUTE FUNCTION staff.refuse_delete();

-- Tipos de limpeza da governança (M6 E01, RF-GOV-01, SPEC §8.2.10): código único em maiúsculas, nome, se exige
-- inspeção, prioridade padrão, regra de troca de enxoval, o modelo de checklist opcional e os créditos por tipo de UH.
-- Os ids de tipo de UH (rooms) e de modelo de checklist (checklists) são opacos: conferidos pela API pública dos
-- módulos donos, sem FK entre schemas. O tipo nunca é apagado, só desativado (o trigger no fim recusa a exclusão); os
-- créditos são substituídos pela edição.

CREATE TABLE housekeeping.cleaning_type
(
    id                        UUID                     NOT NULL,
    -- Chave estável dos tipos da carga inicial (D5), que nunca muda; nula nos criados pela API.
    system_key                VARCHAR(20),
    code                      VARCHAR(20)              NOT NULL,
    name                      VARCHAR(80)              NOT NULL,
    requires_inspection       BOOLEAN                  NOT NULL,
    default_priority          INT                      NOT NULL,
    -- Troca de enxoval a cada N noites; nula = sem regra por noites (troca só a pedido).
    linen_change_every_nights INT,
    checklist_template_id     UUID,
    active                    BOOLEAN                  NOT NULL,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    version                   BIGINT                   NOT NULL,
    CONSTRAINT cleaning_type_pk PRIMARY KEY (id),
    CONSTRAINT cleaning_type_code_uk UNIQUE (code),
    CONSTRAINT cleaning_type_code_ck CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT cleaning_type_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT cleaning_type_system_key_ck CHECK (system_key IS NULL OR system_key IN
        ('CHECKOUT', 'STAYOVER', 'TOUCH_UP', 'DEEP_CLEAN', 'PRE_ARRIVAL', 'TURNDOWN')),
    CONSTRAINT cleaning_type_default_priority_ck CHECK (default_priority BETWEEN 1 AND 5),
    CONSTRAINT cleaning_type_linen_change_ck CHECK (linen_change_every_nights IS NULL
        OR linen_change_every_nights BETWEEN 1 AND 60)
);

-- A E02 acha o tipo da carga pela chave.
CREATE UNIQUE INDEX cleaning_type_system_key_uk ON housekeeping.cleaning_type (system_key)
    WHERE system_key IS NOT NULL;

CREATE TABLE housekeeping.cleaning_type_credit
(
    cleaning_type_id UUID NOT NULL,
    room_type_id     UUID NOT NULL,
    credits          INT  NOT NULL,
    CONSTRAINT cleaning_type_credit_pk PRIMARY KEY (cleaning_type_id, room_type_id),
    CONSTRAINT cleaning_type_credit_type_fk FOREIGN KEY (cleaning_type_id) REFERENCES housekeeping.cleaning_type (id),
    CONSTRAINT cleaning_type_credit_credits_ck CHECK (credits BETWEEN 1 AND 100)
);

CREATE INDEX cleaning_type_credit_room_type_idx ON housekeeping.cleaning_type_credit (room_type_id);

-- Carga inicial (D5): os seis tipos da SPEC, com a chave estável; a abertura de cama vem desativada (H9, Q-14).
INSERT INTO housekeeping.cleaning_type (id, system_key, code, name, requires_inspection, default_priority,
                                        linen_change_every_nights, checklist_template_id, active, created_at,
                                        updated_at, version)
VALUES ('01a1e8f2-f900-7000-8000-000000000001', 'CHECKOUT', 'CHECKOUT', 'Saída', TRUE, 4, NULL, NULL, TRUE, now(),
        now(), 0),
       ('01a1e8f2-f901-7001-8000-000000000002', 'STAYOVER', 'STAYOVER', 'Permanência', FALSE, 3, 3, NULL, TRUE, now(),
        now(), 0),
       ('01a1e8f2-f902-7002-8000-000000000003', 'TOUCH_UP', 'TOUCH_UP', 'Retoque', FALSE, 2, NULL, NULL, TRUE, now(),
        now(), 0),
       ('01a1e8f2-f903-7003-8000-000000000004', 'DEEP_CLEAN', 'DEEP_CLEAN', 'Faxina pesada', TRUE, 1, NULL, NULL, TRUE,
        now(), now(), 0),
       ('01a1e8f2-f904-7004-8000-000000000005', 'PRE_ARRIVAL', 'PRE_ARRIVAL', 'Pré-chegada', TRUE, 4, NULL, NULL, TRUE,
        now(), now(), 0),
       ('01a1e8f2-f905-7005-8000-000000000006', 'TURNDOWN', 'TURNDOWN', 'Abertura de cama', FALSE, 2, NULL, NULL, FALSE,
        now(), now(), 0);

-- Nada é apagado: o tipo de limpeza só é desativado (os créditos saem quando a edição os retira).
CREATE FUNCTION housekeeping.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de housekeeping.% nunca são apagados, só desativados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER cleaning_type_never_deleted
    BEFORE DELETE
    ON housekeeping.cleaning_type
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

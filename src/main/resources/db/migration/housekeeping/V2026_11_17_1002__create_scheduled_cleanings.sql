-- Faxinas programadas da governança (M6 E01, RF-GOV-10, SPEC §8.2.10): um tipo de limpeza repetido a cada N dias numa
-- UH ou numa área comum (exatamente um local), com a última e a próxima data (D7). Os ids de UH e de área (rooms) são
-- opacos: conferidos pela RoomsApi, sem FK entre schemas; o tipo de limpeza é do próprio schema, com FK. A faxina nunca
-- é apagada, só desativada (o trigger no fim recusa a exclusão).

CREATE TABLE housekeeping.scheduled_cleaning
(
    id               UUID                     NOT NULL,
    room_id          UUID,
    area_id          UUID,
    cleaning_type_id UUID                     NOT NULL,
    frequency_days   INT                      NOT NULL,
    last_done_on     DATE,
    next_due_on      DATE                     NOT NULL,
    active           BOOLEAN                  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT                   NOT NULL,
    CONSTRAINT scheduled_cleaning_pk PRIMARY KEY (id),
    CONSTRAINT scheduled_cleaning_type_fk FOREIGN KEY (cleaning_type_id) REFERENCES housekeeping.cleaning_type (id),
    CONSTRAINT scheduled_cleaning_location_ck CHECK (num_nonnulls(room_id, area_id) = 1),
    CONSTRAINT scheduled_cleaning_frequency_days_ck CHECK (frequency_days BETWEEN 1 AND 365)
);

-- No máximo uma faxina ativa por (local, tipo); a desativada não conta.
CREATE UNIQUE INDEX scheduled_cleaning_active_uk
    ON housekeeping.scheduled_cleaning (room_id, area_id, cleaning_type_id) NULLS NOT DISTINCT
    WHERE active;

-- A listagem por (próxima data, id) e a busca das faxinas ativas de um tipo (desativação do tipo).
CREATE INDEX scheduled_cleaning_next_due_idx ON housekeeping.scheduled_cleaning (next_due_on, id);
CREATE INDEX scheduled_cleaning_type_idx ON housekeeping.scheduled_cleaning (cleaning_type_id);

CREATE TRIGGER scheduled_cleaning_never_deleted
    BEFORE DELETE
    ON housekeeping.scheduled_cleaning
    FOR EACH ROW
EXECUTE FUNCTION housekeeping.refuse_delete();

-- A chave estável do tipo de limpeza (D5) nunca muda, nem por SQL direto (o mapeamento já não a atualiza).
CREATE FUNCTION housekeeping.refuse_system_key_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'A chave estável de housekeeping.cleaning_type nunca muda'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER cleaning_type_system_key_never_changed
    BEFORE UPDATE OF system_key
    ON housekeeping.cleaning_type
    FOR EACH ROW
    WHEN (NEW.system_key IS DISTINCT FROM OLD.system_key)
EXECUTE FUNCTION housekeeping.refuse_system_key_change();

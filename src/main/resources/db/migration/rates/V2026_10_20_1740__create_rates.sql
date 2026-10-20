-- Tarifas (SPEC §5.6 e §8.2.5, RF-TAR-01, 06 e 07; M4 E01): políticas de cancelamento e de garantia, pacotes e
-- planos tarifários. Nada daqui é apagado: só desativado (o trigger no fim recusa a exclusão). Os ids de tipo de UH
-- (rooms) e de código de lançamento (billing) são opacos: conferidos pela API pública dos módulos donos, sem FK entre
-- schemas.

-- Política de cancelamento (RF-TAR-07): prazo em horas antes da chegada (até o horário de check-in da data de
-- chegada, no fuso do hotel) e a multa, num só dos três valores conforme o tipo. Não versionada (decisão do usuário
-- em 2026-10-20): a reserva copia os termos (RN-15, E07).
CREATE TABLE rates.cancellation_policy
(
    id              UUID                     NOT NULL,
    name            VARCHAR(80)              NOT NULL,
    deadline_hours  INT                      NOT NULL,
    penalty_type    VARCHAR(20)              NOT NULL,
    penalty_percent NUMERIC(7, 4),
    penalty_nights  INT,
    penalty_amount  NUMERIC(14, 2),
    active          BOOLEAN                  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT cancellation_policy_pk PRIMARY KEY (id),
    CONSTRAINT cancellation_policy_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT cancellation_policy_deadline_ck CHECK (deadline_hours BETWEEN 0 AND 8760),
    CONSTRAINT cancellation_policy_penalty_ck CHECK (
        (penalty_type = 'PERCENT_OF_STAY' AND penalty_percent IS NOT NULL AND penalty_percent BETWEEN 0 AND 100
            AND penalty_nights IS NULL AND penalty_amount IS NULL)
            OR (penalty_type = 'NIGHTS' AND penalty_nights IS NOT NULL AND penalty_nights BETWEEN 0 AND 365
            AND penalty_percent IS NULL AND penalty_amount IS NULL)
            OR (penalty_type = 'FIXED_AMOUNT' AND penalty_amount IS NOT NULL AND penalty_amount BETWEEN 0 AND 1000000
            AND penalty_percent IS NULL AND penalty_nights IS NULL)
        )
);

CREATE UNIQUE INDEX cancellation_policy_name_uk ON rates.cancellation_policy (lower(name));

-- Política de garantia (RF-TAR-07): nenhuma, cartão ou depósito de X% da estadia até N dias antes da chegada (os dois
-- só no depósito). Não versionada, como a de cancelamento.
CREATE TABLE rates.guarantee_policy
(
    id              UUID                     NOT NULL,
    name            VARCHAR(80)              NOT NULL,
    guarantee_type  VARCHAR(20)              NOT NULL,
    deposit_percent NUMERIC(7, 4),
    due_days_before INT,
    active          BOOLEAN                  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT guarantee_policy_pk PRIMARY KEY (id),
    CONSTRAINT guarantee_policy_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT guarantee_policy_type_ck CHECK (
        (guarantee_type IN ('NONE', 'CARD') AND deposit_percent IS NULL AND due_days_before IS NULL)
            OR (guarantee_type = 'DEPOSIT' AND deposit_percent IS NOT NULL AND deposit_percent > 0 AND deposit_percent <= 100
            AND due_days_before IS NOT NULL AND due_days_before BETWEEN 0 AND 365)
        )
);

CREATE UNIQUE INDEX guarantee_policy_name_uk ON rates.guarantee_policy (lower(name));

-- Pacote (RF-TAR-06): preço por noite, por estadia ou por pessoa/noite, ritmo de lançamento (diário ou na chegada; o
-- preço por estadia só na chegada), incluso ou adicional e o código de lançamento do billing (obrigatório, opaco).
CREATE TABLE rates.rate_package
(
    id                  UUID                     NOT NULL,
    code                VARCHAR(20)              NOT NULL,
    name                VARCHAR(80)              NOT NULL,
    price               NUMERIC(14, 2)           NOT NULL,
    pricing             VARCHAR(30)              NOT NULL,
    posting_rhythm      VARCHAR(20)              NOT NULL,
    included_in_rate    BOOLEAN                  NOT NULL,
    transaction_code_id UUID                     NOT NULL,
    active              BOOLEAN                  NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT                   NOT NULL,
    CONSTRAINT rate_package_pk PRIMARY KEY (id),
    CONSTRAINT rate_package_code_uk UNIQUE (code),
    CONSTRAINT rate_package_code_ck CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT rate_package_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT rate_package_price_ck CHECK (price BETWEEN 0.01 AND 1000000),
    CONSTRAINT rate_package_pricing_ck CHECK (pricing IN ('PER_NIGHT', 'PER_STAY', 'PER_PERSON_PER_NIGHT')),
    CONSTRAINT rate_package_rhythm_ck CHECK (posting_rhythm IN ('DAILY', 'ON_ARRIVAL')),
    CONSTRAINT rate_package_per_stay_ck CHECK (pricing <> 'PER_STAY' OR posting_rhythm = 'ON_ARRIVAL')
);

-- Plano tarifário (RF-TAR-01): código único em maiúsculas, regime de alimentação, categoria (fixa; Q-15), as duas
-- políticas e, nas tabelas de vínculo, os canais, os tipos de UH e os pacotes.
CREATE TABLE rates.rate_plan
(
    id                     UUID                     NOT NULL,
    code                   VARCHAR(20)              NOT NULL,
    name                   VARCHAR(80)              NOT NULL,
    description            VARCHAR(1000),
    meal_plan              VARCHAR(20)              NOT NULL,
    category               VARCHAR(20)              NOT NULL,
    cancellation_policy_id UUID                     NOT NULL,
    guarantee_policy_id    UUID                     NOT NULL,
    active                 BOOLEAN                  NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT rate_plan_pk PRIMARY KEY (id),
    CONSTRAINT rate_plan_code_uk UNIQUE (code),
    CONSTRAINT rate_plan_code_ck CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT rate_plan_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT rate_plan_meal_plan_ck CHECK (meal_plan IN ('ROOM_ONLY', 'BREAKFAST', 'HALF_BOARD')),
    CONSTRAINT rate_plan_category_ck CHECK (category IN ('NORMAL', 'COURTESY', 'HOUSE_USE')),
    CONSTRAINT rate_plan_cancellation_policy_fk FOREIGN KEY (cancellation_policy_id)
        REFERENCES rates.cancellation_policy (id),
    CONSTRAINT rate_plan_guarantee_policy_fk FOREIGN KEY (guarantee_policy_id)
        REFERENCES rates.guarantee_policy (id)
);

CREATE INDEX rate_plan_cancellation_policy_idx ON rates.rate_plan (cancellation_policy_id);
CREATE INDEX rate_plan_guarantee_policy_idx ON rates.rate_plan (guarantee_policy_id);

CREATE TABLE rates.rate_plan_channel
(
    rate_plan_id UUID        NOT NULL,
    channel      VARCHAR(20) NOT NULL,
    CONSTRAINT rate_plan_channel_pk PRIMARY KEY (rate_plan_id, channel),
    CONSTRAINT rate_plan_channel_plan_fk FOREIGN KEY (rate_plan_id) REFERENCES rates.rate_plan (id),
    CONSTRAINT rate_plan_channel_ck CHECK (channel IN ('DIRECT', 'WEBSITE', 'OTA'))
);

CREATE TABLE rates.rate_plan_room_type
(
    rate_plan_id UUID NOT NULL,
    room_type_id UUID NOT NULL,
    CONSTRAINT rate_plan_room_type_pk PRIMARY KEY (rate_plan_id, room_type_id),
    CONSTRAINT rate_plan_room_type_plan_fk FOREIGN KEY (rate_plan_id) REFERENCES rates.rate_plan (id)
);

CREATE INDEX rate_plan_room_type_room_type_idx ON rates.rate_plan_room_type (room_type_id);

CREATE TABLE rates.rate_plan_package
(
    rate_plan_id UUID NOT NULL,
    package_id   UUID NOT NULL,
    CONSTRAINT rate_plan_package_pk PRIMARY KEY (rate_plan_id, package_id),
    CONSTRAINT rate_plan_package_plan_fk FOREIGN KEY (rate_plan_id) REFERENCES rates.rate_plan (id),
    CONSTRAINT rate_plan_package_package_fk FOREIGN KEY (package_id) REFERENCES rates.rate_package (id)
);

CREATE INDEX rate_plan_package_package_idx ON rates.rate_plan_package (package_id);

-- Nada é apagado: planos, políticas e pacotes só são desativados (os vínculos do plano saem quando a edição os
-- retira).
CREATE FUNCTION rates.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de rates.% nunca são apagados, só desativados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER cancellation_policy_never_deleted
    BEFORE DELETE
    ON rates.cancellation_policy
    FOR EACH ROW
EXECUTE FUNCTION rates.refuse_delete();

CREATE TRIGGER guarantee_policy_never_deleted
    BEFORE DELETE
    ON rates.guarantee_policy
    FOR EACH ROW
EXECUTE FUNCTION rates.refuse_delete();

CREATE TRIGGER rate_package_never_deleted
    BEFORE DELETE
    ON rates.rate_package
    FOR EACH ROW
EXECUTE FUNCTION rates.refuse_delete();

CREATE TRIGGER rate_plan_never_deleted
    BEFORE DELETE
    ON rates.rate_plan
    FOR EACH ROW
EXECUTE FUNCTION rates.refuse_delete();

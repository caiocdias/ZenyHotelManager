-- Configuração do hotel único e data operacional (SPEC §5.2 e §8.2.2, RF-CFG-01 a 05, RN-01,
-- RN-11, RNF-14). O hotel e as partes da configuração dele são criados no primeiro boot
-- (HotelInitializer), não aqui: cada tabela tem no máximo uma linha.

-- O hotel: dados cadastrais (nulos até o administrador os informar, todos juntos), endereço com
-- código IBGE (colunas do Address do shared), fuso de região, regime tributário e o id do arquivo
-- do logotipo no módulo files (H8; sem FK entre schemas).
CREATE TABLE hotel.hotel
(
    id                     UUID                     NOT NULL,
    singleton              BOOLEAN                  NOT NULL DEFAULT TRUE,
    legal_name             VARCHAR(200),
    trade_name             VARCHAR(200),
    cnpj                   VARCHAR(14),
    municipal_registration VARCHAR(30),
    cadastur               VARCHAR(30),
    street                 VARCHAR(200),
    number                 VARCHAR(20),
    complement             VARCHAR(100),
    district               VARCHAR(100),
    city                   VARCHAR(100),
    state                  VARCHAR(2),
    postal_code            VARCHAR(8),
    ibge_city_code         VARCHAR(7),
    time_zone_id           VARCHAR(64)              NOT NULL,
    tax_regime             VARCHAR(30),
    logo_file_id           UUID,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT hotel_pk PRIMARY KEY (id),
    -- Um hotel só.
    CONSTRAINT hotel_singleton_ck CHECK (singleton),
    CONSTRAINT hotel_singleton_uk UNIQUE (singleton),
    CONSTRAINT hotel_cnpj_ck CHECK (cnpj IS NULL OR cnpj ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    CONSTRAINT hotel_ibge_ck CHECK (ibge_city_code IS NULL OR ibge_city_code ~ '^[0-9]{7}$'),
    -- Ou nenhum dado cadastral, ou todos os obrigatórios.
    CONSTRAINT hotel_profile_ck CHECK (
        (legal_name IS NULL AND trade_name IS NULL AND cnpj IS NULL AND street IS NULL AND tax_regime IS NULL)
        OR (legal_name IS NOT NULL AND trade_name IS NOT NULL AND cnpj IS NOT NULL AND street IS NOT NULL
            AND number IS NOT NULL AND district IS NOT NULL AND city IS NOT NULL AND state IS NOT NULL
            AND postal_code IS NOT NULL AND ibge_city_code IS NOT NULL AND tax_regime IS NOT NULL)
    )
);

-- Políticas operacionais (RF-CFG-02, RN-11): check-out antes do check-in, no mesmo dia, com no
-- máximo 3 horas entre eles; a janela de arrumação cabe nesse intervalo.
CREATE TABLE hotel.policy
(
    hotel_id                UUID                     NOT NULL,
    check_in_time           TIME                     NOT NULL,
    check_out_time          TIME                     NOT NULL,
    cleaning_window_minutes INT                      NOT NULL,
    early_check_in_limit    TIME,
    early_check_in_fee      NUMERIC(14, 2),
    late_check_out_limit    TIME,
    late_check_out_fee      NUMERIC(14, 2),
    child_max_age           INT                      NOT NULL,
    stay_policy_text        TEXT                     NOT NULL,
    additional_policy_text  TEXT,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    version                 BIGINT                   NOT NULL,
    CONSTRAINT policy_pk PRIMARY KEY (hotel_id),
    CONSTRAINT policy_hotel_fk FOREIGN KEY (hotel_id) REFERENCES hotel.hotel (id),
    CONSTRAINT policy_check_times_ck CHECK (
        check_out_time < check_in_time AND check_in_time - check_out_time <= INTERVAL '3 hours'
    ),
    CONSTRAINT policy_cleaning_window_ck CHECK (
        cleaning_window_minutes > 0 AND cleaning_window_minutes <= 180
        AND make_interval(mins => cleaning_window_minutes) <= check_in_time - check_out_time
    ),
    CONSTRAINT policy_early_ck CHECK ((early_check_in_limit IS NULL) = (early_check_in_fee IS NULL)
        AND (early_check_in_fee IS NULL OR early_check_in_fee >= 0)),
    CONSTRAINT policy_late_ck CHECK ((late_check_out_limit IS NULL) = (late_check_out_fee IS NULL)
        AND (late_check_out_fee IS NULL OR late_check_out_fee >= 0)),
    CONSTRAINT policy_child_max_age_ck CHECK (child_max_age BETWEEN 0 AND 17)
);

-- Faixas etárias de criança, na ordem da política.
CREATE TABLE hotel.child_age_band
(
    hotel_id UUID        NOT NULL,
    sort_order INT       NOT NULL,
    min_age  INT         NOT NULL,
    max_age  INT         NOT NULL,
    label    VARCHAR(50) NOT NULL,
    CONSTRAINT child_age_band_pk PRIMARY KEY (hotel_id, sort_order),
    CONSTRAINT child_age_band_policy_fk FOREIGN KEY (hotel_id) REFERENCES hotel.policy (hotel_id),
    CONSTRAINT child_age_band_ages_ck CHECK (min_age >= 0 AND max_age >= min_age AND max_age <= 17)
);

-- Taxas (RF-CFG-03): percentuais com 4 casas (Percent), taxa de turismo em dinheiro (Money),
-- nula quando não cobrada (Q-03).
CREATE TABLE hotel.tax_settings
(
    hotel_id                     UUID                     NOT NULL,
    service_charge_rate          NUMERIC(7, 4)            NOT NULL,
    iss_rate                     NUMERIC(7, 4)            NOT NULL,
    tourism_fee_per_person_night NUMERIC(14, 2),
    updated_at                   TIMESTAMP WITH TIME ZONE NOT NULL,
    version                      BIGINT                   NOT NULL,
    CONSTRAINT tax_settings_pk PRIMARY KEY (hotel_id),
    CONSTRAINT tax_settings_hotel_fk FOREIGN KEY (hotel_id) REFERENCES hotel.hotel (id),
    CONSTRAINT tax_settings_service_charge_ck CHECK (service_charge_rate BETWEEN 0 AND 100),
    CONSTRAINT tax_settings_iss_ck CHECK (iss_rate BETWEEN 0 AND 5),
    CONSTRAINT tax_settings_tourism_fee_ck CHECK (tourism_fee_per_person_night IS NULL OR tourism_fee_per_person_night > 0)
);

-- Parâmetros operacionais (RF-CFG-04).
CREATE TABLE hotel.operational_settings
(
    hotel_id                         UUID                     NOT NULL,
    end_of_day_time                  TIME                     NOT NULL,
    pre_reservation_expiry_hours     INT                      NOT NULL,
    pre_reservation_alert_lead_hours INT                      NOT NULL,
    lost_and_found_retention_days    INT                      NOT NULL,
    updated_at                       TIMESTAMP WITH TIME ZONE NOT NULL,
    version                          BIGINT                   NOT NULL,
    CONSTRAINT operational_settings_pk PRIMARY KEY (hotel_id),
    CONSTRAINT operational_settings_hotel_fk FOREIGN KEY (hotel_id) REFERENCES hotel.hotel (id),
    CONSTRAINT operational_settings_pre_reservation_ck CHECK (
        pre_reservation_expiry_hours BETWEEN 1 AND 720
        AND pre_reservation_alert_lead_hours >= 0
        AND pre_reservation_alert_lead_hours < pre_reservation_expiry_hours
    ),
    CONSTRAINT operational_settings_retention_ck CHECK (lost_and_found_retention_days BETWEEN 1 AND 1825)
);

-- Data operacional (RN-01): a data de negócio corrente. Só avança no fechamento do dia, pela API
-- pública do módulo (HotelApi.advanceBusinessDate), exatamente um dia.
CREATE TABLE hotel.business_day
(
    hotel_id      UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    advanced_at   TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT                   NOT NULL,
    CONSTRAINT business_day_pk PRIMARY KEY (hotel_id),
    CONSTRAINT business_day_hotel_fk FOREIGN KEY (hotel_id) REFERENCES hotel.hotel (id)
);

-- Defesa no banco: a data operacional nunca volta nem pula; muda só para o dia seguinte. Vale
-- também para SQL manual com o papel da aplicação.
CREATE FUNCTION hotel.business_day_advances_one_day() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.business_date <> OLD.business_date AND NEW.business_date <> OLD.business_date + 1 THEN
        RAISE EXCEPTION 'A data operacional só avança um dia: de % para % não é permitido',
            OLD.business_date, NEW.business_date
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER business_day_advances_one_day
    BEFORE UPDATE OF business_date
    ON hotel.business_day
    FOR EACH ROW
EXECUTE FUNCTION hotel.business_day_advances_one_day();

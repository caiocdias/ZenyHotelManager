-- Preços por data e ocupação, planos derivados e temporadas (SPEC §5.6 e §8.2.5, RF-TAR-02 a 04; M4 E02, H1, H2 e H9
-- do roadmap do M4). Os ids de tipo de UH (rooms) são opacos: conferidos pela RoomsApi e pelo vínculo do plano, sem FK
-- entre schemas. As tabelas novas recebem DML do papel da aplicação pelo ALTER DEFAULT PRIVILEGES da migração de
-- privilégios do schema.

-- Derivação (RF-TAR-03, H1): o plano derivado guarda só a regra; os preços dele são os do plano base ajustados,
-- calculados na leitura, nunca gravados. PERCENT: percentual acima de -100 sobre cada valor; AMOUNT: valor somado (por
-- UH ou por pessoa). A aplicação recusa ciclo (também entre transações, por uma trava consultiva) e mais de 5 níveis.
ALTER TABLE rates.rate_plan
    ADD COLUMN base_rate_plan_id     UUID,
    ADD COLUMN derivation_type       VARCHAR(10),
    ADD COLUMN derivation_percent    NUMERIC(7, 4),
    ADD COLUMN derivation_amount     NUMERIC(14, 2),
    ADD COLUMN derivation_per_person BOOLEAN;

ALTER TABLE rates.rate_plan
    ADD CONSTRAINT rate_plan_base_fk FOREIGN KEY (base_rate_plan_id) REFERENCES rates.rate_plan (id),
    ADD CONSTRAINT rate_plan_derivation_ck CHECK (
        (base_rate_plan_id IS NULL AND derivation_type IS NULL AND derivation_percent IS NULL
            AND derivation_amount IS NULL AND derivation_per_person IS NULL)
            OR (base_rate_plan_id IS NOT NULL AND base_rate_plan_id <> id AND derivation_type = 'PERCENT'
            AND derivation_percent IS NOT NULL AND derivation_percent > -100
            AND derivation_amount IS NULL AND derivation_per_person IS NULL)
            OR (base_rate_plan_id IS NOT NULL AND base_rate_plan_id <> id AND derivation_type = 'AMOUNT'
            AND derivation_amount IS NOT NULL AND derivation_amount BETWEEN -1000000 AND 1000000
            AND derivation_percent IS NULL AND derivation_per_person IS NOT NULL)
        );

CREATE INDEX rate_plan_base_idx ON rates.rate_plan (base_rate_plan_id);

-- Preço de um tipo de UH numa data (RF-TAR-02): diária de 1 adulto (obrigatória), de 2 adultos e por adulto extra
-- (nulos = a ocupação não tem preço). Só nos planos com preços próprios. A linha retirada sai de verdade (DELETE,
-- auditado pela aplicação); alterar ou retirar uma tarifa publicada é ação sensível (H9), conferida pela aplicação.
CREATE TABLE rates.rate_price
(
    rate_plan_id UUID                     NOT NULL,
    room_type_id UUID                     NOT NULL,
    stay_date    DATE                     NOT NULL,
    one_adult    NUMERIC(14, 2)           NOT NULL,
    two_adults   NUMERIC(14, 2),
    extra_adult  NUMERIC(14, 2),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT rate_price_pk PRIMARY KEY (rate_plan_id, room_type_id, stay_date),
    CONSTRAINT rate_price_plan_fk FOREIGN KEY (rate_plan_id) REFERENCES rates.rate_plan (id),
    CONSTRAINT rate_price_values_ck CHECK (
        one_adult BETWEEN 0 AND 1000000
            AND (two_adults IS NULL OR two_adults BETWEEN 0 AND 1000000)
            AND (extra_adult IS NULL OR extra_adult BETWEEN 0 AND 1000000)
        )
);

CREATE INDEX rate_price_plan_date_idx ON rates.rate_price (rate_plan_id, stay_date);

-- Preço de criança por faixa etária (H2): a faixa (idades, inclusive) com que foi gravado. Sem faixas no hotel, uma só
-- faixa de 0 à idade limite de criança. Uma faixa que não existe mais na política do hotel fica obsoleta (decisão do
-- usuário na E02): ignorada até o gestor regravar a data, que troca todas as faixas dela.
CREATE TABLE rates.rate_price_child
(
    rate_plan_id UUID           NOT NULL,
    room_type_id UUID           NOT NULL,
    stay_date    DATE           NOT NULL,
    min_age      INT            NOT NULL,
    max_age      INT            NOT NULL,
    amount       NUMERIC(14, 2) NOT NULL,
    CONSTRAINT rate_price_child_pk PRIMARY KEY (rate_plan_id, room_type_id, stay_date, min_age),
    CONSTRAINT rate_price_child_price_fk FOREIGN KEY (rate_plan_id, room_type_id, stay_date)
        REFERENCES rates.rate_price (rate_plan_id, room_type_id, stay_date) ON DELETE CASCADE,
    CONSTRAINT rate_price_child_ages_ck CHECK (min_age >= 0 AND max_age >= min_age AND max_age <= 17),
    CONSTRAINT rate_price_child_amount_ck CHECK (amount BETWEEN 0 AND 1000000)
);

-- Temporada nomeada (RF-TAR-04): atalho de período para a edição em massa, de até 366 dias. Nome único sem diferenciar
-- maiúsculas; nunca apagada, só desativada.
CREATE TABLE rates.season
(
    id         UUID                     NOT NULL,
    name       VARCHAR(80)              NOT NULL,
    starts_on  DATE                     NOT NULL,
    ends_on    DATE                     NOT NULL,
    active     BOOLEAN                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT season_pk PRIMARY KEY (id),
    CONSTRAINT season_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT season_period_ck CHECK (ends_on >= starts_on AND ends_on - starts_on < 366)
);

CREATE UNIQUE INDEX season_name_uk ON rates.season (lower(name));

CREATE TRIGGER season_never_deleted
    BEFORE DELETE
    ON rates.season
    FOR EACH ROW
EXECUTE FUNCTION rates.refuse_delete();

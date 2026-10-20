-- Pontos e turnos de caixa (SPEC §5.10 e §8.2.7, RF-CTA-09 e 10, RN-03, H13 e H14 do M3, M3 E07). O operador é um
-- id de usuário do iam (sem FK fora do schema). Pontos de caixa e turnos nunca são apagados (trigger
-- billing.refuse_delete); um turno fechado não muda mais. Os movimentos de caixa (sangria, suprimento) e as
-- contagens do fechamento são fatos imutáveis: o papel da aplicação só tem SELECT e INSERT neles (REVOKE no fim,
-- bean AppendOnlyTables do módulo) e um trigger recusa UPDATE e DELETE até do dono.

-- Pontos de caixa (recepção, restaurante, bar…). O nome é único sem diferenciar maiúsculas (inclusive o de um
-- desativado). blind_close: o fechamento é cego (padrão; decisão do usuário em 2026-10-19), o operador só vê o valor
-- esperado depois de declarar a contagem; o turno guarda o valor da abertura.
CREATE TABLE billing.cash_point
(
    id          UUID                     NOT NULL,
    name        VARCHAR(80)              NOT NULL,
    blind_close BOOLEAN                  NOT NULL,
    active      BOOLEAN                  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT cash_point_pk PRIMARY KEY (id),
    CONSTRAINT cash_point_name_ck CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80)
);

CREATE UNIQUE INDEX cash_point_name_uk ON billing.cash_point (lower(name));

CREATE TRIGGER cash_point_never_deleted
    BEFORE DELETE
    ON billing.cash_point
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_delete();

-- Número legível dos turnos (CX-2026-000123), com o ano da data operacional da abertura.
CREATE SEQUENCE billing.cashier_shift_number_seq;

-- Turnos de caixa (RF-CTA-09): por operador e ponto de caixa, com o fundo de troco (em dinheiro) na abertura. No
-- máximo um turno aberto por operador, em qualquer ponto (H13): cashier_shift_open_operator_uk, que barra também
-- duas aberturas simultâneas. blind_close é o do ponto na abertura. No fechamento: a data operacional, o instante e
-- a soma das diferenças por forma (difference_total; as diferenças por forma ficam em cashier_shift_count).
CREATE TABLE billing.cashier_shift
(
    id               UUID                     NOT NULL,
    number           VARCHAR(30)              NOT NULL,
    cash_point_id    UUID                     NOT NULL,
    operator_id      UUID                     NOT NULL,
    status           VARCHAR(10)              NOT NULL,
    blind_close      BOOLEAN                  NOT NULL,
    opening_float    NUMERIC(14, 2)           NOT NULL,
    opened_on        DATE                     NOT NULL,
    opened_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_on        DATE,
    closed_at        TIMESTAMP WITH TIME ZONE,
    difference_total NUMERIC(14, 2),
    version          BIGINT                   NOT NULL,
    CONSTRAINT cashier_shift_pk PRIMARY KEY (id),
    CONSTRAINT cashier_shift_number_uk UNIQUE (number),
    CONSTRAINT cashier_shift_cash_point_fk FOREIGN KEY (cash_point_id) REFERENCES billing.cash_point (id),
    CONSTRAINT cashier_shift_status_ck CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT cashier_shift_opening_float_ck CHECK (opening_float >= 0),
    CONSTRAINT cashier_shift_closing_ck CHECK (
        (status = 'OPEN' AND closed_on IS NULL AND closed_at IS NULL AND difference_total IS NULL)
            OR (status = 'CLOSED' AND closed_on IS NOT NULL AND closed_at IS NOT NULL AND closed_at >= opened_at
            AND closed_on >= opened_on AND difference_total IS NOT NULL))
);

CREATE UNIQUE INDEX cashier_shift_open_operator_uk ON billing.cashier_shift (operator_id) WHERE status = 'OPEN';
CREATE INDEX cashier_shift_operator_idx ON billing.cashier_shift (operator_id, id);
CREATE INDEX cashier_shift_cash_point_idx ON billing.cashier_shift (cash_point_id, id);

CREATE TRIGGER cashier_shift_never_deleted
    BEFORE DELETE
    ON billing.cashier_shift
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_delete();

-- Um turno fechado não muda mais (a reabertura está fora do M3).
CREATE FUNCTION billing.refuse_closed_shift_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Turno de caixa fechado (billing.cashier_shift) não muda'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER cashier_shift_closed_immutable
    BEFORE UPDATE
    ON billing.cashier_shift
    FOR EACH ROW
    WHEN (OLD.status = 'CLOSED')
EXECUTE FUNCTION billing.refuse_closed_shift_change();

-- Movimentos de caixa (RF-CTA-09, RN-03): sangria (WITHDRAWAL) e suprimento (SUPPLY), sempre em dinheiro e com
-- valor positivo (decisão do usuário em 2026-10-19), com o motivo, a data operacional, o instante e o operador.
CREATE TABLE billing.cash_movement
(
    id            UUID                     NOT NULL,
    shift_id      UUID                     NOT NULL,
    movement_type VARCHAR(20)              NOT NULL,
    amount        NUMERIC(14, 2)           NOT NULL,
    reason        VARCHAR(200)             NOT NULL,
    business_date DATE                     NOT NULL,
    recorded_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_by   UUID                     NOT NULL,
    CONSTRAINT cash_movement_pk PRIMARY KEY (id),
    CONSTRAINT cash_movement_shift_fk FOREIGN KEY (shift_id) REFERENCES billing.cashier_shift (id),
    CONSTRAINT cash_movement_type_ck CHECK (movement_type IN ('WITHDRAWAL', 'SUPPLY')),
    CONSTRAINT cash_movement_amount_ck CHECK (amount > 0),
    CONSTRAINT cash_movement_reason_ck CHECK (reason = btrim(reason) AND length(reason) BETWEEN 1 AND 200)
);

CREATE INDEX cash_movement_shift_idx ON billing.cash_movement (shift_id, recorded_at, id);

-- A contagem do fechamento (RF-CTA-09): uma linha por forma de pagamento, com o declarado pelo operador, o esperado
-- calculado (fundo, movimentos e, desde a E08, pagamentos) e a diferença (declarado - esperado).
CREATE TABLE billing.cashier_shift_count
(
    shift_id   UUID           NOT NULL,
    method     VARCHAR(20)    NOT NULL,
    declared   NUMERIC(14, 2) NOT NULL,
    expected   NUMERIC(14, 2) NOT NULL,
    difference NUMERIC(14, 2) NOT NULL,
    CONSTRAINT cashier_shift_count_pk PRIMARY KEY (shift_id, method),
    CONSTRAINT cashier_shift_count_shift_fk FOREIGN KEY (shift_id) REFERENCES billing.cashier_shift (id),
    CONSTRAINT cashier_shift_count_method_ck CHECK (method IN
        ('CASH', 'DEBIT_CARD', 'CREDIT_CARD', 'PIX', 'BANK_TRANSFER', 'OTHER')),
    CONSTRAINT cashier_shift_count_declared_ck CHECK (declared >= 0),
    CONSTRAINT cashier_shift_count_difference_ck CHECK (difference = declared - expected)
);

-- Movimentos e contagens nunca mudam nem são apagados (RN-03), nem pelo dono: além do privilégio do papel da
-- aplicação.
CREATE FUNCTION billing.refuse_cash_record_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de caixa (billing.%) são imutáveis', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER cash_movement_immutable
    BEFORE UPDATE OR DELETE
    ON billing.cash_movement
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_cash_record_change();

CREATE TRIGGER cashier_shift_count_immutable
    BEFORE UPDATE OR DELETE
    ON billing.cashier_shift_count
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_cash_record_change();

-- O ALTER DEFAULT PRIVILEGES da pasta deu DML às tabelas novas: as append-only ficam só com SELECT e INSERT.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE billing.cash_movement FROM "${app_role}";
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE billing.cashier_shift_count FROM "${app_role}";

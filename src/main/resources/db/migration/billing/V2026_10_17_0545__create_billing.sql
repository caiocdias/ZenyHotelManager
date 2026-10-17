-- Contas e lançamentos (SPEC §5.10 e §8.2.7, RF-CTA-01 a 03, RN-01 a RN-03, M3 E04): códigos de lançamento,
-- contas (folios) para qualquer dono e lançamentos imutáveis, com estorno e ajuste vinculados.
-- Códigos e contas nunca são apagados (trigger no fim); lançamentos nunca mudam nem são apagados: o papel da
-- aplicação só tem SELECT e INSERT em billing.posting (migração de privilégios, bean AppendOnlyTables do módulo) e
-- um trigger recusa UPDATE e DELETE até do dono.

-- Códigos de lançamento (RF-CTA-02). O código curto fica em maiúsculas e é único. O tipo (kind) e o grupo de
-- receita (revenue_group) são fixos desde a criação (um grupo novo exige migração; decisão do usuário em
-- 2026-10-17). A taxa de serviço só se aplica a código de consumo (CHARGE); a taxa própria é anulável e cai na
-- padrão do hotel (H4). A classificação fiscal é texto livre opcional até o M7 (H2).
CREATE TABLE billing.transaction_code
(
    id                     UUID                     NOT NULL,
    system_key             VARCHAR(30),
    code                   VARCHAR(20)              NOT NULL,
    name                   VARCHAR(80)              NOT NULL,
    kind                   VARCHAR(20)              NOT NULL,
    revenue_group          VARCHAR(20)              NOT NULL,
    service_charge_applies BOOLEAN                  NOT NULL,
    service_charge_rate    NUMERIC(7, 4),
    fiscal_classification  VARCHAR(60),
    active                 BOOLEAN                  NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT transaction_code_pk PRIMARY KEY (id),
    CONSTRAINT transaction_code_code_uk UNIQUE (code),
    CONSTRAINT transaction_code_system_key_uk UNIQUE (system_key),
    CONSTRAINT transaction_code_system_key_ck CHECK (system_key IS NULL OR system_key IN
        ('ROOM_CHARGE', 'CANCELLATION_FEE', 'NO_SHOW_FEE', 'SERVICE_CHARGE', 'FOOD_BEVERAGE', 'LAUNDRY',
         'TOURISM_FEE', 'MISCELLANEOUS')),
    CONSTRAINT transaction_code_code_ck CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT transaction_code_name_ck CHECK (length(btrim(name)) > 0),
    CONSTRAINT transaction_code_kind_ck CHECK (kind IN ('CHARGE', 'PENALTY', 'SERVICE_CHARGE', 'TAX', 'CREDIT')),
    CONSTRAINT transaction_code_revenue_group_ck CHECK (revenue_group IN
        ('ROOM', 'FOOD_BEVERAGE', 'SERVICE_CHARGE', 'FEES', 'TAXES', 'LAUNDRY', 'OTHER')),
    CONSTRAINT transaction_code_service_charge_ck CHECK (NOT service_charge_applies OR kind = 'CHARGE'),
    CONSTRAINT transaction_code_service_charge_rate_ck CHECK (service_charge_rate IS NULL OR
        (service_charge_applies AND service_charge_rate BETWEEN 0 AND 100)),
    CONSTRAINT transaction_code_fiscal_classification_ck CHECK (fiscal_classification IS NULL OR
        length(btrim(fiscal_classification)) > 0)
);

-- Os oito códigos padrão (H5, decisão do usuário em 2026-10-17), com a chave estável pela qual os outros módulos
-- os acham (TransactionCodeKey): o gestor troca código, nome, taxa de serviço e classificação fiscal e os desativa,
-- mas nunca os apaga. A diária não tem taxa de serviço; o A&B tem (com a taxa padrão do hotel).
INSERT INTO billing.transaction_code (id, system_key, code, name, kind, revenue_group, service_charge_applies,
                                      service_charge_rate, fiscal_classification, active, created_at, updated_at,
                                      version)
VALUES ('019bf1a0-0000-7000-8000-000000000001', 'ROOM_CHARGE', 'DIARIA', 'Diária', 'CHARGE', 'ROOM', FALSE, NULL,
        NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0001-7001-8000-000000000002', 'CANCELLATION_FEE', 'MULTA-CANC', 'Multa de cancelamento', 'PENALTY',
        'FEES', FALSE, NULL, NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0002-7002-8000-000000000003', 'NO_SHOW_FEE', 'MULTA-NOSHOW', 'Multa de no-show', 'PENALTY', 'FEES',
        FALSE, NULL, NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0003-7003-8000-000000000004', 'SERVICE_CHARGE', 'TX-SERVICO', 'Taxa de serviço', 'SERVICE_CHARGE',
        'SERVICE_CHARGE', FALSE, NULL, NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0004-7004-8000-000000000005', 'FOOD_BEVERAGE', 'AB', 'Alimentos e bebidas', 'CHARGE',
        'FOOD_BEVERAGE', TRUE, NULL, NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0005-7005-8000-000000000006', 'LAUNDRY', 'LAVANDERIA', 'Lavanderia', 'CHARGE', 'LAUNDRY', FALSE,
        NULL, NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0006-7006-8000-000000000007', 'TOURISM_FEE', 'TX-TURISMO', 'Taxa de turismo', 'TAX', 'TAXES',
        FALSE, NULL, NULL, TRUE, now(), now(), 0),
       ('019bf1a0-0007-7007-8000-000000000008', 'MISCELLANEOUS', 'AVULSO', 'Lançamento avulso', 'CHARGE', 'OTHER',
        FALSE, NULL, NULL, TRUE, now(), now(), 0);

-- Número legível das contas (C-2026-000123), com o ano da data operacional da abertura.
CREATE SEQUENCE billing.folio_number_seq;

-- Contas (RF-CTA-01): o dono é um FolioOwner(tipo, id opaco); o billing não conhece reservas, grupos nem eventos
-- (só a empresa é conferida, pela GuestsApi). O pagador opcional é uma empresa. O limite de crédito é anulável
-- (sem limite) e não negativo. A conta nasce aberta; o encerramento é da E10.
CREATE TABLE billing.folio
(
    id               UUID                     NOT NULL,
    number           VARCHAR(30)              NOT NULL,
    owner_type       VARCHAR(20)              NOT NULL,
    owner_id         UUID                     NOT NULL,
    payer_company_id UUID,
    credit_limit     NUMERIC(14, 2),
    status           VARCHAR(10)              NOT NULL,
    opened_on        DATE                     NOT NULL,
    opened_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    opened_by        UUID,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT                   NOT NULL,
    CONSTRAINT folio_pk PRIMARY KEY (id),
    CONSTRAINT folio_number_uk UNIQUE (number),
    CONSTRAINT folio_owner_type_ck CHECK (owner_type IN ('RESERVATION', 'GROUP', 'EVENT', 'COMPANY', 'WALK_IN')),
    CONSTRAINT folio_status_ck CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT folio_credit_limit_ck CHECK (credit_limit IS NULL OR credit_limit >= 0)
);

CREATE INDEX folio_owner_idx ON billing.folio (owner_type, owner_id, id);
CREATE INDEX folio_payer_idx ON billing.folio (payer_company_id) WHERE payer_company_id IS NOT NULL;

-- Lançamentos (RF-CTA-02 e 03, RN-01, RN-03): fatos imutáveis. O normal (REGULAR) tem quantidade e valor unitário
-- positivos e o valor com o sinal do tipo do código (CREDIT negativo). O estorno (REVERSAL) e o ajuste (ADJUSTMENT)
-- corrigem um lançamento normal da mesma conta (corrects_id), com motivo, quantidade 1 e o valor unitário igual ao
-- valor (sinal oposto ao do original); um lançamento tem no máximo um estorno (posting_reversal_uk). A data é a
-- operacional do hotel (business_date), o instante é o do relógio da aplicação; posted_by nulo = o sistema.
-- approval_id e approved_by: a aprovação de superior que autorizou a correção (step-up), se houve.
CREATE TABLE billing.posting
(
    id                  UUID                     NOT NULL,
    folio_id            UUID                     NOT NULL,
    transaction_code_id UUID                     NOT NULL,
    posting_type        VARCHAR(20)              NOT NULL,
    quantity            NUMERIC(10, 3)           NOT NULL,
    unit_price          NUMERIC(14, 2)           NOT NULL,
    amount              NUMERIC(14, 2)           NOT NULL,
    description         VARCHAR(200)             NOT NULL,
    source              VARCHAR(20)              NOT NULL,
    business_date       DATE                     NOT NULL,
    posted_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    posted_by           UUID,
    corrects_id         UUID,
    reason              VARCHAR(500),
    approval_id         UUID,
    approved_by         UUID,
    CONSTRAINT posting_pk PRIMARY KEY (id),
    CONSTRAINT posting_id_folio_uk UNIQUE (id, folio_id),
    CONSTRAINT posting_folio_fk FOREIGN KEY (folio_id) REFERENCES billing.folio (id),
    CONSTRAINT posting_transaction_code_fk FOREIGN KEY (transaction_code_id) REFERENCES billing.transaction_code (id),
    CONSTRAINT posting_corrects_fk FOREIGN KEY (corrects_id, folio_id) REFERENCES billing.posting (id, folio_id),
    CONSTRAINT posting_type_ck CHECK (posting_type IN ('REGULAR', 'REVERSAL', 'ADJUSTMENT')),
    CONSTRAINT posting_source_ck CHECK (source IN ('MANUAL', 'POS', 'END_OF_DAY', 'LAUNDRY', 'EVENT')),
    CONSTRAINT posting_amount_ck CHECK (amount <> 0),
    CONSTRAINT posting_description_ck CHECK (length(btrim(description)) > 0),
    CONSTRAINT posting_regular_ck CHECK (posting_type <> 'REGULAR' OR
        (corrects_id IS NULL AND reason IS NULL AND quantity > 0 AND unit_price > 0)),
    CONSTRAINT posting_correction_ck CHECK (posting_type = 'REGULAR' OR
        (corrects_id IS NOT NULL AND reason IS NOT NULL AND length(btrim(reason)) > 0 AND quantity = 1
            AND unit_price = amount)),
    CONSTRAINT posting_approval_ck CHECK ((approval_id IS NULL) = (approved_by IS NULL))
);

CREATE UNIQUE INDEX posting_reversal_uk ON billing.posting (corrects_id) WHERE posting_type = 'REVERSAL';
CREATE INDEX posting_folio_idx ON billing.posting (folio_id, posted_at, id);
CREATE INDEX posting_corrects_idx ON billing.posting (corrects_id) WHERE corrects_id IS NOT NULL;

-- Nada daqui é apagado: códigos e contas só são desativados ou encerrados.
CREATE FUNCTION billing.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de billing.% nunca são apagados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER transaction_code_never_deleted
    BEFORE DELETE
    ON billing.transaction_code
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_delete();

CREATE TRIGGER folio_never_deleted
    BEFORE DELETE
    ON billing.folio
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_delete();

-- Lançamento nunca muda nem é apagado (RN-03), nem pelo dono: além do privilégio do papel da aplicação.
CREATE FUNCTION billing.refuse_posting_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Lançamentos (billing.posting) são imutáveis: corrija por estorno ou ajuste'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER posting_immutable
    BEFORE UPDATE OR DELETE
    ON billing.posting
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_posting_change();

-- Intenções de pagamento (SPEC §5.11 e §8.2.8, RF-PAG-03 e 06, H1 do M3, M3 E08): todo pagamento passa por uma
-- intenção neutra, com a finalidade e o destino opacos (o payments não conhece contas: quem a inicia, como o
-- billing, guarda o próprio pagamento e escuta PaymentSucceeded). O provedor confirma a intenção: o MANUAL (registro
-- de maquininha não integrada, Pix ou dinheiro conferido por quem opera) confirma na hora; os provedores do M7 (Pix
-- com PSP, cartão integrado) confirmam depois. A intenção nunca é apagada (trigger no fim) e, confirmada ou
-- recusada, não muda mais. Nada de dado de cartão: a referência do provedor é um identificador curto (NSU,
-- autorização, txid), recusado com cara de número de cartão (13 dígitos ou mais; RNF-10).
CREATE TABLE payments.payment_intent
(
    id                 UUID                     NOT NULL,
    purpose            VARCHAR(60)              NOT NULL,
    target_type        VARCHAR(60)              NOT NULL,
    target_id          UUID                     NOT NULL,
    amount             NUMERIC(14, 2)           NOT NULL,
    provider           VARCHAR(30)              NOT NULL,
    status             VARCHAR(20)              NOT NULL,
    provider_reference VARCHAR(40),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by         UUID,
    succeeded_at       TIMESTAMP WITH TIME ZONE,
    failed_at          TIMESTAMP WITH TIME ZONE,
    failure_reason     VARCHAR(200),
    version            BIGINT                   NOT NULL,
    CONSTRAINT payment_intent_pk PRIMARY KEY (id),
    CONSTRAINT payment_intent_purpose_ck CHECK (purpose ~ '^[a-z][a-z0-9]*\.[a-z][a-z0-9-]*$'),
    CONSTRAINT payment_intent_target_type_ck CHECK (target_type ~ '^[a-z][a-z0-9]*\.[a-z][a-z0-9-]*$'),
    CONSTRAINT payment_intent_amount_ck CHECK (amount > 0),
    CONSTRAINT payment_intent_provider_ck CHECK (provider ~ '^[A-Z][A-Z0-9_]{0,29}$'),
    CONSTRAINT payment_intent_status_ck CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT payment_intent_outcome_ck CHECK (
        (status = 'PENDING' AND succeeded_at IS NULL AND failed_at IS NULL AND failure_reason IS NULL)
            OR (status = 'SUCCEEDED' AND succeeded_at IS NOT NULL AND failed_at IS NULL AND failure_reason IS NULL)
            OR (status = 'FAILED' AND failed_at IS NOT NULL AND succeeded_at IS NULL
            AND failure_reason IS NOT NULL)),
    CONSTRAINT payment_intent_reference_ck CHECK (provider_reference IS NULL OR
        (provider_reference ~ '^[A-Za-z0-9._-]{1,40}$'
            AND length(regexp_replace(provider_reference, '[^0-9]', '', 'g')) < 13))
);

CREATE INDEX payment_intent_target_idx ON payments.payment_intent (target_type, target_id);

CREATE FUNCTION payments.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de payments.% nunca são apagados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER payment_intent_never_deleted
    BEFORE DELETE
    ON payments.payment_intent
    FOR EACH ROW
EXECUTE FUNCTION payments.refuse_delete();

-- Uma intenção confirmada ou recusada não muda mais (o resultado do provedor é um fato).
CREATE FUNCTION payments.refuse_settled_intent_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Intenção de pagamento já concluída (payments.payment_intent) não muda'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER payment_intent_settled_immutable
    BEFORE UPDATE
    ON payments.payment_intent
    FOR EACH ROW
    WHEN (OLD.status <> 'PENDING')
EXECUTE FUNCTION payments.refuse_settled_intent_change();

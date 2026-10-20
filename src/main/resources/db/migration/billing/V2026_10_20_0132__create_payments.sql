-- Pagamentos e reembolsos na conta (SPEC §5.10 e §8.2.7, RF-CTA-06, 07, 10 e 15, RF-PAG-03, RN-03, RNF-10, H1 e H13
-- do M3, M3 E08; decisões do usuário em 2026-10-19). Fatos imutáveis: o papel da aplicação só tem SELECT e INSERT
-- (REVOKE no fim, bean AppendOnlyTables do módulo) e um trigger recusa UPDATE e DELETE até do dono.
--
-- amount é sempre positivo; payment_type diz o sentido: PAYMENT (recebimento: diminui o saldo da conta e soma no
-- esperado do turno, na forma) ou REFUND (reembolso de um recebimento da mesma conta, na mesma forma, com motivo e a
-- aprovação de superior, se houve: aumenta o saldo e tira do esperado do turno de quem devolve). Todo registro cita o
-- turno de caixa aberto de quem recebe ou devolve (RF-CTA-10). O recebimento nasce de uma intenção de pagamento do
-- payments (H1; id opaco, sem FK fora do schema), uma por intenção (payment_intent_uk: o listener de PaymentSucceeded
-- nunca registra a mesma em dobro). Troco só no dinheiro: tendered é o valor entregue e change_given = tendered -
-- amount. deposit marca o depósito (sinal antes da chegada, RF-CTA-07), informado por quem opera; o reembolso de um
-- depósito também é marcado.
--
-- RNF-10 (PCI-DSS): nunca o número do cartão nem o CVV. Só a bandeira e os 4 últimos dígitos (no cartão) e o NSU e a
-- autorização (fora do dinheiro), com até 20 letras e dígitos e no máximo 12 dígitos: uma sequência de 13 a 19 dígitos
-- tem cara de número de cartão e é recusada também aqui.
CREATE TABLE billing.payment
(
    id                 UUID                     NOT NULL,
    folio_id           UUID                     NOT NULL,
    payment_type       VARCHAR(20)              NOT NULL,
    method             VARCHAR(20)              NOT NULL,
    amount             NUMERIC(14, 2)           NOT NULL,
    tendered           NUMERIC(14, 2),
    change_given       NUMERIC(14, 2),
    deposit            BOOLEAN                  NOT NULL,
    nsu                VARCHAR(20),
    authorization_code VARCHAR(20),
    card_brand         VARCHAR(20),
    card_last4         VARCHAR(4),
    payment_intent_id  UUID,
    refund_of_id       UUID,
    reason             VARCHAR(200),
    cashier_shift_id   UUID                     NOT NULL,
    business_date      DATE                     NOT NULL,
    received_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    received_by        UUID                     NOT NULL,
    approval_id        UUID,
    approved_by        UUID,
    CONSTRAINT payment_pk PRIMARY KEY (id),
    CONSTRAINT payment_id_folio_uk UNIQUE (id, folio_id),
    CONSTRAINT payment_folio_fk FOREIGN KEY (folio_id) REFERENCES billing.folio (id),
    CONSTRAINT payment_shift_fk FOREIGN KEY (cashier_shift_id) REFERENCES billing.cashier_shift (id),
    CONSTRAINT payment_refund_of_fk FOREIGN KEY (refund_of_id, folio_id) REFERENCES billing.payment (id, folio_id),
    CONSTRAINT payment_type_ck CHECK (payment_type IN ('PAYMENT', 'REFUND')),
    CONSTRAINT payment_method_ck CHECK (method IN
        ('CASH', 'DEBIT_CARD', 'CREDIT_CARD', 'PIX', 'BANK_TRANSFER', 'OTHER')),
    CONSTRAINT payment_amount_ck CHECK (amount > 0 AND amount <= 1000000),
    CONSTRAINT payment_kind_ck CHECK (
        (payment_type = 'PAYMENT' AND payment_intent_id IS NOT NULL AND refund_of_id IS NULL AND reason IS NULL
            AND approval_id IS NULL)
            OR (payment_type = 'REFUND' AND payment_intent_id IS NULL AND refund_of_id IS NOT NULL
            AND reason IS NOT NULL AND reason = btrim(reason) AND length(reason) BETWEEN 1 AND 200
            AND tendered IS NULL)),
    CONSTRAINT payment_approval_ck CHECK ((approval_id IS NULL) = (approved_by IS NULL)),
    CONSTRAINT payment_change_ck CHECK (
        (tendered IS NULL AND change_given IS NULL)
            OR (method = 'CASH' AND tendered >= amount AND tendered <= 1000000
            AND change_given = tendered - amount)),
    CONSTRAINT payment_cash_reference_ck CHECK (method <> 'CASH' OR (nsu IS NULL AND authorization_code IS NULL)),
    CONSTRAINT payment_card_ck CHECK (method IN ('CREDIT_CARD', 'DEBIT_CARD')
        OR (card_brand IS NULL AND card_last4 IS NULL)),
    CONSTRAINT payment_card_brand_ck CHECK (card_brand IS NULL OR card_brand ~ '^[A-Z][A-Z ]{0,19}$'),
    CONSTRAINT payment_card_last4_ck CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT payment_nsu_ck CHECK (nsu IS NULL OR
        (nsu ~ '^[A-Z0-9]{1,20}$' AND length(regexp_replace(nsu, '[^0-9]', '', 'g')) < 13)),
    CONSTRAINT payment_authorization_ck CHECK (authorization_code IS NULL OR
        (authorization_code ~ '^[A-Z0-9]{1,20}$'
            AND length(regexp_replace(authorization_code, '[^0-9]', '', 'g')) < 13))
);

CREATE UNIQUE INDEX payment_intent_uk ON billing.payment (payment_intent_id) WHERE payment_intent_id IS NOT NULL;
CREATE INDEX payment_folio_idx ON billing.payment (folio_id, received_at, id);
CREATE INDEX payment_shift_idx ON billing.payment (cashier_shift_id, method);
CREATE INDEX payment_refund_of_idx ON billing.payment (refund_of_id) WHERE refund_of_id IS NOT NULL;

-- O reembolso repete o recebimento: cita um recebimento (nunca outro reembolso), na mesma forma e com a mesma marca
-- de depósito, e o total reembolsado nunca passa do valor pago. A trava consultiva por recebimento enfileira dois
-- reembolsos do mesmo pagamento (a aplicação já os enfileira pela trava da conta; esta vale também para SQL direto).
CREATE FUNCTION billing.check_payment_refund() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    original billing.payment%ROWTYPE;
    refunded NUMERIC(14, 2);
BEGIN
    IF NEW.payment_type <> 'REFUND' THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('billing.payment-refund:' || NEW.refund_of_id::text));
    SELECT * INTO original FROM billing.payment WHERE id = NEW.refund_of_id;
    IF original.payment_type <> 'PAYMENT' OR original.method <> NEW.method OR original.deposit <> NEW.deposit THEN
        RAISE EXCEPTION 'Reembolso incoerente com o recebimento (billing.payment)'
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO refunded FROM billing.payment WHERE refund_of_id = NEW.refund_of_id;
    IF refunded + NEW.amount > original.amount THEN
        RAISE EXCEPTION 'Reembolso acima do valor pago (billing.payment)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_refund_coherent
    BEFORE INSERT
    ON billing.payment
    FOR EACH ROW
EXECUTE FUNCTION billing.check_payment_refund();

-- Pagamentos nunca mudam nem são apagados (RN-03), nem pelo dono: além do privilégio do papel da aplicação.
CREATE TRIGGER payment_immutable
    BEFORE UPDATE OR DELETE
    ON billing.payment
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_cash_record_change();

-- O ALTER DEFAULT PRIVILEGES da pasta deu DML à tabela nova: append-only, só SELECT e INSERT.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE billing.payment FROM "${app_role}";

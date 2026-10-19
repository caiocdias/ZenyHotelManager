-- Roteamento, transferência e divisão (SPEC §5.10 e §8.2.7, RF-CTA-04 e 05, RN-02, RN-03, M3 E05).
-- Nada muda num lançamento já feito: o roteamento só decide a conta de um lançamento novo, e a transferência e a
-- divisão são pares novos de lançamentos (saída na origem, entrada no destino). Os privilégios do papel da aplicação
-- vêm do ALTER DEFAULT PRIVILEGES da pasta (DML nas tabelas novas); billing.posting continua só com SELECT e INSERT.

-- Regras de roteamento (RF-CTA-04): os lançamentos pedidos na conta de origem, cujo código está na regra (por
-- código, tabela routing_rule_code) ou no grupo de receita dela (revenue_group), na data operacional dentro do
-- período (inclusive; ends_on nulo = sem fim), vão para a conta de destino. Uma regra cobre ou códigos ou um grupo
-- (a aplicação garante; a por código prevalece na mesma conta). Sobreposição e ciclo são conferidos pela aplicação
-- sob uma trava consultiva única das escritas de regras. Nunca apagada, só desativada.
CREATE TABLE billing.routing_rule
(
    id              UUID                     NOT NULL,
    source_folio_id UUID                     NOT NULL,
    target_folio_id UUID                     NOT NULL,
    revenue_group   VARCHAR(20),
    starts_on       DATE                     NOT NULL,
    ends_on         DATE,
    active          BOOLEAN                  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      UUID,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT                   NOT NULL,
    CONSTRAINT routing_rule_pk PRIMARY KEY (id),
    CONSTRAINT routing_rule_source_fk FOREIGN KEY (source_folio_id) REFERENCES billing.folio (id),
    CONSTRAINT routing_rule_target_fk FOREIGN KEY (target_folio_id) REFERENCES billing.folio (id),
    CONSTRAINT routing_rule_distinct_ck CHECK (source_folio_id <> target_folio_id),
    CONSTRAINT routing_rule_period_ck CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT routing_rule_revenue_group_ck CHECK (revenue_group IS NULL OR revenue_group IN
        ('ROOM', 'FOOD_BEVERAGE', 'SERVICE_CHARGE', 'FEES', 'TAXES', 'LAUNDRY', 'OTHER'))
);

CREATE INDEX routing_rule_source_idx ON billing.routing_rule (source_folio_id, id);
CREATE INDEX routing_rule_target_idx ON billing.routing_rule (target_folio_id, id);

-- Os códigos de uma regra por código (saem e entram quando a regra é editada).
CREATE TABLE billing.routing_rule_code
(
    routing_rule_id     UUID NOT NULL,
    transaction_code_id UUID NOT NULL,
    CONSTRAINT routing_rule_code_pk PRIMARY KEY (routing_rule_id, transaction_code_id),
    CONSTRAINT routing_rule_code_rule_fk FOREIGN KEY (routing_rule_id) REFERENCES billing.routing_rule (id),
    CONSTRAINT routing_rule_code_code_fk FOREIGN KEY (transaction_code_id) REFERENCES billing.transaction_code (id)
);

CREATE TRIGGER routing_rule_never_deleted
    BEFORE DELETE
    ON billing.routing_rule
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_delete();

-- Lançamentos: a origem de um lançamento roteado (routed_from_folio_id, a conta em que foi pedido, e routing_rule_id,
-- a regra dela que o desviou) e o vínculo da entrada de uma transferência com a saída (transferred_from_id).
ALTER TABLE billing.posting
    ADD COLUMN routed_from_folio_id UUID,
    ADD COLUMN routing_rule_id      UUID,
    ADD COLUMN transferred_from_id  UUID;

ALTER TABLE billing.posting
    ADD CONSTRAINT posting_routed_from_fk FOREIGN KEY (routed_from_folio_id) REFERENCES billing.folio (id),
    ADD CONSTRAINT posting_routing_rule_fk FOREIGN KEY (routing_rule_id) REFERENCES billing.routing_rule (id),
    ADD CONSTRAINT posting_transferred_from_fk FOREIGN KEY (transferred_from_id) REFERENCES billing.posting (id);

-- Os tipos novos: TRANSFER_OUT (na conta de origem, o inverso da parte transferida, citando o lançamento transferido
-- em corrects_id, da mesma conta pela FK posting_corrects_fk) e TRANSFER_IN (na conta de destino, a parte, citando a
-- saída em transferred_from_id). Os dois com quantidade 1, valor unitário = valor, motivo opcional e sem aprovação.
ALTER TABLE billing.posting
    DROP CONSTRAINT posting_type_ck,
    DROP CONSTRAINT posting_regular_ck,
    DROP CONSTRAINT posting_correction_ck;

ALTER TABLE billing.posting
    ADD CONSTRAINT posting_type_ck CHECK (posting_type IN
        ('REGULAR', 'REVERSAL', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN')),
    ADD CONSTRAINT posting_regular_ck CHECK (posting_type <> 'REGULAR' OR
        (corrects_id IS NULL AND reason IS NULL AND quantity > 0 AND unit_price > 0 AND transferred_from_id IS NULL)),
    ADD CONSTRAINT posting_correction_ck CHECK (posting_type NOT IN ('REVERSAL', 'ADJUSTMENT') OR
        (corrects_id IS NOT NULL AND reason IS NOT NULL AND length(btrim(reason)) > 0 AND quantity = 1
            AND unit_price = amount AND transferred_from_id IS NULL)),
    ADD CONSTRAINT posting_transfer_out_ck CHECK (posting_type <> 'TRANSFER_OUT' OR
        (corrects_id IS NOT NULL AND transferred_from_id IS NULL AND quantity = 1 AND unit_price = amount
            AND approval_id IS NULL)),
    ADD CONSTRAINT posting_transfer_in_ck CHECK (posting_type <> 'TRANSFER_IN' OR
        (corrects_id IS NULL AND transferred_from_id IS NOT NULL AND quantity = 1 AND unit_price = amount
            AND approval_id IS NULL)),
    ADD CONSTRAINT posting_transfer_reason_ck CHECK (posting_type NOT IN ('TRANSFER_OUT', 'TRANSFER_IN') OR
        reason IS NULL OR length(btrim(reason)) > 0),
    ADD CONSTRAINT posting_routing_ck CHECK ((routed_from_folio_id IS NULL) = (routing_rule_id IS NULL) AND
        (routed_from_folio_id IS NULL OR (posting_type = 'REGULAR' AND routed_from_folio_id <> folio_id)));

-- Cada saída tem exatamente uma entrada.
CREATE UNIQUE INDEX posting_transfer_in_uk ON billing.posting (transferred_from_id) WHERE transferred_from_id IS NOT NULL;

-- A entrada repete a saída com o sinal trocado, no mesmo código e noutra conta (a soma das duas é zero), e só cita
-- uma saída.
CREATE FUNCTION billing.check_transfer_in() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    outgoing billing.posting%ROWTYPE;
BEGIN
    IF NEW.posting_type <> 'TRANSFER_IN' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO outgoing FROM billing.posting WHERE id = NEW.transferred_from_id;
    IF outgoing.posting_type IS DISTINCT FROM 'TRANSFER_OUT' OR outgoing.amount <> -NEW.amount
        OR outgoing.transaction_code_id <> NEW.transaction_code_id OR outgoing.folio_id = NEW.folio_id THEN
        RAISE EXCEPTION 'A entrada de uma transferência repete a saída com o sinal trocado, noutra conta'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_transfer_in_matches_out
    BEFORE INSERT
    ON billing.posting
    FOR EACH ROW
EXECUTE FUNCTION billing.check_transfer_in();

-- Nenhuma saída fica sem a entrada no fim da transação (conferido no commit).
CREATE FUNCTION billing.check_transfer_out_has_in() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.posting_type = 'TRANSFER_OUT'
        AND NOT EXISTS (SELECT 1 FROM billing.posting WHERE transferred_from_id = NEW.id) THEN
        RAISE EXCEPTION 'A saída de uma transferência precisa da entrada na conta de destino'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER posting_transfer_out_has_in
    AFTER INSERT
    ON billing.posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION billing.check_transfer_out_has_in();

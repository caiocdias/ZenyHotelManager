-- Caução registrada manualmente (SPEC §5.10 e §8.2.7, RF-CTA-08 no recorte manual, RN-03, RNF-10, H13 do M3, M3 E09;
-- decisões do usuário em 2026-10-19). A pré-autorização de cartão de crédito feita numa maquininha não integrada:
-- o valor autorizado, a validade (expires_at), o NSU e a autorização (obrigatória) e, do cartão, só a bandeira e os 4
-- últimos dígitos (nunca o número nem o CVV, as mesmas regras de billing.payment).
--
-- O que muda fica na linha da caução: a situação guardada (ACTIVE, PARTIALLY_CAPTURED, CAPTURED, RELEASED) e o valor
-- capturado. A expiração é calculada na leitura (agora >= expires_at numa caução ACTIVE ou PARTIALLY_CAPTURED), nunca
-- gravada: nenhum job marca a caução. As capturas e a liberação são fatos registrados: cada captura é um recebimento
-- em billing.payment (no cartão de crédito, com card_pre_authorization_id), e a liberação da sobra é uma linha de
-- billing.card_pre_authorization_release (append-only). A caução não capturada nunca entra no saldo da conta: só os
-- recebimentos das capturas entram.
CREATE TABLE billing.card_pre_authorization
(
    id                 UUID                     NOT NULL,
    folio_id           UUID                     NOT NULL,
    amount             NUMERIC(14, 2)           NOT NULL,
    captured_amount    NUMERIC(14, 2)           NOT NULL,
    status             VARCHAR(20)              NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    nsu                VARCHAR(20),
    authorization_code VARCHAR(20)              NOT NULL,
    card_brand         VARCHAR(20),
    card_last4         VARCHAR(4),
    business_date      DATE                     NOT NULL,
    registered_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    registered_by      UUID                     NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT                   NOT NULL,
    CONSTRAINT card_pre_authorization_pk PRIMARY KEY (id),
    CONSTRAINT card_pre_authorization_id_folio_uk UNIQUE (id, folio_id),
    CONSTRAINT card_pre_authorization_folio_fk FOREIGN KEY (folio_id) REFERENCES billing.folio (id),
    CONSTRAINT card_pre_authorization_amount_ck CHECK (amount > 0 AND amount <= 1000000),
    CONSTRAINT card_pre_authorization_captured_ck CHECK (captured_amount >= 0 AND captured_amount <= amount),
    CONSTRAINT card_pre_authorization_status_ck CHECK (
        (status = 'ACTIVE' AND captured_amount = 0)
            OR (status = 'PARTIALLY_CAPTURED' AND captured_amount > 0 AND captured_amount < amount)
            OR (status = 'CAPTURED' AND captured_amount = amount)
            OR (status = 'RELEASED' AND captured_amount < amount)),
    CONSTRAINT card_pre_authorization_expiry_ck CHECK (expires_at > registered_at),
    CONSTRAINT card_pre_authorization_card_brand_ck CHECK (card_brand IS NULL OR card_brand ~ '^[A-Z][A-Z ]{0,19}$'),
    CONSTRAINT card_pre_authorization_card_last4_ck CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT card_pre_authorization_nsu_ck CHECK (nsu IS NULL OR
        (nsu ~ '^[A-Z0-9]{1,20}$' AND length(regexp_replace(nsu, '[^0-9]', '', 'g')) < 13)),
    CONSTRAINT card_pre_authorization_authorization_ck CHECK (authorization_code ~ '^[A-Z0-9]{1,20}$'
        AND length(regexp_replace(authorization_code, '[^0-9]', '', 'g')) < 13),
    CONSTRAINT card_pre_authorization_version_ck CHECK (version >= 0)
);

CREATE INDEX card_pre_authorization_folio_idx ON billing.card_pre_authorization (folio_id, id);
-- A consulta das cauções a vencer só olha as que ainda podem ser capturadas.
CREATE INDEX card_pre_authorization_expiring_idx ON billing.card_pre_authorization (expires_at, id)
    WHERE status IN ('ACTIVE', 'PARTIALLY_CAPTURED');

CREATE TRIGGER card_pre_authorization_never_deleted
    BEFORE DELETE
    ON billing.card_pre_authorization
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_delete();

-- Só a situação, o valor capturado, a versão e o updated_at mudam; o capturado nunca diminui; capturada por inteiro
-- ou liberada, a caução não muda mais.
CREATE FUNCTION billing.check_card_pre_authorization_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status IN ('CAPTURED', 'RELEASED') THEN
        RAISE EXCEPTION 'Caução já encerrada (billing.card_pre_authorization)'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.folio_id <> OLD.folio_id OR NEW.amount <> OLD.amount
        OR NEW.expires_at <> OLD.expires_at OR NEW.nsu IS DISTINCT FROM OLD.nsu
        OR NEW.authorization_code <> OLD.authorization_code OR NEW.card_brand IS DISTINCT FROM OLD.card_brand
        OR NEW.card_last4 IS DISTINCT FROM OLD.card_last4 OR NEW.business_date <> OLD.business_date
        OR NEW.registered_at <> OLD.registered_at OR NEW.registered_by <> OLD.registered_by
        OR NEW.captured_amount < OLD.captured_amount THEN
        RAISE EXCEPTION 'Os dados da caução não mudam e o capturado não diminui (billing.card_pre_authorization)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER card_pre_authorization_guarded
    BEFORE UPDATE
    ON billing.card_pre_authorization
    FOR EACH ROW
EXECUTE FUNCTION billing.check_card_pre_authorization_update();

-- A liberação da sobra: uma por caução, o que restava autorizado (o autorizado menos o capturado), na mesma conta.
CREATE TABLE billing.card_pre_authorization_release
(
    id                        UUID                     NOT NULL,
    card_pre_authorization_id UUID                     NOT NULL,
    folio_id                  UUID                     NOT NULL,
    amount                    NUMERIC(14, 2)           NOT NULL,
    business_date             DATE                     NOT NULL,
    released_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    released_by               UUID                     NOT NULL,
    CONSTRAINT card_pre_authorization_release_pk PRIMARY KEY (id),
    CONSTRAINT card_pre_authorization_release_uk UNIQUE (card_pre_authorization_id),
    CONSTRAINT card_pre_authorization_release_fk FOREIGN KEY (card_pre_authorization_id, folio_id)
        REFERENCES billing.card_pre_authorization (id, folio_id),
    CONSTRAINT card_pre_authorization_release_amount_ck CHECK (amount > 0 AND amount <= 1000000)
);

-- Cada captura é um recebimento no cartão de crédito vinculado à caução, na mesma conta (nunca reembolso, depósito
-- nem com troco), com a bandeira e os últimos dígitos dela.
ALTER TABLE billing.payment
    ADD COLUMN card_pre_authorization_id UUID;

ALTER TABLE billing.payment
    ADD CONSTRAINT payment_card_pre_authorization_fk FOREIGN KEY (card_pre_authorization_id, folio_id)
        REFERENCES billing.card_pre_authorization (id, folio_id);

ALTER TABLE billing.payment
    ADD CONSTRAINT payment_capture_ck CHECK (card_pre_authorization_id IS NULL
        OR (payment_type = 'PAYMENT' AND method = 'CREDIT_CARD' AND NOT deposit AND tendered IS NULL));

CREATE INDEX payment_card_pre_authorization_idx ON billing.payment (card_pre_authorization_id)
    WHERE card_pre_authorization_id IS NOT NULL;

-- A captura repete o cartão da caução e nunca passa do autorizado nem acontece depois da liberação. A trava
-- consultiva por caução enfileira duas capturas (a aplicação já as enfileira pela trava da conta e da caução; esta
-- vale também para SQL direto).
CREATE FUNCTION billing.check_payment_capture() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pre           billing.card_pre_authorization%ROWTYPE;
    captured      NUMERIC(14, 2);
BEGIN
    IF NEW.card_pre_authorization_id IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('billing.card-pre-authorization:' || NEW.card_pre_authorization_id::text));
    SELECT * INTO pre FROM billing.card_pre_authorization WHERE id = NEW.card_pre_authorization_id;
    IF NEW.card_brand IS DISTINCT FROM pre.card_brand
        OR NEW.card_last4 IS DISTINCT FROM pre.card_last4
        OR EXISTS (SELECT 1
                   FROM billing.card_pre_authorization_release
                   WHERE card_pre_authorization_id = NEW.card_pre_authorization_id) THEN
        RAISE EXCEPTION 'Captura incoerente com a caução (billing.payment)'
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO captured
    FROM billing.payment
    WHERE card_pre_authorization_id = NEW.card_pre_authorization_id;
    IF captured + NEW.amount > pre.amount THEN
        RAISE EXCEPTION 'Captura acima do autorizado (billing.payment)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_capture_coherent
    BEFORE INSERT
    ON billing.payment
    FOR EACH ROW
EXECUTE FUNCTION billing.check_payment_capture();

-- A liberação libera exatamente o que restava autorizado.
CREATE FUNCTION billing.check_card_pre_authorization_release() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    pre           billing.card_pre_authorization%ROWTYPE;
BEGIN
    SELECT * INTO pre FROM billing.card_pre_authorization WHERE id = NEW.card_pre_authorization_id;
    IF NEW.amount <> pre.amount - pre.captured_amount THEN
        RAISE EXCEPTION 'Liberação diferente do que restava autorizado (billing.card_pre_authorization_release)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER card_pre_authorization_release_coherent
    BEFORE INSERT
    ON billing.card_pre_authorization_release
    FOR EACH ROW
EXECUTE FUNCTION billing.check_card_pre_authorization_release();

-- No commit, a linha da caução bate com os fatos: o capturado é a soma das capturas e a situação RELEASED existe
-- se e só se há a liberação. Conferido depois de qualquer mudança da caução, captura ou liberação (a aplicação grava
-- a caução e o fato na mesma transação).
CREATE FUNCTION billing.check_card_pre_authorization_facts() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    target        UUID;
    pre           billing.card_pre_authorization%ROWTYPE;
    captured      NUMERIC(14, 2);
    released      BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'card_pre_authorization' THEN
        target := NEW.id;
    ELSE
        target := NEW.card_pre_authorization_id;
    END IF;
    IF target IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO pre FROM billing.card_pre_authorization WHERE id = target;
    SELECT COALESCE(sum(amount), 0) INTO captured FROM billing.payment WHERE card_pre_authorization_id = target;
    SELECT EXISTS (SELECT 1 FROM billing.card_pre_authorization_release WHERE card_pre_authorization_id = target)
    INTO released;
    IF pre.captured_amount <> captured OR (pre.status = 'RELEASED') <> released THEN
        RAISE EXCEPTION 'Caução incoerente com as capturas e a liberação (billing.card_pre_authorization)'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER card_pre_authorization_facts
    AFTER INSERT OR UPDATE
    ON billing.card_pre_authorization
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION billing.check_card_pre_authorization_facts();

CREATE CONSTRAINT TRIGGER payment_capture_facts
    AFTER INSERT
    ON billing.payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION billing.check_card_pre_authorization_facts();

CREATE CONSTRAINT TRIGGER card_pre_authorization_release_facts
    AFTER INSERT
    ON billing.card_pre_authorization_release
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION billing.check_card_pre_authorization_facts();

-- A liberação nunca muda nem é apagada (RN-03), nem pelo dono: além do privilégio do papel da aplicação.
CREATE TRIGGER card_pre_authorization_release_immutable
    BEFORE UPDATE OR DELETE
    ON billing.card_pre_authorization_release
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_cash_record_change();

-- O ALTER DEFAULT PRIVILEGES da pasta deu DML às tabelas novas. A caução é mutável (a situação e o capturado; o
-- trigger recusa a exclusão); a liberação é append-only, só SELECT e INSERT.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE billing.card_pre_authorization_release FROM "${app_role}";

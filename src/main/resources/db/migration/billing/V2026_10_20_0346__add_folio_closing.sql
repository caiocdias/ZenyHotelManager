-- Encerramento e reabertura de contas (SPEC §5.10 RF-CTA-11, RN-06, RF-ACS-08, M3 E10; decisões do usuário em
-- 2026-10-20). A conta só é encerrada com saldo zero (a soma dos lançamentos menos o recebido) e sem caução ativa ou
-- parcialmente capturada ainda não vencida (a vencida não impede: o emissor já liberou o valor); a regra fica na
-- aplicação, com a linha da conta travada, porque a validade da caução se compara com o relógio da aplicação.
--
-- closed_on/closed_at/closed_by: o encerramento corrente (a data operacional, o instante e quem encerrou; closed_by
-- nulo = o sistema, pela BillingApi); a conta aberta não tem nenhum. reopened_on/reopened_at/reopened_by: a última
-- reabertura (fica depois de um novo encerramento). A reabertura é ação sensível (billing.folios.reopen, aprovação de
-- superior) e cada uma fica em billing.folio_reopening (append-only), com o motivo, o encerramento desfeito e a
-- aprovação.
ALTER TABLE billing.folio
    ADD COLUMN closed_on   DATE,
    ADD COLUMN closed_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN closed_by   UUID,
    ADD COLUMN reopened_on DATE,
    ADD COLUMN reopened_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN reopened_by UUID;

-- Uma conta marcada como encerrada antes desta migração (só por SQL manual: não havia encerramento pela aplicação)
-- recebe o instante da última mudança como o do encerramento.
UPDATE billing.folio
SET closed_on = GREATEST(opened_on, CAST(updated_at AT TIME ZONE 'UTC' AS DATE)),
    closed_at = updated_at
WHERE status = 'CLOSED';

ALTER TABLE billing.folio
    ADD CONSTRAINT folio_closing_ck CHECK (
        (status = 'OPEN' AND closed_on IS NULL AND closed_at IS NULL AND closed_by IS NULL)
            OR (status = 'CLOSED' AND closed_on IS NOT NULL AND closed_at IS NOT NULL AND closed_on >= opened_on));

ALTER TABLE billing.folio
    ADD CONSTRAINT folio_reopening_ck CHECK (
        (reopened_on IS NULL AND reopened_at IS NULL AND reopened_by IS NULL)
            OR (reopened_on IS NOT NULL AND reopened_at IS NOT NULL AND reopened_by IS NOT NULL
            AND reopened_on >= opened_on));

CREATE INDEX folio_status_idx ON billing.folio (status, id);

-- Cada reabertura (RF-CTA-11): o motivo informado por quem reabre (texto livre: fica aqui, fora da trilha e das
-- views report_*), o encerramento que ela desfez, a data operacional, o instante, quem reabriu e a aprovação de
-- superior, se houve. Nunca muda nem é apagada.
CREATE TABLE billing.folio_reopening
(
    id            UUID                     NOT NULL,
    folio_id      UUID                     NOT NULL,
    reason        VARCHAR(500)             NOT NULL,
    closed_on     DATE                     NOT NULL,
    closed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    business_date DATE                     NOT NULL,
    reopened_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    reopened_by   UUID                     NOT NULL,
    approval_id   UUID,
    approved_by   UUID,
    CONSTRAINT folio_reopening_pk PRIMARY KEY (id),
    CONSTRAINT folio_reopening_folio_fk FOREIGN KEY (folio_id) REFERENCES billing.folio (id),
    CONSTRAINT folio_reopening_reason_ck CHECK (reason = btrim(reason) AND length(reason) BETWEEN 1 AND 500),
    CONSTRAINT folio_reopening_dates_ck CHECK (business_date >= closed_on),
    CONSTRAINT folio_reopening_approval_ck CHECK ((approval_id IS NULL) = (approved_by IS NULL))
);

CREATE INDEX folio_reopening_folio_idx ON billing.folio_reopening (folio_id, reopened_at, id);

CREATE FUNCTION billing.refuse_reopening_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Reaberturas de conta (billing.folio_reopening) são imutáveis'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER folio_reopening_immutable
    BEFORE UPDATE OR DELETE
    ON billing.folio_reopening
    FOR EACH ROW
EXECUTE FUNCTION billing.refuse_reopening_change();

-- Conta encerrada não recebe lançamento, pagamento nem caução, por nenhum caminho (a aplicação já recusa com
-- 409 FOLIO_CLOSED, com a conta travada; o banco repete a regra para SQL direto). A liberação de uma caução (que não
-- muda o saldo) continua valendo com a conta encerrada (decisão do usuário na E09).
CREATE FUNCTION billing.require_open_folio() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM billing.folio f WHERE f.id = NEW.folio_id AND f.status = 'OPEN') THEN
        RAISE EXCEPTION 'A conta % está encerrada (billing.%)', NEW.folio_id, TG_TABLE_NAME
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_requires_open_folio
    BEFORE INSERT
    ON billing.posting
    FOR EACH ROW
EXECUTE FUNCTION billing.require_open_folio();

CREATE TRIGGER payment_requires_open_folio
    BEFORE INSERT
    ON billing.payment
    FOR EACH ROW
EXECUTE FUNCTION billing.require_open_folio();

CREATE TRIGGER card_pre_authorization_requires_open_folio
    BEFORE INSERT
    ON billing.card_pre_authorization
    FOR EACH ROW
EXECUTE FUNCTION billing.require_open_folio();

-- O ALTER DEFAULT PRIVILEGES da pasta deu DML à tabela nova: a reabertura é append-only, só SELECT e INSERT.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE billing.folio_reopening FROM "${app_role}";

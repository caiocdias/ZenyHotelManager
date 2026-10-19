-- Taxa de serviço (SPEC §5.10 RF-CTA-14, RN-02, RN-10, H4, H5, M3 E06; decisões do usuário em 2026-10-19).
-- Nada muda num lançamento já feito: a taxa é um lançamento novo e separado (SERVICE_CHARGE), no código TX-SERVICO,
-- na mesma conta do item e vinculado a ele, com o percentual aplicado guardado. Ela acompanha o item: as correções e
-- as saídas da taxa feitas junto das do item citam o lançamento do item que as provocou (triggered_by_id); à parte,
-- a taxa só sai pela remoção a pedido do cliente (um estorno sem triggered_by_id). billing.posting continua só com
-- SELECT e INSERT para o papel da aplicação.

-- service_charge_for_id: o item da taxa, na mesma conta (o lançamento normal, ou, na entrada da taxa de um item
-- transferido, a entrada do item); service_charge_rate: o percentual aplicado (0 < r <= 100), para o extrato e o
-- relatório não recalcularem com o padrão novo do hotel; triggered_by_id: a correção ou a saída do item, na mesma
-- conta, que a correção ou a saída da taxa acompanha.
ALTER TABLE billing.posting
    ADD COLUMN service_charge_for_id UUID,
    ADD COLUMN service_charge_rate   NUMERIC(7, 4),
    ADD COLUMN triggered_by_id       UUID;

ALTER TABLE billing.posting
    ADD CONSTRAINT posting_service_charge_for_fk FOREIGN KEY (service_charge_for_id, folio_id)
        REFERENCES billing.posting (id, folio_id),
    ADD CONSTRAINT posting_triggered_by_fk FOREIGN KEY (triggered_by_id, folio_id)
        REFERENCES billing.posting (id, folio_id);

ALTER TABLE billing.posting
    DROP CONSTRAINT posting_type_ck;

ALTER TABLE billing.posting
    ADD CONSTRAINT posting_type_ck CHECK (posting_type IN
        ('REGULAR', 'REVERSAL', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN', 'SERVICE_CHARGE')),
    -- A taxa: positiva, quantidade 1, valor unitário = valor, sem motivo, correção, transferência, roteamento nem
    -- aprovação.
    ADD CONSTRAINT posting_service_charge_ck CHECK (posting_type <> 'SERVICE_CHARGE' OR
        (corrects_id IS NULL AND reason IS NULL AND transferred_from_id IS NULL AND quantity = 1
            AND unit_price = amount AND amount > 0 AND approval_id IS NULL AND routed_from_folio_id IS NULL
            AND service_charge_for_id IS NOT NULL)),
    -- O vínculo e o percentual andam juntos, só na taxa e na entrada da taxa de um item transferido.
    ADD CONSTRAINT posting_service_charge_link_ck CHECK ((service_charge_for_id IS NULL) = (service_charge_rate IS NULL)
        AND (service_charge_for_id IS NULL OR posting_type IN ('SERVICE_CHARGE', 'TRANSFER_IN'))
        AND (service_charge_rate IS NULL OR (service_charge_rate > 0 AND service_charge_rate <= 100))),
    -- Só uma correção ou uma saída acompanha um lançamento do item.
    ADD CONSTRAINT posting_triggered_by_ck CHECK (triggered_by_id IS NULL OR
        posting_type IN ('REVERSAL', 'ADJUSTMENT', 'TRANSFER_OUT'));

-- Um item tem no máximo uma taxa na conta dele.
CREATE UNIQUE INDEX posting_service_charge_uk ON billing.posting (service_charge_for_id)
    WHERE service_charge_for_id IS NOT NULL;

CREATE INDEX posting_triggered_by_idx ON billing.posting (triggered_by_id) WHERE triggered_by_id IS NOT NULL;

-- A coerência que os CHECK não alcançam (conferida na inserção):
-- - a taxa cita um lançamento normal que não é taxa, e a entrada da taxa cita a entrada do item; as duas no código de
--   tipo SERVICE_CHARGE; a entrada da taxa repete o percentual da taxa transferida, a entrada de uma taxa
--   transferida sempre leva o vínculo, e o vínculo é a entrada do item do mesmo par (a entrada cuja saída é a que a
--   saída da taxa acompanha);
-- - a correção ou a saída de uma taxa acompanha a do item, do mesmo tipo (o estorno do item, o ajuste do item ou a
--   saída do item), salvo a remoção a pedido do cliente: um estorno sem triggered_by_id. Só a taxa acompanha.
CREATE FUNCTION billing.check_service_charge() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    item      billing.posting%ROWTYPE;
    charge    billing.posting%ROWTYPE;
    moved     billing.posting%ROWTYPE;
    moved_out billing.posting%ROWTYPE;
    follows   billing.posting%ROWTYPE;
    code_kind VARCHAR(20);
BEGIN
    IF NEW.posting_type = 'TRANSFER_IN' THEN
        SELECT c.* INTO moved
        FROM billing.posting o
                 JOIN billing.posting c ON c.id = o.corrects_id
        WHERE o.id = NEW.transferred_from_id;
        IF (moved.service_charge_for_id IS NULL) <> (NEW.service_charge_for_id IS NULL)
            OR moved.service_charge_rate IS DISTINCT FROM NEW.service_charge_rate THEN
            RAISE EXCEPTION 'A entrada de uma taxa de serviço transferida cita a entrada do item, com o mesmo percentual'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.service_charge_for_id IS NOT NULL THEN
            SELECT * INTO moved_out FROM billing.posting WHERE id = NEW.transferred_from_id;
            IF moved_out.triggered_by_id IS NULL OR NOT EXISTS (SELECT 1
                                                                FROM billing.posting i
                                                                WHERE i.id = NEW.service_charge_for_id
                                                                  AND i.transferred_from_id = moved_out.triggered_by_id) THEN
                RAISE EXCEPTION 'A entrada da taxa de serviço transferida cita a entrada do item do mesmo par'
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    IF NEW.service_charge_for_id IS NOT NULL THEN
        SELECT * INTO item FROM billing.posting WHERE id = NEW.service_charge_for_id;
        SELECT kind INTO code_kind FROM billing.transaction_code WHERE id = NEW.transaction_code_id;
        IF item.posting_type IS DISTINCT FROM
           (CASE NEW.posting_type WHEN 'SERVICE_CHARGE' THEN 'REGULAR' ELSE 'TRANSFER_IN' END)
            OR item.service_charge_for_id IS NOT NULL OR code_kind IS DISTINCT FROM 'SERVICE_CHARGE' THEN
            RAISE EXCEPTION 'A taxa de serviço cita um item (lançamento normal ou entrada), no código de taxa de serviço'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.corrects_id IS NOT NULL THEN
        SELECT * INTO charge FROM billing.posting WHERE id = NEW.corrects_id;
        IF charge.service_charge_for_id IS NOT NULL THEN
            IF NEW.triggered_by_id IS NULL THEN
                IF NEW.posting_type <> 'REVERSAL' THEN
                    RAISE EXCEPTION 'A taxa de serviço só é ajustada ou transferida junto do item'
                        USING ERRCODE = 'check_violation';
                END IF;
            ELSE
                SELECT * INTO follows FROM billing.posting WHERE id = NEW.triggered_by_id;
                IF follows.corrects_id IS DISTINCT FROM charge.service_charge_for_id
                    OR follows.posting_type <> NEW.posting_type THEN
                    RAISE EXCEPTION 'A correção da taxa de serviço acompanha a do item, do mesmo tipo'
                        USING ERRCODE = 'check_violation';
                END IF;
            END IF;
        ELSIF NEW.triggered_by_id IS NOT NULL THEN
            RAISE EXCEPTION 'Só a correção de uma taxa de serviço acompanha a do item'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_service_charge_coherent
    BEFORE INSERT
    ON billing.posting
    FOR EACH ROW
EXECUTE FUNCTION billing.check_service_charge();

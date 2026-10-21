-- Pré-reserva, garantia e expiração (M4 E09; RF-RES-08 no recorte manual, RN-23, RF-TAR-07, SPEC §8.4).
--
-- reservation ganha a garantia registrada na confirmação da pré-reserva ("garantia recebida"): o meio (CARD, o cartão
-- informado pelo hóspede, sem dado do cartão, até o M7; DEPOSIT, o depósito pago na conta da reserva e conferido pela
-- BillingApi), quando e por quem e, no depósito, o exigido pela política copiada e o total líquido de depósitos da conta
-- naquele momento (que o cobria). Só fora da pré-reserva (a confirmação a tira de PROVISIONAL na mesma escrita); uma
-- reserva confirmada, cancelada depois ou reativada guarda a garantia que recebeu. A reserva confirmada sem garantia
-- (a política exige uma) a recebe depois pela mesma confirmação, sem mudar a situação.
--
-- O motivo de cancelamento EXPIRED (a pré-reserva venceu e o job de expiração a cancelou, sem multa) cabe na coluna
-- cancellation_reason, que não tem CHECK de valores.
--
-- reservation_history aceita os tipos CONFIRM (a confirmação da pré-reserva) e GUARANTEE (a garantia registrada numa
-- reserva confirmada sem ela: a política copiada exige uma e nenhuma foi registrada, como depois da troca para um plano
-- com garantia ou da reativação de uma pré-reserva expirada).

ALTER TABLE reservations.reservation
    ADD COLUMN guarantee_method           varchar(20),
    ADD COLUMN guaranteed_at              timestamptz,
    ADD COLUMN guaranteed_by              uuid,
    ADD COLUMN guarantee_deposit_required numeric(14, 2),
    ADD COLUMN guarantee_deposit_received numeric(14, 2);

ALTER TABLE reservations.reservation
    ADD CONSTRAINT reservation_guarantee_received_ck CHECK (
        (guarantee_method IS NULL AND guaranteed_at IS NULL AND guaranteed_by IS NULL
            AND guarantee_deposit_required IS NULL AND guarantee_deposit_received IS NULL)
        OR (guarantee_method = 'CARD' AND guaranteed_at IS NOT NULL AND status <> 'PROVISIONAL'
            AND guarantee_deposit_required IS NULL AND guarantee_deposit_received IS NULL)
        OR (guarantee_method = 'DEPOSIT' AND guaranteed_at IS NOT NULL AND status <> 'PROVISIONAL'
            AND guarantee_deposit_required >= 0
            AND guarantee_deposit_received >= guarantee_deposit_required)
    );

-- O job de expiração e a lista das garantias pendentes leem as pré-reservas pela validade.
CREATE INDEX reservation_provisional_idx
    ON reservations.reservation (provisional_until, id) WHERE status = 'PROVISIONAL';

ALTER TABLE reservations.reservation_history DROP CONSTRAINT reservation_history_kind_ck;
ALTER TABLE reservations.reservation_history
    ADD CONSTRAINT reservation_history_kind_ck
        CHECK (kind IN ('CHANGE', 'CANCEL', 'REACTIVATE', 'CONFIRM', 'GUARANTEE'));

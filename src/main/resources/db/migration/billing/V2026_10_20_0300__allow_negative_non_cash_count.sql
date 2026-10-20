-- Contagem do fechamento com valor negativo fora do dinheiro (revisão humana da E08, decisão do usuário em
-- 2026-10-19): um reembolso no cartão, Pix ou transferência num turno sem recebimento nessa forma deixa o esperado
-- negativo, e o relatório da maquininha ou do banco mostra o líquido. O dinheiro continua de 0 para cima (a gaveta não
-- tem valor negativo); nas demais formas, de -1.000.000,00 a 1.000.000,00 (o mesmo teto absoluto da aplicação).
ALTER TABLE billing.cashier_shift_count
    DROP CONSTRAINT cashier_shift_count_declared_ck;

ALTER TABLE billing.cashier_shift_count
    ADD CONSTRAINT cashier_shift_count_declared_ck CHECK (
        (method = 'CASH' AND declared >= 0 AND declared <= 1000000)
            OR (method <> 'CASH' AND declared >= -1000000 AND declared <= 1000000));

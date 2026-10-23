-- Chave de origem do lançamento (M5 E04, H14): o módulo que lança por um fato (o check-out da recepção, e o
-- fechamento do dia no M6, para as diárias) manda uma chave estável, e repetir o pedido devolve o lançamento já
-- gravado em vez de lançar de novo. A chave leva só ids, datas e o tipo da cobrança, nunca dado pessoal. Lançamento
-- sem chave (o lançamento manual, as correções e as transferências) nunca é deduplicado. O trigger de imutabilidade
-- do lançamento continua valendo: a coluna só é preenchida no INSERT.
ALTER TABLE billing.posting
    ADD COLUMN source_reference VARCHAR(200),
    ADD CONSTRAINT posting_source_reference_ck CHECK (
        source_reference IS NULL OR source_reference ~ '^[A-Za-z0-9._:-]{1,200}$');

CREATE UNIQUE INDEX posting_source_reference_uk ON billing.posting (source_reference)
    WHERE source_reference IS NOT NULL;

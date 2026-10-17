-- Mesclagem de perfis duplicados (RF-HOS-05, H6 do M3; M3 E02). O perfil absorvido nunca é apagado (o
-- trigger guests.refuse_delete continua recusando): fica com a situação MERGED e a referência ao sobrevivente
-- (merged_into), com quando e quem mesclou. Os documentos que o sobrevivente não tinha passam para ele (a linha
-- é a mesma, só muda guest_id); os iguais aos dele (mesmo índice cego) ficam no absorvido. Um perfil mesclado
-- não é editado, não volta nas buscas e não é mesclado de novo (nem como sobrevivente); quem guarda o id de um
-- hóspede escuta o evento GuestsMerged, publicado pelo outbox na transação da mesclagem.
ALTER TABLE guests.guest
    ADD COLUMN status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN merged_into UUID,
    ADD COLUMN merged_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN merged_by   UUID;

-- A aplicação sempre grava a situação; o padrão só serviu para as linhas existentes.
ALTER TABLE guests.guest
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE guests.guest
    ADD CONSTRAINT guest_merged_into_fk FOREIGN KEY (merged_into) REFERENCES guests.guest (id),
    ADD CONSTRAINT guest_status_ck CHECK (
        (status = 'ACTIVE' AND merged_into IS NULL AND merged_at IS NULL AND merged_by IS NULL)
        OR (status = 'MERGED' AND merged_into IS NOT NULL AND merged_into <> id AND merged_at IS NOT NULL));

CREATE INDEX guest_merged_into_idx ON guests.guest (merged_into) WHERE merged_into IS NOT NULL;

-- O número legível de uma ordem de serviço é OS-<ano>-<sequência de 6 ou mais dígitos> (DocumentNumber); a listagem lê
-- esse formato, então dado fora dele não entra mais.
ALTER TABLE maintenance.work_order
    ADD CONSTRAINT work_order_number_format_ck CHECK (number ~ '^OS-[0-9]{4}-[0-9]{6,}$');

-- O ano do número legível vai de 2000 a 9999, como no DocumentNumber (a V2026_11_19_2100 aceitava qualquer 4 dígitos).
ALTER TABLE maintenance.work_order
    DROP CONSTRAINT work_order_number_format_ck;
ALTER TABLE maintenance.work_order
    ADD CONSTRAINT work_order_number_format_ck
        CHECK (number ~ '^OS-(20[0-9]{2}|2[1-9][0-9]{2}|[3-9][0-9]{3})-[0-9]{6,}$');

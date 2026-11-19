-- Privilégios do papel da aplicação nos achados e perdidos (ARCHITECTURE §11): leitura, inclusão e alteração (o
-- destino grava a devolução, o descarte ou a doação), sem DELETE (o item nunca é apagado; o trigger
-- housekeeping.refuse_delete também recusa) e sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT, UPDATE ON housekeeping.lost_and_found_item TO "${app_role}";

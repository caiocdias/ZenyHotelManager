-- Privilégios do papel da aplicação nas faxinas programadas (ARCHITECTURE §11): leitura, inclusão e alteração, sem
-- DELETE (a faxina só se desativa; o trigger housekeeping.refuse_delete também recusa) e sem DDL. O placeholder
-- app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT, UPDATE ON housekeeping.scheduled_cleaning TO "${app_role}";

-- Privilégios do papel da aplicação no schema payments (H4 do roadmap do M1, ARCHITECTURE §11): DML em todas as
-- tabelas, sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME). A exclusão física das
-- intenções é recusada pelo trigger payments.refuse_delete.
GRANT USAGE ON SCHEMA payments TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA payments TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA payments TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema payments.
ALTER DEFAULT PRIVILEGES IN SCHEMA payments GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA payments GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

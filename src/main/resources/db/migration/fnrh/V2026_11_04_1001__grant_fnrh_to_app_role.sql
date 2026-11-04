-- Privilégios do papel da aplicação no schema fnrh (ARCHITECTURE §11): DML em todas as tabelas, sem DDL. O
-- placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA fnrh TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fnrh TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA fnrh TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema fnrh.
ALTER DEFAULT PRIVILEGES IN SCHEMA fnrh GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA fnrh GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

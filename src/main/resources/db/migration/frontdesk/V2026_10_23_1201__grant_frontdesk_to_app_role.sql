-- Privilégios do papel da aplicação no schema frontdesk (ARCHITECTURE §11): DML em todas as tabelas, sem DDL. O
-- placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA frontdesk TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA frontdesk TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA frontdesk TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema frontdesk.
ALTER DEFAULT PRIVILEGES IN SCHEMA frontdesk GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA frontdesk GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

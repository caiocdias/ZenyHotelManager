-- Privilégios do papel da aplicação no schema reservations (H4 do roadmap do M1, ARCHITECTURE §11): DML em todas as
-- tabelas, sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA reservations TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reservations TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reservations TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema reservations.
ALTER DEFAULT PRIVILEGES IN SCHEMA reservations GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA reservations GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

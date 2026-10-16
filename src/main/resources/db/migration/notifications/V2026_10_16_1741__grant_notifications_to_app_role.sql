-- Privilégios do papel da aplicação no schema notifications (H4 do roadmap do M1, ARCHITECTURE §11): DML em
-- todas as tabelas, sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME). O registro dos
-- envios (push_delivery) é append-only: só SELECT e INSERT, declarado no bean AppendOnlyTables do módulo, que a
-- partida confere.
GRANT USAGE ON SCHEMA notifications TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA notifications TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA notifications TO "${app_role}";

REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE notifications.push_delivery FROM "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema notifications.
ALTER DEFAULT PRIVILEGES IN SCHEMA notifications GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA notifications GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

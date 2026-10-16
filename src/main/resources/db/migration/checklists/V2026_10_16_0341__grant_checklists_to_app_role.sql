-- Privilégios do papel da aplicação no schema checklists (H4 do roadmap do M1, ARCHITECTURE §11): DML em
-- todas as tabelas, sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME). As versões
-- publicadas são append-only (RF-CHK-06): só SELECT e INSERT, declaradas no bean AppendOnlyTables do módulo,
-- que a partida confere.
GRANT USAGE ON SCHEMA checklists TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA checklists TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA checklists TO "${app_role}";

REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE checklists.checklist_version FROM "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema checklists.
ALTER DEFAULT PRIVILEGES IN SCHEMA checklists GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA checklists GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

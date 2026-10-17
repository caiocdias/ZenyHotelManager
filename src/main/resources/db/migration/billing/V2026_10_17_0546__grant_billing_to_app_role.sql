-- Privilégios do papel da aplicação no schema billing (H4 do roadmap do M1, ARCHITECTURE §11): DML em todas as
-- tabelas, sem DDL. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME). Os lançamentos
-- (billing.posting) são append-only (RN-03): só SELECT e INSERT, declarados no bean AppendOnlyTables do módulo, que
-- a partida confere. A exclusão física de códigos e contas é recusada pelo trigger billing.refuse_delete.
GRANT USAGE ON SCHEMA billing TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA billing TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA billing TO "${app_role}";

REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE billing.posting FROM "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema billing (uma tabela append-only nova revoga o
-- que não pode na própria migração).
ALTER DEFAULT PRIVILEGES IN SCHEMA billing GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA billing GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

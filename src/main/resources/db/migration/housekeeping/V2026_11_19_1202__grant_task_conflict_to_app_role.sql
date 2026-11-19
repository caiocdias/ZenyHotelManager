-- Privilégios do papel da aplicação no registro de conflitos (ARCHITECTURE §11): só SELECT e INSERT, porque a tabela é
-- só de inserção (declarada no bean AppendOnlyTables do módulo, que a partida confere). O ALTER DEFAULT PRIVILEGES do
-- schema deu também UPDATE; ele sai aqui. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT SELECT, INSERT ON housekeeping.task_conflict TO "${app_role}";

REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE housekeeping.task_conflict FROM "${app_role}";

-- Privilégios do papel da aplicação no schema endofday (ARCHITECTURE §11): leitura, inclusão e alteração, sem DELETE
-- (as execuções, etapas e pendências são o histórico do fechamento) e sem DDL. O placeholder app_role é o papel da
-- aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA endofday TO "${app_role}";

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA endofday TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA endofday TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema endofday.
ALTER DEFAULT PRIVILEGES IN SCHEMA endofday GRANT SELECT, INSERT, UPDATE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA endofday GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

-- Apoio só de teste (não vai para produção): o papel da aplicação (placeholder app_role) usa as
-- tabelas de apoio dos testes como as dos módulos, só com DML (H4 do roadmap do M1).
GRANT USAGE ON SCHEMA test_support TO "${app_role}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA test_support TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA test_support TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA test_support GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA test_support GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

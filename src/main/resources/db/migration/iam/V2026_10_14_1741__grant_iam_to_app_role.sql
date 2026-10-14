-- Privilégios do papel da aplicação no schema iam (H4 do roadmap do M1, ARCHITECTURE §11): DML
-- em todas as tabelas e uso das sequences (inclusive a da versão de autorização, que os triggers
-- avançam com o papel de quem escreve), sem DDL. As funções dos triggers já são executáveis por
-- todos (padrão do PostgreSQL). O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA iam TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA iam TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA iam TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema iam.
ALTER DEFAULT PRIVILEGES IN SCHEMA iam GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA iam GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

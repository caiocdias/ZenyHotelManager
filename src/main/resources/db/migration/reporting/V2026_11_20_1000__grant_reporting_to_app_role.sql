-- Privilégios do papel da aplicação no schema reporting (ARCHITECTURE §11; M6 E23): o schema nasce com a primeira
-- migração do módulo, e as tabelas do reporting são só de inserção (o snapshot diário e o índice dos PDFs arquivados
-- nunca são alterados nem apagados). O ALTER DEFAULT PRIVILEGES abaixo vale para as tabelas criadas daqui em diante
-- pelo dono no schema: só SELECT e INSERT. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA reporting TO "${app_role}";

ALTER DEFAULT PRIVILEGES IN SCHEMA reporting GRANT SELECT, INSERT ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA reporting GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

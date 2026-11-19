-- Privilégios do papel da aplicação no schema maintenance (ARCHITECTURE §11): leitura, inclusão e alteração, sem DDL e
-- sem DELETE (a ordem de serviço é cancelada, nunca apagada). O placeholder app_role é o papel da aplicação
-- (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA maintenance TO "${app_role}";

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA maintenance TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA maintenance TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema maintenance: sem DELETE, que cada migração concede
-- só onde precisar.
ALTER DEFAULT PRIVILEGES IN SCHEMA maintenance GRANT SELECT, INSERT, UPDATE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA maintenance GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

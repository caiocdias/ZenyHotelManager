-- Privilégios do papel da aplicação no schema housekeeping (ARCHITECTURE §11): leitura, inclusão e alteração, sem DDL.
-- Sem DELETE nos tipos de limpeza, que só se desativam (o trigger housekeeping.refuse_delete também recusa); DELETE só
-- nos créditos, que a edição do tipo substitui. O placeholder app_role é o papel da aplicação (ZENY_DB_USERNAME).
GRANT USAGE ON SCHEMA housekeeping TO "${app_role}";

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA housekeeping TO "${app_role}";
GRANT DELETE ON housekeeping.cleaning_type_credit TO "${app_role}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA housekeeping TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema housekeeping: sem DELETE, que cada migração concede
-- só onde precisar.
ALTER DEFAULT PRIVILEGES IN SCHEMA housekeeping GRANT SELECT, INSERT, UPDATE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA housekeeping GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

-- Privilégios do papel da aplicação no schema shared (H4 do roadmap do M1, ARCHITECTURE §11).
-- As migrações rodam com o dono dos schemas (Flyway, ZENY_DB_MIGRATION_*); a aplicação conecta
-- com o papel restrito, criado fora das migrações (docker/postgres/initdb/), cujo nome chega pelo
-- placeholder app_role (spring.flyway.placeholders, igual a ZENY_DB_USERNAME). O papel não cria,
-- altera nem remove objetos: só lê e escreve linhas. O histórico do Flyway
-- (shared.flyway_schema_history) fica fora, só com o dono.
GRANT USAGE ON SCHEMA shared TO "${app_role}";

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE shared.event_publication, shared.shedlock, shared.idempotency_key
    TO "${app_role}";

-- Tabelas e sequences criadas daqui em diante pelo dono no schema shared.
ALTER DEFAULT PRIVILEGES IN SCHEMA shared GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA shared GRANT USAGE, SELECT ON SEQUENCES TO "${app_role}";

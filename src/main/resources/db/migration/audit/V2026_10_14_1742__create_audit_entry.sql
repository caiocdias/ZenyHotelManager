-- Trilha de auditoria append-only (RF-ACS-11, RNF-11, ARCHITECTURE §5 e §8.3). Uma linha por
-- entidade mudada por um caso de uso @Audited, gravada na mesma transação dele: quem (usuário e
-- sessão de dispositivo; nulos = o sistema), quando (UTC), a ação, o resultado, a entidade, os
-- retratos antes e depois (JSON, com os campos sensíveis já mascarados) e a aprovação de
-- superior associada (M1 E05). Sem FK para outros schemas (ARCHITECTURE §11).
CREATE TABLE audit.audit_entry
(
    id                UUID                     NOT NULL,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id           UUID,
    device_session_id UUID,
    action            VARCHAR(100)             NOT NULL,
    outcome           VARCHAR(16)              NOT NULL,
    error_code        VARCHAR(100),
    entity_type       VARCHAR(100),
    entity_id         VARCHAR(200),
    before_state      JSONB,
    after_state       JSONB,
    approval_id       UUID,
    CONSTRAINT audit_entry_pk PRIMARY KEY (id),
    CONSTRAINT audit_entry_outcome_ck CHECK (outcome IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT audit_entry_error_code_ck CHECK ((outcome = 'REJECTED') = (error_code IS NOT NULL)),
    CONSTRAINT audit_entry_entity_ck CHECK ((entity_type IS NULL) = (entity_id IS NULL))
);

-- Consulta (GET /api/v1/audit-entries): mais recentes primeiro, com filtros por usuário, por
-- entidade e por período; o id (UUIDv7) desempata.
CREATE INDEX audit_entry_occurred_at_idx ON audit.audit_entry (occurred_at DESC, id DESC);
CREATE INDEX audit_entry_user_idx ON audit.audit_entry (user_id, occurred_at DESC, id DESC);
CREATE INDEX audit_entry_entity_idx ON audit.audit_entry (entity_type, entity_id, occurred_at DESC, id DESC);

-- Imutável para a aplicação (H4): o papel da aplicação (placeholder app_role = ZENY_DB_USERNAME)
-- só lê e insere; UPDATE, DELETE e TRUNCATE ficam só com o dono dos schemas. A partida da
-- aplicação confere isso (DatabaseRoleGuard, a partir das AppendOnlyTables do módulo audit).
GRANT USAGE ON SCHEMA audit TO "${app_role}";
GRANT SELECT, INSERT ON TABLE audit.audit_entry TO "${app_role}";
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE audit.audit_entry FROM "${app_role}";

-- Tabelas criadas daqui em diante no schema audit também nascem append-only para a aplicação.
ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT, INSERT ON TABLES TO "${app_role}";

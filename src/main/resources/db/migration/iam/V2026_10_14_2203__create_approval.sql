-- Aprovação de superior (step-up, SPEC §8.2.1, RF-ACS-07 e 08, ARCHITECTURE §6.3).

-- Bloqueio progressivo do PIN nas aprovações, separado do bloqueio do login: falhas seguidas e
-- até quando as aprovações do usuário ficam recusadas.
ALTER TABLE iam.user_account
    ADD COLUMN failed_pin_count INT NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT user_account_failed_pin_count_ck CHECK (failed_pin_count >= 0);

-- Uma aprovação: o aprovador (login e PIN conferidos, com a permissão) autoriza o solicitante, na
-- sessão de dispositivo dele, a fazer uma operação (ação, permissão, recurso e, acima do limite,
-- chave e valor) pelo motivo dado, por 2 minutos. Do token, só o SHA-256 em hexadecimal.
-- Imutável: as entradas de auditoria a referenciam (audit.audit_entry.approval_id).
CREATE TABLE iam.approval
(
    id                   UUID                     NOT NULL,
    action               VARCHAR(100)             NOT NULL,
    permission           VARCHAR(100)             NOT NULL,
    resource_ref         VARCHAR(200)             NOT NULL,
    limit_key            VARCHAR(100),
    limit_amount         NUMERIC,
    reason               VARCHAR(500)             NOT NULL,
    requester_id         UUID                     NOT NULL,
    requester_session_id UUID                     NOT NULL,
    approver_id          UUID                     NOT NULL,
    approved_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    token_hash           VARCHAR(64)              NOT NULL,
    CONSTRAINT approval_pk PRIMARY KEY (id),
    CONSTRAINT approval_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT approval_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT approval_requester_fk FOREIGN KEY (requester_id) REFERENCES iam.user_account (id),
    CONSTRAINT approval_approver_fk FOREIGN KEY (approver_id) REFERENCES iam.user_account (id),
    CONSTRAINT approval_not_self_ck CHECK (approver_id <> requester_id),
    CONSTRAINT approval_limit_ck CHECK ((limit_key IS NULL) = (limit_amount IS NULL)),
    CONSTRAINT approval_limit_amount_ck CHECK (limit_amount IS NULL OR limit_amount >= 0),
    -- Nunca vale mais que 2 minutos.
    CONSTRAINT approval_validity_ck CHECK (expires_at > approved_at AND expires_at <= approved_at + INTERVAL '2 minutes')
);

CREATE INDEX approval_requester_idx ON iam.approval (requester_id, approved_at);
CREATE INDEX approval_approver_idx ON iam.approval (approver_id, approved_at);

-- O uso (único) de cada aprovação: a chave primária garante que ela é consumida uma vez só, mesmo
-- com requisições simultâneas (INSERT … ON CONFLICT DO NOTHING). Uma linha só existe se a
-- transação da ação aprovada confirmou.
CREATE TABLE iam.approval_use
(
    approval_id       UUID                     NOT NULL,
    used_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id           UUID                     NOT NULL,
    device_session_id UUID                     NOT NULL,
    CONSTRAINT approval_use_pk PRIMARY KEY (approval_id),
    CONSTRAINT approval_use_approval_fk FOREIGN KEY (approval_id) REFERENCES iam.approval (id)
);

-- Append-only para a aplicação (H4, como a trilha de auditoria): o papel da aplicação só lê e
-- insere nas duas tabelas (o padrão do schema iam daria também UPDATE e DELETE). A partida confere
-- isso pelas AppendOnlyTables do módulo iam.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE iam.approval FROM "${app_role}";
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE iam.approval_use FROM "${app_role}";
GRANT SELECT, INSERT ON TABLE iam.approval TO "${app_role}";
GRANT SELECT, INSERT ON TABLE iam.approval_use TO "${app_role}";

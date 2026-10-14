-- Usuários e sessões por dispositivo (SPEC §8.2.1, RF-ACS-01 e RF-ACS-10, ARCHITECTURE §8.1).
-- Senha e PIN só como hash Argon2id (formato PHC: $argon2id$v=19$m=…,t=…,p=…$sal$hash); o
-- refresh token só como SHA-256 em hexadecimal. Usuários nunca são apagados, só desativados
-- (RF-ACS-12).
CREATE TABLE iam.user_account
(
    id                       UUID                     NOT NULL,
    name                     VARCHAR(200)             NOT NULL,
    -- Login normalizado (minúsculo, ASCII): a comparação ignora maiúsculas e não há duas
    -- grafias para o mesmo usuário.
    login                    VARCHAR(100)             NOT NULL,
    password_hash            VARCHAR(255)             NOT NULL,
    pin_hash                 VARCHAR(255),
    active                   BOOLEAN                  NOT NULL,
    password_change_required BOOLEAN                  NOT NULL,
    -- Bloqueio progressivo: falhas seguidas de login e até quando o login fica recusado.
    failed_login_count       INT                      NOT NULL,
    locked_until             TIMESTAMP WITH TIME ZONE,
    created_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    version                  BIGINT                   NOT NULL,
    CONSTRAINT user_account_pk PRIMARY KEY (id),
    CONSTRAINT user_account_login_uk UNIQUE (login),
    CONSTRAINT user_account_login_ck CHECK (login ~ '^[a-z0-9][a-z0-9._@+-]{2,99}$'),
    CONSTRAINT user_account_password_hash_ck CHECK (password_hash LIKE '$argon2id$%'),
    CONSTRAINT user_account_pin_hash_ck CHECK (pin_hash IS NULL OR pin_hash LIKE '$argon2id$%'),
    CONSTRAINT user_account_failed_login_count_ck CHECK (failed_login_count >= 0)
);

-- Uma sessão por login num dispositivo. O refresh token vigente fica só como hash; cada uso o
-- troca por outro (rotação) e o anterior vai para rotated_refresh_token.
CREATE TABLE iam.device_session
(
    id                 UUID                     NOT NULL,
    user_id            UUID                     NOT NULL,
    device_id          VARCHAR(100)             NOT NULL,
    device_name        VARCHAR(100)             NOT NULL,
    refresh_token_hash VARCHAR(64)              NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Validade deslizante do refresh token (30 dias depois do último uso).
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at         TIMESTAMP WITH TIME ZONE,
    revocation_reason  VARCHAR(30),
    version            BIGINT                   NOT NULL,
    CONSTRAINT device_session_pk PRIMARY KEY (id),
    CONSTRAINT device_session_user_fk FOREIGN KEY (user_id) REFERENCES iam.user_account (id),
    CONSTRAINT device_session_refresh_token_hash_uk UNIQUE (refresh_token_hash),
    CONSTRAINT device_session_refresh_token_hash_ck CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT device_session_revocation_ck CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);

-- No máximo uma sessão ativa (não revogada) por usuário e dispositivo: um novo login no mesmo
-- dispositivo revoga a anterior.
CREATE UNIQUE INDEX device_session_active_device_uk ON iam.device_session (user_id, device_id)
    WHERE revoked_at IS NULL;
CREATE INDEX device_session_user_idx ON iam.device_session (user_id, id);

-- Refresh tokens já trocados por outro. Reapresentar um deles é reuso (token roubado ou
-- vazado): a sessão inteira é revogada. Expurgados depois da validade do refresh token.
CREATE TABLE iam.rotated_refresh_token
(
    token_hash VARCHAR(64)              NOT NULL,
    session_id UUID                     NOT NULL,
    rotated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT rotated_refresh_token_pk PRIMARY KEY (token_hash),
    CONSTRAINT rotated_refresh_token_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rotated_refresh_token_session_fk FOREIGN KEY (session_id) REFERENCES iam.device_session (id)
);

CREATE INDEX rotated_refresh_token_rotated_at_idx ON iam.rotated_refresh_token (rotated_at);
CREATE INDEX rotated_refresh_token_session_idx ON iam.rotated_refresh_token (session_id);

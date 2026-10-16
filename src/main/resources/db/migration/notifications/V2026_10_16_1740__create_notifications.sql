-- Notificações internas e push (M2 E09, RF-NOT-01 e 02, SPEC §8.2.18, ARCHITECTURE §12). O destinatário, o
-- usuário das preferências e o dono do dispositivo são ids de usuário do iam, e a sessão do dispositivo é uma
-- sessão do iam: sem FK fora do schema. O token do FCM fica cifrado pela aplicação (FieldCipher, valor
-- v1:<chave>:…); a unicidade entre os ativos usa o SHA-256 dele.

CREATE TABLE notifications.notification
(
    id           uuid          NOT NULL,
    recipient_id uuid          NOT NULL,
    type         varchar(64)   NOT NULL,
    title        varchar(120)  NOT NULL,
    body         varchar(1000) NOT NULL,
    created_at   timestamptz   NOT NULL,
    read_at      timestamptz,
    version      bigint        NOT NULL,
    CONSTRAINT notification_pk PRIMARY KEY (id),
    CONSTRAINT notification_title_ck CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 120),
    CONSTRAINT notification_body_ck CHECK (body = btrim(body) AND length(body) BETWEEN 1 AND 1000),
    CONSTRAINT notification_read_ck CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT notification_version_ck CHECK (version >= 0)
);

-- O painel: as do destinatário, da mais nova para a mais antiga (o id é UUIDv7), e a contagem das não lidas.
CREATE INDEX notification_recipient_idx ON notifications.notification (recipient_id, id DESC);
CREATE INDEX notification_unread_idx ON notifications.notification (recipient_id) WHERE read_at IS NULL;

-- Preferências por usuário e tipo (RF-NOT-02); sem linha, painel e push ligados.
CREATE TABLE notifications.notification_preference
(
    user_id    uuid        NOT NULL,
    type       varchar(64) NOT NULL,
    in_app     boolean     NOT NULL,
    push       boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT notification_preference_pk PRIMARY KEY (user_id, type),
    -- Sem painel, nada é criado: o push ligado não teria o que enviar.
    CONSTRAINT notification_preference_push_ck CHECK (in_app OR NOT push)
);

-- Dispositivos de push, um por sessão de dispositivo do iam (SPEC §8.2.18: PushDevice → DeviceSession).
CREATE TABLE notifications.push_device
(
    id                  uuid        NOT NULL,
    user_id             uuid        NOT NULL,
    session_id          uuid        NOT NULL,
    platform            varchar(16) NOT NULL,
    token               text        NOT NULL,
    token_sha256        char(64)    NOT NULL,
    active              boolean     NOT NULL,
    created_at          timestamptz NOT NULL,
    deactivated_at      timestamptz,
    deactivation_reason varchar(20),
    version             bigint      NOT NULL,
    CONSTRAINT push_device_pk PRIMARY KEY (id),
    CONSTRAINT push_device_platform_ck CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    CONSTRAINT push_device_reason_ck CHECK (deactivation_reason IN ('USER', 'REPLACED', 'INVALID_TOKEN', 'SESSION_ENDED')),
    CONSTRAINT push_device_state_ck CHECK (
        (active AND deactivated_at IS NULL AND deactivation_reason IS NULL)
            OR (NOT active AND deactivated_at IS NOT NULL AND deactivation_reason IS NOT NULL)),
    CONSTRAINT push_device_version_ck CHECK (version >= 0)
);

-- Um token (um aparelho) e uma sessão têm no máximo um dispositivo ativo.
CREATE UNIQUE INDEX push_device_active_token_uk ON notifications.push_device (token_sha256) WHERE active;
CREATE UNIQUE INDEX push_device_active_session_uk ON notifications.push_device (session_id) WHERE active;
CREATE INDEX push_device_user_idx ON notifications.push_device (user_id, id DESC);

-- O que já foi feito com cada notificação em cada dispositivo: o envio reprocessado pelo outbox não manda de
-- novo a quem já recebeu (idempotência do listener). Append-only para o papel da aplicação.
CREATE TABLE notifications.push_delivery
(
    notification_id uuid        NOT NULL,
    device_id       uuid        NOT NULL,
    outcome         varchar(20) NOT NULL,
    attempted_at    timestamptz NOT NULL,
    CONSTRAINT push_delivery_pk PRIMARY KEY (notification_id, device_id),
    CONSTRAINT push_delivery_outcome_ck CHECK (outcome IN ('DELIVERED', 'INVALID_TOKEN'))
);

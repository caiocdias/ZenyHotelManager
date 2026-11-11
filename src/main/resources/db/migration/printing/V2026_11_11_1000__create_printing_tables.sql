-- Agentes, impressoras e fila de impressão (M5 E10, D-E10-2, D-E10-4, D-E10-5). O agente é o processo local que
-- imprime; só o SHA-256 do token dele é guardado (token_hash, em hex), e token_generation sobe a cada emissão. O
-- conteúdo do job é cifrado pela aplicação (ContentCipher) e apagado depois da retenção (content_purged_at). Sem chave
-- estrangeira para outro schema; delivered_to é o agente a que o job foi entregue, sem FK porque o agente pode ser
-- trocado na impressora sem apagar o histórico.

CREATE TABLE printing.print_agent (
    id               uuid         NOT NULL,
    name             text         NOT NULL,
    -- SHA-256 do token em hex minúsculo; nulo quando o agente está desativado.
    token_hash       char(64),
    token_generation int          NOT NULL DEFAULT 0,
    active           boolean      NOT NULL,
    last_seen_at     timestamptz,
    created_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL,
    CONSTRAINT print_agent_pk PRIMARY KEY (id),
    CONSTRAINT print_agent_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT print_agent_token_generation_ck CHECK (token_generation >= 0),
    -- A desativação apaga o hash: agente inativo não tem credencial.
    CONSTRAINT print_agent_inactive_no_token_ck CHECK (active OR token_hash IS NULL)
);

CREATE TABLE printing.printer (
    id       uuid        NOT NULL,
    name     text        NOT NULL,
    kind     varchar(20) NOT NULL,
    address  text        NOT NULL,
    agent_id uuid        NOT NULL,
    active   boolean     NOT NULL,
    version  bigint      NOT NULL,
    CONSTRAINT printer_pk PRIMARY KEY (id),
    CONSTRAINT printer_agent_fk FOREIGN KEY (agent_id) REFERENCES printing.print_agent (id),
    CONSTRAINT printer_kind_ck CHECK (kind IN ('THERMAL_ESCPOS', 'A4_PDF'))
);

CREATE INDEX printer_agent_idx ON printing.printer (agent_id);

-- A posição ordena a entrega e é o Last-Event-ID do SSE; um reenvio recebe posição nova.
CREATE SEQUENCE printing.print_job_position_seq;

CREATE TABLE printing.print_job (
    id                uuid        NOT NULL,
    printer_id        uuid        NOT NULL,
    document_type     text        NOT NULL,
    status            varchar(20) NOT NULL,
    position          bigint      NOT NULL,
    attempts          int         NOT NULL,
    -- Cifrado pela aplicação; NULL depois do expurgo.
    content           bytea,
    content_purged_at timestamptz,
    media_type        varchar(50) NOT NULL,
    created_at        timestamptz NOT NULL,
    sent_at           timestamptz,
    delivered_to      uuid,
    finished_at       timestamptz,
    -- Código fixo ([A-Z][A-Z0-9_]*), nunca texto livre.
    failure_code      varchar(50),
    version           bigint      NOT NULL,
    CONSTRAINT print_job_pk PRIMARY KEY (id),
    CONSTRAINT print_job_printer_fk FOREIGN KEY (printer_id) REFERENCES printing.printer (id),
    CONSTRAINT print_job_position_uk UNIQUE (position),
    CONSTRAINT print_job_status_ck CHECK (status IN ('PENDING', 'SENT', 'PRINTED', 'FAILED')),
    CONSTRAINT print_job_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT print_job_content_purge_ck CHECK (content IS NULL OR content_purged_at IS NULL),
    CONSTRAINT print_job_finished_ck CHECK ((status IN ('PRINTED', 'FAILED')) = (finished_at IS NOT NULL)),
    CONSTRAINT print_job_sent_ck CHECK (status <> 'SENT' OR (delivered_to IS NOT NULL AND sent_at IS NOT NULL))
);

-- A entrega: os PENDING de uma impressora na ordem da posição.
CREATE INDEX print_job_printer_queue_idx ON printing.print_job (printer_id, status, position);
-- O reenvio e o limite de tentativas dos SENT sem ack.
CREATE INDEX print_job_status_sent_idx ON printing.print_job (status, sent_at);
-- A validade dos PENDING.
CREATE INDEX print_job_status_created_idx ON printing.print_job (status, created_at);
-- O expurgo do conteúdo dos jobs terminados.
CREATE INDEX print_job_purge_idx ON printing.print_job (status, finished_at) WHERE content IS NOT NULL;

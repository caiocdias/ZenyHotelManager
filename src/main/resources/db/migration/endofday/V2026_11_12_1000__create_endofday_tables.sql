-- Execuções do fechamento do dia (M6 E10, RN-21): uma por data operacional (run_business_date_uk), nunca em paralelo;
-- as etapas em ordem de posição, cada uma idempotente; as pendências abertas pelas etapas. Sem DELETE para o papel da
-- aplicação: a execução, as etapas e as pendências são o histórico do fechamento. As FKs ficam dentro do schema.

CREATE TABLE endofday.run (
    id            uuid        NOT NULL,
    business_date date        NOT NULL,
    trigger       varchar(20) NOT NULL,
    status        varchar(20) NOT NULL,
    -- Quem disparou o fechamento manual; nulo no automático.
    triggered_by  uuid,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz,
    -- Muda a cada transição, inclusive de etapa, para a versão subir.
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL,
    CONSTRAINT run_pk PRIMARY KEY (id),
    CONSTRAINT run_business_date_uk UNIQUE (business_date),
    CONSTRAINT run_trigger_ck CHECK (trigger IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT run_status_ck CHECK (status IN ('RUNNING', 'FAILED', 'COMPLETED')),
    CONSTRAINT run_finished_ck CHECK ((status <> 'RUNNING') = (finished_at IS NOT NULL))
);

CREATE TABLE endofday.run_step (
    run_id      uuid         NOT NULL,
    -- Nome estável da etapa, como um código (kebab-case).
    name        varchar(60)  NOT NULL,
    position    int          NOT NULL,
    status      varchar(20)  NOT NULL,
    started_at  timestamptz,
    finished_at timestamptz,
    -- DONE, NOT_APPLICABLE, o código da falha ou UNREGISTERED; nunca texto livre.
    result      varchar(200),
    attempts    int          NOT NULL,
    CONSTRAINT run_step_pk PRIMARY KEY (run_id, name),
    CONSTRAINT run_step_run_fk FOREIGN KEY (run_id) REFERENCES endofday.run (id),
    CONSTRAINT run_step_position_uk UNIQUE (run_id, position),
    CONSTRAINT run_step_status_ck CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED')),
    CONSTRAINT run_step_attempts_ck CHECK (attempts >= 0)
);

CREATE TABLE endofday.run_issue (
    id         uuid         NOT NULL,
    run_id     uuid         NOT NULL,
    step_name  varchar(60),
    -- Código UPPER_SNAKE (STEP_FAILED, STEP_UNREGISTERED, ...).
    kind       varchar(60)  NOT NULL,
    -- Referência opaca (id, número ou nome de etapa), nunca dado pessoal de hóspede.
    reference  varchar(120),
    message    varchar(500) NOT NULL,
    resolved   boolean      NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT run_issue_pk PRIMARY KEY (id),
    CONSTRAINT run_issue_run_fk FOREIGN KEY (run_id) REFERENCES endofday.run (id),
    CONSTRAINT run_issue_kind_ck CHECK (kind ~ '^[A-Z][A-Z0-9_]*$')
);

-- As pendências de uma execução, na ordem em que foram abertas.
CREATE INDEX run_issue_run_idx ON endofday.run_issue (run_id, created_at, id);

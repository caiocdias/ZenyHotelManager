-- Envio assíncrono à FNRH (M5 E08, D-E08-2, D-E08-3, D-E08-13, D-E08-15): a fila de submissões, uma por evento, e os
-- ids que o Serpro devolveu. Nenhuma coluna guarda dado de hóspede, texto livre do Serpro, documento, raça/cor ou
-- deficiência: o erro de validação local guarda só o campo e o código, e o link de pré-check-in vai cifrado.
-- Sem chave estrangeira para outro schema: reservation_id e guest_id são referências opacas.

CREATE TABLE fnrh.submission (
    id               uuid        NOT NULL,
    reservation_id   uuid        NOT NULL,
    reservation_code text        NOT NULL,
    operation        text        NOT NULL,
    -- O posto da operação na ordem causal da reserva (REGISTER 0 ... CHECK_OUT 5), para a fila ordenar no banco.
    operation_rank   smallint    NOT NULL,
    event_key        text        NOT NULL,
    -- A version da reserva no evento; 0 no ReservationCreated.
    causal_version   bigint      NOT NULL,
    -- O cursor da API: gerado pelo banco ao inserir.
    sequence         bigint      GENERATED ALWAYS AS IDENTITY,
    status           text        NOT NULL,
    attempts         int         NOT NULL,
    next_attempt_at  timestamptz NOT NULL,
    last_failure     text,
    -- Escrita que cria recurso em curso: só se limpa ao gravar o id remoto ou ao marcar como enviada.
    in_flight        boolean     NOT NULL,
    performed_by     uuid,
    occurred_at      timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    sent_at          timestamptz,
    version          bigint      NOT NULL,
    CONSTRAINT submission_pk PRIMARY KEY (id),
    CONSTRAINT submission_sequence_uk UNIQUE (sequence),
    CONSTRAINT submission_event_uk UNIQUE (reservation_id, operation, event_key),
    CONSTRAINT submission_operation_ck CHECK (operation IN
        ('REGISTER_RESERVATION', 'UPDATE_RESERVATION', 'CANCEL_RESERVATION', 'CHECK_IN', 'UNDO_CHECK_IN',
         'CHECK_OUT')),
    CONSTRAINT submission_status_ck CHECK (status IN
        ('PENDING', 'SENT', 'FAILED', 'INVALID', 'CANCELLED', 'DISMISSED')),
    CONSTRAINT submission_last_failure_ck CHECK (last_failure IS NULL OR last_failure IN
        ('VALIDATION', 'UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND', 'SERVER_ERROR', 'UNAVAILABLE', 'UNKNOWN_OUTCOME',
         'INTERNAL_ERROR', 'REMOTE_CANCELLED', 'NO_REMOTE_OPERATION')),
    CONSTRAINT submission_causal_version_ck CHECK (causal_version >= 0),
    CONSTRAINT submission_attempts_ck CHECK (attempts >= 0)
);

-- A fila por reserva: só as não resolvidas, na ordem causal.
CREATE INDEX submission_queue_idx
    ON fnrh.submission (reservation_id, causal_version, operation_rank, sequence)
    WHERE status IN ('PENDING', 'FAILED', 'INVALID');

CREATE INDEX submission_status_idx ON fnrh.submission (status, sequence);

-- Os erros da validação local (INVALID): o campo e o código, nunca o valor.
CREATE TABLE fnrh.submission_error (
    submission_id uuid NOT NULL,
    guest_id      uuid NOT NULL,
    field         text NOT NULL,
    code          text NOT NULL,
    CONSTRAINT submission_error_pk PRIMARY KEY (submission_id, guest_id, field, code),
    CONSTRAINT submission_error_submission_fk FOREIGN KEY (submission_id) REFERENCES fnrh.submission (id)
);

-- O id da reserva no Serpro, por ambiente; o link de pré-check-in é cifrado pelo FieldCipher (v1:...).
CREATE TABLE fnrh.remote_reservation (
    reservation_id  uuid    NOT NULL,
    environment     text    NOT NULL,
    remote_id       text    NOT NULL,
    precheckin_link text,
    cancelled       boolean NOT NULL,
    CONSTRAINT remote_reservation_pk PRIMARY KEY (reservation_id, environment),
    CONSTRAINT remote_reservation_environment_ck CHECK (environment IN ('HOMOLOGATION', 'PRODUCTION'))
);

-- Os ids da pessoa e do hóspede no Serpro, por reserva e ambiente: o progresso do check-in hóspede a hóspede.
CREATE TABLE fnrh.remote_guest (
    reservation_id  uuid    NOT NULL,
    guest_id        uuid    NOT NULL,
    environment     text    NOT NULL,
    person_id       text    NOT NULL,
    guest_remote_id text,
    checked_out     boolean NOT NULL,
    CONSTRAINT remote_guest_pk PRIMARY KEY (reservation_id, guest_id, environment),
    CONSTRAINT remote_guest_environment_ck CHECK (environment IN ('HOMOLOGATION', 'PRODUCTION'))
);

CREATE INDEX remote_guest_person_idx ON fnrh.remote_guest (guest_id, environment);

-- Privilégios: sem GRANT próprio. O ALTER DEFAULT PRIVILEGES de V2026_11_04_1001 dá SELECT, INSERT, UPDATE e DELETE ao
-- papel da aplicação nas tabelas novas do schema, e o DatabaseRolesTest exige DML completo nas tabelas que não são
-- append-only. Só o retry apaga linhas (os erros de submission_error); o código não apaga as demais.

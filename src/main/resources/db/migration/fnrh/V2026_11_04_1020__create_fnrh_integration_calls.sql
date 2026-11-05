-- Registro das chamadas à FNRH (M5 E07, D-E07-8, ARCHITECTURE §12): uma linha por chamada HTTP ao Serpro, *append-only*.
-- Não tem coluna de texto livre: a mensagem do Serpro pode trazer o nome do hóspede e uma tabela que não admite
-- expurgo não pode guardá-la. Só a operação, o resultado, o status HTTP, a duração, o ambiente e referências opacas
-- (o nosso id de correlação e o id remoto), todos com formato ou tamanho limitado.

CREATE TABLE fnrh.integration_call (
    id              bigint      GENERATED ALWAYS AS IDENTITY,
    operation       text        NOT NULL,
    environment     text        NOT NULL,
    outcome         text        NOT NULL,
    http_status     integer,
    duration_ms     bigint      NOT NULL,
    correlation_ref text,
    remote_id       text,
    occurred_at     timestamptz NOT NULL,
    CONSTRAINT integration_call_pk PRIMARY KEY (id),
    CONSTRAINT integration_call_operation_ck CHECK (operation ~ '^[A-Za-z]{1,40}$'),
    CONSTRAINT integration_call_environment_ck CHECK (environment IN ('HOMOLOGATION', 'PRODUCTION')),
    CONSTRAINT integration_call_outcome_ck CHECK (outcome ~ '^[A-Z_]{1,40}$'),
    CONSTRAINT integration_call_http_status_ck CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT integration_call_duration_ck CHECK (duration_ms >= 0),
    CONSTRAINT integration_call_refs_ck CHECK (length(correlation_ref) <= 100),
    CONSTRAINT integration_call_remote_id_ck CHECK (remote_id IS NULL OR remote_id ~ '^[A-Za-z0-9._-]{1,100}$')
);

CREATE INDEX integration_call_env_outcome_idx
    ON fnrh.integration_call (environment, outcome, occurred_at DESC);

CREATE FUNCTION fnrh.refuse_integration_call_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'O registro das chamadas à FNRH (fnrh.integration_call) é imutável'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER integration_call_immutable
    BEFORE UPDATE OR DELETE
    ON fnrh.integration_call
    FOR EACH ROW
EXECUTE FUNCTION fnrh.refuse_integration_call_change();

-- O ALTER DEFAULT PRIVILEGES do schema deu DML à tabela nova: o registro é só SELECT e INSERT.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE fnrh.integration_call FROM "${app_role}";

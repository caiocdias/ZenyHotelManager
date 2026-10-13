-- Apoio só de teste (não vai para produção): tabela para conferir a gravação de instantes.
CREATE SCHEMA IF NOT EXISTS test_support;

CREATE TABLE test_support.time_probe
(
    id          UUID        NOT NULL PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL
);

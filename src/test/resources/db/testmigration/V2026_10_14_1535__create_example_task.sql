-- Apoio só de teste (não vai para produção): registros com dono, para o escopo OWN (M1 E02).
CREATE TABLE test_support.example_task
(
    id          UUID         NOT NULL PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    assignee_id UUID,
    created_by  UUID         NOT NULL,
    completed   BOOLEAN      NOT NULL
);

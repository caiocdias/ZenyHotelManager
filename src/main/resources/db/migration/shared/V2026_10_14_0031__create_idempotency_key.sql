-- Chaves de idempotência das requisições HTTP (cabeçalho Idempotency-Key, ARCHITECTURE §7).
-- Uma linha por (escopo, chave): o escopo é quem fez a requisição (hoje `user:<nome>`), para que
-- usuários diferentes nunca compartilhem a mesma chave. A linha nasce IN_PROGRESS (reserva,
-- gravada antes de executar o handler) e vira COMPLETED com a resposta inteira, cifrada, que é
-- devolvida nas repetições até expires_at (24h). O job IdempotencyKeyPurgeJob apaga as vencidas.
CREATE TABLE shared.idempotency_key
(
    id               UUID                     NOT NULL,
    scope            VARCHAR(255)             NOT NULL,
    idempotency_key  VARCHAR(255)             NOT NULL,
    -- SHA-256 (hex) de método, caminho, query, If-Match e corpo da requisição original.
    request_hash     CHAR(64)                 NOT NULL,
    status           VARCHAR(16)              NOT NULL,
    response_status  INT,
    -- Cabeçalhos relevantes da resposta (Content-Type, Location, ETag…) em JSON ({"nome": ["valor"]})
    -- e o corpo em base64, ambos cifrados com o FieldCipher (v1:<chave>:<base64url>, ARCHITECTURE
    -- §8.3): a resposta pode trazer dado pessoal. Cifrados, não servem para busca nem índice.
    response_headers TEXT,
    response_body    TEXT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at     TIMESTAMP WITH TIME ZONE,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT idempotency_key_pk PRIMARY KEY (id),
    CONSTRAINT idempotency_key_scope_key_uk UNIQUE (scope, idempotency_key),
    CONSTRAINT idempotency_key_status_ck CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_key_completed_ck CHECK (
        status = 'IN_PROGRESS'
            OR (response_status IS NOT NULL AND response_headers IS NOT NULL AND response_body IS NOT NULL
                AND completed_at IS NOT NULL)
        )
);

CREATE INDEX idempotency_key_expires_at_idx ON shared.idempotency_key (expires_at);

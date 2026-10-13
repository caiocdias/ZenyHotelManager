-- Event Publication Registry do Spring Modulith 2.x (outbox transacional, ARCHITECTURE §5 e D9).
-- Estrutura "v2" do spring-modulith-events-jdbc (schemas/v2/schema-postgresql.sql), no schema
-- shared (spring.modulith.events.jdbc.schema). Ao atualizar o Spring Modulith, compare com o
-- schema do jar e crie uma migração nova se ele mudar.
CREATE TABLE shared.event_publication
(
    id                     UUID                     NOT NULL,
    listener_id            TEXT                     NOT NULL,
    event_type             TEXT                     NOT NULL,
    serialized_event       TEXT                     NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON shared.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON shared.event_publication (completion_date);

-- Metadados dos anexos (M1 E08, ARCHITECTURE §14, RNF-09). O conteúdo fica no armazenamento S3, na
-- chave object_key (<módulo>/<finalidade>/<id>); aqui ficam a finalidade, o módulo e o registro
-- donos, o tipo detectado no conteúdo, o tamanho e o SHA-256 do conteúdo original, se é sensível e,
-- nesse caso, a chave que o cifrou (anexo sensível nunca vai em claro ao armazenamento), e quem
-- enviou. O nome original pode ter dado pessoal: fica cifrado pelo FieldCipher (texto v1:…).
CREATE TABLE files.stored_file (
    id                uuid         PRIMARY KEY,
    purpose           varchar(64)  NOT NULL,
    owner_module      varchar(32)  NOT NULL,
    owner_entity_id   varchar(100),
    object_key        varchar(200) NOT NULL,
    content_type      varchar(100) NOT NULL,
    size_bytes        bigint       NOT NULL,
    stored_size_bytes bigint       NOT NULL,
    sha256            varchar(64)  NOT NULL,
    sensitive         boolean      NOT NULL,
    encryption_key_id varchar(32),
    original_name     text,
    created_by        uuid,
    created_at        timestamptz  NOT NULL,
    attached_at       timestamptz,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT stored_file_object_key_uk UNIQUE (object_key),
    CONSTRAINT stored_file_purpose_ck CHECK (purpose ~ '^[a-z][a-z0-9]*\.[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    CONSTRAINT stored_file_owner_module_ck CHECK (owner_module = split_part(purpose, '.', 1)),
    CONSTRAINT stored_file_size_ck CHECK (size_bytes > 0 AND stored_size_bytes >= size_bytes),
    CONSTRAINT stored_file_sha256_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Sensível sempre com a chave que o cifrou; comum nunca com chave.
    CONSTRAINT stored_file_encryption_ck CHECK (sensitive = (encryption_key_id IS NOT NULL)),
    CONSTRAINT stored_file_attachment_ck CHECK ((owner_entity_id IS NULL) = (attached_at IS NULL))
);

CREATE INDEX stored_file_owner_idx ON files.stored_file (owner_module, owner_entity_id);

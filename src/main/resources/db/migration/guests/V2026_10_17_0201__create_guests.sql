-- Hóspedes e empresas (SPEC §5.5 e §8.2.4, RF-HOS-01, 02, 04 e 06, RN-19; M3 E01). Proteção de dados
-- pessoais (ARCHITECTURE §8.3, RNF-09):
-- - o número de documento fica cifrado pelo FieldCipher (coluna text, `v1:<chave>:…`) e só se busca pelo
--   índice cego (H8): HMAC-SHA256 do valor normalizado com chave derivada do anel, gravado com o id da
--   chave; a busca procura o hash calculado com cada chave do anel;
-- - raça/cor e deficiência (RN-19) ficam juntas num único valor cifrado de tamanho fixo (sensitive_data),
--   para que o tamanho do texto cifrado não revele a resposta;
-- - search_name guarda o nome sem acento e em minúsculas, gravado pela aplicação, com índice de trigramas
--   (pg_trgm, migração do shared) para a busca por trecho (decisão do usuário em 2026-10-16).
-- Perfis e empresas nunca são apagados (o trigger no fim recusa); a anonimização é da E03. Os documentos
-- de um perfil saem quando a recepção os retira (correção). O sufixo do número (number_suffix, os 4 últimos
-- caracteres, 2 nos números curtos) fica em claro para mostrar o documento mascarado (H9) sem decifrar nada: a
-- listagem e a busca nunca decifram um documento; só a ação que revela o número decifra o pedido.

-- Empresas (RF-HOS-06): pagadoras de contas, tomadoras de nota, clientes de eventos. O CNPJ é único sempre
-- (decisão do usuário em 2026-10-16): a empresa desativada continua dona dele e é reativada, não recadastrada.
CREATE TABLE guests.company
(
    id                     UUID                     NOT NULL,
    legal_name             VARCHAR(200)             NOT NULL,
    trade_name             VARCHAR(200),
    search_name            VARCHAR(401)             NOT NULL,
    cnpj                   VARCHAR(14)              NOT NULL,
    state_registration     VARCHAR(30),
    municipal_registration VARCHAR(30),
    street                 VARCHAR(200),
    number                 VARCHAR(20),
    complement             VARCHAR(100),
    district               VARCHAR(100),
    city                   VARCHAR(100),
    state                  VARCHAR(2),
    postal_code            VARCHAR(8),
    ibge_city_code         VARCHAR(7),
    contact_name           VARCHAR(200),
    phone                  VARCHAR(30),
    email                  VARCHAR(254),
    billing_email          VARCHAR(254),
    credit_limit           NUMERIC(14, 2),
    active                 BOOLEAN                  NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT                   NOT NULL,
    CONSTRAINT company_pk PRIMARY KEY (id),
    CONSTRAINT company_cnpj_uk UNIQUE (cnpj),
    CONSTRAINT company_cnpj_ck CHECK (cnpj ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    CONSTRAINT company_legal_name_ck CHECK (length(btrim(legal_name)) > 0),
    CONSTRAINT company_credit_limit_ck CHECK (credit_limit IS NULL OR credit_limit >= 0),
    CONSTRAINT company_address_ck CHECK (
        (street IS NULL AND number IS NULL AND district IS NULL AND city IS NULL AND state IS NULL
            AND postal_code IS NULL AND ibge_city_code IS NULL AND complement IS NULL)
        OR (street IS NOT NULL AND number IS NOT NULL AND district IS NOT NULL AND city IS NOT NULL
            AND state IS NOT NULL AND postal_code ~ '^[0-9]{8}$' AND ibge_city_code ~ '^[0-9]{7}$'))
);

CREATE INDEX company_search_name_trgm_idx ON guests.company USING gin (search_name public.gin_trgm_ops);
CREATE INDEX company_search_name_idx ON guests.company (search_name, id);

-- Hóspedes (RF-HOS-01 e 02): campos da FNRH, preferências, VIP, observações e a lista de restrição
-- (RF-HOS-04). O endereço completo é só de quem reside no Brasil (o Address do shared, com o código IBGE);
-- o estrangeiro informa só o país de residência (decisão do usuário em 2026-10-16).
CREATE TABLE guests.guest
(
    id                 UUID                     NOT NULL,
    full_name          VARCHAR(200)             NOT NULL,
    social_name        VARCHAR(200),
    search_name        VARCHAR(401)             NOT NULL,
    nationality        VARCHAR(2),
    birth_date         DATE,
    gender             VARCHAR(20),
    email              VARCHAR(254),
    phone              VARCHAR(30),
    residence_country  VARCHAR(2),
    street             VARCHAR(200),
    number             VARCHAR(20),
    complement         VARCHAR(100),
    district           VARCHAR(100),
    city               VARCHAR(100),
    state              VARCHAR(2),
    postal_code        VARCHAR(8),
    ibge_city_code     VARCHAR(7),
    preferred_floor    VARCHAR(60),
    preferred_bed      VARCHAR(60),
    preferred_pillow   VARCHAR(60),
    vip                BOOLEAN                  NOT NULL,
    notes              VARCHAR(1000),
    company_id         UUID,
    restricted         BOOLEAN                  NOT NULL,
    restriction_reason VARCHAR(500),
    restricted_at      TIMESTAMP WITH TIME ZONE,
    restricted_by      UUID,
    sensitive_data     TEXT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT                   NOT NULL,
    CONSTRAINT guest_pk PRIMARY KEY (id),
    CONSTRAINT guest_company_fk FOREIGN KEY (company_id) REFERENCES guests.company (id),
    CONSTRAINT guest_full_name_ck CHECK (length(btrim(full_name)) > 0),
    CONSTRAINT guest_nationality_ck CHECK (nationality IS NULL OR nationality ~ '^[A-Z]{2}$'),
    CONSTRAINT guest_residence_country_ck CHECK (residence_country IS NULL OR residence_country ~ '^[A-Z]{2}$'),
    CONSTRAINT guest_gender_ck CHECK (gender IS NULL OR gender IN ('FEMALE', 'MALE', 'OTHER', 'NOT_INFORMED')),
    CONSTRAINT guest_email_ck CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT guest_address_ck CHECK (
        (street IS NULL AND number IS NULL AND district IS NULL AND city IS NULL AND state IS NULL
            AND postal_code IS NULL AND ibge_city_code IS NULL AND complement IS NULL)
        OR (residence_country = 'BR' AND street IS NOT NULL AND number IS NOT NULL AND district IS NOT NULL
            AND city IS NOT NULL AND state IS NOT NULL AND postal_code ~ '^[0-9]{8}$'
            AND ibge_city_code ~ '^[0-9]{7}$')),
    CONSTRAINT guest_restriction_ck CHECK (
        (restricted AND restriction_reason IS NOT NULL AND restricted_at IS NOT NULL)
        OR (NOT restricted AND restriction_reason IS NULL AND restricted_at IS NULL AND restricted_by IS NULL)),
    CONSTRAINT guest_sensitive_data_ck CHECK (sensitive_data IS NULL OR sensitive_data LIKE 'v1:%')
);

CREATE INDEX guest_search_name_trgm_idx ON guests.guest USING gin (search_name public.gin_trgm_ops);
CREATE INDEX guest_search_name_idx ON guests.guest (search_name, id);
CREATE INDEX guest_email_idx ON guests.guest (email) WHERE email IS NOT NULL;
CREATE INDEX guest_company_idx ON guests.guest (company_id) WHERE company_id IS NOT NULL;

-- Documentos de identidade (SPEC §8.2.4): tipo da lista da FNRH (decisão do usuário em 2026-10-16), país
-- emissor (ISO 3166-1 alfa-2; BR nos documentos brasileiros), número cifrado e o índice cego. Um documento
-- de cada tipo e país por perfil. O mesmo documento em dois perfis é possível (duplicado, tratado na E02).
CREATE TABLE guests.guest_document
(
    id                UUID                     NOT NULL,
    guest_id          UUID                     NOT NULL,
    type              VARCHAR(20)              NOT NULL,
    issuing_country   VARCHAR(2)               NOT NULL,
    number            TEXT                     NOT NULL,
    number_suffix     VARCHAR(4)               NOT NULL,
    number_index_key  VARCHAR(32)              NOT NULL,
    number_index      VARCHAR(64)              NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT guest_document_pk PRIMARY KEY (id),
    CONSTRAINT guest_document_guest_fk FOREIGN KEY (guest_id) REFERENCES guests.guest (id),
    CONSTRAINT guest_document_kind_uk UNIQUE (guest_id, type, issuing_country),
    CONSTRAINT guest_document_type_ck CHECK (type IN ('CPF', 'RG', 'CNH', 'PASSPORT', 'RNM', 'FOREIGN_ID')),
    CONSTRAINT guest_document_country_ck CHECK (issuing_country ~ '^[A-Z]{2}$'
        AND (type NOT IN ('CPF', 'RG', 'CNH', 'RNM') OR issuing_country = 'BR')
        AND (type <> 'FOREIGN_ID' OR issuing_country <> 'BR')),
    CONSTRAINT guest_document_number_ck CHECK (number LIKE 'v1:%'),
    CONSTRAINT guest_document_number_suffix_ck CHECK (number_suffix ~ '^[A-Z0-9]{2,4}$')
);

CREATE INDEX guest_document_number_index_idx ON guests.guest_document (number_index);
CREATE INDEX guest_document_guest_idx ON guests.guest_document (guest_id);

-- Perfis e empresas nunca são apagados: a anonimização (E03) apaga os dados pessoais e mantém a linha.
CREATE FUNCTION guests.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de guests.% nunca são apagados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER guest_never_deleted
    BEFORE DELETE
    ON guests.guest
    FOR EACH ROW
EXECUTE FUNCTION guests.refuse_delete();

CREATE TRIGGER company_never_deleted
    BEFORE DELETE
    ON guests.company
    FOR EACH ROW
EXECUTE FUNCTION guests.refuse_delete();

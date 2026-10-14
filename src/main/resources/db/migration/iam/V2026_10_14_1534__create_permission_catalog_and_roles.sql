-- Catálogo de permissões, roles e atribuições (SPEC §8.2.1, RF-ACS-02 a 06, ARCHITECTURE §8.2).

-- Catálogo declarado em código pelos módulos e sincronizado no boot: as permissões novas são
-- inseridas e as que saíram do código ficam obsoletas (obsolete_at), sem conceder acesso. Nunca
-- se apagam: as concessões das roles as referenciam.
CREATE TABLE iam.permission
(
    code        VARCHAR(100)             NOT NULL,
    module      VARCHAR(50)              NOT NULL,
    description VARCHAR(300)             NOT NULL,
    -- Escopos suportados (OWN, ALL).
    scopes      VARCHAR(20)[]            NOT NULL,
    sensitive   BOOLEAN                  NOT NULL,
    -- Limites aceitos: [{"key": "...", "unit": "MONEY|PERCENT|QUANTITY", "description": "..."}].
    limits      JSONB                    NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    obsolete_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT permission_pk PRIMARY KEY (code),
    CONSTRAINT permission_scopes_ck CHECK (cardinality(scopes) > 0 AND scopes <@ ARRAY ['OWN', 'ALL']::VARCHAR(20)[]),
    CONSTRAINT permission_limits_ck CHECK (jsonb_typeof(limits) = 'array')
);

-- Roles compostas pelo administrador (RF-ACS-03). template_key marca as criadas a partir dos
-- modelos da SPEC §3.1; customized, as que o administrador editou (o seed não acrescenta mais
-- nada a elas, H5); system_role, o Administrador, mantido pelo catálogo com todas as permissões.
CREATE TABLE iam.role
(
    id           UUID                     NOT NULL,
    name         VARCHAR(100)             NOT NULL,
    description  VARCHAR(500)             NOT NULL,
    active       BOOLEAN                  NOT NULL,
    template_key VARCHAR(30),
    system_role  BOOLEAN                  NOT NULL,
    customized   BOOLEAN                  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT role_pk PRIMARY KEY (id),
    CONSTRAINT role_template_key_uk UNIQUE (template_key),
    CONSTRAINT role_system_ck CHECK (NOT system_role OR template_key = 'ADMINISTRATOR')
);

CREATE UNIQUE INDEX role_name_uk ON iam.role (lower(name));

-- Concessão de uma permissão por uma role, com o escopo (RF-ACS-05).
CREATE TABLE iam.role_grant
(
    id              UUID         NOT NULL,
    role_id         UUID         NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    scope           VARCHAR(20)  NOT NULL,
    CONSTRAINT role_grant_pk PRIMARY KEY (id),
    CONSTRAINT role_grant_role_fk FOREIGN KEY (role_id) REFERENCES iam.role (id),
    CONSTRAINT role_grant_permission_fk FOREIGN KEY (permission_code) REFERENCES iam.permission (code),
    CONSTRAINT role_grant_role_permission_uk UNIQUE (role_id, permission_code),
    CONSTRAINT role_grant_scope_ck CHECK (scope IN ('OWN', 'ALL'))
);

-- Limites de uma concessão (RF-ACS-06): um teto (max_value) ou sem teto (unlimited).
CREATE TABLE iam.role_grant_limit
(
    grant_id  UUID           NOT NULL,
    limit_key VARCHAR(100)   NOT NULL,
    unlimited BOOLEAN        NOT NULL,
    max_value NUMERIC(18, 4),
    CONSTRAINT role_grant_limit_pk PRIMARY KEY (grant_id, limit_key),
    CONSTRAINT role_grant_limit_grant_fk FOREIGN KEY (grant_id) REFERENCES iam.role_grant (id) ON DELETE CASCADE,
    CONSTRAINT role_grant_limit_value_ck CHECK (unlimited = (max_value IS NULL) AND (max_value IS NULL OR max_value >= 0))
);

-- Roles de cada usuário (RF-ACS-04).
CREATE TABLE iam.user_role
(
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT user_role_pk PRIMARY KEY (user_id, role_id),
    CONSTRAINT user_role_user_fk FOREIGN KEY (user_id) REFERENCES iam.user_account (id),
    CONSTRAINT user_role_role_fk FOREIGN KEY (role_id) REFERENCES iam.role (id)
);

CREATE INDEX user_role_role_idx ON iam.user_role (role_id);

-- Versão das autorizações: as permissões efetivas ficam em cache na aplicação, marcadas com a
-- versão em que foram calculadas; toda mudança em permissão, role, concessão, limite, atribuição
-- ou no estado ativo de um usuário troca a versão na mesma transação (triggers abaixo), e a
-- próxima checagem recalcula. Linha única. O valor novo vem de uma sequence, que não volta num
-- rollback: uma versão vista dentro de uma transação desfeita nunca reaparece.
CREATE TABLE iam.authorization_version
(
    id    SMALLINT NOT NULL,
    value BIGINT   NOT NULL,
    CONSTRAINT authorization_version_pk PRIMARY KEY (id),
    CONSTRAINT authorization_version_single_ck CHECK (id = 1)
);

CREATE SEQUENCE iam.authorization_version_seq;

INSERT INTO iam.authorization_version (id, value)
VALUES (1, nextval('iam.authorization_version_seq'));

CREATE FUNCTION iam.bump_authorization_version() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    UPDATE iam.authorization_version SET value = nextval('iam.authorization_version_seq') WHERE id = 1;
    RETURN NULL;
END;
$$;

CREATE TRIGGER permission_authorization_version
    AFTER INSERT OR UPDATE OR DELETE
    ON iam.permission
    FOR EACH ROW
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER role_authorization_version
    AFTER INSERT OR UPDATE OR DELETE
    ON iam.role
    FOR EACH ROW
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER role_grant_authorization_version
    AFTER INSERT OR UPDATE OR DELETE
    ON iam.role_grant
    FOR EACH ROW
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER role_grant_limit_authorization_version
    AFTER INSERT OR UPDATE OR DELETE
    ON iam.role_grant_limit
    FOR EACH ROW
EXECUTE FUNCTION iam.bump_authorization_version();

CREATE TRIGGER user_role_authorization_version
    AFTER INSERT OR UPDATE OR DELETE
    ON iam.user_role
    FOR EACH ROW
EXECUTE FUNCTION iam.bump_authorization_version();

-- Do usuário, só a troca do estado ativo muda as permissões (as falhas de login não).
CREATE TRIGGER user_account_authorization_version
    AFTER UPDATE OF active
    ON iam.user_account
    FOR EACH ROW
    WHEN (OLD.active IS DISTINCT FROM NEW.active)
EXECUTE FUNCTION iam.bump_authorization_version();

-- Role de sistema Administrador (SPEC §3.1). As concessões (todas as permissões) e os demais
-- modelos são criados no boot, junto da sincronização do catálogo.
INSERT INTO iam.role (id, name, description, active, template_key, system_role, customized, created_at, updated_at,
                      version)
VALUES ('019a0000-0000-7000-8000-000000000001', 'Administrador',
        'Usuários, roles, configurações e integrações; todas as permissões.', TRUE, 'ADMINISTRATOR', TRUE, FALSE,
        now(), now(), 0);

-- Bancos de antes desta migração (M1 E01) só têm o administrador inicial, o primeiro usuário
-- criado (H2): ele recebe a role Administrador. Num banco novo não há usuário, e o administrador
-- inicial a recebe ao ser criado.
INSERT INTO iam.user_role (user_id, role_id)
SELECT id, '019a0000-0000-7000-8000-000000000001'
FROM iam.user_account
ORDER BY created_at, id
LIMIT 1;

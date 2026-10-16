-- Modelos de checklist (M2 E06, RF-CHK-01 a 06, SPEC §8.2.11). O conteúdo de um modelo (aplicabilidade,
-- seções, itens, condições e ações) é um documento jsonb: o rascunho fica no modelo e cada publicação o
-- congela numa linha nova de checklist_version, que nunca muda (append-only para o papel da aplicação, e um
-- trigger recusa UPDATE e DELETE de qualquer papel). Tipos de UH, tipos de limpeza, áreas, ativos, insumos e
-- tipos de enxoval são IDs opacos de outros módulos (H3): nenhuma FK fora do schema.

CREATE TABLE checklists.checklist_template
(
    id                      uuid         NOT NULL,
    name                    varchar(120) NOT NULL,
    context                 varchar(20)  NOT NULL,
    active                  boolean      NOT NULL DEFAULT true,
    draft                   jsonb        NOT NULL,
    has_unpublished_changes boolean      NOT NULL,
    published_version       integer,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    version                 bigint       NOT NULL,
    CONSTRAINT checklist_template_pk PRIMARY KEY (id),
    CONSTRAINT checklist_template_name_ck CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 120),
    CONSTRAINT checklist_template_context_ck CHECK (context IN ('HOUSEKEEPING', 'INSPECTION', 'MAINTENANCE',
                                                                'COMMON_AREA', 'RESTAURANT', 'EVENT', 'OTHER')),
    CONSTRAINT checklist_template_draft_ck CHECK (jsonb_typeof(draft) = 'object'),
    CONSTRAINT checklist_template_published_version_ck CHECK (published_version IS NULL OR published_version >= 1),
    -- Nunca publicado: o rascunho é, por definição, uma mudança a publicar.
    CONSTRAINT checklist_template_never_published_ck CHECK (published_version IS NOT NULL OR has_unpublished_changes),
    CONSTRAINT checklist_template_version_ck CHECK (version >= 0)
);

CREATE INDEX checklist_template_name_idx ON checklists.checklist_template (name, id);
CREATE INDEX checklist_template_context_idx ON checklists.checklist_template (context, active);

CREATE TABLE checklists.checklist_version
(
    id           uuid         NOT NULL,
    template_id  uuid         NOT NULL,
    number       integer      NOT NULL,
    name         varchar(120) NOT NULL,
    context      varchar(20)  NOT NULL,
    content      jsonb        NOT NULL,
    published_at timestamptz  NOT NULL,
    published_by uuid,
    CONSTRAINT checklist_version_pk PRIMARY KEY (id),
    CONSTRAINT checklist_version_template_fk FOREIGN KEY (template_id)
        REFERENCES checklists.checklist_template (id),
    CONSTRAINT checklist_version_number_uk UNIQUE (template_id, number),
    CONSTRAINT checklist_version_number_ck CHECK (number >= 1),
    CONSTRAINT checklist_version_context_ck CHECK (context IN ('HOUSEKEEPING', 'INSPECTION', 'MAINTENANCE',
                                                               'COMMON_AREA', 'RESTAURANT', 'EVENT', 'OTHER')),
    CONSTRAINT checklist_version_content_ck CHECK (jsonb_typeof(content) = 'object')
);

-- A última versão publicada do modelo existe de fato (MATCH SIMPLE: nunca publicado = nulo, sem conferência).
ALTER TABLE checklists.checklist_template
    ADD CONSTRAINT checklist_template_published_fk FOREIGN KEY (id, published_version)
        REFERENCES checklists.checklist_version (template_id, number);

-- Modelos nunca são apagados, só desativados (as versões e, a partir da E07, as execuções os citam).
CREATE FUNCTION checklists.refuse_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Registros de checklists.% nunca são apagados, só desativados', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER checklist_template_never_deleted
    BEFORE DELETE
    ON checklists.checklist_template
    FOR EACH ROW
EXECUTE FUNCTION checklists.refuse_delete();

-- Versões publicadas são imutáveis (RF-CHK-06): nem o dono do schema as altera ou apaga por SQL comum.
CREATE FUNCTION checklists.refuse_version_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'Versões publicadas de checklist são imutáveis: edite o rascunho e publique outra versão'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER checklist_version_immutable
    BEFORE UPDATE OR DELETE
    ON checklists.checklist_version
    FOR EACH ROW
EXECUTE FUNCTION checklists.refuse_version_change();

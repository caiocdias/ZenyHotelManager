-- LGPD no módulo guests (RF-HOS-02, 07 e 08, RN-19, RN-20, RNF-09; M3 E03): consentimentos por finalidade,
-- restrições alimentares (só com o consentimento ativo), anexos do hóspede (o vínculo com o anexo do files) e a
-- anonimização sob pedido do titular.
--
-- Anonimização (decisões do usuário em 2026-10-17): o perfil fica, com o id, a situação ANONYMIZED (com quando e
-- quem; num perfil absorvido por uma mesclagem, a situação continua MERGED e só anonymized_at marca a
-- anonimização), os consentimentos (prova, RN-20), a nacionalidade, o país de residência, a UF e o município (só
-- estes dois do endereço, em anonymized_state e anonymized_ibge_city_code, para os relatórios agregados) e o VIP.
-- Todo o resto sai: nome (o nome completo vira o texto fixo 'Hóspede anonimizado'), nome social, contatos,
-- endereço de rua, data de nascimento, gênero, preferências, observações, restrições alimentares, empresa, a
-- restrição com o motivo, os dados sensíveis da FNRH, os documentos (linhas inteiras, com o número cifrado, o
-- sufixo e o índice cego) e os anexos (apagados pelo files). O CHECK guest_anonymized_ck repete a regra.

ALTER TABLE guests.guest
    ADD COLUMN dietary_restrictions      TEXT,
    ADD COLUMN anonymized_at             TIMESTAMP WITH TIME ZONE,
    ADD COLUMN anonymized_by             UUID,
    ADD COLUMN anonymized_state          VARCHAR(2),
    ADD COLUMN anonymized_ibge_city_code VARCHAR(7);

ALTER TABLE guests.guest
    DROP CONSTRAINT guest_status_ck;

ALTER TABLE guests.guest
    ADD CONSTRAINT guest_status_ck CHECK (
        (status = 'ACTIVE' AND merged_into IS NULL AND merged_at IS NULL AND merged_by IS NULL
            AND anonymized_at IS NULL)
        OR (status = 'MERGED' AND merged_into IS NOT NULL AND merged_into <> id AND merged_at IS NOT NULL)
        OR (status = 'ANONYMIZED' AND merged_into IS NULL AND merged_at IS NULL AND merged_by IS NULL
            AND anonymized_at IS NOT NULL)),
    -- As restrições alimentares ficam cifradas pelo FieldCipher (dado de saúde).
    ADD CONSTRAINT guest_dietary_restrictions_ck CHECK (dietary_restrictions IS NULL
        OR dietary_restrictions LIKE 'v1:%'),
    ADD CONSTRAINT guest_anonymized_ck CHECK (
        (anonymized_at IS NULL AND anonymized_by IS NULL AND anonymized_state IS NULL
            AND anonymized_ibge_city_code IS NULL)
        OR (anonymized_at IS NOT NULL
            AND full_name = 'Hóspede anonimizado' AND search_name = 'hospede anonimizado'
            AND social_name IS NULL AND birth_date IS NULL AND gender IS NULL AND email IS NULL AND phone IS NULL
            AND street IS NULL AND number IS NULL AND complement IS NULL AND district IS NULL AND city IS NULL
            AND state IS NULL AND postal_code IS NULL AND ibge_city_code IS NULL
            AND preferred_floor IS NULL AND preferred_bed IS NULL AND preferred_pillow IS NULL
            AND notes IS NULL AND company_id IS NULL AND NOT restricted AND sensitive_data IS NULL
            AND dietary_restrictions IS NULL
            AND (anonymized_state IS NULL OR anonymized_state ~ '^[A-Z]{2}$')
            AND (anonymized_ibge_city_code IS NULL OR anonymized_ibge_city_code ~ '^[0-9]{7}$')));

CREATE INDEX guest_anonymized_idx ON guests.guest (anonymized_at) WHERE anonymized_at IS NOT NULL;

-- Consentimentos por finalidade (RF-HOS-07, SPEC §8.2.4): quando e por qual meio foram dados e revogados, e por
-- quem foram registrados. Nunca são apagados (prova, RN-20): a revogação marca a linha, e um consentimento novo da
-- mesma finalidade é outra linha. No máximo um ativo por perfil e finalidade. Na mesclagem de duplicados, todos os
-- consentimentos do absorvido passam para o sobrevivente (a mesma linha, só muda guest_id); o que estava ativo numa
-- finalidade em que o sobrevivente já tem um ativo fica substituído por ele (superseded_at e superseded_by = o
-- consentimento do sobrevivente que prevalece), e deixa de valer sem ser uma revogação do titular.
CREATE TABLE guests.guest_consent
(
    id             UUID                     NOT NULL,
    guest_id       UUID                     NOT NULL,
    purpose        VARCHAR(40)              NOT NULL,
    granted_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    granted_source VARCHAR(20)              NOT NULL,
    granted_by     UUID,
    revoked_at     TIMESTAMP WITH TIME ZONE,
    revoked_source VARCHAR(20),
    revoked_by     UUID,
    superseded_at  TIMESTAMP WITH TIME ZONE,
    superseded_by  UUID,
    CONSTRAINT guest_consent_pk PRIMARY KEY (id),
    CONSTRAINT guest_consent_guest_fk FOREIGN KEY (guest_id) REFERENCES guests.guest (id),
    CONSTRAINT guest_consent_purpose_ck CHECK (purpose IN ('MARKETING', 'DIETARY_RESTRICTIONS')),
    CONSTRAINT guest_consent_source_ck CHECK (
        granted_source IN ('IN_PERSON', 'PAPER_FORM', 'PHONE', 'EMAIL', 'WEBSITE')
            AND (revoked_source IS NULL OR revoked_source IN ('IN_PERSON', 'PAPER_FORM', 'PHONE', 'EMAIL', 'WEBSITE'))),
    CONSTRAINT guest_consent_revocation_ck CHECK (
        (revoked_at IS NULL AND revoked_source IS NULL AND revoked_by IS NULL)
            OR (revoked_at IS NOT NULL AND revoked_source IS NOT NULL AND revoked_at >= granted_at)),
    CONSTRAINT guest_consent_superseded_ck CHECK (
        (superseded_at IS NULL AND superseded_by IS NULL)
            OR (superseded_at IS NOT NULL AND superseded_by IS NOT NULL AND superseded_by <> id
                AND revoked_at IS NULL))
);

CREATE UNIQUE INDEX guest_consent_active_uk ON guests.guest_consent (guest_id, purpose)
    WHERE revoked_at IS NULL AND superseded_at IS NULL;
CREATE INDEX guest_consent_guest_idx ON guests.guest_consent (guest_id);

CREATE TRIGGER guest_consent_never_deleted
    BEFORE DELETE
    ON guests.guest_consent
    FOR EACH ROW
EXECUTE FUNCTION guests.refuse_delete();

-- Anexos do hóspede (RF-HOS-08): o vínculo de um perfil com um anexo do files (o id do anexo, que o files guarda
-- com o id do perfil como dono). O conteúdo, o tipo e o tamanho ficam no files, que cifra os sensíveis e audita cada
-- acesso. A linha sai quando o anexo é retirado ou o perfil anonimizado (o anexo é apagado pelo files).
CREATE TABLE guests.guest_attachment
(
    file_id     UUID                     NOT NULL,
    guest_id    UUID                     NOT NULL,
    purpose     VARCHAR(64)              NOT NULL,
    attached_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attached_by UUID,
    CONSTRAINT guest_attachment_pk PRIMARY KEY (file_id),
    CONSTRAINT guest_attachment_guest_fk FOREIGN KEY (guest_id) REFERENCES guests.guest (id),
    CONSTRAINT guest_attachment_purpose_ck CHECK (purpose LIKE 'guests.%')
);

CREATE INDEX guest_attachment_guest_idx ON guests.guest_attachment (guest_id, attached_at);

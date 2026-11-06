-- Descrição do gênero do hóspede (M5 E15, D-E15-1): a FNRH exige um texto livre quando o gênero é OUTRO. O texto
-- revela a identidade de gênero da pessoa (dado pessoal sensível, LGPD art. 5º, II), por isso fica cifrado pelo
-- FieldCipher (`v1:<chave>:…`, como as restrições alimentares) e só existe com o gênero OTHER. O gênero nulo também
-- recusa a descrição: `gender = 'OTHER'` daria NULL e o CHECK passaria, então a comparação é IS NOT DISTINCT FROM.
-- A anonimização apaga a descrição junto com o gênero: guest_anonymized_ck é recriada com o texto de
-- V2026_10_17_0440__add_guest_privacy.sql mais `gender_description IS NULL`.

ALTER TABLE guests.guest
    ADD COLUMN gender_description TEXT;

ALTER TABLE guests.guest
    DROP CONSTRAINT guest_anonymized_ck;

ALTER TABLE guests.guest
    ADD CONSTRAINT guest_gender_description_ck CHECK (gender_description IS NULL
        OR (gender IS NOT DISTINCT FROM 'OTHER' AND gender_description LIKE 'v1:%')),
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
            AND dietary_restrictions IS NULL AND gender_description IS NULL
            AND (anonymized_state IS NULL OR anonymized_state ~ '^[A-Z]{2}$')
            AND (anonymized_ibge_city_code IS NULL OR anonymized_ibge_city_code ~ '^[0-9]{7}$')));

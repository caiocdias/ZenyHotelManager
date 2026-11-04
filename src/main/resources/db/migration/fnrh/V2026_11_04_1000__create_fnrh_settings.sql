-- Configuração da integração com a FNRH do Serpro (M5 E07; SPEC §8.2.17, D-E07-1). A tabela tem uma linha só: a
-- semente abaixo, desligada, em homologação e vazia, com version 0, para o primeiro PUT ter ETag. O usuário, a senha e o
-- CPF solicitante padrão ficam cifrados pela aplicação (FieldCipher, ARCHITECTURE §8.3): por isso text, sem formato.
-- O ambiente escolhe a URL, que fica em propriedade (zeny.fnrh), nunca aqui.

CREATE TABLE fnrh.settings (
    id          uuid        NOT NULL,
    environment varchar(20) NOT NULL,
    username    text,
    password    text,
    default_cpf text,
    enabled     boolean     NOT NULL,
    version     bigint      NOT NULL,
    CONSTRAINT settings_pk PRIMARY KEY (id),
    -- Uma linha só: a semente.
    CONSTRAINT settings_singleton_ck CHECK (id = '00000000-0000-0000-0000-00000000f001'),
    CONSTRAINT settings_environment_ck CHECK (environment IN ('HOMOLOGATION', 'PRODUCTION')),
    -- Só se liga com usuário, senha e CPF.
    CONSTRAINT settings_enabled_ck CHECK (
        NOT enabled OR (username IS NOT NULL AND password IS NOT NULL AND default_cpf IS NOT NULL)
    )
);

INSERT INTO fnrh.settings (id, environment, enabled, version)
VALUES ('00000000-0000-0000-0000-00000000f001', 'HOMOLOGATION', FALSE, 0);

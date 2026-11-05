-- CPF solicitante da FNRH por usuário (M5 E07, D-E07-7). O user_id é opaco: o fnrh não importa iam e não confere se o
-- usuário existe. O CPF fica cifrado pela aplicação (FieldCipher, ARCHITECTURE §8.3): por isso text, sem formato.
-- Os privilégios vêm do ALTER DEFAULT PRIVILEGES de V2026_11_04_1001.

CREATE TABLE fnrh.requester (
    user_id uuid NOT NULL,
    cpf     text,
    active  boolean NOT NULL,
    -- Sobe a cada troca (@Version) e entra no retrato da trilha; o DEFAULT cobre quem insere direto na tabela.
    version bigint  NOT NULL DEFAULT 0,
    CONSTRAINT requester_pk PRIMARY KEY (user_id),
    -- Nada é apagado: desativar anula o CPF (minimização de dado pessoal) e mantém a linha.
    CONSTRAINT requester_active_cpf_ck CHECK (active = (cpf IS NOT NULL))
);

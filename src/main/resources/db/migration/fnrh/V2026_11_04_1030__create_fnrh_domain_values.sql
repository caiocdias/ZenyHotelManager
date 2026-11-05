-- Cache dos domínios oficiais da FNRH (M5 E07, D-E07-5): o código (`id`) que a API do Serpro aceita e o rótulo que o
-- usuário lê, por domínio (o nome do FnrhDomain). A semente traz os valores documentados em
-- docs/fnrh/api-fnrh-v2.4.md, para a validação funcionar desde o primeiro dia; o refresh sob demanda os substitui.
-- Os privilégios vêm do ALTER DEFAULT PRIVILEGES de V2026_11_04_1001.

CREATE TABLE fnrh.domain_value (
    domain       text        NOT NULL,
    code         text        NOT NULL,
    label        text        NOT NULL,
    refreshed_at timestamptz NOT NULL,
    CONSTRAINT domain_value_pk PRIMARY KEY (domain, code)
);

INSERT INTO fnrh.domain_value (domain, code, label, refreshed_at)
SELECT domain, code, label, now()
FROM (VALUES
    ('TRANSPORT_MODES', 'ONIBUS', 'Ônibus'),
    ('TRANSPORT_MODES', 'AUTOMOVEL', 'Automóvel'),
    ('TRANSPORT_MODES', 'AVIAO', 'Avião'),
    ('TRANSPORT_MODES', 'MOTO', 'Moto'),
    ('TRANSPORT_MODES', 'NAVIO_BARCO', 'Navio/Barco'),
    ('TRANSPORT_MODES', 'PE', 'A pé'),
    ('TRANSPORT_MODES', 'TREM', 'Trem'),
    ('TRANSPORT_MODES', 'BICICLETA', 'Bicicleta'),
    ('TRAVEL_REASONS', 'COMPRAS', 'Compras'),
    ('TRAVEL_REASONS', 'CONGRESSO_FEIRA', 'Congresso/Feira'),
    ('TRAVEL_REASONS', 'ESTUDOS_CURSOS', 'Estudos/Cursos'),
    ('TRAVEL_REASONS', 'LAZER_FERIAS', 'Lazer/Férias'),
    ('TRAVEL_REASONS', 'NEGOCIOS', 'Negócios'),
    ('TRAVEL_REASONS', 'PARENTES_AMIGOS', 'Parentes/Amigos'),
    ('TRAVEL_REASONS', 'RELIGIAO', 'Religião'),
    ('TRAVEL_REASONS', 'SAUDE', 'Saúde'),
    ('GUEST_STATUSES', 'CANCELADO', 'Cancelado'),
    ('GUEST_STATUSES', 'CHECKIN_REALIZADO', 'Check-in realizado'),
    ('GUEST_STATUSES', 'CHECKOUT_REALIZADO', 'Check-out realizado'),
    ('GUEST_STATUSES', 'NOSHOW', 'No-Show'),
    ('GUEST_STATUSES', 'PRECHECKIN_NAOVINCULADO', 'Pré-check-in não vinculado'),
    ('GUEST_STATUSES', 'PRECHECKIN_PENDENTE', 'Pré-check-in pendente'),
    ('GUEST_STATUSES', 'PRECHECKIN_REALIZADO', 'Pré-check-in realizado'),
    ('GENDERS', 'HOMEM', 'Masculino'),
    ('GENDERS', 'MULHER', 'Feminino'),
    ('GENDERS', 'OUTRO', 'Outros'),
    ('GENDERS', 'NAOINFORMADO', 'Prefiro não informar'),
    ('DISABILITY_OPTIONS', 'SIM', 'Sim'),
    ('DISABILITY_OPTIONS', 'NAO', 'Não'),
    ('DISABILITY_OPTIONS', 'NAOINFORMAR', 'Prefiro não informar'),
    ('RACES', 'AMARELA', 'Amarelo'),
    ('RACES', 'BRANCA', 'Branco'),
    ('RACES', 'INDIGENA', 'Indígena'),
    ('RACES', 'PARDA', 'Pardo'),
    ('RACES', 'PRETA', 'Preto'),
    ('RACES', 'NAOINFORMAR', 'Prefiro não informar'),
    ('DISABILITY_TYPES', 'FISICA', 'Deficiência Física'),
    ('DISABILITY_TYPES', 'AUDITIVA_SURDEZ', 'Deficiência Auditiva/Surdez'),
    ('DISABILITY_TYPES', 'VISUAL', 'Deficiência Visual'),
    ('DISABILITY_TYPES', 'INTELECTUAL', 'Deficiência Intelectual'),
    ('DISABILITY_TYPES', 'MULTIPLA', 'Deficiência Múltipla'),
    ('DOCUMENT_TYPES', 'CPF', 'CPF'),
    ('DOCUMENT_TYPES', 'PASSAPORTE', 'Passaporte'),
    ('RESERVATION_STATUSES', 'CRIADA', 'Criada'),
    ('RESERVATION_STATUSES', 'EM_ANDAMENTO', 'Em Andamento'),
    ('RESERVATION_STATUSES', 'CONCLUIDA', 'Concluída'),
    ('RESERVATION_STATUSES', 'CANCELADA', 'Cancelada'),
    ('RESERVATION_STATUSES', 'NOSHOW', 'No-Show'),
    ('FORM_STATUSES', 'AGUARDANDO_CHECKIN', 'Aguardando Check-in'),
    ('FORM_STATUSES', 'AGUARDANDO_CHECKOUT', 'Aguardando Check-out'),
    ('FORM_STATUSES', 'CHECK_OUT_CONFIRMADO', 'Check-out confirmado'),
    ('FORM_STATUSES', 'INVALIDADA', 'Invalidada'),
    ('FORM_STATUSES', 'NO_SHOW', 'No show')
) AS seed (domain, code, label);

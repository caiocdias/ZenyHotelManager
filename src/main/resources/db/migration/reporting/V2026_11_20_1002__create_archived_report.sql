-- O índice dos documentos arquivados por data (M6 E23, D3 e D4; RF-REL): uma linha por (data operacional, tipo) liga o
-- documento ao anexo sensível do files (finalidade reporting.day-report; o id da linha é o dono do anexo). Só de
-- inserção: o papel da aplicação não altera nem apaga, e o arquivo de uma data não é refeito. O file_id não tem chave
-- estrangeira (o files é outro módulo; o vínculo é pelo dono do anexo). kind: hoje só DAY_REPORT, o PDF do fechamento.
CREATE TABLE reporting.archived_report
(
    id            UUID                     NOT NULL,
    business_date DATE                     NOT NULL,
    kind          TEXT                     NOT NULL,
    file_id       UUID                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT archived_report_pk PRIMARY KEY (id),
    CONSTRAINT archived_report_date_kind_uk UNIQUE (business_date, kind),
    CONSTRAINT archived_report_kind_ck CHECK (kind IN ('DAY_REPORT'))
);

REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE reporting.archived_report FROM "${app_role}";

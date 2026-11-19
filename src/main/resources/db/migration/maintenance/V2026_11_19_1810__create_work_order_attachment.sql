-- Os anexos das ordens de serviço (M6 E07, D5): o vínculo de uma ordem com um arquivo do módulo `files` (foto ou PDF),
-- com quem vinculou e quando. Desvincular marca detached_at e detached_by, sem DELETE (o papel da aplicação não o
-- tem); vincular de novo o mesmo arquivo gera outra linha. O id do arquivo é opaco (files), sem FK entre schemas. Um
-- arquivo tem no máximo um vínculo vigente (o `files` só o deixa em um registro).

CREATE TABLE maintenance.work_order_attachment
(
    id             UUID                     NOT NULL,
    work_order_id  UUID                     NOT NULL,
    file_id        UUID                     NOT NULL,
    purpose        VARCHAR(64)              NOT NULL,
    attached_by    UUID                     NOT NULL,
    attached_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    detached_by    UUID,
    detached_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT work_order_attachment_pk PRIMARY KEY (id),
    CONSTRAINT work_order_attachment_order_fk FOREIGN KEY (work_order_id) REFERENCES maintenance.work_order (id),
    CONSTRAINT work_order_attachment_detached_ck CHECK ((detached_at IS NULL) = (detached_by IS NULL))
);

-- Um vínculo vigente por arquivo.
CREATE UNIQUE INDEX work_order_attachment_file_uk ON maintenance.work_order_attachment (file_id)
    WHERE detached_at IS NULL;
-- Os anexos vigentes de uma ordem, em ordem de vínculo.
CREATE INDEX work_order_attachment_order_idx ON maintenance.work_order_attachment (work_order_id, attached_at, id)
    WHERE detached_at IS NULL;

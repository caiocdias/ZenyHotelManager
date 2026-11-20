-- As observações das ordens de serviço (M6 E08, D3): texto livre do operador (sem dado de hóspede, RN-13), com o autor e
-- o instante. Só de inserção: a observação não se edita nem se apaga (a grant da migração seguinte tira o UPDATE). O id
-- é do servidor ou, no comando offline, o commandId.

CREATE TABLE maintenance.work_order_note
(
    id            UUID                     NOT NULL,
    work_order_id UUID                     NOT NULL,
    text          VARCHAR(1000)            NOT NULL,
    author_id     UUID                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT work_order_note_pk PRIMARY KEY (id),
    CONSTRAINT work_order_note_order_fk FOREIGN KEY (work_order_id) REFERENCES maintenance.work_order (id),
    CONSTRAINT work_order_note_text_ck CHECK (length(btrim(text)) > 0)
);

-- As observações de uma ordem, em ordem de registro.
CREATE INDEX work_order_note_order_idx ON maintenance.work_order_note (work_order_id, created_at, id);

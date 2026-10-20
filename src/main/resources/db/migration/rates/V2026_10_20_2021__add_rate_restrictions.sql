-- Restrições de venda por data, plano e tipo de UH (SPEC §5.6 e §8.2.5, RF-TAR-05; M4 E03, H15 e H16 do roadmap do
-- M4). O id do tipo de UH (rooms) é opaco: conferido pelo vínculo do plano, sem FK entre schemas. A tabela recebe DML
-- do papel da aplicação pelo ALTER DEFAULT PRIVILEGES da migração de privilégios do schema.
--
-- Uma linha por plano, data e tipo de UH; room_type_id nulo = a restrição GERAL do plano (vale para todos os tipos
-- dele). Precedência (decisão do usuário em 2026-10-20): a restrição do tipo substitui a geral CAMPO A CAMPO; o campo
-- nulo (não informado) herda o da geral, e o da geral nulo não restringe. Por isso todo campo é anulável e os booleanos
-- são tri-estado: numa linha do tipo, closed = false abre uma exceção sobre a geral closed = true.
--
-- Semântica para a avaliação (E04) e para o channel manager (M8), decidida pelo usuário em 2026-10-20:
--   closed: fecha a venda de qualquer noite da estadia (da chegada à véspera da saída);
--   closed_to_arrival (CTA): só impede chegar nesta data; closed_to_departure (CTD): só impede sair nesta data;
--   min_stay/max_stay (noites) e min/max_advance_days (dias da data operacional até a chegada, H15): valem os da
--   data de chegada ("on arrival"), não os de cada noite.
-- A linha que fica sem nenhum campo sai (DELETE auditado pela aplicação); restrições não entram no rates.refuse_delete.
CREATE TABLE rates.rate_restriction
(
    rate_plan_id        UUID                     NOT NULL,
    room_type_id        UUID,
    stay_date           DATE                     NOT NULL,
    closed              BOOLEAN,
    closed_to_arrival   BOOLEAN,
    closed_to_departure BOOLEAN,
    min_stay            INT,
    max_stay            INT,
    min_advance_days    INT,
    max_advance_days    INT,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT rate_restriction_uk UNIQUE NULLS NOT DISTINCT (rate_plan_id, room_type_id, stay_date),
    CONSTRAINT rate_restriction_plan_fk FOREIGN KEY (rate_plan_id) REFERENCES rates.rate_plan (id),
    CONSTRAINT rate_restriction_not_empty_ck CHECK (
        num_nonnulls(closed, closed_to_arrival, closed_to_departure, min_stay, max_stay, min_advance_days,
                     max_advance_days) > 0
        ),
    CONSTRAINT rate_restriction_values_ck CHECK (
        (min_stay IS NULL OR min_stay BETWEEN 1 AND 365)
            AND (max_stay IS NULL OR max_stay BETWEEN 1 AND 365)
            AND (min_advance_days IS NULL OR min_advance_days BETWEEN 0 AND 730)
            AND (max_advance_days IS NULL OR max_advance_days BETWEEN 0 AND 730)
        ),
    CONSTRAINT rate_restriction_ranges_ck CHECK (
        (min_stay IS NULL OR max_stay IS NULL OR min_stay <= max_stay)
            AND (min_advance_days IS NULL OR max_advance_days IS NULL OR min_advance_days <= max_advance_days)
        )
);

CREATE INDEX rate_restriction_plan_date_idx ON rates.rate_restriction (rate_plan_id, stay_date);

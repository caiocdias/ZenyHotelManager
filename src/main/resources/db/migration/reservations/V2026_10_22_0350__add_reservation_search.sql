-- Busca de reservas, listas operacionais e categoria do plano (M4 E12; RF-RES-11, RF-RES-13, SPEC Q-15).
--
-- reservation.rate_plan_category: a categoria do plano tarifário da reserva (NORMAL, COURTESY ou HOUSE_USE; Q-15: a
-- cortesia conta na ocupação e não no ADR, o uso da casa em nenhum dos dois), copiada da RatesApi na criação e na
-- troca de plano. A categoria de um plano é fixa desde a criação dele, então a cópia nunca diverge; as views de
-- relatório do reservations a leem daqui, sem ler o schema do rates. Nenhuma implantação existe antes do M8: numa base
-- de desenvolvimento com reservas anteriores a esta migração, elas ficam NORMAL (o DEFAULT só preenche as linhas que já
-- existem e sai em seguida; a aplicação grava sempre a categoria).
ALTER TABLE reservations.reservation
    ADD COLUMN rate_plan_category varchar(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE reservations.reservation ALTER COLUMN rate_plan_category DROP DEFAULT;
ALTER TABLE reservations.reservation
    ADD CONSTRAINT reservation_rate_plan_category_ck
        CHECK (rate_plan_category IN ('NORMAL', 'COURTESY', 'HOUSE_USE'));

-- A busca e a lista de chegadas (e os no-shows previstos) leem pela chegada, na ordem (chegada, id) do cursor.
CREATE INDEX reservation_arrival_idx ON reservations.reservation (arrival, id);
-- A lista de saídas lê pela saída.
CREATE INDEX reservation_departure_idx ON reservations.reservation (departure, id);
-- A lista de hospedados lê só as IN_HOUSE (a partir do M5), pela chegada.
CREATE INDEX reservation_in_house_idx ON reservations.reservation (arrival, id) WHERE status = 'IN_HOUSE';
-- A busca por período procura as estadias que tocam o período (inclusive o dia da saída e o day use).
CREATE INDEX reservation_stay_period_idx
    ON reservations.reservation USING gist (daterange(arrival, departure, '[]'));

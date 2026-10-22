-- Lista dos no-shows previstos (M4 E12; RF-RES-13): só as reservas confirmadas, pela chegada, na ordem (chegada, id)
-- do cursor. Os no-shows previstos da data operacional corrente pegam também as chegadas já passadas
-- (arrival <= data), e o índice parcial as lê sem percorrer as reservas de outras situações.
CREATE INDEX reservation_confirmed_arrival_idx ON reservations.reservation (arrival, id) WHERE status = 'CONFIRMED';

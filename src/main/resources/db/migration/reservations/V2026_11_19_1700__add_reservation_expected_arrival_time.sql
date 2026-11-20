-- A hora prevista de chegada (M6 E22): hora local do hotel, sem data nem fuso, até segundos. Anulável: as reservas
-- existentes e as criadas sem ela ficam sem hora. Nenhum dado é apagado.
ALTER TABLE reservations.reservation ADD COLUMN expected_arrival_time time;

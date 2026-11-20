-- Views de leitura do módulo rooms para os relatórios operacionais (M6 E09, D1; ARCHITECTURE §11 "Leitura para
-- relatórios"). Fazem parte da API do módulo: o módulo reporting lê só elas, nunca as tabelas internas. Uma coluna
-- publicada não muda de nome nem de sentido; uma mudança incompatível cria outra view. Cada linha é um fato; quem
-- consulta filtra e agrega. Nenhuma lê a data operacional nem o relógio: o "em vigor" é decidido por quem consulta.
--
-- Conteúdo: ids opacos, situações, datas e instantes. Nada de texto livre (observações da UH, motivo e responsável do
-- bloqueio) nem dado de hóspede (RN-13).
--
-- rooms.report_room_status: uma linha por UH, ativa ou não (quem consulta filtra `active`).
--   room_id              UUID  a UH
--   room_type_id         UUID  o tipo de UH
--   floor                text  o andar (rótulo de cadastro, nulo se não informado)
--   active               bool  a UH está ativa
--   occupancy            text  VACANT | OCCUPIED
--   housekeeping_status  text  DIRTY | CLEAN | INSPECTED
--   inspection_pending   bool  limpa e à espera de inspeção
--   status_changed_at    timestamptz  a última mudança do status de governança
CREATE VIEW rooms.report_room_status AS
SELECT r.id AS room_id,
       r.room_type_id,
       r.floor,
       r.active,
       r.occupancy,
       r.housekeeping_status,
       r.inspection_pending,
       r.status_changed_at
FROM rooms.room r;

-- rooms.report_out_of_order: uma linha por período de fora de ordem (OOO) ou fora de serviço (OOS), liberado ou não.
--   out_of_order_id  UUID  o período
--   room_id          UUID  a UH
--   kind             text  OUT_OF_ORDER | OUT_OF_SERVICE
--   starts_on        date  o primeiro dia
--   ends_on          date  o último dia previsto, inclusive
--   released_on      date  o dia da liberação antecipada (nulo enquanto não liberado); o período vale de starts_on
--                          até o menor entre ends_on e released_on - 1, e quem consulta decide o "em vigor" com a data
--                          operacional do hotel
CREATE VIEW rooms.report_out_of_order AS
SELECT o.id AS out_of_order_id,
       o.room_id,
       o.kind,
       o.starts_on,
       o.ends_on,
       o.released_on
FROM rooms.out_of_order o;

-- Privilégios: o ALTER DEFAULT PRIVILEGES da pasta também vale para views e daria DML ao papel da aplicação; ele só
-- lê. A view roda com os privilégios do dono (sem security_invoker).
GRANT SELECT ON rooms.report_room_status TO "${app_role}";
GRANT SELECT ON rooms.report_out_of_order TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON rooms.report_room_status FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON rooms.report_out_of_order FROM "${app_role}";

-- View de leitura do módulo maintenance para os relatórios operacionais (M6 E09, D1; ARCHITECTURE §11 "Leitura para
-- relatórios"). Faz parte da API do módulo: o módulo reporting lê só ela. Uma coluna publicada não muda de nome nem de
-- sentido; uma mudança incompatível cria outra view. Cada linha é um fato; quem consulta filtra e agrega.
--
-- Conteúdo: ids opacos, o número legível, situações, instantes e custos. Nada de texto livre (descrição, motivos,
-- observações de conclusão) nem o nome do terceiro, nem dado de hóspede (RN-13).
--
-- maintenance.report_work_orders: uma linha por ordem de serviço, em qualquer status.
--   work_order_id     UUID  a ordem
--   number            text  o número legível (OS-<ano>-NNNNNN)
--   room_id           UUID  a UH (nulo numa ordem de área)
--   area_id           UUID  a área comum (nulo numa ordem de UH)
--   category          text  ELECTRICAL | PLUMBING | AIR_CONDITIONING | FURNITURE | OTHER
--   priority          text  LOW | NORMAL | HIGH | URGENT
--   origin            text  RECEPTION | HOUSEKEEPING | MAINTENANCE | CHECKLIST
--   status            text  OPEN | IN_PROGRESS | ON_HOLD | DONE | CANCELLED
--   opened_at         timestamptz  a abertura
--   started_at        timestamptz  o primeiro início
--   closed_at         timestamptz  a conclusão ou o cancelamento (nulo enquanto aberta)
--   labor_cost        numeric(7,2)  mão de obra
--   third_party_cost  numeric(7,2)  terceiro
--   parts_cost        numeric(7,2)  peças
CREATE VIEW maintenance.report_work_orders AS
SELECT w.id AS work_order_id,
       w.number,
       w.room_id,
       w.area_id,
       w.category,
       w.priority,
       w.origin,
       w.status,
       w.opened_at,
       w.started_at,
       w.closed_at,
       w.labor_cost,
       w.third_party_cost,
       w.parts_cost
FROM maintenance.work_order w;

-- Privilégios: o ALTER DEFAULT PRIVILEGES da pasta também vale para views e daria DML ao papel da aplicação; ele só
-- lê. A view roda com os privilégios do dono (sem security_invoker).
GRANT SELECT ON maintenance.report_work_orders TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON maintenance.report_work_orders FROM "${app_role}";

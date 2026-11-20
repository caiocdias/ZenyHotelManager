-- Views de leitura do módulo housekeeping para os relatórios operacionais (M6 E09, D1; ARCHITECTURE §11 "Leitura
-- para relatórios"). Fazem parte da API do módulo: o módulo reporting lê só elas. Uma coluna publicada não muda de
-- nome nem de sentido; uma mudança incompatível cria outra view. Cada linha é um fato; quem consulta filtra e agrega.
--
-- Conteúdo: ids opacos, situações, datas, instantes e contagens. Nada de texto livre (observações da tarefa, motivo da
-- recusa) nem dado de hóspede (RN-13).
--
-- housekeeping.report_tasks: uma linha por tarefa, em qualquer status.
--   task_id               UUID  a tarefa
--   business_date         date  a data operacional da tarefa
--   room_id               UUID  a UH (nulo numa tarefa de área)
--   area_id               UUID  a área comum (nulo numa tarefa de UH)
--   cleaning_type_id      UUID  o tipo de limpeza
--   source                text  CHECKOUT | ROOM_MOVE | STAYOVER | PRE_ARRIVAL | SCHEDULED | AD_HOC
--   status                text  PENDING | ASSIGNED | IN_PROGRESS | PAUSED | AWAITING_INSPECTION | REJECTED | DONE |
--                               REFUSED | CANCELLED; contam como limpeza feita AWAITING_INSPECTION e DONE
--   credits               int   os créditos do tipo na geração da tarefa
--   assigned_employee_id  UUID  a camareira (colaborador do staff); nulo sem atribuição
--   started_at            timestamptz  o primeiro início
--   finished_at           timestamptz  a conclusão da limpeza
--   worked_seconds        bigint  a soma dos trechos em andamento, sem as pausas
CREATE VIEW housekeeping.report_tasks AS
SELECT t.id AS task_id,
       t.business_date,
       t.room_id,
       t.area_id,
       t.cleaning_type_id,
       t.source,
       t.status,
       t.credits,
       t.assigned_employee_id,
       t.started_at,
       t.finished_at,
       t.worked_seconds
FROM housekeeping.housekeeping_task t;

-- housekeeping.report_inspections: uma linha por inspeção concluída (a em andamento não entra).
--   inspection_id  UUID  a inspeção
--   task_id        UUID  a tarefa inspecionada (nulo na inspeção voluntária)
--   room_id        UUID  a UH
--   result         text  APPROVED | REJECTED
--   completed_at   timestamptz  a conclusão
--   inspector_id   UUID  o usuário que inspecionou
CREATE VIEW housekeeping.report_inspections AS
SELECT i.id AS inspection_id,
       i.task_id,
       i.room_id,
       i.result,
       i.completed_at,
       i.inspector_id
FROM housekeeping.inspection i
WHERE i.status = 'COMPLETED';

-- Privilégios: o ALTER DEFAULT PRIVILEGES da pasta também vale para views e daria DML ao papel da aplicação; ele só
-- lê. A view roda com os privilégios do dono (sem security_invoker).
GRANT SELECT ON housekeeping.report_tasks TO "${app_role}";
GRANT SELECT ON housekeeping.report_inspections TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON housekeeping.report_tasks FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON housekeeping.report_inspections FROM "${app_role}";

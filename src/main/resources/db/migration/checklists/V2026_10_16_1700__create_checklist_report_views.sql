-- Views de leitura para relatórios do módulo checklists (M2 E08, RF-CHK-09, ARCHITECTURE §11 "Leitura para
-- relatórios"). Fazem parte da API do módulo: os endpoints de /api/v1/checklist-reports e, no M6, o módulo
-- reporting leem só elas, nunca as tabelas internas. Uma coluna publicada não muda de nome nem de sentido; uma
-- mudança incompatível cria outra view. Cada linha é um fato (uma execução, uma resposta), sem agregar por
-- período: quem consulta filtra e agrega.
--
-- Conteúdo: ids opacos (modelo, versão, sujeito, rotina, executor = id de usuário), o nome do modelo (o atual) e o
-- rótulo do item (o da versão usada), situações, horários e contagens. Nada de texto livre de quem executa
-- (resposta de texto, observação), foto nem dado de hóspede (RN-13).
--
-- Conformidade: só os itens de marcação (CHECK) têm resultado. OK conta como conforme, NOT_OK como não conforme e
-- NOT_APPLICABLE fica FORA do denominador: evaluated_count = ok_count + not_ok_count, e a taxa é
-- ok_count / evaluated_count (sem taxa quando nada foi avaliado). Item oculto pela condição não tem resposta na
-- execução concluída (a conclusão a retira), então não conta.

-- Uma linha por execução concluída, com as contagens das respostas de marcação. Inclui a execução registrada à
-- parte de uma rotina (duplicate_of, RN-22): é uma verificação que de fato aconteceu.
CREATE VIEW checklists.report_execution_compliance AS
SELECT e.id              AS execution_id,
       e.template_id,
       t.name            AS template_name,
       e.context,
       e.version_id,
       e.version_number,
       e.subject_type,
       e.subject_id,
       e.routine_id,
       e.business_date,
       e.duplicate_of,
       e.executor_id,
       e.started_at,
       e.completed_at,
       c.ok_count,
       c.not_ok_count,
       c.not_applicable_count,
       c.ok_count + c.not_ok_count AS evaluated_count
FROM checklists.checklist_execution e
         JOIN checklists.checklist_template t ON t.id = e.template_id
         CROSS JOIN LATERAL (
    SELECT count(*) FILTER (WHERE a.check_value = 'OK')             AS ok_count,
           count(*) FILTER (WHERE a.check_value = 'NOT_OK')         AS not_ok_count,
           count(*) FILTER (WHERE a.check_value = 'NOT_APPLICABLE') AS not_applicable_count
    FROM checklists.checklist_answer a
    WHERE a.execution_id = e.id
      AND a.item_type = 'CHECK'
      AND a.check_value IS NOT NULL) c
WHERE e.status = 'COMPLETED';

-- Uma linha por resposta de marcação (CHECK) de uma execução concluída, com o rótulo do item na versão usada
-- (a chave do item é estável entre as versões; o rótulo pode mudar). outcome: OK, NOT_OK ou NOT_APPLICABLE.
CREATE VIEW checklists.report_check_item_outcome AS
SELECT e.id                          AS execution_id,
       e.template_id,
       t.name                        AS template_name,
       e.context,
       e.version_id,
       e.version_number,
       e.subject_type,
       e.subject_id,
       e.routine_id,
       e.completed_at,
       a.item_key,
       COALESCE(l.label, a.item_key) AS item_label,
       a.check_value                 AS outcome
FROM checklists.checklist_execution e
         JOIN checklists.checklist_template t ON t.id = e.template_id
         JOIN checklists.checklist_version v ON v.id = e.version_id
         JOIN checklists.checklist_answer a ON a.execution_id = e.id
         LEFT JOIN LATERAL (
    SELECT i ->> 'label' AS label
    FROM jsonb_array_elements(v.content -> 'sections') s,
         jsonb_array_elements(s -> 'items') i
    WHERE i ->> 'key' = a.item_key
    LIMIT 1) l ON true
WHERE e.status = 'COMPLETED'
  AND a.item_type = 'CHECK'
  AND a.check_value IS NOT NULL;

-- Uma linha por execução com sujeito (o local: UH, área…), em qualquer situação: a pendente de rotina que ninguém
-- fez também é histórico. reference_at posiciona a execução no histórico: a conclusão, senão o início, senão o
-- prazo da rotina. As contagens são as das respostas gravadas (parciais enquanto em andamento; zero na pendente).
CREATE VIEW checklists.report_location_history AS
SELECT e.id                                         AS execution_id,
       e.subject_type,
       e.subject_id,
       e.template_id,
       t.name                                       AS template_name,
       e.context,
       e.version_id,
       e.version_number,
       e.status,
       e.routine_id,
       e.business_date,
       e.due_at,
       e.duplicate_of,
       e.executor_id,
       e.started_at,
       e.completed_at,
       COALESCE(e.completed_at, e.started_at, e.due_at) AS reference_at,
       c.ok_count,
       c.not_ok_count,
       c.not_applicable_count,
       c.ok_count + c.not_ok_count                  AS evaluated_count
FROM checklists.checklist_execution e
         JOIN checklists.checklist_template t ON t.id = e.template_id
         CROSS JOIN LATERAL (
    SELECT count(*) FILTER (WHERE a.check_value = 'OK')             AS ok_count,
           count(*) FILTER (WHERE a.check_value = 'NOT_OK')         AS not_ok_count,
           count(*) FILTER (WHERE a.check_value = 'NOT_APPLICABLE') AS not_applicable_count
    FROM checklists.checklist_answer a
    WHERE a.execution_id = e.id
      AND a.item_type = 'CHECK'
      AND a.check_value IS NOT NULL) c
WHERE e.subject_id IS NOT NULL;

-- Os filtros dos relatórios: período pela conclusão e histórico de um local em ordem de referência.
CREATE INDEX checklist_execution_completed_idx ON checklists.checklist_execution (completed_at)
    WHERE status = 'COMPLETED';
CREATE INDEX checklist_execution_subject_idx ON checklists.checklist_execution (subject_id, subject_type)
    WHERE subject_id IS NOT NULL;

-- O papel da aplicação só LÊ as views. O ALTER DEFAULT PRIVILEGES da pasta (V2026_10_16_0341) daria também
-- INSERT, UPDATE e DELETE (views contam como tabelas nos privilégios padrão); são retirados aqui, e o
-- ChecklistReportsTest e o DatabaseRolesTest conferem que sobra só SELECT.
GRANT SELECT ON checklists.report_execution_compliance TO "${app_role}";
GRANT SELECT ON checklists.report_check_item_outcome TO "${app_role}";
GRANT SELECT ON checklists.report_location_history TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON checklists.report_execution_compliance FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON checklists.report_check_item_outcome FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON checklists.report_location_history FROM "${app_role}";

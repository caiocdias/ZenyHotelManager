-- As ocorrências do checklist e o aviso do prazo de guarda (M6 E06, Task 3; D3 e D5).
--
-- Um registro só por item de execução de checklist em cada tabela (achado e dano são tabelas à parte, então a chave
-- executionId + itemKey + tipo do ouvinte de ChecklistCompleted vira executionId + itemKey em cada uma): a reentrega
-- do evento pelo outbox não duplica. As origens MANUAL e OFFLINE não têm execução (NULL), e NULLs não colidem.
CREATE UNIQUE INDEX lost_and_found_item_checklist_uk
    ON housekeeping.lost_and_found_item (checklist_execution_id, checklist_item_key);

CREATE UNIQUE INDEX damage_report_checklist_uk
    ON housekeeping.damage_report (checklist_execution_id, checklist_item_key);

-- Quando o item guardado e vencido foi avisado a quem gere os achados (o job do prazo de guarda, uma vez por item);
-- dado técnico, fora da versão do item.
ALTER TABLE housekeeping.lost_and_found_item
    ADD COLUMN expiry_notified_at TIMESTAMP WITH TIME ZONE;

-- Os vencidos ainda não avisados, que o job lê a cada dia.
CREATE INDEX lost_and_found_item_expiry_notice_idx
    ON housekeeping.lost_and_found_item (found_at)
    WHERE status = 'STORED' AND expiry_notified_at IS NULL;

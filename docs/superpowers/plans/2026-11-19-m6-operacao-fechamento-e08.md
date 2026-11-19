# E08 do M6 — OS por checklist e app offline da manutenção: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** abrir OS automaticamente a partir das ocorrências `MAINTENANCE` de `ChecklistCompleted` (RF-CHK-05, ARCHITECTURE §6.2) e deixar o técnico operar a OS offline (RF-MAN-07), na mesma base de sincronização da governança, sem dado de hóspede no feed (RN-13).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E08", H2, H21 (e) e os handoffs da E07, da E19 (padrão offline) e da E06 (consumo de `ChecklistCompleted`); SPEC RF-CHK-05, RF-MAN-01, RF-MAN-07, RN-13, RN-22; ARCHITECTURE §3.1, §6.2, §9. Brain: `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Testes de integração` e as capturas pendentes da E06, E19 e E07.

**Architecture:** o `maintenance` escuta `ChecklistCompleted` (aresta `maintenance → checklists`, já na linha da §3.1). O local da OS vem do próprio evento: o `checklists` passa a guardar e publicar o **local da execução** (`ChecklistLocation(roomId | areaId)`), informado por quem é dono do sujeito ao iniciar (a governança passa a UH ou a área da tarefa) ou derivado do sujeito `rooms.room`/`rooms.area` de uma rotina. Assim o `maintenance` não depende do `housekeeping` (mesma camada, fora da §3.1) e o `checklists`, que é plataforma, não conhece a manutenção. O offline segue o padrão da E19: comandos `maintenance.<verbo>-work-order` pelos mesmos casos de uso do online, `commandId` como id da OS aberta offline, `occurredAt` corrigido, fonte `maintenance-work-orders` só com dados da OS e do local.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopo `OWN` na fonte offline e nos comandos; quem pode abrir OS offline; a execução avulsa de checklist não cria OS por um local escolhido pelo cliente (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** payloads offline (Task 3);
- **concorrência:** reentrega do evento pelo outbox e a mesma ação online e offline (Tasks 2 e 3);
- **dado pessoal ou segredo:** feed e respostas sem dado de hóspede (RN-13); texto livre fora da trilha (Tasks 2 e 3);
- **migração ou escrita destrutiva de dados:** colunas novas em `checklists` e `maintenance` (Tasks 1 e 2).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` exato: `maintenance` passa a depender também de `checklists` (`shared, hotel, rooms, staff, checklists, files, notifications`); nenhuma outra aresta; `checklists` não ganha dependência; `maintenance` não depende de `housekeeping` nem de `inventory`.
- Migrações novas com timestamp posterior à última do projeto no momento; colunas anuláveis, sem apagar dado.
- Padrão offline da E19 (nota `ZenyHotelManager - Sincronização offline` e captura da E19): handler rejeita lançando `DomainException` (vira `REJECTED`), alertas por `warnings`, `CLOCK_SKEW_EXCEEDED` é alerta, nomes de fonte em kebab-case no plural e de comando `módulo.verbo-objeto`; registro criado offline usa o `commandId` como id; idempotência pelo `commandId` (o `shared` já guarda).
- Entrada estrita da E20 também nos payloads offline; `409 CONCURRENT_UPDATE` da E21.
- RN-13: nada de dado de hóspede no feed, nas respostas ou nos avisos; texto livre (descrição, motivos, observações) fora da trilha.
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, só os próprios dados; quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)`; eventos assíncronos esperados pelo padrão do projeto (sem `sleep` cego).
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Reentrega do mesmo `ChecklistCompleted`** e **execução registrada à parte de uma rotina (`duplicateOf`)** com o mesmo item "não ok": uma OS só. Teste na Task 2.
2. **Execução avulsa com sujeito `rooms.room` escolhido pelo cliente:** não cria OS (local não confiável), com log só de ids; a de rotina e a da governança criam. Teste na Task 2.
3. **Comando offline de iniciar ou concluir uma OS já concluída ou cancelada online:** `REJECTED` com `WORK_ORDER_TRANSITION_NOT_ALLOWED`, sem mudar nada; o mesmo `commandId` reenviado devolve o resultado gravado. Teste na Task 3.
4. **OS aberta offline com o `commandId` de uma OS que já existe com outro conteúdo:** `REJECTED` (conflito), não sobrescreve. Teste na Task 3.
5. **Técnico `OWN` baixando o feed:** só as suas OS; a OS reatribuída a outro sai do feed dele (*tombstone* de escopo). Teste na Task 3.

## Decisões do plano

- **D1 — local da execução no `checklists`** (decisão de arquitetura tomada pelo orquestrador pela opção recomendada, sob a autorização do usuário de 2026-11-19; alternativas descartadas: aresta `maintenance → housekeeping` na mesma camada, fora da §3.1; o `housekeeping` chamar o `maintenance`, também mesma camada; e só aceitar rotinas, o que perderia o caso principal, a camareira que acha o defeito na limpeza). `ChecklistLocation(roomId: UUID?, areaId: UUID?)` (exatamente um) na raiz do `checklists`; `ChecklistsApi.startExecution(..., location: ChecklistLocation? = null)` grava o local informado pelo módulo dono do sujeito; numa rotina de sujeito `rooms.room`/`rooms.area`, o local é o sujeito; a execução avulsa iniciada pelo cliente (endpoint e comando offline do `checklists`) fica **sem** local, mesmo com sujeito `rooms.*`, porque o sujeito é escolhido pelo cliente (M1 da revisão da E06). Colunas `location_room_id`/`location_area_id` anuláveis na execução; `ChecklistExecutionView.location` e `ChecklistCompleted.location` (campo novo com padrão `null`, evento interno). A governança passa a UH ou a área da tarefa em todo `startExecution` dela (tarefa e inspeção, online e offline).
- **D2 — OS por checklist.** `MaintenanceChecklistListener` (`@ApplicationModuleListener` de `ChecklistCompleted`): para cada ação `OPEN_OCCURRENCE` com `occurrenceKind = MAINTENANCE`, com `location` presente, abre uma OS `origin = CHECKLIST`, `priority = NORMAL`, `category` = o `category` da ação quando for um nome de `WorkOrderCategory`, senão `OTHER`; `description` = rótulo do item (da versão), categoria e observação, até 2000; `openedBy` = executor; `openedAt` = `completedAt`; vínculo `checklistExecutionId` + `checklistItemKey`. Idempotência: índice único `(checklist_execution_id, checklist_item_key)` onde a execução de referência é `duplicateOf ?: executionId` (a execução registrada à parte de uma rotina não abre segunda OS do mesmo item); a reentrega devolve a OS gravada. Sem `location`: ignora com log só de ids. A trilha da abertura é a do caso de uso (ator sistema/executor, sem texto livre). Sem aviso urgente (prioridade normal).
- **D3 — observações.** `POST /api/v1/work-orders/{id}/notes` `{text}` (1 a 1000; `manage`, ou `request` na OS que o ator abriu; OS final recusa), tabela `maintenance.work_order_note` só de inserção (id, OS, texto, autor, instante); a resposta da OS lista as observações (id, texto, autor, instante) em ordem; não vão à trilha (texto livre), só o fato.
- **D4 — fonte offline** `maintenance-work-orders` (`MaintenanceOffline.WORK_ORDERS`, permissão `maintenance.workorders.read`): as OS não finais e as finais há até 7 dias, com id, número, local (`roomId`/`areaId`, número da UH ou nome da área), categoria, prioridade, status, descrição, motivo da espera, responsável (id), prazo, versão e as observações; `OWN` só as suas (quem abriu ou responsável); saída do escopo `OWN` (reatribuída) e conclusão há mais de 7 dias geram *tombstone* (pelo `OfflineTombstones` ou derivado, como na E19). Nada de dado de hóspede (a OS não tem).
- **D5 — comandos offline** (`MaintenanceOffline`), pelos mesmos casos de uso do online, com o `occurredAt` corrigido como instante da transição: `maintenance.open-work-order` (`commandId` = id da OS; payload como o `POST`; permissão `request`), `maintenance.start-work-order`, `maintenance.hold-work-order` (`reason`), `maintenance.resume-work-order`, `maintenance.complete-work-order` (`notes` e custos opcionais como no online) e `maintenance.add-work-order-note` (`text`; `commandId` = id da observação), todos com o `workOrderId`, sem versão (a idempotência é o `commandId`), permissão `manage` (ou `request` para a observação na OS que abriu). Recusa vira `REJECTED` com o código do online. O offline **não** bloqueia nem libera UH e não cancela (exigem step-up online).

---

### Task 1: Local da execução no `checklists`

**Objetivo:** D1.

**Critérios de aceite:**
- `ChecklistExecutionLocationTest` (`@IntegrationTest`, novo, em `checklists`): `startExecution` com `location` grava e devolve o local na view e no `ChecklistCompleted`; rotina com sujeito `rooms.room` publica o local = sujeito; execução avulsa pelo endpoint com sujeito `rooms.room` publica `location = null`; `location` com UH e área juntas ou nenhuma → `IllegalArgumentException`. Verificação: `timeout 900 ./gradlew test --tests '*ChecklistExecutionLocationTest'`.
- A governança passa o local: um teste da E04/E05 estendido ou um novo (`HousekeepingChecklistLocationTest`) conclui uma tarefa com checklist e confere `location.roomId` no evento publicado. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingChecklistLocationTest'`.
- Os testes do `checklists` e da execução da governança seguem verdes: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*' --tests '*HousekeepingTaskExecution*' --tests '*HousekeepingInspection*' --tests '*HousekeepingOccurrence*'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `checklists/ChecklistExecutions.kt` (`ChecklistLocation`, view), `checklists/ChecklistsApi.kt`, `checklists/ChecklistsEvents.kt`, `checklists/application/` (caso de uso da execução e da rotina), `checklists/infrastructure/` (persistência da execução), migração em `db/migration/checklists/`, `housekeeping/application/HousekeepingTaskExecution.kt`, `housekeeping/application/HousekeepingInspections.kt` (e os handlers offline que iniciam execução, se chamarem `startExecution` direto).

**Tamanho:** ~50 min; 8 a 10 arquivos de produção, mais a migração.

**Depende de:** nenhuma.

**Interfaces (produz):** `ChecklistLocation(roomId: UUID?, areaId: UUID?)`; `ChecklistsApi.startExecution(versionId, subject, executor, startedAt, id, location: ChecklistLocation? = null)`; `ChecklistCompleted.location: ChecklistLocation?`; `ChecklistExecutionView.location`.

### Task 2: OS por checklist e observações

**Objetivo:** D2 e D3.

**Critérios de aceite:**
- `WorkOrderChecklistListenerTest` (`@IntegrationTest`, novo): `ChecklistCompleted` de uma execução da governança com item `MAINTENANCE` "não ok" gera exatamente uma OS `origin = CHECKLIST` com o local, o vínculo à execução e ao item, o executor como quem abriu e a categoria mapeada; reprocessar o mesmo evento não duplica; a execução `duplicateOf` com o mesmo item não abre outra (Review Focus 1); execução sem local (avulsa) não abre (Review Focus 2); item `LOST_AND_FOUND`/`DAMAGE` não abre OS. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderChecklistListenerTest'`.
- `WorkOrderNotesTest` (`@IntegrationTest`, novo): observação com `manage` e com `request` na OS própria (`201`), `403` na alheia sem `manage`, `409` em OS final, texto vazio ou com 1001 caracteres → `400`; a resposta da OS lista as observações; a trilha não leva o texto. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderNotesTest'`.
- `ModularityTest` com `checklists` entre as dependências do `maintenance`. Verificação: `timeout 900 ./gradlew test --tests '*ModularityTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/MaintenanceModule.kt`, `maintenance/application/MaintenanceChecklistListener.kt` (novo), `maintenance/application/WorkOrdersService.kt` (abertura pelo sistema), `maintenance/application/WorkOrderNotes.kt` (novo), `maintenance/domain/WorkOrder.kt` (vínculo ao checklist), `maintenance/infrastructure/` (colunas, índice, tabela de observações), `maintenance/web/WorkOrdersController.kt`, migrações em `db/migration/maintenance/`, `api/openapi.yaml`, `ModularityTest`.

**Tamanho:** ~55 min; 8 arquivos de produção, mais migrações e contrato.

**Depende de:** Task 1.

**Interfaces (consome):** `ChecklistCompleted.location` (Task 1). **Produz:** `WorkOrderNotes.add(workOrderId, noteId, text, actor, at)`; tabela `maintenance.work_order_note`.

### Task 3: App offline da manutenção

**Objetivo:** D4 e D5.

**Critérios de aceite:**
- `MaintenanceOfflineTest` (`@IntegrationTest`, novo): OS aberta offline é persistida com o UUID do `commandId`; o mesmo `commandId` reenviado não cria outra; `commandId` de OS existente com outro conteúdo → `REJECTED` (Review Focus 4); iniciar, pausar, retomar e concluir offline produzem o mesmo estado, os mesmos instantes (pelo `occurredAt` corrigido) e a mesma trilha que online; comando numa OS final → `REJECTED` com `WORK_ORDER_TRANSITION_NOT_ALLOWED` (Review Focus 3); observação offline com o `commandId` como id; payload fora do formato → `REJECTED` registrado. Verificação: `timeout 900 ./gradlew test --tests '*MaintenanceOfflineTest'`.
- `MaintenanceOfflineFeedTest` (`@IntegrationTest`, novo): o feed `maintenance-work-orders` do técnico traz as suas OS e locais (número da UH, nome da área) e nenhum campo de hóspede (conjunto exato de chaves do item); `OWN` só as suas; a OS reatribuída sai do feed dele (Review Focus 5); sem `read`, a fonte não vem. Verificação: `timeout 900 ./gradlew test --tests '*MaintenanceOfflineFeedTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/MaintenanceOffline.kt` (novo), `maintenance/application/WorkOrderOfflineHandlers.kt` (novo), `maintenance/infrastructure/WorkOrderOfflineSource.kt` (novo), `maintenance/application/WorkOrdersService.kt` (tombstone na reatribuição, se preciso), `maintenance/application/MaintenanceConfiguration.kt`.

**Tamanho:** ~55 min; 5 a 6 arquivos de produção.

**Depende de:** Task 2 (observações).

**Interfaces (consome):** `OfflineCommandHandler`, `OfflineChangeSource`, `OfflineTombstones` (`shared/OfflineSync.kt`); `WorkOrdersService` e `WorkOrderNotes`.

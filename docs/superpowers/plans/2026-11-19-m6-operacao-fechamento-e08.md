# E08 do M6 — OS por checklist e app offline da manutenção: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** abrir OS automaticamente a partir das ocorrências `MAINTENANCE` de `ChecklistCompleted` (RF-CHK-05, ARCHITECTURE §6.2) e deixar o técnico operar a OS offline (RF-MAN-07), na mesma base de sincronização da governança, sem dado de hóspede no feed (RN-13).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E08", H2, H21 (e) e os handoffs da E07, da E19 (padrão offline) e da E06 (consumo de `ChecklistCompleted`); SPEC RF-CHK-05, RF-MAN-01, RF-MAN-07, RN-13, RN-22; ARCHITECTURE §3.1, §6.2, §9. Brain: `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Testes de integração` e as capturas pendentes da E06, E19 e E07.

**Architecture:** o `maintenance` escuta `ChecklistCompleted` (aresta `maintenance → checklists`, já na linha da §3.1). O local da OS vem do próprio evento: o `checklists` passa a guardar e publicar o **local da execução** (`ChecklistLocation(roomId | areaId)`), informado por quem é dono do sujeito ao iniciar (a governança passa a UH ou a área da tarefa) ou derivado do sujeito `rooms.room`/`rooms.area` de uma rotina. Assim o `maintenance` não depende do `housekeeping` (mesma camada, fora da §3.1) e o `checklists`, que é plataforma, não conhece a manutenção. O offline segue o padrão da E19: comandos `maintenance.<verbo>-work-order` pelos mesmos casos de uso do online (variantes com o instante e sem versão), `commandId` como id do registro criado offline, `occurredAt` corrigido com a regra de ordem da E19, trilha `<verbo>-offline`, fonte `maintenance-work-orders` com posição de mudança por gatilho e *tombstone* derivado da OS final.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopo `OWN` na fonte offline e nos comandos; quem pode abrir OS offline; a execução avulsa de checklist não cria OS por um local escolhido pelo cliente (Tasks 1 a 3);
- **parsing de entrada externa não confiável:** payloads offline (Task 3);
- **concorrência:** reentrega do evento pelo outbox e a mesma ação online e offline (Tasks 2 e 3);
- **dado pessoal ou segredo:** feed e respostas sem dado de hóspede (RN-13); texto livre fora da trilha (Tasks 2 e 3);
- **migração ou escrita destrutiva de dados:** colunas novas em `checklists` e `maintenance` (Tasks 1 e 2).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` exato: `maintenance` passa a depender também de `checklists` (na Task 2) (`shared, hotel, rooms, staff, checklists, files, notifications`); nenhuma outra aresta; `checklists` não ganha dependência; `maintenance` não depende de `housekeeping` nem de `inventory`.
- Migrações novas com timestamp posterior à última do projeto no momento; colunas anuláveis, sem apagar dado.
- Padrão offline da E19 (nota `ZenyHotelManager - Sincronização offline`, captura e handoff da E19, D7): handler rejeita lançando `DomainException` (vira `REJECTED`), alertas por `warnings`, `CLOCK_SKEW_EXCEEDED` é alerta, nomes de fonte em kebab-case no plural e de comando `módulo.verbo-objeto`; registro criado offline usa o `commandId` como id; idempotência pelo `commandId` (o `shared` já guarda); instante = `max(occurredAt corrigido, última transição)`; trilha própria `<verbo>-offline` (regra ArchUnit `offlineCommandHandlersAreAudited`); posição de mudança pela coluna e gatilho do padrão (`housekeeping/V2026_11_19_1103__add_task_offline_change.sql`).
- Tabela só de inserção: `REVOKE UPDATE` na migração e entrada *append-only* no `DatabaseRolesTest`; tabela sem `DELETE` em `NO_DELETE_TABLES` (o gate da E07 falhou por isso).
- Entrada estrita da E20 também nos payloads offline; `409 CONCURRENT_UPDATE` da E21.
- RN-13: nada de dado de hóspede no feed, nas respostas ou nos avisos; texto livre (descrição, motivos, observações) fora da trilha.
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`.
- Código em inglês; KDoc e commits em pt-BR; commits com `Soul:`.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, só os próprios dados; quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)`; eventos assíncronos esperados pelo padrão do projeto (sem `sleep` cego).
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Reentrega do mesmo `ChecklistCompleted`** e **execução registrada à parte de uma rotina (`duplicateOf`)** com o mesmo item "não ok": uma OS só, que guarda o `executionId` real de quem a abriu. Teste na Task 2.
2. **Execução avulsa com sujeito `rooms.room` escolhido pelo cliente:** não cria OS (sem local); a de rotina e a da governança criam; local do evento inexistente ou desativado é ignorado sem falhar o ouvinte. Teste na Task 2.
3. **Comando offline de iniciar ou concluir uma OS já concluída ou cancelada online:** `REJECTED` com `WORK_ORDER_TRANSITION_NOT_ALLOWED`, sem mudar nada; o mesmo `commandId` reenviado devolve o resultado gravado. Teste na Task 3.
4. **OS aberta offline com o `commandId` de uma OS que já existe:** `REJECTED`, não sobrescreve. Teste na Task 3.
5. **`occurredAt` antigo** (anterior à abertura ou ao início): os instantes da OS nunca andam para trás. Teste na Task 3.

## Decisões do plano

- **D1 — local da execução no `checklists`** (decisão de arquitetura tomada pelo orquestrador pela opção recomendada, sob a autorização do usuário de 2026-11-19; validada pelo `revisor-arquitetura` como a melhor alternativa). Alternativas descartadas: aresta `maintenance → housekeeping` (mesma camada, fora da §3.1); o `housekeeping` chamar o `maintenance` (idem); uma porta `ChecklistSubjectLocator` no `checklists` implementada pelo `housekeeping` (resolveria só no consumo, deixaria forjável o sujeito `housekeeping.task` de uma avulsa, o risco aceito na E06, e somaria uma consulta por evento); só aceitar rotinas (perderia o caso principal, a camareira que acha o defeito na limpeza). `ChecklistLocation(roomId: UUID?, areaId: UUID?)` (exatamente um) na raiz do `checklists`; `ChecklistsApi.startExecution(..., location: ChecklistLocation?)` com o parâmetro **obrigatório** (sem padrão: quem chama declara o local ou `null`); numa rotina de sujeito `rooms.room`/`rooms.area`, o local é o sujeito (o KDoc registra que o `checklists` passa a interpretar esses dois tipos de sujeito de rotina como local); a execução avulsa iniciada pelo cliente (endpoint e comando offline do `checklists`) fica **sem** local. Colunas `location_room_id`/`location_area_id` anuláveis na execução; `ChecklistExecutionView.location` e `ChecklistCompleted.location` (campo novo com padrão `null`, legível nos eventos já gravados). A governança passa a UH ou a área da tarefa em todo `startExecution` dela (`HousekeepingTaskExecution` e `HousekeepingInspections`).
- **D2 — OS por checklist.** `MaintenanceChecklistListener` (`@ApplicationModuleListener` de `ChecklistCompleted`): para cada ação `OPEN_OCCURRENCE` com `occurrenceKind = MAINTENANCE`, com `location` presente **e conferido pela `RoomsApi`** (UH ou área existente e ativa; senão ignora com log só de ids, sem falhar o ouvinte), abre uma OS `origin = CHECKLIST`, `priority = NORMAL`, `category` = o `category` da ação quando for um nome de `WorkOrderCategory`, senão `OTHER`; `description` = rótulo do item (da versão), categoria e observação, até 2000; `openedBy` = executor; `openedAt` = `completedAt`; vínculo `checklistExecutionId` (o `executionId` real) + `checklistItemKey`, mais a coluna `checklist_reference_execution_id` = `duplicateOf ?: executionId` com índice único `(checklist_reference_execution_id, checklist_item_key)` (a execução registrada à parte não abre segunda OS do mesmo item; desvio consciente da E06, onde o `duplicateOf` cria o próprio registro, porque uma OS em dobro é trabalho em dobro). A reentrega devolve a OS gravada. Sem aviso urgente (prioridade normal). Risco aceito: o mesmo defeito marcado em dias diferentes abre uma OS por execução.
- **D3 — observações.** `POST /api/v1/work-orders/{id}/notes` `{text}` (1 a 1000; `manage`, ou `request` na OS que o ator abriu; OS final recusa), tabela `maintenance.work_order_note` **só de inserção** (id, OS, texto, autor, instante; `REVOKE UPDATE`; *append-only* no `DatabaseRolesTest`); a resposta da OS lista as observações em ordem; o texto não vai à trilha, só o fato. Escopo novo no contrato (o roadmap pede "registrar observações" no offline): decisão para o usuário.
- **D4 — fonte offline** `maintenance-work-orders` (`MaintenanceOffline.WORK_ORDERS`, permissão **`maintenance.workorders.manage`**, como a fonte de execução da E19): as OS **não finais**, com id, número, local (`roomId`/`areaId` e o número da UH ou o nome da área, desnormalizados e possivelmente defasados após renomear: defasagem aceita), categoria, prioridade, status, **descrição** (exceção à regra "nada de texto livre" do feed: o técnico precisa dela para trabalhar e a RN-13 permite observações operacionais; decisão para o usuário), responsável (id), prazo e versão. **Fora do feed:** motivo da espera, observações, notas de conclusão, terceiro e custos. Posição de mudança por coluna e gatilho na `maintenance.work_order` (padrão da E19). OS final, e OS que sai do escopo `OWN` (reatribuída), geram *tombstone* (derivado da linha final; `OfflineTombstones` com `leftScopeOf` na reatribuição). Nada de dado de hóspede.
- **D5 — comandos offline** (`MaintenanceOffline`), pelos mesmos casos de uso do online em variantes com o instante e sem versão, com instante = `max(occurredAt corrigido, última transição da OS)` (a última entre `openedAt`, `startedAt` e o instante da última ação; a OS ganha a coluna `last_transition_at` se o `updatedAt` não servir), trilha `<verbo>-offline`: `maintenance.open-work-order` (`commandId` = id da OS; payload como o `POST`; permissão `request`), `maintenance.start-work-order`, `maintenance.hold-work-order` (`reason`), `maintenance.resume-work-order`, `maintenance.complete-work-order` (`notes` e custos opcionais como no online; **libera o bloqueio da UH como o online**, sem step-up, U3 da E07) e `maintenance.add-work-order-note` (`text`; `commandId` = id da observação), todos com o `workOrderId`, permissão `manage` (ou `request` para a observação na OS que abriu). O offline **não** abre bloqueio nem cancela (exigem step-up online). Recusa (OS final, fora do escopo, transição inválida) vira `REJECTED` com o código do online, **sem** registro de conflito (RN-22 trata de tarefa e checklist; com o modelo Manutenção `ALL`, a reatribuição não tira o escopo): decisão para o usuário.

---

### Task 1: Local da execução no `checklists`

**Objetivo:** D1.

**Critérios de aceite:**
- `ChecklistExecutionLocationTest` (`@IntegrationTest`, novo, em `checklists`): `startExecution` com `location` grava e devolve o local na view e no `ChecklistCompleted`; rotina com sujeito `rooms.room` publica o local = sujeito; execução avulsa pelo endpoint com sujeito `rooms.room` publica `location = null`; `ChecklistLocation` com UH e área juntas ou nenhuma → `IllegalArgumentException`. Verificação: `timeout 900 ./gradlew test --tests '*ChecklistExecutionLocationTest'`.
- `HousekeepingChecklistLocationTest` (`@IntegrationTest`, novo, em `housekeeping`): concluir uma tarefa com checklist e uma inspeção publicam `ChecklistCompleted` com `location.roomId` da tarefa. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingChecklistLocationTest'`.
- Os testes do `checklists` e da execução da governança seguem verdes: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*' --tests '*HousekeepingTaskExecution*' --tests '*HousekeepingInspection*' --tests '*HousekeepingOccurrence*'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `checklists/ChecklistExecutions.kt` (`ChecklistLocation`, view), `checklists/ChecklistsApi.kt`, `checklists/ChecklistsEvents.kt`, `checklists/application/ChecklistExecutions.kt` e `ChecklistTemplates.kt` (implementação da API), `checklists/infrastructure/` (persistência da execução), migração em `db/migration/checklists/`, `housekeeping/application/HousekeepingTaskExecution.kt`, `housekeeping/application/HousekeepingInspections.kt`.

**Tamanho:** ~50 min; 8 a 10 arquivos de produção, mais a migração.

**Depende de:** nenhuma.

**Interfaces (produz):** `ChecklistLocation(roomId: UUID?, areaId: UUID?)`; `ChecklistsApi.startExecution(versionId, subject, executor, startedAt, id, location: ChecklistLocation?)`; `ChecklistCompleted.location: ChecklistLocation?`; `ChecklistExecutionView.location`.

### Task 2: OS por checklist e feed offline

**Objetivo:** D2 e D4.

**Critérios de aceite:**
- `WorkOrderChecklistListenerTest` (`@IntegrationTest`, novo): `ChecklistCompleted` de uma execução da governança com item `MAINTENANCE` "não ok" gera exatamente uma OS `origin = CHECKLIST` com o local, o vínculo à execução e ao item, o executor como quem abriu e a categoria mapeada; reprocessar o mesmo evento não duplica; a execução `duplicateOf` com o mesmo item não abre outra e a OS guarda o `executionId` real (Review Focus 1); execução sem local (avulsa) e local inexistente ou desativado não abrem e não falham (Review Focus 2); item `LOST_AND_FOUND`/`DAMAGE` não abre OS. Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderChecklistListenerTest'`.
- `MaintenanceOfflineFeedTest` (`@IntegrationTest`, novo): o feed `maintenance-work-orders` do técnico traz as suas OS não finais e os locais, com o conjunto exato de chaves do item (sem motivo da espera, observações, notas, terceiro, custos nem dado de hóspede); `OWN` só as suas; a OS concluída e a reatribuída saem do feed por *tombstone* (OWN); sem `manage`, a fonte não vem (quem só tem `read` não baixa). Quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)`. Verificação: `timeout 900 ./gradlew test --tests '*MaintenanceOfflineFeedTest'`.
- `ModularityTest` com `checklists` entre as dependências do `maintenance`; `DatabaseRolesTest` verde. Verificação: `timeout 900 ./gradlew test --tests '*ModularityTest' --tests '*DatabaseRolesTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/MaintenanceModule.kt`, `maintenance/MaintenanceOffline.kt` (novo, a fonte), `maintenance/application/MaintenanceChecklistListener.kt` (novo), `maintenance/application/WorkOrdersService.kt` (abertura pelo sistema; *tombstone* na reatribuição), `maintenance/domain/WorkOrder.kt` (vínculo ao checklist), `maintenance/infrastructure/WorkOrderPersistence.kt`, `maintenance/infrastructure/WorkOrderOfflineSource.kt` (novo), `maintenance/application/MaintenanceConfiguration.kt`, migrações em `db/migration/maintenance/` (colunas do vínculo e índice; posição de mudança e gatilho), `ModularityTest`.

**Tamanho:** ~60 min; 8 arquivos de produção, mais migrações.

**Depende de:** Task 1.

**Interfaces (consome):** `ChecklistCompleted.location` (Task 1); `OfflineChangeSource`, `OfflineTombstones`. **Produz:** `MaintenanceOffline.WORK_ORDERS`; `WorkOrdersService.openFromChecklist(...)`.

### Task 3: Comandos offline e observações

**Objetivo:** D3 e D5.

**Critérios de aceite:**
- `WorkOrderNotesTest` (`@IntegrationTest`, novo): observação com `manage` e com `request` na OS própria (`201`), `403` na alheia sem `manage`, `409` em OS final, texto vazio ou com 1001 caracteres → `400`; a resposta da OS lista as observações; a trilha não leva o texto; a tabela é só de inserção (`DatabaseRolesTest`). Verificação: `timeout 900 ./gradlew test --tests '*WorkOrderNotesTest' --tests '*DatabaseRolesTest'`.
- `MaintenanceOfflineTest` (`@IntegrationTest`, novo): OS aberta offline é persistida com o UUID do `commandId`; o mesmo `commandId` reenviado não cria outra; `commandId` de OS existente → `REJECTED` (Review Focus 4); iniciar, pausar, retomar e concluir offline produzem o mesmo estado que online, com os instantes pelo `occurredAt` corrigido e trilha `<verbo>-offline`; concluir offline uma OS com OOO vigente libera o período e deixa a UH Suja; `occurredAt` antigo não faz instante andar para trás (Review Focus 5); comando numa OS final → `REJECTED` com `WORK_ORDER_TRANSITION_NOT_ALLOWED` (Review Focus 3); observação offline com o `commandId` como id; payload fora do formato → `REJECTED` registrado. Verificação: `timeout 900 ./gradlew test --tests '*MaintenanceOfflineTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `maintenance/MaintenanceOffline.kt` (comandos), `maintenance/application/WorkOrderOfflineHandlers.kt` (novo), `maintenance/application/WorkOrderNotes.kt` (novo), `maintenance/application/WorkOrdersService.kt` (variantes com instante e sem versão; regra de ordem), `maintenance/domain/WorkOrder.kt` (instante da última transição, se preciso), `maintenance/infrastructure/` (tabela de observações), `maintenance/web/WorkOrdersController.kt` (`POST /notes`), `maintenance/application/MaintenanceAudit.kt` (ações `-offline`), migrações em `db/migration/maintenance/`, `api/openapi.yaml`, `DatabaseRolesTest`.

**Tamanho:** ~60 min; 8 arquivos de produção, mais migrações e contrato.

**Depende de:** Task 2 (a fonte e o `MaintenanceOffline`).

**Interfaces (consome):** `OfflineCommandHandler` (`shared/OfflineSync.kt`); `WorkOrdersService`. **Produz:** `WorkOrderNotes.add(workOrderId, noteId, text, actor, at)`; os tipos de comando de `MaintenanceOffline`.

# E05 do M6 — Inspeção e liberação da UH: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar a inspeção (RF-GOV-06): a supervisora executa o checklist de inspeção e aprova (UH Inspecionada, tarefa `DONE`) ou reprova (UH Suja, tarefa `REJECTED` de volta à camareira com os itens apontados); inspeção voluntária de UH Limpa; aviso quando a UH fica pronta (RF-GOV-07, RF-NOT-01), por SSE e por notificação de UH liberada; inspeção offline pelo padrão da E19.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E05"; handoffs da E04 (D3: tarefa `AWAITING_INSPECTION` cuja UH perdeu a pendência) e da E19 (padrão offline D7); SPEC RF-GOV-06, RF-GOV-07, RN-05, RF-NOT-01, §8.2.10 (`Inspection`), §8.4; ARCHITECTURE §3.1, §6.2, §7, §9, §10. Brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Sincronização offline`, e as capturas pendentes da E04, E19, E20 e E21.

**Architecture:** a `Inspection` é um agregado do `housekeeping` com ciclo `IN_PROGRESS → COMPLETED`, ligado a uma tarefa (ou só à UH, na voluntária) e, havendo modelo, a uma execução de checklist de contexto `INSPECTION`. Um caso de uso único (`HousekeepingInspections`) atende o online e os comandos offline. A UH muda só pela `RoomsApi`, por operações condicionais com a linha travada que devolvem o status antes e depois (precedente `markCleanIfDirty` da E04), porque a pendência pode ter sumido (check-out, fechamento, aprovação manual pelo `rooms`). A ordem de trava é tarefa → inspeção → execução do checklist → UH. A prontidão (RN-05) é do `rooms`; o SSE da UH pronta é a mensagem `rooms.room-status` que o `rooms` já publica; o aviso de UH liberada sai do `housekeeping` só na passagem de "não pronta" para "pronta". Nenhuma aresta nova: o `housekeeping` já declara `rooms`, `checklists`, `staff` e `notifications`, e não depende de `frontdesk`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissão própria de inspeção; quem limpou não inspeciona sem ela; a fonte offline do inspetor (Tasks 1, 2 e 3);
- **concorrência:** inspeção × check-out/fechamento/aprovação manual na mesma UH; duas inspeções na mesma tarefa; ordem de trava (Tasks 1 e 2);
- **dado pessoal ou segredo:** avisos, respostas e feeds sem dado de hóspede (RN-13) (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** corpos e payloads da inspeção, itens reprovados (Tasks 1, 2 e 3);
- **migração ou escrita destrutiva de dados:** tabela da inspeção e índice único parcial (Task 1).

## Global Constraints

- Grafo §3.1 com o `ModularityTest` em igualdade exata; a declaração do `housekeeping` não muda (nenhuma aresta nova; em particular, nenhuma para `frontdesk`, restrição do roadmap).
- Migrações novas por fatia (nunca editar a de outra fatia), com timestamp posterior à última do projeto (hoje `V2026_11_19_1202`); `outOfOrder` desligado.
- Transição inválida `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`; ações com `If-Match`; `@Audited` sem dado pessoal; autorização antes de qualquer recusa que revele estado.
- O status da UH só muda pela `RoomsApi`; o checklist só pela `ChecklistsApi`. Toda transição da tarefa publica `housekeeping.task` no tópico `housekeeping` (ARCHITECTURE §10).
- RN-13: nenhuma resposta, aviso, trilha, feed ou mensagem leva `notes`, nome, documento, contato nem id de hóspede ou de reserva. A `Inspection` não tem campo de texto livre.
- Entrada estrita da E20, `409 CONCURRENT_UPDATE` da E21, `ExceptionClasses.chain` nos logs, regra do `required` nos DTOs.
- Padrão offline da E19 (D7): comandos `housekeeping.<verbo>-<objeto>`, payloads próprios, `REJECTED` por `DomainException`, alertas por `warnings`, `occurredAt` corrigido e `max(instante, última transição)`, versão do checklist do aparelho com os alertas da E19, foto `TOLERATE` com alertas; só `ALL` aqui (sem caminho de conflito de posse).
- Contrato regenerado; `checkOpenApi` e `check-api-breaking.sh` sem `ERR`. Enum de resposta como `x-extensible-enum`.
- Código em inglês; KDoc, ARCHITECTURE e commits em pt-BR; commits com `Soul:`, intermediários.
- Um Gradle por vez; `timeout` em tudo; sem laço de espera; nunca `--stop`.
- Testes: banco compartilhado, só os próprios dados; tarefas na data operacional corrente isoladas por UHs, tipos e camareiras próprios; `neutralizeGeneralHousekeepingTemplates()` no `@BeforeEach` de todo teste que inicia ou conclui tarefa, e um equivalente para modelos gerais de `INSPECTION` em quem depende da resolução; `HousekeepingExecutionTestSupport.assign` grava a posse; quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)`; trava por `pg_blocking_pids`.
- Verificação da fatia: testes focados mais `timeout 900 ./gradlew checkOpenApi lint`.

## Review Focus

1. **Aprovar tarefa `AWAITING_INSPECTION` cuja UH perdeu a pendência** (check-out a deixou Suja, ou o `rooms` já a aprovou): a tarefa vai a `DONE`, a UH não muda, a resposta diz que a UH não foi inspecionada; nunca 500 nem 422 do `rooms`. Teste na Task 1.
2. **Reprovar com a UH já Suja, já Inspecionada ou ocupada:** a tarefa vai a `REJECTED` com os itens, a UH não muda (a operação condicional não mexe), a camareira é avisada. Teste na Task 2.
3. **Quem limpou a tarefa tenta inspecioná-la sem a permissão de inspeção:** `403`. Teste na Task 1.
4. **UH que já estava pronta, ou que não fica pronta (ocupada, em OOO):** nenhum aviso de UH liberada; só a passagem não pronta → pronta avisa. Teste na Task 2.
5. **Comando offline de inspeção numa tarefa já inspecionada online:** `REJECTED` com o código de transição, sem segunda inspeção. Teste na Task 3.

## Decisões do plano

- **D1 — permissão.** `housekeeping.inspections.perform`, só `ALL`, não sensível, nos modelos Governanta e Gerente. A camareira não tem. Quem limpou só inspeciona se tiver a permissão.
- **D2 — agregado `Inspection`.** Tabela `housekeeping.inspection`: `id`, `task_id` (anulável, na voluntária), `room_id`, `checklist_execution_id` (anulável sem modelo), `status` (`IN_PROGRESS`/`COMPLETED`), `result` (`APPROVED`/`REJECTED`, nulo enquanto aberta), `rejected_items` (chaves do checklist, `text[]`, vazio na aprovação), `inspector_id` (usuário que inspecionou; o colaborador vem da `StaffApi` quando preciso), `started_at`, `completed_at`. Índice único parcial: uma inspeção `IN_PROGRESS` por tarefa. Não é só de inserção (a conclusão grava o resultado), mas não se apaga. Os itens reprovados vivem só na inspeção: a tarefa e o feed os leem da última inspeção reprovada (uma fonte de verdade). Desvios da SPEC §8.2.10 registrados para o `arquiteto` (sem `notes`; tarefa e checklist anuláveis; inspetor como usuário).
- **D3 — fluxo online.** `POST /api/v1/housekeeping-tasks/{id}/inspection/start` e `POST …/inspection/complete`, com `If-Match` da tarefa (o `start` muda a `version` da tarefa, para serializar duas inspeções). O `start` exige tarefa `AWAITING_INSPECTION`; abre a inspeção e, se houver modelo de contexto `INSPECTION` (`ChecklistsApi.resolveTemplate`), a execução da versão vigente; segundo `start` com uma aberta dá `409 HOUSEKEEPING_INSPECTION_ALREADY_OPEN`. O `complete` (`result`, `answers`, `rejectedItems`) conclui a inspeção aberta; **sem** inspeção aberta, só é aceito quando a tarefa não resolve modelo `INSPECTION` (abre e conclui numa chamada); com modelo e sem `start`, `409 HOUSEKEEPING_INSPECTION_NOT_STARTED`. Aprovar: tarefa `AWAITING_INSPECTION → DONE`, `RoomsApi.markInspectedIfPending`. Reprovar: tarefa `AWAITING_INSPECTION → REJECTED`, `RoomsApi.rejectInspectionIfPending`; refazer é o `start` da E04 (`REJECTED → IN_PROGRESS`). `rejectedItems`: com modelo, chaves da versão do checklist de inspeção, pelo menos uma, validadas (chave desconhecida `400` no campo); sem modelo, vazio (nada de texto livre). Item obrigatório faltando no checklist dá `422` sem mudar nada.
- **D4 — inspeção voluntária.** `POST /api/v1/room-inspections` (recurso do `housekeeping`, corpo `{ roomId }`), sem checklist, só aprova: `RoomsApi.markInspectedIfClean` travada — UH Limpa sem pendência → Inspecionada; Limpa **com** pendência → `409 HOUSEKEEPING_INSPECTION_USE_TASK` ("inspecione pela tarefa"); não Limpa → `409 HOUSEKEEPING_ROOM_NOT_CLEAN`. Grava uma `Inspection` sem tarefa, `COMPLETED`/`APPROVED`.
- **D5 — UH pronta (RF-GOV-07).** As operações condicionais do `rooms` (`markInspectedIfPending`, `rejectInspectionIfPending`, `markInspectedIfClean` e a `markCleanIfDirty` da E04) devolvem o status antes e depois (com `readyForCheckIn`, decidido com a linha travada). O `housekeeping` avisa `notifications.room-released` só quando a UH passou de não pronta a pronta — na aprovação, na voluntária **e** na conclusão da E04 sem inspeção (tipo sem inspeção numa UH vaga) — aos usuários com `housekeeping.tasks.read` (Recepção, Governanta e Gerente), menos o inspetor ou quem concluiu. Alternativas descartadas: o código de permissão do `frontdesk` como texto (dependência escondida que o roadmap proíbe) e um evento novo de "UH pronta" no `rooms` (o painel já rebusca a prontidão pelo `rooms.room-status`). O SSE é a mensagem `rooms.room-status`.
- **D6 — reprovação.** O aviso `notifications.inspection-rejected` vai à camareira atribuída (usuário pela `StaffApi`; sem usuário, o aviso é omitido sem erro), com o número da UH e a contagem de itens, sem dado de hóspede.
- **D7 — offline.** Fonte nova `housekeeping-inspections` (permissão `housekeeping.inspections.perform`, `ALL`) com as tarefas `AWAITING_INSPECTION` (id, `version`, UH, área, tipo, data, `checklistExecutionId` da limpeza); deixa de estar aguardando → *tombstone* derivado. O item `REJECTED` da fonte `housekeeping-tasks` passa a levar `rejectedItems` (da última inspeção reprovada) e `inspectionChecklistVersionId`, para o aparelho da camareira exibir os itens. Comandos `housekeeping.start-inspection` (o `commandId` vira o id da inspeção; `checklistVersionId` do aparelho com os alertas da E19), `housekeeping.complete-inspection` e `housekeeping.inspect-room` (voluntária), pelo mesmo caso de uso.

---

### Task 1: Permissão, agregado, início e aprovação online

**Objetivo:** permissão (D1), agregado e tabela (D2), `inspection/start` e `inspection/complete` com aprovação (D3), operação condicional `markInspectedIfPending` no `rooms` (devolvendo o status antes e depois), o caso da UH que perdeu a pendência (Review Focus 1) e o `housekeeping.task` na aprovação.

**Critérios de aceite:**
- `HousekeepingInspectionApiTest` (`@IntegrationTest`): aprovação com modelo → UH Inspecionada, tarefa `DONE`, execução do checklist de inspeção concluída, trilha, `housekeeping.task` publicado; aprovação sem modelo, sem `start`; UH sem pendência → tarefa `DONE` e UH igual; segundo `start` → `409 HOUSEKEEPING_INSPECTION_ALREADY_OPEN`; `complete` sem `start` com modelo → `409 HOUSEKEEPING_INSPECTION_NOT_STARTED`; item obrigatório faltando → `422` sem mudança. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingInspectionApiTest'`.
- `HousekeepingInspectionPermissionsTest` (`@IntegrationTest`): sem a permissão, `403` antes de `404`/`409`/`412`, inclusive quem limpou (Review Focus 3); a permissão e os modelos no catálogo; `HousekeepingPermissionsTest` e `AuthorizationTest` atualizados (os do testador, pelo testador). Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingInspectionPermissionsTest' --tests '*HousekeepingPermissionsTest'`.
- `RoomsApiInspectionOperationsTest` (`@IntegrationTest`, no `rooms`): `markInspectedIfPending` muda só a UH Limpa com pendência e devolve o status antes e depois com `readyForCheckIn`. Verificação: `timeout 900 ./gradlew test --tests '*RoomsApiInspectionOperationsTest'`.
- Schema: tabela, índice único parcial; `DatabaseRolesTest`. Contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests '*DatabaseRolesTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** `housekeeping/HousekeepingPermissions.kt`, `housekeeping/HousekeepingTypes.kt` (códigos), `housekeeping/domain/Inspection.kt` (novo), `housekeeping/domain/HousekeepingTask.kt` (aprovar), `housekeeping/application/HousekeepingInspections.kt` (novo), `housekeeping/application/HousekeepingAudit.kt`, `housekeeping/infrastructure/` (persistência da inspeção), `housekeeping/web/HousekeepingInspectionsController.kt` (novo, com os DTOs), `rooms/RoomsApi.kt` e `rooms/application/RoomsApiService.kt`, a migração nova, `api/openapi.yaml`.

**Tamanho:** ~60 min; 10 arquivos de produção, mais a migração e o contrato.

**Depende de:** nenhuma.

**Interfaces (produz):** `HousekeepingInspections.start(taskId, actor, at, expectedVersion?, deviceVersionId?)` e `complete(taskId, result, answers, rejectedItems, actor, at, expectedVersion?)`; `RoomsApi.markInspectedIfPending(id, source)` devolvendo o status antes e depois.

### Task 2: Reprovação, inspeção voluntária e UH liberada

**Objetivo:** reprovar (D3, D6) com `rejectInspectionIfPending`; refazer pela E04; inspeção voluntária (D4) com `markInspectedIfClean`; aviso de UH liberada só na passagem não pronta → pronta (D5), na aprovação, na voluntária e na conclusão da E04 sem inspeção; `housekeeping.task` na reprovação.

**Critérios de aceite:**
- `HousekeepingInspectionRejectionApiTest` (`@IntegrationTest`): reprovação → UH Suja, tarefa `REJECTED`, itens gravados na inspeção e expostos na tarefa, camareira notificada (sem usuário: omitido sem erro), `housekeeping.task` publicado; refazer → `IN_PROGRESS`; reprovar com a UH já Suja, já Inspecionada ou ocupada → tarefa `REJECTED`, UH igual (Review Focus 2); chave reprovada desconhecida → `400`; sem modelo, `rejectedItems` não vazio → `400`. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingInspectionRejectionApiTest'`.
- `HousekeepingRoomInspectionApiTest` (`@IntegrationTest`): voluntária de UH Limpa sem pendência → Inspecionada; com pendência → `409 HOUSEKEEPING_INSPECTION_USE_TASK`; Suja → `409 HOUSEKEEPING_ROOM_NOT_CLEAN`; sem a permissão → `403`. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingRoomInspectionApiTest'`.
- `HousekeepingRoomReleasedTest` (`@IntegrationTest`): UH vaga sem OOO que passa a pronta (aprovação, voluntária e conclusão da E04 de tipo sem inspeção) → `notifications.room-released` no painel de um usuário com `housekeeping.tasks.read` e não no do inspetor, e `rooms.room-status` no tópico `rooms`; UH já pronta, ocupada ou em OOO → nenhum aviso (Review Focus 4); o aviso sem dado de hóspede. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingRoomReleasedTest'`.
- `RoomsApiInspectionOperationsTest` ganha `rejectInspectionIfPending` e `markInspectedIfClean`; a `markCleanIfDirty` passa a devolver o status antes e depois.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `housekeeping/application/HousekeepingInspections.kt`, `housekeeping/application/HousekeepingTaskExecution.kt` (aviso de UH liberada na conclusão sem inspeção), `housekeeping/application/` (o aviso de UH liberada, num ponto só), `housekeeping/domain/HousekeepingTask.kt` (reprovar), `housekeeping/web/HousekeepingInspectionsController.kt` (voluntária e `rejectedItems`), `housekeeping/web/HousekeepingTasksController.kt` (itens na resposta), `rooms/RoomsApi.kt` e `rooms/application/RoomsApiService.kt`, `api/openapi.yaml`.

**Tamanho:** ~60 min; 8 arquivos de produção, mais o contrato.

**Depende de:** Task 1.

**Interfaces:** consome as da Task 1; produz `RoomsApi.rejectInspectionIfPending` e `markInspectedIfClean` (status antes e depois) e o aviso de UH liberada reutilizável.

### Task 3: Inspeção offline e fonte do inspetor

**Objetivo:** fonte `housekeeping-inspections` e `rejectedItems` no feed da camareira (D7); comandos `housekeeping.start-inspection`, `housekeeping.complete-inspection` e `housekeeping.inspect-room` pelo mesmo caso de uso, com o padrão da E19.

**Critérios de aceite:**
- `HousekeepingInspectionOfflineTest` (`@IntegrationTest`): um lote offline com `start-inspection` e `complete-inspection` produz o mesmo estado que o online (aprovar e reprovar); o id da inspeção é o `commandId` do `start`; idempotência; `CLOCK_SKEW_EXCEEDED`; `checklistVersionId` do aparelho com os alertas da E19; sem a permissão `REJECTED`; inspeção offline de tarefa já inspecionada online `REJECTED` com o código de transição (Review Focus 5); `inspect-room` aplica a voluntária. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingInspectionOfflineTest'`.
- `HousekeepingInspectionFeedTest` (`@IntegrationTest`, `GET /api/v1/sync/changes` depois de `InventoryListenersQuiescence.await(jdbc)`): o inspetor recebe as tarefas `AWAITING_INSPECTION` com `version`, e o *tombstone* quando deixam de aguardar; a camareira não recebe a fonte; o item `REJECTED` do feed da camareira traz `rejectedItems` e `inspectionChecklistVersionId`; nada de `notes` nem dado de hóspede. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingInspectionFeedTest'`.
- A ARCHITECTURE §6.2, §9 e §10 registram os comandos, a fonte e as transições `AWAITING_INSPECTION → DONE/REJECTED` no tópico. Verificação: `grep -n 'housekeeping-inspections\|complete-inspection' docs/ARCHITECTURE.md`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `housekeeping/HousekeepingOffline.kt`, `housekeeping/application/HousekeepingInspectionOfflineHandlers.kt` (novo), `housekeeping/infrastructure/HousekeepingInspectionOfflineSource.kt` (novo), `housekeeping/infrastructure/HousekeepingTaskOfflineSource.kt` (`rejectedItems`), `housekeeping/application/HousekeepingAudit.kt`, `docs/ARCHITECTURE.md`.

**Tamanho:** ~55 min; 5 arquivos de produção.

**Depende de:** Task 2.

**Interfaces:** consome `HousekeepingInspections` (Tasks 1 e 2); produz a fonte `housekeeping-inspections` e os três comandos.

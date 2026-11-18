# E04 do M6 — Execução da tarefa online e offline: plano de implementação

> **Para agentes:** a execução usa `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde; o código fica com quem implementa.

**Goal:** implementar a execução da tarefa de governança (RF-GOV-04 e RF-GOV-05) pela máquina de estados da SPEC §8.4: iniciar, pausar, retomar, concluir com checklist, recusar (DND ou hóspede) e tentar de novo.
- **Online:** endpoints de ação, com escopo `OWN` para a camareira.
- **Offline:** comandos com `OfflineCommandHandler` (ARCHITECTURE §6.2 e §9).
- **Efeitos da conclusão:** a conclusão:
  - leva a UH a Limpa, com ou sem inspeção pendente;
  - registra quem limpou, o início, o fim e a duração;
  - atualiza a faxina programada;
  - publica `HousekeepingTaskCompleted`;
  - envia ao `rooms` a ocupação encontrada, para a discrepância (RF-UH-08).
- **Conflito (RN-22):** o comando offline sobre uma tarefa que mudou de mãos é registrado e sinalizado, nunca descartado.
- **Feed:** as tarefas da camareira entram no feed de sincronização, sem dado de hóspede (RN-13).

**Spec:**
- `docs/roadmaps/roadmap-m6-operacao-fechamento.md`: a seção "E04", com H2, H3 e H16, e os handoffs da E01 (`recordDone`), da E02 e da E03 ("Notas para a próxima etapa");
- SPEC: RF-GOV-04, RF-GOV-05, RF-UH-08, RN-13, RN-22, §8.3 (`HousekeepingTaskCompleted`) e §8.4;
- ARCHITECTURE: §3.1, §6.2, §7, §8, §9 e §10;
- brain: `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Tempo real (SSE)` e `ZenyHotelManager - Módulo housekeeping`, mais as capturas pendentes da E01, E02 e E03, que valem sobre ela.

**Architecture:**
- **Domínio:** a máquina de estados da tarefa fica no `domain` e cobre todas as transições da §8.4, mais `ASSIGNED → PENDING` (D7 da E03). Um único caso de uso de execução na `application` atende o online e o offline. A UH só muda pela `RoomsApi` (`markClean`, `setServiceFlag`, e a observação nova).
- **Checklist:** a execução começa no `start`, pela `ChecklistsApi.startExecution`, e termina no `complete`, pela `ChecklistsApi.completeExecution`.
- **Offline:** os comandos usam a infraestrutura do `shared`: idempotência por `commandId`, `occurredAt` corrigido, e "aplicado com alerta" para os conflitos.
- **Feed:** é uma fonte nova (`OfflineChangeSource`) para as tarefas, com escopo `OWN` pelo usuário da camareira atribuída e *tombstone* quando a tarefa sai do escopo dela.
- **Dependências:** nenhuma aresta nova. O `housekeeping` já declara `rooms`, `checklists`, `staff` e `notifications`. A `RoomsApi` ganha um método público, que é dependência existente.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (eventos), Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopo `OWN` da camareira e permissão nova de execução, online e offline (Tasks 1 e 3);
- **concorrência:** ação online e comando offline sobre a mesma tarefa, reatribuição durante a execução offline, conclusão concorrente (Tasks 1 a 3);
- **dado pessoal ou segredo:** feed, eventos, trilha e aviso sem dado de hóspede (RN-13) (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** corpos das ações, respostas do checklist, ocupação encontrada e payloads offline (Tasks 1 a 3);
- **migração ou escrita destrutiva de dados:** colunas novas na tarefa, tabela de conflitos e gatilho de sincronização (Tasks 1 e 3).

## Global Constraints

- **Grafo:** segue a ARCHITECTURE §3.1, com o `ModularityTest` em igualdade exata. A declaração do `housekeeping` não muda.
- **Migrações:** ficam no schema `housekeeping`, com timestamp posterior a `V2026_11_18_1102`. As do `rooms`, se houver, têm timestamp posterior à última dele.
- **H16:** referência inexistente no corpo dá `400 …UNKNOWN_…`, e transição inválida dá `409 HOUSEKEEPING_TASK_INVALID_TRANSITION`, como o padrão de transição do projeto. Se o padrão do projeto for `422`, siga o projeto e registre.
- **Contrato:** regenerado com `./gradlew updateOpenApi`, com enums como `x-extensible-enum`. `.github/scripts/check-api-breaking.sh` sem `ERR`.
- **Escrita da `application`:** é `@Audited`, com retrato sem dado pessoal. A autorização vem antes de qualquer estado. Os handlers offline também são `@Audited` (regra ArchUnit `offlineCommandHandlersAreAudited`).
- **Ações online:** usam `If-Match` (`428`/`412`), como o resto do projeto. Os comandos offline não usam: a idempotência é por `commandId` e o conflito é RN-22.
- **Idioma:** código, API e banco em inglês; KDoc, comentários e commits em pt-BR (D17).
- **Commits:** por fatia, com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`. Faça commits intermediários, porque houve queda de energia nesta máquina.
- **Execução:** um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- **Testes:**
  - o banco compartilhado recebe avanços de data e planos de outros testes. Os testes afirmam só sobre as próprias UHs, tarefas e camareiras e nunca contam nada de forma global, nem alteram os tipos da carga;
  - quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)` (nota de testes);
  - as tarefas de teste nascem como avulsas de datas próprias e são atribuídas pelo próprio teste, por publicação de plano numa data futura própria ou por escrita direta da camareira no banco, explicada no teste.
- **Verificação da fatia:** os testes focados da fatia, mais `timeout 900 ./gradlew checkOpenApi lint`. A suíte inteira roda no gate.

## Review Focus

1. **Máquina de estados completa:** toda transição da §8.4, válida e inválida, testada no domínio, inclusive `REJECTED → IN_PROGRESS` (refazer, que a E05 vai produzir) e `REFUSED → ASSIGNED` (nova tentativa). Transição inválida nunca muda a UH nem o checklist. Task 1.
2. **Escopo `OWN`:** a camareira só age sobre tarefa atribuída a ela. A atribuição vem do `assigned_employee_id`, e o vínculo com o usuário vem de `StaffApi.employeeOfUser`. Sobre tarefa de outra, a resposta é `403` online e `REJECTED` no comando offline. A governanta age com escopo `ALL`. Tasks 1 e 3.
3. **Conclusão atômica:** checklist concluído, UH Limpa, tarefa `DONE` ou `AWAITING_INSPECTION`, faxina programada atualizada e evento publicado acontecem numa transação só. Uma falha no checklist (item obrigatório faltando, `422`) não deixa a UH Limpa. Task 2.
4. **Conflito RN-22:** o comando offline que chega depois que a tarefa foi reatribuída a outra camareira, cancelada ou desatribuída não é descartado:
   - ele é gravado como conflito, com quem, o quê e quando;
   - a tarefa fica marcada;
   - a governanta recebe aviso;
   - o resultado é "aplicado com alerta";
   - a tarefa da outra camareira não muda.

   Task 3.
5. **RN-13 no feed:** o feed da camareira traz as tarefas dela e as UHs (número e status), sem nome, documento, contato nem id de hóspede ou de reserva. Task 3.

## Decisões desta etapa

Decisões do orquestrador, sob a autorização noturna do usuário. Todas revisáveis.

- **D1-E04, permissão:** `housekeeping.tasks.execute`, com escopo `OWN` no modelo Camareira e `ALL` nos modelos Governanta e Gerente. Ela não é sensível. A leitura da própria tarefa pela camareira vem do feed offline e do `GET` com escopo `OWN`: `housekeeping.tasks.read` passa a aceitar `OWN` no modelo Camareira.
- **D2-E04, ações online:** `POST /api/v1/housekeeping-tasks/{id}/start`, `/pause`, `/resume`, `/complete`, `/refuse` (corpo com `reason`: `DO_NOT_DISTURB` ou `GUEST_REFUSED`) e `/retry` (`REFUSED → ASSIGNED`), todos com `If-Match`.
  - `start` aceita `ASSIGNED` e `REJECTED` (refazer).
  - A recusa com `DO_NOT_DISTURB` também liga o "Não perturbe" da UH pela `RoomsApi.setServiceFlag` (origem `TASK`).
- **D3-E04, checklist:**
  - **início:** no `start`, o modelo é escolhido nesta ordem: o vínculo do tipo de limpeza, se ativo e publicado; senão `ChecklistsApi.resolveTemplate(HOUSEKEEPING, …cleaningTypeId…)`; senão nenhum (D1 da E01). Havendo modelo, a execução começa com a camareira como executor, e a tarefa guarda o `checklist_execution_id`;
  - **conclusão:** o `complete` conclui a execução com as respostas do corpo. Se a execução não existir, as respostas no corpo dão `400`;
  - **refazer:** o refazer (`REJECTED → IN_PROGRESS`) abre uma execução nova.
- **D4-E04, ocupação encontrada:** o corpo do `complete` traz `foundOccupancy` (`VACANT`/`OCCUPIED`) e `foundGuestCount` opcionais. A `RoomsApi` ganha `recordObservation(roomId, occupancy, guestCount, observedBy, observedAt)`, que grava a observação que o `rooms` já usa para a discrepância. Sem `foundOccupancy`, não se grava observação.
- **D5-E04, quem limpou e tempos:**
  - `cleaned_by` é o usuário da ação;
  - `started_at` é o primeiro `start`;
  - `finished_at` é a conclusão;
  - `worked_seconds` soma os trechos em `IN_PROGRESS`, sem as pausas;
  - online, os instantes vêm do `Clock`; offline, do `occurredAt` corrigido.
- **D6-E04, evento:** `HousekeepingTaskCompleted(taskId, roomId?, areaId?, cleaningTypeId, requiresInspection, businessDate, completedBy, completedAt, version)` vai no arquivo público de eventos do módulo, sem dado de hóspede. Ainda não há consumidor: o aviso de UH pronta é da E05.
- **D7-E04, faxina programada:** a conclusão de uma tarefa `SCHEDULED` chama `ScheduledCleaning.recordDone(businessDate da tarefa)`. Isso fecha a nota da E01: o `recordDone` passa a atualizar `updatedAt`, só age sobre faxina ativa e recusa data fora do `StorableRange`.
- **D8-E04, conflito RN-22:**
  - **o que conta como conflito:** o comando offline da camareira A sobre uma tarefa que, no servidor, está atribuída a B, desatribuída (`PENDING`) ou `CANCELLED`;
  - **o que é gravado:** a tabela `housekeeping.task_conflict` (tarefa, `command_id`, usuário, ação, `occurred_at` e status da tarefa no servidor) e `has_conflict = true` na tarefa;
  - **o aviso:** vai à governança pela permissão `housekeeping.assignments.manage` (`NotificationRecipients.permissions`), com o número da UH;
  - **o resultado:** "aplicado com alerta" (`OfflineCommandOutcome` com o aviso `HOUSEKEEPING_TASK_CONFLICT`). A tarefa no servidor **não** muda;
  - **o que não é conflito:** uma transição inválida da própria camareira, como concluir uma tarefa já concluída pelo mesmo comando, cai na idempotência por `commandId` ou dá `REJECTED` com o código da transição.
- **D9-E04, feed:**
  - **fonte:** a fonte nova `housekeeping.tasks` (permissão `housekeeping.tasks.read`, escopo `OWN` pelo `assigned_user_id`) traz as tarefas da camareira em `ASSIGNED`, `IN_PROGRESS`, `PAUSED`, `REJECTED` e `REFUSED`;
  - **dono no banco:** a tarefa ganha `assigned_user_id`, que a publicação da E03 preenche junto com a camareira, pela `StaffApi.employees`. Ela também ganha as colunas de mudança e o gatilho `shared.mark_offline_change` (ARCHITECTURE §9);
  - **tombstone:** se a tarefa sai do escopo da camareira (reatribuída, desatribuída, concluída ou cancelada), o *tombstone* vai para ela (`OfflineTombstones`, `leftScopeOf`);
  - **UHs:** já estão na fonte `rooms`, e o modelo Camareira já a recebe.
- **D10-E04, tempo real:** toda transição publica `housekeeping.task` (payload padrão: `id` e `version`) no tópico `housekeeping`, que a E03 criou para isso.
- **D11-E04, notas da E03 resolvidas aqui:**
  - na publicação de um plano, o item de uma tarefa `DONE` ou `CANCELLED` é ignorado, e a publicação não é recusada. É ruling do orquestrador: com a execução, a saída do hóspede entre a sugestão e a publicação deixaria de travar a governanta;
  - o CHECK `ASSIGNED ⇒ camareira` **não** é endurecido nesta etapa, porque um teste do testador grava `ASSIGNED` sem camareira. A nota segue.

## Revisão de arquitetura

- **Veredito: bloqueado na rodada 0** (`revisor-arquitetura`, modelo opus), com 1 Crítico e 8 Importantes. O resumo está na seção "E04" do roadmap.
- **Plano não executado.** Por decisão do usuário em 2026-11-18 (H18), a E04 volta a `#pendente` para o `arquiteto` dividi-la.
- Este texto fica como ponto de partida, e as decisões D1 a D11 não valem como tomadas.

---

### Task 1: Máquina de estados e ações online

**Objetivo:**
- **Domínio:** a tarefa cobre todas as transições da §8.4 (Review Focus 1).
- **Online:** as ações `start`, `pause`, `resume`, `refuse` e `retry` (D2-E04) ficam disponíveis, com a permissão `housekeeping.tasks.execute` e o escopo `OWN` (D1-E04).
- **Checklist:** a execução começa no `start` (D3-E04).
- **UH:** a recusa por DND liga o "Não perturbe".
- **Tempos e tempo real:** os tempos são registrados (D5-E04), e cada transição sai no tópico `housekeeping` (D10-E04).

**Critérios de aceite:**
- **`HousekeepingTaskDomainTest`:** cobre todas as transições válidas e inválidas da §8.4, inclusive `ASSIGNED → PENDING`, `REJECTED → IN_PROGRESS` e `REFUSED → ASSIGNED`, mais a soma de `worked_seconds` com pausas. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskDomainTest'`.
- **`HousekeepingTaskActionsApiTest`** (`@IntegrationTest`):
  - a camareira atribuída faz `start`, `pause`, `resume`, `refuse` (`DO_NOT_DISTURB`, que liga o DND da UH) e `retry`, com `If-Match`;
  - o `start` com modelo de checklist abre a execução e guarda o id dela; sem modelo, a tarefa segue sem checklist;
  - transição inválida → `409 HOUSEKEEPING_TASK_INVALID_TRANSITION`, sem mudar a UH;
  - cada ação é auditada (`housekeeping.task.<start|pause|resume|refuse|retry>`).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskActionsApiTest'`.
- **Escopo** (`HousekeepingTaskExecutionPermissionsTest`):
  - camareira com `OWN` sobre tarefa de outra → `403`, antes de qualquer `404`, `409` ou `412`;
  - a governanta, com `ALL`, age sobre qualquer tarefa;
  - sem `housekeeping.tasks.execute` → `403`;
  - a permissão e os modelos (D1-E04) estão no catálogo, e o `GET` da tarefa com `OWN` só devolve as próprias;
  - o `AuthorizationTest` do testador vai ao testador.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskExecutionPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- **Tempo real:** um `SseTestClient` com `housekeeping.tasks.read` recebe `housekeeping.task` com o `id` e a `version` a cada transição. O teste fica no contexto `RANDOM_PORT`, como o `AssignmentPlanRealtimeTest`.
- **Schema:** colunas `started_at`, `finished_at`, `worked_seconds`, `last_resumed_at`, `cleaned_by`, `checklist_execution_id` e `refusal_reason`, com CHECKs de coerência. O contrato é regenerado. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingTypes.kt` e `HousekeepingPermissions.kt`;
  - `domain/HousekeepingTask.kt`;
  - `application/HousekeepingTaskExecution.kt` (novo);
  - `application/HousekeepingTasksService.kt` (escopo `OWN` no `GET`);
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `web/HousekeepingTaskActionsController.kt` (novo);
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1200__add_task_execution.sql`;
- `api/openapi.yaml`.

**Tamanho:** ~60 min; 9 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `HousekeepingTaskExecution`, com:
  - `start(taskId, actor, at, expectedVersion?)`;
  - `pause(...)` e `resume(...)`;
  - `refuse(taskId, reason, actor, at, expectedVersion?)`;
  - `retry(...)`.
- Na `HousekeepingTaskExecution`, o parâmetro `expectedVersion` é nulo no offline. Uma função interna `requireActsOn(task, actor)` aplica o escopo `OWN`/`ALL` e é usada pelas Tasks 2 e 3.
- `enum class RefusalReason { DO_NOT_DISTURB, GUEST_REFUSED }`.
- `HousekeepingTask.start`, `pause`, `resume`, `complete`, `refuse`, `retry`, `approve`, `reject` e `cancel`, este último para a E05 e o futuro.

### Task 2: Conclusão, UH limpa, observação e evento

**Objetivo:** criar `POST /api/v1/housekeeping-tasks/{id}/complete` com `If-Match`, respostas do checklist e ocupação encontrada. A conclusão, numa transação só:
- conclui o checklist e leva a UH a Limpa (`RoomsApi.markClean(requiresInspection)`);
- deixa a tarefa `DONE` ou `AWAITING_INSPECTION` e registra quem limpou, o fim e a duração;
- atualiza a faxina programada (D7-E04);
- grava a observação (D4-E04);
- publica `HousekeepingTaskCompleted` (D6-E04).

A fatia também aplica a ruling D11-E04 na publicação do plano.

**Critérios de aceite:**
- **`HousekeepingTaskCompletionApiTest`** (`@IntegrationTest`):
  - tipo sem inspeção: UH Limpa e tarefa `DONE`; tipo com inspeção: UH Limpa com inspeção pendente e tarefa `AWAITING_INSPECTION`;
  - `cleanedBy`, `startedAt`, `finishedAt` e a duração (sem as pausas) gravados;
  - checklist concluído com as respostas;
  - item obrigatório faltando → `422 CHECKLIST_REQUIRED_ITEMS_MISSING`, com a UH e a tarefa sem mudança (Review Focus 3);
  - tarefa `SCHEDULED` concluída → `lastDoneOn` e `nextDueOn` da faxina atualizados;
  - `HousekeepingTaskCompleted` publicado uma vez, conferido no outbox ou por um ouvinte de teste, sem dado de hóspede.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskCompletionApiTest'`.
- **Observação:** em `HousekeepingTaskCompletionApiTest` ou num teste do `rooms`, `foundOccupancy` divergente da UH aparece na lista de discrepâncias do `rooms` (RF-UH-08). `RoomsApi.recordObservation` tem teste no `rooms`. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.rooms.*Observation*' --tests '*HousekeepingTaskCompletionApiTest'`.
- **`recordDone`:** testes de unidade em `ScheduledCleaningDomainTest` para `updatedAt`, faxina inativa e data fora do `StorableRange`.
- **D11-E04:** em `AssignmentPlanPublishTest`, um rascunho com uma tarefa `DONE` dada a outra camareira publica normalmente e não altera essa tarefa. O teste "would move … 422" segue valendo para tarefas `IN_PROGRESS`.
- **Contrato:** regenerado. Verificação: `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingEvents.kt` (novo, público);
  - `domain/ScheduledCleaning.kt` (`recordDone`);
  - `application/HousekeepingTaskExecution.kt` (`complete`);
  - `application/AssignmentPlansService.kt` (D11-E04);
  - `web/HousekeepingTaskActionsController.kt`;
- em `src/main/kotlin/com/zeny/hotel/rooms/`: `RoomsApi.kt` e `application/RoomsApiService.kt` (`recordObservation`);
- `api/openapi.yaml`.

**Tamanho:** ~55 min; 8 arquivos de produção.

**Depende de:** Task 1.

**Interfaces:**
- Consome: `HousekeepingTaskExecution` e `requireActsOn` (Task 1); `ChecklistsApi.completeExecution`; `RoomsApi.markClean`; `ScheduledCleaningStore`.
- Produz:
  - `HousekeepingTaskExecution.complete(taskId, answers, foundOccupancy?, foundGuestCount?, actor, at, expectedVersion?)`;
  - o evento público `HousekeepingTaskCompleted`;
  - `RoomsApi.recordObservation(roomId: RoomId, occupancy: OccupancyStatus, guestCount: Int?, observedBy: UserId, observedAt: Instant)`.

### Task 3: Comandos offline, conflito RN-22 e feed da camareira

**Objetivo:**
- **Handlers offline:** os comandos `housekeeping.task.start`, `pause`, `resume`, `complete`, `refuse` e `retry` usam a mesma `HousekeepingTaskExecution`, com o `occurredAt` corrigido.
- **Escopo `OWN`:** a tarefa de outra camareira dá `REJECTED`.
- **Conflito RN-22:** segue a D8-E04.
- **Feed:** a fonte `housekeeping.tasks` tem escopo `OWN`, `assigned_user_id`, gatilho de mudança e *tombstone* (D9-E04).

**Critérios de aceite:**
- **`HousekeepingTaskOfflineTest`** (`@IntegrationTest`, com `POST /api/v1/sync/commands`):
  - um lote com `start` e `complete` produz o mesmo estado que as chamadas online;
  - o mesmo `commandId` reenviado devolve o resultado anterior sem reaplicar;
  - `occurredAt` fora da tolerância do relógio dá a rejeição do protocolo;
  - camareira sobre tarefa de outra dá `REJECTED`, com o código do escopo (Review Focus 2).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskOfflineTest'`.
- **Conflito** (`HousekeepingTaskConflictTest`): numa tarefa reatribuída a B, desatribuída ou `CANCELLED` (gravada no banco), o comando de A:
  - volta "aplicado com alerta" (`HOUSEKEEPING_TASK_CONFLICT`);
  - grava `task_conflict`;
  - marca `has_conflict`;
  - avisa a governança, conferido no painel de um usuário com `housekeeping.assignments.manage`;
  - não muda a tarefa.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskConflictTest'`.
- **Feed** (`HousekeepingTaskFeedTest`):
  - `GET /api/v1/sync/changes` da camareira traz as tarefas dela e não as de outra;
  - reatribuir uma tarefa gera o *tombstone* para a antiga dona;
  - o corpo do feed, com tarefas e UHs, não tem nome, documento, contato nem id de hóspede ou de reserva de uma reserva hospedada na UH do teste (Review Focus 5);
  - o teste chama `InventoryListenersQuiescence.await(jdbc)` antes de baixar.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskFeedTest'`.
- **Publicação da E03:** grava `assigned_user_id` junto com a camareira, e a retirada limpa o campo. O teste fica em `AssignmentPlanPublishTest` ou no `HousekeepingTaskFeedTest`.
- **Schema e contrato:**
  - a tarefa ganha `assigned_user_id`, `has_conflict` e as colunas de mudança com o gatilho `shared.mark_offline_change`;
  - entra a tabela `task_conflict`, que o banco recusa apagar;
  - `DatabaseRolesTest` atualizado;
  - contrato regenerado.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*DatabaseRolesTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingOffline.kt` (novo: fonte e tipos de comando);
  - `application/HousekeepingTaskOfflineHandlers.kt` (novo);
  - `application/HousekeepingTaskExecution.kt` (conflito);
  - `application/AssignmentPlansService.kt` (`assigned_user_id`);
  - `infrastructure/HousekeepingTaskOfflineSource.kt` (novo);
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `application/HousekeepingConfiguration.kt` (beans);
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1201__add_task_offline_and_conflicts.sql` e `V2026_11_18_1202__grant_task_conflicts_to_app_role.sql`;
- `api/openapi.yaml`.

**Tamanho:** ~60 min; 10 arquivos de produção.

**Depende de:** Task 2.

**Interfaces:**
- Consome: `HousekeepingTaskExecution` (Tasks 1 e 2), `OfflineCommandHandler`, `OfflineChangeSource`, `OfflineTombstones`, `NotificationsApi.notify` e `StaffApi.employees`.
- Produz:
  - os tipos de comando `housekeeping.task.*`;
  - a fonte `housekeeping.tasks`;
  - a tabela `task_conflict`, consultável pela E05 e pelo painel.

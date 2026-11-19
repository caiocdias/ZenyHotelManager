# E19 do M6 — Comandos offline, conflito RN-22 e feed da camareira: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** levar a execução da tarefa de governança da E04 para o app da camareira offline (RF-GOV-04, RN-22, ARCHITECTURE §6.2 e §9): comandos `housekeeping.<verbo>-task` tratados pelo mesmo caso de uso da E04, histórico de posse, conflito RN-22 registrado e sinalizado (H18 b) e a fonte `housekeeping-tasks` com as tarefas da camareira sem dado de hóspede (RN-13).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E19", H2, H18 (b e c), H21 e o handoff da E04 (notas para a E19); SPEC RN-13, RN-22, RF-GOV-04; ARCHITECTURE §6.2, §9 e §10. Brain: `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo checklists` (precedente `ChecklistsOffline`), `ZenyHotelManager - Módulo notifications`, e as capturas pendentes da E01 a E04, E20 e E21.

**Architecture:** os comandos são `OfflineCommandHandler`s do `housekeeping` (`shared/OfflineSync.kt`), um por verbo, que chamam a `HousekeepingTaskExecution` da E04 com `expectedVersion` nulo, o `occurredAt` corrigido pelo protocolo (o alerta `CLOCK_SKEW_EXCEEDED` já vem do `shared`) e a política de foto `TOLERATE`. Antes de agir, o handler decide entre três casos pela posse: dona atual (aplica), ex-dona (conflito RN-22: registra, marca, avisa e devolve aplicado com alerta, sem mudar tarefa nem UH) e quem nunca foi dona (`REJECTED` com o código do escopo). A posse vem de um histórico gravado em toda atribuição, retirada e cancelamento. O feed é uma `OfflineChangeSource` com o escopo `OWN` pelo usuário da camareira atribuída, colunas de mudança com o gatilho `shared.mark_offline_change` e *tombstone* quando a tarefa sai do escopo dela. Nenhuma aresta nova: o `housekeeping` já declara `notifications` e `checklists`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopo `OWN` nos comandos e no feed; a ex-dona não pode mudar a tarefa da nova dona; quem nunca foi dona é recusada (Tasks 1, 2 e 3);
- **concorrência:** comando offline contra ação online e contra reatribuição simultânea; ordem de trava da E04; a decisão de posse é tomada com a tarefa travada (Tasks 1 e 2);
- **dado pessoal ou segredo:** feed sem `notes` nem dado de hóspede ou de reserva (RN-13, H18 c); aviso de conflito sem dado de hóspede; o registro do conflito guarda as respostas do checklist da ex-dona (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** payloads dos comandos (Task 1);
- **migração ou escrita destrutiva de dados:** tabelas de posse e de conflito, colunas de mudança e o gatilho na tarefa, preenchimento da posse das tarefas já atribuídas (Tasks 2 e 3).

## Global Constraints

- Grafo da ARCHITECTURE §3.1 com o `ModularityTest` em igualdade exata; a declaração do `housekeeping` não muda.
- Migrações no schema `housekeeping` com timestamp posterior à última do módulo (`V2026_11_19_1001`); `outOfOrder` desligado. Tabelas de registro (posse, conflito) são só de inserção: o banco recusa `UPDATE`/`DELETE` (padrão append-only do projeto) e o `DatabaseRolesTest` é atualizado.
- Protocolo offline sem reimplementar a base (H2): rejeitar é lançar `DomainException` (vira `REJECTED` com o código, gravado); aplicar com alerta é devolver `OfflineCommandOutcome.warnings`; qualquer outra exceção é falha (`COMMAND_FAILED`, reenviar). Handler `@Audited` e `@Transactional` (regra `offlineCommandHandlersAreAudited`); o ator é o usuário da sincronização.
- Nomes: comandos `housekeeping.start-task`, `housekeeping.pause-task`, `housekeeping.resume-task`, `housekeeping.complete-task`, `housekeeping.refuse-task` e `housekeeping.retry-task`; fonte `housekeeping-tasks`. Publicados, não mudam.
- RN-13: o feed, o aviso de conflito e os alertas dos comandos não levam `notes`, nome, documento, contato nem id de hóspede ou de reserva. Uma transição inválida da própria dona continua `REJECTED` com o código da E04, e não é conflito.
- Entrada estrita da E20 nos payloads (payload fora do formato: `REJECTED COMMAND_PAYLOAD_INVALID`); `409 CONCURRENT_UPDATE`/`COMMAND_FAILED` da E21; log de falha com `ExceptionClasses.chain`.
- Toda mudança de API regenera o contrato (`./gradlew updateOpenApi`); `checkOpenApi` passa e `.github/scripts/check-api-breaking.sh` não acusa `ERR`. Enum de resposta que cresce como `x-extensible-enum`.
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e commits em pt-BR (D17). Commits por fatia com `Soul: <soul>`, intermediários (quedas de energia nesta máquina).
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- Testes: banco compartilhado; afirme só sobre os próprios dados; tarefas na data operacional corrente isoladas por UHs, tipos e camareiras próprios; `HousekeepingExecutionTestSupport.neutralizeGeneralHousekeepingTemplates()` no `@BeforeEach` de todo teste que inicia ou conclui tarefa; quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)`; trava por `pg_blocking_pids`, nunca `sleep`.
- Verificação da fatia: testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`; a suíte inteira roda no gate do orquestrador.

## Review Focus

1. **Comando da ex-dona depois de a tarefa ser concluída pela nova dona:** aplicado com o alerta de conflito, registrado, e a tarefa `DONE` e a UH Limpa da nova dona não mudam (nem a execução do checklist dela). Teste na Task 2.
2. **Reatribuição de volta para a mesma camareira** (A → B → A): A é dona atual de novo e o comando dela aplica normalmente, sem conflito. Teste na Task 2.
3. **Comando offline de uma tarefa cancelada que a camareira nunca teve:** `REJECTED` com o código do escopo, sem registro de conflito, mesmo que outra camareira a tenha tido. Teste na Task 2.
4. **`start` offline com um `versionId` de outro modelo** (não do modelo que a tarefa resolve) ou de versão inexistente: `REJECTED` com código próprio, sem abrir execução. Teste na Task 1.
5. **Tarefa que sai do escopo e volta:** a ex-dona recebe *tombstone* ao perder a tarefa e a recebe de novo (upsert) se a tarefa voltar a ela; a Governanta (`ALL`) vê a tarefa o tempo todo. Teste na Task 3.

## Decisões do plano

- **D1 — payloads.** Todo comando leva `taskId`. `start-task` leva `checklistVersionId` opcional (a versão que o aparelho tem, precedente `checklists.start-execution`): presente, precisa ser uma versão publicada do modelo que a tarefa resolve (vínculo do tipo ou `resolveTemplate`), senão `REJECTED HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`; ausente, usa a versão vigente como na E04. `complete-task` leva `answers` (com as fotos pelo `clientFileId`/`fileId`, como o `checklists.complete-execution`), `foundOccupancy` e `foundGuestCount` opcionais. `refuse-task` leva `reason`. Nenhum leva versão: a idempotência é o `commandId`.
- **D2 — instantes.** O `occurredAt` corrigido pelo protocolo vai à `HousekeepingTaskExecution` como o instante; a regra `max(instante, última transição)` da E04 mantém a ordem. Acima da tolerância, o `shared` já devolve o alerta `CLOCK_SKEW_EXCEEDED` e o comando é aplicado.
- **D3 — histórico de posse.** Tabela `housekeeping.task_assignment` (só inserção): `task_id`, `employee_id`, `assigned_at`, `ended_at` (nulo enquanto vigente; a troca grava uma linha nova de encerramento em vez de atualizar — o desenvolvedor escolhe entre linha de evento `ASSIGNED`/`ENDED` ou par de linhas, mantendo só inserção) e o motivo do fim (`REASSIGNED`, `WITHDRAWN`). É gravada pela própria tarefa em toda atribuição e reatribuição da publicação do plano (E03) e na retirada (`ASSIGNED → PENDING`) — hoje o único caminho que troca a camareira (`AssignmentPlansService`, `task.assign`/`unassign`). Não existe hoje caminho de cancelamento (só o status `CANCELLED`); a etapa que o criar grava o fim da posse com o motivo `CANCELLED`. As tarefas atribuídas antes desta etapa ganham, na migração, a linha de posse da camareira atual com `assigned_at = updated_at` (nenhuma está em produção; registrado no handoff).
- **D4 — conflito RN-22 (H18 b).** Comando de quem **foi** dona (consta no histórico) e não é mais — outra é a dona atual, a tarefa está sem camareira (retirada) ou a tarefa está `CANCELLED`: o handler grava `housekeeping.task_conflict` (só inserção: tarefa, `command_id`, tipo do comando, usuário e colaborador, `occurred_at`, o status da tarefa no servidor e o payload do comando, inclusive as respostas do checklist, como JSON), marca a tarefa com `has_conflict = true` (coluna nova; sem mudar status, versão de execução, UH nem checklist), avisa a governança e devolve aplicado com o alerta `HOUSEKEEPING_TASK_CONFLICT`. Quem **nunca** foi dona recebe `REJECTED` com `RESOURCE_OUT_OF_SCOPE`, sem registro. A resolução pela governanta fica fora (roadmap).
- **D5 — aviso de conflito.** Tipo novo `notifications.task-conflict` no catálogo do `notifications` (`NotificationTypes.kt`), enviado pelo `housekeeping` a quem tem `housekeeping.assignments.manage` (`NotificationRecipients` por permissão), com o número da UH (ou o nome da área) e o verbo do comando, sem dado de hóspede.
- **D6 — feed.** Fonte `housekeeping-tasks`, permissão `housekeeping.tasks.execute` (D2 da E04; precedente do `checklists`), escopo `OWN` pelo usuário da camareira atribuída: a tarefa ganha `assigned_user_id`, preenchido junto com `assigned_employee_id` na publicação (pela `StaffApi`) e limpo na retirada. Itens: as tarefas abertas (`ASSIGNED`, `IN_PROGRESS`, `PAUSED`, `REJECTED`, `REFUSED`), inclusive de datas anteriores (o cursor leva a posição de mudança, não a data), com `id`, `version` (o `ETag` do `If-Match` online; nota da E04), `roomId`, `areaId`, `cleaningTypeId`, `businessDate`, `status`, `priority`, `position`, `arrivalExpected`, `checklistExecutionId` e `refusalReason` — **sem `notes`** (H18 c) e sem id de hóspede ou de reserva. A tarefa que deixa de ser aberta (`DONE`, `AWAITING_INSPECTION`, `CANCELLED`) vira *tombstone* derivado da linha; a que sai do escopo da camareira (reatribuída ou retirada) gera *tombstone* para ela por `OfflineTombstones.record(..., leftScopeOf = setOf(user))`. Colunas `offline_change_xid`/`offline_change_seq` e o gatilho `shared.mark_offline_change` na tarefa (ARCHITECTURE §9). As UHs vêm da fonte `rooms`.
- **D7 — padrão offline para E05, E06 e E08.** Registrado no handoff: nomes, payload, código de recusa, alerta, posse e feed.

---

### Task 1: Comandos offline da execução

**Objetivo:** os seis comandos `housekeeping.<verbo>-task` (D1) são tratados pela `HousekeepingTaskExecution` da E04 com o `occurredAt` corrigido (D2), a política de foto `TOLERATE` e idempotência por `commandId`; o `start-task` aceita o `checklistVersionId` do aparelho. Nesta fatia, a posse é só a atual: dona atual aplica, qualquer outra pessoa recebe `REJECTED RESOURCE_OUT_OF_SCOPE` (a distinção da ex-dona vem na Task 2).

**Critérios de aceite:**
- `HousekeepingTaskOfflineTest` (`@IntegrationTest`, `POST /api/v1/sync/commands`):
  - um lote com `start-task` e `complete-task` produz o mesmo estado que as chamadas online da E04 (status, UH, checklist, tempos, evento);
  - o mesmo `commandId` reenviado devolve o resultado anterior sem reaplicar;
  - um `occurredAt` além da tolerância do relógio é aplicado com `CLOCK_SKEW_EXCEEDED`, e nenhum instante gravado fica antes da última transição da tarefa;
  - `start-task` com o `checklistVersionId` do aparelho abre a execução dessa versão mesmo que o modelo tenha publicado outra depois; com versão de outro modelo ou inexistente, `REJECTED HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID` sem abrir execução (Review Focus 4);
  - transição inválida da própria dona: `REJECTED` com o código de transição da E04;
  - outra camareira: `REJECTED RESOURCE_OUT_OF_SCOPE`;
  - payload fora do formato (ex.: `taskId: ""`): `REJECTED COMMAND_PAYLOAD_INVALID`.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskOfflineTest'`.
- `offlineCommandHandlersAreAudited` e o `ModularityTest` verdes: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.architecture.*' --tests '*ModularityTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/housekeeping/HousekeepingOffline.kt` (novo: nomes dos comandos e da fonte), `housekeeping/application/HousekeepingTaskOfflineHandlers.kt` (novo), `housekeeping/application/HousekeepingTaskExecution.kt` (versão do aparelho no `start`, política de foto, instante do comando), `housekeeping/HousekeepingTypes.kt` (código de erro novo). Testes: o citado.

**Tamanho:** ~55 min; 4 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** `HousekeepingOffline.START_TASK` … `RETRY_TASK` (`OfflineCommandType`) e `HousekeepingOffline.TASKS` (`OfflineSource`, usada na Task 3); os handlers com um ponto único de decisão de posse (`resolveActorRole(task, actor)` ou equivalente) que a Task 2 estende.

### Task 2: Histórico de posse e conflito RN-22

**Objetivo:** toda atribuição, reatribuição, retirada e cancelamento grava a posse (D3); o comando de ex-dona vira conflito registrado, marcado e avisado, aplicado com alerta, sem mudar tarefa nem UH (D4, D5); quem nunca foi dona segue recusada.

**Critérios de aceite:**
- `HousekeepingTaskConflictTest` (`@IntegrationTest`): para a ex-dona de uma tarefa reatribuída, retirada (`ASSIGNED → PENDING`) e cancelada (status gravado direto no banco, explicado no teste, porque não há caminho de cancelamento), o comando (inclusive `complete-task` com respostas):
  - volta aplicado com o alerta `HOUSEKEEPING_TASK_CONFLICT`;
  - grava a linha em `task_conflict` com as respostas do checklist;
  - marca `has_conflict` na tarefa;
  - entrega o aviso `notifications.task-conflict` no painel de um usuário com `housekeeping.assignments.manage`, com o número da UH e sem dado de hóspede;
  - não muda a tarefa (status, tempos, execução do checklist) nem a UH (Review Focus 1);
  - e o reenvio do mesmo `commandId` não grava um segundo conflito.
  - Reatribuição A → B → A: o comando de A aplica normalmente (Review Focus 2). Quem nunca foi dona (inclusive de tarefa cancelada que outra teve): `REJECTED RESOURCE_OUT_OF_SCOPE`, sem conflito (Review Focus 3).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskConflictTest'`.
- Posse: a publicação do plano (atribuição e reatribuição) e a retirada gravam o histórico; as tarefas atribuídas antes da migração têm a linha de posse atual; o banco recusa `UPDATE`/`DELETE` em `task_assignment` e `task_conflict`. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*DatabaseRolesTest' --tests '*AssignmentPlan*'`.
- O catálogo do `notifications` tem o tipo novo (teste do catálogo existente). Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.notifications.*Type*'`.
- Contrato regenerado (o tipo novo e o campo `hasConflict` na resposta da tarefa, como `x-extensible-enum` onde couber); `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `housekeeping/domain/HousekeepingTask.kt` (`hasConflict`), `housekeeping/domain/` (posse e conflito, novo arquivo), `housekeeping/application/HousekeepingTaskOfflineHandlers.kt` (decisão de posse), `housekeeping/application/AssignmentPlansService.kt` (gravar posse na atribuição, reatribuição e retirada), `housekeeping/infrastructure/` (persistência da posse e do conflito), `housekeeping/web/HousekeepingTasksController.kt` (`hasConflict` na resposta para quem tem `tasks.read`), `notifications/NotificationTypes.kt`; a migração nova; `api/openapi.yaml`. Testes: os citados.

**Tamanho:** ~60 min; 8 arquivos de produção, mais a migração e o contrato.

**Depende de:** Task 1 (estende a decisão de posse dos handlers).

**Interfaces:** consome os handlers da Task 1; produz o histórico de posse (consultável pelo `housekeeping`: "o colaborador X já foi dono da tarefa T?") e o registro de conflito.

### Task 3: Feed da camareira

**Objetivo:** a fonte `housekeeping-tasks` (D6) entrega à camareira as tarefas abertas dela, inclusive de datas anteriores, com a `version`, sem `notes` nem dado de hóspede; *tombstone* quando a tarefa sai do escopo dela ou deixa de ser aberta.

**Critérios de aceite:**
- `HousekeepingTaskFeedTest` (`@IntegrationTest`, `GET /api/v1/sync/changes` depois de `InventoryListenersQuiescence.await(jdbc)`):
  - a camareira recebe só as tarefas dela, inclusive as abertas de datas anteriores (escrita direta explicada no teste), com `version` igual ao `ETag` online;
  - reatribuir gera o *tombstone* para a antiga dona, e a tarefa volta como upsert se for reatribuída de volta (Review Focus 5); concluir e cancelar geram *tombstone*; a Governanta (`ALL`) segue vendo a tarefa reatribuída;
  - o corpo do feed, com tarefas e UHs, não contém `notes` (de uma avulsa com texto), nome, documento, contato nem id de hóspede ou de reserva de uma reserva hospedada na UH do teste.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskFeedTest'`.
- Schema: `assigned_user_id`, as colunas de mudança e o gatilho na tarefa; `HousekeepingTaskSchemaTest` e `DatabaseRolesTest` verdes. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*DatabaseRolesTest'`.
- A ARCHITECTURE §6.2 passa a mostrar os nomes vigentes (`housekeeping.complete-task`, a conclusão do checklist por dentro dele) e o padrão D7. Verificação: `grep -n 'housekeeping.complete-task' docs/ARCHITECTURE.md`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `housekeeping/application/HousekeepingTaskOfflineSource.kt` (novo, `OfflineChangeSource`), `housekeeping/domain/HousekeepingTask.kt` (`assignedUserId`), `housekeeping/application/AssignmentPlansService.kt` (preenche o usuário e grava o *tombstone* de escopo), `housekeeping/infrastructure/HousekeepingTaskPersistence.kt`, a migração nova, `docs/ARCHITECTURE.md`. Testes: os citados.

**Tamanho:** ~55 min; 5 arquivos de produção, mais a migração.

**Depende de:** Task 1 (usa `HousekeepingOffline.TASKS`).

**Interfaces:** consome `HousekeepingOffline.TASKS`; produz a fonte `housekeeping-tasks`.

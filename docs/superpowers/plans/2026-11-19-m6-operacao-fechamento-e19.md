# E19 do M6 — Comandos offline, conflito RN-22 e feed da camareira: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** levar a execução da tarefa de governança da E04 para o app da camareira offline (RF-GOV-04, RN-22, ARCHITECTURE §6.2 e §9): comandos `housekeeping.<verbo>-task` tratados pelo mesmo caso de uso da E04, histórico de posse, fonte `housekeeping-tasks` com as tarefas da camareira sem dado de hóspede (RN-13) e conflito RN-22 registrado e sinalizado (H18 b).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E19", H2, H18 (b e c), H21 e o handoff da E04 (notas para a E19); SPEC RN-13, RN-22, RF-GOV-04; ARCHITECTURE §6.2, §8.2, §9, §10, §11 e §12. Brain: `ZenyHotelManager - Sincronização offline`, `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo checklists` (precedente `ChecklistsOffline`/`ChecklistOfflineHandlers`), `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Módulo files`, e as capturas pendentes da E01 a E04, E20 e E21.

**Architecture:** os comandos são `OfflineCommandHandler`s do `housekeeping` (`shared/OfflineSync.kt`), um por verbo, que chamam a `HousekeepingTaskExecution` da E04 com `expectedVersion` nulo, o `occurredAt` corrigido pelo protocolo (o alerta `CLOCK_SKEW_EXCEEDED` já vem do `shared`) e a política de foto `TOLERATE`. A autorização passa pela `Authorization` (ARCHITECTURE §8.2): com escopo `ALL` (Governanta, Gerente), o comando aplica direto, como o online; com `OWN` (Camareira), o handler, com a tarefa travada, decide entre dona atual (aplica), ex-dona (conflito RN-22: registra, marca, avisa e devolve aplicado com alerta, sem mudar status, tempos, checklist nem UH) e quem nunca foi dona (`REJECTED RESOURCE_OUT_OF_SCOPE`). A posse vem de um histórico mínimo gravado a cada atribuição. O feed é uma `OfflineChangeSource` com o escopo `OWN` resolvido pelo colaborador do usuário (`StaffApi.employeeOfUser`), colunas de mudança com o gatilho `shared.mark_offline_change` e *tombstone* quando a tarefa sai do escopo da camareira. Nenhuma aresta nova: o `housekeeping` já declara `staff`, `checklists` e `notifications`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** escopos `OWN` e `ALL` nos comandos e no feed; a ex-dona não pode mudar a tarefa da nova dona; quem nunca foi dona é recusada (Tasks 1, 2 e 3);
- **concorrência:** comando offline contra ação online e contra reatribuição simultânea; a decisão de posse com a tarefa travada (ordem de trava da E04) (Tasks 1 e 3);
- **dado pessoal ou segredo:** feed sem `notes` nem dado de hóspede ou de reserva (RN-13, H18 c); aviso de conflito sem dado de hóspede; o registro do conflito sem texto livre numa tabela que não se apaga (ARCHITECTURE §12) (Tasks 2 e 3);
- **parsing de entrada externa não confiável:** payloads dos comandos (Task 1);
- **migração ou escrita destrutiva de dados:** tabelas só de inserção (posse, conflito), colunas de mudança e o gatilho na tarefa, preenchimento da posse e das colunas das tarefas existentes (Tasks 2 e 3).

## Global Constraints

- Grafo da ARCHITECTURE §3.1 com o `ModularityTest` em igualdade exata; a declaração do `housekeeping` não muda.
- Migrações com timestamp posterior à **última do projeto** (hoje `V2026_11_19_1001`, que também é a última do módulo); `outOfOrder` desligado. Tabela só de inserção segue a ARCHITECTURE §11: o `housekeeping` passa a declarar as suas em `AppendOnlyTables` (exigido na partida), e a concessão ao papel da aplicação faz `REVOKE UPDATE, DELETE` (padrão `checklists/V2026_10_16_0341`); o `DatabaseRolesTest` é atualizado.
- Protocolo offline sem reimplementar a base (H2): rejeitar é lançar `DomainException` (vira `REJECTED` com o código, gravado); aplicar com alerta é devolver `OfflineCommandOutcome.warnings`; qualquer outra exceção é falha (`COMMAND_FAILED`, reenviar). Handler `@Audited` e `@Transactional` (regra `offlineCommandHandlersAreAudited`); o ator é o usuário da sincronização. Os payloads são tipos próprios do `housekeeping` (o `OfflineAnswerPayload` do `checklists` é interno a ele).
- Nomes: comandos `housekeeping.start-task`, `housekeeping.pause-task`, `housekeeping.resume-task`, `housekeeping.complete-task`, `housekeeping.refuse-task` e `housekeeping.retry-task`; fonte `housekeeping-tasks`; tipo de aviso `housekeeping.task-conflict`. Publicados, não mudam.
- RN-13: o feed, o aviso de conflito e os alertas dos comandos não levam `notes`, nome, documento, contato nem id de hóspede ou de reserva. Uma transição inválida da própria dona continua `REJECTED` com o código da E04, e não é conflito.
- Entrada estrita da E20 nos payloads (fora do formato: `REJECTED COMMAND_PAYLOAD_INVALID`); `409 CONCURRENT_UPDATE`/`COMMAND_FAILED` da E21; log de falha com `ExceptionClasses.chain`.
- Toda mudança de API regenera o contrato (`./gradlew updateOpenApi`); `checkOpenApi` passa e `.github/scripts/check-api-breaking.sh` não acusa `ERR`. Enum de resposta que cresce como `x-extensible-enum`.
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e commits em pt-BR (D17). Commits por fatia com `Soul: <soul>`, intermediários (quedas de energia nesta máquina).
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- Testes: banco compartilhado; afirme só sobre os próprios dados; tarefas na data operacional corrente isoladas por UHs, tipos e camareiras próprios; `HousekeepingExecutionTestSupport.neutralizeGeneralHousekeepingTemplates()` no `@BeforeEach` de todo teste que inicia ou conclui tarefa; quem baixa `/api/v1/sync/changes` chama antes `InventoryListenersQuiescence.await(jdbc)`; trava por `pg_blocking_pids`, nunca `sleep`.
- Verificação da fatia: testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`; a suíte inteira roda no gate do orquestrador.

## Review Focus

1. **Comando da ex-dona depois de a tarefa ser concluída pela nova dona:** aplicado com o alerta de conflito, registrado, e a tarefa `DONE`, a UH Limpa e a execução do checklist da nova dona não mudam. Teste na Task 3.
2. **Reatribuição de volta para a mesma camareira** (A → B → A): A é dona atual de novo e o comando dela aplica normalmente, sem conflito. Teste na Task 3.
3. **Comando de quem nunca foi dona, inclusive de uma tarefa cancelada que outra teve:** `REJECTED RESOURCE_OUT_OF_SCOPE`, sem registro de conflito. Teste na Task 3.
4. **`start-task` com um `checklistVersionId` inexistente, de outro contexto ou de outro modelo:** inexistente ou de outro contexto dá `REJECTED HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`, sem abrir execução; versão antiga do mesmo modelo abre com `CHECKLIST_VERSION_OUTDATED`; versão de outro modelo HOUSEKEEPING abre com `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o desvio na trilha. Teste na Task 1.
5. **Tarefa que sai do escopo e volta:** a ex-dona recebe *tombstone* ao perder a tarefa e a recebe de novo (upsert) se a tarefa voltar a ela; a Governanta (`ALL`) vê a tarefa o tempo todo. Teste na Task 2.

## Decisões do plano

- **D1 — payloads e checklist offline.** Todo comando leva `taskId`. `start-task` leva `checklistVersionId` opcional (a versão que o aparelho tem, precedente `checklists.start-execution`): presente, precisa ser uma versão publicada de um modelo de contexto HOUSEKEEPING (`ChecklistsApi.version(id)`, que traz `templateId` e `context`); inexistente ou de outro contexto, `REJECTED HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`, sem abrir execução. Sendo do **mesmo modelo** que a tarefa resolve agora (vínculo do tipo, senão `resolveTemplate`, como na E04), abre essa versão, com o alerta `CHECKLIST_VERSION_OUTDATED` se não for a vigente (precedente do `checklists`). Sendo de **outro modelo** HOUSEKEEPING, ou numa tarefa que não resolve modelo nenhum, a execução abre com a versão do aparelho assim mesmo (RN-22: a execução offline não é descartada), com o alerta `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o registro do desvio na trilha da ação (modelo esperado e recebido), para a governança ver a troca de modelo (o payload vem do aparelho, entrada não confiável). Essa regra entra no padrão D7. Ausente, usa a versão vigente como na E04. `complete-task` leva `answers` (tipo próprio do `housekeeping`, com as fotos pelo `fileId` e o `answeredAt` de cada resposta, corrigido pela defasagem do relógio como no precedente), `foundOccupancy` e `foundGuestCount` opcionais. `refuse-task` leva `reason`. Nenhum leva versão: a idempotência é o `commandId`.
- **D2 — instantes.** O `occurredAt` corrigido pelo protocolo vai à `HousekeepingTaskExecution` como o instante; a regra `max(instante, última transição)` da E04 mantém a ordem. Acima da tolerância, o `shared` já devolve `CLOCK_SKEW_EXCEEDED` e o comando é aplicado.
- **D3 — histórico de posse.** Tabela `housekeeping.task_assignment(id, task_id, employee_id, assigned_at)`, só de inserção, uma linha a cada vez que a tarefa passa a uma camareira (atribuição ou reatribuição a outra; mudar só a posição não grava). A retirada (`ASSIGNED → PENDING`) e a camareira atual vêm da própria tarefa. Basta para o conflito ("o colaborador X já foi dono da tarefa T?"); sem motivo nem fim, porque nenhum consumidor desta etapa os usa (a resolução do conflito fica fora). Gravada pela publicação do plano (`AssignmentPlansService.write`, o único caminho que troca a camareira hoje; a etapa que criar o cancelamento não precisa gravar posse). As tarefas atribuídas antes desta etapa ganham, na migração, a linha da camareira atual com `assigned_at = updated_at` (nenhuma está em produção).
- **D4 — conflito RN-22 (H18 b), só com escopo `OWN`.** Ordem da decisão, com a tarefa travada: (1) tarefa `CANCELLED` ou de outra dona atual ou sem camareira, e o ator **foi** dona (consta em `task_assignment`) ⇒ conflito; (2) ator é a dona atual e a tarefa não está cancelada ⇒ aplica (transição inválida dá o `REJECTED` da E04); (3) nunca foi dona ⇒ `REJECTED RESOURCE_OUT_OF_SCOPE`, sem registro. O conflito grava `housekeeping.task_conflict` (só de inserção): tarefa, `command_id`, tipo do comando, usuário e colaborador, `occurred_at`, o status da tarefa no servidor e o **resumo estruturado** do que a ex-dona fez — no `complete-task`, cada resposta como item, tipo e valor não textual (escolha, número, quantidade, sim/não), com `text` e `note` reduzidos a "tinha texto" e as fotos só pela contagem; no `refuse-task`, o motivo; `foundOccupancy`/`foundGuestCount`. **Sem texto livre** (ARCHITECTURE §12: registro só de inserção não admite expurgo) e **sem vincular as fotos** (ficam órfãs e saem no expurgo do `files`; perda aceita e registrada). Alternativas descartadas: a execução à parte do `checklists` (`recordApart`), que publicaria `ChecklistCompleted` com efeitos (achados, estoque, OS) do trabalho de quem já não é dona; e o payload completo numa tabela expurgável com retenção, mais caro e fora do padrão. Além do registro: `has_conflict = true` na tarefa (coluna nova; muda a `version` e publica `housekeeping.task` no tópico — a nova dona rebaixa a tarefa pelo feed), o aviso (D5) e o resultado aplicado com o alerta `HOUSEKEEPING_TASK_CONFLICT`. Status, tempos, checklist e UH não mudam. Não há leitura do registro de conflito nesta etapa (fica para a etapa de resolução; `hasConflict` aparece no `GET`).
- **D5 — aviso de conflito.** Tipo `housekeeping.task-conflict`, declarado num `HousekeepingNotificationTypes` do próprio módulo e publicado como bean numa `@Configuration` do `housekeeping` para o catálogo do `notifications` (precedentes `ReservationsNotificationTypes`, `BillingNotificationTypes`); não se toca em `notifications/NotificationTypes.kt`. Enviado pelo `housekeeping`, **só no primeiro conflito de cada par (tarefa, colaborador)**, conferido com a tarefa já travada (dois lotes simultâneos não avisam duas vezes); os demais comandos do mesmo par ficam registrados sem novo aviso, inclusive numa segunda perda (A → B → A → C), em que o `hasConflict` segue como o sinal durável, a quem tem `housekeeping.assignments.manage` (`NotificationRecipients` por permissão), com o número da UH (ou o nome da área) e o verbo do comando, sem dado de hóspede. Segue as preferências como qualquer aviso; o sinal durável da RN-22 é o `hasConflict` da tarefa.
- **D6 — feed.** Fonte `housekeeping-tasks`, permissão `housekeeping.tasks.execute` (D2 da E04; precedente do `checklists`), em `infrastructure` (como `ChecklistsOfflineSources`, `RoomsOfflineSource`). Com `OWN`, a fonte resolve o colaborador do usuário do escopo (`StaffApi.employeeOfUser`) e filtra por `assigned_employee_id` (sem coluna de usuário na tarefa: uma fonte de verdade só); usuário sem colaborador recebe nenhuma tarefa, sem erro (e o comando dele cai no `RESOURCE_OUT_OF_SCOPE`); com `ALL`, todas. Itens: as tarefas abertas (`ASSIGNED`, `IN_PROGRESS`, `PAUSED`, `REJECTED`, `REFUSED`), inclusive de datas anteriores (o cursor é a posição de mudança, não a data), com `id`, `version` (o `ETag` do `If-Match` online; nota da E04), `roomId`, `areaId`, `cleaningTypeId`, `businessDate`, `status`, `priority`, `position`, `arrivalExpected`, `checklistExecutionId` e `refusalReason` (o `hasConflict` entra no item pela Task 3) — **sem `notes`** (H18 c) e sem id de hóspede ou de reserva. A tarefa que deixa de ser aberta (`DONE`, `AWAITING_INSPECTION`, `CANCELLED`) vira *tombstone* derivado da linha; a que sai do escopo da camareira (reatribuída ou retirada) gera *tombstone* para o usuário da antiga dona (`OfflineTombstones.record(..., leftScopeOf = …)`, com o usuário vindo de `StaffApi.employees`, como o `AssignmentPlansService.notifyChanges`). Colunas `change_seq`/`change_xid`, gatilho `shared.mark_offline_change`, preenchimento, `NOT NULL` e índice `(change_xid, change_seq)`, seguindo `rooms/V2026_10_16_0111__add_room_offline_change.sql`. As UHs vêm da fonte `rooms`. A troca do vínculo usuário ↔ colaborador no `staff` não reenvia o feed (registrado no handoff).
- **D7 — padrão offline para E05, E06 e E08.** Registrado no handoff: nomes, payloads, código de recusa, alertas, posse, `ALL` × `OWN` e feed.

## Revisão de arquitetura

- **Veredito:** aprovado com notas, na rodada 2 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 8 Importantes: o escopo `ALL` ignorado; os nomes das colunas de mudança; o tipo de aviso no módulo errado; um critério sem teste; `assigned_user_id` como segunda fonte de verdade; o formato da posse em aberto; texto livre numa tabela só de inserção e as fotos; a Task 2 subdimensionada. Havia também 7 Menores. Corrigido em `0fb164b`, com as decisões tomadas pelo orquestrador pela opção recomendada (autorização do usuário para este lote).
- **Rodada 1:** bloqueado por 2 Importantes novos: a versão do aparelho de outro modelo sem sinal (I9) e o `hasConflict` no feed antes de a coluna existir (I10), mais 2 Menores (avisos repetidos; usuário sem colaborador). Corrigido em `001a982`.
- **Notas Menores da rodada 2, aplicadas neste texto:** o aviso "primeiro por par" conferido com a tarefa travada e o caso A → B → A → C (N1); as áreas da Task 1 (N2); a diferença `GET` × ações se o `hasConflict` sair das ações (N3).
- **Para o usuário (sem bloquear), padrão que E05, E06 e E08 repetem:** D1 (versão de outro modelo aceita com alerta), D4 (conflito sem texto livre, fotos perdidas), D5 (aviso uma vez por par), D6 (a troca de vínculo usuário ↔ colaborador não reenvia o feed).

---

### Task 1: Comandos offline da execução

**Objetivo:** os seis comandos `housekeeping.<verbo>-task` (D1) são tratados pela `HousekeepingTaskExecution` com o `occurredAt` corrigido (D2), a política de foto `TOLERATE` e idempotência por `commandId`; o `start-task` aceita o `checklistVersionId` do aparelho. Nesta fatia, a posse é só a atual: com `ALL`, aplica; com `OWN`, a dona atual aplica e qualquer outra pessoa recebe `REJECTED RESOURCE_OUT_OF_SCOPE` (a ex-dona vem na Task 3).

**Critérios de aceite:**
- `HousekeepingTaskOfflineTest` (`@IntegrationTest`, `POST /api/v1/sync/commands`):
  - um lote com `start-task` e `complete-task` da camareira produz o mesmo estado que as chamadas online da E04 (status, UH, checklist, tempos, evento);
  - a Governanta (`ALL`), offline, inicia e conclui a tarefa de outra camareira: aplicado;
  - o mesmo `commandId` reenviado devolve o resultado anterior sem reaplicar;
  - um `occurredAt` além da tolerância do relógio é aplicado com `CLOCK_SKEW_EXCEEDED`, e nenhum instante gravado fica antes da última transição da tarefa;
  - `start-task` com o `checklistVersionId` do aparelho abre a execução dessa versão mesmo que o modelo tenha publicado outra depois, com `CHECKLIST_VERSION_OUTDATED`; de outro modelo HOUSEKEEPING, abre com `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e a trilha registra o modelo esperado e o recebido; inexistente ou de modelo de outro contexto: `REJECTED HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID`, sem abrir execução (Review Focus 4);
  - o `answeredAt` das respostas é corrigido pela defasagem;
  - transição inválida da própria dona: `REJECTED` com o código de transição da E04; outra camareira: `REJECTED RESOURCE_OUT_OF_SCOPE`; payload fora do formato (ex.: `taskId: ""`): `REJECTED COMMAND_PAYLOAD_INVALID`.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskOfflineTest'`.
- `offlineCommandHandlersAreAudited` e o `ModularityTest` verdes: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.architecture.*' --tests '*ModularityTest'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/housekeeping/HousekeepingOffline.kt` (novo: nomes dos comandos e da fonte), `housekeeping/application/HousekeepingTaskOfflineHandlers.kt` (novo, com os payloads próprios), `housekeeping/application/HousekeepingTaskExecution.kt` (a versão do aparelho no `start`, os alertas `CHECKLIST_VERSION_OUTDATED` e `HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH` e o registro do desvio na trilha), `housekeeping/application/HousekeepingAudit.kt` (códigos dos handlers), `housekeeping/HousekeepingTypes.kt` (os códigos novos de recusa e de alerta). Testes: o citado.

**Tamanho:** ~55 min; 5 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** `HousekeepingOffline.START_TASK` … `RETRY_TASK` (`OfflineCommandType`) e `HousekeepingOffline.TASKS` (`OfflineSource`, usada na Task 2); nos handlers, um ponto único de decisão de posse (`resolveOwnership(task, actor)` ou equivalente, chamado com a tarefa travada) que a Task 3 estende com a ex-dona.

### Task 2: Histórico de posse, feed e *tombstones*

**Objetivo:** toda atribuição grava a posse (D3), com o preenchimento das tarefas já atribuídas; a fonte `housekeeping-tasks` (D6) entrega à camareira as tarefas abertas dela, inclusive de datas anteriores, com a `version`, sem `notes` nem dado de hóspede; *tombstone* quando a tarefa sai do escopo dela ou deixa de ser aberta.

**Critérios de aceite:**
- `HousekeepingTaskFeedTest` (`@IntegrationTest`, `GET /api/v1/sync/changes` depois de `InventoryListenersQuiescence.await(jdbc)`):
  - a camareira recebe só as tarefas dela, inclusive as abertas de datas anteriores (escrita direta explicada no teste), com `version` igual ao `ETag` online;
  - reatribuir gera o *tombstone* para a antiga dona, e a tarefa volta como upsert se for reatribuída de volta (Review Focus 5); concluir gera *tombstone*; a Governanta (`ALL`) segue vendo a tarefa reatribuída;
  - o corpo do feed, com tarefas e UHs, não contém `notes` (de uma avulsa com texto), nome, documento, contato nem id de hóspede ou de reserva de uma reserva hospedada na UH do teste;
  - um usuário com `OWN` e sem colaborador vinculado recebe a fonte vazia, sem erro.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskFeedTest'`.
- Posse: a publicação do plano grava uma linha em `task_assignment` a cada atribuição a uma camareira (não ao mudar só a posição); as atribuídas antes da migração têm a linha da camareira atual; o banco recusa `UPDATE`/`DELETE` em `task_assignment`; o `housekeeping` declara a tabela em `AppendOnlyTables`. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*DatabaseRolesTest' --tests '*AssignmentPlan*'`.
- Schema: as colunas de mudança, o gatilho, o preenchimento, o `NOT NULL` e o índice na tarefa (D6).
- A ARCHITECTURE §6.2 passa a mostrar os nomes vigentes (`housekeeping.complete-task`, a conclusão do checklist por dentro dele) e §9 cita a fonte `housekeeping-tasks`. Verificação: `grep -n 'housekeeping.complete-task\|housekeeping-tasks' docs/ARCHITECTURE.md`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `housekeeping/infrastructure/HousekeepingTaskOfflineSource.kt` (novo, `OfflineChangeSource`), `housekeeping/infrastructure/` (persistência da posse, nova ou junto da tarefa), `housekeeping/application/AssignmentPlansService.kt` (grava a posse e o *tombstone* de escopo), `housekeeping/application/` (a porta da posse, se nova), `housekeeping/HousekeepingAppendOnlyTables.kt` (novo, ou o nome do padrão), a migração nova (posse, concessão com `REVOKE`, colunas de mudança, gatilho e preenchimentos), `docs/ARCHITECTURE.md`. Testes: os citados.

**Tamanho:** ~60 min; 6 arquivos de produção, mais a migração.

**Depende de:** Task 1 (usa `HousekeepingOffline.TASKS`).

**Interfaces:** consome `HousekeepingOffline.TASKS`; produz a fonte `housekeeping-tasks` e a consulta de posse ("o colaborador X já foi dono da tarefa T?"), usada pela Task 3.

### Task 3: Conflito RN-22 e aviso

**Objetivo:** o comando de ex-dona com escopo `OWN` vira conflito registrado sem texto livre, marcado e avisado, aplicado com alerta, sem mudar status, tempos, checklist nem UH (D4, D5); quem nunca foi dona segue recusada.

**Critérios de aceite:**
- `HousekeepingTaskConflictTest` (`@IntegrationTest`): para a ex-dona de uma tarefa reatribuída, retirada (`ASSIGNED → PENDING`) e cancelada mantendo a camareira (status gravado direto no banco, explicado no teste, porque não há caminho de cancelamento), o comando (inclusive `complete-task` com respostas de texto e foto):
  - volta aplicado com o alerta `HOUSEKEEPING_TASK_CONFLICT`;
  - grava a linha em `task_conflict` com o resumo estruturado, sem o texto livre das respostas (o texto não aparece em nenhuma coluna) e sem vincular as fotos;
  - marca `has_conflict` na tarefa (a `version` sobe e o tópico recebe `housekeeping.task`);
  - entrega o aviso `housekeeping.task-conflict` no painel de um usuário com `housekeeping.assignments.manage`, com o número da UH e sem dado de hóspede, uma vez por par (tarefa, ex-dona), mesmo com vários comandos dela no lote;
  - o feed da nova dona traz o item com `hasConflict: true`;
  - não muda status, tempos, execução do checklist nem UH (Review Focus 1);
  - o reenvio do mesmo `commandId` não grava um segundo conflito.
  - Reatribuição A → B → A: o comando de A aplica normalmente (Review Focus 2). Quem nunca foi dona (inclusive de tarefa cancelada que outra teve): `REJECTED RESOURCE_OUT_OF_SCOPE`, sem conflito (Review Focus 3).
  - O tipo `housekeeping.task-conflict` está no catálogo carregado na partida (asserção no mesmo teste, pela leitura do catálogo do `notifications`).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskConflictTest'`.
- O banco recusa `UPDATE`/`DELETE` em `task_conflict` (declarada em `AppendOnlyTables`). Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*DatabaseRolesTest'`.
- Contrato regenerado (`hasConflict` na resposta da tarefa e das ações); a ARCHITECTURE §10 cita a publicação de `housekeeping.task` na marca de conflito. `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `housekeeping/domain/HousekeepingTask.kt` (`hasConflict`), `housekeeping/application/HousekeepingTaskOfflineHandlers.kt` (decisão de posse com a ex-dona), `housekeeping/application/` (registro do conflito e o aviso), `housekeeping/infrastructure/` (persistência do conflito), `housekeeping/HousekeepingNotificationTypes.kt` (novo) e a `@Configuration` que o publica, `housekeeping/HousekeepingTypes.kt` (código do alerta), `housekeeping/application/HousekeepingTasksService.kt` (visão com `hasConflict`), `housekeeping/web/HousekeepingTasksController.kt` e `HousekeepingTaskActionsController.kt` (`hasConflict`), `housekeeping/infrastructure/HousekeepingTaskOfflineSource.kt` (`hasConflict` no item), o `AppendOnlyTables` do módulo, a migração nova (conflito com `REVOKE`, `has_conflict`), `api/openapi.yaml`, `docs/ARCHITECTURE.md`. Testes: os citados.

**Tamanho:** ~60 min; 11 a 12 arquivos de produção, mais a migração e o contrato. No teto: commits intermediários; se estourar, o `hasConflict` na resposta das ações (o último item) fica para depois, registrado no relatório e no handoff (o `GET` e as ações ficam com formas diferentes até lá; o acréscimo depois é compatível).

**Depende de:** Task 1 (estende a decisão de posse) e Task 2 (consulta de posse e `AppendOnlyTables` do módulo).

**Interfaces:** consome a decisão de posse da Task 1 e a consulta de posse da Task 2; produz o registro de conflito e o tipo `housekeeping.task-conflict`.

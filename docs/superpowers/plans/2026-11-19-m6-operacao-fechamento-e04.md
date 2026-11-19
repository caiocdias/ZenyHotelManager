# E04 do M6 — Máquina de estados, ações online e conclusão da tarefa: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar a execução **online** da tarefa de governança (RF-GOV-04, RF-GOV-05) pela máquina de estados da SPEC §8.4: iniciar, pausar, retomar, recusar (não perturbe ou hóspede), tentar de novo e concluir com checklist. A conclusão leva a UH Suja a Limpa (com ou sem inspeção pendente), registra quem limpou e os tempos, grava a ocupação encontrada (RF-UH-08), atualiza a faxina programada e publica `HousekeepingTaskCompleted`. Os comandos offline, o conflito RN-22 e o feed ficam na E19.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E04" (com as notas do plano tentado e as armadilhas), H2, H3, H18 (b a d), H19 (a), H21 e os handoffs da E01 (`recordDone`), da E02 e da E03; SPEC RF-GOV-04, RF-GOV-05, RF-UH-08, RN-13, §8.2.10, §8.3 e §8.4; ARCHITECTURE §3.1, §6.2, §7, §8, §10 e §11. Brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Segurança, autenticação e autorização`, e as capturas pendentes da E01, E02, E03, E20 e E21, que valem sobre as notas. Ponto de partida (não decisões tomadas): `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e04.md`.

**Architecture:** a máquina de estados fica no `domain` da tarefa (`HousekeepingTask`), com todas as transições da §8.4 e a ordem dos instantes. Um caso de uso único de execução (`HousekeepingTaskExecution`, `application`) atende as ações online agora e os comandos offline da E19 depois. A UH só muda pela `RoomsApi` (`markClean`, `setServiceFlag` e a observação nova); o checklist só pela `ChecklistsApi` (com a consulta nova da versão publicada vigente). A ordem de trava em toda ação é **tarefa → execução do checklist → UH**. Nenhuma aresta nova de módulo: `housekeeping` já declara `rooms`, `checklists`, `staff` e `notifications`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (eventos públicos), Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissão nova de execução com escopo `OWN` (dona = a camareira atribuída) e a leitura da camareira que não pode vazar a lista, o plano e o tópico (Tasks 1 e 3);
- **concorrência:** ação e conclusão simultâneas na mesma tarefa e UH; ordem de trava tarefa → execução → UH (Tasks 1, 2 e 3);
- **dado pessoal ou segredo:** o evento `HousekeepingTaskCompleted`, as mensagens de tempo real e as respostas sem dado de hóspede (RN-13) (Tasks 1 e 2);
- **parsing de entrada externa não confiável:** corpos das ações (motivo da recusa, respostas do checklist, ocupação encontrada) (Tasks 1 e 2);
- **migração ou escrita destrutiva de dados:** colunas novas na tarefa, sem apagar dados (Tasks 1 e 2).

## Global Constraints

- Grafo da ARCHITECTURE §3.1 com o `ModularityTest` em igualdade exata; a declaração do `housekeeping` não muda. Acréscimos às APIs públicas de `rooms` e `checklists` são dependências já declaradas.
- Migrações no schema `housekeeping` com timestamp posterior a `V2026_11_18_1102` (a última do módulo); as do `rooms` ou `checklists`, se houver, posteriores à última de cada um. `outOfOrder` desligado.
- Transição inválida dá `409` com o código `HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED` (padrão `…_TRANSITION_NOT_ALLOWED` do projeto, `RESERVATION_TRANSITION_NOT_ALLOWED`). Referência inexistente no corpo dá `400 …UNKNOWN_…` (H16).
- Ações online como subrecurso de ação (ARCHITECTURE §7), com `If-Match` (`428` sem, `412` divergente). Toda escrita da `application` é `@Audited`, com retrato sem dado pessoal. A autorização vem antes de qualquer recusa que revele estado (`403` antes de `404`, `409` e `412`).
- O status da UH nunca muda fora da `RoomsApi`. Nenhuma resposta, evento, trilha ou mensagem de tempo real leva nome, documento, contato nem id de hóspede ou de reserva (RN-13).
- Entrada estrita da E20 e `409 CONCURRENT_UPDATE` da E21 valem; os DTOs novos seguem a regra do `required` (campo opcional = anulável sem anotação ou com padrão; DTO só de ação não tem padrão que sobrescreva estado).
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` (enum de resposta que cresce como `x-extensible-enum`); `./gradlew checkOpenApi` passa e `.github/scripts/check-api-breaking.sh` não acusa `ERR`.
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e commits em pt-BR (D17). Commits por fatia com o trailer `Soul: <soul>`; faça commits intermediários (houve quedas de energia nesta máquina).
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- Testes: o banco compartilhado recebe avanços de data e planos de outros testes e a geração roda para o hotel inteiro; os testes afirmam só sobre as próprias tarefas, UHs e camareiras e nunca contam nada de forma global. As tarefas de teste nascem como avulsas de datas próprias e são atribuídas pelo próprio teste (publicação de plano numa data futura própria, ou escrita direta explicada no teste). Trava determinística por `pg_blocking_pids` (padrão de `ScheduledCleaningsTypeLockTest`, E01), nunca `sleep`.
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`; a suíte inteira roda no gate do orquestrador.

## Review Focus

1. **Instantes fora de ordem:** uma ação cujo instante (relógio do servidor hoje; `occurredAt` corrigido na E19) é anterior à última transição da tarefa grava `max(instante, última transição)`, e a duração nunca fica negativa. Teste de domínio na Task 1.
2. **Pausar e retomar várias vezes:** `worked_seconds` soma só os trechos em `IN_PROGRESS`, e um `pause` logo depois do `start` dá zero, não negativo. Teste de domínio na Task 1.
3. **Concluir tarefa de área ou de UH já Limpa/Inspecionada com tipo que exige inspeção:** a UH não muda, e a tarefa vai a `DONE` (D3), sem chamar `markClean` nem deixar uma tarefa `AWAITING_INSPECTION` sem inspeção pendente no `rooms`. Teste na Task 2.
4. **Item obrigatório faltando no checklist:** `422`, e nada muda (tarefa, execução, UH, faxina programada, observação, evento). Teste na Task 2.
5. **Camareira sem permissão de leitura:** com o modelo Camareira, a lista do dia, o plano e o tópico `housekeeping` não lhe entregam tarefa nenhuma, inclusive as dela (a leitura dela vem da E19). Teste na Task 3.

## Decisões do plano

- **D1 — permissão de execução.** `housekeeping.tasks.execute`, escopos `OWN` e `ALL`, não sensível: `OWN` no modelo Camareira (`RoleTemplate.ROOM_ATTENDANT`), `ALL` nos modelos Governanta e Gerente. A dona de uma tarefa é a camareira atribuída: o colaborador de `assigned_employee_id` cujo usuário é o ator (`StaffApi.employeeOfUser`). Com `OWN`, uma tarefa inexistente também dá `403` (não revela a existência); com `ALL`, `404`.
- **D2 — leitura da camareira (armadilha do roadmap).** O modelo Camareira **não** recebe `housekeeping.tasks.read`, que segue só com `ALL`: a lista do dia, o plano e o tópico `housekeeping` continuam filtrados por `ALL` e não vazam. A camareira vê as próprias tarefas pela fonte offline da E19 (precedente do `checklists`: as fontes usam a permissão de execução) e pela resposta das ações. Alternativa descartada: `OWN` em `tasks.read`, que exigiria `scopeFilter` e dono em cada leitura.
- **D3 — estado da conclusão.** A tarefa vai a `AWAITING_INSPECTION` só quando a conclusão levou a UH de Suja a Limpa com inspeção pendente (`markClean(requiresInspection = true)`); em qualquer outro caso (tipo sem inspeção, UH já Limpa ou Inspecionada, tarefa de área) vai a `DONE`. Assim, toda tarefa `AWAITING_INSPECTION` tem a inspeção pendente que a `RoomsApi.rejectInspection` exige; a E05 recebe isso no handoff (H18 d).
- **D4 — ações.** `POST /api/v1/housekeeping-tasks/{id}/start`, `/pause`, `/resume`, `/refuse` (corpo `{ "reason": "DO_NOT_DISTURB" | "GUEST_REFUSED" }`), `/retry` e `/complete`, todas com `If-Match` e resposta `200` com a tarefa e o `ETag` novo. `start` aceita `ASSIGNED` e `REJECTED` (refazer). `refuse` só de `ASSIGNED` (SPEC §8.4). `retry` leva `REFUSED → ASSIGNED`. A recusa por não perturbe numa UH **ocupada** liga o sinal pela `RoomsApi.setServiceFlag` (origem `TASK`); numa UH **vaga** ou tarefa de área, não chama o `rooms` e responde `200` com a tarefa `REFUSED` (comportamento descrito na operação do contrato).
- **D5 — checklist.** No `start` (e no refazer), o modelo é o vínculo do tipo de limpeza, se ativo e publicado, senão `ChecklistsApi.resolveTemplate` (D1 da E01), senão nenhum; havendo modelo, a execução abre com a **versão publicada vigente** do modelo, pela consulta nova `ChecklistsApi.currentVersion(templateId): ChecklistVersionView?`, e a tarefa guarda o `checklist_execution_id` (o refazer abre uma execução nova). No `complete`, a execução é concluída com as respostas do corpo pela `ChecklistsApi.completeExecution`. Se a execução **já foi concluída por fora** (endpoints ou comandos do `checklists`), a tarefa conclui sem chamar de novo o `checklists` (as respostas do corpo são ignoradas, e a operação diz isso): nada de 500 nem de um segundo `ChecklistCompleted`.
- **D6 — tempos e autoria.** `started_at` (primeiro `start`), `finished_at` (conclusão), `worked_seconds` (soma dos trechos em `IN_PROGRESS`), `cleaned_by` (usuário da conclusão) e `last_transition_at`; o instante de cada transição é `max(agora, last_transition_at)` (Review Focus 1). O offline (E19) passa o `occurredAt` corrigido no lugar do `agora`.
- **D7 — evento.** `HousekeepingTaskCompleted(taskId, roomId?, areaId?, cleaningTypeId, businessDate, finalStatus, completedBy, completedAt)` no arquivo público de eventos do módulo, publicado uma vez na transação da conclusão, sem dado de hóspede. Sem consumidor ainda (SPEC §8.3 corrigida em 2026-11-18).
- **D8 — tempo real.** Toda transição publica a mensagem de tarefa no tópico `housekeeping` (D9 da E03), com o payload padrão do projeto (id e versão).
- **D9 — trava.** Toda ação trava a tarefa primeiro (leitura com trava de linha), depois a execução do checklist (pela `ChecklistsApi`), depois a UH (pela `RoomsApi`). A espera passa pelo `lock_timeout` da E21 (`409 CONCURRENT_UPDATE`).
- **D10 — nota da E03 (publicação).** Na publicação de um plano, o item de uma tarefa `DONE` ou `CANCELLED` é ignorado (a tarefa não muda), em vez de recusar a publicação inteira: com a execução, uma tarefa concluída entre a sugestão e a publicação deixaria de travar a governanta. As outras notas da E03 (CHECK `ASSIGNED ⇒ camareira`, desempate, extração da `AssignmentPlanPublication`) ficam para quando a publicação mudar de novo.

---

### Task 1: Máquina de estados, permissão de execução e ações online sem conclusão

**Objetivo:** a tarefa cobre todas as transições da §8.4 com a ordem dos instantes e os tempos (D6); as ações `start`, `pause`, `resume`, `refuse` e `retry` (D4) ficam disponíveis com `housekeeping.tasks.execute` e o escopo `OWN`/`ALL` (D1), com trava (D9), trilha e tempo real (D8). O `start` ainda não abre checklist (Task 2).

**Critérios de aceite:**
- `HousekeepingTaskDomainTest` (unidade): todas as transições válidas e inválidas da §8.4, inclusive `ASSIGNED → PENDING` (H19 a), `REJECTED → IN_PROGRESS` e `REFUSED → ASSIGNED`; a ordem dos instantes e `worked_seconds` com pausas (Review Focus 1 e 2). Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskDomainTest'`.
- `HousekeepingTaskActionsApiTest` (`@IntegrationTest`): a camareira atribuída faz `start`, `pause`, `resume`, `refuse` e `retry` com `If-Match`, recebendo a tarefa e o `ETag` novo; transição inválida dá `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED` sem mudar a UH; `refuse` com `DO_NOT_DISTURB` numa UH ocupada liga o sinal no `rooms`, e numa UH vaga ou tarefa de área responde `200` sem chamar o `rooms` e sem 500; cada ação gera a entrada de trilha. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskActionsApiTest'`.
- `HousekeepingTaskExecutionPermissionsTest` (`@IntegrationTest`): camareira com `OWN` sobre tarefa de outra dá `403` antes de `404`, `409` e `412` (inclusive tarefa inexistente, D1); a Governanta com `ALL` age sobre qualquer tarefa; sem a permissão, `403`; a permissão e os modelos (D1) estão no catálogo. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskExecutionPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- Tempo real: um cliente inscrito no tópico `housekeeping` (com `housekeeping.tasks.read`) recebe uma mensagem por transição, com o id e a versão (contexto `RANDOM_PORT`, como o `AssignmentPlanRealtimeTest`). Verificação: o teste citado no relatório.
- Schema: colunas novas na tarefa (`started_at`, `finished_at`, `worked_seconds`, `in_progress_since`, `last_transition_at`, `cleaned_by`, `refusal_reason`) com CHECKs de coerência, sem apagar dados; `DatabaseRolesTest` e o teste de schema do módulo verdes. Contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests '*Housekeeping*Schema*' --tests '*DatabaseRolesTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingTypes.kt` (`RefusalReason`), `HousekeepingPermissions.kt`, `domain/HousekeepingTask.kt`, `application/HousekeepingTaskExecution.kt` (novo), `infrastructure/HousekeepingTaskPersistence.kt` (leitura com trava), `web/HousekeepingTaskActionsController.kt` (novo); `src/main/resources/db/migration/housekeeping/V2026_11_19_…__add_task_execution.sql`; `api/openapi.yaml`. Testes: os citados.

**Tamanho:** ~60 min; 7 arquivos de produção, mais a migração e o contrato.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `HousekeepingTaskExecution` com `start`, `pause`, `resume`, `refuse(…, reason: RefusalReason, …)` e `retry`, cada uma `(taskId: HousekeepingTaskId, actor: …, at: Instant, expectedVersion: Long?)` (`expectedVersion` nulo no offline da E19), devolvendo a tarefa;
- a verificação de escopo `requireActsOn(task, actor)` (interna ao módulo), usada pela Task 2 e pela E19;
- `enum class RefusalReason { DO_NOT_DISTURB, GUEST_REFUSED }`;
- `HousekeepingTask.start/pause/resume/refuse/retry/complete(...)` no domínio (o `complete` do domínio já existe aqui; a Task 2 o usa).

### Task 2: Conclusão com checklist, UH, observação, faxina programada e evento

**Objetivo:** o `start` abre a execução do checklist com a versão publicada vigente (D5); `POST …/complete` (D4) conclui numa transação só, na ordem de trava D9: fecha a execução com as respostas (ou aceita a já concluída por fora), leva a UH Suja a Limpa (D3), grava a observação de ocupação quando informada, atualiza a faxina programada, grava quem limpou e os tempos e publica `HousekeepingTaskCompleted` (D7).

**Critérios de aceite:**
- `HousekeepingTaskCompletionApiTest` (`@IntegrationTest`):
  - UH Suja, tipo sem inspeção: UH Limpa e tarefa `DONE`; com inspeção: UH Limpa com inspeção pendente e tarefa `AWAITING_INSPECTION`;
  - UH já Limpa ou Inspecionada e tarefa de área: a UH não muda, a tarefa vai a `DONE` e o checklist conclui (D3, Review Focus 3);
  - `start` com modelo abre a execução da versão publicada vigente e guarda o id; sem modelo, a tarefa segue sem checklist;
  - item obrigatório faltando dá `422`, sem mudar tarefa, execução, UH, faxina, observação nem evento (Review Focus 4);
  - execução já concluída por fora: a tarefa conclui sem 500 e sem um segundo `ChecklistCompleted`;
  - `cleanedBy`, `startedAt`, `finishedAt` e a duração sem as pausas gravados;
  - `HousekeepingTaskCompleted` publicado uma vez (ouvinte de teste ou registro do Modulith), sem dado de hóspede.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskCompletionApiTest'`.
- Ocupação encontrada: a `RoomsApi` ganha `recordObservation(roomId, occupancy, guestCount?, observedBy, observedAt)`, que grava a observação que o endpoint do `rooms` já grava (mesma regra, sem permissão — quem chama se autoriza); `foundOccupancy` divergente na conclusão aparece nas discrepâncias do `rooms` (RF-UH-08). Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.rooms.*Observation*' --tests '*HousekeepingTaskCompletionApiTest'`.
- `ScheduledCleaning.recordDone`: tarefa `SCHEDULED` concluída atualiza `lastDoneOn` e `nextDueOn`; `recordDone` atualiza `updatedAt`, recusa faxina inativa e data fora do `StorableRange` (testes de unidade). Verificação: `timeout 900 ./gradlew test --tests '*ScheduledCleaning*Domain*'`.
- `ChecklistsApi.currentVersion(templateId)`: devolve a versão publicada vigente, ou nulo para modelo sem versão publicada ou inativo (teste no `checklists`). Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*Api*'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingEvents.kt` (novo, público), `domain/ScheduledCleaning.kt`, `application/HousekeepingTaskExecution.kt`, `web/HousekeepingTaskActionsController.kt`; em `rooms/`: `RoomsApi.kt` e `application/RoomsApiService.kt`; em `checklists/`: `ChecklistsApi.kt` e `application/ChecklistTemplates.kt` (a consulta nova); a migração da tarefa (`checklist_execution_id`), se não couber na da Task 1; `api/openapi.yaml`. Testes: os citados.

**Tamanho:** ~60 min; 8 arquivos de produção, mais o contrato.

**Depende de:** Task 1 (usa `HousekeepingTaskExecution`, `requireActsOn` e as colunas de tempo).

**Interfaces:** consome as da Task 1; produz `HousekeepingTaskExecution.complete(taskId, answers, foundOccupancy?, foundGuestCount?, actor, at, expectedVersion?)`, o evento público `HousekeepingTaskCompleted`, `RoomsApi.recordObservation(...)` e `ChecklistsApi.currentVersion(templateId)`.

### Task 3: Leitura da camareira, concorrência e a nota da publicação

**Objetivo:** provar a D2 (a camareira não lê tarefas pela lista, pelo plano nem pelo tópico) e a D9 (ação e conclusão simultâneas na mesma tarefa e UH se enfileiram sem deadlock), e aplicar a D10 na publicação do plano.

**Critérios de aceite:**
- `HousekeepingAttendantReadTest` (`@IntegrationTest`, usuário com o modelo Camareira): `GET` da lista do dia, `GET` do plano e o tópico `housekeeping` não entregam tarefa nenhuma (`403` nas leituras; nenhuma mensagem no tópico), inclusive as dela (Review Focus 5); a Governanta, com `ALL`, segue lendo. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingAttendantReadTest'`.
- `HousekeepingTaskLockTest` (`@IntegrationTest`, determinístico por `pg_blocking_pids`): uma ação (`pause`) e uma conclusão simultâneas na mesma tarefa e UH se enfileiram sem deadlock: uma passa e a outra vê o estado novo (`409` de transição ou `412`), nunca `500` nem `409 CONCURRENT_UPDATE` por deadlock; e uma conclusão concorrente com uma mudança de status da UH pelo `rooms` segue a ordem tarefa → execução → UH. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskLockTest'`.
- D10: em `AssignmentPlanPublishTest` (ou teste novo), um rascunho com uma tarefa `DONE` dada a outra camareira publica normalmente e não altera a tarefa; o caso de tarefa `IN_PROGRESS` segue recusado como hoje. Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlan*'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/housekeeping/application/AssignmentPlansService.kt` (D10; hoje a recusa vem do `requireNotStarted` do domínio da tarefa, `domain/HousekeepingTask.kt`); ajustes de trava em `HousekeepingTaskExecution.kt`, se o teste de concorrência pedir. Testes: os citados.

**Tamanho:** ~45 min; 1 a 2 arquivos de produção.

**Depende de:** Task 2 (o teste de concorrência usa a conclusão).

**Interfaces:** nenhuma nova.

# E04 do M6 — Máquina de estados, ações online e conclusão da tarefa: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar a execução **online** da tarefa de governança (RF-GOV-04, RF-GOV-05) pela máquina de estados da SPEC §8.4: iniciar, pausar, retomar, recusar (não perturbe ou hóspede), tentar de novo e concluir com checklist. A conclusão leva a UH Suja a Limpa (com ou sem inspeção pendente), registra quem limpou e os tempos, grava a ocupação encontrada (RF-UH-08), atualiza a faxina programada e publica `HousekeepingTaskCompleted`. Os comandos offline, o conflito RN-22 e o feed ficam na E19.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E04" (com as notas do plano tentado e as armadilhas), H2, H3, H18 (b a d), H19 (a), H21 e os handoffs da E01 (`recordDone`), da E02, da E03, da E20 e da E21; SPEC RF-GOV-04, RF-GOV-05, RF-UH-08, RN-13, §8.2.10, §8.3 e §8.4; ARCHITECTURE §3.1, §6.2, §7, §8, §10 e §11. Brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Módulo checklists`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Tempo real (SSE)`, `ZenyHotelManager - Segurança, autenticação e autorização`, e as capturas pendentes da E01, E02, E03, E20 e E21, que valem sobre as notas. Ponto de partida (não decisões tomadas): `docs/superpowers/plans/2026-11-18-m6-operacao-fechamento-e04.md`.

**Architecture:** a máquina de estados fica no `domain` da tarefa (`HousekeepingTask`), com todas as transições da §8.4 e a ordem dos instantes. Um caso de uso único de execução (`HousekeepingTaskExecution`, `application`) atende as ações online agora e os comandos offline da E19 depois. A UH só muda pela `RoomsApi` e o checklist só pela `ChecklistsApi`; toda decisão que depende do estado deles é tomada **com a linha travada, dentro do outro módulo**, por operações condicionais novas (precedente `RoomsApi.markOccupiedIfReady`), e nunca por uma leitura solta seguida de ação. A ordem de trava em toda ação é **tarefa → execução do checklist → UH → faxina programada**. Nenhuma aresta nova de módulo: `housekeeping` já declara `rooms`, `checklists`, `staff` e `notifications`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (eventos públicos), Hibernate 7.4, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** permissão nova de execução com escopo `OWN` (dona = a camareira atribuída) e a leitura da camareira que não pode vazar a lista, o plano, o tópico nem a `notes` (Tasks 1 e 3);
- **concorrência:** ação e conclusão simultâneas na mesma tarefa; conclusão concorrente com mudança da UH pelo `rooms`, com a conclusão do checklist pelo `checklists` e com a edição da faxina; ordem de trava (Tasks 1, 2 e 3);
- **dado pessoal ou segredo:** respostas das ações sem `notes` nem dado de hóspede, evento `HousekeepingTaskCompleted` e mensagens de tempo real sem dado de hóspede (RN-13) (Tasks 1, 2 e 3);
- **parsing de entrada externa não confiável:** corpos das ações (motivo da recusa, respostas do checklist, ocupação encontrada) (Tasks 1 e 2);
- **migração ou escrita destrutiva de dados:** colunas novas na tarefa, sem apagar dados (Tasks 1 e 2).

## Global Constraints

- Grafo da ARCHITECTURE §3.1 com o `ModularityTest` em igualdade exata; a declaração do `housekeeping` não muda. Acréscimos às APIs públicas de `rooms` e `checklists` são dependências já declaradas.
- Migrações no schema `housekeeping` com timestamp posterior a `V2026_11_18_1102` (a última do módulo); as do `rooms` ou `checklists`, se houver, posteriores à última de cada um. `outOfOrder` desligado.
- Transição inválida dá `409` com o código `HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED` (padrão `…_TRANSITION_NOT_ALLOWED` do projeto, `RESERVATION_TRANSITION_NOT_ALLOWED`). Referência inexistente no corpo dá `400 …UNKNOWN_…` (H16). Enum de resposta (`RefusalReason`, `TaskStatus`) sai como `x-extensible-enum`.
- Ações online como subrecurso de ação (ARCHITECTURE §7), com `If-Match` (`428` sem, `412` divergente). Toda escrita da `application` é `@Audited`, com retrato sem dado pessoal. A autorização vem antes de qualquer recusa que revele estado (`403` antes de `404`, `409` e `412`).
- O status da UH nunca muda fora da `RoomsApi`, e o checklist nunca fora da `ChecklistsApi`. Nenhuma resposta das ações, evento, trilha ou mensagem de tempo real leva `notes`, nome, documento, contato nem id de hóspede ou de reserva (RN-13, H18 c).
- Entrada estrita da E20 e `409 CONCURRENT_UPDATE` da E21 valem; os DTOs novos seguem a regra do `required` (campo opcional = anulável sem anotação ou com padrão; DTO só de ação não tem padrão que sobrescreva estado). Log de falha usa `ExceptionClasses.chain` (E21).
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi`; `./gradlew checkOpenApi` passa e `.github/scripts/check-api-breaking.sh` não acusa `ERR`.
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e commits em pt-BR (D17). Commits por fatia com o trailer `Soul: <soul>`; faça commits intermediários (houve quedas de energia nesta máquina).
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- Testes: o banco compartilhado recebe avanços de data e planos de outros testes e a geração roda para o hotel inteiro; os testes afirmam só sobre as próprias tarefas, UHs e camareiras e nunca contam nada de forma global. As tarefas de teste nascem como avulsas de datas próprias (não posteriores à data operacional, por causa da D11) e são atribuídas pelo próprio teste (publicação de plano, ou escrita direta explicada no teste). Trava determinística por `pg_blocking_pids` (padrão de `ScheduledCleaningsTypeLockTest`, E01), nunca `sleep`.
- Verificação da fatia: os testes focados citados mais `timeout 900 ./gradlew checkOpenApi lint`; a suíte inteira roda no gate do orquestrador.

## Review Focus

1. **Instantes fora de ordem:** uma ação cujo instante (relógio do servidor hoje; `occurredAt` corrigido na E19) é anterior à última transição da tarefa grava `max(instante, última transição)`, e a duração nunca fica negativa. Teste de domínio na Task 1.
2. **Pausar e retomar várias vezes:** `worked_seconds` soma só os trechos em `IN_PROGRESS`, e um `pause` logo depois do `start` dá zero, não negativo. Teste de domínio na Task 1.
3. **Concluir tarefa de área ou de UH já Limpa/Inspecionada com tipo que exige inspeção:** a UH não muda (a operação condicional do `rooms` devolve "não mudou"), e a tarefa vai a `DONE` (D3). Teste na Task 2.
4. **Item obrigatório faltando no checklist:** `422`, e nada muda (tarefa, execução, UH, faxina programada, observação, evento). Teste na Task 2.
5. **Camareira sem permissão de leitura:** com o modelo Camareira, a lista do dia, o plano e o tópico `housekeeping` não lhe entregam tarefa nenhuma, inclusive as dela, e a resposta das ações não traz `notes`. Testes nas Tasks 1 e 3.

## Decisões do plano

- **D1 — permissão de execução.** `housekeeping.tasks.execute`, escopos `OWN` e `ALL`, não sensível: `OWN` no modelo Camareira (`RoleTemplate.ROOM_ATTENDANT`), `ALL` nos modelos Governanta e Gerente. A dona é a camareira atribuída: o colaborador de `assigned_employee_id` cujo usuário é o ator (`StaffApi.employeeOfUser`). Com `OWN`, uma tarefa inexistente dá o **mesmo** `403` (`RESOURCE_OUT_OF_SCOPE`, mesmo `detail`) que uma tarefa alheia, sem revelar a existência; com `ALL`, `404`. Diverge de propósito do `checklists`, que responde `404` fora do escopo na leitura: aqui é escrita, e o critério do roadmap pede `403` antes de `404`.
- **D2 — leitura da camareira (armadilha do roadmap).** O modelo Camareira **não** recebe `housekeeping.tasks.read`, que segue só com `ALL`: a lista do dia, o plano e o tópico `housekeeping` continuam filtrados por `ALL` e não vazam. A camareira vê as próprias tarefas pela fonte offline da E19 (precedente do `checklists`: as fontes usam a permissão de execução), que precisa levar a `version` de cada tarefa (o `ETag` do `If-Match`; nota para a E19), e pela resposta das ações. A resposta das ações é um schema próprio (`HousekeepingTaskActionResponse`), **sem `notes`** (H18 c, RN-13); o `GET` da tarefa com `tasks.read` segue com `notes`. Alternativa descartada: `OWN` em `tasks.read`, que exigiria `scopeFilter` e dono em cada leitura.
- **D3 — estado da conclusão.** A tarefa vai a `AWAITING_INSPECTION` só quando a conclusão levou a UH de Suja a Limpa com inspeção pendente (a operação condicional do `rooms` devolve "mudou"); em qualquer outro caso (tipo sem inspeção, UH já Limpa ou Inspecionada, tarefa de área) vai a `DONE`. Alternativa descartada: `AWAITING_INSPECTION` sempre que o tipo exige inspeção, com a aprovação só na tarefa e o `markInspected` voluntário — deixaria tarefa à espera de uma inspeção que o `rooms` não tem pendente. A garantia vale no instante da conclusão: depois dela, o check-out ou o fechamento (`markDirty`) ou a aprovação manual pelo `rooms` podem tirar a pendência; a E05 trata a tarefa `AWAITING_INSPECTION` cuja UH já perdeu a pendência (nota de handoff). A SPEC §8.4 ("checklist concluído (com inspeção)") ganha a nota pelo `arquiteto`, como a `ASSIGNED → PENDING`.
- **D4 — ações.** `POST /api/v1/housekeeping-tasks/{id}/start`, `/pause`, `/resume`, `/refuse` (corpo `{ "reason": "DO_NOT_DISTURB" | "GUEST_REFUSED" }`), `/retry` e `/complete`, todas com `If-Match` e resposta `200` com a `HousekeepingTaskActionResponse` e o `ETag` novo. `start` aceita `ASSIGNED` e `REJECTED` (refazer). `refuse` só de `ASSIGNED` (SPEC §8.4). `retry` leva `REFUSED → ASSIGNED`. A recusa por não perturbe chama a operação condicional `RoomsApi.setServiceFlagIfOccupied` (origem `TASK`), que liga o sinal numa UH ocupada e não faz nada numa UH vaga, decidindo com a linha da UH travada; tarefa de área não chama o `rooms`. Em todos os casos a recusa responde `200` com a tarefa `REFUSED`, e a operação do contrato descreve isso.
- **D5 — checklist.** No `start` (e no refazer), o modelo é o vínculo do tipo de limpeza, se ativo e publicado, senão `ChecklistsApi.resolveTemplate` (D1 da E01), senão nenhum; havendo modelo, a execução abre com a **versão publicada vigente** (consulta nova `ChecklistsApi.currentVersion(templateId): ChecklistVersionView?`), e a tarefa guarda o `checklist_execution_id` (o refazer abre uma execução nova). No `complete`, a execução é concluída pela operação condicional nova `ChecklistsApi.completeExecutionIfOpen(...)`, que trava a execução e, se ela **já foi concluída por fora**, devolve "já concluída" sem erro e sem um segundo `ChecklistCompleted` (as respostas do corpo são ignoradas, e a operação do contrato diz isso); com a execução aberta, conclui com as respostas e mantém o `422` de item obrigatório faltando. `answers` num `complete` de tarefa sem execução de checklist dá `400 VALIDATION_FAILED` no campo `answers`.
- **D6 — tempos e autoria.** `started_at` (primeiro `start`), `finished_at` (conclusão), `worked_seconds` (soma dos trechos em `IN_PROGRESS`), `cleaned_by` (usuário que concluiu — a Governanta, se foi ela) e `last_transition_at`; o instante de cada transição é `max(agora, last_transition_at)` (Review Focus 1). O histórico por camareira (RF-GOV-05) vem de `assigned_employee_id`, não de `cleaned_by`. O offline (E19) passa o `occurredAt` corrigido no lugar do `agora`.
- **D7 — evento.** `HousekeepingTaskCompleted(taskId, roomId?, areaId?, cleaningTypeId, businessDate, finalStatus, completedBy, completedAt)` no arquivo público de eventos do módulo, publicado uma vez na transação da conclusão, sem dado de hóspede. Sem consumidor ainda (SPEC §8.3 corrigida em 2026-11-18).
- **D8 — tempo real.** Toda transição publica a mensagem de tarefa no tópico `housekeeping` (D9 da E03), com o payload padrão do projeto (id e versão). Entra na Task 3 (mitigação do roadmap: o tempo real é o primeiro candidato a sair de uma fatia cheia).
- **D9 — trava.** Toda ação trava a tarefa primeiro (leitura com trava de linha pelo `HousekeepingTaskStore`), depois a execução do checklist (dentro de `completeExecutionIfOpen`), depois a UH (dentro das operações condicionais do `rooms`), e por último a faxina programada (`ScheduledCleaningStore.forUpdate`). A espera passa pelo `lock_timeout` da E21 (`409 CONCURRENT_UPDATE`).
- **D10 — faxina programada.** A conclusão de uma tarefa `SCHEDULED` chama `ScheduledCleaning.recordDone(businessDate da tarefa)` com a faxina travada. `recordDone` passa a atualizar `updatedAt`, recusa data fora do `StorableRange` e **não age sobre faxina inativa**: nesse caso a tarefa conclui mesmo assim, a faxina não muda, e a trilha da conclusão registra que a faxina estava inativa (o trabalho feito não é recusado; na E19 isso seria comando recusado de trabalho real).
- **D11 — data da tarefa.** O `start` só aceita tarefa com `businessDate` até a data operacional corrente (tarefas abertas de datas anteriores seguem válidas); de data futura dá `409 HOUSEKEEPING_TASK_NOT_DUE`.
- **D12 — nota da E03 (publicação).** Na publicação de um plano, o item de uma tarefa `DONE` ou `CANCELLED` é ignorado: a tarefa não muda e o item **sai do plano publicado** (não fica apontando outra camareira), em vez de recusar a publicação inteira. As outras notas da E03 (CHECK `ASSIGNED ⇒ camareira`, desempate, extração da `AssignmentPlanPublication`) ficam para quando a publicação mudar de novo.

---

### Task 1: Máquina de estados, permissão de execução e ações online sem conclusão

**Objetivo:** a tarefa cobre todas as transições da §8.4 com a ordem dos instantes e os tempos (D6); as ações `start`, `pause`, `resume`, `refuse` (sem o sinal de não perturbe, que é da Task 2) e `retry` (D4) ficam disponíveis com `housekeeping.tasks.execute` e o escopo `OWN`/`ALL` (D1), com trava da tarefa (D9), trilha e a resposta própria sem `notes` (D2). O `start` ainda não abre checklist (Task 2).

**Critérios de aceite:**
- `HousekeepingTaskDomainTest` (unidade): todas as transições válidas e inválidas da §8.4, inclusive `ASSIGNED → PENDING` (H19 a), `REJECTED → IN_PROGRESS` e `REFUSED → ASSIGNED`; a ordem dos instantes e `worked_seconds` com pausas (Review Focus 1 e 2). Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskDomainTest'`.
- `HousekeepingTaskActionsApiTest` (`@IntegrationTest`): a camareira atribuída faz `start`, `pause`, `resume`, `refuse` e `retry` com `If-Match`, recebendo a `HousekeepingTaskActionResponse` e o `ETag` novo; a resposta não contém `notes` nem o texto da `notes` de uma avulsa com texto (Review Focus 5); transição inválida dá `409 HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED`; `start` de tarefa de data futura dá `409 HOUSEKEEPING_TASK_NOT_DUE` (D11); cada ação gera a entrada de trilha. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskActionsApiTest'`.
- `HousekeepingTaskExecutionPermissionsTest` (`@IntegrationTest`): camareira com `OWN` sobre tarefa de outra dá `403` antes de `404`, `409` e `412`, e tarefa inexistente dá o mesmo corpo (`code` e `detail` iguais, D1); a Governanta com `ALL` age sobre qualquer tarefa; sem a permissão, `403`. O `HousekeepingPermissionsTest` existente passa a listar a permissão nova nos modelos da D1. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskExecutionPermissionsTest' --tests '*HousekeepingPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- Schema: colunas novas na tarefa (`started_at`, `finished_at`, `worked_seconds`, `in_progress_since`, `last_transition_at`, `cleaned_by`, `refusal_reason`) com CHECKs de coerência, sem apagar dados; o teste de schema da tarefa e o `DatabaseRolesTest` verdes. Contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*DatabaseRolesTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:** em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingTypes.kt` (`RefusalReason`), `HousekeepingPermissions.kt`, `domain/HousekeepingTask.kt`, `application/HousekeepingTaskExecution.kt` (novo), `application/HousekeepingTasksService.kt` (a interface `HousekeepingTaskStore` ganha a leitura com trava), `application/HousekeepingAudit.kt`, `infrastructure/HousekeepingTaskPersistence.kt`, `web/HousekeepingTaskActionsController.kt` (novo, com a `HousekeepingTaskActionResponse`); `src/main/resources/db/migration/housekeeping/V2026_11_19_…__add_task_execution.sql`; `api/openapi.yaml`. Testes: os citados.

**Tamanho:** ~60 min; 8 arquivos de produção, mais a migração e o contrato.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `HousekeepingTaskExecution` com `start`, `pause`, `resume`, `refuse(…, reason: RefusalReason, …)` e `retry`, cada uma `(taskId: HousekeepingTaskId, actor: …, at: Instant, expectedVersion: Long?)` (`expectedVersion` nulo no offline da E19), devolvendo a tarefa;
- `requireActsOn(task, actor)` (interna ao módulo), usada pela Task 2 e pela E19;
- `enum class RefusalReason { DO_NOT_DISTURB, GUEST_REFUSED }`;
- `HousekeepingTask.start/pause/resume/refuse/retry/complete(...)` no domínio;
- `HousekeepingTaskActionResponse` (web) e `HousekeepingTaskStore.findForUpdate(id)` (ou nome equivalente).

### Task 2: Conclusão com checklist, UH, observação, faxina programada, evento e o sinal de não perturbe

**Objetivo:** o `start` abre a execução do checklist com a versão publicada vigente (D5); `POST …/complete` (D4) conclui numa transação só, na ordem de trava D9: fecha a execução pela operação condicional (ou aceita a já concluída por fora), leva a UH Suja a Limpa pela operação condicional do `rooms` (D3), grava a observação de ocupação quando informada, atualiza a faxina programada (D10), grava quem limpou e os tempos e publica `HousekeepingTaskCompleted` (D7). A recusa por não perturbe passa a ligar o sinal pela operação condicional (D4).

**Critérios de aceite:**
- `HousekeepingTaskCompletionApiTest` (`@IntegrationTest`):
  - UH Suja, tipo sem inspeção: UH Limpa e tarefa `DONE`; com inspeção: UH Limpa com inspeção pendente e tarefa `AWAITING_INSPECTION`;
  - UH já Limpa ou Inspecionada e tarefa de área: a UH não muda, a tarefa vai a `DONE` e o checklist conclui (D3, Review Focus 3);
  - `start` com modelo abre a execução da versão publicada vigente e guarda o id; sem modelo, a tarefa segue sem checklist, e `answers` no `complete` dá `400` no campo `answers`;
  - item obrigatório faltando dá `422`, sem mudar tarefa, execução, UH, faxina, observação nem evento (Review Focus 4);
  - execução já concluída por fora: a tarefa conclui sem 500 e sem um segundo `ChecklistCompleted`;
  - tarefa `SCHEDULED` concluída atualiza `lastDoneOn` e `nextDueOn` da faxina; com a faxina desativada depois da geração, a tarefa conclui e a faxina não muda (D10);
  - `cleanedBy`, `startedAt`, `finishedAt` e a duração sem as pausas gravados;
  - `HousekeepingTaskCompleted` publicado uma vez (ouvinte de teste ou registro do Modulith), sem dado de hóspede;
  - `refuse` com `DO_NOT_DISTURB`: numa UH ocupada liga o sinal no `rooms`; numa UH vaga e numa tarefa de área responde `200` com a tarefa `REFUSED`, sem mudar a UH e sem 500.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskCompletionApiTest'`.
- `RoomsApiHousekeepingOperationsTest` (novo, no `rooms`): `markCleanIfDirty` muda só a UH Suja e devolve se mudou; `setServiceFlagIfOccupied` liga o sinal só na UH ocupada; `recordObservation(roomId, occupancy, guestCount?, observedBy, observedAt)` grava a observação como o endpoint do `rooms` grava, e uma ocupação divergente aparece nas discrepâncias (RF-UH-08). Verificação: `timeout 900 ./gradlew test --tests '*RoomsApiHousekeepingOperationsTest'`.
- `ChecklistsApiHousekeepingOperationsTest` (novo, no `checklists`): `currentVersion(templateId)` devolve a versão publicada vigente, e nulo para modelo sem versão publicada ou inativo; `completeExecutionIfOpen` conclui a execução aberta, devolve "já concluída" sem erro nem evento para a concluída, e mantém o `422` de item obrigatório. Verificação: `timeout 900 ./gradlew test --tests '*ChecklistsApiHousekeepingOperationsTest'`.
- `ScheduledCleaningDomainTest` (unidade, novo ou existente): `recordDone` atualiza `updatedAt`, não age sobre faxina inativa e recusa data fora do `StorableRange`. Verificação: `timeout 900 ./gradlew test --tests '*ScheduledCleaningDomainTest'`.
- Contrato regenerado; `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingEvents.kt` (novo, público), `domain/ScheduledCleaning.kt`, `application/HousekeepingTaskExecution.kt`, `web/HousekeepingTaskActionsController.kt`; em `rooms/`: `RoomsApi.kt` e `application/RoomsApiService.kt` (`markCleanIfDirty`, `setServiceFlagIfOccupied`, `recordObservation`); em `checklists/`: `ChecklistsApi.kt` e a implementação em `application/` (`currentVersion` em `ChecklistTemplates.kt`, `completeExecutionIfOpen` onde está o `completeExecution`); a migração da tarefa (`checklist_execution_id`), se não couber na da Task 1; `api/openapi.yaml`. Testes: os citados.

**Tamanho:** ~60 min; 9 arquivos de produção, mais o contrato.

**Depende de:** Task 1 (usa `HousekeepingTaskExecution`, `requireActsOn`, a leitura com trava e as colunas de tempo).

**Interfaces:** consome as da Task 1; produz `HousekeepingTaskExecution.complete(taskId, answers, foundOccupancy?, foundGuestCount?, actor, at, expectedVersion?)`, o evento público `HousekeepingTaskCompleted`, `RoomsApi.markCleanIfDirty(id, requiresInspection, source)`, `RoomsApi.setServiceFlagIfOccupied(id, flag, source)`, `RoomsApi.recordObservation(...)`, `ChecklistsApi.currentVersion(templateId)` e `ChecklistsApi.completeExecutionIfOpen(...)` (cada operação condicional devolve se mudou).

### Task 3: Tempo real, leitura da camareira, concorrência e a nota da publicação

**Objetivo:** cada transição publica no tópico `housekeeping` (D8); provar a D2 (a camareira não lê tarefas pela lista, pelo plano nem pelo tópico) e a D9 (ação e conclusão simultâneas, e conclusão concorrente com o `rooms`, se enfileiram sem deadlock); aplicar a D12 na publicação do plano.

**Critérios de aceite:**
- `HousekeepingTaskRealtimeTest` (`@IntegrationTest`, contexto `RANDOM_PORT`, como o `AssignmentPlanRealtimeTest`): um cliente inscrito no tópico `housekeeping` com `housekeeping.tasks.read` recebe uma mensagem por transição (`start`, `pause`, `resume`, `refuse`, `retry`, `complete`), com o id e a versão, sem `notes` nem dado de hóspede. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskRealtimeTest'`.
- `HousekeepingAttendantReadTest` (`@IntegrationTest`, usuário com o modelo Camareira): `GET` da lista do dia e `GET` do plano dão `403`; a inscrição no tópico `housekeeping` não entrega mensagem de tarefa nenhuma, inclusive das dela (Review Focus 5); a Governanta, com `ALL`, segue lendo. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingAttendantReadTest'`.
- `HousekeepingTaskLockTest` (`@IntegrationTest`, determinístico por `pg_blocking_pids`): (a) `pause` e `complete` simultâneos na mesma tarefa: um passa e o outro vê o estado novo (`409` de transição ou `412`), nunca `500` nem `409 CONCURRENT_UPDATE` por deadlock; (b) `complete` concorrente com a limpeza manual da mesma UH pelo `rooms`: sem deadlock, e a tarefa termina coerente com a UH (`DONE` se a UH já estava Limpa quando a conclusão a travou); (c) `complete` concorrente com a edição da faxina programada: sem deadlock e sem `412` na conclusão. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskLockTest'`.
- D12: em `AssignmentPlanPublishTest` (ou teste novo), um rascunho com uma tarefa `DONE` dada a outra camareira publica normalmente, não altera a tarefa e o item não aparece no plano publicado; o caso de tarefa `IN_PROGRESS` segue recusado como hoje. Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlan*'`.
- `timeout 900 ./gradlew checkOpenApi lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/housekeeping/HousekeepingRealtime.kt` (mensagem de tarefa), `application/HousekeepingTaskExecution.kt` (publicação no tópico e ajustes de trava, se os testes pedirem), `application/AssignmentPlansService.kt` e, se preciso, `domain/HousekeepingTask.kt` (D12; hoje a recusa vem do `requireNotStarted` do domínio da tarefa). Testes: os citados.

**Tamanho:** ~50 min; 2 a 4 arquivos de produção.

**Depende de:** Task 2 (os testes de concorrência e de tempo real usam a conclusão).

**Interfaces:** nenhuma nova.

# E02 do M6 — Geração automática de tarefas: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** gerar as tarefas de governança do dia (RF-GOV-02). As origens são:
- saídas (`GuestCheckedOut`);
- troca de UH (`RoomMoved`, na UH de origem);
- permanências, pré-chegadas e faxinas programadas vencidas, ao receber `BusinessDateAdvanced`;
- solicitações avulsas da recepção.

Cada tarefa leva os créditos e a prioridade do tipo de limpeza.

**Spec:**
- `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E02", com H4, H10 e H16;
- o handoff da E01 ("Notas para a próxima etapa");
- SPEC RF-GOV-02, §8.2.10 (`HousekeepingTask`, `TaskStatus`), §8.3 (eventos) e §8.4 ("Tarefa de governança");
- ARCHITECTURE §3.1, §7, §8, §11 e §13 (geração de tarefas depois de `BusinessDateAdvanced`).
- Notas do brain: `ZenyHotelManager - Módulo housekeeping`, `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo rooms` e `ZenyHotelManager - Testes de integração`.

**Architecture:**
- **Módulo e dependência:** a lógica fica no `housekeeping`, que passa a depender de `reservations`. A aresta já está na linha §3.1 do `housekeeping` e não é nova.
- **Eventos escutados:** `GuestCheckedOut`, `RoomMoved` e `BusinessDateAdvanced`, com `@ApplicationModuleListener` (outbox, transação própria).
- **Leituras:** os listeners leem `reservations` (chegadas, saídas e hospedados da data, e a UH alocada) e `rooms` (ocupação e status).
- **Persistência:** grava `HousekeepingTask` em `PENDING`. As transições de estado são da E03 e da E04.
- **Idempotência:** vem da restrição única por (local, data, tipo de limpeza), e reprocessar um evento não cria uma segunda tarefa.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (outbox, `Scenario`), Flyway 12, Testcontainers.

**Riscos:**
- **concorrência:** entrega repetida e fora de ordem dos eventos (check-out antes e depois do avanço da data) e duas origens para a mesma UH e data (Tasks 2 e 3);
- **dado pessoal ou segredo:** RN-13. A tarefa e as respostas nunca levam nome, documento nem contato do hóspede. As `notes` da tarefa avulsa são texto livre da recepção e nunca são preenchidas pelo servidor com dado do hóspede (Tasks 1 a 3);
- **autenticação ou autorização:** as permissões novas das tarefas (Task 1);
- **migração ou escrita destrutiva de dados:** tabela nova com restrição única, CHECKs e trigger (Task 1).

## Global Constraints

- **Grafo de módulos:** segue a ARCHITECTURE §3.1. O `housekeeping` passa a declarar `["shared", "hotel", "rooms", "reservations", "checklists"]`, com igualdade exata no `ModularityTest`. O `reservations` entra na Task 2. O `housekeeping` não depende de `endofday` nem de `frontdesk`.
- **Migrações:** ficam no schema `housekeeping`, com timestamp posterior a `V2026_11_17_1003`, e seguem o padrão `create_*` + `grant_*_to_app_role`. A API não tem `DELETE`. O banco recusa DELETE na tabela de tarefas.
- **H16:** referência inexistente no corpo dá `400 …UNKNOWN_…`. Referência que fere uma regra dá `422`.
- **Contrato:** é regenerado com `./gradlew updateOpenApi`, nunca editado à mão. O `TaskStatus` e a origem saem como `x-extensible-enum`. `.github/scripts/check-api-breaking.sh` sem `ERR`.
- **Permissões e auditoria:** a escrita exige permissão, com a autorização antes de qualquer checagem que revele estado, e é `@Audited` com retrato sem dado pessoal. A geração automática é do sistema: não tem ator nem permissão e não é auditada como ação de usuário.
- **Idioma:** código, API e banco em inglês; KDoc, comentários e commits em pt-BR (D17).
- **Commits:** por fatia, com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- **Execução:** um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- **Testes:**
  - O banco compartilhado (`@IntegrationTest`) **nunca** recebe um `BusinessDateAdvanced` publicado nem avança a data (H10). Lá, a lógica de geração por data é testada chamando o caso de uso direto, com uma data explícita.
  - O ponta a ponta com o avanço real usa `@EndOfDayIntegrationTest`, num banco isolado, e afirma só sobre os próprios dados.
  - Os testes deixam inativas as faxinas programadas que criam.
- **Verificação da fatia:** os testes focados que ela cita, mais `timeout 900 ./gradlew checkOpenApi lint`. A suíte inteira roda no gate do orquestrador.

## Review Focus

1. **Duas ordens de chegada dos eventos:** o check-out da data D pode chegar antes ou depois do `BusinessDateAdvanced` para D+1. Nas duas ordens, a UH tem uma tarefa de saída na data D. Não há permanência para ela em D+1 se ela ficou vaga, e não há tarefa duplicada. Teste na Task 3.
2. **Entrega repetida:** reprocessar o mesmo `GuestCheckedOut`, `RoomMoved` ou `BusinessDateAdvanced` não cria uma segunda tarefa nem falha o listener. O conflito de unicidade é absorvido, e o evento é dado como entregue. Testes nas Tasks 2 e 3.
3. **Permanência nunca para UH vaga** nem para a UH cuja saída é na própria data. Essa UH ganha a tarefa de saída pelo check-out. Teste na Task 3.
4. **Prioridade de chegada:** uma UH com chegada prevista na data fica acima de qualquer permanência, mesmo com as prioridades dos tipos editadas. A tarefa leva `arrivalExpected = true`, e a ordem da lista é (`arrivalExpected` desc, `priority` desc). Teste nas Tasks 2 e 3.
5. **Faxina programada vencida com tarefa aberta:** não se gera uma tarefa por dia enquanto houver uma aberta (`PENDING`) da mesma faxina. Também não se gera para faxina inativa, nem para local inativo ou inexistente. Teste na Task 3.

## Decisões desta etapa (do orquestrador, na escrita do plano; revisáveis)

- **D1-E02, tipo de limpeza por origem:** a geração usa o tipo pela `key` (`CleaningTypeStore.byKey`).

  | Origem | Tipo |
  |---|---|
  | saída | `CHECKOUT` |
  | troca de UH | `CHECKOUT` |
  | permanência | `STAYOVER` |
  | pré-chegada | `PRE_ARRIVAL` |
  | faxina programada | o tipo dela |
  | avulsa | escolhida no corpo |

  Se o tipo da `key` estiver inativo (por exemplo, o gestor desativou o `STAYOVER`), aquela origem não gera tarefa e o motivo vai para o log em `INFO`, sem dado de hóspede. O `TURNDOWN` vem inativo e não tem origem automática nesta etapa.
- **D2-E02, créditos:** a tarefa recebe o crédito do tipo para o tipo da UH. Sem crédito (D6 da E01), recebe 0. Tarefa de área recebe 0.
- **D3-E02, pré-chegada:** é gerada para uma UH **vaga** com chegada alocada na data que não teve nenhuma tarefa nos últimos `zeny.housekeeping.pre-arrival-after-days` dias. O padrão é `3`, configurável em `application.yaml`. A "vacância há N dias" é medida pelo histórico de tarefas do próprio módulo, sem ler o histórico de status do `rooms`.
- **D4-E02, data da tarefa:** é a `businessDate` do evento (saída e troca de UH) ou a `current` do `BusinessDateAdvanced`.
- **D5-E02, avulsa duplicada:** uma segunda avulsa no mesmo (local, data, tipo) dá `409 HOUSEKEEPING_TASK_ALREADY_EXISTS`.
- **D6-E02, `GuestCheckedIn` e `GuestCheckInUndone`:** a governança não os escuta. O desfazer devolve a UH vaga sem sujar. O KDoc deles em `reservations/ReservationsEvents.kt` deixa de dizer "escutado pela governança (M6)" (Task 2).
- **D7-E02, UH alocada:** a `ReservationsApi` ganha `assignedRooms(ids: Collection<ReservationId>): Map<ReservationId, RoomId>`. Ela devolve a alocação vigente de cada reserva, em lote, sem conferir permissão. O `ReservationSummary` não traz a UH. É só acréscimo à API pública do `reservations`, sem aresta nova.

---

### Task 1: Tarefa de governança, listagem e solicitação avulsa

**Objetivo:** criar a tarefa de governança (`HousekeepingTask`) com a tabela, o domínio no estado `PENDING`, a listagem do dia com filtros e a solicitação avulsa da recepção (`POST`), com permissões próprias. Nesta fatia ainda não há geração automática.

**Critérios de aceite:**
- **`HousekeepingTasksApiTest`** (`@IntegrationTest`):
  - `POST /api/v1/housekeeping-tasks`, com `roomId` (ou `areaId`), `cleaningTypeId`, `date` (padrão: a data operacional) e `notes` opcional, cria a tarefa `PENDING` com origem `AD_HOC`. Ela leva os créditos e a prioridade do tipo (D2) e `arrivalExpected` calculado. A resposta é `201`, com `Location` e `ETag`.
  - `GET /{id}` devolve a tarefa.
  - `GET /api/v1/housekeeping-tasks?date=…` lista por cursor, na ordem (`arrivalExpected` desc, `priority` desc, `id`), com filtros `roomId`, `status`, `cleaningTypeId` e `source`.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTasksApiTest'`.
- **Recusas** (`HousekeepingTasksAttackTest`):
  - UH, área ou tipo inexistente dá `400 …UNKNOWN_…` (H16).
  - UH ou área inativa dá `422`, e tipo inativo dá `422 CLEANING_TYPE_INACTIVE`.
  - Os dois locais, ou nenhum, dão `400`.
  - Uma segunda avulsa no mesmo (local, data, tipo) dá `409 HOUSEKEEPING_TASK_ALREADY_EXISTS` (D5).
  - `notes` com mais de 500 caracteres ou com NUL dá `400`.
  - A data antes da operacional dá `422`.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTasksAttackTest'`.
- **Permissões** (`HousekeepingTaskPermissionsTest`):
  - `housekeeping.tasks.read` e `housekeeping.tasks.request` existem, só de escopo `ALL`.
  - A leitura vai para os modelos Gerente, Governanta e Recepção.
  - A solicitação vai para Gerente, Governanta e Recepção, pelo `PermissionDefinition.templates` do módulo.
  - Sem a permissão, a resposta é `403`, antes de qualquer `400 UNKNOWN`/`404`/`409`/`422`.
  - O `POST` é auditado como `housekeeping.task.request`.
  - Os testes de grants dos modelos de papel que o testador mantém (`AuthorizationTest`) vão ao testador, se ficarem vermelhos.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- **RN-13** (`HousekeepingSchemaTest` ou um teste novo do schema): `housekeeping.housekeeping_task` não tem coluna de nome, documento, contato nem id de hóspede ou reserva. Ela tem `room_id`/`area_id`, `business_date`, `cleaning_type_id`, `source`, `status`, `priority`, `credits`, `arrival_expected`, `scheduled_cleaning_id`, `notes`, `created_by`, os carimbos e `version`. A restrição única é por (local, `business_date`, `cleaning_type_id`), com `NULLS NOT DISTINCT`. O banco recusa DELETE. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingSchemaTest' --tests '*DatabaseRolesTest'`.
- **Porta e contrato:**
  - `CleaningTypeStore.byKey(key)` devolve o tipo da carga pela `key`, com teste.
  - O contrato é regenerado: `status` e `source` saem como `x-extensible-enum`.
  - Verificação: `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingTypes.kt`: `HousekeepingTaskId`, `TaskStatus`, `TaskSource` e os códigos;
  - `HousekeepingPermissions.kt`;
  - `domain/HousekeepingTask.kt`;
  - `application/HousekeepingTasksService.kt`;
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `web/HousekeepingTasksController.kt`;
  - `infrastructure/CleaningTypePersistence.kt` (`byKey`).
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1000__create_housekeeping_tasks.sql` e `V2026_11_18_1001__grant_housekeeping_tasks_to_app_role.sql`.
- `api/openapi.yaml`.

Testes em `src/test/kotlin/com/zeny/hotel/housekeeping/`.

**Tamanho:** ~60 min; 11 arquivos de produção (2 SQL e o contrato gerado).

**Depende de:** nenhuma.

**Interfaces (produz):**
- `HousekeepingTaskId` (`TypedId`, UUIDv7).
- `enum class TaskStatus { PENDING, ASSIGNED, IN_PROGRESS, PAUSED, AWAITING_INSPECTION, REJECTED, DONE, REFUSED, CANCELLED }` (SPEC §8.2.10).
- `enum class TaskSource { CHECKOUT, ROOM_MOVE, STAYOVER, PRE_ARRIVAL, SCHEDULED, AD_HOC }`.
- A porta `HousekeepingTaskStore`, com:
  - `insertIfAbsent(task: HousekeepingTask): Boolean`, que devolve `false` quando a restrição única já tem a tarefa, sem lançar e sem abortar a transação (`INSERT … ON CONFLICT DO NOTHING`);
  - `hasOpenTaskFor(scheduledCleaningId: ScheduledCleaningId): Boolean`;
  - `lastTaskDate(roomId: RoomId): LocalDate?`.
- `CleaningTypeStore.byKey(key: CleaningTypeKey): CleaningType?`.
- Um construtor de tarefa no domínio que recebe o tipo, o local, a data, a origem, `arrivalExpected` e os créditos, e aplica D2.

### Task 2: Tarefas de saída e de troca de UH pelos eventos

**Objetivo:** os listeners de `GuestCheckedOut` e `RoomMoved` geram a tarefa `CHECKOUT` na UH que ficou vaga, na data do evento. A tarefa marca `arrivalExpected` quando a UH tem chegada alocada naquela data. A geração é idempotente. A `ReservationsApi` ganha a consulta em lote da UH alocada (D7), e o KDoc dos eventos de check-in é corrigido (D6).

**Critérios de aceite:**
- **`HousekeepingCheckoutTasksTest`** (`@IntegrationTest` com o `Scenario` do Spring Modulith, ou pela publicação real ao fazer check-out e troca de UH pelos casos de uso de `reservations`):
  - `GuestCheckedOut` gera uma tarefa `CHECKOUT`, origem `CHECKOUT`, na UH do evento e na `businessDate` dele;
  - `RoomMoved` gera uma `CHECKOUT`, origem `ROOM_MOVE`, na `fromRoomId`;
  - reentregar o mesmo evento não cria uma segunda tarefa, e o listener termina sem erro (Review Focus 2);
  - com chegada alocada à mesma UH naquela data, a tarefa sai com `arrivalExpected = true` (Review Focus 4).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingCheckoutTasksTest'`.
- **`ReservationsApi.assignedRooms(ids)`:** devolve a alocação vigente em lote. Um id sem alocação fica fora do mapa. Teste no `reservations`. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.reservations.*Assign*'`.
- **Tipo inativo (D1-E02):** com o `CHECKOUT` desativado, o evento não gera tarefa e não falha. O teste reativa o tipo num `finally`, porque os tipos da carga nunca ficam alterados.
- **KDoc:** o de `GuestCheckedIn` e `GuestCheckInUndone` não diz mais "escutado pela governança". Quem confere é a revisão.
- **Dependências do módulo:** o `ModularityTest` declara `["shared", "hotel", "rooms", "reservations", "checklists"]`, com igualdade exata. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingModule.kt`, `application/HousekeepingTaskGeneration.kt` (novo: o caso de uso de geração) e `application/HousekeepingTaskListeners.kt` (novo).
- `src/main/kotlin/com/zeny/hotel/reservations/ReservationsApi.kt` e a implementação em `reservations/application/`.
- `src/main/kotlin/com/zeny/hotel/reservations/ReservationsEvents.kt`, só o KDoc.

Testes no `housekeeping` e no `reservations`.

**Tamanho:** ~45 min; 6 arquivos de produção.

**Depende de:** Task 1.

**Interfaces:**
- **Consome:** `HousekeepingTaskStore.insertIfAbsent`, `CleaningTypeStore.byKey` e o construtor da tarefa (Task 1).
- **Produz:**
  - `ReservationsApi.assignedRooms(ids: Collection<ReservationId>): Map<ReservationId, RoomId>`;
  - `HousekeepingTaskGeneration`, com `generateCheckout(roomId: RoomId, date: LocalDate, source: TaskSource): Boolean` e uma função que diz se a UH tem chegada alocada na data (usada também pela Task 3).

### Task 3: Tarefas do dia ao avançar a data operacional

**Objetivo:** o listener de `BusinessDateAdvanced` gera, para a data `current`:
- **permanência** para as UHs ocupadas, exceto as com saída na própria data;
- **pré-chegada** (D3-E02);
- as tarefas das **faxinas programadas** vencidas, com `nextDueOn` ≤ `current`, ativas, de local ativo e sem tarefa aberta.

Todas saem com a prioridade de chegada (Review Focus 4). Também há um teste ponta a ponta com o fechamento real, num banco isolado.

**Critérios de aceite:**
- **`HousekeepingDailyTasksTest`** (`@IntegrationTest`), que chama `HousekeepingTaskGeneration.generateForDate(date)` direto, sem publicar `BusinessDateAdvanced` no banco compartilhado. A data é a operacional do banco de teste ou uma futura, e as afirmações são só sobre as UHs do próprio teste:
  - uma UH ocupada gera `STAYOVER`;
  - uma UH vaga não gera permanência;
  - uma UH ocupada com saída na data não gera permanência (Review Focus 3);
  - uma UH vaga com chegada alocada e sem tarefa nos últimos 3 dias gera `PRE_ARRIVAL`, com `arrivalExpected = true`; com uma tarefa há 2 dias, não gera;
  - uma UH ocupada com chegada na mesma data (troca de hóspede) fica acima da permanência comum, mesmo com o `STAYOVER` editado para prioridade 5 e restaurado no `finally` (Review Focus 4);
  - uma faxina vencida gera `SCHEDULED` com o tipo dela; rodar de novo, com a tarefa ainda `PENDING`, não gera outra (Review Focus 5); uma faxina inativa, ou com local inativo, não gera;
  - rodar `generateForDate` duas vezes para a mesma data não duplica nada (Review Focus 2).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingDailyTasksTest'`.
- **`HousekeepingEndOfDayTasksTest`** (`@EndOfDayIntegrationTest`, banco isolado, H10):
  - **Avanço real:** o fechamento real avança a data e publica `BusinessDateAdvanced`. Para uma UH hospedada do próprio teste, a `STAYOVER` da nova data é gerada.
  - **Duas ordens** (Review Focus 1): o check-out da data D antes do avanço, e o check-out com `businessDate` D entregue depois do avanço para D+1. Nas duas, a UH tem uma `CHECKOUT` em D, nenhuma `STAYOVER` em D+1 e nada duplicado.
  - **Limpeza:** cada teste termina com `EndOfDayTestRuns.settle()`.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingEndOfDayTasksTest'`.
- **Convivência do listener:** o listener de `BusinessDateAdvanced` convive com o das rotinas de checklist. A falha de um não impede o outro (cada um tem a sua publicação no outbox). A revisão confere isso, e a suíte do `checklists` segue verde. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*'`.
- **Propriedade:** `zeny.housekeeping.pre-arrival-after-days: 3` em `application.yaml`, validada no boot (1..30).
- **Verificação geral:** `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `application/HousekeepingTaskGeneration.kt`, `application/HousekeepingTaskListeners.kt`, `infrastructure/HousekeepingTaskPersistence.kt` (consultas) e `infrastructure/ScheduledCleaningPersistence.kt` (faxinas vencidas).
- Uma classe de propriedades do módulo.
- `src/main/resources/application.yaml`.

Testes no `housekeeping`.

**Tamanho:** ~60 min; 6 arquivos de produção.

**Depende de:** Task 2.

**Interfaces:**
- **Consome:** a `HousekeepingTaskGeneration` e o `assignedRooms` (Task 2); `ReservationsApi.inHouse(date)`, `pendingArrivals(date)` e `pendingDepartures(date)`; `RoomsApi.activeRooms()` e `status(id)` (`occupancy`); e `ScheduledCleaningStore`.
- **Produz:** `HousekeepingTaskGeneration.generateForDate(date: LocalDate): DailyGenerationResult`, uma contagem por origem para log e teste, e a consulta `ScheduledCleaningStore.dueOn(date: LocalDate): List<ScheduledCleaning>`.

# E02 do M6 — Geração automática de tarefas: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** gerar as tarefas de governança do dia (RF-GOV-02). As origens são:
- saídas (`GuestCheckedOut`);
- troca de UH (`RoomMoved`, na UH de origem);
- permanências, pré-chegadas e faxinas programadas vencidas (`BusinessDateAdvanced`);
- solicitações avulsas da recepção.

Cada tarefa leva os créditos e a prioridade do tipo de limpeza.

**Spec:**
- `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E02", com H4, H10 e H16;
- o handoff da E01 ("Notas para a próxima etapa");
- SPEC: RF-GOV-02; §8.2.10 (`HousekeepingTask`, `TaskStatus`); §8.3 (eventos); §8.4 ("Tarefa de governança");
- ARCHITECTURE: §3.1, §5 (auditoria), §7, §8, §11 e §13;
- brain: `ZenyHotelManager - Módulo housekeeping` (com a captura pendente `2026-11-18 … E01 (refação) - 3bd9134b`, que vale sobre ela), `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Eventos e jobs`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo rooms` e `ZenyHotelManager - Testes de integração`.

**Architecture:**
- **Onde:** no `housekeeping`, que passa a depender de `reservations`. A aresta já está na linha §3.1 e não é nova.
- **Listeners:** `@ApplicationModuleListener` (outbox, transação própria) para `GuestCheckedOut`, `RoomMoved` e `BusinessDateAdvanced`. O listener só delega a um caso de uso `@Audited` com o ator sistema, como faz o `ChecklistRoutineListener`.
- **Leituras:** `reservations` informa quem está hospedado, quem chega, quem sai e a UH alocada. `rooms` informa UH ativa, OOO/OOS e status de governança.
- **Gravação:** `HousekeepingTask` nasce em `PENDING`. As transições são da E03 e da E04.
- **Idempotência:** vem da restrição única por (local, data, tipo de limpeza), com `INSERT … ON CONFLICT DO NOTHING`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (outbox), Flyway 12, Testcontainers.

**Riscos:**
- **Concorrência:** entrega repetida e fora de ordem dos eventos (check-out antes e depois do avanço da data); duas origens para a mesma UH e data; evento de avanço atrasado (Tasks 2 e 3).
- **Dado pessoal ou segredo:** RN-13. Tarefa, respostas, trilha e log nunca levam nome, documento, contato nem id de hóspede ou de reserva. As `notes` da avulsa são texto livre da recepção, e o contrato pede que não levem dado do hóspede (Tasks 1 a 3).
- **Parsing de entrada externa não confiável:** corpo da avulsa (`notes`, `date`, ids) e cursor da lista com chaves novas (Task 1).
- **Autenticação ou autorização:** as permissões novas das tarefas (Task 1).
- **Migração ou escrita destrutiva de dados:** tabela nova com restrição única, CHECKs e trigger (Task 1).

## Global Constraints

- **Módulos:** o grafo segue a ARCHITECTURE §3.1. O `housekeeping` passa a declarar `["shared", "hotel", "rooms", "reservations", "checklists"]`, com igualdade exata no `ModularityTest`. O `reservations` entra na Task 2. O `housekeeping` não depende de `endofday` nem de `frontdesk`.
- **Migrações:** ficam no schema `housekeeping`, com timestamp posterior a `V2026_11_17_1003`, no padrão `create_*` + `grant_*_to_app_role`. A API não tem `DELETE`, e o banco recusa DELETE na tabela de tarefas.
- **H16:** referência inexistente no corpo dá `400 …UNKNOWN_…`; referência que fere regra dá `422`.
- **Contrato:** é regenerado com `./gradlew updateOpenApi`, nunca editado à mão. `status` e `source` saem como `x-extensible-enum`. `.github/scripts/check-api-breaking.sh` não pode acusar `ERR`.
- **Escrita da `application`:** todo método `@Transactional` de escrita é `@Audited` (regra ArchUnit `architecture/LayerRules.kt`).
  - A solicitação avulsa é auditada como `housekeeping.task.request`, com autorização antes de qualquer checagem que revele estado.
  - A geração automática é auditada como `housekeeping.task.generate`, com o ator sistema e retrato só de ids, local, tipo, origem e data (RN-13). Quando nada é criado, ela usa `audit.noChanges()`, como em `ChecklistRoutines.kt`.
- **Idioma:** código, API e banco em inglês; KDoc, comentários e commits em pt-BR (D17).
- **Commits:** por fatia, com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- **Comandos:** um Gradle por vez; nunca `./gradlew --stop`; todo comando com `timeout`; nunca laço de espera.
- **Testes:**
  - **Banco compartilhado:** o `@IntegrationTest` **recebe avanços de data de outros testes** (`BusinessDateTest`, `ChecklistRoutinesTest`, `StayTransitionsApiTest`), e a geração da Task 3 roda neles para o hotel inteiro. Por isso os testes do `housekeeping` afirmam só existência e conteúdo das tarefas nas UHs e áreas que o próprio teste criou, nunca totais por data nem as contagens do resultado da geração.
  - **Testes do `housekeeping`:** não publicam `BusinessDateAdvanced` nem avançam a data no banco compartilhado. A geração por data é testada chamando o caso de uso direto.
  - **Ponta a ponta com avanço real:** usa `@EndOfDayIntegrationTest` (banco isolado, H10) com `EndOfDayTestRuns.settle(context)`.
  - **Tipos da carga:** os testes nunca alteram os seis tipos da carga (convenção da E01). Comportamento que dependa de tipo inativo ou de prioridade editada é testado em teste de unidade da geração, com portas falsas.
  - **Faxinas:** os testes deixam inativas as faxinas programadas que criam.
  - **Espera do outbox:** se um teste espera publicações pendentes do outbox, a espera filtra o próprio listener e não passa a esperar os listeners novos.
- **Verificação da fatia:** os testes focados que ela cita mais `timeout 900 ./gradlew checkOpenApi lint`. A suíte inteira roda no gate.

## Review Focus

1. **Duas ordens de evento:** o check-out da data D é entregue antes ou depois do `BusinessDateAdvanced` para D+1. Nas duas ordens, e inclusive numa estadia de uma noite com chegada alocada em D+1, o resultado é o mesmo:
   - uma `CHECKOUT` em D;
   - nenhuma `STAYOVER` em D+1;
   - nenhuma `PRE_ARRIVAL` em D+1 (a UH ficou Suja no check-out, D3-E02);
   - nada duplicado.

   Teste na Task 3.
2. **Entrega repetida:** reprocessar o mesmo `GuestCheckedOut`, `RoomMoved` ou `BusinessDateAdvanced` não cria uma segunda tarefa nem falha o listener. Teste nas Tasks 2 e 3.
3. **Permanência:** nunca para UH vaga, nem para a UH cuja saída é na própria data. Teste na Task 3.
4. **Prioridade de chegada:** UH com chegada prevista na data fica acima de qualquer permanência, mesmo com as prioridades dos tipos editadas. A ordem da lista é (`arrivalExpected` desc, `priority` desc, `id`). Teste de unidade na Task 3; teste de integração nas Tasks 2 e 3.
5. **Faxina programada:** com tarefa aberta (qualquer status fora `DONE` e `CANCELLED`), não se gera outra. Também não se gera para faxina inativa, nem para local inativo, inexistente ou em OOO/OOS. Teste na Task 3.

## Decisões desta etapa

São do orquestrador, com a autorização do usuário de 2026-11-17 para escolher a opção recomendada durante a noite. Todas são revisáveis e vão às "Decisões para o usuário" do handoff.

- **D1-E02, tipo de limpeza por origem:** pela `key` (`CleaningTypeStore.byKey`).

  | Origem | Tipo |
  |---|---|
  | saída e troca de UH | `CHECKOUT` |
  | permanência | `STAYOVER` |
  | pré-chegada | `PRE_ARRIVAL` |
  | programada | o tipo dela |
  | avulsa | o do corpo |

  Tipo da `key` inativo: a origem não gera tarefa, com log `INFO` sem dado do hóspede. O `TURNDOWN` não tem origem automática.
- **D2-E02, créditos:** o crédito do tipo para o tipo da UH. Sem crédito, ou para área, 0.
- **D3-E02, pré-chegada:** gerada para uma UH que cumpre tudo isto:
  - ativa, fora de OOO/OOS;
  - **vaga** pela `reservations`, sem hospedado nela;
  - **não Suja** pela `rooms`;
  - com chegada alocada na data;
  - sem nenhuma tarefa, fora as `CANCELLED`, nos últimos `zeny.housekeeping.pre-arrival-after-days` dias (padrão `3`, validado de 1 a 30).

  A condição "não Suja" é gravada na transação do check-out e torna o resultado independente da ordem dos eventos (Review Focus 1).
- **D4-E02, data da tarefa:** a `businessDate` do evento (saída e troca de UH) ou a `current` do avanço. É a chave determinística da idempotência.
  - Consequência: uma `CHECKOUT` entregue depois do avanço fica na data anterior, e uma tarefa não feita fica aberta na data dela.
  - **A E03 precisa incluir na lista e na distribuição as tarefas abertas de datas anteriores** (nota no handoff). A alternativa descartada é mudar a data da tarefa para a data corrente, o que quebra a idempotência.
- **D5-E02, unicidade:** a avulsa duplicada no mesmo (local, data, tipo) dá `409 HOUSEKEEPING_TASK_ALREADY_EXISTS`.
  - A unicidade vale em qualquer status: uma segunda saída da mesma UH no mesmo dia não gera outra `CHECKOUT`, e uma avulsa dá `409` mesmo com a existente `CANCELLED`.
  - Para uma segunda limpeza no dia, usa-se uma avulsa de outro tipo, como `TOUCH_UP`.
  - A alternativa descartada é a unicidade só entre abertas, que perderia a idempotência depois de `DONE`.
- **D6-E02, eventos de reserva:** a governança **não** escuta `GuestCheckedIn`, `GuestCheckInUndone`, `ReservationCreated`, `ReservationChanged` nem `ReservationCancelled`.
  - O "plano do dia" sai do avanço da data, e a E03 recalcula a chegada prevista na sugestão.
  - O desfazer do check-in devolve a UH vaga sem sujar.
  - O KDoc desses eventos em `reservations/ReservationsEvents.kt` deixa de prometer a governança como ouvinte.
  - A SPEC §8.3 e a ARCHITECTURE §6.1 ("atualizar plano do dia") divergem disso e vão ao `arquiteto` (nota no handoff).
  - A alternativa descartada é escutar os eventos de reserva para manter as tarefas atualizadas.
- **D7-E02, UH alocada:** a `ReservationsApi` ganha `assignedRooms(ids: Collection<ReservationId>): Map<ReservationId, RoomId>`. Ela devolve a alocação vigente (`released_at IS NULL`, única por reserva por `room_assignment_active_uk`), em lote, sem conferir permissão. Motivos para não usar o `ReservationSummary.roomAssignment`:
  - uma consulta em lote, em vez de N;
  - minimização (RN-13): o `ReservationSummary` traz hóspedes e `specialRequests`;
  - menos tipos do `reservations` dentro do `housekeeping`.
- **D8-E02, `arrivalExpected`:** é um **retrato do momento da geração**. O KDoc e a descrição no contrato dizem isso. Alocação, cancelamento ou no-show posteriores não o atualizam, e a E03 recalcula a chegada prevista na sugestão (nota no handoff).
  - "Chegada prevista" é a reserva de `pendingArrivals(date)` com UH alocada: `CONFIRMED` com chegada até a data, o que inclui a atrasada e exclui a pré-reserva.
  - Alternativas descartadas: calcular na leitura ou escutar os eventos de reserva.
- **D9-E02, fonte da verdade:** a ocupação vem do `reservations`: hospedada é `inHouse(date)`, e sai na data quem está em `pendingDepartures(date)`. O `rooms` informa só UH ativa, OOO/OOS e status de governança. UH em OOO/OOS não recebe pré-chegada nem programada.
- **D10-E02, evento de avanço atrasado:** um `BusinessDateAdvanced` cuja `current` é anterior à `HotelApi.currentBusinessDate()` é ignorado, com log `WARN` que traz as duas datas. A guarda fica no **listener**, não em `generateForDate`, para que as chamadas diretas dos testes não dependam dela.
- **D11-E02, ordem dentro do dia:** `generateForDate` gera primeiro as programadas e depois as permanências e as pré-chegadas. Assim, a tarefa da faxina programada leva o `scheduledCleaningId`. Quando a programada colide com uma tarefa já existente do mesmo (local, data, tipo), ela não gera tarefa, e isso vai ao log `INFO`.
- **D12-E02, falha na geração:** estados esperados (tipo inativo, crédito ausente, UH inativa, inexistente ou em OOO) nunca lançam exceção. Erro inesperado desfaz a geração do dia inteiro, e o outbox a reenvia. Isso é aceitável para 30 UHs.
- **D13-E02, sincronização offline:** as colunas de mudança da ARCHITECTURE §9 (`change_seq` etc.) **não** entram nesta etapa. A E04, que leva as tarefas ao feed offline, as acrescenta (nota no handoff).

## Revisão de arquitetura

- **Veredito:** aprovado com notas na rodada 2 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueada por 12 Importantes, corrigidos em `16dc904`:
  - premissa H10 errada;
  - auditoria da geração;
  - pré-chegada dependente da ordem;
  - `arrivalExpected` velho;
  - D6 sem registro;
  - tarefa atrasada;
  - definição de "aberta";
  - justificativa da D7;
  - Task 1 dependente da 2;
  - testes alterando a carga;
  - `Riscos:` sem parsing;
  - critérios sem comando.
- **Rodada 1:** bloqueada por 2 Importantes (ordem B não produzida; D10 sem comando), corrigidos em `6ed4077`.
- **Nota Menor da rodada 2 (N1):** objetivo da Task 3 alinhado aos critérios, já aplicado neste texto.
- **Pontos de usuário (autorização noturna):** D1 a D13 tomadas pela opção recomendada. As que mais pesam na revisão humana são D3, D4, D5, D6, D8, D10 e D13.

---

### Task 1: Tarefa de governança, listagem e solicitação avulsa

**Objetivo:** criar a tarefa de governança (`HousekeepingTask`): a tabela, o domínio no estado `PENDING`, a listagem do dia com filtros e a solicitação avulsa da recepção (`POST`), com permissões próprias. A geração automática ainda não entra. Nesta fatia, a avulsa nasce com `arrivalExpected = false`, e a Task 2 passa a calcular a marca.

**Critérios de aceite:**
- **`HousekeepingTasksApiTest`** (`@IntegrationTest`):
  - `POST /api/v1/housekeeping-tasks` recebe `roomId` ou `areaId`, `cleaningTypeId`, `date` (padrão: a data operacional) e `notes` opcional. Cria a tarefa `PENDING` com origem `AD_HOC`, com os créditos e a prioridade do tipo (D2), e responde `201` com `Location` e `ETag`.
  - `GET /{id}` devolve a tarefa.
  - `GET /api/v1/housekeeping-tasks?date=…` lista por cursor na ordem (`arrivalExpected` desc, `priority` desc, `id`), com os filtros `roomId`, `areaId`, `status`, `cleaningTypeId` e `source`.
  - A paginação percorre as páginas sem repetir item.
  - O teste afirma só sobre as tarefas das UHs e áreas que criou.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTasksApiTest'`.
- **Recusas** (`HousekeepingTasksAttackTest`):
  - UH, área ou tipo inexistente → `400 …UNKNOWN_…` (H16);
  - UH ou área inativa → `422`; tipo inativo → `422 CLEANING_TYPE_INACTIVE`;
  - os dois locais, ou nenhum → `400`;
  - avulsa repetida no mesmo (local, data, tipo) → `409 HOUSEKEEPING_TASK_ALREADY_EXISTS` (D5);
  - `notes` com mais de 500 caracteres ou com NUL → `400`;
  - `date` antes da data operacional ou mais de 30 dias depois dela → `422 HOUSEKEEPING_TASK_DATE_INVALID`;
  - cursor com chave adulterada (booleano, prioridade ou id inválidos) → `400 INVALID_CURSOR`, nunca `500`.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTasksAttackTest'`.
- **Permissões** (`HousekeepingTaskPermissionsTest`):
  - `housekeeping.tasks.read` e `housekeeping.tasks.request`, só de escopo `ALL`, entram nos modelos Gerente, Governanta e Recepção pelo `PermissionDefinition.templates` do módulo.
  - Sem a permissão, a resposta é `403` antes de qualquer `400 UNKNOWN`, `404`, `409` ou `422`.
  - O `POST` é auditado como `housekeeping.task.request`, com retrato sem dado pessoal.
  - Os testes de grants que o testador mantém (`AuthorizationTest`) vão ao testador se ficarem vermelhos.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- **RN-13 e schema** (`HousekeepingSchemaTest`):
  - `housekeeping.housekeeping_task` tem só estas colunas: `room_id`/`area_id`, `business_date`, `cleaning_type_id`, `source`, `status`, `priority`, `credits`, `arrival_expected`, `scheduled_cleaning_id`, `notes`, `created_by`, os carimbos e `version`. Nenhuma de nome, documento, contato ou id de hóspede ou de reserva.
  - A unicidade é (local, `business_date`, `cleaning_type_id`), com `NULLS NOT DISTINCT`.
  - CHECKs de exatamente um local e de `status`/`source`.
  - O banco recusa DELETE (`NO_DELETE_TABLES`).
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingSchemaTest' --tests '*DatabaseRolesTest'`.
- **Porta `byKey` e contrato:**
  - `CleaningTypeStore.byKey(key)` devolve o tipo da carga, com teste em `CleaningTypesApiTest` ou num teste de persistência.
  - O contrato é regenerado: `status` e `source` como `x-extensible-enum`; a descrição de `arrivalExpected` diz "retrato do momento da geração"; a de `notes` pede que não se escreva dado do hóspede.
  - Verificação: `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingTypes.kt` (`HousekeepingTaskId`, `TaskStatus`, `TaskSource` e códigos);
  - `HousekeepingPermissions.kt`;
  - `domain/HousekeepingTask.kt`;
  - `application/HousekeepingTasksService.kt`;
  - `application/CleaningTypesService.kt` (a porta `CleaningTypeStore` ganha `byKey`);
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `infrastructure/CleaningTypePersistence.kt`;
  - `web/HousekeepingTasksController.kt`.
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1000__create_housekeeping_tasks.sql` e `V2026_11_18_1001__grant_housekeeping_tasks_to_app_role.sql`.
- `api/openapi.yaml`.

Testes em `src/test/kotlin/com/zeny/hotel/housekeeping/`.

**Tamanho:** ~60 min; 12 arquivos de produção, sendo 2 SQL e o contrato gerado.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `HousekeepingTaskId`, um `TypedId` com UUIDv7.
- `enum class TaskStatus { PENDING, ASSIGNED, IN_PROGRESS, PAUSED, AWAITING_INSPECTION, REJECTED, DONE, REFUSED, CANCELLED }`.
- `enum class TaskSource { CHECKOUT, ROOM_MOVE, STAYOVER, PRE_ARRIVAL, SCHEDULED, AD_HOC }`.
- Porta `HousekeepingTaskStore`, com:
  - `insertIfAbsent(task: HousekeepingTask): Boolean`, que devolve `false` quando a unicidade já tem a tarefa, sem lançar e sem abortar a transação (`ON CONFLICT DO NOTHING`);
  - `hasOpenTaskFor(scheduledCleaningId: ScheduledCleaningId): Boolean`, em que aberta é `status NOT IN ('DONE','CANCELLED')`, dito no KDoc;
  - `lastTaskDate(roomId: RoomId, onOrBefore: LocalDate): LocalDate?`, que ignora as `CANCELLED`.
- `CleaningTypeStore.byKey(key: CleaningTypeKey): CleaningType?`.
- O construtor da tarefa no domínio, que recebe o tipo, o local, a data, a origem, `arrivalExpected`, os créditos e o `scheduledCleaningId` opcional.

### Task 2: Tarefas de saída e de troca de UH pelos eventos, e a chegada prevista

**Objetivo:**
- Os listeners de `GuestCheckedOut` e `RoomMoved` geram a `CHECKOUT` na UH que ficou vaga, na data do evento, de forma idempotente e auditada como sistema.
- A marca `arrivalExpected` (D8) passa a ser calculada na geração e na avulsa.
- A `ReservationsApi` ganha `assignedRooms` (D7).
- O KDoc dos eventos de reserva é corrigido (D6).

**Critérios de aceite:**
- **`HousekeepingCheckoutTasksTest`** (`@IntegrationTest`), por check-out e troca de UH reais, pelos casos de uso do `reservations`/`frontdesk`, esperando o próprio listener:
  - `GuestCheckedOut` gera a `CHECKOUT` (origem `CHECKOUT`) na UH e na `businessDate` do evento;
  - `RoomMoved` gera a `CHECKOUT` (origem `ROOM_MOVE`) na `fromRoomId`;
  - com chegada alocada à UH na data, a tarefa sai com `arrivalExpected = true`;
  - a avulsa (Task 1) numa UH com chegada alocada também sai com `true`;
  - a trilha `housekeeping.task.generate` tem o ator sistema e nenhum dado pessoal.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingCheckoutTasksTest'`.
- **Entrega repetida** (Review Focus 2): chamar a geração da `CHECKOUT` duas vezes com o mesmo evento não cria uma segunda tarefa e não lança. Teste no `HousekeepingCheckoutTasksTest` chamando o caso de uso direto.
- **Tipo inativo** (D1-E02): `HousekeepingTaskGenerationTest`, teste de unidade com portas falsas. Com o `CHECKOUT` inativo, não há tarefa nem exceção, e o resultado vem como `noChanges`. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskGenerationTest'`.
- **`ReservationsApi.assignedRooms(ids)`:** devolve a alocação vigente em lote; id sem alocação ou com alocação liberada fica fora. O teste fica no `reservations`. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.reservations.*AssignedRooms*'`.
- **KDoc** (D6): `grep -n 'governança (M6' src/main/kotlin/com/zeny/hotel/reservations/ReservationsEvents.kt` só acha os KDocs de `GuestCheckedOut` e `RoomMoved`. Os de `ReservationCreated`, `ReservationCancelled`, `GuestCheckedIn` e `GuestCheckInUndone` deixam de citar a governança como ouvinte.
- **Módulos:** o `ModularityTest` passa a declarar `["shared", "hotel", "rooms", "reservations", "checklists"]`, em igualdade exata. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`: `HousekeepingModule.kt`, `application/HousekeepingTaskGeneration.kt` (novo, o caso de uso `@Audited`), `application/HousekeepingTaskListeners.kt` (novo) e `application/HousekeepingTasksService.kt` (marca na avulsa).
- `src/main/kotlin/com/zeny/hotel/reservations/ReservationsApi.kt`, a implementação em `reservations/application/InventoryService.kt` (que implementa a `ReservationsApi`), a porta `reservations/application/RoomAssignmentPorts.kt` e `reservations/infrastructure/JdbcRoomAssignmentStore.kt`.
- `src/main/kotlin/com/zeny/hotel/reservations/ReservationsEvents.kt` (só o KDoc).

Testes no `housekeeping` e no `reservations`.

**Tamanho:** ~50 min; 9 arquivos de produção.

**Depende de:** Task 1.

**Interfaces:**
- Consome: `HousekeepingTaskStore.insertIfAbsent`, `CleaningTypeStore.byKey` e o construtor da tarefa (Task 1).
- Produz:
  - `ReservationsApi.assignedRooms(ids: Collection<ReservationId>): Map<ReservationId, RoomId>`;
  - `HousekeepingTaskGeneration.generateCheckout(roomId: RoomId, date: LocalDate, source: TaskSource): Boolean`;
  - `HousekeepingTaskGeneration.arrivalsByRoom(date: LocalDate): Set<RoomId>`, as UHs com chegada prevista na data (D8), usado também pela Task 3.

### Task 3: Tarefas do dia ao avançar a data operacional

**Objetivo:** o listener de `BusinessDateAdvanced` gera, para a data `current`, nesta ordem (D11):
1. as tarefas das faxinas programadas vencidas;
2. as permanências (D9);
3. as pré-chegadas (D3).

Todas levam a marca de chegada (D8). O listener ignora o avanço atrasado (D10). Um teste ponta a ponta com o fechamento real, no banco isolado, cobre a ordem A; a ordem B fica no `HousekeepingDailyTasksTest`, por chamadas diretas.

**Critérios de aceite:**
- **`HousekeepingDailyTasksTest`** (`@IntegrationTest`), que chama `HousekeepingTaskGeneration.generateForDate(date)` direto, com a data operacional do banco ou uma futura, e afirma só sobre as UHs e áreas do próprio teste:
  - hospedada (`inHouse`) gera `STAYOVER`;
  - vaga não gera permanência;
  - hospedada com saída na data não gera permanência (Review Focus 3);
  - vaga, não Suja, com chegada alocada e sem tarefa nos últimos 3 dias gera `PRE_ARRIVAL` com `arrivalExpected = true`;
  - com tarefa há 2 dias, ou Suja, ou em OOO/OOS, não gera;
  - faxina vencida gera `SCHEDULED` com o tipo e o `scheduledCleaningId` dela;
  - rodar de novo com a tarefa aberta (inclusive com status `ASSIGNED` gravado direto no banco) não gera outra (Review Focus 5);
  - faxina inativa, ou com local inativo ou em OOO, não gera;
  - rodar `generateForDate` duas vezes não duplica nada (Review Focus 2);
  - **ordem B** (Review Focus 1), por chamadas diretas, reproduzindo o estado "check-out confirmado, listener ainda não rodou":
    - montagem: uma UH do teste sem hospedada, deixada Suja por `RoomsApi.markVacant(id, CHECK_OUT)` (o que o `CheckOutService` do `frontdesk` faz na transação do check-out), sem tarefa anterior e com chegada alocada na data;
    - `generateForDate(data)` não gera `PRE_ARRIVAL` nem `STAYOVER` para ela;
    - depois, `generateCheckout(id, data - 1, CHECKOUT)` gera uma `CHECKOUT` em `data - 1`;
  - **ordem A**, o caso espelhado: `generateCheckout` antes e `generateForDate` depois dão o mesmo resultado.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingDailyTasksTest'`.
- **Prioridade de chegada e tipo inativo**, com portas falsas no `HousekeepingTaskGenerationTest` (Review Focus 4 e D1-E02):
  - uma UH com chegada fica antes da permanência na ordem, mesmo com o `STAYOVER` de prioridade 5;
  - com o `STAYOVER` inativo, não sai permanência;
  - com o `PRE_ARRIVAL` inativo, não sai pré-chegada.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskGenerationTest'`.
- **Avanço atrasado** (D10): no `HousekeepingTaskGenerationTest`, o listener recebe um `BusinessDateAdvanced` com `current` anterior à data operacional da `HotelApi` falsa e não chama a geração. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskGenerationTest'`.
- **`HousekeepingEndOfDayTasksTest`** (`@EndOfDayIntegrationTest`, banco isolado, contexto de etapas reais como os testes do fechamento da E11, `EndOfDayTestRuns.settle(context)` no fim):
  - o fechamento real avança a data e gera a `STAYOVER` da nova data para uma hospedada do próprio teste;
  - **ordem A ponta a ponta** (Review Focus 1), com uma estadia de uma noite que sai em D e uma chegada alocada à mesma UH em D+1:
    - o check-out passa pelo caso de uso do `frontdesk` (`CheckOutService`), que deixa a UH Suja;
    - o listener da `CHECKOUT` roda;
    - o fechamento real avança para D+1;
    - resultado: uma `CHECKOUT` em D, nenhuma `STAYOVER` nem `PRE_ARRIVAL` em D+1, e nada duplicado.

    A ordem B fica no `HousekeepingDailyTasksTest`, por chamadas diretas.

  Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingEndOfDayTasksTest'`.
- **Convivência com o listener das rotinas de checklist:** cada listener tem a sua publicação no outbox, e a falha de um não impede o outro. Quem confere é a revisão, e a suíte do `checklists` segue verde. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.checklists.*'`.
- **Propriedade:** `zeny.housekeeping.pre-arrival-after-days: 3` no `application.yaml`, com validação 1..30. O `HousekeepingPropertiesTest` liga 0 e 31 e espera o contexto recusar, e liga 5 e espera aceitar. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingPropertiesTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `application/HousekeepingTaskGeneration.kt`;
  - `application/HousekeepingTaskListeners.kt`;
  - `application/ScheduledCleaningsService.kt` (a porta `ScheduledCleaningStore` ganha `dueOn`);
  - `infrastructure/ScheduledCleaningPersistence.kt`;
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - a classe de propriedades do módulo.
- `src/main/resources/application.yaml`.

Testes no `housekeeping`.

**Tamanho:** ~60 min; 7 arquivos de produção.

**Depende de:** Task 2.

**Interfaces:**
- Consome:
  - de `HousekeepingTaskGeneration` e da Task 2: `arrivalsByRoom` e `assignedRooms`;
  - de `ReservationsApi`: `inHouse(date)`, `pendingArrivals(date)` e `pendingDepartures(date)`;
  - de `RoomsApi`: `activeRooms()`, `status(id)` (`housekeepingStatus`, `outOfOrder`) e `area(id)`;
  - de `HotelApi`: `currentBusinessDate()`.
- Produz:
  - `HousekeepingTaskGeneration.generateForDate(date: LocalDate): DailyGenerationResult`, uma contagem por origem só para o log;
  - `ScheduledCleaningStore.dueOn(date: LocalDate): List<ScheduledCleaning>`, com as ativas de `nextDueOn` até a data.

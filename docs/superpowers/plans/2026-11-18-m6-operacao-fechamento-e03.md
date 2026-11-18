# E03 do M6 — Distribuição sugerida e publicação: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar a distribuição das tarefas de governança (RF-GOV-03, `AssignmentPlan`). Ela tem três partes:
- **sugestão determinística:** balanceia créditos entre as camareiras escaladas no dia (RF-PES-04), agrupa por andar, põe as chegadas primeiro e respeita o "Não perturbe";
- **ajuste manual** pela governanta;
- **publicação:** atribui as tarefas (`ASSIGNED`, com a camareira e a posição), avisa só quem teve mudança e publica um evento de tempo real para o painel.

**Spec:**
- `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E03", com H16 e **H17** (early check-in adiado, criada nesta etapa);
- o handoff da E02 ("Notas para a próxima etapa" e "Decisões para o usuário");
- SPEC: RF-GOV-03, RF-PES-04, RF-NOT-01, §8.2.10 e §8.4 ("Tarefa de governança");
- ARCHITECTURE: §3.1, §7, §8, §10 e §11;
- brain: `ZenyHotelManager - Módulo housekeeping` (as capturas pendentes `… E01 (refação) - 3bd9134b` e `… E02 - f7e6203c` valem sobre ela), `ZenyHotelManager - Módulos hotel e staff`, `ZenyHotelManager - Módulo notifications`, `ZenyHotelManager - Tempo real (SSE)` e `ZenyHotelManager - Módulo rooms`.

**Architecture:**
- **Dependências:** a lógica fica no `housekeeping`, que passa a depender de `staff` (escala) e de `notifications` (aviso). As duas arestas já estão na linha §3.1. O `notifications` não escuta eventos de negócio: quem avisa é o caso de uso de publicação.
- **Algoritmo:** fica no `domain` (função pura, com ids próprios do módulo), testável sem Spring.
- **Plano:** o plano do dia é o rascunho (`assignment_plan` com id UUID e data única, mais `assignment_plan_item`).
- **Publicação:** grava nas tarefas a camareira e a posição, calcula a diferença contra o que já estava atribuído, avisa e publica no tópico de tempo real `housekeeping`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** a permissão nova de distribuição; a atribuição só a quem está escalado; o tópico com permissão (Tasks 2 e 3);
- **concorrência:** dois `suggest` iniciais, ajuste e publicação concorrentes no mesmo plano, tarefa que anda durante a publicação, tarefas geradas depois da sugestão (Tasks 2 e 3);
- **dado pessoal ou segredo:** aviso, tempo real e trilha só com número de UH e nome de área, nunca dado de hóspede (Task 3);
- **parsing de entrada externa não confiável:** a data no caminho e os corpos do `suggest` e do ajuste (Task 2);
- **migração ou escrita destrutiva de dados:** tabelas novas e colunas novas na tarefa (Tasks 2 e 3).

## Global Constraints

- **Grafo de módulos:** segue a ARCHITECTURE §3.1. O `housekeeping` passa a declarar `"staff"` na Task 2 e `"notifications"` na Task 3, com igualdade exata no `ModularityTest` em cada fatia. A Task 1 não usa tipo de outro módulo.
- **Migrações:** ficam no schema `housekeeping`, com timestamp posterior a `V2026_11_18_1001`. O padrão é `create_*` + `grant_*_to_app_role`.
- **H16:** referência inexistente no corpo dá `400 …UNKNOWN_…`. A **exceção registrada** é a D11-E03.
- **Contrato:** é regenerado com `./gradlew updateOpenApi`. Enum que cresce sai como `x-extensible-enum`. `.github/scripts/check-api-breaking.sh` não acusa `ERR`.
- **Escrita da `application`:**
  - é `@Audited`, com retrato sem dado pessoal;
  - a autorização vem antes de qualquer checagem que revele estado;
  - toda escrita num plano existente exige `If-Match` (`428`/`412`), inclusive o `suggest`;
  - a versão é comparada **sob trava** da linha do plano (`SELECT … FOR UPDATE`);
  - falha otimista numa tarefa durante a publicação dá `412`, nunca `500`.
- **Idioma:** código, API e banco em inglês; KDoc, comentários, commits e texto do aviso em pt-BR (D17).
- **Commits:** por fatia, com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- **Execução:** um Gradle por vez, nunca `./gradlew --stop`, todo comando com `timeout`, nunca laço de espera.
- **Testes:**
  - O banco compartilhado recebe avanços de data e gera tarefas para o hotel inteiro. Os testes afirmam só sobre as UHs, os colaboradores e os planos do próprio teste. Nunca contam `audit.audit_entry`, tarefas ou notificações de forma global, e nunca alteram os tipos da carga.
  - A janela só tem 8 datas e os planos nunca são apagados. Por isso **os testes toleram plano e tarefas alheias**:
    - quando o plano da data já existe, fazem `GET` e mandam `If-Match`;
    - restringem as camareiras com `employeeIds` às do próprio teste;
    - afirmam só sobre as próprias tarefas e camareiras.
  - O plano da data operacional só é usado sem publicar.
  - Os casos que exigem uma data **sem plano** (o primeiro `suggest`, que cria o plano, e a corrida de criação) rodam no banco isolado do fechamento (`@EndOfDayIntegrationTest`, com `EndOfDayTestRuns.settle(context)`). Eles escolhem a primeira data da janela sem plano e falham com mensagem clara se não houver nenhuma.
- **Verificação da fatia:** os testes focados dela, mais `timeout 900 ./gradlew checkOpenApi lint`. A suíte inteira roda no gate.

## Review Focus

1. **Só quem está escalado:** a sugestão, o ajuste e a publicação só atribuem a quem está na escala do setor de governança na data e, se o `suggest` recebeu `employeeIds`, a esse subconjunto. A publicação reconfere a escala. Testes nas Tasks 2 e 3.
2. **Balanceamento contra andar (heurística, D5-E03):**
   - a diferença de créditos entre a mais e a menos carregada nunca passa do maior crédito de uma tarefa;
   - se a distribuição dos andares inteiros já cumpre esse limite, nenhum andar é dividido;
   - o algoritmo termina sempre, inclusive com créditos 0.

   Testes de unidade na Task 1.
3. **Republicação:** só recebe aviso quem teve tarefa incluída ou removida em relação ao que estava atribuído. Teste na Task 3.
4. **Tarefa que já andou:** uma tarefa em `IN_PROGRESS`, `PAUSED`, `AWAITING_INSPECTION`, `REJECTED`, `DONE`, `REFUSED` ou `CANCELLED`:
   - não entra na sugestão nem no ajuste (`422`);
   - na publicação, nunca é tocada.
     - Se o rascunho tem um item dela com a **mesma camareira**, o item é ignorado, inclusive a diferença de posição: a posição dela não é regravada.
     - Se o item a daria a **outra camareira**, a publicação inteira é recusada (`422`, com os ids em `errors[]`).
     - Se ela está fora do rascunho, não conta como retirada (D7-E03).

   Testes nas Tasks 2 e 3.
5. **Tarefa aberta de data anterior** (D4 da E02): entra no plano da data operacional, e só nele. Teste na Task 2.

## Decisões desta etapa

Decisões do orquestrador, sob a autorização noturna do usuário. São todas revisáveis. Onde houve alternativa, a escolhida foi a recomendada pela revisão de arquitetura.

- **D1-E03, quem recebe tarefas:**
  - são os colaboradores na escala da data do setor `DepartmentKey.HOUSEKEEPING` (`StaffApi.daySchedule`);
  - o `suggest` aceita `employeeIds` opcional, que precisa ser subconjunto dos escalados (`422` fora disso). O padrão é todos os escalados. É assim que a governanta e as supervisoras ficam de fora;
  - o setor de governança vem da carga do `staff`, que não pode ser apagado. Sem ele, é invariante quebrada (`IllegalStateException`), sem código no contrato; o teste é só de unidade, com `StaffApi` falsa;
  - o colaborador sem usuário vinculado recebe tarefas, mas não recebe aviso. A resposta da publicação lista os "não avisados" pelo id, com o motivo `NO_USER` ou `NOT_DELIVERED`. O `housekeeping` não sabe se um usuário está inativo; vale só o retorno do `notify`.
- **D2-E03, janela e conteúdo do plano:**
  - a data do plano vai da data operacional até 7 dias à frente. Fora disso, ou fora do `StorableRange`, dá `422 ASSIGNMENT_PLAN_DATE_INVALID` (data malformada dá `400`);
  - o plano de uma data que ficou para trás fica congelado: `suggest`, ajuste e publicação dão `422`;
  - o plano da **data operacional** leva as tarefas `PENDING` ou `ASSIGNED` com `business_date` até essa data, inclusive as de datas anteriores (D4 da E02);
  - um plano de **data futura** leva só as tarefas da própria data.
- **D3-E03, chegada e early:**
  - a chegada é recalculada na sugestão pelo `ArrivalsByRoom` (D8 da E02), com teste de que vale a recalculada, e não a gravada;
  - **early check-in fica adiado (H17).** O `reservations` não guarda pedido de early nem hora prevista de chegada. Por isso o critério da E03 "chegadas e early vêm primeiro" é cumprido só para as chegadas. O registro vai na tabela de hipóteses e na nota ao `arquiteto`, que decide a etapa que leva o dado ao `reservations`.
- **D4-E03, DND:** a `STAYOVER` de UH em `GuestServiceFlag.DO_NOT_DISTURB` no momento da sugestão fica de fora e segue `PENDING`. A governanta pode acrescentá-la no ajuste.
- **D5-E03, algoritmo** (heurística gulosa e determinística no `domain`; não é ótimo, como o roadmap aceita):
  1. **ordem:** as tarefas são ordenadas por chegada (desc), prioridade (desc), andar em ordem natural (números como números, depois texto, depois sem andar), número da UH ou nome da área, id;
  2. **andares inteiros:** os andares são distribuídos do maior total de créditos para o menor, cada um para a camareira menos carregada (desempate por nome e depois id);
  3. **rebalanceamento:** se a diferença entre a mais carregada (M) e a menos carregada (m) passar do maior crédito de uma tarefa, tarefas de crédito maior que 0 vão da mais para a menos carregada, a de menor crédito primeiro, até cumprir o limite. Como cada movimento usa c ≤ maior crédito < M − m, a soma dos quadrados das cargas cai a cada passo, e o laço termina;
  4. **ordem de execução:** a de cada camareira segue a do passo 1.

  Todas as áreas valem 0 crédito (D2 da E02). O grupo "sem andar" vai inteiro para uma camareira, sem custo, e isso é um efeito conhecido. O handoff registra o algoritmo final.
- **D6-E03, plano:**
  - **estrutura:** um plano por data (`assignment_plan`: `id` UUID, `business_date` único, status `DRAFT` ou `PUBLISHED`, `version`);
  - **`suggest`:** refaz o rascunho inteiro e exige `If-Match` quando o plano já existe. Depois de publicado, volta a `DRAFT`; as tarefas só mudam na próxima publicação;
  - **ajuste (`PATCH`):** move uma tarefa de camareira, acrescenta ao rascunho qualquer tarefa elegível pela D2 (inclusive as geradas depois da sugestão) e tira itens do rascunho (`remove`, diferente de "sem camareira");
  - **publicação:** grava nas tarefas e marca `PUBLISHED`.
  - **Alternativa descartada:** um `suggest` que preserve as atribuições vigentes. Fica como melhoria futura.
- **D7-E03, tarefa sem camareira na publicação:** volta a `PENDING` (`ASSIGNED → PENDING`) a tarefa publicada sem camareira e a tarefa **retirada**.
  - **Retirada**, só neste sentido: tarefa com status `ASSIGNED`, dentro do conteúdo do plano pela D2-E03 (no plano operacional, as da data e as anteriores; num plano futuro, só as da data) e ausente do rascunho.
  - Uma tarefa em qualquer outro status nunca é tocada pela publicação.
  - A SPEC §8.4 não desenha essa transição. Ela é necessária para a governanta recolher uma tarefa sem passá-la a outra pessoa, e o `arquiteto` registra a extensão na §8.4.
  - "Refused → Assigned" (nova tentativa) fica com a E04, porque `REFUSED` fica fora do plano.
- **D8-E03, aviso:**
  - tipo `NotificationsTypes.TASK_ASSIGNED`, uma notificação por camareira com mudança;
  - título "Suas tarefas de DD/MM mudaram";
  - texto com os números das UHs e os nomes das áreas incluídas e removidas, truncado em "e mais N" para caber no limite de 1000 caracteres do `notify`;
  - nenhum dado de hóspede.
- **D9-E03, tempo real:**
  - tópico novo `housekeeping`, com permissão `housekeeping.tasks.read`, que a E04 reaproveita para as mensagens das tarefas. A Task 3 o registra na ARCHITECTURE §10;
  - a mensagem tem o payload padrão do `shared` (`type = "housekeeping.assignment-plan"`, `id` do plano, `version`);
  - só a publicação publica no tópico; `suggest` e ajuste não publicam.
- **D10-E03, permissão:**
  - `housekeeping.assignments.manage`, só escopo `ALL`, não sensível, nos modelos Gerente e Governanta;
  - a leitura do plano exige `housekeeping.tasks.read`.
- **D11-E03, exceção à H16:** como a `StaffApi` não busca colaborador por id, um colaborador inexistente e um colaborador fora da escala dão o mesmo `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`. Tarefa inexistente no corpo dá `400 ASSIGNMENT_UNKNOWN_TASK`.
- **D12-E03, posição publicada:** a tarefa grava `assigned_employee_id` e `assignment_position` na publicação. Assim, o app da camareira (E04) mantém a ordem publicada mesmo depois de um novo `suggest`.
- **Adiados, com nota no handoff:** a lista `GET /housekeeping-tasks` com as tarefas abertas de datas anteriores e com a data no cursor fica para a E04, com o feed da camareira.

## Revisão de arquitetura

- **Veredito:** aprovado com notas na rodada 2 (`revisor-arquitetura`, modelo opus).
- **Rodada 0:** bloqueado por 12 Importantes, corrigidos em `e895acd`:
  - `ModularityTest` da Task 1;
  - garantia e término do algoritmo;
  - janela de datas;
  - fluxo do dia depois da publicação;
  - ordem publicada;
  - contrato do tempo real;
  - concorrência;
  - recusas inviáveis;
  - tamanho da Task 2;
  - limite do texto do aviso;
  - "camareira" incluindo a governanta;
  - early check-in.
- **Rodada 1:** bloqueado por 2 Importantes, corrigidos em `77b3c40`: datas dos testes e definição de "retirada".
- **Notas Menores da rodada 2:** cabeçalho do `AssignmentPlanPublishTest` e retorno do `PublishResult`. Já aplicadas neste texto.
- **Pontos de usuário (autorização noturna):** D1, D2, D3 (H17), D6, D7, D9 e D11, tomadas pela opção recomendada. D7 e D11 precisam de registro do `arquiteto`: D7 na SPEC §8.4 e D11 como exceção à H16.

---

### Task 1: Algoritmo da sugestão e dívida da E02

**Objetivo:**
- criar a função pura que, dadas as tarefas elegíveis, as camareiras, os andares e o DND, devolve a distribuição e a ordem de cada camareira (D4-E03 e D5-E03, Review Focus 2);
- pagar a dívida mecânica da E02:
  - extrair o `ArrivalsByRoom` e o `HousekeepingAudit`, este com a `@Configuration` dos beans do módulo, para arquivos próprios;
  - mudar o log da programada pulada por tipo ou local inválido para `INFO`.

**Critérios de aceite:**
- **`AssignmentSuggesterTest`** (unidade, sem Spring):
  - só as camareiras dadas recebem tarefas;
  - a diferença de créditos é ≤ o maior crédito de uma tarefa em pelo menos 8 casos: andar maior que a média, créditos 0 (inclusive todas as tarefas com 0), uma só camareira, mais camareiras que tarefas, 30 UHs em 3 andares, créditos iguais, o contraexemplo 5/4/3/3/3 com duas camareiras e andares "2" e "10" (ordem natural);
  - se a distribuição dos andares inteiros cumpre o limite, nenhum andar é dividido;
  - as chegadas vêm primeiro na ordem de cada camareira;
  - DND tira só a `STAYOVER` da UH;
  - a mesma entrada embaralhada gera a mesma saída;
  - sem camareira, tudo fica em não atribuídas;
  - o algoritmo termina em todos os casos: o teste usa `@Timeout`.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentSuggesterTest'`.
- **Extrações:**
  - `application/ArrivalsByRoom.kt` passa a ser usado pela geração e pela avulsa;
  - `application/HousekeepingAudit.kt` sai de `CleaningTypesService.kt`;
  - a `@Configuration` do módulo vai para `application/HousekeepingConfiguration.kt`.
  - O log `INFO` está aplicado.
  - As suítes do `housekeeping` seguem verdes, sem mudança de asserção.
  - Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.housekeeping.*' --tests 'com.zeny.hotel.architecture.*'` e `timeout 900 ./gradlew lint`.

**Áreas tocadas:** em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
- `domain/AssignmentSuggester.kt` (novo);
- `application/ArrivalsByRoom.kt` (novo);
- `application/HousekeepingAudit.kt` (novo);
- `application/HousekeepingConfiguration.kt` (novo);
- `application/HousekeepingTaskGeneration.kt`;
- `application/HousekeepingTasksService.kt`;
- `application/CleaningTypesService.kt`.

Os testes ficam em `src/test/kotlin/com/zeny/hotel/housekeeping/`.

**Tamanho:** ~50 min; 7 arquivos de produção (3 deles extrações mecânicas).

**Depende de:** nenhuma.

**Interfaces (produz):**
- `AttendantId`, um id próprio do `domain` (`value: UUID`), que a `application` converte de e para o `EmployeeId` do `staff`;
- `data class SuggestionTask(val taskId: HousekeepingTaskId, val roomNumber: String?, val areaName: String?, val floor: String?, val credits: Int, val priority: Int, val arrivalExpected: Boolean, val source: TaskSource, val doNotDisturb: Boolean)`;
- `data class Attendant(val id: AttendantId, val name: String)`;
- `object AssignmentSuggester { fun suggest(tasks: List<SuggestionTask>, attendants: List<Attendant>): Suggestion }`;
- `data class Suggestion(val byAttendant: Map<AttendantId, List<HousekeepingTaskId>>, val unassigned: List<HousekeepingTaskId>)`, com cada lista na ordem de execução;
- o componente `ArrivalsByRoom`, com `arrivalsByRoom(date: LocalDate): Set<RoomId>` e a mesma regra da E02.

### Task 2: Plano do dia, sugestão e ajuste

**Objetivo:** persistir o `AssignmentPlan` e expor três rotas:
- `POST /api/v1/assignment-plans/{date}/suggest` (com `employeeIds` opcional e `If-Match` quando o plano existe);
- `GET /api/v1/assignment-plans/{date}`;
- `PATCH /api/v1/assignment-plans/{date}` (mover, acrescentar e remover itens, com `If-Match`).

Valem a janela de datas (D2-E03), as camareiras pela escala (D1-E03), o DND e a chegada recalculada.

**Critérios de aceite:**
- **`AssignmentPlansApiTest`** (`@IntegrationTest`):
  - `suggest` em data futura da janela, com as avulsas do próprio teste, dá `200` com o plano `DRAFT`, o `id`, a versão no `ETag`, os itens (tarefa, camareira, posição) e as não atribuídas;
  - só aparecem as camareiras escaladas pelo teste no setor de governança;
  - `employeeIds` restringe a sugestão a um subconjunto;
  - uma `STAYOVER` de UH em DND fica em não atribuídas;
  - uma tarefa `IN_PROGRESS` gravada direto no banco não entra (Review Focus 4);
  - `GET` devolve o mesmo plano;
  - `PATCH` com `If-Match` move uma tarefa, acrescenta uma avulsa criada depois da sugestão e remove um item;
  - um novo `suggest` sobre o plano existente exige `If-Match`;
  - **data operacional, sem publicar:** uma tarefa `PENDING` do próprio teste de data anterior entra no plano da data operacional e não entra no plano de amanhã (Review Focus 5). As afirmações ficam só sobre as próprias tarefas.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlansApiTest'`.
- **Chegada recalculada:** uma tarefa gravada com `arrivalExpected = false` numa UH que agora tem chegada alocada vem primeiro na ordem da camareira. Teste em `AssignmentPlansApiTest`, numa data futura.
- **Recusas** (`AssignmentPlansAttackTest`):
  - plano existente: sem `If-Match` → `428`; versão velha → `412`;
  - colaborador fora da escala ou inexistente → `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED` (D11-E03);
  - `employeeIds` fora da escala → `422`;
  - tarefa que já andou → `422 ASSIGNMENT_TASK_NOT_ASSIGNABLE`;
  - tarefa inexistente → `400 ASSIGNMENT_UNKNOWN_TASK`;
  - tarefa fora do conteúdo da D2 → `422 ASSIGNMENT_TASK_NOT_ASSIGNABLE`;
  - data antes da operacional ou depois de +7 → `422 ASSIGNMENT_PLAN_DATE_INVALID`;
  - data malformada → `400`, nunca `500`;
  - `PATCH` antes de existir plano → `404`.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlansAttackTest'`.
- **Concorrência** (`AssignmentPlanConcurrencyTest`):
  - dois primeiros `suggest` simultâneos da mesma data: um cria o plano e o outro recebe `428` ou `412`, nunca `500`. A criação usa `INSERT … ON CONFLICT DO NOTHING` + `SELECT … FOR UPDATE`;
  - dois `PATCH` com o mesmo `If-Match`: um passa e o outro dá `412`.
  - O teste é determinístico (latch ou `pg_blocking_pids`, sem `sleep` cego).
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanConcurrencyTest'`.
- **Permissões** (`AssignmentPlanPermissionsTest`):
  - `housekeeping.assignments.manage` (D10-E03) entra nos modelos Gerente e Governanta;
  - sem ela, `suggest` e `PATCH` dão `403` antes de qualquer recusa de estado;
  - `GET` exige `housekeeping.tasks.read`;
  - a trilha registra `housekeeping.assignment-plan.suggest` e `housekeeping.assignment-plan.adjust`;
  - o `AuthorizationTest` do testador vai ao testador se ficar vermelho.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- **Setor ausente** (D1-E03): um teste de unidade do serviço com `StaffApi` falsa confere a `IllegalStateException`.
- **Schema** (`AssignmentPlanSchemaTest`):
  - `assignment_plan` com `id`, `business_date` único, `status`, `version`, carimbos, `published_at` e `published_by`;
  - `assignment_plan_item` com `plan_id`, `task_id` único por plano, `employee_id` nulável e `position`;
  - FKs nas duas tabelas;
  - o banco recusa DELETE em `assignment_plan`, e o item aceita DELETE;
  - `DatabaseRolesTest` atualizado.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanSchemaTest' --tests '*DatabaseRolesTest'`.
- **Módulo:** `ModularityTest` com `"staff"` acrescentado, em igualdade exata, e o contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingModule.kt`;
  - `HousekeepingPermissions.kt`;
  - `HousekeepingTypes.kt`;
  - `domain/AssignmentPlan.kt`;
  - `application/AssignmentPlansService.kt`;
  - `application/HousekeepingTasksService.kt` (a porta ganha a consulta das elegíveis);
  - `infrastructure/AssignmentPlanPersistence.kt`;
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `web/AssignmentPlansController.kt`.
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1100__create_assignment_plans.sql` e `V2026_11_18_1101__grant_assignment_plans_to_app_role.sql`.
- `api/openapi.yaml`.

**Tamanho:** ~60 min; 12 arquivos de produção, sendo 2 SQL e o contrato gerado.

**Depende de:** Task 1.

**Interfaces:**
- Consome: `AssignmentSuggester`, `AttendantId` e `ArrivalsByRoom` (Task 1); `StaffApi.departments()` e `daySchedule`; `RoomsApi.activeRooms()` (andar e número, em lote), `area(id)` e `status(id)` (`serviceFlag`).
- Produz:
  - `HousekeepingTaskStore.assignableUpTo(date: LocalDate, includePrevious: Boolean): List<HousekeepingTask>`;
  - a porta `AssignmentPlanStore` (`createIfAbsent`, `lockByDate`);
  - `AssignmentPlansService.suggest(date, expectedVersion?, employeeIds?)`, `get(date)` e `adjust(date, expectedVersion, changes)`;
  - o `AssignmentPlanView` (`id`, data, status, versão, itens com `taskId`, `employeeId` e `position`, e as não atribuídas);
  - uma função interna `scheduledAttendants(date): List<Attendant>`, que a Task 3 reusa.

### Task 3: Publicação, republicação, aviso e tempo real

**Objetivo:** `POST /api/v1/assignment-plans/{date}/publish` com `If-Match`. A publicação, sob trava do plano:
- reconfere a escala;
- grava nas tarefas `assigned_employee_id`, `assignment_position` e `ASSIGNED`, ou `PENDING` para as que ficaram sem camareira ou foram retiradas (D7-E03);
- ignora o item de tarefa que andou quando ele não mudaria a tarefa;
- marca o plano `PUBLISHED`;
- avisa só as camareiras com tarefa incluída ou removida (D8-E03);
- publica no tópico `housekeeping` (D9-E03).

**Critérios de aceite:**
- **`AssignmentPlanPublishTest`** (`@IntegrationTest`, data futura da janela, com tolerância a plano e tarefas alheias, `employeeIds` só das próprias camareiras e afirmações só sobre o que é do teste, conforme as Global Constraints):
  - **primeira publicação:** as tarefas ficam `ASSIGNED`, com camareira e posição, e cada camareira com usuário ativo recebe uma `notifications.task-assigned`, conferida no painel dela;
  - **republicação sem mudança:** ninguém é avisado;
  - **tarefa movida de A para B:** só A e B são avisadas, e C não (Review Focus 3);
  - **tarefa retirada:** volta a `PENDING` sem camareira;
  - **sem aviso:** o colaborador sem usuário vinculado, ou cujo `notify` não produziu notificação (painel desligado nas preferências, ou usuário que não recebe), aparece na resposta em "não avisados", com o id e o motivo `NO_USER` ou `NOT_DELIVERED`;
  - **tarefa que andou fora do rascunho:** uma republicação em que uma tarefa `IN_PROGRESS` (gravada no banco) não está no rascunho passa e não altera essa tarefa (R2);
  - **tarefa que andou, mudando só de posição:** uma tarefa `IN_PROGRESS` cujo item tem a mesma camareira e outra posição não impede a publicação, e a posição dela não é regravada;
  - **tarefa `ASSIGNED` de outra data, fora do conteúdo do plano:** não é retirada;
  - **texto do aviso:** o título leva a data, e o texto só tem números de UH e nomes de área, sem o nome do hóspede de uma reserva da UH;
  - **muitas áreas:** o texto truncado com "e mais N" não estoura o limite;
  - **ordem:** depois de um novo `suggest`, as tarefas mantêm a posição publicada;
  - **trilha:** `housekeeping.assignment-plan.publish`, com a camareira antes e depois de cada tarefa alterada, só por ids.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanPublishTest'`.
- **Escala e tarefa que andou** (Review Focus 1 e 4):
  - uma camareira que sai da escala antes da publicação faz a publicação dar `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`, sem nada gravado;
  - uma tarefa `IN_PROGRESS` com o mesmo item publicado não impede a publicação e não é alterada;
  - uma tarefa `IN_PROGRESS` cujo item a moveria dá `422 ASSIGNMENT_TASK_NOT_ASSIGNABLE`, com o id em `errors[]` e nada gravado.
- **Concorrência:**
  - duas publicações com o mesmo `If-Match` em paralelo: uma passa e a outra dá `412`;
  - uma falha otimista numa tarefa dá `412`;
  - o teste é determinístico.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanPublishTest' --tests '*AssignmentPlanConcurrencyTest'`.
- **Tempo real** (`AssignmentPlanRealtimeTest`, contexto `RANDOM_PORT` próprio, como o `RealtimeStreamTest`):
  - quem tem `housekeeping.tasks.read` recebe `housekeeping.assignment-plan`, com o `id` do plano e a versão;
  - a inscrição no tópico sem a permissão dá `403 PERMISSION_DENIED`;
  - `suggest` e `PATCH` não publicam.
  - O tópico é registrado como bean na `HousekeepingConfiguration`, e a ARCHITECTURE §10 o lista.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanRealtimeTest'`.
- **Schema e lista:**
  - `housekeeping_task` ganha `assigned_employee_id` e `assignment_position`, nulos em `PENDING`, com CHECK de coerência;
  - a lista e o `GET` da tarefa expõem `assignedEmployeeId` e `assignmentPosition`.
  - Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*HousekeepingTasksApiTest'`.
- **Módulo:** `ModularityTest` com `"notifications"` acrescentado, em igualdade exata, e o contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingModule.kt`;
  - `HousekeepingTypes.kt`;
  - `HousekeepingRealtime.kt` (novo);
  - `domain/HousekeepingTask.kt` (`assign`, `unassign`);
  - `application/AssignmentPlansService.kt` (`publish`);
  - `application/HousekeepingConfiguration.kt` (bean do tópico);
  - `application/HousekeepingTasksService.kt` (view e retrato da tarefa);
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `web/AssignmentPlansController.kt`.
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1102__add_task_assignee.sql`.
- `src/main/kotlin/com/zeny/hotel/housekeeping/web/HousekeepingTasksController.kt` (DTO da tarefa com os campos novos).
- `docs/ARCHITECTURE.md` (§10, tópico `housekeeping`).
- `api/openapi.yaml`.

**Tamanho:** ~60 min; 11 arquivos de produção mais 1 doc e o contrato gerado.

**Depende de:** Task 2.

**Interfaces:**
- Consome: `AssignmentPlansService` e `scheduledAttendants` (Task 2), `NotificationsApi.notify`, `NotificationsTypes.TASK_ASSIGNED` e `RealtimePublisher`.
- Produz:
  - `HousekeepingTask.assign(employeeId, position)` e `unassign()`, só a partir de `PENDING` ou `ASSIGNED`;
  - `AssignmentPlansService.publish(date, expectedVersion): PublishResult`, com a versão, a contagem de avisados e os não avisados (id e motivo `NO_USER` ou `NOT_DELIVERED`);
  - `HousekeepingRealtime.TOPIC`.

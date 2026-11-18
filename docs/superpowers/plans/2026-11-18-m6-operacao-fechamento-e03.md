# E03 do M6 — Distribuição sugerida e publicação: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** implementar a distribuição das tarefas de governança (RF-GOV-03, `AssignmentPlan`):
- uma sugestão determinística que balanceia créditos entre as camareiras escaladas no dia (RF-PES-04), agrupa por andar, põe as chegadas primeiro e respeita o "Não perturbe";
- o ajuste manual pela governanta;
- a publicação, que atribui as tarefas (`ASSIGNED`) e avisa só quem teve mudança, com evento de tempo real para o painel.

**Spec:**
- `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E03";
- o handoff da E02, em "Notas para a próxima etapa" e "Decisões para o usuário";
- SPEC: RF-GOV-03, RF-PES-04, RF-NOT-01, §8.2.10 (`AssignmentPlan`, `HousekeepingTask`) e §8.4 ("Tarefa de governança");
- ARCHITECTURE: §3.1, §7, §8, §10 (tempo real) e §11;
- brain: `ZenyHotelManager - Módulo housekeeping`, com as capturas pendentes `… E01 (refação) - 3bd9134b` e a da E02, que valem sobre ela; `ZenyHotelManager - Módulos hotel e staff` (escala); `ZenyHotelManager - Módulo notifications`; `ZenyHotelManager - Tempo real (SSE)`; `ZenyHotelManager - Módulo rooms`.

**Architecture:**
- **Onde:** no `housekeeping`, que passa a depender de `staff` (escala) e `notifications` (aviso). As duas arestas já estão na linha §3.1. O `notifications` não escuta eventos de negócio: quem avisa é o caso de uso de publicação.
- **Algoritmo:** fica no `domain`, como função pura e determinística, testável sem Spring.
- **Rascunho:** o plano do dia (`assignment_plan` + `assignment_plan_item`) é o rascunho da distribuição.
- **Publicação:** copia as atribuições para as tarefas (`assigned_employee_id`, `ASSIGNED`), calcula a diferença contra a última publicação, avisa e publica no tempo real.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway 12, Testcontainers.

**Riscos:**
- **autenticação ou autorização:** a permissão nova de distribuição e a recusa de atribuir a quem não está escalado (Tasks 2 e 3);
- **concorrência:** ajuste e publicação concorrentes no mesmo plano, e tarefas geradas depois da sugestão (Tasks 2 e 3);
- **dado pessoal ou segredo:** o título e o texto do aviso e o evento de tempo real só levam número de UH, nunca dado de hóspede (Task 3);
- **parsing de entrada externa não confiável:** a data no caminho e o corpo do ajuste (Task 2);
- **migração ou escrita destrutiva de dados:** tabelas novas e coluna nova na tarefa (Tasks 2 e 3).

## Global Constraints

- **Grafo e dependências:** segue a ARCHITECTURE §3.1. O `housekeeping` passa a declarar `["shared", "hotel", "rooms", "reservations", "staff", "checklists"]` na Task 2 e acrescenta `"notifications"` na Task 3. Cada fatia confere com igualdade exata no `ModularityTest`.
- **Migrações:** ficam no schema `housekeeping`, com timestamp posterior a `V2026_11_18_1001`, no padrão `create_*` + `grant_*_to_app_role`.
- **Recusas:** H16 se aplica. Referência inexistente no corpo dá `400 …UNKNOWN_…`. Referência que fere regra dá `422`.
- **Contrato:** regenerado com `./gradlew updateOpenApi`. Enum que cresce é `x-extensible-enum`. `.github/scripts/check-api-breaking.sh` sem `ERR`. O status do plano sai como `x-extensible-enum`.
- **Escritas:**
  - toda escrita da `application` é `@Audited`, com retrato sem dado pessoal;
  - a autorização vem antes de qualquer checagem que revele estado;
  - as edições do plano usam `ETag`/`If-Match` (`428`/`412`).
- **Idioma:** código, API e banco em inglês; KDoc, comentários e commits em pt-BR (D17). O texto do aviso é pt-BR.
- **Commits:** por fatia, com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- **Execução:** um Gradle por vez, nunca `./gradlew --stop`, todo comando com `timeout`, nunca laço de espera.
- **Testes:**
  - o banco compartilhado recebe avanços de data de outros testes e gera tarefas para o hotel inteiro. Os testes de integração afirmam só sobre as UHs, os colaboradores e os planos do próprio teste. Os planos usam uma data futura própria (por exemplo, a data operacional + 20 a 29 dias, distinta por teste), para não colidir com o plano do dia de outros testes;
  - nunca contar `audit.audit_entry` nem tarefas de forma global;
  - os tipos da carga nunca são alterados.
- **Verificação da fatia:** os testes focados dela mais `timeout 900 ./gradlew checkOpenApi lint`. A suíte inteira roda no gate.

## Review Focus

1. **Só quem está escalado:** a sugestão e o ajuste só atribuem a quem está na escala do setor de governança naquela data (`StaffApi.daySchedule`, setor com `DepartmentKey.HOUSEKEEPING`). O ajuste para outro colaborador dá `422`. Também não se atribui a quem saiu da escala entre a sugestão e a publicação: a publicação reconfere a escala. Testes nas Tasks 1, 2 e 3.
2. **Balanceamento contra agrupamento por andar:** a diferença de créditos entre a mais e a menos carregada nunca passa do maior crédito de uma tarefa. O andar fica com uma só camareira **sempre que isso não quebrar o limite**. Testes de unidade na Task 1 com casos difíceis: um andar maior que a média, créditos 0, uma só camareira e mais camareiras que tarefas.
3. **Republicação:** só recebe aviso quem teve tarefa incluída ou removida. Quem não mudou não é avisado, nem quando outra camareira mudou. Teste na Task 3.
4. **Tarefa que já andou:** uma tarefa em `IN_PROGRESS`, `PAUSED`, `AWAITING_INSPECTION`, `REJECTED`, `DONE`, `REFUSED` ou `CANCELLED` não entra na sugestão nem pode ser movida pelo ajuste (`422`). A publicação não a altera. Teste na Task 2.
5. **Tarefa aberta de data anterior** (D4 da E02): uma tarefa `PENDING` ou `ASSIGNED` de uma data anterior entra no plano da data. Assim, uma `CHECKOUT` entregue depois do avanço não fica órfã. Teste na Task 2.

## Decisões desta etapa

Decisões do orquestrador, sob a autorização noturna do usuário. Todas são revisáveis.

- **D1-E03, quem é camareira:** é colaborador ativo na escala da data (`daySchedule(date, departmentId)`) do setor `DepartmentKey.HOUSEKEEPING`.
  - Sem esse setor cadastrado, a sugestão dá `422 HOUSEKEEPING_DEPARTMENT_MISSING`.
  - Sem ninguém escalado, a sugestão fica vazia, com todas as tarefas sem camareira.
  - Um colaborador sem usuário vinculado recebe tarefas, mas não recebe aviso. A resposta da publicação lista esse caso, sem nome.
- **D2-E03, o que entra no plano:** tarefas `PENDING` ou `ASSIGNED` com `business_date` até a data do plano, inclusive as de datas anteriores (D4 da E02). As tarefas em outros status ficam fora (Review Focus 4).
- **D3-E03, chegada:**
  - a marca de chegada é **recalculada** na sugestão, pelo `ArrivalsByRoom` extraído na Task 2. Corresponde à D8 da E02;
  - **early check-in:** o `reservations` não guarda hora prevista de chegada nem pedido de early. Por isso a prioridade de early da RF-GOV-03 fica **pendente**, com nota para o `arquiteto`. Aqui só a chegada prevista conta.
- **D4-E03, DND:** com a UH em `GuestServiceFlag.DO_NOT_DISTURB` (`RoomsApi.status`) no momento da sugestão, a tarefa `STAYOVER` dela fica fora da sugestão inicial e segue `PENDING` sem camareira. A governanta pode atribuí-la no ajuste.
- **D5-E03, algoritmo** (guloso determinístico, no `domain`):
  1. ordena as tarefas por (chegada desc, prioridade desc, andar, número da UH ou nome da área, id);
  2. distribui os andares inteiros, do maior total de créditos para o menor, sempre para a camareira menos carregada (desempate por nome e depois id do colaborador);
  3. se a diferença entre a mais e a menos carregada passar do maior crédito de uma tarefa, move tarefas da mais carregada para a menos carregada, começando pela de menor crédito e terminando na de maior id, até respeitar o limite;
  4. a ordem de execução de cada camareira segue o passo 1.

  Área sem andar entra como andar `null`, depois dos andares. O desenvolvedor pode ajustar os detalhes se mantiver as garantias do Review Focus 2 e o determinismo. O handoff registra o que ficou.
- **D6-E03, plano:** um plano por data (`assignment_plan`, chave `business_date`), em `DRAFT` ou `PUBLISHED`.
  - `suggest` refaz o rascunho inteiro a partir das tarefas atuais. Isso vale também depois de publicado: o plano volta a `DRAFT`, e as tarefas só mudam na próxima publicação.
  - O ajuste (`PATCH`) muda itens do rascunho.
  - A publicação grava nas tarefas e marca `PUBLISHED` com a versão.
- **D7-E03, tarefa removida numa republicação:** volta a `PENDING`, sem camareira. É a transição `ASSIGNED → PENDING`, que a SPEC §8.4 não desenha (só `Assigned → Assigned`, reatribuída). Fica registrada como extensão da máquina de estados para o `arquiteto`. A alternativa descartada, proibir a remoção, contradiz "republicar notifica quem teve tarefa removida".
- **D8-E03, aviso:**
  - tipo `notifications.task-assigned`, uma notificação por camareira com mudança;
  - título "Suas tarefas de hoje mudaram";
  - texto com os números das UHs e os nomes das áreas incluídas e removidas, sem dado de hóspede;
  - destinatário `NotificationRecipients.user(employee.userId)`.
- **D9-E03, tempo real:** tópico novo `housekeeping`, com permissão `housekeeping.tasks.read`. A mensagem `assignment-plan.published` leva só a data, a versão e as contagens. Ela é publicada na transação da publicação pelo `RealtimePublisher`.
- **D10-E03, permissão:** `housekeeping.assignments.manage`, só escopo `ALL`, não sensível, nos modelos Gerente e Governanta. A leitura do plano usa `housekeeping.tasks.read`.

---

### Task 1: Algoritmo da sugestão no domínio

**Objetivo:** criar a função pura que, dadas as tarefas elegíveis, as camareiras escaladas, os andares e o DND, devolve a distribuição sugerida e a ordem de cada camareira, cumprindo D4-E03, D5-E03 e o Review Focus 2.

**Critérios de aceite:**
- `AssignmentSuggesterTest` (unidade, sem Spring), que afirma:
  - só as camareiras dadas recebem tarefas;
  - a diferença de créditos entre a mais e a menos carregada é ≤ o maior crédito de uma tarefa, em pelo menos 8 casos, inclusive:
    - andar maior que a média;
    - créditos 0;
    - uma só camareira;
    - mais camareiras que tarefas;
    - 30 UHs em 3 andares;
    - todas as tarefas do mesmo crédito;
  - quando o limite permite, todas as tarefas de um andar ficam com a mesma camareira;
  - na ordem de cada camareira, as chegadas vêm primeiro;
  - DND tira só a `STAYOVER` da UH, e a `CHECKOUT` dela continua;
  - a mesma entrada, embaralhada, gera a mesma saída (determinismo);
  - sem camareira, a saída é vazia, com todas as tarefas em "não atribuídas".
- Verificação: `timeout 900 ./gradlew test --tests '*AssignmentSuggesterTest'` e `timeout 900 ./gradlew lint`.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/housekeeping/domain/AssignmentSuggester.kt` (novo). Teste em `src/test/kotlin/com/zeny/hotel/housekeeping/domain/`.

**Tamanho:** ~40 min; 1 a 2 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `data class SuggestionTask(val taskId: HousekeepingTaskId, val roomNumber: String?, val areaName: String?, val floor: String?, val credits: Int, val priority: Int, val arrivalExpected: Boolean, val source: TaskSource, val doNotDisturb: Boolean)`.
- `data class Attendant(val employeeId: EmployeeId, val name: String)`.
- `object AssignmentSuggester { fun suggest(tasks: List<SuggestionTask>, attendants: List<Attendant>): Suggestion }`.
- `data class Suggestion(val byAttendant: Map<EmployeeId, List<HousekeepingTaskId>>, val unassigned: List<HousekeepingTaskId>)`, com cada lista já na ordem de execução.

O `EmployeeId` é do `staff` (tipo público). Se o `domain` não puder depender dele pela ArchUnit, use um id próprio e converta na `application`.

### Task 2: Plano do dia, sugestão e ajuste

**Objetivo:**
- criar o `AssignmentPlan` persistido, com:
  - `POST /api/v1/assignment-plans/{date}/suggest`, que monta o rascunho com as tarefas elegíveis (D2-E03), as camareiras da escala (D1-E03), os andares, o DND e a chegada recalculada (D3-E03);
  - `GET /api/v1/assignment-plans/{date}`;
  - `PATCH /api/v1/assignment-plans/{date}/assignments`, para o ajuste manual;
- extrair antes o `ArrivalsByRoom` e o `HousekeepingAudit` (dívida da E02).

**Critérios de aceite:**
- **Refatoração:**
  - `arrivalsByRoom` sai da `HousekeepingTaskGeneration` para `application/ArrivalsByRoom.kt`, usado pela geração, pela avulsa e pela sugestão;
  - o `HousekeepingAudit` sai de `CleaningTypesService.kt` para `application/HousekeepingAudit.kt`;
  - o log da programada pulada por tipo ou local inválido passa a `INFO` (T3-M4 da E02);
  - as suítes do `housekeeping` seguem verdes, sem mudança de asserção.
- **`AssignmentPlansApiTest`** (`@IntegrationTest`, data futura própria):
  - `suggest` responde `200` com o plano em `DRAFT`, a versão no `ETag`, os itens (tarefa, camareira, ordem) e as não atribuídas;
  - só aparecem as camareiras escaladas no setor de governança naquela data, montadas pelo teste com a escala do `staff`;
  - uma tarefa `PENDING` de uma data anterior entra no plano (Review Focus 5);
  - uma `STAYOVER` de UH em DND fica em não atribuídas;
  - uma tarefa `IN_PROGRESS`, gravada direto no banco, não entra (Review Focus 4);
  - `GET` devolve o mesmo plano;
  - `PATCH` com `If-Match` move uma tarefa de camareira, ou a tira (`employeeId: null`), e devolve o plano novo.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlansApiTest'`.
- **Recusas** (`AssignmentPlansAttackTest`):
  - sem `If-Match` → `428`; com versão velha → `412`;
  - colaborador fora da escala da data → `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`;
  - tarefa que já andou → `422 ASSIGNMENT_TASK_NOT_ASSIGNABLE`;
  - tarefa ou colaborador inexistente → `400 …UNKNOWN_…`;
  - tarefa de data posterior à do plano → `422`;
  - sem o setor de governança → `422 HOUSEKEEPING_DEPARTMENT_MISSING`;
  - data malformada ou fora do `StorableRange` no caminho → `400`, nunca `500`;
  - ajuste antes de existir o plano → `404`.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlansAttackTest'`.
- **Permissões** (`AssignmentPlanPermissionsTest`):
  - `housekeeping.assignments.manage` (D10-E03) nos modelos Gerente e Governanta;
  - sem ela, `suggest` e `PATCH` dão `403`, antes de qualquer recusa de estado;
  - o `GET` exige `housekeeping.tasks.read`;
  - a trilha é `housekeeping.assignment-plan.suggest` e `housekeeping.assignment-plan.adjust`;
  - o `AuthorizationTest` do testador vai ao testador se ficar vermelho.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanPermissionsTest' --tests 'com.zeny.hotel.iam.*'`.
- **Schema** (`AssignmentPlanSchemaTest`):
  - `assignment_plan` (`business_date` PK, `status`, `version`, carimbos, `published_at`, `published_by`);
  - `assignment_plan_item` (`business_date`, `task_id` único por plano, `employee_id`, `position`);
  - FK para a tarefa;
  - o banco recusa DELETE em `assignment_plan`;
  - `assignment_plan_item` aceita DELETE, porque o rascunho é refeito;
  - `DatabaseRolesTest` atualizado.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanSchemaTest' --tests '*DatabaseRolesTest'`.
- **Módulo:** `ModularityTest` com `["shared", "hotel", "rooms", "reservations", "staff", "checklists"]` em igualdade exata, e o contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingModule.kt`, `HousekeepingPermissions.kt` e `HousekeepingTypes.kt`;
  - `application/ArrivalsByRoom.kt` e `application/HousekeepingAudit.kt` (extraídos);
  - `application/HousekeepingTaskGeneration.kt` e `application/CleaningTypesService.kt` (só a extração e o log);
  - `application/AssignmentPlansService.kt`, `infrastructure/AssignmentPlanPersistence.kt` e `web/AssignmentPlansController.kt` (novos).
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1100__create_assignment_plans.sql` e `V2026_11_18_1101__grant_assignment_plans_to_app_role.sql`.
- `api/openapi.yaml`.

**Tamanho:** ~60 min; 12 arquivos de produção (2 SQL, o contrato gerado e 2 extrações mecânicas).

**Depende de:** Task 1.

**Interfaces:**
- **Consome:** `AssignmentSuggester.suggest` (Task 1); `StaffApi.departments()` e `daySchedule(date, departmentId)`; `RoomsApi.room(id)` (andar e número), `area(id)` e `status(id)` (`serviceFlag`); `ArrivalsByRoom`.
- **Produz:**
  - a porta `AssignmentPlanStore`;
  - `AssignmentPlansService.suggest(date)`, `get(date)` e `adjust(date, expectedVersion, changes)`;
  - o `AssignmentPlanView` (data, status, versão, itens com `taskId`, `employeeId`, `position` e não atribuídas), que a Task 3 publica;
  - uma função interna que devolve as camareiras escaladas numa data, reusada pela Task 3.

### Task 3: Publicação, republicação, aviso e tempo real

**Objetivo:** `POST /api/v1/assignment-plans/{date}/publish` com `If-Match`:
- reconfere a escala;
- grava nas tarefas a camareira (`assigned_employee_id`) e o status `ASSIGNED`, ou `PENDING` para as removidas (D7-E03);
- marca o plano `PUBLISHED`;
- avisa só as camareiras com tarefa incluída ou removida em relação à publicação anterior (D8-E03);
- publica `assignment-plan.published` no tópico `housekeeping` (D9-E03).

**Critérios de aceite:**
- **`AssignmentPlanPublishTest`** (`@IntegrationTest`, data futura própria):
  - na primeira publicação, as tarefas do plano ficam `ASSIGNED` com a camareira, e cada camareira com usuário recebe uma notificação `notifications.task-assigned`, conferida no painel dela;
  - na republicação sem mudança, ninguém é avisado;
  - na republicação depois de mover uma tarefa de A para B, só A e B são avisadas, e C, sem mudança, não (Review Focus 3);
  - a tarefa removida volta a `PENDING`, sem camareira (D7-E03);
  - a camareira sem usuário não é avisada e aparece na resposta como "sem usuário", só pelo id;
  - o título e o texto do aviso só têm números de UH e nomes de área: o teste confere que não há nome de hóspede de uma reserva da UH;
  - a trilha é `housekeeping.assignment-plan.publish`, com retrato só de ids e contagens.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanPublishTest'`.
- **Escala na publicação** (Review Focus 1): uma camareira que sai da escala entre a sugestão e a publicação faz a publicação responder `422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED`, e nada é gravado.
- **Tarefa que andou:** uma tarefa que andou entre o ajuste e a publicação (`IN_PROGRESS`, gravada no banco) não é alterada, e a publicação responde `422 ASSIGNMENT_TASK_NOT_ASSIGNABLE` sem gravar nada.
- **Concorrência:** sem `If-Match` → `428`; com versão velha → `412`.
- **Tempo real:** um `SseTestClient` com `housekeeping.tasks.read` recebe `assignment-plan.published` com a data e a versão, e sem `housekeeping.tasks.read` não recebe.
  - O tópico é registrado como bean.
  - Verificação: `timeout 900 ./gradlew test --tests '*AssignmentPlanPublishTest' --tests '*AssignmentPlanRealtimeTest'`.
- **Schema:** `housekeeping_task.assigned_employee_id` (nulo em `PENDING`) e a lista de tarefas expõe `assignedEmployeeId`. Verificação: `timeout 900 ./gradlew test --tests '*HousekeepingTaskSchemaTest' --tests '*HousekeepingTasksApiTest'`.
- **Módulo:** `ModularityTest` com `"notifications"` acrescentado, em igualdade exata, e o contrato regenerado. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.ModularityTest'` e `timeout 900 ./gradlew checkOpenApi lint`.

**Áreas tocadas:**
- Em `src/main/kotlin/com/zeny/hotel/housekeeping/`:
  - `HousekeepingModule.kt` e `HousekeepingTypes.kt`;
  - `HousekeepingRealtime.kt` (novo, o tópico);
  - `domain/HousekeepingTask.kt` (`assign` e `unassign`);
  - `application/AssignmentPlansService.kt` (`publish`);
  - `infrastructure/HousekeepingTaskPersistence.kt`;
  - `web/AssignmentPlansController.kt` e `web/HousekeepingTasksController.kt` (campo novo).
- `src/main/resources/db/migration/housekeeping/V2026_11_18_1102__add_task_assignee.sql`.
- `api/openapi.yaml`.

Testes no `housekeeping`.

**Tamanho:** ~55 min; 10 arquivos de produção.

**Depende de:** Task 2.

**Interfaces:**
- **Consome:** `AssignmentPlansService` e a função das camareiras escaladas (Task 2); `NotificationsApi.notify`; `NotificationTypes.TASK_ASSIGNED`; `RealtimePublisher`.
- **Produz:**
  - `HousekeepingTask.assign(employeeId)` e `unassign()`, só a partir de `PENDING` ou `ASSIGNED`;
  - `AssignmentPlansService.publish(date, expectedVersion): PublishResult`, com a versão, os avisados (contagem) e os sem usuário;
  - `HousekeepingRealtime.TOPIC`.

# M6 E10 — Motor do fechamento do dia: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** criar o módulo `endofday` com o motor do fechamento do dia (execução com etapas persistidas, ordenada, retomável, única por data e nunca em paralelo), os gatilhos automático (horário do hotel, no fuso dele) e manual (com step-up), o andamento no SSE, o aviso de pendência pelo `notifications`, as métricas e os alertas, e o apoio de testes isolado (H10) que E11, E13, E14 e o gate reutilizam.

**Architecture:** o `endofday` (camada Operação) depende só de `shared`, `hotel` e `notifications` nesta etapa (subconjunto da linha da ARCHITECTURE §3.1). Uma execução (`EndOfDayRun`) é única por data operacional (restrição única no banco) e nasce com a lista ordenada das etapas registradas (beans `EndOfDayStepHandler`), reconciliada com o registro a cada retomada. A execução é exclusiva pela trava do ShedLock `end-of-day-run` com *keep-alive* e, no banco, pela linha da execução travada e conferida na transação de cada etapa; uma queda solta a trava e o próximo disparo retoma a etapa pendente. As etapas rodam numa thread própria do motor (sempre com ator sistema), cada uma numa transação que inclui a marca `COMPLETED`; a falha marca a etapa e a execução `FAILED`, registra uma `EndOfDayIssue` e avisa quem acompanha o fechamento.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, ShedLock 7.10.1 (`KeepAliveLockProvider` e `DefaultLockingTaskExecutor` do `shedlock-core`, já no classpath), Micrometer/Prometheus, Testcontainers 2 (PostgreSQL 18), Kotest assertions/JUnit 5.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E10 — Motor do fechamento do dia" (objetivo, escopo, critérios de aceite, notas para o plano, H8 e H10), com `docs/SPEC.md` §5.13 (RF-FEC-01, RF-FEC-03), RN-21, §8.2.9, RF-ACS-08, RF-CFG-04, RF-NOT-01, e `docs/ARCHITECTURE.md` §3.1, §5, §6.3, §7, §10, §11, §13 e §16.

## Global Constraints

- Grafo de módulos e regra por camadas da ARCHITECTURE §3.1/§3.2: `endofday` declara `allowedDependencies = ["shared", "hotel", "notifications"]` e usa só a API pública da raiz de cada um; a linha da §3.1 já lista um superconjunto, então a tabela não muda. `notifications` nunca escuta evento de negócio: o `endofday` o chama.
- RN-21: uma única execução de fechamento por data, nunca em paralelo; cada etapa idempotente (o motor não reexecuta etapa `COMPLETED`).
- Ações sensíveis com step-up (RF-ACS-08): fechamento manual e retomada manual usam `Authorization.requireOrApproval` com permissão `sensitive = true`; a autorização vem antes de qualquer checagem que revele estado (ARCHITECTURE §5, passo 2).
- Auditoria (RNF-11; regra ArchUnit `writeUseCasesAreAudited` em `src/test/kotlin/com/zeny/hotel/architecture/LayerRules.kt`): todo método `@Transactional` de escrita em `application` é `@Audited`. Chamadas que precisam do proxy (`@Transactional`/`@Audited`) atravessam beans diferentes, nunca `this.`.
- `HotelApi.advanceBusinessDate(expectedCurrent)` é o único caminho de avanço da data; o motor não o chama nesta etapa (é a última etapa de negócio, E11). "Data fechada" deriva da execução `COMPLETED` da data; a data operacional continua só em `hotel`.
- Migrações novas em `src/main/resources/db/migration/endofday/`, com versão posterior a `V2026_11_11_1001` (`outOfOrder` desligado): `V2026_11_12_1000__create_endofday_tables.sql` e `V2026_11_12_1001__grant_endofday_to_app_role.sql`.
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca à mão); enum de resposta que cresce sai como `x-extensible-enum` (`@field:ExtensibleEnum`); a API não expõe `DELETE`; parâmetro novo só com consumidor nomeado.
- Erros de domínio: subclasses de `DomainException` com `ErrorCode` estável declarado na raiz do módulo (`EndOfDayErrorCodes`); mensagens em pt-BR sem dado pessoal. Código, API e banco em inglês; KDoc, mensagens, documentação e commits em pt-BR.
- Notificação, SSE e log nunca levam dado pessoal de hóspede (RN-13; ARCHITECTURE §16): título e texto só com data, código da pendência e referência opaca; mensagem SSE só `type`, `id`, `version`; falha de etapa logada pelo código, sem a mensagem da exceção.
- Jobs: `@Scheduled` + `@SchedulerLock(name = …)`, método sem retorno primitivo; o ShedLock usa a hora do banco. O gatilho novo fica adiado em **todo** contexto de teste (Task 12).
- Testes: banco compartilhado por todos os `@IntegrationTest`; nenhum teste desta etapa cria execução de fechamento no banco compartilhado — todos rodam no banco isolado (Task 1), em dois contextos só: `@EndOfDayIntegrationTest` puro (Tasks 1, 3 e 4) e `@EndOfDayIntegrationTest` + `@Import(EndOfDayTestConfiguration::class)` (Task 5 em diante). **Regra do banco isolado:** todo teste termina com a data operacional corrente sem execução ou com execução `COMPLETED` (use `EndOfDayTestRuns.settle()` no `finally`), devolve o `MutableClock` ao original e desfaz o que mudou na configuração do hotel (fuso, horário). As asserções olham só os dados do próprio teste.
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`. As tarefas rodam em série (sem ondas paralelas).
- Contagem de "arquivos de produção" por tarefa: arquivos Kotlin de `src/main`; o par de migrações criar + conceder conta como um; YAML de configuração, `alerts.yaml`, `api/openapi.yaml` (gerado) e documentação não contam.
- Commits de cada soul com o trailer `Soul: <soul>`.

## Review Focus

1. **Queda no meio da execução** (processo morre com uma etapa `RUNNING`): o próximo disparo retoma dessa etapa sem reexecutar as concluídas — Tasks 5 e 12.
2. **Disparo manual e automático ao mesmo tempo para a mesma data, ou trava vencida com dois executores:** uma execução, cada etapa confirmada uma vez, sem 500 — Tasks 4 (duas confirmações da mesma etapa), 9 (dois `POST`) e 12 (gatilho × `POST`).
3. **Repetição do `POST` depois de um timeout:** mesma `Idempotency-Key` → mesma resposta; sem a chave, `400` — Task 9.
4. **Hotel com a data operacional atrasada (servidor desligado):** o automático só age até 24 h depois do horário devido; fora disso, só manual — Task 12.
5. **Deploy que muda as etapas com uma execução `FAILED` pendente:** a retomada reconcilia com o registro e termina — Tasks 3 e 5.

---

## Mapa de arquivos

Produção (`src/main/kotlin/com/zeny/hotel/endofday/`):

| Arquivo | Responsabilidade | Task |
|---|---|---|
| `EndOfDayModule.kt` | declaração do módulo e dependências | 2 |
| `EndOfDayPermissions.kt` | `endofday.runs.read` e `endofday.runs.execute` (sensível) | 2 |
| `application/EndOfDayConfiguration.kt` | beans de permissões e do tópico | 2, 7 |
| `domain/EndOfDayRun.kt` | agregado `EndOfDayRun` com `EndOfDayRunStep` e `EndOfDayIssue`, máquina de estados e reconciliação | 3 |
| `infrastructure/EndOfDayRunRepository.kt` | persistência JPA e consultas | 3, 11 |
| `application/EndOfDaySteps.kt` | contrato de etapa, contexto, resultado e registro ordenado | 4 |
| `application/EndOfDayRunTransitions.kt` | transições auditadas (início, conclusão, falha de etapa) | 4, 6, 7 |
| `application/EndOfDayEngine.kt` | execução sob trava, numa thread própria, laço das etapas | 5, 13 |
| `infrastructure/EndOfDayExecutionConfiguration.kt` | `LockingTaskExecutor` com *keep-alive* e o executor de uma thread | 5 |
| `application/EndOfDayIssues.kt` | registro de pendência + aviso pelo `notifications` | 6 |
| `EndOfDayRealtime.kt` | tópico SSE `end-of-day` e tipo `endofday.run` | 7 |
| `EndOfDayErrorCodes.kt` | códigos de erro estáveis | 8 |
| `application/EndOfDayRunCommands.kt` | casos de uso transacionais e auditados (criar, iniciar manual, retomar) | 8, 10 |
| `application/EndOfDayService.kt` | fachada: comando + execução depois do commit; consultas | 9, 10, 11 |
| `web/EndOfDayController.kt` | `/api/v1/end-of-day/runs…` | 9, 10, 11 |
| `domain/EndOfDaySchedule.kt` | horário devido de uma data no fuso do hotel | 12 |
| `application/EndOfDayTrigger.kt` | decisão do disparo automático | 12 |
| `infrastructure/EndOfDayTriggerJob.kt` | `@Scheduled` + `@SchedulerLock("end-of-day-trigger")` | 12 |
| `infrastructure/EndOfDayMetrics.kt` | `MeterBinder` com as métricas | 13 |

Migrações (Task 3); `src/main/resources/application.yaml` (Tasks 5, 9, 12, 13); `docker/prometheus/alerts.yaml` (Task 13); `api/openapi.yaml` (Tasks 9, 10, 11, 14, gerado); `README.md` (Tasks 9–11, 13, 14); `docs/ARCHITECTURE.md` §10 e §13 (Tasks 7 e 14); `hotel/web/HotelController.kt`, só a descrição de `endOfDayTime` (Task 14). Testes em `src/test/kotlin/com/zeny/hotel/`: `testsupport/EndOfDayIntegrationTest.kt` (Task 1), `endofday/EndOfDayTestConfiguration.kt` e `endofday/EndOfDayTestRuns.kt` (Task 5), um arquivo de teste por task em `endofday/`.

---

### Task 1: Apoio de testes isolado do fechamento (H10)

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (base de isolamento que E11, E13, E14 e o gate reutilizam)

**Files:**
- Create: `src/test/kotlin/com/zeny/hotel/testsupport/EndOfDayIntegrationTest.kt`
- Test: `src/test/kotlin/com/zeny/hotel/testsupport/EndOfDayIsolationTest.kt`

**Interfaces:**
- Produces: a anotação `@EndOfDayIntegrationTest` (mesmas propriedades, profile, `@AutoConfigureMockMvc`, `@AutoConfigureMetrics` e inicializadores do `@IntegrationTest`, trocando `PostgresInitializer` por `EndOfDayDatabaseInitializer`); `object EndOfDayDatabase { const val NAME = "zeny_endofday"; val jdbcUrl: String }`; `class EndOfDayDatabaseInitializer : ApplicationContextInitializer<ConfigurableApplicationContext>`.

Decisão (H10): **banco próprio no mesmo container** (`PostgresContainer.instance`), criado uma vez por JVM. Os papéis são do cluster, então `zeny_app` já existe; o Flyway migra o banco novo como dono e as migrações dão os privilégios. Alternativa descartada: um segundo container (mais memória numa máquina de 14 GB e mais um boot do Testcontainers).

- [ ] **Step 1: Testes que falham** (`EndOfDayIsolationTest`, com `@EndOfDayIntegrationTest`):
  - `the context is connected to the isolated database`: a URL do `DataSource` termina em `/zeny_endofday` e o usuário é `zeny_app`.
  - `advancing the business date here does not change the shared database`: lê a data do banco compartilhado por JDBC direto (`PostgresContainer.instance.jdbcUrl`, dono, `SELECT business_date FROM hotel.business_day`), chama `hotelApi.advanceBusinessDate(hotelApi.currentBusinessDate())` no contexto isolado e confere que a isolada andou 1 dia e a compartilhada não mudou.
  - `the app role has no DDL in the isolated database`: `CREATE TABLE public.x(i int)` e `CREATE TEMP TABLE y(i int)` pelo `DataSource` do contexto falham com `SQLException`.
  - `both integration annotations carry the same test properties`: por reflexão, o `properties` do `@SpringBootTest` de `@EndOfDayIntegrationTest` é igual ao do `@IntegrationTest`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.testsupport.EndOfDayIsolationTest'` → falha de compilação.
- [ ] **Step 3: Implementar** `EndOfDayIntegrationTest.kt`: `EndOfDayDatabase` cria o banco por `lazy` sincronizado, como dono no banco padrão do container (`CREATE DATABASE zeny_endofday`), e no banco novo aplica o que `docker/postgres/initdb/10-create-app-role.sh` faz por banco: `REVOKE TEMPORARY ON DATABASE zeny_endofday FROM PUBLIC; GRANT CONNECT ON DATABASE zeny_endofday TO zeny_app; REVOKE CREATE ON SCHEMA public FROM PUBLIC`. O inicializador aplica as propriedades do `PostgresInitializer` com a URL nova. KDoc em pt-BR: o motivo (H10); que o banco é compartilhado por todos os testes desta anotação; a **regra do banco isolado** de "Global Constraints"; que o fechamento real (E11 em diante) age sobre tudo o que os testes anteriores deixaram, então as asserções olham só os próprios dados.
- [ ] **Step 4: Ver passar** o mesmo comando → 4 testes.
- [ ] **Step 5: Commit** `test(endofday): apoio de testes isolado do fechamento (H10)`.

### Task 2: Módulo `endofday` e permissões

**Estimativa:** 25 min · **Depende de:** nenhuma · **Risco:** alto (catálogo de permissões e ação sensível)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/EndOfDayModule.kt`, `EndOfDayPermissions.kt`, `application/EndOfDayConfiguration.kt`
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayPermissionsTest.kt`

**Interfaces:**
- Produces: `object EndOfDayPermissions : ModulePermissions` com `module = "endofday"`, `RUNS_READ = PermissionCode("endofday.runs.read")` ("Acompanhar o fechamento do dia: execuções, etapas e pendências"; escopo `ALL`, não sensível; modelos `MANAGER`, `RECEPTIONIST` e `FINANCE`) e `RUNS_EXECUTE = PermissionCode("endofday.runs.execute")` ("Executar ou retomar o fechamento do dia manualmente"; `ALL`, `sensitive = true`, modelo `MANAGER`); `@Configuration class EndOfDayConfiguration` com `@Bean fun endOfDayPermissions(): ModulePermissions`.

- [ ] **Step 1: Testes que falham:** no `ModularityTest`, `endofday depends only on shared, hotel and notifications, and only on their public APIs` (formato do teste do `checklists`); em `EndOfDayPermissionsTest` (`@IntegrationTest`), `GET /api/v1/permissions` como administrador lista as duas, `endofday.runs.execute` com `sensitive: true` e `endofday.runs.read` com `sensitive: false`, e os modelos sugeridos são os acima.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.endofday.EndOfDayPermissionsTest'`.
- [ ] **Step 3: Implementar;** KDoc do módulo no estilo do `PrintingModule` (o que faz, de quem depende, que nunca acessa `files` e que só a etapa de avanço da E11 chama `advanceBusinessDate`).
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): módulo e permissões do fechamento do dia`.

### Task 3: Esquema e agregado `EndOfDayRun`

**Estimativa:** 45 min · **Depende de:** Tasks 1 e 2 · **Risco:** alto (persistência e núcleo)

**Files:**
- Create: as duas migrações de "Global Constraints"; `src/main/kotlin/com/zeny/hotel/endofday/domain/EndOfDayRun.kt`; `infrastructure/EndOfDayRunRepository.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRunTest.kt` (unidade), `EndOfDayRunPersistenceTest.kt` (`@EndOfDayIntegrationTest`)

**Interfaces:**
- Produces:
  - `@JvmInline value class EndOfDayRunId(val value: UUID)` (UUIDv7, como os demais ids); `enum class RunTrigger { AUTOMATIC, MANUAL }`, `RunStatus { RUNNING, FAILED, COMPLETED }`, `StepStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }`.
  - `class EndOfDayRun` (tabela `endofday.run`): `id`, `businessDate: LocalDate`, `trigger`, `status`, `triggeredBy: UserId?`, `startedAt`, `finishedAt?`, `updatedAt`, `version: Long` (`@Version`), `steps` (ordenados por `position`). **Toda transição altera `updatedAt`**, para a versão da execução subir a cada mudança de etapa (a etapa é parte do agregado).
  - Fábrica e transições: `companion fun start(businessDate, trigger, triggeredBy, steps: List<StepSlot>, now): EndOfDayRun` com `data class StepSlot(val name: String, val position: Int)` (status `RUNNING`, etapas `PENDING`; lista não vazia, sem nome nem posição repetidos); `fun beginNext(registered: List<StepSlot>, now): BeginResult` — numa só chamada: `reconcile(registered, now)`, escolhe a primeira etapa em ordem de posição que não é `COMPLETED` nem `SKIPPED` e a marca `RUNNING` com `attempts + 1`; sem etapa restante, a execução vira `COMPLETED` (com `finishedAt`). `data class BeginResult(val started: StepStart?, val skipped: List<String>)`, `data class StepStart(val name: String, val attempts: Int)`. A escolha vem **depois** da reconciliação, para uma etapa nova anterior à falhada rodar antes dela e uma etapa pulada nunca ser iniciada; `fun completeStep(name, result, now)` (se não resta etapa, execução `COMPLETED` com `finishedAt`); `fun failStep(name, reason, now)` (etapa e execução `FAILED`, `finishedAt`); `fun resume(now)` (só de `FAILED`: volta a `RUNNING`, limpa `finishedAt`, a etapa `FAILED` volta a `PENDING`; de outro estado, `IllegalStateException`); `fun reconcile(registered: List<StepSlot>, now): List<String>` (usado por `beginNext`) — só em `RUNNING`: etapa não concluída cujo nome não está no registro vira `SKIPPED` com `result = "UNREGISTERED"`; etapa registrada que falta na execução entra `PENDING` na posição dela **se** a posição for maior que a da última `COMPLETED` (senão é ignorada: não se volta atrás); devolve os nomes pulados (o chamador abre uma pendência por nome).
  - `class EndOfDayRunStep` (tabela `endofday.run_step`, PK `(run_id, name)`, posição única por execução): `name`, `position`, `status`, `startedAt?`, `finishedAt?`, `result: String?` (até 200), `attempts: Int`.
  - `class EndOfDayIssue` (tabela `endofday.run_issue`): `id`, `runId`, `stepName: String?`, `kind: String` (`UPPER_SNAKE`, até 60), `reference: String?` (até 120), `message: String` (até 500), `resolved: Boolean = false`, `createdAt`.
  - `interface EndOfDayRunRepository : JpaRepository<EndOfDayRun, EndOfDayRunId>` com `findByBusinessDate(date): EndOfDayRun?` e `lockForUpdate(id)`, que trava a linha da execução com **`SELECT … FOR NO KEY UPDATE`** (consulta nativa; não `FOR UPDATE`, que bloquearia o `INSERT` de pendência de uma transação `REQUIRES_NEW` de item pela FK e travaria a própria thread sem o PostgreSQL detectar), seguido da carga normal da entidade; repositório de `EndOfDayIssue` com `findByRunIdOrderByCreatedAtAscIdAsc(runId)`.
- Banco: `run_step.run_id` e `run_issue.run_id` com FK para `run` (ARCHITECTURE §11, FKs normais dentro do schema); `run.business_date` `UNIQUE` (`run_business_date_uk`) — **uma execução por data**, como na RN-21. Ruling registrada: o roadmap fala em "única por data de execução concluída"; a unicidade total é mais estrita e, com a reconciliação acima, nenhuma execução fica sem saída (alternativa descartada: unicidade parcial mais uma transição de abandono com step-up, que abre o caminho de recomeçar do zero). `CHECK` dos valores de `trigger`, `status` e `step.status`; `finished_at` presente se e só se `status <> 'RUNNING'`. Grants ao `zeny_app`: `SELECT, INSERT, UPDATE` nas três tabelas (sem `DELETE`).

- [ ] **Step 1: Testes de unidade que falham** (`EndOfDayRunTest`): `start creates pending steps in position order`; `completing the last step completes the run`; `fail marks step and run failed`; `resume from failed reopens the failed step and keeps completed ones`; `resume from running or completed is refused`; `start refuses empty list, duplicated names or positions`; `beginStep counts attempts`; `reconcile skips unregistered pending steps and adds new registered steps after the last completed`; `reconcile ignores a new step positioned before the last completed`; `beginNext after reconcile starts a new step positioned before the failed one first`; `beginNext never starts a skipped step and completes the run when nothing is left` (execução `FAILED` cuja etapa falhada saiu do registro → `resume` → `beginNext` → execução `COMPLETED`, `started` nulo, `skipped` com o nome); `every transition changes updatedAt`.
- [ ] **Step 2: Testes de integração que falham** (`EndOfDayRunPersistenceTest`; datas `currentBusinessDate().plusYears(50).plusDays(n)`, que nenhum outro teste usa, sem tocar a data corrente): `a run round-trips with its steps in order`; `a second run for the same business date violates run_business_date_uk`; `a step change increments the run version`; `lockForUpdate does not block an issue insert from another transaction` (com a execução travada por `lockForUpdate` numa transação aberta, um `INSERT` em `run_issue` noutra conexão conclui; `@Timeout(10)`); `the app role cannot delete runs` (`DELETE` por JDBC falha).
- [ ] **Step 3: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRun*'`.
- [ ] **Step 4: Implementar** migrações, agregado e repositórios.
- [ ] **Step 5: Ver passar** o mesmo comando.
- [ ] **Step 6: Commit** `feat(endofday): esquema e agregado da execução do fechamento`.

### Task 4: Contrato de etapa, registro e transições auditadas

**Estimativa:** 40 min · **Depende de:** Task 3 · **Risco:** alto (contrato que a E11 e a E13 implementam; auditoria)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDaySteps.kt`, `application/EndOfDayRunTransitions.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayStepRegistryTest.kt` (unidade), `EndOfDayRunTransitionsTest.kt` (`@EndOfDayIntegrationTest`)

**Interfaces:**
- Produces:
  - `interface EndOfDayStepHandler { val name: String; val position: Int; fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome }`. `name` em kebab-case (`[a-z][a-z0-9-]{0,59}`), `position` positivo. **KDoc do contrato** (é o que a E11 e a E13 seguem): `execute` roda dentro da transação que marca a etapa concluída; precisa ser idempotente, com a chave pela **data** (`context.businessDate`), nunca pelo `runId` (uma retomada repete a etapa); trabalho por item que tolera falha roda cada item em transação própria (`REQUIRES_NEW`, por um bean com `@Transactional` próprio), idempotente pela chave natural, e a falha do item vira pendência; a transação do item nunca altera a linha da execução (`endofday.run`), que está travada pela transação da etapa; o nome da etapa é estável como um código (renomear ou remover a etapa de avanço da data é proibido: a execução concluiria sem avançar); uma exceção que escapa de `execute` falha a etapa e desfaz tudo o que ela fez na transação dela, inclusive pendências e avisos levantados nela; efeito fora do banco (arquivo no S3, na E13) precisa ser idempotente; as posições das etapas de produção ficam espaçadas de 100 em 100 na ordem da RF-FEC-02, para caber etapa nova entre duas.
  - `data class EndOfDayStepContext(val runId: EndOfDayRunId, val businessDate: LocalDate, val issues: EndOfDayIssueSink)`; `fun interface EndOfDayIssueSink { fun raise(kind: String, reference: String?, message: String) }`.
  - `data class EndOfDayStepOutcome(val result: String)` com `DONE` e `NOT_APPLICABLE`.
  - `@Component class EndOfDayStepRegistry(handlers: List<EndOfDayStepHandler>)`: `val steps` por `position`; `val slots: List<StepSlot>`; `fun handlerOrNull(name): EndOfDayStepHandler?`; o construtor recusa nome ou posição repetidos (`IllegalStateException`: o boot falha).
  - `@Component class EndOfDayRunTransitions` (cada método `@Transactional` e `@Audited`, ator sistema quando chamado da thread do motor):
    - `fun begin(runId): StepStart?` (`endofday.run-step.begin`): trava a execução (`lockForUpdate`), chama `beginNext(registry.slots, now)`, abre uma pendência `STEP_UNREGISTERED` por etapa pulada e devolve a etapa iniciada, ou `null` quando a execução terminou (`COMPLETED`);
    - `fun complete(runId, stepName, attempts, work: (EndOfDayStepContext) -> EndOfDayStepOutcome): Boolean` (`endofday.run-step.complete`): trava a execução (`lockForUpdate`) e **confere que a etapa ainda está `RUNNING` com o mesmo `attempts`**; se não está (outro executor passou à frente depois de uma trava vencida), devolve `false` sem chamar `work` e sem efeito; se está, chama `work` e `completeStep` na mesma transação e devolve `true`;
    - `fun fail(runId, stepName, reason): Unit` (`endofday.run-step.fail`): `failStep` e pendência `STEP_FAILED` com `reference = stepName` e `message = reason`.
    - `reason` = o `ErrorCode` se a exceção é `DomainException`, senão o nome simples da classe; nunca a mensagem.
    - O sink de pendência desta task grava a `EndOfDayIssue` no repositório; a Task 6 o troca pelo `EndOfDayIssues` com aviso.

- [ ] **Step 1: Testes que falham:** `EndOfDayStepRegistryTest` (ordena por posição; recusa nome repetido; recusa posição repetida; recusa nome fora do padrão). `EndOfDayRunTransitionsTest` (execuções em datas `plusYears(60)`, criadas pelo repositório): `begin then complete runs the work once and completes the step`; `begin returns null and completes the run when every remaining step is unregistered`; `complete with a stale attempts value does nothing and returns false` (duas chamadas `complete` com o mesmo `attempts` em sequência depois de um `begin`: a primeira `true`, a segunda `false` e o `work` chamado uma vez); `two concurrent completes of the same step commit once` (duas threads, `CountDownLatch`, mesmo `attempts`: exatamente uma `true`); `fail records STEP_FAILED with the error code and no message text`; `the transitions are audited` (trilha com `endofday.run-step.begin`, `.complete` e `.fail`); `begin reconciles an unregistered step into SKIPPED with a STEP_UNREGISTERED issue and starts the next registered one`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayStepRegistryTest' --tests 'com.zeny.hotel.endofday.EndOfDayRunTransitionsTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando e `./gradlew test --tests 'com.zeny.hotel.architecture.*'`.
- [ ] **Step 5: Commit** `feat(endofday): contrato de etapa e transições auditadas do fechamento`.

### Task 5: Motor — trava, thread própria e retomada

**Estimativa:** 45 min · **Depende de:** Task 4 · **Risco:** alto (concorrência e núcleo)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDayEngine.kt`, `infrastructure/EndOfDayExecutionConfiguration.kt`
- Modify: `src/main/resources/application.yaml` (`zeny.endofday.lock-at-most-for: PT5M`)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayTestConfiguration.kt`, `EndOfDayTestRuns.kt`, `EndOfDayEngineTest.kt`

**Interfaces:**
- Consumes: `EndOfDayRunTransitions`, `EndOfDayStepRegistry` (Task 4); repositórios (Task 3).
- Produces:
  - `infrastructure/EndOfDayExecutionConfiguration`: bean `endOfDayLockingTaskExecutor: LockingTaskExecutor` = `DefaultLockingTaskExecutor(KeepAliveLockProvider(lockProvider, scheduler))` sobre o `LockProvider` do `shared` (KDoc: acoplamento consciente ao bean do `SchedulingConfiguration`, pelo tipo da biblioteca) e um `ScheduledExecutorService` de uma thread para o *keep-alive*; e o bean `endOfDayExecutor: ExecutorService` de **uma** thread nomeada `end-of-day`, fechado no `destroy`. Confira na biblioteca o `lockAtMostFor` mínimo do `KeepAliveLockProvider` e anote no KDoc.
  - `@Component class EndOfDayEngine`: `fun execute(runId: EndOfDayRunId, waitAtMost: Duration): EndOfDayExecution` com `sealed interface EndOfDayExecution { data class Finished(val status: RunStatus); data object Busy; data object StillRunning }`.
- Algoritmo do `execute`:
  1. Submete o trabalho ao `endOfDayExecutor` (thread sem contexto de segurança: tudo o que as etapas fizerem é do ator sistema) e espera até `waitAtMost`; passou o prazo → `StillRunning` (o trabalho continua na thread).
  2. No trabalho: `executeWithLock` com `LockConfiguration(now, "end-of-day-run", lockAtMostFor, Duration.ZERO)`; trava ocupada → `Busy` sem tocar na execução.
  3. Sob a trava: execução `COMPLETED` ou `FAILED` → `Finished(status)` sem executar nada (retomar é `resume` do domínio, feito por quem retoma antes de `execute`). `RUNNING` (nova, ou interrompida por queda) → laço: `transitions.begin(runId)`; `null` → `Finished(COMPLETED)`; senão `transitions.complete(runId, name, attempts) { handler.execute(it) }`; `complete` falso → para e devolve `Busy`; exceção em `complete` → `transitions.fail(reason)`, log `WARN` com `runId`, etapa e `reason` (sem a mensagem da exceção; o *stack trace* só em `DEBUG`), devolve `Finished(FAILED)`. Etapa sem handler é tratada pela reconciliação do `begin`. KDoc do motor: o executor tem uma thread só, então um segundo `execute` no mesmo processo espera na fila (e encontra a execução já terminada) em vez de receber `Busy`; `Busy` só vem de outro processo segurando a trava.
- Apoio de teste:
  - `EndOfDayTestConfiguration` (`@TestConfiguration`, o **único** contexto dos testes do motor): importa `MutableClockConfiguration` e `IamTestConfiguration` e declara as etapas falsas `fake-one` (10), `fake-two` (20), `fake-three` (30) e `fake-advance` (40, chama `hotelApi.advanceBusinessDate(context.businessDate)`), mais o `FakeStepsProbe` com `invocations(step, businessDate)`, `failNext(step, times)` (lança `IllegalStateException`), `raiseIssueOn(step, kind)`, `itemsOn(step, items, failingItem)` (processa `items` itens, cada um num bean com `@Transactional(propagation = REQUIRES_NEW)` que levanta a pendência `TEST_ITEM`; o item `failingItem` lança `DomainException` capturada pela etapa; o teste que o usa tem `@Timeout(30)`, para um impasse de trava virar falha e não travamento) e `reset()`. KDoc: quando a E11 registrar etapas reais (posições de 100 em 100), este contexto passa a ter as duas e precisa ser revisto (nota do handoff).
  - `object EndOfDayTestRuns { fun settle(context) }`: para a data corrente do banco isolado, execução `FAILED` → `resume` + `execute`; `RUNNING` → `execute`; repete até `COMPLETED` (com `failNext` zerado). Usado no `finally` de todo teste que cria execução.
- [ ] **Step 1: Testes que falham** (`EndOfDayEngineTest`, `@EndOfDayIntegrationTest @Import(EndOfDayTestConfiguration::class)`; cada teste cria a execução da data corrente com `EndOfDayRun.start(...)` e os `slots` do registro, e termina com `settle`):
  - `runs all steps in order and records start, finish and result of each`: `Finished(COMPLETED)`; quatro etapas `COMPLETED`, `startedAt <= finishedAt`, `result = "DONE"`, início de cada uma ≥ fim da anterior; cada handler 1 vez.
  - `a failure in the third step leaves the run resumable and resuming does not rerun the first two`: `failNext("fake-three", 1)` → `Finished(FAILED)`, `fake-three` `FAILED`, pendência `STEP_FAILED` (`reference = "fake-three"`, `message = "IllegalStateException"`); `resume` + `execute` → `COMPLETED`; contadores `1, 1, 2, 1`; `attempts` de `fake-three` = 2.
  - `a crash mid-run is resumed from the interrupted step`: execução gravada com `fake-one` `COMPLETED` e `fake-two` `RUNNING` (sem trava) → `execute` → `COMPLETED`; `fake-one` não chamado de novo, `fake-two` 1 vez.
  - `a busy lock returns Busy and touches nothing`: o teste segura `end-of-day-run` pelo `LockProvider`; `Busy`; etapas `PENDING`; solta no `finally` e `settle`.
  - `a completed run is not executed again`.
  - `steps run as the system actor`: chamado de dentro de uma requisição autenticada (`asUser`), a trilha de `endofday.run-step.complete` não tem usuário (ator sistema).
  - `per-item work in its own transaction survives a caught item failure`: `itemsOn("fake-two", 3, failingItem = 2)` → `COMPLETED`; três pendências `TEST_ITEM` gravadas (a do item 2 registrando a falha) e a etapa `COMPLETED`.
  - `an issue raised before the step fails is rolled back with it`: `raiseIssueOn("fake-three", "TEST_PENDING")` + `failNext("fake-three", 1)` → só a `STEP_FAILED`, sem `TEST_PENDING`.
  - `a step removed by a deploy does not block the run`: execução criada com uma etapa `fake-gone` (posição 35) que o registro não tem → `COMPLETED`, `fake-gone` `SKIPPED` e pendência `STEP_UNREGISTERED`.
  - `a failed run whose failed step left the registry is resumed to completion`: execução com `fake-one`..`fake-three` `COMPLETED` e `fake-gone` (35) `FAILED` → `resume` + `execute` → `COMPLETED`, `fake-advance` 1 vez, `fake-gone` `SKIPPED`.
  - `waitAtMost elapses with StillRunning and the run completes in background`: `fake-two` dormindo 2 s por `FakeStepsProbe`; `execute(…, 100 ms)` → `StillRunning`; depois a execução fica `COMPLETED`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayEngineTest'`.
- [ ] **Step 3: Implementar** os dois arquivos e a propriedade.
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): motor do fechamento com trava e retomada`.

### Task 6: Pendências avisadas a quem acompanha o fechamento

**Estimativa:** 30 min · **Depende de:** Task 5 · **Risco:** alto (persistência e texto que vai ao push, RN-13)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDayIssues.kt`
- Modify: `application/EndOfDayRunTransitions.kt` (o sink e a `STEP_FAILED` passam pelo `EndOfDayIssues`)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayIssuesTest.kt`

**Interfaces:**
- Produces: `@Component class EndOfDayIssues` com `fun raise(runId, businessDate, stepName: String?, kind: String, reference: String?, message: String): EndOfDayIssue` (sem transação própria: roda na de quem chama), que grava a pendência e chama `NotificationsApi.notify(NotificationsTypes.END_OF_DAY_PENDING, título, texto, NotificationRecipients.withPermission(EndOfDayPermissions.RUNS_READ))` (o tipo vem de `notifications/NotificationTypes.kt` e é obrigatório no painel). Título `"Pendência no fechamento de dd/MM/yyyy"`; texto `"<kind>: <reference ou —>"`; nunca a `message` (ela fica só na API, para quem tem a permissão).

- [ ] **Step 1: Testes que falham** (`EndOfDayIssuesTest`, mesmo contexto; um usuário com `endofday.runs.read` e um sem, pelo `IamTestSupport`; termina com `settle`):
  - `an issue raised by a step notifies who follows the end of day`: `raiseIssueOn("fake-two", "TEST_PENDING")`; depois do `execute`, o usuário com a permissão tem uma notificação `notifications.end-of-day-pending` com o título da data e o texto com `TEST_PENDING`, sem a `message`; o sem permissão não tem nenhuma desse tipo.
  - `a failed step notifies STEP_FAILED`: idem com `failNext`.
  - `a notification of a rolled back issue is not sent`: `raiseIssueOn` + `failNext` na mesma etapa → nenhuma notificação com `TEST_PENDING`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayIssuesTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o comando acima e `EndOfDayEngineTest`.
- [ ] **Step 5: Commit** `feat(endofday): pendências do fechamento avisadas pelo notifications`.

### Task 7: Andamento no tempo real

**Estimativa:** 25 min · **Depende de:** Task 6 · **Risco:** alto (contrato do SSE com os clientes)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/EndOfDayRealtime.kt`
- Modify: `application/EndOfDayRunTransitions.kt` (publica), `application/EndOfDayConfiguration.kt` (bean do tópico), `docs/ARCHITECTURE.md` §10 (o tópico `end-of-day` na lista)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayProgressTest.kt`

**Interfaces:**
- Produces: `object EndOfDayRealtime { val TOPIC = RealtimeTopic("end-of-day", EndOfDayPermissions.RUNS_READ); const val RUN = "endofday.run" }` e o bean do tópico. Cada transação de `EndOfDayRunTransitions` (e as de criação e retomada das Tasks 8 e 10) publica `RealtimeMessage(EndOfDayRealtime.RUN, run.id.value, run.version)` no tópico, sem `owners`.

- [ ] **Step 1: Teste que falha** (`EndOfDayProgressTest`): `progress is published on the end-of-day topic` — as mensagens do tópico durante um `execute` (pelo `RealtimeHistory` do `shared` a partir da posição anterior, ou pelo `SseTestClient` se o histórico não for acessível ao teste) têm `type = "endofday.run"`, `id` = a execução, versões estritamente crescentes e pelo menos duas por etapa (início e conclusão).
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayProgressTest'`.
- [ ] **Step 3: Implementar;** ARCHITECTURE §10 com o tópico.
- [ ] **Step 4: Ver passar** o comando acima e `./gradlew test --tests 'com.zeny.hotel.realtime.*'`.
- [ ] **Step 5: Commit** `feat(endofday): andamento do fechamento no tempo real`.

### Task 8: Códigos de erro e criação auditada da execução

**Estimativa:** 30 min · **Depende de:** Task 7 · **Risco:** alto (regras de criação e segurança)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/EndOfDayErrorCodes.kt`, `application/EndOfDayRunCommands.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRunCommandsTest.kt`

**Interfaces:**
- Produces:
  - `object EndOfDayErrorCodes`: `END_OF_DAY_RUN_NOT_FOUND` (404), `END_OF_DAY_ALREADY_CLOSED` (409: a data pedida tem execução `COMPLETED`), `END_OF_DAY_RUN_EXISTS` (409: a data tem execução `RUNNING` ou `FAILED`; o problema leva `runId`), `END_OF_DAY_DATE_MISMATCH` (409: a data pedida não é a corrente; leva `currentBusinessDate`), `END_OF_DAY_NOT_CONFIGURED` (409: nenhuma etapa registrada), `END_OF_DAY_RUN_NOT_RESUMABLE` (409, Task 10), `END_OF_DAY_RUN_IN_PROGRESS` (409, Task 10).
  - `@Component class EndOfDayRunCommands` (`@Transactional` + `@Audited`, ação `endofday.run.start`): `fun createAutomatic(businessDate): EndOfDayRun` (ator sistema, `triggeredBy` nulo) e `fun createManually(businessDate, approvalToken: String?): EndOfDayRun` (primeiro `requireOrApproval(SensitiveAction("endofday.run.start", RUNS_EXECUTE, "endofday.business-date:<data ISO>"), approvalToken)`, depois as checagens; `triggeredBy` = solicitante). Checagens, nesta ordem: execução da data `COMPLETED` → `END_OF_DAY_ALREADY_CLOSED`; `RUNNING`/`FAILED` → `END_OF_DAY_RUN_EXISTS`; data ≠ corrente → `END_OF_DAY_DATE_MISMATCH`; registro vazio → `END_OF_DAY_NOT_CONFIGURED`. A criação publica a mensagem SSE da Task 7.
- **Ruling registrada (sem etapas registradas):** enquanto a E11 não registra etapas reais, uma execução sem etapas fecharia a data sem avançá-la; por isso `END_OF_DAY_NOT_CONFIGURED`. Alternativa descartada: deixar criar a execução vazia.
- **Ruling registrada (data no corpo):** o `POST` exige a data que o operador pretende fechar, como `advanceBusinessDate(expectedCurrent)`: um clique repetido depois que a data andou não fecha o dia seguinte.

- [ ] **Step 1: Testes que falham** (`EndOfDayRunCommandsTest`, contexto dos testes do motor, chamando `createAutomatic` direto; `settle` no `finally`): `createAutomatic creates a RUNNING run with the registered steps and is audited as the system`; `a completed date is END_OF_DAY_ALREADY_CLOSED`; `a running or failed date is END_OF_DAY_RUN_EXISTS with runId`; `another date is END_OF_DAY_DATE_MISMATCH with currentBusinessDate`; e de unidade `createAutomatic with an empty registry fails with END_OF_DAY_NOT_CONFIGURED`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRunCommandsTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando e `./gradlew test --tests 'com.zeny.hotel.architecture.*'`.
- [ ] **Step 5: Commit** `feat(endofday): criação auditada da execução do fechamento`.

### Task 9: Fechamento manual com step-up

**Estimativa:** 40 min · **Depende de:** Task 8 · **Risco:** alto (contrato público e segurança)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDayService.kt`, `web/EndOfDayController.kt`
- Modify: `src/main/resources/application.yaml`, `api/openapi.yaml` (gerado), `README.md` (seção da API: fechamento do dia)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRunsApiTest.kt`

**Interfaces:**
- Consumes: `EndOfDayRunCommands.createManually` (Task 8), `EndOfDayEngine.execute` (Task 5).
- Produces:
  - `EndOfDayService.startManually(businessDate, approvalToken): EndOfDayRun` (sem transação): `commands.createManually` e, depois do commit, `engine.execute(id, zeny.endofday.manual-wait)`; devolve a execução relida, **no estado em que estiver**. A violação de `run_business_date_uk` de uma criação concorrente vira `END_OF_DAY_RUN_EXISTS` aqui, fora da transação desfeita.
  - `POST /api/v1/end-of-day/runs` (tag `end-of-day`), corpo `{ "businessDate": "AAAA-MM-DD" }` obrigatório, `@Idempotent`, cabeçalho de aprovação opcional → `201` com `Location` e `EndOfDayRunResponse`. **Descrição no contrato:** a resposta traz a execução no estado em que estiver; `RUNNING` quer dizer que o fechamento continua (ou que outro disparo o executa), e o cliente acompanha pelo tópico `end-of-day` ou por `GET …/runs/{id}`.
  - `EndOfDayRunResponse`: `id`, `businessDate`, `trigger`, `status`, `triggeredBy` (id ou nulo), `startedAt`, `finishedAt`, `version`, `steps[]` (`name`, `position`, `status`, `startedAt`, `finishedAt`, `result`, `attempts`); `trigger`, `status` e `steps[].status` com `@field:ExtensibleEnum`.
  - Propriedade `zeny.endofday.manual-wait: PT30S`.
- **Ruling registrada (resposta):** `201` síncrono com espera limitada e estado como estiver (decisão I-6 da revisão do plano; alternativa descartada: `202` assíncrono desde já). Vai às "Decisões para o usuário".

- [ ] **Step 1: Testes que falham** (`EndOfDayRunsApiTest`, contexto dos testes do motor; aprovação como no `ApprovalTest`; `settle` no `finally`):
  - `without the permission the manual run requires approval`: `403 APPROVAL_REQUIRED` com `action = endofday.run.start`, `permission = endofday.runs.execute`, `resourceRef = endofday.business-date:<data>`; nenhuma execução.
  - `without the permission the 409s are not revealed`: com uma execução `COMPLETED` na data, o usuário sem permissão recebe `403 APPROVAL_REQUIRED`, não `409`.
  - `with an approval the run executes and the trail has requester and approver`: `201`, `COMPLETED`, quatro etapas `COMPLETED`; a trilha de `endofday.run.start` tem o solicitante como autor e o `approval_id` do aprovador.
  - `a manager runs it directly`: `201` sem token.
  - `a new run for a closed date is a 409 with a stable code`: depois de uma `COMPLETED` (a data andou pelo `fake-advance`), `POST` com a data fechada → `409 END_OF_DAY_ALREADY_CLOSED`; com uma data futura → `409 END_OF_DAY_DATE_MISMATCH` com `currentBusinessDate`.
  - `a failed run is reported as existing`: `failNext("fake-three", 1)`; `POST` → `201` com `FAILED`; novo `POST` mesma data → `409 END_OF_DAY_RUN_EXISTS` com `runId`.
  - `a run held by another executor is returned as RUNNING`: o teste segura `end-of-day-run`; `POST` → `201` com `status = RUNNING` e etapas `PENDING`; solta a trava e `settle`.
  - `two simultaneous manual runs for the same date produce one run`: duas threads com permissão e `Idempotency-Key` diferentes, liberadas por `CountDownLatch`; uma `201`, a outra `409` (`END_OF_DAY_RUN_EXISTS` ou `END_OF_DAY_ALREADY_CLOSED`); uma linha em `endofday.run` para a data; cada fake 1 vez.
  - `repeating the POST with the same idempotency key returns the stored response`; sem `Idempotency-Key` → `400 IDEMPOTENCY_KEY_REQUIRED`.
  - `missing businessDate is a validation error`: `400 VALIDATION_FAILED` no campo `businessDate`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRunsApiTest'`.
- [ ] **Step 3: Implementar;** `./gradlew updateOpenApi`; README (endpoint, permissões, códigos, resposta no estado em que estiver, rulings).
- [ ] **Step 4: Ver passar:** o comando do Step 2, `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` (sem `ERR`).
- [ ] **Step 5: Commit** `feat(endofday): fechamento manual com aprovação de superior`.

### Task 10: Retomada manual com step-up

**Estimativa:** 35 min · **Depende de:** Task 9 · **Risco:** alto (contrato público e segurança)

**Files:**
- Modify: `application/EndOfDayRunCommands.kt`, `application/EndOfDayService.kt`, `web/EndOfDayController.kt`, `api/openapi.yaml` (gerado), `README.md`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayResumeApiTest.kt`

**Interfaces:**
- Produces: `EndOfDayRunCommands.resumeManually(runId, approvalToken): EndOfDayRun` (`@Audited` `endofday.run.resume`; primeiro `requireOrApproval(SensitiveAction("endofday.run.resume", RUNS_EXECUTE, "endofday.run:<id>"), token)`; inexistente → `END_OF_DAY_RUN_NOT_FOUND`; `COMPLETED` → `END_OF_DAY_RUN_NOT_RESUMABLE`; `FAILED` → `resume`; `RUNNING` (interrompida) aceita sem mudança); `EndOfDayService.resumeManually` executa depois do commit e mapeia `Busy` para `END_OF_DAY_RUN_IN_PROGRESS`; `POST /api/v1/end-of-day/runs/{id}/resume`, `@Idempotent`, sem corpo → `200` com `EndOfDayRunResponse` no estado em que estiver. **Exceção registrada à convenção de `If-Match`:** a retomada não leva versão, porque a trava e o `END_OF_DAY_RUN_NOT_RESUMABLE` já cobrem a corrida e o cliente não tem o que comparar.

- [ ] **Step 1: Testes que falham:** sem permissão → `403 APPROVAL_REQUIRED` (`action = endofday.run.resume`, `resourceRef = endofday.run:<id>`); com aprovação → `200 COMPLETED`, trilha com solicitante e aprovador, contadores de `fake-one`/`fake-two` = 1; de novo → `409 END_OF_DAY_RUN_NOT_RESUMABLE`; id desconhecido com permissão → `404 END_OF_DAY_RUN_NOT_FOUND`; com a trava segura pelo teste → `409 END_OF_DAY_RUN_IN_PROGRESS`; mesma `Idempotency-Key` → mesma resposta.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayResumeApiTest'`.
- [ ] **Step 3: Implementar;** `./gradlew updateOpenApi`; README.
- [ ] **Step 4: Ver passar** o comando acima, `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh`.
- [ ] **Step 5: Commit** `feat(endofday): retomada manual do fechamento com aprovação de superior`.

### Task 11: Consultas de execuções, etapas e pendências

**Estimativa:** 30 min · **Depende de:** Task 10 · **Risco:** alto (entrada externa, autorização e contrato)

**Files:**
- Modify: `application/EndOfDayService.kt`, `web/EndOfDayController.kt`, `infrastructure/EndOfDayRunRepository.kt`, `api/openapi.yaml` (gerado), `README.md`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayQueriesApiTest.kt`

**Interfaces:**
- Produces (todas exigem `endofday.runs.read`, sem filtros além do cursor): `GET /api/v1/end-of-day/runs` (cursor; data mais nova primeiro, desempate pelo id); `GET /api/v1/end-of-day/runs/{id}` (`ETag` da versão, etapas na ordem); `GET /api/v1/end-of-day/runs/{id}/issues` (cursor por `createdAt`, id) com `EndOfDayIssueResponse` (`id`, `runId`, `stepName`, `kind`, `reference`, `message`, `resolved`, `createdAt`).

- [ ] **Step 1: Testes que falham:** sem token `401`; sem permissão `403 PERMISSION_DENIED`; lista com `limit=1` e `nextCursor`; detalhe com `ETag` e etapas na ordem; pendências com a `STEP_FAILED` e a do fake; id desconhecido `404 END_OF_DAY_RUN_NOT_FOUND`; cursor adulterado `400 INVALID_CURSOR`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayQueriesApiTest'`.
- [ ] **Step 3: Implementar;** `./gradlew updateOpenApi`; README.
- [ ] **Step 4: Ver passar** o comando acima e `./gradlew checkOpenApi`.
- [ ] **Step 5: Commit** `feat(endofday): consultas das execuções e pendências do fechamento`.

### Task 12: Gatilho automático no horário do hotel

**Estimativa:** 45 min · **Depende de:** Tasks 10 e 11 · **Risco:** alto (fuso, concorrência e efeito de negócio)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/domain/EndOfDaySchedule.kt`, `application/EndOfDayTrigger.kt`, `infrastructure/EndOfDayTriggerJob.kt`
- Modify: `src/main/resources/application.yaml` (`zeny.endofday.trigger.interval: PT1M`, `initial-delay: PT1M`, `catch-up-window: PT24H`); o adiamento nos testes (abaixo)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayScheduleTest.kt` (unidade), `EndOfDayTriggerTest.kt`

**Interfaces:**
- Produces:
  - `object EndOfDaySchedule { fun dueAt(businessDate: LocalDate, endOfDayTime: LocalTime, zone: ZoneId): Instant }` — **regra:** horário antes de 12:00 → `businessDate + 1 dia` nesse horário (madrugada seguinte, como o padrão 03:00); 12:00 ou depois → no próprio `businessDate`; convertido por `ZonedDateTime.of` no `zone`. Alternativa descartada: sempre `+1 dia`, que atrasaria quase um dia o fechamento configurado para 23:30.
  - `@Component class EndOfDayTrigger { fun tick(): TickOutcome }`, `enum class TickOutcome { NOT_DUE, OUTSIDE_WINDOW, NO_STEPS, STARTED, RESUMED, WAITING_MANUAL_RESUME, ALREADY_CLOSED, BUSY }`: lê data corrente, `endOfDayTime` e fuso do `HotelApi`; `now < dueAt` → `NOT_DUE`; `now >= dueAt + catch-up-window` → `OUTSIDE_WINDOW` (nada roda sozinho; uma vez por data e processo, `WARN` e `notify(END_OF_DAY_PENDING)` para `RUNS_READ` com o texto `"OUTSIDE_WINDOW: fechamento automático fora da janela; execute manualmente"`); registro vazio → `NO_STEPS` (no máximo um `WARN` por data); sem execução → `commands.createAutomatic` + `engine.execute` → `STARTED`; corrida perdida na criação (`END_OF_DAY_RUN_EXISTS`, `END_OF_DAY_ALREADY_CLOSED` ou `END_OF_DAY_DATE_MISMATCH`) → relê e segue pelo estado; `RUNNING` (interrompida) → `engine.execute` → `RESUMED`; `FAILED` → `WAITING_MANUAL_RESUME`; `COMPLETED` → `ALREADY_CLOSED`; `Busy` → `BUSY`. O `tick` espera a execução até o fim (`waitAtMost` = `lock-at-most-for`).
  - `EndOfDayTriggerJob`: `@Scheduled(fixedDelayString = "\${zeny.endofday.trigger.interval}", initialDelayString = "\${zeny.endofday.trigger.initial-delay}")` + `@SchedulerLock(name = "end-of-day-trigger", lockAtMostFor = "PT10M")`, sem retorno; loga `STARTED`/`RESUMED`.
  - **Adiamento em todo contexto de teste:** `src/test/resources/config/application.yaml` com `zeny.endofday.trigger.initial-delay: PT24H` (vale para todo contexto, inclusive os montados à mão como o `JwtKeysTest`); se o diretório já tiver arquivo que conflite, use a constante `END_OF_DAY_TRIGGER_DEFERRED` no `@IntegrationTest`, no `@EndOfDayIntegrationTest` e em todo contexto montado à mão (`grep -rl 'SpringApplication\|SpringBootTest' src/test`). Um teste confere que a propriedade vale num contexto `@IntegrationTest`.
  - **Ruling registrada (I-8 da revisão do plano):** fora da janela de 24 h o fechamento é só manual, porque fechar sozinho datas atrasadas lança no-show com multa (RN-15, imutável pela RN-03) e, na E14, envia à FNRH hóspedes que podem ter chegado com o servidor fora do ar. Vai às "Decisões para o usuário".
  - **Ruling registrada (M-3):** `FAILED` espera a retomada manual. Alternativa descartada: 1 a 3 novas tentativas automáticas com espera; a falha já avisa (pendência e alerta) e quem corrige retoma.
  - A documentação da regra (descrição de `endOfDayTime`, README e ARCHITECTURE §13) fica com a Task 14.

- [ ] **Step 1: Testes de unidade que falham** (`EndOfDayScheduleTest`): `03:00 in America/Manaus is due at 07:00Z of the next day` (`2026-11-12`, `03:00` → `2026-11-13T07:00:00Z`); `23:30 in Asia/Tokyo is due the same day` (→ `2026-11-12T14:30:00Z`); `12:00 is the same day`; `11:59 is the next day`; `the JVM default zone does not matter`.
- [ ] **Step 2: Testes de integração que falham** (`EndOfDayTriggerTest`, contexto único; o teste troca o fuso do hotel isolado para `America/Manaus` pelo `PUT /api/v1/hotel` e o devolve no fim; relógio devolvido; `settle`):
  - `before the due time nothing runs` (1 min antes → `NOT_DUE`).
  - `at the due time in the hotel zone the run starts automatically` (`STARTED`; `AUTOMATIC`, `COMPLETED`, `triggeredBy` nulo, trilha `endofday.run.start` com ator sistema).
  - `an interrupted run is resumed by the trigger` (`RESUMED`; etapa concluída não reexecutada).
  - `a failed run waits for the manual resume` (`WAITING_MANUAL_RESUME`, contadores inalterados).
  - `after the catch-up window nothing runs automatically` (relógio em `dueAt + 25 h` → `OUTSIDE_WINDOW`, nenhuma execução, uma notificação `OUTSIDE_WINDOW` e só uma em dois `tick()`); o `POST` manual continua funcionando.
  - `the trigger and a manual run at the same time produce one run` (threads com `tick()` e `POST`, `CountDownLatch`; uma execução; o `POST` recebe `201` com qualquer estado ou `409`).
  - `an interrupted run outside the window waits for the manual resume` (execução `RUNNING` interrompida e relógio em `dueAt + 25 h` → `OUTSIDE_WINDOW`, execução intocada; a retomada manual a conclui).
  - `the trigger is deferred in every test context` (num `@IntegrationTest`, a propriedade `zeny.endofday.trigger.initial-delay` vale `PT24H`).
- [ ] **Step 3: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayScheduleTest' --tests 'com.zeny.hotel.endofday.EndOfDayTriggerTest'`.
- [ ] **Step 4: Implementar.**
- [ ] **Step 5: Ver passar** o comando acima e `EndOfDayIsolationTest`.
- [ ] **Step 6: Commit** `feat(endofday): gatilho automático no horário e fuso do hotel`.

### Task 13: Métricas e alertas do fechamento

**Estimativa:** 35 min · **Depende de:** Task 12 · **Risco:** alto (toca o motor; nomes das métricas são contrato com as regras)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/infrastructure/EndOfDayMetrics.kt`
- Modify: `application/EndOfDayEngine.kt` (duração e falha), `src/main/resources/application.yaml` (`zeny.endofday.metrics.refresh-interval: PT30S`), `docker/prometheus/alerts.yaml`, `README.md`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayMetricsTest.kt`

**Interfaces:**
- Produces:
  - Timer `zeny.endofday.run.duration`, tags `outcome` (`completed`|`failed`) e `resumed` (`true` se a execução tinha etapa concluída antes desta chamada), medido **por chamada** de `execute` que termina a execução → `zeny_endofday_run_duration_seconds_*`.
  - Counter `zeny.endofday.run.failures` → `zeny_endofday_run_failures_total`.
  - Gauges do `MeterBinder` (refresh como o `OutboxMetrics`, `NaN` se o banco não responder): `zeny.endofday.runs.failed` (execuções `FAILED`) e `zeny.endofday.overdue.seconds` (segundos desde o `dueAt` da data corrente sem execução `COMPLETED`; `0` antes do horário **e com o registro de etapas vazio**, para o alerta não disparar entre a E10 e a E11).
  - `docker/prometheus/alerts.yaml`, grupo `zeny-end-of-day`: `ZenyEndOfDayFailed` (`zeny_endofday_runs_failed > 0`, `for: 1m`, `critical`, "O fechamento do dia falhou"; descrição manda ver `GET /api/v1/end-of-day/runs/{id}/issues` e retomar), `ZenyEndOfDayNotRun` (`zeny_endofday_overdue_seconds > 3600`, `critical`, "O fechamento do dia não rodou até 1 hora depois do horário"), `ZenyEndOfDayMetricsUnavailable` (`zeny_endofday_overdue_seconds != zeny_endofday_overdue_seconds`, `for: 5m`, `warning`); comentário: a entrega a uma pessoa é da M8 E18.

- [ ] **Step 1: Testes que falham** (`EndOfDayMetricsTest`, contexto único): depois de uma `COMPLETED`, `zeny_endofday_run_duration_seconds_count` com `outcome="completed"` aumentou; depois de uma `FAILED`, `zeny_endofday_run_failures_total` aumentou e, após o refresh (chamado direto), `zeny_endofday_runs_failed` ≥ 1; com o relógio 2 h depois do `dueAt` da data corrente sem execução, `zeny_endofday_overdue_seconds` ≥ 7200; unidade do binder com registro vazio → `0`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayMetricsTest'`.
- [ ] **Step 3: Implementar;** conferir as regras com `docker run --rm --entrypoint promtool -v "$PWD/docker/prometheus:/rules:ro" prom/prometheus:latest check rules /rules/alerts.yaml` (a imagem já está na máquina) → `SUCCESS: 8 rules found`; README (métricas e alertas).
- [ ] **Step 4: Ver passar** o comando do Step 2 e `./gradlew test --tests 'com.zeny.hotel.shared.web.ActuatorExposureTest'`.
- [ ] **Step 5: Commit** `feat(endofday): métricas e alertas do fechamento do dia`.

### Task 14: Documentação do gatilho e do horário devido

**Estimativa:** 20 min · **Depende de:** Task 13 · **Risco:** alto (descrição no contrato do `hotel`)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/hotel/web/HotelController.kt` (só a descrição `@Schema` de `endOfDayTime`), `api/openapi.yaml` (gerado), `README.md`, `docs/ARCHITECTURE.md` §13

**Interfaces:**
- Produces: a descrição de `endOfDayTime` diz a regra do horário devido (antes de 12:00 → madrugada seguinte; 12:00 ou depois → o próprio dia operacional) e que o automático só age até 24 h depois desse horário; o README (seção do fechamento) repete a regra e a janela, e diz que execução interrompida ou `FAILED` fora da janela só volta pela retomada manual e que `FAILED` nunca é repetida sozinha; a ARCHITECTURE §13, linha "Fechamento do dia", registra o gatilho de 1 min que decide pelo horário no fuso do hotel e pela janela, as travas `end-of-day-trigger` e `end-of-day-run` (com *keep-alive*), a exclusividade, a retomada e o `FAILED` manual.

- [ ] **Step 1: Implementar;** `./gradlew updateOpenApi`; conferir no `api/openapi.yaml` que a descrição de `endOfDayTime` tem `12:00` e `24 h`.
- [ ] **Step 2: Ver passar:** `./gradlew test --tests 'com.zeny.hotel.hotel.*'`, `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` (sem `ERR`).
- [ ] **Step 3: Commit** `docs(endofday): regra do horário devido e janela do fechamento automático`.

---

## Verificação da etapa (orquestrador)

- `./gradlew build` → `BUILD SUCCESSFUL` (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi`, `koverVerify`).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- `promtool check rules` → `SUCCESS`.
- Critérios de aceite da E10 ↔ testes: ordem e registro (Task 5); falha na 3ª e retomada sem reexecutar (Tasks 5 e 10); dois disparos simultâneos (Tasks 4, 9 e 12); 409 com código estável (Tasks 8 e 9); `403 APPROVAL_REQUIRED` e auditoria com aprovador (Tasks 9 e 10); horário no fuso do hotel (Task 12); SSE (Task 7); notificação de pendência (Task 6); métricas e regra de alerta (Task 13); build e contrato (acima).
- Notas para o handoff da E11: o contexto `EndOfDayTestConfiguration` tem etapas falsas nas posições 10–40 e precisa ser revisto quando entrarem as reais (posições de 100 em 100); o banco isolado acumula estado entre testes (asserções só sobre os próprios dados); pendências por saída pendente geram uma notificação por usuário com `endofday.runs.read` (considerar agrupar por execução e etapa); o contrato de etapa (KDoc de `EndOfDayStepHandler`) é obrigatório para as etapas de negócio.

# M6 E10 — Motor do fechamento do dia: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** criar o módulo `endofday` com o motor do fechamento do dia (execução com etapas persistidas, ordenada, retomável, única por data e nunca em paralelo), os gatilhos automático (horário do hotel, no fuso dele) e manual (com step-up), o andamento no SSE, o aviso de pendência pelo `notifications`, as métricas e os alertas, e o apoio de testes isolado (H10) que E11, E13, E14 e o gate reutilizam.

**Architecture:** o `endofday` (camada Operação) depende só de `shared`, `hotel` e `notifications` nesta etapa (subconjunto da linha da ARCHITECTURE §3.1). Uma execução (`EndOfDayRun`) é criada por data operacional com a lista ordenada das etapas registradas (beans `EndOfDayStepHandler`) copiada como linhas `PENDING`; a criação é única por data (restrição única no banco) e a execução é exclusiva pela trava do ShedLock `end-of-day-run` com *keep-alive*, de modo que uma queda solta a trava e o próximo disparo retoma a etapa pendente. Cada etapa roda numa transação própria que inclui a marca `COMPLETED`; a falha marca a etapa e a execução `FAILED`, registra uma `EndOfDayIssue` e avisa quem acompanha o fechamento.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, ShedLock 7.10.1 (`KeepAliveLockProvider` e `DefaultLockingTaskExecutor` do `shedlock-core`, já no classpath), Micrometer/Prometheus, Testcontainers 2 (PostgreSQL 18), Kotest assertions/JUnit 5.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E10 — Motor do fechamento do dia" (objetivo, escopo, critérios de aceite, notas para o plano, H8 e H10), com `docs/SPEC.md` §5.13 (RF-FEC-01, RF-FEC-03), RN-21, §8.2.9, RF-ACS-08, RF-CFG-04, RF-NOT-01, e `docs/ARCHITECTURE.md` §3.1, §6.3, §10, §11, §13 e §16.

## Global Constraints

- Grafo de módulos e regra por camadas da ARCHITECTURE §3.1/§3.2: `endofday` declara `allowedDependencies = ["shared", "hotel", "notifications"]` e usa só a API pública da raiz de cada um; a linha da §3.1 já lista um superconjunto, então a tabela não muda. `notifications` nunca escuta evento de negócio: o `endofday` o chama.
- RN-21: uma única execução de fechamento por data, nunca em paralelo; cada etapa idempotente (o motor não reexecuta etapa `COMPLETED`).
- Ações sensíveis com step-up (RF-ACS-08): fechamento manual e retomada manual usam `Authorization.requireOrApproval` com permissão `sensitive = true`.
- `HotelApi.advanceBusinessDate(expectedCurrent)` é o único caminho de avanço da data; o motor não o chama nesta etapa (é a última etapa de negócio, E11). "Data fechada" deriva da execução `COMPLETED` da data; a data operacional continua só em `hotel`.
- Migrações novas em `src/main/resources/db/migration/endofday/`, com versão posterior a `V2026_11_11_1001` (`outOfOrder` desligado): use `V2026_11_12_1000__create_endofday_tables.sql` e `V2026_11_12_1001__grant_endofday_to_app_role.sql`.
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca à mão); enum de resposta que cresce sai como `x-extensible-enum` (`@field:ExtensibleEnum`); a API não expõe `DELETE`.
- Erros de domínio: subclasses de `DomainException` com `ErrorCode` estável declarado na raiz do módulo (`EndOfDayErrorCodes`); mensagens em pt-BR sem dado pessoal. Código, API e banco em inglês; KDoc, mensagens e commits em pt-BR.
- Notificação e SSE nunca levam dado pessoal de hóspede (RN-13; nota do `notifications`): título e texto só com data, nome da etapa e referências opacas; a mensagem SSE é só `type`, `id`, `version`.
- Jobs: `@Scheduled` + `@SchedulerLock(name = …)`, método sem retorno primitivo; o ShedLock usa a hora do banco. Todo job novo é adiado nos testes por uma constante `…_DEFERRED` aplicada ao `@IntegrationTest`, ao `@EndOfDayIntegrationTest` e a todo contexto montado à mão que já passa `RESUBMISSION_JOB_DEFERRED`.
- Testes: banco compartilhado por todos os `@IntegrationTest`; nenhum teste desta etapa cria execução de fechamento no banco compartilhado — todos rodam no banco isolado da Task 1. O relógio avança pelo `MutableClock` e volta ao original no fim; a JVM roda em `America/Sao_Paulo`; o que o teste mudar na configuração do hotel isolado (fuso, horário) é devolvido no fim.
- Máquina de 14 GB: um Gradle por vez; nunca `./gradlew --stop`. Por isso as tarefas rodam em série (sem ondas paralelas).
- Commits de cada soul com o trailer `Soul: <soul>`.

## Review Focus

1. **Queda no meio da execução** (processo morre com uma etapa `RUNNING`): espera-se que o próximo disparo retome dessa etapa sem reexecutar as concluídas — teste na Task 4 (queda simulada) e na Task 8 (o gatilho retoma).
2. **Disparo manual e automático ao mesmo tempo para a mesma data:** uma execução só, sem 500 — teste de concorrência na Task 6 (dois `POST`) e na Task 8 (gatilho × `POST`).
3. **Duplo clique / repetição do `POST` depois de um timeout:** o cliente repete com a mesma `Idempotency-Key` e recebe a mesma resposta; sem a chave, `400` — teste na Task 6.
4. **Hotel com a data operacional atrasada vários dias (servidor desligado):** o gatilho fecha uma data por vez, cada uma só depois do horário dela — teste na Task 8.
5. **Horário do fechamento à tarde/noite (ex.: 23:30) e fuso diferente do da JVM:** o horário devido é calculado no fuso do hotel pela regra da Task 8, nunca em UTC nem no fuso da JVM — testes de unidade e de integração na Task 8.

---

## Mapa de arquivos

Produção (`src/main/kotlin/com/zeny/hotel/endofday/`):

| Arquivo | Responsabilidade | Task |
|---|---|---|
| `EndOfDayModule.kt` | declaração do módulo e dependências | 2 |
| `EndOfDayPermissions.kt` | `endofday.runs.read` e `endofday.runs.execute` (sensível) | 2 |
| `EndOfDayRealtime.kt` | tópico SSE `end-of-day` e tipo `endofday.run` | 5 |
| `EndOfDayErrorCodes.kt` | códigos de erro estáveis | 6 |
| `application/EndOfDayConfiguration.kt` | beans de permissões e do tópico | 2, 5 |
| `domain/EndOfDayRun.kt` | agregado `EndOfDayRun` com `EndOfDayRunStep` e `EndOfDayIssue`, máquina de estados | 3 |
| `infrastructure/EndOfDayRunRepository.kt` | persistência JPA e consultas | 3, 7 |
| `application/EndOfDaySteps.kt` | contrato de etapa, contexto, resultado e registro ordenado | 4 |
| `application/EndOfDayEngine.kt` | execução sob trava, transação por etapa, falha e retomada | 4, 5, 9 |
| `infrastructure/EndOfDayLockConfiguration.kt` | `LockingTaskExecutor` com *keep-alive* | 4 |
| `application/EndOfDayIssues.kt` | registro de pendência + aviso pelo `notifications` | 5 |
| `application/EndOfDayService.kt` | casos de uso manuais (iniciar, retomar) e consultas | 6, 7 |
| `web/EndOfDayController.kt` | `/api/v1/end-of-day/runs…` | 6, 7 |
| `domain/EndOfDaySchedule.kt` | horário devido de uma data no fuso do hotel | 8 |
| `application/EndOfDayTrigger.kt` | decisão do disparo automático (criar, retomar, nada) | 8 |
| `infrastructure/EndOfDayTriggerJob.kt` | `@Scheduled` + `@SchedulerLock("end-of-day-trigger")` | 8 |
| `infrastructure/EndOfDayMetrics.kt` | `MeterBinder` com as métricas | 9 |

Migrações: `src/main/resources/db/migration/endofday/V2026_11_12_1000__create_endofday_tables.sql`, `V2026_11_12_1001__grant_endofday_to_app_role.sql` (Task 3). Configuração: `src/main/resources/application.yaml` (Tasks 4, 8, 9). Alertas: `docker/prometheus/alerts.yaml` (Task 9). Contrato: `api/openapi.yaml` (Tasks 6, 7, gerado). Documentação: `README.md` (Tasks 6, 7, 9).

Testes (`src/test/kotlin/com/zeny/hotel/`): `testsupport/EndOfDayIntegrationTest.kt` (Task 1), `endofday/FakeEndOfDaySteps.kt` (Task 4), um arquivo de teste por task em `endofday/`.

---

### Task 1: Apoio de testes isolado do fechamento (H10)

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (é a base de isolamento que E11, E13, E14 e o gate reutilizam)

**Files:**
- Create: `src/test/kotlin/com/zeny/hotel/testsupport/EndOfDayIntegrationTest.kt`
- Test: `src/test/kotlin/com/zeny/hotel/testsupport/EndOfDayIsolationTest.kt`

**Interfaces:**
- Produces: a anotação `@EndOfDayIntegrationTest` (mesmas propriedades, profile, `@AutoConfigureMockMvc`, `@AutoConfigureMetrics` e inicializadores do `@IntegrationTest`, trocando `PostgresInitializer` por `EndOfDayDatabaseInitializer`); `object EndOfDayDatabase { const val NAME = "zeny_endofday"; val jdbcUrl: String }`; `class EndOfDayDatabaseInitializer : ApplicationContextInitializer<ConfigurableApplicationContext>`.

Decisão (H10): **banco próprio no mesmo container** (`PostgresContainer.instance`), criado uma vez por JVM. Os papéis são do cluster, então `zeny_app` já existe; o Flyway migra o banco novo como dono e as migrações dão os privilégios. Alternativa descartada: um segundo container (mais memória numa máquina de 14 GB e mais um boot do Testcontainers).

- [ ] **Step 1: Escrever os testes que falham** (`EndOfDayIsolationTest`, anotado com `@EndOfDayIntegrationTest`):
  - `the context is connected to the isolated database`: o `DataSource` do contexto tem a URL terminando em `/zeny_endofday` e o usuário `zeny_app`.
  - `advancing the business date here does not change the shared database`: lê a data do banco compartilhado por JDBC direto (`PostgresContainer.instance.jdbcUrl`, dono, `SELECT business_date FROM hotel.business_day`), chama `hotelApi.advanceBusinessDate(hotelApi.currentBusinessDate())` no contexto isolado e confere que a data isolada andou 1 dia e a compartilhada não mudou.
  - `the app role has no DDL in the isolated database`: `CREATE TABLE public.x(i int)` pelo `DataSource` do contexto falha com `SQLException` (permissão negada) e `CREATE TEMP TABLE` também falha.
  - `both integration annotations carry the same test properties`: por reflexão, o `properties` do `@SpringBootTest` meta-anotado em `@EndOfDayIntegrationTest` é igual ao do `@IntegrationTest` (evita que um job adiado num falte no outro).
- [ ] **Step 2: Rodar e ver falhar:** `./gradlew test --tests 'com.zeny.hotel.testsupport.EndOfDayIsolationTest'` → falha de compilação (anotação inexistente).
- [ ] **Step 3: Implementar** `EndOfDayIntegrationTest.kt`: `EndOfDayDatabase` cria o banco por `lazy` sincronizado, conectando como dono ao banco padrão do container (`CREATE DATABASE zeny_endofday`, ignorando "já existe"), e no banco novo aplica o mesmo que `docker/postgres/initdb/10-create-app-role.sh` faz por banco: `REVOKE TEMPORARY ON DATABASE zeny_endofday FROM PUBLIC; GRANT CONNECT ON DATABASE zeny_endofday TO zeny_app; REVOKE CREATE ON SCHEMA public FROM PUBLIC`. O inicializador aplica as mesmas propriedades do `PostgresInitializer`, com a URL do banco novo. KDoc em pt-BR explicando H10: quem roda o fechamento com etapas reais usa esta anotação; os testes neste banco também o compartilham entre si (sempre partir de `HotelApi.currentBusinessDate()`).
- [ ] **Step 4: Rodar e ver passar** o mesmo comando → `BUILD SUCCESSFUL`, 4 testes.
- [ ] **Step 5: Commit** `test(endofday): apoio de testes isolado do fechamento (H10)` com `Soul: desenvolvedor`.

### Task 2: Módulo `endofday` e permissões

**Estimativa:** 25 min · **Depende de:** nenhuma · **Risco:** normal

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/EndOfDayModule.kt`, `EndOfDayPermissions.kt`, `application/EndOfDayConfiguration.kt`
- Modify: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayPermissionsTest.kt`

**Interfaces:**
- Produces: `object EndOfDayPermissions : ModulePermissions` com `module = "endofday"`, `RUNS_READ = PermissionCode("endofday.runs.read")` (escopo `ALL`, não sensível; modelos `MANAGER`, `RECEPTIONIST` e `FINANCE`) e `RUNS_EXECUTE = PermissionCode("endofday.runs.execute")` (`ALL`, `sensitive = true`, modelo `MANAGER`); `@Configuration class EndOfDayConfiguration` com `@Bean fun endOfDayPermissions(): ModulePermissions`.

- [ ] **Step 1: Testes que falham:** no `ModularityTest`, `endofday depends only on shared, hotel and notifications, and only on their public APIs` (mesmo formato do teste do `checklists`: dependências reais exatamente essas três e `allowedDependencies` igual ao conjunto); em `EndOfDayPermissionsTest` (`@IntegrationTest`), `GET /api/v1/permissions` como administrador lista as duas, `endofday.runs.execute` com `sensitive: true` e `endofday.runs.read` com `sensitive: false`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.endofday.EndOfDayPermissionsTest'`.
- [ ] **Step 3: Implementar** os três arquivos; KDoc do módulo no estilo do `PrintingModule` (o que faz, de quem depende, que nunca chama `hotel.advanceBusinessDate` fora da etapa de avanço da E11 e que nunca acessa `files`).
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): módulo e permissões do fechamento do dia`.

### Task 3: Esquema e agregado `EndOfDayRun`

**Estimativa:** 45 min · **Depende de:** Task 2 · **Risco:** alto (persistência e núcleo da etapa)

**Files:**
- Create: as duas migrações de "Global Constraints"; `src/main/kotlin/com/zeny/hotel/endofday/domain/EndOfDayRun.kt`; `infrastructure/EndOfDayRunRepository.kt` (as duas migrações contam como uma unidade, pela convenção criar + conceder do projeto)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRunTest.kt` (unidade), `EndOfDayRunPersistenceTest.kt` (`@EndOfDayIntegrationTest`)

**Interfaces:**
- Produces:
  - `@JvmInline value class EndOfDayRunId(val value: UUID)` (UUIDv7, como os demais ids).
  - `enum class RunTrigger { AUTOMATIC, MANUAL }`, `enum class RunStatus { RUNNING, FAILED, COMPLETED }`, `enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED }`.
  - `class EndOfDayRun` (entidade, tabela `endofday.run`): `id`, `businessDate: LocalDate` (único), `trigger`, `status`, `triggeredBy: UserId?`, `startedAt: Instant`, `finishedAt: Instant?`, `version: Long`, `steps: List<EndOfDayRunStep>` (ordenados por `position`); `companion fun start(businessDate, trigger, triggeredBy, stepNames: List<String>, now: Instant): EndOfDayRun` (status `RUNNING`, etapas `PENDING` na ordem dada, `stepNames` não vazio e sem repetição); `fun nextStep(): EndOfDayRunStep?` (a primeira não `COMPLETED`); `fun beginStep(name: String, now: Instant)`; `fun completeStep(name: String, result: String, now: Instant)` (se era a última, `status = COMPLETED` e `finishedAt`); `fun failStep(name: String, reason: String, now: Instant)` (etapa e execução `FAILED`, `finishedAt`); `fun resume(now: Instant)` (só de `FAILED`: volta a `RUNNING`, limpa `finishedAt`, a etapa `FAILED` volta a `PENDING`; de outro estado, `IllegalStateException`).
  - `class EndOfDayRunStep` (tabela `endofday.run_step`, PK `(run_id, position)`, `name` único por execução): `name`, `position`, `status`, `startedAt?`, `finishedAt?`, `result: String?` (até 200, código ou texto curto sem dado pessoal), `attempts: Int` (incrementa a cada `beginStep`).
  - `class EndOfDayIssue` (tabela `endofday.run_issue`): `id`, `runId`, `stepName: String?`, `kind: String` (código `UPPER_SNAKE`, até 60), `reference: String?` (até 120), `message: String` (até 500), `resolved: Boolean = false`, `createdAt`.
  - `interface EndOfDayRunRepository : JpaRepository<EndOfDayRun, EndOfDayRunId>` com `findByBusinessDate(date): EndOfDayRun?`; um repositório de `EndOfDayIssue` com `findByRunIdOrderByCreatedAtAscIdAsc(runId)`.
- Banco: `run.business_date` com `UNIQUE` (`run_business_date_uk`) — é a garantia de RN-21 contra duas criações simultâneas; `CHECK` dos valores de `trigger`, `status` e `step.status`; `finished_at` presente se e só se `status <> 'RUNNING'`. Grants ao `zeny_app`: `SELECT, INSERT, UPDATE` nas três tabelas (sem `DELETE`).

- [ ] **Step 1: Testes de unidade que falham** (`EndOfDayRunTest`): `start creates pending steps in the given order`; `complete of the last step completes the run`; `fail marks step and run failed`; `resume from failed reopens the failed step and keeps completed ones`; `resume from running or completed is refused`; `start refuses empty or duplicated step names`; `beginStep counts attempts`.
- [ ] **Step 2: Testes de integração que falham** (`EndOfDayRunPersistenceTest`, com `@EndOfDayIntegrationTest`; datas bem no futuro, como `currentBusinessDate().plusYears(50).plusDays(n)`, para não colidir com execuções reais dos outros testes do banco isolado): `a run round-trips with its steps in order`; `a second run for the same business date violates run_business_date_uk` (`DataIntegrityViolationException`); `the app role cannot delete runs` (`DELETE` por JDBC falha com permissão negada).
- [ ] **Step 3: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRun*'`.
- [ ] **Step 4: Implementar** migrações, agregado e repositórios (JPA como nos demais módulos; `@Version` para `version`).
- [ ] **Step 5: Ver passar** o mesmo comando.
- [ ] **Step 6: Commit** `feat(endofday): esquema e agregado da execução do fechamento`.

### Task 4: Motor — etapas, trava e retomada

**Estimativa:** 45 min · **Depende de:** Tasks 1 e 3 · **Risco:** alto (concorrência e núcleo)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDaySteps.kt`, `application/EndOfDayEngine.kt`, `infrastructure/EndOfDayLockConfiguration.kt`
- Modify: `src/main/resources/application.yaml` (`zeny.endofday.lock-at-most-for: PT5M`)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/FakeEndOfDaySteps.kt`, `EndOfDayEngineTest.kt`, `EndOfDayStepRegistryTest.kt`

**Interfaces:**
- Consumes: `EndOfDayRun`, repositórios (Task 3); `@EndOfDayIntegrationTest` (Task 1).
- Produces:
  - `interface EndOfDayStepHandler { val name: String; val position: Int; fun execute(context: EndOfDayStepContext): EndOfDayStepOutcome }` — `name` em kebab-case (`[a-z][a-z0-9-]{0,59}`), `position` positivo; KDoc: `execute` precisa ser idempotente (pode rodar de novo depois de uma queda) e roda dentro da transação que marca a etapa concluída.
  - `data class EndOfDayStepContext(val runId: EndOfDayRunId, val businessDate: LocalDate, val issues: EndOfDayIssueSink)`; `fun interface EndOfDayIssueSink { fun raise(kind: String, reference: String?, message: String) }` (implementação real na Task 5; nesta task o motor grava a `EndOfDayIssue` sem aviso).
  - `data class EndOfDayStepOutcome(val result: String) { companion object { val DONE = EndOfDayStepOutcome("DONE"); val NOT_APPLICABLE = EndOfDayStepOutcome("NOT_APPLICABLE") } }`.
  - `@Component class EndOfDayStepRegistry(handlers: List<EndOfDayStepHandler>)`: `val steps: List<EndOfDayStepHandler>` por `position`; `fun handler(name: String): EndOfDayStepHandler`; o construtor recusa nome ou posição repetidos (`IllegalStateException`, o boot falha).
  - `@Component class EndOfDayEngine`: `fun execute(runId: EndOfDayRunId): EndOfDayExecution`, com `sealed interface EndOfDayExecution { data class Finished(val status: RunStatus) : EndOfDayExecution; data object Busy : EndOfDayExecution }`.
  - `infrastructure/EndOfDayLockConfiguration`: bean `endOfDayLockingTaskExecutor: LockingTaskExecutor` = `DefaultLockingTaskExecutor(KeepAliveLockProvider(lockProvider, scheduledExecutor))`, sobre o `LockProvider` do `shared` (bean do tipo do ShedLock) e um `ScheduledExecutorService` de uma thread, fechado no `destroy`.
- Algoritmo do `execute` (o que os testes não determinam sozinhos):
  1. `executeWithLock` com `LockConfiguration(now, "end-of-day-run", lockAtMostFor, Duration.ZERO)`; trava ocupada → `Busy` sem tocar na execução.
  2. Sob a trava, numa transação de leitura: execução `COMPLETED` ou `FAILED` → `Finished(status)` sem executar nada (retomar é o `resume` do domínio, chamado por quem retoma antes de `execute`). `RUNNING` → segue: com a trava livre, uma execução `RUNNING` é uma nova ou uma interrompida por queda.
  3. Para cada `nextStep()`: transação A (`beginStep`, commit); transação B (`handler.execute(context)` + `completeStep(name, outcome.result)`, commit junto); exceção em B → B desfeita e transação C (`failStep(name, reason)`, onde `reason` é o nome da classe da exceção, sem a mensagem, que pode ter dado pessoal; mais a `EndOfDayIssue` `STEP_FAILED` com `reference = name`), log `WARN` com a exceção, devolve `Finished(FAILED)`.
  4. Etapa da execução sem handler registrado (deploy mudou) → trata como falha da etapa (`reason = "UNKNOWN_STEP"`).

- [ ] **Step 1: Apoio de teste** `FakeEndOfDaySteps.kt`: `@TestConfiguration class FakeEndOfDayStepsConfiguration` com quatro handlers — `fake-one` (1), `fake-two` (2), `fake-three` (3), `fake-advance` (4, chama `hotelApi.advanceBusinessDate(context.businessDate)`, para que cada teste deixe a data pronta para o próximo) — e um `FakeStepsProbe` (bean) com `invocations(step, runId): Int`, `failNext(step, times)` (lança `IllegalStateException` nas próximas N execuções da etapa) e `raiseIssueOn(step, kind)`. KDoc: só para contextos `@EndOfDayIntegrationTest @Import(FakeEndOfDayStepsConfiguration::class)`; quando a E11 registrar etapas reais, este contexto passa a ter as duas e precisa ser revisto (nota para o handoff).
- [ ] **Step 2: Testes que falham** (`EndOfDayEngineTest`, `@EndOfDayIntegrationTest @Import(FakeEndOfDayStepsConfiguration::class)`; cada teste cria a execução da data corrente com `EndOfDayRun.start(...)` e os nomes do registro):
  - `runs all steps in order and records start, finish and result of each`: `Finished(COMPLETED)`; as quatro etapas `COMPLETED`, `startedAt <= finishedAt`, `result = "DONE"`, início de cada uma ≥ fim da anterior; cada handler chamado 1 vez.
  - `a failure in the third step leaves the run resumable and resuming does not rerun the first two`: `failNext("fake-three", 1)`; `execute` → `Finished(FAILED)`, `fake-three` `FAILED`, uma `EndOfDayIssue` `STEP_FAILED` com `reference = "fake-three"`; `run.resume(now)` em transação + `execute` → `COMPLETED`; contadores: `fake-one` 1, `fake-two` 1, `fake-three` 2, `fake-advance` 1; `attempts` de `fake-three` = 2.
  - `a crash mid-run is resumed from the interrupted step`: grava a execução com `fake-one` `COMPLETED` e `fake-two` `RUNNING` (simula a queda: nada segura a trava), chama `execute` → `COMPLETED`; `fake-one` não chamado, `fake-two` chamado 1 vez.
  - `a busy lock returns Busy and touches nothing`: o teste segura `end-of-day-run` pelo `LockProvider` e `execute` devolve `Busy`; a execução continua com as etapas `PENDING`; solta a trava no `finally`.
  - `a completed run is not executed again`: `execute` de novo depois de `COMPLETED` → `Finished(COMPLETED)`, contadores inalterados.
  - `EndOfDayStepRegistryTest` (unidade): ordena por `position`; nome repetido e posição repetida recusados.
- [ ] **Step 3: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayEngineTest' --tests 'com.zeny.hotel.endofday.EndOfDayStepRegistryTest'`.
- [ ] **Step 4: Implementar** os três arquivos e a propriedade.
- [ ] **Step 5: Ver passar** o mesmo comando.
- [ ] **Step 6: Commit** `feat(endofday): motor do fechamento com trava e retomada`.

### Task 5: Pendências com aviso e andamento no SSE

**Estimativa:** 35 min · **Depende de:** Task 4 · **Risco:** normal

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/EndOfDayRealtime.kt`, `application/EndOfDayIssues.kt`
- Modify: `application/EndOfDayEngine.kt`, `application/EndOfDayConfiguration.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayProgressTest.kt`

**Interfaces:**
- Produces: `object EndOfDayRealtime { val TOPIC = RealtimeTopic("end-of-day", EndOfDayPermissions.RUNS_READ); const val RUN = "endofday.run" }` e o bean do tópico na `EndOfDayConfiguration`; `@Component class EndOfDayIssues` com `fun raise(runId, businessDate, stepName: String?, kind: String, reference: String?, message: String): EndOfDayIssue`, que grava a pendência e, na mesma transação, chama `NotificationsApi.notify(NotificationsTypes.END_OF_DAY_PENDING` (de `notifications/NotificationTypes.kt`), título, texto, NotificationRecipients.withPermission(EndOfDayPermissions.RUNS_READ))` — título `"Pendência no fechamento de dd/MM/yyyy"`, texto `"<kind>: <reference ou —>"` limitado a 1000; nunca a `message` da etapa (pode ter dado de hóspede no futuro: ela fica só na API, para quem tem a permissão). O sink do contexto passa a usar `EndOfDayIssues`, e a pendência `STEP_FAILED` do motor também (H8).
- O motor publica `RealtimeMessage(EndOfDayRealtime.RUN, run.id.value, run.version)` no tópico em cada transação que muda a execução (início de etapa, conclusão, falha, conclusão da execução), sem `owners`.

- [ ] **Step 1: Testes que falham** (`EndOfDayProgressTest`, mesmo contexto dos fakes; um usuário com `endofday.runs.read` e outro sem, criados pelo `IamTestSupport`):
  - `an issue raised by a step notifies who follows the end of day`: `raiseIssueOn("fake-two", "TEST_PENDING")`; depois do `execute`, `GET /api/v1/notifications` do usuário com a permissão tem uma do tipo `notifications.end-of-day-pending` cujo texto contém `TEST_PENDING` e não contém a `message`; o usuário sem a permissão não recebe.
  - `a failed step raises STEP_FAILED and notifies`: idem com `failNext`.
  - `progress is published on the end-of-day topic`: as mensagens do tópico `end-of-day` publicadas durante o `execute` (leia o histórico do `RealtimeHistory` do `shared` a partir da posição anterior, ou o `SseTestClient` se o histórico não for acessível) têm `type = "endofday.run"`, `id` = a execução e versões crescentes, e são pelo menos uma por etapa.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayProgressTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o comando acima e `EndOfDayEngineTest`.
- [ ] **Step 5: Commit** `feat(endofday): pendências avisadas e andamento no tempo real`.

### Task 6: Fechamento e retomada manuais com step-up

**Estimativa:** 45 min · **Depende de:** Task 5 · **Risco:** alto (contrato público e segurança)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/EndOfDayErrorCodes.kt`, `application/EndOfDayService.kt`, `web/EndOfDayController.kt`
- Modify: `api/openapi.yaml` (gerado por `./gradlew updateOpenApi`), `README.md` (seção da API: fechamento do dia)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRunsApiTest.kt`

**Interfaces:**
- Consumes: `EndOfDayEngine.execute`, `EndOfDayStepRegistry`, `EndOfDayRun` e repositórios, `HotelApi.currentBusinessDate()`, `Authorization.requireOrApproval`, `@Audited`.
- Produces:
  - `object EndOfDayErrorCodes`: `END_OF_DAY_RUN_NOT_FOUND` (404), `END_OF_DAY_ALREADY_CLOSED` (409: a data já tem execução `COMPLETED`), `END_OF_DAY_RUN_EXISTS` (409: a data tem execução `RUNNING` ou `FAILED`; o problema leva `runId`), `END_OF_DAY_DATE_MISMATCH` (409: a data pedida não é a data operacional corrente; o problema leva `currentBusinessDate`), `END_OF_DAY_NOT_CONFIGURED` (409: nenhuma etapa registrada; ver ruling abaixo), `END_OF_DAY_RUN_NOT_RESUMABLE` (409: execução `COMPLETED`), `END_OF_DAY_RUN_IN_PROGRESS` (409: outra execução segura a trava).
  - `EndOfDayService`: `fun create(businessDate: LocalDate, trigger: RunTrigger, triggeredBy: UserId?): EndOfDayRun` (transacional, auditado `endofday.run.start`; confere, nesta ordem: execução da data pedida `COMPLETED` → `END_OF_DAY_ALREADY_CLOSED`, `RUNNING`/`FAILED` → `END_OF_DAY_RUN_EXISTS`, data diferente da corrente → `END_OF_DAY_DATE_MISMATCH`, registro vazio → `END_OF_DAY_NOT_CONFIGURED`; a violação de `run_business_date_uk` de uma criação concorrente vira `END_OF_DAY_RUN_EXISTS`); `fun startManually(businessDate: LocalDate, approvalToken: String?): EndOfDayRun` (na mesma transação de `create`: `requireOrApproval(SensitiveAction("endofday.run.start", RUNS_EXECUTE, "endofday.business-date:<data ISO>"), approvalToken)`; depois do commit, `engine.execute`); `fun resumeManually(runId, approvalToken): EndOfDayRun` (transação: `requireOrApproval(SensitiveAction("endofday.run.resume", RUNS_EXECUTE, "endofday.run:<id>"), token)`, `run.resume` se `FAILED`, recusa `COMPLETED`, aceita `RUNNING` (interrompida); auditado `endofday.run.resume`; depois do commit, `engine.execute`; `Busy` → `END_OF_DAY_RUN_IN_PROGRESS`). `create` é público no módulo para o gatilho da Task 8 (ator sistema).
  - Endpoints (`EndOfDayController`, tag `end-of-day`): `POST /api/v1/end-of-day/runs` com `{ "businessDate": "AAAA-MM-DD" }` (obrigatório), `@Idempotent`, cabeçalho de aprovação opcional → `201` com `Location` e o `EndOfDayRunResponse` já executado (execução síncrona: o hotel tem até 30 UHs); `POST /api/v1/end-of-day/runs/{id}/resume`, `@Idempotent` → `200` com o `EndOfDayRunResponse`.
  - `EndOfDayRunResponse`: `id`, `businessDate`, `trigger`, `status`, `triggeredBy` (id ou nulo), `startedAt`, `finishedAt`, `version`, `steps[]` (`name`, `position`, `status`, `startedAt`, `finishedAt`, `result`, `attempts`); `trigger`, `status` e `steps[].status` com `@field:ExtensibleEnum`.
- **Ruling registrada (sem etapas registradas):** enquanto a E11 não registra etapas reais, uma execução sem etapas fecharia a data sem avançá-la. Por isso `create` recusa com `END_OF_DAY_NOT_CONFIGURED` e o gatilho automático (Task 8) não faz nada. Alternativa descartada: deixar criar a execução vazia (marcaria a data como fechada sem fechamento real).
- **Ruling registrada (data no corpo):** o `POST` exige a data que o operador pretende fechar e recusa outra (`END_OF_DAY_DATE_MISMATCH`), como `advanceBusinessDate(expectedCurrent)`: um clique repetido depois que a data andou não fecha o dia seguinte.

- [ ] **Step 1: Testes que falham** (`EndOfDayRunsApiTest`, `@EndOfDayIntegrationTest @Import(FakeEndOfDayStepsConfiguration::class)`, usuários pelo `IamTestSupport`, aprovação como no `ApprovalTest`):
  - `without the permission the manual run requires approval`: usuário sem `endofday.runs.execute` → `403 APPROVAL_REQUIRED` com `action = endofday.run.start`, `permission = endofday.runs.execute` e `resourceRef = endofday.business-date:<data>`; nenhuma execução criada.
  - `with an approval the run executes and the trail has requester and approver`: aprovador com a permissão e PIN; `POST /api/v1/auth/approvals` → token; `POST …/runs` com o token → `201`, `status = COMPLETED`, quatro etapas `COMPLETED`; a trilha de `endofday.run.start` tem o solicitante como autor e o `approval_id` cujo aprovador é o aprovador.
  - `a manager runs it directly`: com a permissão, `201` sem token.
  - `a new run for a closed date is a 409 with a stable code`: depois de uma execução `COMPLETED` (a data avançou pelo `fake-advance`), `POST` com a data fechada → `409 END_OF_DAY_ALREADY_CLOSED`; `POST` com uma data futura sem execução → `409 END_OF_DAY_DATE_MISMATCH` com `currentBusinessDate`.
  - `a failed run is reported as existing and is resumed with step-up`: `failNext("fake-three", 1)`; `POST` → `201` com `status = FAILED`; novo `POST` mesma data → `409 END_OF_DAY_RUN_EXISTS` com `runId`; `POST …/resume` sem permissão → `403 APPROVAL_REQUIRED` (`action = endofday.run.resume`); com permissão → `200 COMPLETED`; contadores de `fake-one`/`fake-two` = 1; `resume` de novo → `409 END_OF_DAY_RUN_NOT_RESUMABLE`.
  - `two simultaneous manual runs for the same date produce one run`: duas threads com usuários com permissão e `Idempotency-Key` diferentes, liberadas juntas por `CountDownLatch`; uma `201`, a outra `409` (`END_OF_DAY_RUN_EXISTS` ou `END_OF_DAY_ALREADY_CLOSED`, conforme o momento); uma única linha em `endofday.run` para a data; cada fake chamado 1 vez.
  - `repeating the POST with the same idempotency key returns the stored response`: mesma chave → mesma resposta `201` e uma execução; sem `Idempotency-Key` → `400 IDEMPOTENCY_KEY_REQUIRED`.
  - `missing businessDate is a validation error`: `400 VALIDATION_FAILED` com o campo `businessDate`.
  - `END_OF_DAY_NOT_CONFIGURED`: teste de unidade do `create` com registro vazio.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRunsApiTest'`.
- [ ] **Step 3: Implementar** os três arquivos; `./gradlew updateOpenApi`; README (endpoints, permissões, códigos, execução síncrona e a ruling das etapas).
- [ ] **Step 4: Ver passar:** o comando do Step 2, depois `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` (sem `ERR`).
- [ ] **Step 5: Commit** `feat(endofday): fechamento e retomada manuais com aprovação de superior`.

### Task 7: Consultas de execuções, etapas e pendências

**Estimativa:** 30 min · **Depende de:** Task 6 · **Risco:** normal

**Files:**
- Modify: `application/EndOfDayService.kt`, `web/EndOfDayController.kt`, `infrastructure/EndOfDayRunRepository.kt`, `api/openapi.yaml` (gerado), `README.md`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayQueriesApiTest.kt`

**Interfaces:**
- Produces: `GET /api/v1/end-of-day/runs` (cursor, da data mais nova para a mais velha, desempate pelo id; `?status=` opcional), `GET /api/v1/end-of-day/runs/{id}` (com `ETag` da versão e as etapas), `GET /api/v1/end-of-day/runs/{id}/issues` (cursor, por `createdAt`, id; `?resolved=` opcional): `EndOfDayIssueResponse` com `id`, `runId`, `stepName`, `kind`, `reference`, `message`, `resolved`, `createdAt`. Todos exigem `endofday.runs.read`.

- [ ] **Step 1: Testes que falham:** sem token `401`; sem `endofday.runs.read` `403 PERMISSION_DENIED`; lista paginada com `limit=1` e `nextCursor`; detalhe com `ETag` e etapas na ordem; pendências da execução com a `STEP_FAILED` e a levantada pelo fake; id desconhecido `404 END_OF_DAY_RUN_NOT_FOUND`; cursor adulterado `400 INVALID_CURSOR`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayQueriesApiTest'`.
- [ ] **Step 3: Implementar;** `./gradlew updateOpenApi`; README.
- [ ] **Step 4: Ver passar** o comando acima e `./gradlew checkOpenApi`.
- [ ] **Step 5: Commit** `feat(endofday): consultas das execuções e pendências do fechamento`.

### Task 8: Gatilho automático no horário do hotel

**Estimativa:** 45 min · **Depende de:** Task 6 · **Risco:** alto (fuso e concorrência com o manual)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/domain/EndOfDaySchedule.kt`, `application/EndOfDayTrigger.kt`, `infrastructure/EndOfDayTriggerJob.kt`
- Modify: `src/main/resources/application.yaml` (`zeny.endofday.trigger.interval: PT1M`, `zeny.endofday.trigger.initial-delay: PT1M`); `src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt` (constante `END_OF_DAY_TRIGGER_DEFERRED = "zeny.endofday.trigger.initial-delay=PT24H"` no `@IntegrationTest`), `testsupport/EndOfDayIntegrationTest.kt` e os contextos montados à mão que passam `RESUBMISSION_JOB_DEFERRED` (hoje `PrintAgentStreamAttackTest`, `RealtimeStreamTest`, `ProductionProfileApiTest`, `PrintJobsMaintenanceAttackTest`, `PrintAgentStreamTest`, `PrintingStageEndToEndTest`, `EventPublicationRegistryTest`; confira com `grep -rl RESUBMISSION_JOB_DEFERRED src/test`)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayScheduleTest.kt` (unidade), `EndOfDayTriggerTest.kt`

**Interfaces:**
- Produces:
  - `object EndOfDaySchedule { fun dueAt(businessDate: LocalDate, endOfDayTime: LocalTime, zone: ZoneId): Instant }` — **regra:** horário antes de 12:00 → `businessDate + 1 dia` nesse horário (madrugada seguinte, como o padrão 03:00); 12:00 ou depois → no próprio `businessDate`; convertido no `zone` por `ZonedDateTime.of` (horário inexistente por horário de verão avança, como o `ZonedDateTime`). Alternativa descartada: sempre `+1 dia`, que atrasaria quase um dia o fechamento configurado para 23:30.
  - `@Component class EndOfDayTrigger { fun tick(): TickOutcome }` com `enum class TickOutcome { NOT_DUE, NO_STEPS, STARTED, RESUMED, WAITING_MANUAL_RESUME, ALREADY_CLOSED, BUSY }`: lê `HotelApi.currentBusinessDate()`, `operationalSettings().endOfDayTime` e `timezone()`; `now < dueAt` → `NOT_DUE`; registro vazio → `NO_STEPS` (sem log a cada minuto: no máximo um `WARN` por data); sem execução da data → `EndOfDayService.create(date, AUTOMATIC, null)` (auditado com ator sistema) e `engine.execute` → `STARTED`; criação concorrente perdida (`END_OF_DAY_RUN_EXISTS`) → trata como existente; execução `RUNNING` (interrompida) → `engine.execute` → `RESUMED`; `FAILED` → `WAITING_MANUAL_RESUME` (não repete sozinho: a falha já avisou e alerta; quem corrige retoma); `COMPLETED` → `ALREADY_CLOSED`; `Busy` → `BUSY`.
  - `EndOfDayTriggerJob`: `@Scheduled(fixedDelayString = "\${zeny.endofday.trigger.interval}", initialDelayString = "\${zeny.endofday.trigger.initial-delay}")` + `@SchedulerLock(name = "end-of-day-trigger", lockAtMostFor = "PT10M")`, método sem retorno que chama `tick()` e loga `STARTED`/`RESUMED`.

- [ ] **Step 1: Testes de unidade que falham** (`EndOfDayScheduleTest`): `03:00 in America/Manaus is due at 07:00Z of the next day` (`2026-11-12`, `03:00` → `2026-11-13T07:00:00Z`); `23:30 in Asia/Tokyo is due the same day` (`2026-11-12`, `23:30` → `2026-11-12T14:30:00Z`); `12:00 is the same day` (fronteira); `the JVM default zone does not matter` (mesmo resultado com `TimeZone.setDefault` trocado e restaurado).
- [ ] **Step 2: Testes de integração que falham** (`EndOfDayTriggerTest`, `@EndOfDayIntegrationTest @Import(FakeEndOfDayStepsConfiguration::class, MutableClockConfiguration::class)`; o teste troca o fuso do hotel isolado para `America/Manaus` pelo `PUT /api/v1/hotel` e o devolve no fim, e o relógio sempre volta ao original):
  - `before the due time nothing runs` (relógio 1 min antes de `dueAt` → `NOT_DUE`, nenhuma execução).
  - `at the due time in the hotel zone the run starts automatically` (relógio em `dueAt` → `STARTED`, execução `AUTOMATIC` `COMPLETED`, `triggeredBy` nulo, trilha `endofday.run.start` com ator sistema).
  - `an interrupted run is resumed by the trigger` (execução `RUNNING` com uma etapa `RUNNING` gravada pelo teste → `RESUMED`, etapa concluída não reexecutada).
  - `a failed run waits for the manual resume` (`FAILED` → `WAITING_MANUAL_RESUME`, contadores inalterados).
  - `a late business date is closed one date at a time` (data isolada atrasada 2 dias em relação ao relógio: três `tick()` seguidos → `STARTED`, `STARTED`, `NOT_DUE`; duas execuções, uma por data).
  - `the trigger and a manual run at the same time produce one run` (duas threads: `tick()` e `POST …/runs`, liberadas juntas; uma execução para a data).
- [ ] **Step 3: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayScheduleTest' --tests 'com.zeny.hotel.endofday.EndOfDayTriggerTest'`.
- [ ] **Step 4: Implementar;** acrescentar a constante adiada aos contextos listados.
- [ ] **Step 5: Ver passar** o comando acima e `./gradlew test --tests 'com.zeny.hotel.testsupport.EndOfDayIsolationTest'` (as duas anotações continuam com as mesmas propriedades).
- [ ] **Step 6: Commit** `feat(endofday): gatilho automático no horário e fuso do hotel`.

### Task 9: Métricas e alertas do fechamento

**Estimativa:** 35 min · **Depende de:** Task 8 · **Risco:** normal

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/endofday/infrastructure/EndOfDayMetrics.kt`
- Modify: `application/EndOfDayEngine.kt` (registrar duração e falha), `src/main/resources/application.yaml` (`zeny.endofday.metrics.refresh-interval: PT30S`), `docker/prometheus/alerts.yaml`, `README.md` (métricas)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayMetricsTest.kt`

**Interfaces:**
- Produces (nomes Micrometer → Prometheus):
  - Timer `zeny.endofday.run.duration` com a tag `outcome` (`completed`|`failed`), registrado pelo motor no fim de cada `execute` que terminou a execução (do `startedAt` da execução ao fim) → `zeny_endofday_run_duration_seconds_*`.
  - Counter `zeny.endofday.run.failures` (incrementa a cada execução que termina `FAILED`) → `zeny_endofday_run_failures_total`.
  - Gauges no `MeterBinder`, recalculados a cada `refresh-interval` (como o `OutboxMetrics`; `NaN` se o banco não responder): `zeny.endofday.runs.failed` (execuções `FAILED`) e `zeny.endofday.overdue.seconds` (segundos desde o `dueAt` da data operacional corrente se ela não tem execução `COMPLETED`; `0` antes do horário).
  - Regras em `docker/prometheus/alerts.yaml`, grupo `zeny-end-of-day`: `ZenyEndOfDayFailed` (`zeny_endofday_runs_failed > 0`, `for: 1m`, `critical`: "O fechamento do dia falhou"; descrição manda ver as pendências em `GET /api/v1/end-of-day/runs/{id}/issues` e retomar), `ZenyEndOfDayNotRun` (`zeny_endofday_overdue_seconds > 3600`, `critical`: "O fechamento do dia não rodou até 1 hora depois do horário"), `ZenyEndOfDayMetricsUnavailable` (`zeny_endofday_overdue_seconds != zeny_endofday_overdue_seconds`, `for: 5m`, `warning`). Comentário dizendo que a entrega a uma pessoa é da M8 E18.

- [ ] **Step 1: Testes que falham** (`EndOfDayMetricsTest`, contexto dos fakes com `MutableClockConfiguration`): depois de uma execução `COMPLETED`, `GET /actuator/prometheus` tem `zeny_endofday_run_duration_seconds_count{…outcome="completed"…}` maior que antes; depois de uma `FAILED`, `zeny_endofday_run_failures_total` aumentou e, após o refresh (chame o método de refresh direto), `zeny_endofday_runs_failed` ≥ 1; com o relógio 2 h depois do `dueAt` da data corrente sem execução, `zeny_endofday_overdue_seconds` ≥ 7200 (o teste retoma ou conclui o que criou e devolve o relógio).
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayMetricsTest'`.
- [ ] **Step 3: Implementar;** conferir as regras: `docker run --rm --entrypoint promtool -v "$PWD/docker/prometheus:/rules:ro" prom/prometheus:latest check rules /rules/alerts.yaml` (a imagem já está na máquina) → `SUCCESS: 8 rules found`.
- [ ] **Step 4: Ver passar** o comando do Step 2 e `./gradlew test --tests 'com.zeny.hotel.shared.web.ActuatorExposureTest'`.
- [ ] **Step 5: Commit** `feat(endofday): métricas e alertas do fechamento do dia`.

---

## Verificação da etapa (orquestrador)

- `./gradlew build` → `BUILD SUCCESSFUL` (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi`, `koverVerify`).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- `promtool check rules` → `SUCCESS`.
- Critérios de aceite da E10 ↔ testes: ordem e registro (Task 4); falha na 3ª e retomada (Tasks 4 e 6); dois disparos simultâneos (Tasks 6 e 8); 409 com código estável (Task 6); `403 APPROVAL_REQUIRED` e auditoria com aprovador (Task 6); horário no fuso do hotel (Task 8); SSE (Task 5); notificação de pendência (Task 5); métricas e regra de alerta (Task 9); build e contrato (verificação acima).

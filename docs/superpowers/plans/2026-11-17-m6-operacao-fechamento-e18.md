# E18 do M6 — Faixa do cursor, rollback em toda exceção, datas estritas e log dos jobs: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** resolver no `shared`, para todos os módulos, os quatro problemas transversais da E10: (1) chave de data ou de instante do cursor fora do alcance do banco dá `400 INVALID_CURSOR`, e não 500; (2) exceção *checked* desfaz a transação; (3) datas e instantes fora do ISO-8601 são recusados no campo; (4) a falha de um job é logada sem a mensagem.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E18", com H10, H12, H13 e H15; ARCHITECTURE §5 (item 6), §7 ("Paginação" e "Datas e valores"), §11 ("Identificadores e tipos"), §13 e §16. Referência do comportamento já documentado (H15: a execução anterior desta etapa, feita **sobre a mesma base `5e06f16`**, se perdeu, e esta refaz o mesmo comportamento): no brain, `ZenyHotelManager - Estado por marco` ("M6 E18", decisões (a) a (f)), `ZenyHotelManager - Convenções de API` ("Cursor e datas estritos: comportamento atual"), `ZenyHotelManager - Auditoria` ("Rollback em toda exceção: comportamento atual", com o inventário), `ZenyHotelManager - Eventos e jobs` ("Log das exceções dos jobs: `JobErrorLoggingTaskScheduler`") e `ZenyHotelManager - Testes de integração` ("M6 E18"). Como a base do código é a mesma, o inventário e as armadilhas dessas notas valem para o código de hoje e só precisam ser confirmados.

**Architecture:** tudo vive no `shared`. A faixa fica num tipo único (`StorableRange`), que o `PageCursor` usa ao decodificar as chaves. A leitura estrita é um customizador do mapeador único do Jackson 3 da aplicação. O rollback é um atributo do `@EnableTransactionManagement` que o `shared` já declara. O log dos jobs vem de um agendador que substitui o `taskScheduler` do Boot. Nenhum módulo ganha código local, e a validação própria do `endofday` sai.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 / Spring Framework 7 (`spring-tx` 7.0.10, `RollbackOn`), Jackson 3 (`tools.jackson` 3.1.7), ShedLock 7.10.1, Testcontainers.

**Riscos:**
- **parsing de entrada externa não confiável:** chaves do cursor (Task 1), datas e instantes nos corpos JSON e nos payloads dos comandos offline (Task 2);
- **autenticação ou autorização:** os `noRollbackFor` de login com falha, refresh e aprovação recusada, de que depende o bloqueio progressivo (§8.1), passam a conviver com `ALL_EXCEPTIONS` (Task 3);
- **dado pessoal ou segredo:** o log dos jobs perde a mensagem do JDBC, que pode trazer valores do banco (§13, §16) (Task 3);
- **concorrência:** troca do agendador de todos os `@Scheduled` e do executor dos ouvintes assíncronos, a trava do ShedLock e a transação unida que passa a falhar com `UnexpectedRollbackException` (Task 3).

## Global Constraints

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1; esta etapa não cria aresta.
- Sem migração.
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa mudança `ERR` em `/api/v1`. Esperado: o contrato não muda.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados (não se espera nenhuma).
- Código, nomes e testes em inglês; KDoc, comentários, ARCHITECTURE e mensagens de commit em pt-BR (D17).
- Commits por fatia com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- Máquina: um Gradle por vez; nunca `./gradlew --stop`; todo comando longo com `timeout` (testes focados: `timeout 900`; build: `timeout 1800`); nunca laço de espera (`while pgrep …`).
- A verificação da fatia são os testes focados que ela cita mais `./gradlew lint`; a suíte inteira roda no gate de integração do orquestrador.
- Os testes de integração usam `@IntegrationTest` (banco compartilhado, que nunca avança a data); quem precisa do fechamento usa `@EndOfDayIntegrationTest` (H10).

## Review Focus

1. **Cursor nos extremos e logo antes do piso do driver:** o pgjdbc manda como `-infinity` qualquer data ou instante anterior a `-4712-01-01`, sem erro. Um cursor nesse trecho devolve uma página errada em silêncio, e não 500. Teste na Task 1: `-4712-01-01` aceita e o dia anterior, `-4713-12-31`, recusado, em data e em instante; e um instante com fração abaixo do microssegundo logo acima de `MAX_INSTANT` (`+294276-12-31T23:59:59.9999995Z`) recusado.
2. **`""` numa data ou num instante opcional:** antes virava `null` e era aceito. Agora dá `400` com `INVALID_FORMAT` no campo; ausente ou `null` seguem como antes. Teste na Task 2, em data **e** em instante.
3. **JSON que o próprio servidor grava e relê** (`reservations` em `JdbcBookingStore`/`JdbcRoomBlockStore` e `checklists` em `JdbcChecklists`/`ChecklistsOfflineSources`): continua legível nos extremos do alcance, inclusive com ano de sinal e mais de 4 dígitos. Teste na Task 2.
4. **Data em objeto aninhado ou em lista:** o erro sai no campo com o caminho completo (ex.: `guests[0].birthDate`), e não como `MALFORMED_REQUEST` sem campo. Teste na Task 2.
5. ***Checked* engolida por um chamador numa transação unida (REQUIRED):** com `ALL_EXCEPTIONS`, o commit externo falha com `UnexpectedRollbackException`. O efeito é novo e precisa estar provado, no KDoc e na ARCHITECTURE §5. Teste na Task 3, junto do `noRollbackFor` explícito que continua confirmando.

**Regressão conhecida e aceita (decisão (e)):** um recado gravado com `dueOn` = `+999999999-12-31` é guardado como `infinity` e relido como `LocalDate.MAX`. O cursor seguinte que o servidor emite fica fora do `StorableRange`, e o `PageCursor` o recusa com `400 INVALID_CURSOR`: o resto da lista fica inalcançável. Antes desta etapa, a paginação seguia. A correção (recusar no corpo o que estiver fora do `StorableRange`) fica fora do escopo pelo roadmap. Ela vai ao handoff, em "Decisões para o usuário" e em "Notas para a próxima etapa".

## Decisões herdadas (H15: refazer o comportamento documentado)

Valem como requisito, salvo um achado novo que o desenvolvedor devolve ao orquestrador, sem decidir sozinho:

- **(a)** O piso da faixa é `-4712-01-01`, e não `-4713-11-24` (limite do pgjdbc). A ARCHITECTURE §11 passa a registrá-lo (Task 1).
- **(b)** Um comando offline com payload fora do formato volta `REJECTED COMMAND_PAYLOAD_INVALID`, sem efeito, e não é guardado: o protocolo da §9 não muda.
- **(c)** A leitura estrita aceita **só texto**, sem espaço nas pontas. Numa data, vale o formato `ISO_LOCAL_DATE` do `java.time`, inclusive o ano com sinal e mais de 4 dígitos que o próprio servidor grava nos extremos (`-4712-01-01`, `+5874897-12-31`); não é um regex de 4 dígitos. Num instante, vale o ISO-8601 com `T` do `Instant`. Recusa número, epoch em texto, data-hora numa data (com ou sem `Z`), array `[a,m,d]` e objeto.
- **(d)** O logger do ShedLock `net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateStorageAccessor` fica em `OFF`, com comentário sobre o custo: o "Unlock failed after 10 attempts" também some. A alternativa descartada é um filtro que descarte só os eventos com exceção. Fica registrado na ARCHITECTURE §13 (Task 3).
- **(e)** Datas fora do `StorableRange` no **corpo** ficam fora desta etapa (roadmap, "Fora de escopo"), com a regressão conhecida acima.
- **(f) Decisão nova (rodada 0 da revisão de arquitetura), que substitui o `spring.task.execution.mode: force` da execução perdida:** um `taskScheduler` próprio, que é um `Executor`, desligaria o `applicationTaskExecutor` do Boot (o executor dos `@Async` e dos ouvintes do Modulith). Primeiro se tenta a convenção que o projeto já usa em `endofday/infrastructure/EndOfDayExecutionConfiguration.kt:33-35`: o bean com `defaultCandidate = false`, achado pelo nome. Só se o `@Scheduled` ou o ShedLock não o acharem assim, o `force` entra, e a escolha e a alternativa descartada vão para a ARCHITECTURE §13 e para o relatório.

---

### Task 1: Faixa do cursor no `PageCursor`

**Objetivo:** `PageCursor.instant(i)` e `PageCursor.localDate(i)` recusam com `400 INVALID_CURSOR` a chave fora do alcance que o PostgreSQL e o driver guardam. As 15 consultas por cursor de instante ou de data ficam protegidas sem código local, e a validação própria do `endofday` sai.

**Critérios de aceite:**
- `PaginationTest`: `instant` aceita `-4712-01-01T00:00:00Z` e `+294276-12-31T23:59:59.999999Z` e recusa com `INVALID_CURSOR` o vizinho de fora de cada um (`-4713-12-31T23:59:59.999999Z`, `+294277-01-01T00:00:00Z` e `+294276-12-31T23:59:59.9999995Z`). `localDate` aceita `-4712-01-01` e `+5874897-12-31` e recusa `-4713-12-31` e `+5874898-01-01`. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.shared.PaginationTest'`.
- `CursorKeyRangeApiTest` (novo, `@IntegrationTest`): nas 15 consultas, um cursor bem formado com a chave fora da faixa dá `400 INVALID_CURSOR`. São 9 por instante (`AuditQueries`, `ChecklistReports`, `ChecklistExecutions`, `ReservationLifecycle`, `ProvisionalReservations`, `CardPreAuthorizationService`, `PaymentService`, `FolioService`, `RoomStatusService`) e 6 por data (`EndOfDayQueries` na lista de execuções, `ReservationQueries`, `RoomAssignments`, `TraceService`, `OutOfOrderService`, `Scheduling`). O teste vai pelo endpoint que expõe a consulta, ou pelo serviço quando ela não tiver endpoint, e reaproveita o apoio de teste que cada módulo já tem. O relatório traz o vermelho antes da correção em pelo menos uma consulta por instante e uma por data, com a saída (500, ou 200 com página errada). Verificação: `timeout 900 ./gradlew test --tests '*CursorKeyRangeApiTest'`.
- A validação local do `endofday` sai, e os testes de cursor das pendências da E10 seguem verdes. Verificação: `grep -rn 'requireStorable\|MIN_STORABLE\|MAX_STORABLE' src/main` vazio e `timeout 900 ./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- A ARCHITECTURE §11 ("Identificadores e tipos") e a §7 ("Paginação") citam o piso efetivo `-4712-01-01` do driver e o `StorableRange`. Verificação: `grep -n '4712-01-01' docs/ARCHITECTURE.md` mostra as duas seções.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/` (`Pagination.kt`, `StorableRange.kt` novo), `src/main/kotlin/com/zeny/hotel/endofday/application/` (`EndOfDayQueries.kt`, `EndOfDayRunCommands.kt`), `src/main/kotlin/com/zeny/hotel/endofday/infrastructure/EndOfDayRunRepository.kt`, `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/PaginationTest.kt`, `src/test/kotlin/com/zeny/hotel/shared/web/CursorKeyRangeApiTest.kt` (novo).

**Tamanho:** ~60 min; 5 arquivos de produção (a maior parte do tempo vai nos 15 cenários do teste por endpoint).

**Depende de:** nenhuma.

**Interfaces (produz):**
- `com.zeny.hotel.shared.StorableRange` (`object`, público no `shared`): `MIN_DATE: LocalDate = -4712-01-01`, `MAX_DATE: LocalDate = +5874897-12-31`, `MIN_INSTANT: Instant = -4712-01-01T00:00:00Z`, `MAX_INSTANT: Instant = +294276-12-31T23:59:59.999999Z` (extremos inclusivos); `fun contains(date: LocalDate): Boolean` e `fun contains(instant: Instant): Boolean`.
- `PageCursor.instant(index)` e `PageCursor.localDate(index)`: mesma assinatura; fora do `StorableRange`, a mesma exceção de `INVALID_CURSOR` do cursor ilegível.

### Task 2: Datas e instantes estritos no JSON

**Objetivo:** o mapeador único da aplicação recusa, em `LocalDate` e `Instant`, tudo o que não seja o texto ISO-8601 da decisão (c), com `400 VALIDATION_FAILED` e o erro no campo. Vale para todo corpo de `/api/v1` e para os payloads dos comandos offline. O JSON que o servidor grava e relê continua legível.

**Critérios de aceite:**
- `StrictDateTimeJsonApiTest` (novo, `@IntegrationTest`):
  - no `POST /api/v1/end-of-day/runs`, com um usuário que **não** tem `endofday.runs.execute` (H10: não dispara o fechamento no banco compartilhado; a leitura do corpo vem antes da autorização), e no `dueOn` dos recados do `frontdesk`, `businessDate`/`dueOn` como `20405`, `"2026-11-13T00:00:00"`, `"2026-11-13T00:00:00Z"`, `[2026,11,13]` e `""` dão `400 VALIDATION_FAILED` com `INVALID_FORMAT` no campo, e `"2026-11-13"` passa da leitura do corpo;
  - num corpo com `Instant` (o `expiresAt` da pré-autorização de cartão do `billing`), epoch em segundos e em milissegundos (número e texto), texto sem `T` e `""` dão `400` no campo, e `"2026-11-13T10:00:00Z"` é aceito.
  - Verificação: `timeout 900 ./gradlew test --tests '*StrictDateTimeJsonApiTest'`.
- `StrictDateTimeJsonMapperTest` (novo, `@IntegrationTest`, sobre o `JsonMapper` injetado do contexto da aplicação, e não um montado no teste):
  - recusa os mesmos formatos para `LocalDate` e `Instant`, também num objeto aninhado e numa lista, com o caminho completo do campo no erro (Review Focus 4);
  - aceita `-4712-01-01`, `+5874897-12-31`, `-4712-01-01T00:00:00Z` e `+294276-12-31T23:59:59.999999Z` (Review Focus 3);
  - `null` e campo ausente não mudam.
  - Verificação: `timeout 900 ./gradlew test --tests '*StrictDateTimeJsonMapperTest'`.
- Releitura: os testes de persistência que já existem para `JdbcBookingStore`, `JdbcRoomBlockStore`, `JdbcChecklists` e `ChecklistsOfflineSources` seguem verdes. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.reservations.*' --tests 'com.zeny.hotel.checklists.*'`.
- Offline: em `OfflineSyncTest`, um comando cujo payload traz uma data em número volta `REJECTED COMMAND_PAYLOAD_INVALID`, sem efeito, e o reenvio dá a mesma recusa. Com a data em ISO, o comando é aplicado. Verificação: `timeout 900 ./gradlew test --tests '*OfflineSyncTest'`.
- Os testes que hoje mandam data ou instante em formato leniente para entrada legítima mudam junto, cada um listado no relatório.
- O relatório confere e registra, sem corrigir (roadmap, "Fora de escopo"), como se comportam as datas e instantes em query string e path e os valores fora do alcance no corpo, com um exemplo reproduzível de cada.
- A ARCHITECTURE §7 ("Datas e valores") registra a regra do `""` (`400 INVALID_FORMAT`) contra o ausente e o `null` ("sem valor"). Verificação: `grep -n 'INVALID_FORMAT' docs/ARCHITECTURE.md`.
- `./gradlew checkOpenApi` passa sem regenerar o contrato; se ele mudar, o relatório diz o quê.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/json/StrictDateTimeJson.kt` (novo), `src/main/resources/application.yaml` (só se a configuração do `spring.jackson` precisar), `src/main/kotlin/com/zeny/hotel/shared/web/FieldViolations.kt` (só se o caminho do campo não sair), `src/main/kotlin/com/zeny/hotel/shared/application/sync/OfflineCommands.kt` (só se a recusa não sair como `COMMAND_PAYLOAD_INVALID`), `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/web/StrictDateTimeJsonApiTest.kt` e `StrictDateTimeJsonMapperTest.kt` (novos), `OfflineSyncTest` e os testes lenientes que mudam.

**Tamanho:** ~50 min; de 1 a 4 arquivos de produção.

**Depende de:** Task 1.

**Interfaces:**
- Consome: `com.zeny.hotel.shared.StorableRange` (Task 1), nos extremos do teste do mapeador.
- Produz: um `JsonMapperBuilderCustomizer` do Boot no `shared` que liga a leitura estrita de `LocalDate` e `Instant` no mapeador único. Nenhuma API nova para os módulos.

### Task 3: Rollback em toda exceção e log dos jobs sem mensagem

**Objetivo:** uma exceção *checked* que escapa de um caso de uso `@Transactional` desfaz a escrita e a trilha, e os `noRollbackFor` explícitos continuam confirmando. Toda exceção que escapa de um job agendado, inclusive a da trava do ShedLock com o banco fora, sai no log com o nome do job e só as classes da exceção e das causas.

**Critérios de aceite:**
- Configuração: `TransactionOrderingConfiguration` usa `rollbackOn = RollbackOn.ALL_EXCEPTIONS`, com KDoc que avisa do efeito do Review Focus 5. As três transições de `EndOfDayRunTransitions` perdem o `rollbackFor = Throwable` local, e o comportamento não muda. Verificação: `grep -n 'rollbackOn = RollbackOn.ALL_EXCEPTIONS' src/main/kotlin/com/zeny/hotel/shared/infrastructure/audit/AuditAspect.kt` com uma linha, e `grep -rn 'rollbackFor' src/main/kotlin/com/zeny/hotel/endofday` vazio.
- `RollbackOnAllExceptionsTest` (novo, `@IntegrationTest` com a configuração de sondas):
  - (1) um caso de uso de teste `@Transactional` + `@Audited` que grava e lança uma *checked* não deixa nem a escrita nem a entrada de trilha; o relatório traz o vermelho com a configuração antiga (`expected:<0> but was:<1>`);
  - (2) um `noRollbackFor` explícito continua confirmando com `ALL_EXCEPTIONS`;
  - (3) uma *checked* engolida numa transação unida faz o commit externo falhar com `UnexpectedRollbackException`;
  - os testes atuais de login com falha, refresh, aprovação recusada e `files` (`recordRejections`) passam.
  - Verificação: `timeout 900 ./gradlew test --tests '*RollbackOnAllExceptionsTest' --tests 'com.zeny.hotel.iam.*' --tests 'com.zeny.hotel.files.*' --tests 'com.zeny.hotel.endofday.*'`.
- Inventário: o relatório confirma, caminho a caminho, contra o código de hoje, o inventário da nota `ZenyHotelManager - Auditoria` ("Rollback em toda exceção: comportamento atual"), que foi feito na mesma base. Para cada caminho de `src/main` com exceção *checked* dentro de `@Transactional`, diz que nada dependia do commit ou qual ajuste foi feito, e acrescenta o que a nota não tiver. O orquestrador leva o inventário ao handoff.
- `ScheduledJobErrorLogTest` (novo, `@IntegrationTest`):
  - um job agendado real (`IdempotencyKeyPurgeJob.purgeExpiredKeys`), com a trava falhando com uma exceção cuja mensagem e cuja causa levam um sentinela, gera log (saída capturada) com o nome do job e a classe da exceção e da causa, sem o sentinela em lugar nenhum: nem mensagem, nem stack trace, nem suprimidas; o relatório traz o vermelho antes da correção;
  - uma falha no corpo do job sai no mesmo formato;
  - um ouvinte assíncrono (`@ApplicationModuleListener`) continua rodando fora do agendador; o teste espera o ouvinte terminar antes de afirmar sobre a thread e o log.
  - Verificação: `timeout 900 ./gradlew test --tests '*ScheduledJobErrorLogTest'`.
- O agendador é um só, sem código por job, e o mecanismo segue a decisão (f). Verificação: `grep -rn 'taskScheduler\|execution.mode' src/main` mostra só o bean do `shared` e, se entrou, o `force`, com o motivo no relatório.
- O logger do ShedLock fica como na decisão (d). Verificação: `grep -n 'JdbcTemplateStorageAccessor: OFF' src/main/resources/application.yaml`, com o comentário do custo logo acima, e nenhum comentário deslocado do bloco a que se refere.
- A sonda de trava é um `ExtensibleLockProvider` que delega ao `lockProvider` de produção, porque `EndOfDayExecutionConfiguration.kt:55` exige esse tipo. Ela só falha para o nome armado, e cada teste a desarma num `finally`, porque o contexto é compartilhado. Quem confere é a revisão de código.
- O KDoc do agendador avisa que o nome logado é o `toString()` do `Runnable` e que um `Future` avulso tem a exceção logada e engolida.
- A ARCHITECTURE registra: na §13, (d) e (f), com a alternativa descartada e as consequências de cada uma; na §5, item 6, a transação unida (Review Focus 5). Verificação: `grep -n 'UnexpectedRollbackException\|JdbcTemplateStorageAccessor' docs/ARCHITECTURE.md`.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/audit/AuditAspect.kt`, `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDayRunTransitions.kt`, `src/main/kotlin/com/zeny/hotel/shared/infrastructure/scheduling/` (`SchedulingConfiguration.kt`, `ScheduledJobErrors.kt` novo), `src/main/resources/application.yaml`, `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/testsupport/TransactionAndLockProbes.kt` (novo, sondas de transação e de trava; o nome evita confusão com a entidade de `KernelProbe.kt`), `RollbackOnAllExceptionsTest`, `ScheduledJobErrorLogTest`.

**Tamanho:** ~60 min; 5 arquivos de produção (o inventário é confirmação da nota, não descoberta).

**Depende de:** nenhuma.

**Interfaces (produz):** o bean `taskScheduler`, um `JobErrorLoggingTaskScheduler` que estende o `ThreadPoolTaskScheduler`, montado pelo `ThreadPoolTaskSchedulerBuilder` do Boot para manter o `spring.task.scheduling.*` (pool, prefixo das threads, espera no desligamento). Ele loga em `ERROR` no formato `Job <Classe.método> falhou: <Classe>[ <- <Causa>…]`, com proteção contra ciclo de causas. Nenhuma API nova para os módulos.

# E18 do M6 — Faixa do cursor, rollback em toda exceção, datas estritas e log dos jobs: plano de implementação

> **Para agentes:** execução por `superpowers:subagent-driven-development` com as souls (`desenvolvedor`, `testador`, `revisor-seguranca`), uma fatia por vez. Cada `### Task` é uma fatia vertical. O plano diz o quê e onde, não o código.

**Goal:** resolver no `shared`, para todos os módulos, os quatro problemas transversais da E10: (1) chave de data ou de instante do cursor fora do alcance do banco dá `400 INVALID_CURSOR`, e não 500; (2) exceção *checked* desfaz a transação; (3) datas e instantes fora do ISO-8601 são recusados no campo; (4) a falha de um job é logada sem a mensagem.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E18", com H12, H13 e H15; ARCHITECTURE §5 (item 6), §7 ("Paginação" e "Datas e valores"), §11 ("Identificadores e tipos"), §13 e §16. Referência do comportamento já documentado (H15: a execução anterior desta etapa se perdeu, e esta refaz o mesmo comportamento): no brain, `ZenyHotelManager - Estado por marco` ("M6 E18", decisões (a) a (f)), `ZenyHotelManager - Convenções de API` ("Cursor e datas estritos: comportamento atual"), `ZenyHotelManager - Auditoria` ("Rollback em toda exceção: comportamento atual"), `ZenyHotelManager - Eventos e jobs` ("Log das exceções dos jobs: `JobErrorLoggingTaskScheduler`") e `ZenyHotelManager - Testes de integração` ("M6 E18").

**Architecture:** tudo vive no `shared`. A faixa fica num tipo único (`StorableRange`), que o `PageCursor` usa ao decodificar as chaves. A leitura estrita é um customizador do mapeador único do Jackson 3 da aplicação. O rollback é um atributo do `@EnableTransactionManagement` que o `shared` já declara. O log dos jobs vem de um agendador que substitui o `taskScheduler` do Boot. Nenhum módulo ganha código local, e a validação própria do `endofday` sai.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 / Spring Framework 7 (`RollbackOn`), Jackson 3 (`tools.jackson`), ShedLock 7.10.1, Testcontainers.

**Riscos:** parsing de entrada externa não confiável (chaves do cursor, datas e instantes nos corpos JSON e nos payloads dos comandos offline).

## Global Constraints

- Respeitar o grafo de módulos e a regra por camadas da ARCHITECTURE §3.1; esta etapa não cria aresta.
- Sem migração.
- Toda mudança de API regenera o contrato `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca editado à mão); `./gradlew checkOpenApi` passa, e `.github/scripts/check-api-breaking.sh` não acusa mudança `ERR` em `/api/v1`. Esperado: o contrato não muda.
- Biblioteca nova só pelo catálogo `gradle/libs.versions.toml`, com os lockfiles regravados (não se espera nenhuma).
- Código, nomes e testes em inglês; KDoc, comentários e mensagens de commit em pt-BR (D17).
- Commits por fatia com o trailer `Soul: <soul>` no mesmo parágrafo do `Co-Authored-By`.
- Máquina: um Gradle por vez; nunca `./gradlew --stop`; todo comando longo com `timeout` (testes focados: `timeout 900`; build: `timeout 1800`); nunca laço de espera (`while pgrep …`).
- A verificação da fatia são os testes focados que ela cita mais `./gradlew lint`; a suíte inteira roda no gate de integração do orquestrador.
- Os testes de integração usam `@IntegrationTest` (banco compartilhado, que nunca avança a data); quem precisa do fechamento usa `@EndOfDayIntegrationTest` (H10).

## Review Focus

1. **Cursor nos extremos e logo antes do piso do driver:** o pgjdbc manda como `-infinity` qualquer data ou instante anterior a `-4712-01-01`, sem erro. Um cursor nesse trecho devolve uma página errada em silêncio, e não 500. Teste na Task 1: `-4712-01-01` aceita e o dia anterior, `-4713-12-31`, recusado, em data e em instante.
2. **`""` numa data ou num instante opcional:** antes virava `null` e era aceito. Agora dá `400` com `INVALID_FORMAT` no campo; ausente ou `null` seguem como antes. Teste na Task 2.
3. **JSON que o próprio servidor grava e relê** (`reservations` em `JdbcBookingStore`/`JdbcRoomBlockStore` e `checklists` em `JdbcChecklists`/`ChecklistsOfflineSources`): continua legível nos extremos do alcance. Teste na Task 2.
4. **Data em objeto aninhado ou em lista:** o erro sai no campo com o caminho completo (ex.: `guests[0].birthDate`), e não como `MALFORMED_REQUEST` sem campo. Teste na Task 2.
5. ***Checked* engolida por um chamador numa transação unida (REQUIRED):** com `ALL_EXCEPTIONS`, o commit externo falha com `UnexpectedRollbackException`. O efeito é novo e precisa estar provado e no KDoc. Teste na Task 3, junto do `noRollbackFor` explícito que continua confirmando.

## Decisões herdadas (H15: refazer o comportamento documentado)

Valem como requisito, salvo um achado novo que o desenvolvedor devolve ao orquestrador, sem decidir sozinho:

- **(a)** O piso da faixa é `-4712-01-01`, e não `-4713-11-24` (limite do pgjdbc). A ARCHITECTURE §11 passa a registrá-lo (Task 1).
- **(b)** Um comando offline com payload fora do formato volta `REJECTED COMMAND_PAYLOAD_INVALID`, sem efeito, e não é guardado: o protocolo da §9 não muda.
- **(c)** A leitura estrita aceita **só texto**, sem espaço nas pontas: numa data, só `YYYY-MM-DD`; num instante, só ISO-8601 com `T`. Recusa número, epoch em texto, data-hora numa data (com ou sem `Z`), array `[a,m,d]` e objeto.
- **(d)** O logger do ShedLock `net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateStorageAccessor` fica em `OFF`, com comentário sobre o custo: o "Unlock failed after 10 attempts" também some.
- **(e)** Datas fora do `StorableRange` no **corpo** ficam fora desta etapa (roadmap, "Fora de escopo"). Saem registradas em "Notas para a próxima etapa", sem corrigir.
- **(f)** Se o agendador novo desligar o `applicationTaskExecutor` do Boot (o executor dos `@Async` e dos ouvintes do Modulith), `spring.task.execution.mode: force` o mantém. O teste prova que os ouvintes assíncronos seguem fora do agendador.

---

### Task 1: Faixa do cursor no `PageCursor`

**Objetivo:** `PageCursor.instant(i)` e `PageCursor.localDate(i)` recusam com `400 INVALID_CURSOR` a chave fora do alcance que o PostgreSQL e o driver guardam. As 15 consultas por cursor de instante ou de data ficam protegidas sem código local, e a validação própria do `endofday` sai.

**Critérios de aceite:**
- `PaginationTest`: `instant` aceita `-4712-01-01T00:00:00Z` e `+294276-12-31T23:59:59.999999Z` e recusa o vizinho de fora de cada um (`-4713-12-31T23:59:59.999999Z` e `+294277-01-01T00:00:00Z`) com `INVALID_CURSOR`. `localDate` aceita `-4712-01-01` e `+5874897-12-31` e recusa `-4713-12-31` e `+5874898-01-01`. Verificação: `timeout 900 ./gradlew test --tests 'com.zeny.hotel.shared.PaginationTest'`.
- `CursorKeyRangeApiTest` (novo, `@IntegrationTest`): nas 15 consultas, um cursor bem formado com a chave fora da faixa dá `400 INVALID_CURSOR`. São 9 por instante (`AuditQueries`, `ChecklistReports`, `ChecklistExecutions`, `ReservationLifecycle`, `ProvisionalReservations`, `CardPreAuthorizationService`, `PaymentService`, `FolioService`, `RoomStatusService`) e 6 por data (`EndOfDayQueries` na lista de execuções, `ReservationQueries`, `RoomAssignments`, `TraceService`, `OutOfOrderService`, `Scheduling`). O teste vai pelo endpoint que expõe a consulta, ou pelo serviço quando ela não tiver endpoint. O relatório traz o vermelho antes da correção em pelo menos uma consulta por instante e uma por data, com a saída (500, ou 200 com página errada). Verificação: `timeout 900 ./gradlew test --tests '*CursorKeyRangeApiTest'`.
- `EndOfDayQueries` não tem mais `requireStorable` nem as constantes `MIN_STORABLE_INSTANT`/`MAX_STORABLE_INSTANT_EXCLUSIVE`, e `MIN_STORABLE_DATE`/`MAX_STORABLE_DATE` de `EndOfDayRunRepository.kt` dão lugar ao `StorableRange`. Os testes de cursor das pendências da E10 seguem verdes. Verificação: `grep -rn 'requireStorable\|MIN_STORABLE\|MAX_STORABLE' src/main` vazio e `timeout 900 ./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- A ARCHITECTURE §11 ("Identificadores e tipos") e a §7 ("Paginação") citam o piso efetivo `-4712-01-01` do driver e o `StorableRange`.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/` (`Pagination.kt`, `StorableRange.kt` novo), `src/main/kotlin/com/zeny/hotel/endofday/application/` (`EndOfDayQueries.kt`, `EndOfDayRunCommands.kt`), `src/main/kotlin/com/zeny/hotel/endofday/infrastructure/EndOfDayRunRepository.kt`, `docs/ARCHITECTURE.md`. Testes: `src/test/kotlin/com/zeny/hotel/shared/PaginationTest.kt`, `src/test/kotlin/com/zeny/hotel/shared/web/CursorKeyRangeApiTest.kt` (novo).

**Tamanho:** ~45 min; 5 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):**
- `com.zeny.hotel.shared.StorableRange` (`object`, público no `shared`): `MIN_DATE: LocalDate = -4712-01-01`, `MAX_DATE: LocalDate = +5874897-12-31`, `MIN_INSTANT: Instant = -4712-01-01T00:00:00Z`, `MAX_INSTANT: Instant = +294276-12-31T23:59:59.999999Z` (extremos inclusivos); `fun contains(date: LocalDate): Boolean` e `fun contains(instant: Instant): Boolean`.
- `PageCursor.instant(index)` e `PageCursor.localDate(index)`: mesma assinatura; fora do `StorableRange`, a mesma exceção de `INVALID_CURSOR` do cursor ilegível.

### Task 2: Datas e instantes estritos no JSON

**Objetivo:** o mapeador único da aplicação recusa, em `LocalDate` e `Instant`, tudo o que não seja o texto ISO-8601 da §7, com `400 VALIDATION_FAILED` e o erro no campo. Vale para todo corpo de `/api/v1` e para os payloads dos comandos offline. O JSON que o servidor grava e relê continua legível.

**Critérios de aceite:**
- `StrictDateTimeJsonApiTest` (novo, `@IntegrationTest`): no `POST /api/v1/end-of-day/runs` (com um usuário que **não** tem `endofday.runs.execute`, para não disparar o fechamento no banco compartilhado, H10; a leitura do corpo vem antes da autorização) e no `dueOn` dos recados do `frontdesk`, `businessDate`/`dueOn` como `20405`, `"2026-11-13T00:00:00"`, `"2026-11-13T00:00:00Z"`, `[2026,11,13]` e `""` dão `400 VALIDATION_FAILED` com o erro no campo (`INVALID_FORMAT`). `"2026-11-13"` passa da leitura do corpo. Num corpo com `Instant` (o `expiresAt` da pré-autorização de cartão do `billing`), epoch em segundos e em milissegundos (número e texto), texto sem `T` e `""` dão `400` no campo, e `"2026-11-13T10:00:00Z"` é aceito. Verificação: `timeout 900 ./gradlew test --tests '*StrictDateTimeJsonApiTest'`.
- Mapeador: um teste sobre o `JsonMapper` injetado do contexto da aplicação (e não um montado no teste) recusa os mesmos formatos para `LocalDate` e `Instant`, inclusive num objeto aninhado e numa lista, com o caminho completo do campo no erro. A leitura de `null` e de campo ausente não muda.
- Releitura: o JSON gravado pelo servidor em `reservations` e `checklists` com datas e instantes nos extremos do `StorableRange` é relido sem erro (Review Focus 3).
- Offline: em `OfflineSyncTest`, um comando cujo payload traz uma data em número volta `REJECTED COMMAND_PAYLOAD_INVALID`, sem efeito, e o reenvio dá a mesma recusa. Com a data em ISO, o comando é aplicado. Verificação: `timeout 900 ./gradlew test --tests '*OfflineSyncTest'`.
- Os testes que hoje mandam data ou instante em formato leniente para entrada legítima mudam junto, cada um listado no relatório.
- O relatório confere e registra, sem corrigir (roadmap, "Fora de escopo"; decisão (e)), como se comportam as datas e instantes em query string e path e os valores fora do alcance no corpo, com um exemplo reproduzível de cada.
- `./gradlew checkOpenApi` passa sem regenerar o contrato; se ele mudar, o relatório diz o quê.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/json/StrictDateTimeJson.kt` (novo), `src/main/resources/application.yaml` (só se a configuração do `spring.jackson` precisar), `src/main/kotlin/com/zeny/hotel/shared/web/FieldViolations.kt` (só se o caminho do campo não sair), `src/main/kotlin/com/zeny/hotel/shared/application/sync/OfflineCommands.kt` (só se a recusa não sair como `COMMAND_PAYLOAD_INVALID`). Testes: `src/test/kotlin/com/zeny/hotel/shared/web/StrictDateTimeJsonApiTest.kt` (novo), o teste do mapeador no mesmo pacote, `OfflineSyncTest` e os testes lenientes que mudam.

**Tamanho:** ~50 min; de 1 a 4 arquivos de produção.

**Depende de:** nenhuma (usa o `StorableRange` da Task 1 só nos testes de releitura dos extremos; a ordem é a do plano).

**Interfaces:**
- Consome: `com.zeny.hotel.shared.StorableRange` (Task 1).
- Produz: um `JsonMapperBuilderCustomizer` do Boot no `shared` que liga a leitura estrita de `LocalDate` e `Instant` no mapeador único. Nenhuma API nova para os módulos.

### Task 3: Rollback em toda exceção e log dos jobs sem mensagem

**Objetivo:** uma exceção *checked* que escapa de um caso de uso `@Transactional` desfaz a escrita e a trilha, e os `noRollbackFor` explícitos continuam confirmando. Toda exceção que escapa de um job agendado, inclusive a da trava do ShedLock com o banco fora, sai no log com o nome do job e só as classes da exceção e das causas.

**Critérios de aceite:**
- `TransactionOrderingConfiguration` (`shared/infrastructure/audit/AuditAspect.kt`) usa `rollbackOn = RollbackOn.ALL_EXCEPTIONS`, com KDoc que avisa do efeito do Review Focus 5. As três transições de `EndOfDayRunTransitions` perdem o `rollbackFor = Throwable` local, e o comportamento não muda.
- `RollbackOnAllExceptionsTest` (novo, `@IntegrationTest` com uma configuração de sondas de teste): (1) um caso de uso de teste `@Transactional` + `@Audited` que grava e lança uma *checked* não deixa nem a escrita nem a entrada de trilha, e o relatório traz o vermelho com a configuração antiga (`expected:<0> but was:<1>`); (2) um `noRollbackFor` explícito continua confirmando com `ALL_EXCEPTIONS`; (3) uma *checked* engolida numa transação unida faz o commit externo falhar com `UnexpectedRollbackException`. Os testes atuais de login com falha, refresh, aprovação recusada e `files` (`recordRejections`) passam. Verificação: `timeout 900 ./gradlew test --tests '*RollbackOnAllExceptionsTest' --tests 'com.zeny.hotel.iam.*' --tests 'com.zeny.hotel.files.*' --tests 'com.zeny.hotel.endofday.*'`.
- Inventário: o relatório lista cada caminho de `src/main` com exceção *checked* dentro de `@Transactional` (pistas: IO em `files`, `printing` e `fnrh`, o tempo real, as cifras, o `endofday` e os `TransactionTemplate` programáticos) e diz, em cada um, que nada dependia do commit ou qual ajuste foi feito. O orquestrador leva o inventário ao handoff.
- `ScheduledJobErrorLogTest` (novo, `@IntegrationTest`): um job agendado real (ex.: `IdempotencyKeyPurgeJob.purgeExpiredKeys`), com o `LockProvider` falhando com uma exceção cuja mensagem e cuja causa levam um sentinela, gera log (saída capturada) com o nome do job e a classe da exceção e da causa, e sem o sentinela em lugar nenhum: nem mensagem, nem stack trace, nem suprimidas. O relatório traz o vermelho antes da correção. Um ouvinte assíncrono (`@ApplicationModuleListener`) continua rodando fora do agendador (decisão (f)). Verificação: `timeout 900 ./gradlew test --tests '*ScheduledJobErrorLogTest'`.
- O agendador da aplicação é um só, para todos os `@Scheduled` de hoje e os futuros, sem código por job. O logger do ShedLock fica como na decisão (d), com o comentário do custo no `application.yaml`.
- A sonda de trava (`LockProvider` de teste que falha só para o nome armado) é desarmada num `finally`, porque o contexto é compartilhado.
- `timeout 900 ./gradlew lint` verde.

**Áreas tocadas:** `src/main/kotlin/com/zeny/hotel/shared/infrastructure/audit/AuditAspect.kt`, `src/main/kotlin/com/zeny/hotel/endofday/application/EndOfDayRunTransitions.kt`, `src/main/kotlin/com/zeny/hotel/shared/infrastructure/scheduling/` (`SchedulingConfiguration.kt`, `ScheduledJobErrors.kt` novo), `src/main/resources/application.yaml`. Testes: `src/test/kotlin/com/zeny/hotel/testsupport/KernelProbes.kt` (novo, sondas de transação e de trava), `RollbackOnAllExceptionsTest`, `ScheduledJobErrorLogTest`.

**Tamanho:** ~55 min; 5 arquivos de produção.

**Depende de:** nenhuma.

**Interfaces (produz):** o bean `taskScheduler` (subclasse de `ThreadPoolTaskScheduler`, `JobErrorLoggingTaskScheduler`), que loga `Job <Classe.método> falhou: <Classe>[ <- <Causa>…]` em `ERROR`, com proteção contra ciclo de causas. Nenhuma API nova para os módulos.

# M5 E08 — Envio assíncrono e pendências da FNRH: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ligar o `fnrh` aos eventos de reserva e de hospedagem: cada evento grava uma `FnrhSubmission`, que um job envia ao Serpro pela porta da E07, em ordem por reserva, com retry, conciliação do resultado incerto, ids remotos guardados, pendências na API, alerta de falha, métrica e view.

**Architecture:** listeners `@ApplicationModuleListener` no `fnrh` só gravam a submissão (idempotente por reserva, operação e evento). Um job com ShedLock pega, por reserva, a submissão mais antiga ainda não resolvida e a envia: lê a reserva pela `ReservationsApi` e os hóspedes pela `GuestsApi.fnrhData` (só em memória), valida localmente, chama o Serpro **fora de transação** e grava cada id remoto logo depois de cada passo, em transação própria, para a próxima tentativa retomar de onde parou. O resultado de cada tentativa é gravado por um bean `@Audited @Transactional` separado, que também dispara o alerta. Estados: `PENDING`, `SENT`, `FAILED`, `INVALID`, `CANCELLED`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (outbox), ShedLock 7, Hibernate 7.4, Flyway 12, WireMock, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E08", mais os handoffs de E01, E02, E03 e E07 do mesmo arquivo, `docs/fnrh/api-fnrh-v2.4.md`, ARCHITECTURE §3.1, §12 e §13 e as notas do brain `ZenyHotelManager - Módulo fnrh` e `ZenyHotelManager - Eventos e jobs`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E08-1 | **Eventos → operações:** `ReservationCreated` → `REGISTER_RESERVATION`; `ReservationChanged`, `ReservationConfirmed` e `ReservationReactivated` → `UPDATE_RESERVATION` (o `PUT` reenvia os dados atuais, idempotente); `ReservationCancelled` → `CANCEL_RESERVATION`; `GuestCheckedIn` → `CHECK_IN`; `GuestCheckInUndone` → `UNDO_CHECK_IN`; `GuestCheckedOut` → `CHECK_OUT`. A reserva criada sem documentos completos também gera envio: o registro da reserva não leva dado pessoal (D-E07-2). | Critério "cada evento listado gera exatamente uma submissão"; o `PUT` é a única operação da API que sincroniza a reserva, e reenviar dado igual não cria recurso. | Uma chamada `PUT` a mais por confirmação. Trocar o mapeamento é local ao listener. |
| D-E08-2 | **Uma submissão por evento**, com chave única `(reservation_id, operation, event_key)`; `event_key` = o tipo do evento e a `version` dele (`created` no `ReservationCreated`, que não tem versão). O `CHECK_IN` cobre todos os hóspedes da reserva numa submissão, com o progresso por hóspede guardado em `fnrh.remote_guest`. | Critério "exatamente uma" e reentrega idempotente (`INSERT … ON CONFLICT DO NOTHING`). Progresso por hóspede guardado deixa a tentativa seguinte retomar sem duplicar. | Uma submissão por hóspede daria fila mais fina; mudar depois exige migrar a tabela. |
| D-E08-3 | **Ordem por reserva:** só a cabeça da reserva anda, isto é, a submissão de menor `sequence` entre as `PENDING`, `FAILED` e `INVALID` daquela reserva, e só se estiver `PENDING` e vencida. `FAILED` e `INVALID` seguram as seguintes até o `retry`. | O check-out nunca sai antes do check-in, nem o check-in antes do registro (critério). Uma regra só, numa consulta. | Uma reserva com submissão parada fica parada até alguém agir; é isso que o alerta pede. |
| D-E08-4 | **Retry:** falha temporária (`SERVER_ERROR`, `UNAVAILABLE`, `UNKNOWN_OUTCOME`) reagenda com espera `1 min × 2^(n−1)` até `1 h` (n = tentativas feitas) e, na **8ª** tentativa com falha, vira `FAILED`. Falha permanente (`VALIDATION`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`) vira `FAILED` na hora. Propriedades `zeny.fnrh.submissions.max-attempts` (8), `initial-backoff` (`PT1M`), `max-backoff` (`PT1H`). | Mesma curva do outbox (1, 2, 4… 32 min, 1 h), cerca de 2 h no total: cobre uma queda curta do Serpro sem segurar a fila por um dia. Limite também em `UNAVAILABLE` (nota da E07: erro de programação cai nele). | Fila curta demais numa queda longa; o `retry` manual resolve. |
| D-E08-5 | **Sem configuração** (`FnrhConnectionSource.current()` nulo): o job não envia nada e não gasta tentativa; as submissões ficam `PENDING`. | Desligar a integração (E16) não pode perder envios nem esgotar tentativas. | Nenhum. |
| D-E08-6 | **Resultado incerto (`UNKNOWN_OUTCOME`)** marca `uncertain`; a tentativa seguinte concilia antes de repetir: reserva por `findReservationByCode`; hóspede por `listReservationGuests` (porta nova, `GET /reservas/{id}/hospedes`), casando pelo `pessoa_id`; **pessoa por `findPersonByDocument`** (porta nova, `GET /pessoas/documento/{tipo}/{numero}`, com `cpf_solicitante`). `PUT`, cancelamento e `PATCH` de check-out são repetidos, porque não criam recurso; se a repetição de um cancelamento der `VALIDATION`, a submissão fica `FAILED` com alerta. Escolha do `registerPerson` registrada no handoff. | ARCHITECTURE §12: nunca repetir às cegas uma escrita que cria recurso. Conciliar a pessoa evita pessoa duplicada no MTur, que é registro legal. | A consulta por documento é mais uma chamada com dado pessoal na URL; nunca registrar a URL (já é regra do adaptador). |
| D-E08-7 | **Pessoa já conhecida:** se o mesmo hóspede (`guest_id`) já tem `person_id` no mesmo ambiente, de qualquer reserva, o `CHECK_IN` o reaproveita sem novo `POST /pessoas`. | Hóspede recorrente não vira pessoa nova a cada estadia. A API v2.4 não tem atualização de pessoa. | Dado alterado no perfil não chega ao Serpro numa estadia seguinte; a E09 confirma se é preciso. |
| D-E08-8 | **Desfazer check-in:** a API não tem a operação. Ao gravar o `UNDO_CHECK_IN`, o listener cancela (`CANCELLED`) os `CHECK_IN` da reserva ainda não resolvidos e sem nenhum hóspede no Serpro; no envio, se nenhum hóspede da reserva chegou ao Serpro, o `UNDO_CHECK_IN` fica `SENT` sem chamada; se algum chegou, fica `FAILED` com `NO_REMOTE_OPERATION` e alerta (correção manual no portal). Um novo check-in pula os hóspedes já no Serpro. | Não existe caminho técnico do nosso lado; o alerta põe a pessoa certa para corrigir no portal. | Correção manual rara. Vai às "Decisões para o usuário". |
| D-E08-9 | **Reativação de reserva cancelada no Serpro:** se a reserva remota foi cancelada por nós, o `UPDATE_RESERVATION` fica `FAILED` com `REMOTE_CANCELLED`, sem chamada, e alerta. Cancelamento de reserva que nunca foi ao Serpro fica `SENT` sem chamada. | O `numero_reserva` é único no meio de hospedagem e a API não reativa. | Correção manual rara. Vai às "Decisões para o usuário". |
| D-E08-10 | **Validação local** só no `CHECK_IN` (o registro da reserva não leva dado pessoal): todos os hóspedes são validados antes de qualquer chamada; com erro, `INVALID`, erros por hóspede e campo em `fnrh.submission_error` (sem valor) e alerta. A residência nula continua aceita (comportamento da E07) e o `checkedOutAt` não é validado: o check-out usa `StayGuestSummary.checkedOutAt` ou, sem ele, o instante do evento. | H21; o check-in nunca é bloqueado (RF-FNRH-03). | Nenhum além do da E07. |
| D-E08-11 | **Alerta** a cada passagem a `FAILED` ou `INVALID`: `NotificationsApi.notify(NotificationTypes.INTEGRATION_FAILURE, "Falha no envio à FNRH", "Reserva <código>: <operação> — <situação>", NotificationRecipients.withPermission(FnrhPermissions.SUBMISSIONS_MANAGE))`. Só o código da reserva e rótulos fixos. | RF-FNRH-03, RF-NOT-01; nunca dado de hóspede no texto. | Nenhum. |
| D-E08-12 | **Permissão nova** `fnrh.submissions.manage` (não sensível), só para o Gerente; `fnrh.submissions.read` continua com Gerente e Recepcionista. `POST /api/v1/fnrh/submissions/{id}/retry` aceita só `FAILED` e `INVALID` (senão `409 FNRH_SUBMISSION_NOT_RETRYABLE`), zera as tentativas e os erros, sem `@Idempotent` (repetir dá `409`, sem efeito duplo). | H20. O `retry` não tem efeito financeiro e já é idempotente pela regra de estado. | Um código publicado não muda. |
| D-E08-13 | Ids remotos **por ambiente** (`environment` na chave de `remote_reservation` e `remote_guest`): trocar de ambiente faz a próxima submissão registrar de novo no ambiente novo. O link de pré-check-in não é guardado. | Nota da E07: os ids dependem do ambiente. O link é do Serpro e o M5 não o mostra; guardá-lo é decisão da tela que o usar. | Guardar o link depois é coluna nova, aditiva. |
| D-E08-14 | O `fnrh` passa a depender de `reservations` e `notifications` (já na ARCHITECTURE §3.1); o `ModularityTest` do `fnrh` muda na mesma tarefa. | Restrição do roadmap. | Nenhum. |

## Global Constraints

- Respeitar o grafo da ARCHITECTURE §3.1: `fnrh` → `shared`, `guests`, `reservations`, `notifications`; nunca `iam`, `frontdesk` nem tabela de outro schema. `frontdesk` nunca chama `fnrh`.
- Falha da FNRH nunca bloqueia check-in nem check-out (RF-FNRH-03): o listener só grava a submissão; o envio nunca acontece na transação do evento.
- Dados sensíveis (RN-19: raça/cor, deficiência) e número inteiro de documento só em memória, durante o envio, lidos por `GuestsApi.fnrhData`; nunca em tabela, view, log, trilha, evento, notificação nem `toString`. `Cpf.toString()` devolve os dígitos: nunca interpole `Cpf`.
- Toda chamada ao Serpro roda **fora** de transação do banco. Escrita `@Audited` em bean separado de quem chama (a autoinvocação pula o proxy).
- Todo listener é idempotente (o outbox reentrega).
- Migrações na pasta `fnrh/`, com timestamp posterior a `V2026_11_06_1000`.
- Views `report_*`: uma linha por fato, ids opacos, sem texto livre nem dado de hóspede, contrato no comentário da migração, `GRANT SELECT` e `REVOKE` por view, provadas com um papel que só as lê.
- Job: `@Scheduled` + `@SchedulerLock(name = "fnrh-submissions-send")`, adiado nos testes pela constante `FNRH_SEND_DEFERRED` no `@IntegrationTest`; os testes chamam o envio direto.
- Contrato regenerado por `./gradlew updateOpenApi`; `.github/scripts/check-api-breaking.sh` sem `ERR` em `/api/v1`. Enums de resposta que podem crescer (`operation`, `status`, `lastFailure`) são `x-extensible-enum` (`@ExtensibleEnum`).
- Código em inglês; mensagens, KDoc e comentários em pt-BR.
- Todo commit leva o trailer `Soul: <soul>` **no mesmo último parágrafo** do `Co-Authored-By`, senão o Git não o reconhece.
- Antes de commitar: `./gradlew ktlintFormat`, `./gradlew lint` e os testes da tarefa. Detekt com limites padrão: extraia função em vez de suprimir.
- Banco de teste compartilhado e WireMock global (`FnrhTestServer`, `reset()` no `@BeforeEach`): cada teste cria as próprias reservas e hóspedes, envia só as submissões **da própria reserva** (`sendDueFor`) e devolve a configuração da FNRH e o relógio ao estado anterior.

## Review Focus

1. Evento reentregue depois de a submissão já estar `SENT`: nenhuma submissão nova nem chamada (Task 3).
2. Integração desligada quando o job roda: as submissões ficam `PENDING`, sem gastar tentativa, e saem quando ligada (Task 5).
3. Walk-in (`ReservationCreated` e `GuestCheckedIn` na mesma transação): o registro sai antes do check-in, e o check-in usa o id remoto (Task 6).
4. Menor listado antes do responsável na reserva: o responsável entra primeiro e o menor leva o `responsavel_id` dele (Task 6).
5. Reserva criada antes da E08 (sem `REGISTER_RESERVATION`) que recebe check-in: o `CHECK_IN` registra a reserva antes; cancelamento de reserva que nunca foi ao Serpro fica `SENT` sem chamada (Tasks 5 e 6).

## Ondas

Em sequência, no worktree da etapa (ruling da E15: o harness recusa git de subagente em outro worktree). Ordem: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12.

---

### Task 1: Domínio da submissão e política de retry

**Estimativa:** 30 min. **Depende de:** nenhuma. **Risco:** alto (núcleo da etapa).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmission.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhRetryPolicy.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhErrorCodes.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmissionTest.kt`, `FnrhRetryPolicyTest.kt`

**Interfaces:**
- Produces (raiz `domain`, entidade JPA como `FnrhRequester`, tabela `fnrh.submission` da Task 2):
  - `enum class FnrhOperation { REGISTER_RESERVATION, UPDATE_RESERVATION, CANCEL_RESERVATION, CHECK_IN, UNDO_CHECK_IN, CHECK_OUT }`
  - `enum class FnrhSubmissionStatus { PENDING, SENT, FAILED, INVALID, CANCELLED }`
  - `enum class FnrhSubmissionFailure` com os valores de `FnrhFailure` menos `NOT_CONFIGURED`, mais `REMOTE_CANCELLED` e `NO_REMOTE_OPERATION`.
  - `data class FnrhSubmissionError(val guestId: UUID, val field: String, val code: String)` (embutível; nunca valor).
  - `class FnrhSubmission` com `id`, `reservationId: UUID`, `reservationCode: String`, `operation`, `eventKey: String`, `sequence: Long?` (gerado pelo banco), `status`, `attempts: Int`, `nextAttemptAt: Instant`, `lastFailure: FnrhSubmissionFailure?`, `uncertain: Boolean`, `performedBy: UUID?`, `occurredAt: Instant`, `createdAt: Instant`, `sentAt: Instant?`, `errors: List<FnrhSubmissionError>`, `@Version version: Long`; `companion fun create(reservationId, reservationCode, operation, eventKey, performedBy, occurredAt, now): FnrhSubmission`.
  - Transições: `markSent(now)`; `recordFailure(failure: FnrhFailure, now: Instant, policy: FnrhRetryPolicy): Boolean` (devolve `true` se virou `FAILED`); `fail(reason: FnrhSubmissionFailure, now)`; `markInvalid(errors: List<FnrhSubmissionError>, now)`; `reconciled()` (zera `uncertain`); `cancel(now)`; `retry(now)`.
  - `data class FnrhRetryPolicy(val maxAttempts: Int = 8, val initialBackoff: Duration = 1 min, val maxBackoff: Duration = 1 h) { fun delayAfter(attempts: Int): Duration }`.
  - `FnrhErrorCodes.SUBMISSION_NOT_RETRYABLE = "FNRH_SUBMISSION_NOT_RETRYABLE"`.

- [ ] **Step 1: Escreva os testes que falham**

`FnrhRetryPolicyTest`: `delayAfter(1..7)` = 1, 2, 4, 8, 16, 32, 60 min; `delayAfter(20)` = 60 min.

`FnrhSubmissionTest`:
- `create` deixa `PENDING`, `attempts = 0`, `nextAttemptAt = now`, `uncertain = false`.
- `recordFailure(SERVER_ERROR)` 7 vezes: segue `PENDING`, `attempts = 7`, `nextAttemptAt = now + delayAfter(7)`, devolve `false`; na 8ª, `FAILED`, `lastFailure = SERVER_ERROR`, devolve `true`.
- `recordFailure(UNKNOWN_OUTCOME)` liga `uncertain`; `reconciled()` desliga; `markSent` desliga e grava `sentAt`.
- `recordFailure(VALIDATION)` na 1ª tentativa: `FAILED` na hora; `recordFailure(NOT_CONFIGURED)` → `IllegalArgumentException` (quem chama não grava tentativa sem configuração, D-E08-5).
- `markInvalid` grava os erros, `INVALID`; `retry` de `FAILED` e de `INVALID` volta a `PENDING` com `attempts = 0`, sem erros, `lastFailure = null`, `nextAttemptAt = now`.
- `retry` de `PENDING`, `SENT` ou `CANCELLED` → `ConflictException` (a do kernel usada pelos módulos) com `FNRH_SUBMISSION_NOT_RETRYABLE`.
- Toda transição a partir de `SENT` ou `CANCELLED` (salvo `retry`, coberto acima) → `IllegalStateException`.
- `toString` não traz `reservationCode` nem erros.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.FnrhSubmissionTest' --tests 'com.zeny.hotel.fnrh.domain.FnrhRetryPolicyTest'`
Expected: falha de compilação.

- [ ] **Step 3: Implemente** as classes do bloco "Produces", com os mapeamentos JPA das colunas da Task 2 (`errors` como `@ElementCollection` em `fnrh.submission_error`, chave `submission_id`). Use o tipo de exceção de conflito que os outros módulos usam para `409` (veja `FnrhErrorCodes` e o `shared`).

- [ ] **Step 4: Rode** o comando do Step 2. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 2: Migração, persistência e portas

**Estimativa:** 35 min. **Depende de:** Task 1. **Risco:** alto (persistência, migração).

**Files:**
- Create: `src/main/resources/db/migration/fnrh/V2026_11_10_1000__create_fnrh_submissions.sql`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionPorts.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhSubmissionPersistence.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionPersistenceTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Consumes: Task 1.
- Produces (em `FnrhSubmissionPorts.kt`):
  - `interface FnrhSubmissionStore { fun recordIfAbsent(submission: FnrhSubmission): Boolean; fun find(id: UUID): FnrhSubmission?; fun save(submission: FnrhSubmission); fun dueHeads(now: Instant, limit: Int, reservationId: UUID? = null): List<UUID>; fun unresolvedOf(reservationId: UUID, operation: FnrhOperation): List<FnrhSubmission>; fun page(status: FnrhSubmissionStatus?, after: Long?, limit: Int): List<FnrhSubmission>; fun countByStatus(): Map<FnrhSubmissionStatus, Long> }`
  - `data class FnrhRemoteReservation(val reservationId: UUID, val environment: FnrhEnvironment, val remoteId: FnrhRemoteId, val cancelled: Boolean)`
  - `data class FnrhRemoteGuest(val reservationId: UUID, val guestId: UUID, val environment: FnrhEnvironment, val personId: FnrhRemoteId, val guestRemoteId: FnrhRemoteId?, val checkedOut: Boolean)`
  - `interface FnrhRemoteIds { fun reservation(reservationId: UUID, env: FnrhEnvironment): FnrhRemoteReservation?; fun saveReservation(r: FnrhRemoteReservation); fun guests(reservationId: UUID, env: FnrhEnvironment): List<FnrhRemoteGuest>; fun saveGuest(g: FnrhRemoteGuest); fun knownPerson(guestId: UUID, env: FnrhEnvironment): FnrhRemoteId? }` — toda escrita em transação própria (`REQUIRES_NEW`), porque é chamada entre chamadas ao Serpro.

**Esquema** (uma migração; sem FK para outro schema):
- `fnrh.submission`: `id uuid PK`, `reservation_id uuid NOT NULL`, `reservation_code text NOT NULL`, `operation text NOT NULL` (CHECK com os 6 valores), `event_key text NOT NULL`, `sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE`, `status text NOT NULL` (CHECK), `attempts int NOT NULL CHECK (attempts >= 0)`, `next_attempt_at timestamptz NOT NULL`, `last_failure text NULL` (CHECK), `uncertain boolean NOT NULL`, `performed_by uuid NULL`, `occurred_at`, `created_at timestamptz NOT NULL`, `sent_at timestamptz NULL`, `version bigint NOT NULL`; `UNIQUE (reservation_id, operation, event_key)`; índice `(reservation_id, sequence) WHERE status IN ('PENDING','FAILED','INVALID')` e índice `(status, sequence)`.
- `fnrh.submission_error`: `submission_id uuid FK → fnrh.submission`, `guest_id uuid`, `field text`, `code text`, `PK (submission_id, guest_id, field)`.
- `fnrh.remote_reservation`: `reservation_id`, `environment` (CHECK `HOMOLOGATION`/`PRODUCTION`), `remote_id text NOT NULL`, `cancelled boolean NOT NULL`, `PK (reservation_id, environment)`.
- `fnrh.remote_guest`: `reservation_id`, `guest_id`, `environment`, `person_id text NOT NULL`, `guest_remote_id text NULL`, `checked_out boolean NOT NULL`, `PK (reservation_id, guest_id, environment)`; índice `(guest_id, environment)`.
- `GRANT SELECT, INSERT, UPDATE` ao `"${app_role}"` nas quatro (no modelo de `V2026_11_04_1001`), e `DELETE` só em `submission_error` (o `retry` limpa os erros). Nenhuma coluna de texto livre do Serpro, de documento, raça ou deficiência.

- [ ] **Step 1: Escreva os testes que falham** (`FnrhSubmissionPersistenceTest`):
- `recordIfAbsent` duas vezes com a mesma chave → `true`, depois `false`, uma linha.
- `dueHeads`: reserva com `REGISTER_RESERVATION` (`PENDING`, vencida) e `CHECK_IN` (`PENDING`) → só a primeira; com a primeira `SENT`, a segunda; com a primeira `FAILED`, nenhuma; com a primeira `PENDING` e `nextAttemptAt` no futuro, nenhuma; o filtro `reservationId` limita à reserva; `limit` respeitado.
- `save` com erros e `retry` → os erros somem do banco.
- `FnrhRemoteIds`: grava e lê reserva e hóspede por ambiente; `knownPerson` acha o `person_id` do mesmo hóspede em outra reserva do mesmo ambiente e não no outro ambiente.
- Esquema: nenhuma coluna de `fnrh.submission`, `submission_error`, `remote_reservation` ou `remote_guest` se chama ou contém `document`, `race`, `disability`, `name`, `message` (consulta a `information_schema.columns`).

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionPersistenceTest'`. Expected: FAIL.

- [ ] **Step 3: Implemente** a migração, as portas e os adaptadores (`JpaRepository` para a submissão, `JdbcClient` para `dueHeads` com `DISTINCT ON (reservation_id) … ORDER BY reservation_id, sequence` e para os ids remotos).

- [ ] **Step 4: Rode** o teste do Step 2 e `--tests 'com.zeny.hotel.persistence.MigrationConventionTest'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 3: Listeners que gravam a submissão

**Estimativa:** 35 min. **Depende de:** Task 2. **Risco:** alto (concorrência, primeiro consumidor de produção dos eventos).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionListeners.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhModule.kt` (`allowedDependencies = ["shared", "guests", "reservations", "notifications"]`, KDoc)
- Modify (test): `src/test/kotlin/com/zeny/hotel/ModularityTest.kt` (teste do `fnrh`), `src/test/kotlin/com/zeny/hotel/testsupport/InventoryListenersQuiescence.kt` (espera também `%fnrh.application.FnrhSubmissionListeners%`)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionListenersTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Consumes: Tasks 1 e 2; eventos de `reservations/ReservationsEvents.kt`; `ReservationsApi.reservation(id)` para o `code`.
- Produces: `class FnrhSubmissionListeners` com um `@ApplicationModuleListener fun on(event: X)` por evento de D-E08-1. `event_key`: `created`, `changed:v<version>`, `confirmed:v<version>`, `reactivated:v<version>`, `cancelled:v<version>`, `checked-in:v<version>`, `check-in-undone:v<version>`, `checked-out:v<version>`. `performedBy` vem dos eventos de hospedagem; `occurredAt` é o instante do evento.
- No `GuestCheckInUndone`, na mesma transação: cada `CHECK_IN` da reserva em `unresolvedOf(…, CHECK_IN)` sem nenhum `FnrhRemoteGuest` da reserva no ambiente atual (ou sem configuração) vira `CANCELLED` (D-E08-8).
- Reserva que a `ReservationsApi` não acha: log `WARN` com o id e nada gravado.

- [ ] **Step 1: Escreva os testes que falham:** para cada um dos 8 eventos, criado pelo fluxo real (reserva e check-in pela API, com os `…TestSupport` de reservas e recepção), exatamente uma submissão com a operação de D-E08-1, o `reservationCode` da reserva e, nos de hospedagem, o `performedBy` do usuário; chamar o listener de novo com o mesmo evento (bean injetado) não cria outra; o check-in desfeito cancela o `CHECK_IN` pendente; walk-in gera `REGISTER_RESERVATION` com `sequence` menor que o `CHECK_IN`. `ModularityTest` com as dependências novas.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionListenersTest' --tests 'com.zeny.hotel.ModularityTest'`.

- [ ] **Step 3: Implemente.**

- [ ] **Step 4: Rode** o comando do Step 2 e as suítes que usam a quiescência: `--tests '*Inventory*' --tests '*Offline*' --tests 'com.zeny.hotel.MilestoneM4EndToEndTest'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 4: Operações de conciliação na porta e no adaptador

**Estimativa:** 30 min. **Depende de:** nenhuma (roda depois da 3, em série). **Risco:** alto (integração externa, documento na URL).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhGateway.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGateway.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhWire.kt`
- Modify (test): `src/test/kotlin/com/zeny/hotel/testsupport/FnrhTestServer.kt` (`stubListReservationGuests_v2_4`, `stubFindPersonByDocument_v2_4`, estritos como os outros)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGatewayReconciliationTest.kt`

**Interfaces:**
- Produces:
  - `data class FnrhRemoteGuestRef(val guestId: FnrhRemoteId, val personId: FnrhRemoteId)`
  - `fun listReservationGuests(reservationId: FnrhRemoteId, correlationRef: String): List<FnrhRemoteGuestRef>` → `GET /reservas/{id}/hospedes` (sem `cpf_solicitante`).
  - `fun findPersonByDocument(document: FnrhDocument, requesterCpf: Cpf, correlationRef: String): FnrhRemoteId?` → `GET /pessoas/documento/{tipo}/{numero}` com `cpf_solicitante`; `404` → `null`.
- Os nomes dos campos da resposta seguem `docs/fnrh/api-fnrh-v2.4.md` (`hospede_id`, `pessoa_id`). Se o levantamento não traz a forma da resposta, use a do PDF citado nele e registre a premissa no KDoc, para a E09 confirmar.

- [ ] **Step 1: Escreva os testes que falham:** caminho, método, Basic Auth e presença (só em `findPersonByDocument`) e ausência do `cpf_solicitante`; `tipo` = `CPF`/`PASSAPORTE` como no `registerPerson`; resposta mapeada; `404` em `findPersonByDocument` → `null`; `500` → `SERVER_ERROR`; timeout de leitura num `GET` → `UNAVAILABLE`; com `OutputCaptureExtension`, nem log nem mensagem da exceção trazem o número do documento nem a URL.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.infrastructure.SerproFnrhGatewayReconciliationTest'`.

- [ ] **Step 3: Implemente** pelo mesmo caminho das leituras existentes (`findReservationByCode`), com o registro em `fnrh.integration_call` e o saneador.

- [ ] **Step 4: Rode** o Step 2 e `--tests 'com.zeny.hotel.fnrh.infrastructure.*'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 5: Envio — núcleo e operações da reserva

**Estimativa:** 45 min. **Depende de:** Tasks 2, 3 e 4. **Risco:** alto (núcleo, integração externa).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionRecorder.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhAudit.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhReservationSendTest.kt` (`@IntegrationTest`, `MutableClockConfiguration`, WireMock)

**Interfaces:**
- Consumes: Tasks 1, 2 e 4; `FnrhConnectionSource`, `ReservationsApi.reservation` e `booking`, `FnrhGateway`.
- Produces:
  - `data class FnrhSendRun(val sent: Int, val postponed: Int, val failed: Int, val invalid: Int)`
  - `FnrhSubmissionSender.sendDue(): FnrhSendRun` (até `zeny.fnrh.submissions.batch-size`, 50) e `sendDueFor(reservationId: UUID): FnrhSendRun` (só a reserva; para testes e para o job). **Sem `@Transactional`**: lê, chama o Serpro e grava pelo recorder.
  - `FnrhSubmissionRecorder` (bean separado, `@Audited(FnrhAudit.SEND_SUBMISSION) @Transactional`): `fun sent(id: UUID)`, `fun failed(id: UUID, failure: FnrhFailure)`, `fun failedLocally(id: UUID, reason: FnrhSubmissionFailure)`, `fun invalid(id: UUID, errors: List<FnrhSubmissionError>)`, `fun reconciled(id: UUID)`. O retrato da trilha leva id, operação, situação, tentativas e `lastFailure`; nunca o código da reserva nem dado de hóspede.
  - Um passo interno por operação, com a assinatura `fun interface FnrhOperationStep { fun send(submission: FnrhSubmission, connection: FnrhConnection): FnrhStepOutcome }`, `sealed interface FnrhStepOutcome { Sent; Invalid(errors); FailedLocally(reason) }`; falha do Serpro sobe como `FnrhGatewayException`. Esta tarefa registra os passos de `REGISTER_RESERVATION`, `UPDATE_RESERVATION` e `CANCEL_RESERVATION`; as Tasks 6 e 7 registram os demais num `Map<FnrhOperation, FnrhOperationStep>`.
  - `fun ensureReservation(submission, reservation, connection): FnrhRemoteId` (público no pacote, usado pela Task 6): id guardado; senão, com `uncertain`, `findReservationByCode`; senão `registerReservation`; grava o id em seguida.
- Regras: sem conexão, nada é tocado (D-E08-5). `FnrhReservationData` sai da reserva e do `booking` (`otaCode = booking.externalId`; `minors` pela data de nascimento dos hóspedes da reserva, via `GuestsApi.guest(id).birthDate`, menor de 18 anos na chegada; sem hóspedes, `childAges.size`). `UPDATE_RESERVATION` com a reserva remota cancelada → `failedLocally(REMOTE_CANCELLED)`; sem reserva remota, registra (e não faz o `PUT`). `CANCEL_RESERVATION` sem reserva remota → `sent` sem chamada; com ela, `cancelReservation` e `cancelled = true`. `correlationRef` = id da submissão.

- [ ] **Step 1: Escreva os testes que falham:**
- Registro com sucesso grava o id remoto da reserva (ambiente atual) e fica `SENT`; reserva de OTA envia `numero_reserva_ota` = `externalId` e `origem_reserva_id = OTA` (corpo no WireMock).
- `503` três vezes com o `MutableClock`: `attempts` 1, 2, 3 e `nextAttemptAt` = +1, +2, +4 min; antes de vencer, `sendDueFor` não chama o Serpro; na 8ª falha, `FAILED`.
- Timeout de leitura no `POST /reservas` (atraso maior que o `read-timeout` do teste) → `uncertain`; na tentativa seguinte, `findReservationByCode` acha a reserva e **não** há segundo `POST /reservas`.
- Integração desligada: `sendDueFor` não chama nada e `attempts` segue 0; ligada, envia.
- `REGISTER_RESERVATION` `FAILED` segura o `UPDATE_RESERVATION` da mesma reserva.
- Cancelamento de reserva sem id remoto → `SENT` sem chamada; reativação depois de cancelada no Serpro → `FAILED` com `REMOTE_CANCELLED`, sem chamada.
- A trilha da submissão enviada não traz o código da reserva.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhReservationSendTest'`.

- [ ] **Step 3: Implemente.**

- [ ] **Step 4: Rode** o Step 2. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 6: Envio do check-in

**Estimativa:** 45 min. **Depende de:** Task 5. **Risco:** alto (dado pessoal e sensível, integração externa).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhCheckInStep.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt` (registra o passo)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhCheckInSendTest.kt` (`@IntegrationTest`, WireMock, `OutputCaptureExtension`)

**Interfaces:**
- Consumes: Tasks 2, 4 e 5 (`ensureReservation`, `FnrhRemoteIds`, `FnrhOperationStep`); `GuestsApi.fnrhData`, `FnrhPersonMapper.toPerson`, `FnrhLocalValidator` (montado com `JdbcFnrhDomainCatalog` e `IbgeMunicipalities`), `RequesterCpfPolicy.choose(submission.performedBy, FnrhRequesterDirectory::cpfOf, connection.defaultRequesterCpf)`.
- Produces: `class FnrhCheckInStep : FnrhOperationStep` para `CHECK_IN`.
- Regras:
  1. Lê a reserva; para cada `StayGuestSummary`: `fnrhData` → `toPerson`; `FnrhGuest(principal = primary, checkedInAt = checkedInAt ?: submission.occurredAt, travelReason, transportMode, responsibleGuestId = null)`; `responsibleKnown` = há `minorAuthorization` e o `responsibleGuestId` dela é hóspede da reserva. Valida **todos** antes de qualquer chamada; com erro, `Invalid` com `FnrhSubmissionError(guestId, field, code)`.
  2. `ensureReservation`.
  3. Ordem: hóspedes sem `minorAuthorization` primeiro (o principal à frente), depois os menores.
  4. Por hóspede ainda sem `guestRemoteId`: pessoa = `knownPerson` (D-E08-7); senão, com `uncertain`, `findPersonByDocument`; senão `registerPerson`. Grave o `person_id` logo. Depois, com `uncertain`, procure em `listReservationGuests` o `pessoa_id`; senão `addGuestToReservation` com o `responsibleGuestId` = `guestRemoteId` do responsável. Grave logo.
  5. Ao fim, `Sent`.

- [ ] **Step 1: Escreva os testes que falham:**
- Check-in com dois adultos: uma pessoa e um hóspede por adulto, ids gravados, `SENT`; `cpf_solicitante` = CPF do usuário do check-in quando cadastrado em `/fnrh/requesters`, senão o padrão.
- Menor listado antes do responsável: o `POST /reservas/{id}/hospedes` do menor leva `responsavel_id` = o `hospede_id` do responsável, e o do responsável vem antes.
- Hóspede sem gênero (não coletado): `INVALID` com `person.gender`/`REQUIRED` e o `guestId`, **nenhuma** chamada ao Serpro.
- Falha no `addGuest` do 2º hóspede (`503`) e nova tentativa: o 1º não é reenviado.
- Timeout no `POST /pessoas` → na tentativa seguinte, `GET /pessoas/documento/…` acha a pessoa e não há segundo `POST /pessoas`.
- Walk-in pela API da recepção: `REGISTER_RESERVATION` sai antes e o `CHECK_IN` usa o id dele.
- Reserva sem `REGISTER_RESERVATION` (apague a submissão no teste para simular a reserva anterior à E08): o `CHECK_IN` registra a reserva antes.
- Hóspede recorrente (segunda reserva, mesmo hóspede): sem novo `POST /pessoas`.
- Com o WireMock devolvendo `503` em tudo, `POST /api/v1/reservations/{id}/check-in` responde `200` e a submissão fica `PENDING`.
- No log capturado: nenhum nome, número de documento, raça, deficiência nem CPF.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhCheckInSendTest'`.

- [ ] **Step 3: Implemente.**

- [ ] **Step 4: Rode** o Step 2 e `--tests 'com.zeny.hotel.fnrh.*'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 7: Envio do check-out e do desfazer check-in

**Estimativa:** 25 min. **Depende de:** Task 6. **Risco:** alto (ordem, correção manual).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhCheckOutSteps.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhCheckOutSendTest.kt`

**Interfaces:**
- Produces: passos de `CHECK_OUT` (para cada `FnrhRemoteGuest` da reserva com `guestRemoteId` e `checkedOut = false`: `checkOut(guestRemoteId, at = StayGuestSummary.checkedOutAt ?: submission.occurredAt)`, grava `checkedOut = true` em seguida; hóspede sem id remoto é ignorado com log `INFO` só com ids) e de `UNDO_CHECK_IN` (D-E08-8: sem hóspede remoto, `Sent` sem chamada; com algum, `FailedLocally(NO_REMOTE_OPERATION)`).

- [ ] **Step 1: Escreva os testes que falham:** check-in e check-out gravados com o `CHECK_IN` ainda `PENDING` e o Serpro respondendo `503` ao check-in: `sendDueFor` nunca chama o `PATCH …/checkout` antes de o check-in sair; depois de sair, o check-out sai com o instante do check-out; falha no 2º hóspede e nova tentativa não repete o 1º. Desfazer check-in antes do envio: `CHECK_IN` `CANCELLED`, `UNDO_CHECK_IN` `SENT`, nenhuma chamada de hóspede; depois do envio: `UNDO_CHECK_IN` `FAILED` com `NO_REMOTE_OPERATION`.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhCheckOutSendTest'` falha, implemente, passa.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 8: Alerta de falha e permissão de gestão

**Estimativa:** 20 min. **Depende de:** Task 7. **Risco:** alto (privacidade na notificação).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhPermissions.kt` (`SUBMISSIONS_MANAGE = PermissionCode("fnrh.submissions.manage")`, só `RoleTemplate.MANAGER`, não sensível)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionRecorder.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhFailureAlertTest.kt`

**Interfaces:**
- Produces: em `failed`, `failedLocally` e `invalid`, quando a submissão passa a `FAILED` ou `INVALID`, a chamada de D-E08-11 na mesma transação. Rótulos fixos em pt-BR por operação ("registro da reserva", "atualização da reserva", "cancelamento da reserva", "check-in", "desfazer check-in", "check-out") e por situação ("falhou", "dados inválidos").

- [ ] **Step 1: Escreva os testes que falham:** submissão que vira `FAILED` gera uma notificação `notifications.integration-failure` para um usuário com `fnrh.submissions.manage` e nenhuma para um Recepcionista; o título e o texto trazem o código da reserva e **não** trazem o nome, o nome social, o documento, o e-mail, o telefone nem a descrição de gênero de um hóspede que tem todos preenchidos (nota da E15); `INVALID` também notifica; falha temporária que reagenda não notifica.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhFailureAlertTest'` falha, implemente, passa; rode também `--tests '*Permission*' --tests '*RoleTemplate*'`.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 9: Job e métrica de pendentes

**Estimativa:** 25 min. **Depende de:** Task 8. **Risco:** alto (concorrência entre instâncias).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhSubmissionJob.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhMetrics.kt`
- Modify: `src/main/resources/application.yaml` (`zeny.fnrh.submissions`: `interval` `PT1M`, `initial-delay` `PT1M`, `batch-size` 50, `max-attempts` 8, `initial-backoff` `PT1M`, `max-backoff` `PT1H`)
- Modify (test): `src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt` (`FNRH_SEND_DEFERRED = "zeny.fnrh.submissions.initial-delay=PT24H"`, na lista)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionJobTest.kt`, `FnrhMetricsTest.kt` (estender)

**Interfaces:**
- Produces: `FnrhSubmissionJob.send()` com `@Scheduled(fixedDelayString = "\${zeny.fnrh.submissions.interval}", initialDelayString = "\${zeny.fnrh.submissions.initial-delay}")` e `@SchedulerLock(name = "fnrh-submissions-send", lockAtMostFor = "PT10M")`, chamando `sendDue()` e logando as contagens só quando > 0; `Unit` (o ShedLock recusa primitivo). Gauge `zeny_fnrh_submissions` com a tag `status` (`pending`, `failed`, `invalid`), lido de `countByStatus()`.

- [ ] **Step 1: Escreva os testes que falham:** o job envia uma submissão da reserva do teste (as demais do banco compartilhado podem sair também: o teste só confere a sua); a métrica aparece em `/actuator/prometheus` com as três tags e conta uma `FAILED` criada no teste; `ActuatorExposureTest` intacto.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionJobTest' --tests 'com.zeny.hotel.fnrh.FnrhMetricsTest' --tests 'com.zeny.hotel.shared.web.ActuatorExposureTest'` falha, implemente, passa.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 10: Endpoints de pendências e de reenvio

**Estimativa:** 35 min. **Depende de:** Task 9. **Risco:** alto (contrato público).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionsService.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/web/FnrhSubmissionsController.kt`
- Modify: `api/openapi.yaml` (por `./gradlew updateOpenApi`)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionsApiTest.kt`

**Interfaces:**
- Produces:
  - `GET /api/v1/fnrh/submissions?status=…` (`operationId` `listFnrhSubmissions`, `fnrh.submissions.read`), paginação por cursor do kernel (`PageParams`/`CursorPage`; o cursor é a `sequence`). Item: `id`, `reservationId`, `reservationCode`, `operation`, `status`, `attempts`, `nextAttemptAt`, `lastFailure` (nulo), `errors[]` (`guestId`, `field`, `code`), `createdAt`, `sentAt`. `operation`, `status` e `lastFailure` com `@ExtensibleEnum`.
  - `POST /api/v1/fnrh/submissions/{id}/retry` (`retryFnrhSubmission`, `fnrh.submissions.manage`, `@Audited(FnrhAudit.RETRY_SUBMISSION)`): `200` com o item; inexistente `404`; outro estado `409 FNRH_SUBMISSION_NOT_RETRYABLE`.

- [ ] **Step 1: Escreva os testes que falham:** sem permissão `403` nos dois; `GET ?status=FAILED` traz a submissão `FAILED` do teste e não a `SENT`; o corpo não traz nome, documento nem CPF de hóspede; `retry` de `FAILED` → `PENDING`, `attempts = 0` e a submissão sai no `sendDueFor` seguinte; `retry` de `INVALID` limpa os erros; `retry` de `PENDING` → `409` com o código; trilha do `retry` com o usuário e sem dado de hóspede; `ApiSecurityTest` cobre os caminhos novos.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionsApiTest' --tests 'com.zeny.hotel.shared.web.ApiSecurityTest'` falha, implemente, `./gradlew updateOpenApi`, passa; `./gradlew checkOpenApi` verde.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 11: View `fnrh.report_submissions`

**Estimativa:** 15 min. **Depende de:** Task 2. **Risco:** normal.

**Files:**
- Create: `src/main/resources/db/migration/fnrh/V2026_11_10_1100__create_fnrh_report_views.sql`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhReportViewsTest.kt`

**Interfaces:**
- Produces: `fnrh.report_submissions` com `submission_id`, `reservation_id`, `operation`, `status`, `attempts`, `last_failure`, `created_at`, `sent_at`, `next_attempt_at`; contrato no comentário da migração; `GRANT SELECT` e `REVOKE` no modelo de `reservations/V2026_10_22_0351__create_reservation_report_views.sql`. Sem `reservation_code` (a convenção pede ids opacos; quem precisa do código junta com `reservations.report_reservations` pelo `reservation_id`), sem erros por campo e sem texto.

- [ ] **Step 1: Escreva o teste que falha:** com o papel que só lê views (como no teste das views do `reservations`), a view devolve a submissão do teste; o papel não lê `fnrh.submission`; as colunas são exatamente as listadas.
- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhReportViewsTest' --tests 'com.zeny.hotel.persistence.MigrationConventionTest'` falha, implemente, passa.
- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 12: Documentos de referência (orquestrador)

**Estimativa:** 20 min. **Depende de:** Tasks 1 a 11. **Risco:** normal.

**Files:** só `.md`, pelo orquestrador:
- `docs/SPEC.md` §8.2.17: a porta `FnrhGateway` real (as operações da E07 mais `listReservationGuests` e `findPersonByDocument`) e a `FnrhSubmission` com as operações e situações de D-E08-1 e D-E08-3.
- `docs/ARCHITECTURE.md` §13: o job `fnrh-submissions-send`; §12, se o texto divergir do que a etapa fez.
- `README.md`, seção "API": `GET /api/v1/fnrh/submissions` e `POST …/{id}/retry`.
- `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`: "Decisões do lote E08", handoff (com a escolha do `registerPerson`, D-E08-6, e o que a etapa do no-show do M6 acrescenta: operação na porta, `markNoShow` publicando o evento e o listener gravando `NO_SHOW`).

- [ ] **Step 1:** edite. **Step 2:** `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m5-recepcao-fnrh.md`. **Step 3:** commit com `Soul: orquestrador`.

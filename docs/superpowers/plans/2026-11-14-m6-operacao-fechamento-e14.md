# M6 E14 — No-show na FNRH — Plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** o no-show que o fechamento publica (`ReservationMarkedNoShow`, M6 E11) vira uma `FnrhSubmission` `NO_SHOW` na fila do M5 E08 e é enviado ao Serpro por uma operação nova da porta `FnrhGateway`.

**Architecture:** segue o padrão "Envio assíncrono por pedido" (ARCHITECTURE §12, aprovado pelo usuário na revisão humana da M5 E08): o listener só grava a submissão (idempotente pela chave `(reservation_id, operation, event_key)`), o job envia fora de transação, na ordem causal da reserva, agindo sobre o estado atual. A operação nova é `markNoShow` → `POST /reservas/{reserva_id}/noshow` (no-show **em lote, da reserva**). O estado remoto ganha `no_show` em `fnrh.remote_reservation`, para o `UPDATE`/`CANCEL` posteriores não chamarem o Serpro à toa.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (`@ApplicationModuleListener`), Flyway 12, PostgreSQL 18 (Testcontainers), WireMock (`FnrhTestServer`).

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E14 — No-show na FNRH", mais "Contexto confirmado" e "Restrições e invariantes" do mesmo roadmap; `docs/fnrh/api-fnrh-v2.4.md` (seções "Reservas" e "Hóspedes"); ARCHITECTURE §12 ("Envio assíncrono por pedido", "Resultado incerto") e §13; nota do brain `ZenyHotelManager - Módulo fnrh` e a captura pendente `ZenyHotelManager - atualização m5-recepcao-fnrh E08` (seção "Envio assíncrono").

## Decisões do plano

- **D-E14-1 — Caminho: `POST /reservas/{reserva_id}/noshow` (lote, da reserva), não `PATCH /hospedes/{id}/no-show` (individual).** Motivo: no nosso fluxo (D-E07-2) a reserva é registrada na criação sem hóspedes e os hóspedes só entram no check-in; uma reserva no-show nunca teve check-in, então não temos `hospede_id` remoto para o caminho individual. O caminho da reserva só precisa do id remoto que já guardamos, é o único sem divergência no PDF (a divergência `/no-show` × `/noshow` é do individual) e alcança também os hóspedes de pré-check-in que o próprio hóspede criou pelo link. Risco: o Serpro recusar o no-show de reserva sem hóspede (`4xx` → `FAILED` com alerta); a M5 E09 confirma. Fica no handoff e entra na lista da M5 E09.
- **D-E14-2 — Ordem causal:** `FnrhOperation.NO_SHOW` com posto `6` (anexo ao fim; o posto só desempata dentro da mesma `causal_version`). `event_key` = `no-show:v<version>`; `causal_version` = `ReservationMarkedNoShow.version`. Como o registro tem `causal_version` 0 e o no-show tem `version` ≥ 1, o no-show sai sempre depois do registro.
- **D-E14-3 — Passo do envio, pelo estado atual (D-E08-9):**
  - remota guardada não cancelada e não no-show → `markNoShow`; no sucesso grava `noShow = true` (preservando link e `cancelled`) → `SENT`;
  - sem remota guardada: com escrita em curso na reserva concilia (`FnrhReservationSync.existing`, como o `cancel`); achada → segue acima; não achada → `SENT` sem chamada (a reserva no-show nunca é registrada, `NOT_REGISTERED`);
  - remota já cancelada ou já no-show → `SENT` sem chamada.
  - Repetição: como o cancelamento (E08), o `POST …/noshow` não cria recurso e é repetido no `retry`; uma repetição recusada (`VALIDATION`) fica `FAILED` com alerta.
- **D-E14-4 — Remota no-show não volta:** a API não desfaz no-show. `UPDATE_RESERVATION` (inclusive o da reativação) com a remota `noShow` e a reserva ativa aqui → `FailedLocally(REMOTE_NO_SHOW)` com alerta, sem chamada (correção no portal e `dismiss`, como `REMOTE_CANCELLED`); com a reserva cancelada ou no-show aqui → `SENT` sem chamada. `CANCEL_RESERVATION` com a remota `noShow` → `SENT` sem chamada.
- **D-E14-5 — Códigos novos:** `FnrhOperation.NO_SHOW`, `FnrhSubmissionFailure.REMOTE_NO_SHOW(temporary = false)` e os espelhos `FnrhSubmissionOperationView.NO_SHOW` e `FnrhSubmissionFailureView.REMOTE_NO_SHOW` (já `x-extensible-enum` no contrato: acrescentar não quebra). Rótulo do alerta: `"no-show"`.
- **D-E14-6 — Migração única** `src/main/resources/db/migration/fnrh/V2026_11_14_1000__add_fnrh_no_show.sql` (posterior a `V2026_11_13_1000`): troca `submission_operation_ck` e `submission_last_failure_ck` pelos novos conjuntos e acrescenta `fnrh.remote_reservation.no_show boolean NOT NULL DEFAULT false`. A view `fnrh.report_submissions` não muda (expõe `operation` e `last_failure` como texto; o comentário da E08 que lista as operações está numa migração já aplicada e não é editado: o contrato da view é atualizado no brain).

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1): o `fnrh` continua dependendo exatamente de `shared`, `guests`, `reservations` e `notifications`; o `ModularityTest` não muda.
- Toda chamada ao Serpro roda fora de transação do banco; o listener só grava.
- Nada de dado pessoal em log, trilha, alerta, view ou `fnrh.integration_call` (nota "Dados que nunca saem"); o `correlationRef` é o id da submissão.
- RN-22 / E08: a falha do Serpro nunca bloqueia o fechamento; `FAILED`/`INVALID` seguram a fila da reserva até `retry`/`dismiss`.
- Migração nova com versão posterior a `V2026_11_13_1000`; nunca editar migração aplicada.
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` (nunca à mão); `.github/scripts/check-api-breaking.sh` sem `ERR` em `/api/v1`. Enum de resposta que cresce sai como `x-extensible-enum`.
- Testes do fechamento com as etapas reais rodam no banco isolado (`@EndOfDayIntegrationTest`, H10): cada teste termina com `EndOfDayTestRuns.settle()`, devolve o relógio e a configuração (inclusive a da FNRH) e afirma só sobre os próprios dados; no `FnrhTestServer` compartilhado, conta só os pedidos da própria reserva.
- Máquina de 14 GB: um Gradle por vez (tarefas em série; nunca `./gradlew --stop`).
- Commits em pt-BR com o trailer `Soul: <soul>`.

## Review Focus

1. **Reentrega do `ReservationMarkedNoShow`** (outbox reentrega, ou depois do `SENT`): não grava outra submissão — teste na Task 4.
2. **No-show de reserva cujo registro ainda está pendente/`FAILED` na fila** (integração ligada depois, Serpro fora): o no-show não sai antes do registro; com o registro resolvido sem remota (reserva já no-show, `NOT_REGISTERED`), o no-show fica `SENT` sem chamada — teste na Task 3.
3. **Resposta inesperada do `POST …/noshow`** (`200` com corpo vazio, `{"dados": []}`, corpo não JSON): sucesso sem ler o corpo; `4xx`/`5xx`/timeout seguem a classificação da E07 (timeout de leitura num `POST` = `UNKNOWN_OUTCOME`) — testes na Task 2.
4. **Reativação depois do no-show enviado**: o `UPDATE` não chama o Serpro e fica `FAILED` `REMOTE_NO_SHOW` com alerta — teste na Task 3.
5. **Serpro fora do ar durante o fechamento**: o fechamento conclui `COMPLETED` e a submissão fica `PENDING` com a tentativa contada — teste na Task 4.

---

### Task 1: Operação `NO_SHOW`, falha `REMOTE_NO_SHOW` e migração

Estimativa: 30 min · Depende de: nenhuma · Risco: alto (persistência, contrato público)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmission.kt` (`FnrhOperation`, `FnrhSubmissionFailure`)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionsService.kt` (`FnrhSubmissionOperationView`, `FnrhSubmissionFailureView`)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionRecorder.kt` (rótulo do alerta, `when` exaustivo; uma linha — por isso a tarefa passa de 3 arquivos sem ser dividida)
- Create: `src/main/resources/db/migration/fnrh/V2026_11_14_1000__add_fnrh_no_show.sql`
- Modify (gerado): `api/openapi.yaml`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhNoShowCodesTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Produces: `FnrhOperation.NO_SHOW` (rank 6); `FnrhSubmissionFailure.REMOTE_NO_SHOW` (`temporary = false`); `FnrhSubmissionOperationView.NO_SHOW`; `FnrhSubmissionFailureView.REMOTE_NO_SHOW`; coluna `fnrh.remote_reservation.no_show` (`boolean NOT NULL DEFAULT false`).

- [ ] **Step 1: Testes que falham** em `FnrhNoShowCodesTest`:
  - `NO_SHOW comes after every other operation in the causal order`: `FnrhOperation.NO_SHOW.rank == 6` e maior que o `rank` de todas as demais.
  - `REMOTE_NO_SHOW is a permanent failure`: `FnrhSubmissionFailure.REMOTE_NO_SHOW.temporary == false`.
  - `the queue stores a NO_SHOW submission`: `FnrhSubmissionStore.recordIfAbsent(FnrhSubmission.create(… operation = NO_SHOW, eventKey = "no-show:v2", causalVersion = 2 …))` devolve `true`, e a linha em `fnrh.submission` tem `operation = 'NO_SHOW'` e `operation_rank = 6`.
  - `the database accepts REMOTE_NO_SHOW as last failure and the remote no_show flag`: `UPDATE fnrh.submission SET status='FAILED', last_failure='REMOTE_NO_SHOW'` na submissão do teste passa; `SELECT no_show FROM fnrh.remote_reservation` de uma linha inserida sem a coluna devolve `false`.
  - `the submissions API shows NO_SHOW and REMOTE_NO_SHOW`: `GET /api/v1/fnrh/submissions?status=FAILED` (com `fnrh.submissions.read`) lista a submissão do teste com `operation = "NO_SHOW"` e `lastFailure = "REMOTE_NO_SHOW"`.
  - Use uma reserva criada pelo próprio teste (ids aleatórios) e afirme só sobre ela.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhNoShowCodesTest'` → falha de compilação (`NO_SHOW` não existe).
- [ ] **Step 3: Implementar:** as duas constantes de domínio (KDoc: no-show em lote da reserva, D-E14-1; remota no-show não reativa, D-E14-4), os dois espelhos da web, o rótulo `FnrhOperation.NO_SHOW -> "no-show"` no `FnrhSubmissionRecorder` e a migração: `ALTER TABLE fnrh.submission DROP CONSTRAINT submission_operation_ck, ADD CONSTRAINT submission_operation_ck CHECK (operation IN (… as 6 atuais …, 'NO_SHOW'))`; o mesmo para `submission_last_failure_ck` com `'REMOTE_NO_SHOW'` acrescentado aos 10 códigos atuais (copie a lista da `V2026_11_10_1000`); `ALTER TABLE fnrh.remote_reservation ADD COLUMN no_show boolean NOT NULL DEFAULT false`. Cabeçalho em pt-BR citando M6 E14, D-E14-2, D-E14-4 e D-E14-5.
- [ ] **Step 4:** `./gradlew updateOpenApi` e confira que o diff de `api/openapi.yaml` só acrescenta `NO_SHOW` e `REMOTE_NO_SHOW` nos enums de `operation` e `lastFailure` das submissões.
- [ ] **Step 5:** `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → verde (inclui `FnrhSubmissionPersistence*`, `FnrhReportViews*`, `FnrhSubmissionsApi*`).
- [ ] **Step 6: Commit** `feat(fnrh): operação NO_SHOW e falha REMOTE_NO_SHOW na fila de submissões` com `Soul: desenvolvedor`.

### Task 2: `FnrhGateway.markNoShow` no adaptador Serpro

Estimativa: 30 min · Depende de: nenhuma · Risco: alto (entrada externa)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhGateway.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGateway.kt`
- Modify (teste): `src/test/kotlin/com/zeny/hotel/testsupport/FnrhTestServer.kt` (stub `stubMarkNoShow_v2_4(reservationId: String)`, no modelo de `stubCancelReservation_v2_4`)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGatewayNoShowTest.kt` (sem Spring, como `SerproFnrhGatewayTest`)

**Interfaces:**
- Produces: `fun markNoShow(reservationId: FnrhRemoteId, correlationRef: String)` na `FnrhGateway` (KDoc: `POST /reservas/{id}/noshow`, sem corpo, no-show em lote da reserva; D-E14-1; o corpo da resposta não é lido); operação `"markNoShow"` no `fnrh.integration_call`.

- [ ] **Step 1: Testes que falham** em `SerproFnrhGatewayNoShowTest`:
  - `markNoShow posts to the reservation noshow path without body`: com `stubMarkNoShow_v2_4("r-1")`, a chamada faz exatamente um `POST $BASE_PATH/reservas/r-1/noshow`, com `Authorization` Basic, sem corpo e sem header `cpf_solicitante`.
  - `markNoShow accepts any 2xx body`: `200` com `{"dados": []}`, `200` com corpo vazio e `200` com corpo não JSON → sem exceção.
  - `markNoShow maps failures like the other writes`: `422` → `FnrhGatewayException(VALIDATION, 422, …)`; `404` → `NOT_FOUND`; `503` → `SERVER_ERROR`; conexão recusada → `UNAVAILABLE`; atraso maior que o read timeout → `UNKNOWN_OUTCOME` (é `POST`).
  - `markNoShow logs the call without personal data`: o `FnrhIntegrationLog` recebe operação `markNoShow`, o `correlationRef` e `remote_id = r-1`; nenhuma URL no log.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.fnrh.infrastructure.SerproFnrhGatewayNoShowTest'` → falha de compilação.
- [ ] **Step 3: Implementar** `markNoShow` no `SerproFnrhGateway` com o `call(...)` existente: `operation = "markNoShow"`, `method = POST`, `segments = listOf("reservas", reservationId.value, "noshow")`, `knownRemoteId = reservationId.value`, resultado `Unit` (mesma forma do `cancelReservation`).
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.infrastructure.*'` → verde.
- [ ] **Step 5: Commit** `feat(fnrh): operação markNoShow na porta e no adaptador do Serpro` com `Soul: desenvolvedor`.

### Task 3: Estado remoto no-show e passo de envio `NO_SHOW`

Estimativa: 45 min · Depende de: Task 1, Task 2 · Risco: alto (persistência, integração externa, núcleo da etapa)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionPorts.kt` (`FnrhRemoteReservation.noShow: Boolean = false`, no `toString`)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhSubmissionPersistence.kt` (ler e gravar `no_show`, inclusive no `ON CONFLICT … DO UPDATE`)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt` (`FnrhReservationSteps.noShow`, regras de D-E14-4 em `update` e `cancel`, mapa do `FnrhOperationSteps`)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhNoShowSendTest.kt` (`@IntegrationTest` + `FnrhTestConfiguration`, no modelo de `FnrhReservationSendTest`/`FnrhReservationSendTestBase`)

**Interfaces:**
- Consumes: Task 1 (`NO_SHOW`, `REMOTE_NO_SHOW`, coluna), Task 2 (`FnrhGateway.markNoShow`).
- Produces: `fun noShow(submission: FnrhSubmission, connection: FnrhConnection): FnrhStepOutcome` em `FnrhReservationSteps`; `FnrhOperationSteps[FnrhOperation.NO_SHOW]`; `FnrhRemoteReservation.noShow`.

- [ ] **Step 1: Testes que falham** em `FnrhNoShowSendTest` (gravam a submissão `NO_SHOW` pela store, com a reserva do próprio teste levada a `NO_SHOW` pela `ReservationsApi.markNoShow`, e enviam por `FnrhSubmissionSender.sendDueFor(reservationId)`):
  - `a registered reservation is marked no-show once`: registro `SENT` com remota `R`; o no-show faz um `POST …/reservas/R/noshow`, fica `SENT` e a remota passa a `noShow = true` com o link preservado.
  - `no-show waits for the reservation registration`: com o registro ainda `PENDING` e o Serpro em `503` para `POST /reservas`, o `NO_SHOW` não é enviado (nenhum `POST …/noshow`) e continua `PENDING` com 0 tentativas.
  - `no-show without remote reservation is sent without a call`: integração ligada só depois do no-show (registro processado com a reserva já `NO_SHOW` → `SENT` sem chamada); o `NO_SHOW` fica `SENT` sem nenhum pedido ao `FnrhTestServer` para a reserva.
  - `no-show with a write in flight reconciles first`: registro com escrita em curso e sem id; `findReservationByCode` acha `R` → grava a remota e faz o `POST …/noshow`.
  - `no-show of a remote already cancelled or no-show makes no call` (os dois casos).
  - `a refused no-show fails with an alert`: `422` no `POST …/noshow` → `FAILED` `VALIDATION`, alerta `integration-failure` com o rótulo `no-show` e sem dado pessoal.
  - `update after a remote no-show fails locally`: remota `noShow`, reserva reativada (`ReservationsApi` / endpoint de reativação) → a submissão `UPDATE_RESERVATION` fica `FAILED` `REMOTE_NO_SHOW`, sem nenhum `PUT`.
  - `cancel after a remote no-show makes no call`: remota `noShow` → `CANCEL_RESERVATION` `SENT` sem `POST …/cancelar`.
  - `remote reservation round-trips the no-show flag`: `FnrhRemoteIds.saveReservation(… noShow = true …)` e `reservation(...)` devolve `noShow = true`; regravar com `cancelled = true` mantém `noShow`.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhNoShowSendTest'` → vermelho.
- [ ] **Step 3: Implementar** D-E14-3 e D-E14-4: `noShow` usa a remota guardada ou `reconciled(...)` (o mesmo caminho do `cancel`); chama `gateway.markNoShow(remote.remoteId, submission.id.toString())` e grava `remote.copy(noShow = true)` por `remoteIds.saveReservation(..., submission.id)`. Em `update`, a remota `noShow` segue a mesma regra da `cancelled`, com `REMOTE_NO_SHOW`; em `cancel`, remota `noShow` não chama. Registre `FnrhOperation.NO_SHOW to FnrhOperationStep(reservationSteps::noShow)`. Atualize os KDocs de `update` e `cancel`.
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.*'` → verde.
- [ ] **Step 5: Commit** `feat(fnrh): envio do no-show pela fila, com a remota no-show sem reativação` com `Soul: desenvolvedor`.

### Task 4: Listener do `ReservationMarkedNoShow` e o fechamento com o Serpro fora

Estimativa: 40 min · Depende de: Task 3 · Risco: alto (núcleo da etapa; fechamento real)

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionListeners.kt` (`on(event: ReservationMarkedNoShow)`; KDoc da classe passa a citar o no-show)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhNoShowListenerTest.kt` (`@IntegrationTest`)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayFnrhNoShowTest.kt` (`@EndOfDayIntegrationTest` + `@Import(EndOfDayRealStepsTestConfiguration::class, FnrhTestConfiguration::class)`, no modelo de `NoShowStepTest`)

**Interfaces:**
- Consumes: Task 1 (`NO_SHOW`), Task 3 (envio); evento `com.zeny.hotel.reservations.ReservationMarkedNoShow` (`reservationId`, `performedBy`, `version`, `markedAt`).
- Produces: a submissão `NO_SHOW` com `eventKey = "no-show:v<version>"`, `causalVersion = version`, `performedBy` e `occurredAt = markedAt`.

- [ ] **Step 1: Testes que falham:**
  - `FnrhNoShowListenerTest`: `a no-show records one NO_SHOW submission` — reserva do teste levada a no-show por `ReservationsApi.markNoShow`; depois de as publicações terminarem (espera pelo outbox, como `FnrhSubmissionListenersTest`), há exatamente uma submissão `NO_SHOW` `PENDING` da reserva, com `event_key = 'no-show:v<version>'`; `redelivering the event does not duplicate` — chamar `FnrhSubmissionListeners.on(evento)` de novo (antes e depois de marcá-la `SENT`) mantém uma só.
  - `EndOfDayFnrhNoShowTest`: `the end of day completes with the Serpro down and the no-show stays pending` — no banco isolado, liga a FNRH (`enableFnrh`), registra a reserva do teste no Serpro (stub do `POST /reservas` e `sendDueFor`), deixa a chegada vencer, põe o `FnrhTestServer` em `503` para `POST …/noshow`, roda o fechamento real até `COMPLETED`; depois, a reserva é `NO_SHOW`, há uma submissão `NO_SHOW` da reserva e, após `sendDueFor(reservationId)`, ela continua `PENDING` com `attempts = 1` e `lastFailure = SERVER_ERROR`. No `finally`: `EndOfDayTestRuns.settle()`, relógio devolvido e a FNRH desligada (configuração original de volta). Conta só os pedidos da própria reserva no WireMock.
- [ ] **Step 2:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhNoShowListenerTest' --tests 'com.zeny.hotel.endofday.EndOfDayFnrhNoShowTest'` → vermelho (nenhuma submissão gravada).
- [ ] **Step 3: Implementar** o listener `@ApplicationModuleListener fun on(event: ReservationMarkedNoShow) = record(event.reservationId, FnrhOperation.NO_SHOW, "no-show", event.version, event.performedBy, event.markedAt)`.
- [ ] **Step 4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.*' --tests 'com.zeny.hotel.endofday.*' --tests 'com.zeny.hotel.ModularityTest'` → verde.
- [ ] **Step 5: Commit** `feat(fnrh): no-show do fechamento gera submissão à FNRH` com `Soul: desenvolvedor`.

### Task 5: Documentação do no-show na FNRH

Estimativa: 20 min · Depende de: Task 4 · Risco: normal

**Files:**
- Modify: `docs/fnrh/api-fnrh-v2.4.md` (item do no-show em "Consequências para o projeto": o caminho usado é o em lote da reserva, por D-E14-1; o individual fica fora da porta)
- Modify: `docs/SPEC.md` §8.2.17 (porta `FnrhGateway` com `markNoShow`; operação `NO_SHOW`)
- Modify: `docs/ARCHITECTURE.md` §12 (eventos do envio à FNRH passam a incluir o no-show do fechamento, se a seção os lista)
- Modify: `README.md` (item "Envio à FNRH": o no-show do fechamento também é enviado)
- Modify: `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, só o conteúdo da E09 (lista do que a homologação confirma): acrescentar "o no-show em lote `POST /reservas/{id}/noshow` numa reserva sem hóspede (M6 E14, D-E14-1)". Não mexer em tags.

- [ ] **Step 1:** editar os cinco documentos (pt-BR, frases curtas, sem repetir o código).
- [ ] **Step 2:** `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m5-recepcao-fnrh.md` não acusa problema novo (o M5 já tem os seus; compare com a saída antes da edição).
- [ ] **Step 3: Commit** `docs(fnrh): no-show em lote da reserva na porta, SPEC, ARCHITECTURE e README` com `Soul: desenvolvedor`.

## Validação da etapa

- `./gradlew build` → BUILD SUCCESSFUL (inclui `ModularityTest`, `checkOpenApi`, detekt, ktlint, `koverVerify`).
- `.github/scripts/check-api-breaking.sh main` → nenhuma mudança `ERR` em `/api/v1`.
- Critérios de aceite da E14 → Tasks 3 e 4 (única submissão e reentrega; ordem por reserva; Serpro fora), D-E14-1 + Task 5 (caminho e motivo), build e oasdiff.

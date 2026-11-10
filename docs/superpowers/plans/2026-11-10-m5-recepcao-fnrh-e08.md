# M5 E08 — Envio assíncrono e pendências da FNRH: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ligar o `fnrh` aos eventos de reserva e de hospedagem: cada evento grava uma `FnrhSubmission`, que um job envia ao Serpro pela porta da E07, em ordem causal por reserva, com retry, conciliação do resultado incerto, ids remotos e link de pré-check-in guardados, pendências na API (com reenvio e encerramento manual), alerta de falha, métrica e view.

**Architecture:** listeners `@ApplicationModuleListener` no `fnrh` só gravam a submissão (idempotente por reserva, operação e evento), com a `version` da reserva que o evento carrega. Um job com ShedLock pega, por reserva, a submissão não resolvida de menor ordem causal e a envia. Cada passo age sobre o **estado atual** da reserva e dos ids remotos, não sobre a ordem de chegada: lê a reserva pela `ReservationsApi` e os hóspedes pela `GuestsApi.fnrhData` (só em memória), valida localmente, marca a escrita como em curso antes de cada chamada que cria recurso, chama o Serpro **fora de transação** e grava cada id remoto logo depois, em transação própria. O resultado de cada tentativa é gravado por um bean `@Audited @Transactional` separado, que também dispara o alerta. Estados: `PENDING`, `SENT`, `FAILED`, `INVALID`, `CANCELLED`, `DISMISSED`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1 (outbox), ShedLock 7, Hibernate 7.4, Flyway 12, WireMock, Testcontainers.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E08", mais os handoffs de E01, E02, E03 e E07 do mesmo arquivo, `docs/fnrh/api-fnrh-v2.4.md`, SPEC §5.9 e §8.2.17, ARCHITECTURE §3.1, §12 e §13 e as notas do brain `ZenyHotelManager - Módulo fnrh` e `ZenyHotelManager - Eventos e jobs`.

## Decisões desta etapa

| # | Decisão | Por quê | Custo se errada |
|---|---|---|---|
| D-E08-1 | **Eventos → operações:** `ReservationCreated` → `REGISTER_RESERVATION`; `ReservationChanged`, `ReservationConfirmed` e `ReservationReactivated` → `UPDATE_RESERVATION` (o `PUT` reenvia os dados atuais, idempotente); `ReservationCancelled` → `CANCEL_RESERVATION`; `GuestCheckedIn` → `CHECK_IN`; `GuestCheckInUndone` → `UNDO_CHECK_IN`; `GuestCheckedOut` → `CHECK_OUT`. A reserva criada sem documentos completos e a pré-reserva (`PROVISIONAL`) também geram envio: o registro não leva dado pessoal (D-E07-2), e a expiração da pré-reserva vira cancelamento no MTur. `RoomMoved` não gera envio: a API v2.4 não tem UH. Alternativas descartadas: registrar só a partir de `CONFIRMED` (o link de pré-check-in chegaria tarde para a pré-reserva); ignorar `Confirmed` e `Reactivated` (fere "cada evento listado gera exatamente uma submissão"). | Critério da etapa; o `PUT` é a única operação da API que sincroniza a reserva, e reenviar dado igual não cria recurso. | Uma chamada `PUT` a mais por confirmação. Mudar o mapeamento é local ao listener. Vai às "Decisões para o usuário" (a pré-reserva no MTur). |
| D-E08-2 | **Uma submissão por evento**, chave única `(reservation_id, operation, event_key)`; `event_key` = o tipo do evento e a `version` dele (`created` no `ReservationCreated`). O `CHECK_IN` cobre todos os hóspedes da reserva, com o progresso por hóspede em `fnrh.remote_guest`. Diverge da forma da SPEC §8.2.17 (`FnrhSubmission → 0..1 StayGuest`, `remoteId` na submissão); a Task 13 atualiza a SPEC. Alternativa descartada: uma submissão por hóspede (fila mais fina, mas "exatamente uma por evento" deixa de valer e a ordem responsável → menor vira dependência entre submissões). | Reentrega idempotente (`INSERT … ON CONFLICT DO NOTHING`); a tentativa seguinte retoma sem duplicar. | Mudar depois exige migrar a tabela. |
| D-E08-3 | **Ordem causal por reserva:** cada submissão guarda `causal_version` (a `version` da reserva no evento; `0` no `ReservationCreated`) e o posto da operação (`REGISTER_RESERVATION` 0 < `UPDATE_RESERVATION` 1 < `CANCEL_RESERVATION` 2 < `CHECK_IN` 3 < `UNDO_CHECK_IN` 4 < `CHECK_OUT` 5). Só a cabeça da reserva anda: a de menor `(causal_version, posto, sequence)` entre as `PENDING`, `FAILED` e `INVALID`, e só se estiver `PENDING` e vencida. `FAILED` e `INVALID` seguram as seguintes até o `retry` ou o `dismiss` (D-E08-16). O `sequence` (identidade) é só o cursor da API. **Os passos não dependem da ordem de chegada** (o listener de um evento pode chegar depois, pelo reenvio do outbox): ver D-E08-9 e as regras das Tasks 6 a 8. Alternativa descartada: ordem de inserção (o outbox reenvia listener que falhou minutos ou horas depois, e os listeners rodam em paralelo). | O check-out nunca sai antes do check-in, nem o check-in antes do registro (critério), mesmo com eventos fora de ordem. | Uma reserva com submissão parada fica parada até alguém agir; é o que o alerta pede. |
| D-E08-4 | **Retry:** falha temporária (`SERVER_ERROR`, `UNAVAILABLE`, `UNKNOWN_OUTCOME` e o erro interno de D-E08-17) reagenda com espera `1 min × 2^(n−1)` até `1 h` (n = tentativas feitas) e, na **8ª** tentativa com falha, vira `FAILED`. Falha permanente (`VALIDATION`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`) vira `FAILED` na hora. Propriedades `zeny.fnrh.submissions.max-attempts` (8), `initial-backoff` (`PT1M`), `max-backoff` (`PT1H`). Alternativas descartadas, que vão à entrevista: a curva do outbox até 24 h; `retry` em lote; credencial recusada sem gastar a submissão e com alerta único. | Mesma curva do outbox (1, 2, 4… 32 min, 1 h), cerca de 2 h no total: cobre uma queda curta sem segurar a fila por um dia. Limite também em `UNAVAILABLE` (nota da E07). | Numa queda de horas, cada reserva vira `FAILED` com um alerta e um `retry` manual. Os números são propriedade. Vai às "Decisões para o usuário". |
| D-E08-5 | **Sem configuração** (`FnrhConnectionSource.current()` nulo): o job não envia nada e não gasta tentativa; as submissões ficam `PENDING`. Ao ligar, todo o acumulado sai no ambiente escolhido, inclusive estadias antigas: registrado para a E09 e a M8 E13 (corte ou limpeza antes de ligar em homologação). | Desligar a integração (E16) não pode perder envios nem esgotar tentativas. | Homologação recebendo dados reais, se ligada sem corte. |
| D-E08-6 | **Conciliação.** Reserva: `findReservationByCode` quando a escrita estava em curso (D-E08-15). Hóspede: `listReservationGuests` (`GET /reservas/{id}/hospedes`, lendo só `dados[].hospede.hospede_id` e `.pessoa_id`), casando pelo `pessoa_id`, quando a escrita estava em curso. **Pessoa: sempre** `findPersonByDocument` (`GET /pessoas/documento/{tipo}/{numero}`, com `cpf_solicitante`) antes de criar, quando não há `person_id` conhecido (D-E08-7): `dados.id` presente → reaproveita; ausente ou `404` → `registerPerson`. Na conciliação, um `200` sem o campo esperado ou com mais de um casamento é `UNKNOWN_OUTCOME` (falha fechado). `PUT`, cancelamento e `PATCH` de check-out são repetidos, porque não criam recurso; se a repetição de um cancelamento der `VALIDATION`, a submissão fica `FAILED` com alerta. Escolha do `registerPerson` registrada no handoff. | ARCHITECTURE §12: nunca repetir às cegas uma escrita que cria recurso. O PDF indica a consulta por documento "antes de criar um registro"; sem `id`, a pessoa existe no MTur mas não neste meio de hospedagem. | Uma consulta com dado pessoal na URL por hóspede novo (nunca registrar a URL; já é regra do adaptador). O nome `dados.id` vem da observação do PDF: a E09 confirma. |
| D-E08-7 | **Pessoa já conhecida:** se o hóspede já tem `person_id` no mesmo ambiente, de qualquer reserva, o `CHECK_IN` o reaproveita sem consulta nem `POST`. O hóspede é identificado pelo id do **sobrevivente** (`GuestsApi.identification(id).id`; `GuestSummary.id` é o próprio perfil consultado), e um hóspede cujo `person_id` já está entre os `remote_guest` da reserva é pulado, para uma mesclagem não duplicar a pessoa nem o hóspede. | Hóspede recorrente não vira pessoa nova; a API não atualiza pessoa. | Dado alterado no perfil não chega ao Serpro numa estadia seguinte; a E09 confirma se é preciso. Um hóspede enviado antes de ser absorvido numa mesclagem não é achado pelo id do sobrevivente: a consulta por documento cobre. |
| D-E08-8 | **Desfazer check-in:** a API não tem a operação. Ao gravar o `UNDO_CHECK_IN`, o listener cancela (`CANCELLED`) os `CHECK_IN` da reserva não resolvidos, **sem escrita em curso** e sem hóspede no Serpro. No envio, se nenhum hóspede da reserva chegou ao Serpro, o `UNDO_CHECK_IN` fica `SENT` sem chamada; se algum chegou, fica `FAILED` com `NO_REMOTE_OPERATION` e alerta (correção manual no portal e `dismiss`). Um novo check-in pula os hóspedes já no Serpro. Um `CHECK_IN` que sobrevive ao desfazer (escrita em curso ou parcialmente enviado) **não cria nada novo** se a reserva não tem mais check-in: só concilia a escrita em curso, grava os ids do que já existe no Serpro e termina `SENT`; o `UNDO_CHECK_IN` decide depois. | Não há caminho técnico do nosso lado; o alerta põe a pessoa certa para corrigir no portal. | Correção manual rara. Vai às "Decisões para o usuário". |
| D-E08-9 | **Passos pelo estado atual:** registro de reserva que já está cancelada (ou no-show) e não tem id remoto → `SENT` sem chamada; cancelamento sem id remoto → `SENT` sem chamada; `UPDATE_RESERVATION` sem id remoto registra (sem o `PUT`); `UPDATE_RESERVATION` com a reserva remota cancelada por nós → `FAILED` com `REMOTE_CANCELLED`, sem chamada, e alerta (o `numero_reserva` é único e a API não reativa); `CHECK_OUT` de reserva com hóspedes com check-in feito e sem hóspede no Serpro roda antes o check-in deles, e nunca fica `SENT` vazio. | C1 da revisão: a chegada fora de ordem não pode perder um registro legal. | Correção manual rara na reativação. Vai às "Decisões para o usuário". |
| D-E08-10 | **Validação local** só no `CHECK_IN` (e no check-in que o `CHECK_OUT` roda antes): todos os hóspedes são validados antes de qualquer chamada; com erro, `INVALID`, erros por hóspede, campo e código em `fnrh.submission_error` (sem valor) e alerta. A residência nula continua aceita (comportamento da E07) e o `checkedOutAt` não é validado: o check-out usa `StayGuestSummary.checkedOutAt` ou, sem ele, o instante do evento. | H21; o check-in nunca é bloqueado (RF-FNRH-03). | Nenhum além do da E07. |
| D-E08-11 | **Alerta** a cada passagem a `FAILED` ou `INVALID`: `NotificationsApi.notify(NotificationTypes.INTEGRATION_FAILURE, "Falha no envio à FNRH", "Reserva <código>: <operação> — <situação>", NotificationRecipients.withPermission(FnrhPermissions.SUBMISSIONS_MANAGE))`. Só o código da reserva e rótulos fixos. | RF-FNRH-03, RF-NOT-01; nunca dado de hóspede no texto. | Nenhum. |
| D-E08-12 | **Permissão nova** `fnrh.submissions.manage` (não sensível), só para o Gerente; `fnrh.submissions.read` continua com Gerente e Recepcionista. `POST /api/v1/fnrh/submissions/{id}/retry` aceita só `FAILED` e `INVALID` (senão `409 FNRH_SUBMISSION_NOT_RETRYABLE`), zera as tentativas e os erros e **preserva** a escrita em curso; sem `@Idempotent` (repetir dá `409`, sem efeito duplo). | H20. O `retry` não tem efeito financeiro e é idempotente pela regra de estado. | Um código publicado não muda. |
| D-E08-13 | Ids remotos **por ambiente** (`environment` na chave de `remote_reservation` e `remote_guest`): trocar de ambiente faz a próxima submissão registrar de novo. **O link de pré-check-in é guardado** em `fnrh.remote_reservation`, **cifrado** (`FieldCipher`), como pede a RF-FNRH-04: é uma URL que dá acesso ao preenchimento de dados pessoais. A E08 não o expõe na API (nenhum cliente o usa ainda; a confirmação da E11 pode usá-lo). | Nota da E07 (ids por ambiente); SPEC §5.9. | Expor depois é aditivo. Mais um valor para a re-cifragem da M8 E15. |
| D-E08-14 | O `fnrh` passa a depender de `reservations` e `notifications` (já na ARCHITECTURE §3.1); o `ModularityTest` do `fnrh` muda na mesma tarefa. | Restrição do roadmap. | Nenhum. |
| D-E08-15 | **Escrita em curso:** antes de cada chamada que cria recurso (`POST /reservas`, `POST /pessoas`, `POST /reservas/{id}/hospedes`), a submissão grava `in_flight = true` em transação própria; o id gravado depois da chamada a limpa. Uma tentativa que encontra `in_flight` concilia antes de repetir (D-E08-6). Outra falha e o `retry` **preservam** a marca. | I1 da revisão: queda do processo entre o 2xx e a gravação do id não pode virar repetição às cegas. | Uma consulta a mais depois de cada falha numa escrita. |
| D-E08-16 | **Encerramento manual:** `POST /api/v1/fnrh/submissions/{id}/dismiss` (`fnrh.submissions.manage`, `@Audited`), só de `FAILED` ou `INVALID` (senão `409 FNRH_SUBMISSION_NOT_DISMISSABLE`), leva ao estado terminal `DISMISSED`, que **não segura a fila**. Para quem corrigiu no portal (`NO_REMOTE_OPERATION`, `REMOTE_CANCELLED`) ou para um `PUT` recusado que seguraria o check-out. | I2 da revisão: sem ele, uma falha que o `retry` não resolve trava a reserva para sempre. `status` é `x-extensible-enum`: valor novo é aditivo. | Um código publicado não muda. |
| D-E08-17 | **Isolamento por submissão:** o envio de cada submissão roda em `try/catch`; exceção que não é `FnrhGatewayException` conta como falha temporária `INTERNAL_ERROR`, com log só de ids. O recorder é no-op (com log) numa submissão que já está em estado terminal (por exemplo, cancelada pelo listener do desfazer enquanto o job a enviava). | I4 da revisão: uma submissão envenenada não pode parar o lote. | Nenhum. |

## Revisão de arquitetura

- **Veredito:** aprovado com notas.
- **Rodadas:** 2.
- **Modelo:** opus (`revisor-arquitetura`).
- **Notas Menores** (aplicadas no plano depois da aprovação, sem re-revisão, por serem pontuais):
  - M-a: o `UNDO_CHECK_IN` superado por um novo check-in fica `SENT` sem chamada (Task 8).
  - M-b: a conferência "pessoa já na reserva" vale para o `person_id` vindo de `knownPerson` ou da consulta por documento (Task 7).
- Premissas que só a E09 confirma: `dados.id` na consulta por documento, documento repetido no `POST /pessoas`, `PUT` numa reserva `EM_ANDAMENTO`, link de pré-check-in no `GET /reservas`.

## Global Constraints

- Respeitar o grafo da ARCHITECTURE §3.1: `fnrh` → `shared`, `guests`, `reservations`, `notifications`; nunca `iam`, `frontdesk` nem tabela de outro schema. `frontdesk` nunca chama `fnrh`.
- Falha da FNRH nunca bloqueia check-in nem check-out (RF-FNRH-03): o listener só grava a submissão; o envio nunca acontece na transação do evento.
- Dados sensíveis (RN-19: raça/cor, deficiência) e número inteiro de documento só em memória, durante o envio, lidos por `GuestsApi.fnrhData`; nunca em tabela, view, log, trilha, evento, notificação nem `toString`. `Cpf.toString()` devolve os dígitos: nunca interpole `Cpf`. As respostas das consultas do Serpro trazem dado pessoal: o adaptador lê só os ids.
- Toda chamada ao Serpro roda **fora** de transação do banco. Escrita `@Audited` em bean separado de quem chama (a autoinvocação pula o proxy). Classes de `application` dependem de interfaces (`FnrhDomainCatalog`, portas), nunca de `infrastructure`.
- Todo listener é idempotente (o outbox reentrega, inclusive fora de ordem).
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
2. Integração desligada quando o job roda: as submissões ficam `PENDING`, sem gastar tentativa, e saem quando ligada (Task 6).
3. Eventos fora de ordem (o listener do registro chega depois do check-in, do cancelamento ou do check-out): nenhum registro legal se perde, nenhuma reserva cancelada é registrada, nenhum check-out fica `SENT` vazio (Tasks 2, 6, 7 e 8).
4. Queda do processo entre o 2xx do Serpro e a gravação do id: a tentativa seguinte concilia e não duplica reserva, pessoa nem hóspede (Tasks 1, 6 e 7).
5. Menor listado antes do responsável na reserva: o responsável entra primeiro e o menor leva o `responsavel_id` dele (Task 7).

## Ondas

Em sequência, no worktree da etapa (ruling da E15: o harness recusa git de subagente em outro worktree). Ordem: 1 → 2 → … → 13.

---

### Task 1: Domínio da submissão e política de retry

**Estimativa:** 30 min. **Depende de:** nenhuma. **Risco:** alto (núcleo da etapa).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmission.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/domain/FnrhRetryPolicy.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhErrorCodes.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/domain/FnrhSubmissionTest.kt`, `FnrhRetryPolicyTest.kt`

**Interfaces:**
- Produces (pacote `domain`, entidade JPA como `FnrhRequester`, tabela `fnrh.submission` da Task 2):
  - `enum class FnrhOperation(val rank: Int) { REGISTER_RESERVATION(0), UPDATE_RESERVATION(1), CANCEL_RESERVATION(2), CHECK_IN(3), UNDO_CHECK_IN(4), CHECK_OUT(5) }`
  - `enum class FnrhSubmissionStatus { PENDING, SENT, FAILED, INVALID, CANCELLED, DISMISSED }`
  - `enum class FnrhSubmissionFailure` com os valores de `FnrhFailure` menos `NOT_CONFIGURED`, mais `INTERNAL_ERROR` (temporária), `REMOTE_CANCELLED` e `NO_REMOTE_OPERATION`.
  - `data class FnrhSubmissionError(val guestId: UUID, val field: String, val code: String)` (embutível; nunca valor).
  - `class FnrhSubmission` com `id`, `reservationId: UUID`, `reservationCode: String`, `operation`, `eventKey: String`, `causalVersion: Long`, `sequence: Long?` (gerado pelo banco), `status`, `attempts: Int`, `nextAttemptAt: Instant`, `lastFailure: FnrhSubmissionFailure?`, `inFlight: Boolean`, `performedBy: UUID?`, `occurredAt: Instant`, `createdAt: Instant`, `sentAt: Instant?`, `errors: List<FnrhSubmissionError>`, `@Version version: Long`; `companion fun create(reservationId, reservationCode, operation, eventKey, causalVersion, performedBy, occurredAt, now): FnrhSubmission`.
  - Transições: `markInFlight()`; `clearInFlight()`; `markSent(now)`; `recordFailure(failure: FnrhSubmissionFailure, now: Instant, policy: FnrhRetryPolicy): Boolean` (devolve `true` se virou `FAILED`; temporárias = `SERVER_ERROR`, `UNAVAILABLE`, `UNKNOWN_OUTCOME`, `INTERNAL_ERROR`); `fail(reason: FnrhSubmissionFailure, now)` (local, permanente); `markInvalid(errors, now)`; `cancel(now)`; `retry(now)`; `dismiss(now)`; `isTerminal: Boolean` (`SENT`, `CANCELLED`, `DISMISSED`).
  - `fun FnrhFailure.toSubmissionFailure(): FnrhSubmissionFailure` (`NOT_CONFIGURED` → `IllegalArgumentException`).
  - `data class FnrhRetryPolicy(val maxAttempts: Int = 8, val initialBackoff: Duration = Duration.ofMinutes(1), val maxBackoff: Duration = Duration.ofHours(1)) { fun delayAfter(attempts: Int): Duration }`.
  - `FnrhErrorCodes.SUBMISSION_NOT_RETRYABLE = "FNRH_SUBMISSION_NOT_RETRYABLE"`, `SUBMISSION_NOT_DISMISSABLE = "FNRH_SUBMISSION_NOT_DISMISSABLE"`.

- [ ] **Step 1: Escreva os testes que falham**

`FnrhRetryPolicyTest`: `delayAfter(1..7)` = 1, 2, 4, 8, 16, 32, 60 min; `delayAfter(20)` = 60 min.

`FnrhSubmissionTest`:
- `create` deixa `PENDING`, `attempts = 0`, `nextAttemptAt = now`, `inFlight = false`.
- `recordFailure(SERVER_ERROR)` 7 vezes: segue `PENDING`, `attempts = 7`, `nextAttemptAt = now + delayAfter(7)`, devolve `false`; na 8ª, `FAILED`, `lastFailure = SERVER_ERROR`, devolve `true`. O mesmo com `INTERNAL_ERROR`.
- `recordFailure(VALIDATION)` na 1ª tentativa: `FAILED` na hora.
- `markInFlight` liga a marca; `recordFailure` (de qualquer tipo), `markInvalid` e `retry` **a preservam**; `clearInFlight` e `markSent` a desligam.
- `markInvalid` grava os erros, `INVALID`; `retry` de `FAILED` e de `INVALID` volta a `PENDING` com `attempts = 0`, sem erros, `lastFailure = null`, `nextAttemptAt = now`.
- `retry` de `PENDING`, `SENT`, `CANCELLED` ou `DISMISSED` → `ConflictException` com `FNRH_SUBMISSION_NOT_RETRYABLE`; `dismiss` só de `FAILED` e `INVALID` (senão `ConflictException` com `FNRH_SUBMISSION_NOT_DISMISSABLE`).
- Toda outra transição a partir de estado terminal → `IllegalStateException`.
- `toString` não traz `reservationCode` nem erros.

- [ ] **Step 2: Rode e veja falhar**

Run: `./gradlew test --tests 'com.zeny.hotel.fnrh.domain.FnrhSubmissionTest' --tests 'com.zeny.hotel.fnrh.domain.FnrhRetryPolicyTest'`
Expected: falha de compilação.

- [ ] **Step 3: Implemente** as classes do bloco "Produces", com os mapeamentos JPA das colunas da Task 2 (`errors` como `@ElementCollection` em `fnrh.submission_error`, chave `submission_id`). Use a `ConflictException` do kernel, como os outros módulos para `409`.

- [ ] **Step 4: Rode** o comando do Step 2. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 2: Migração, persistência e portas

**Estimativa:** 40 min. **Depende de:** Task 1. **Risco:** alto (persistência, migração, ordem causal).

**Files:**
- Create: `src/main/resources/db/migration/fnrh/V2026_11_10_1000__create_fnrh_submissions.sql`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionPorts.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhSubmissionPersistence.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionPersistenceTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Consumes: Task 1; `FieldCipher` do kernel (para o link).
- Produces (em `FnrhSubmissionPorts.kt`):
  - `interface FnrhSubmissionStore { fun recordIfAbsent(submission: FnrhSubmission): Boolean; fun find(id: UUID): FnrhSubmission?; fun save(submission: FnrhSubmission); fun markInFlight(id: UUID); fun dueHeads(now: Instant, limit: Int, reservationId: UUID? = null): List<UUID>; fun unresolvedOf(reservationId: UUID, operation: FnrhOperation): List<FnrhSubmission>; fun page(status: FnrhSubmissionStatus?, after: Long?, limit: Int): List<FnrhSubmission>; fun countByStatus(): Map<FnrhSubmissionStatus, Long> }` — `markInFlight` em transação própria (`REQUIRES_NEW`).
  - `data class FnrhRemoteReservation(val reservationId: UUID, val environment: FnrhEnvironment, val remoteId: FnrhRemoteId, val precheckinLink: String?, val cancelled: Boolean)` (`toString` sem o link).
  - `data class FnrhRemoteGuest(val reservationId: UUID, val guestId: UUID, val environment: FnrhEnvironment, val personId: FnrhRemoteId, val guestRemoteId: FnrhRemoteId?, val checkedOut: Boolean)`
  - `interface FnrhRemoteIds { fun reservation(reservationId: UUID, env: FnrhEnvironment): FnrhRemoteReservation?; fun saveReservation(r: FnrhRemoteReservation, submissionId: UUID); fun guests(reservationId: UUID, env: FnrhEnvironment): List<FnrhRemoteGuest>; fun saveGuest(g: FnrhRemoteGuest, submissionId: UUID); fun knownPerson(guestId: UUID, env: FnrhEnvironment): FnrhRemoteId? }` — toda escrita em transação própria (`REQUIRES_NEW`), porque é chamada entre chamadas ao Serpro, e **limpa o `in_flight` da submissão `submissionId` na mesma transação** em que grava o id (D-E08-15). A marca só se liga por `FnrhSubmissionStore.markInFlight` e só se limpa por essas gravações ou por `markSent`.

**Esquema** (uma migração; sem FK para outro schema):
- `fnrh.submission`: `id uuid PK`, `reservation_id uuid NOT NULL`, `reservation_code text NOT NULL`, `operation text NOT NULL` (CHECK com os 6 valores), `operation_rank smallint NOT NULL`, `event_key text NOT NULL`, `causal_version bigint NOT NULL CHECK (causal_version >= 0)`, `sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE`, `status text NOT NULL` (CHECK com os 6), `attempts int NOT NULL CHECK (attempts >= 0)`, `next_attempt_at timestamptz NOT NULL`, `last_failure text NULL` (CHECK), `in_flight boolean NOT NULL`, `performed_by uuid NULL`, `occurred_at timestamptz NOT NULL`, `created_at timestamptz NOT NULL`, `sent_at timestamptz NULL`, `version bigint NOT NULL`; `UNIQUE (reservation_id, operation, event_key)`; índice `(reservation_id, causal_version, operation_rank, sequence) WHERE status IN ('PENDING','FAILED','INVALID')` e índice `(status, sequence)`.
- `fnrh.submission_error`: `submission_id uuid FK → fnrh.submission`, `guest_id uuid`, `field text`, `code text`, `PK (submission_id, guest_id, field, code)`.
- `fnrh.remote_reservation`: `reservation_id`, `environment` (CHECK `HOMOLOGATION`/`PRODUCTION`), `remote_id text NOT NULL`, `precheckin_link text NULL` (cifrado pelo `FieldCipher`, prefixo `v1:` como as outras colunas cifradas), `cancelled boolean NOT NULL`, `PK (reservation_id, environment)`.
- `fnrh.remote_guest`: `reservation_id`, `guest_id`, `environment`, `person_id text NOT NULL`, `guest_remote_id text NULL`, `checked_out boolean NOT NULL`, `PK (reservation_id, guest_id, environment)`; índice `(guest_id, environment)`.
- `GRANT SELECT, INSERT, UPDATE` ao `"${app_role}"` nas quatro (modelo de `V2026_11_04_1001`), e `DELETE` só em `submission_error` (o `retry` limpa os erros). Nenhuma coluna de texto livre do Serpro, de documento, raça ou deficiência.

- [ ] **Step 1: Escreva os testes que falham** (`FnrhSubmissionPersistenceTest`):
- `recordIfAbsent` duas vezes com a mesma chave → `true`, depois `false`, uma linha.
- `dueHeads` pela ordem causal, **independente da ordem de inserção**: grave pelo store, nesta ordem, um `CHECK_OUT` (versão 5), um `CHECK_IN` (versão 3) e um `REGISTER_RESERVATION` (versão 0) da mesma reserva → a cabeça é o registro; com ele `SENT`, o check-in; com o check-in `FAILED`, nenhuma; com o check-in `DISMISSED`, o check-out. Mesma versão (walk-in): `REGISTER_RESERVATION` antes de `CHECK_IN`. `PENDING` com `nextAttemptAt` no futuro não sai; o filtro `reservationId` limita; `limit` respeitado.
- `save` com erros e `retry` → os erros somem do banco; dois erros no mesmo campo com códigos diferentes gravam.
- `markInFlight` grava mesmo com a transação do chamador desfeita; `saveReservation` e `saveGuest` gravam o id e limpam o `in_flight` da submissão juntos.
- `FnrhRemoteIds`: grava e lê reserva (com o link) e hóspede por ambiente; a coluna `precheckin_link` lida por SQL não é o link em claro; `knownPerson` acha o `person_id` do mesmo hóspede em outra reserva do mesmo ambiente e não no outro ambiente.
- Esquema: nenhuma coluna das quatro tabelas se chama ou contém `document`, `race`, `disability`, `name`, `message` (`information_schema.columns`).

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionPersistenceTest'`. Expected: FAIL.

- [ ] **Step 3: Implemente** a migração, as portas e os adaptadores (`JpaRepository` para a submissão; `JdbcClient` para `dueHeads`, com `DISTINCT ON (reservation_id) … ORDER BY reservation_id, causal_version, operation_rank, sequence` sobre as não resolvidas e depois o filtro `status = 'PENDING' AND next_attempt_at <= :now`, e para os ids remotos).

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
- Consumes: Tasks 1 e 2; eventos de `reservations/ReservationsEvents.kt`; `ReservationsApi.reservation(id)` para o `code`; `FnrhConnectionSource` e `FnrhRemoteIds` (desfazer).
- Produces: `class FnrhSubmissionListeners` com um `@ApplicationModuleListener fun on(event: X)` por evento de D-E08-1. `event_key`: `created`, `changed:v<version>`, `confirmed:v<version>`, `reactivated:v<version>`, `cancelled:v<version>`, `checked-in:v<version>`, `check-in-undone:v<version>`, `checked-out:v<version>`; `causalVersion` = `version` do evento (`0` no `ReservationCreated`). `performedBy` vem dos eventos de hospedagem; `occurredAt` é o instante do evento.
- No `GuestCheckInUndone`, na mesma transação: cada `CHECK_IN` em `unresolvedOf(…, CHECK_IN)` **sem `inFlight`** e sem `FnrhRemoteGuest` da reserva no ambiente atual (ou sem configuração) vira `CANCELLED` (D-E08-8).
- Reserva que a `ReservationsApi` não acha: log `WARN` com o id e nada gravado.

- [ ] **Step 1: Escreva os testes que falham:** para cada um dos 8 eventos, criado pelo fluxo real (reserva e check-in pela API, com os `…TestSupport` de reservas e recepção), exatamente uma submissão com a operação de D-E08-1, o `reservationCode`, a `causalVersion` do evento e, nos de hospedagem, o `performedBy` do usuário; chamar o listener de novo com o mesmo evento (bean injetado) não cria outra, inclusive com a submissão já `SENT`; o check-in desfeito cancela o `CHECK_IN` pendente e **não** cancela um com `inFlight`; walk-in gera `REGISTER_RESERVATION` e `CHECK_IN`. `ModularityTest` com as dependências novas.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionListenersTest' --tests 'com.zeny.hotel.ModularityTest'`.

- [ ] **Step 3: Implemente.**

- [ ] **Step 4: Rode** o comando do Step 2 e as suítes que usam a quiescência: `--tests '*Inventory*' --tests '*Offline*' --tests 'com.zeny.hotel.MilestoneM4EndToEndTest'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 4: Operações de conciliação na porta e no adaptador

**Estimativa:** 30 min. **Depende de:** Task 3 (em série). **Risco:** alto (integração externa, documento na URL, resposta com dado pessoal).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhGateway.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGateway.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhWire.kt`
- Modify (test): `src/test/kotlin/com/zeny/hotel/testsupport/FnrhTestServer.kt` (`stubListReservationGuests_v2_4`, `stubFindPersonByDocument_v2_4`, estritos como os outros)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/infrastructure/SerproFnrhGatewayReconciliationTest.kt`

**Interfaces:**
- Produces:
  - `data class FnrhRemoteGuestRef(val guestId: FnrhRemoteId, val personId: FnrhRemoteId)`
  - `fun listReservationGuests(reservationId: FnrhRemoteId, correlationRef: String): List<FnrhRemoteGuestRef>` → `GET /reservas/{id}/hospedes` (sem `cpf_solicitante`); lê só `dados[].hospede.hospede_id` e `dados[].hospede.pessoa_id`.
  - `fun findPersonByDocument(document: FnrhDocument, requesterCpf: Cpf, correlationRef: String): FnrhRemoteId?` → `GET /pessoas/documento/{tipo}/{numero}` com `cpf_solicitante`; `dados.id` presente → o id; `dados` sem `id` ou `404` → `null`.
- Formas das respostas: `docs/fnrh/api-fnrh-v2.4.md` (seções "Reservas" e "Pessoas"), conferidas no PDF oficial; o nome `dados.id` vem da observação do PDF (KDoc: a E09 confirma). **Falha fechada:** `200` sem `dados`, ou item de `dados[]` sem `hospede.hospede_id`/`hospede.pessoa_id`, → `UNKNOWN_OUTCOME`. Nenhum campo pessoal da resposta é lido, guardado ou logado.

- [ ] **Step 1: Escreva os testes que falham:** caminho, método, Basic Auth e presença (só em `findPersonByDocument`) e ausência do `cpf_solicitante`; `tipo` = `CPF`/`PASSAPORTE` como no `registerPerson`; respostas com os corpos de exemplo do levantamento (com nome, telefone e e-mail) mapeadas só aos ids; `dados` sem `id` → `null`; `404` → `null`; `200` com item sem `hospede_id` → `UNKNOWN_OUTCOME`; `500` → `SERVER_ERROR`; timeout de leitura num `GET` → `UNAVAILABLE`; com `OutputCaptureExtension`, nem log nem mensagem da exceção trazem o número do documento, a URL, o nome nem o e-mail da resposta.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.infrastructure.SerproFnrhGatewayReconciliationTest'`.

- [ ] **Step 3: Implemente** pelo mesmo caminho das leituras existentes (`findReservationByCode`), com o registro em `fnrh.integration_call` e o saneador.

- [ ] **Step 4: Rode** o Step 2 e `--tests 'com.zeny.hotel.fnrh.infrastructure.*'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 5: Propriedades e montagem do envio

**Estimativa:** 15 min. **Depende de:** Task 1. **Risco:** normal.

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhProperties.kt`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhSubmissionConfiguration.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionConfigurationTest.kt`

**Interfaces:**
- Produces: `FnrhProperties.submissions` com `interval` (`PT1M`), `initialDelay` (`PT1M`), `batchSize` (50), `maxAttempts` (8), `initialBackoff` (`PT1M`), `maxBackoff` (`PT1H`), em `zeny.fnrh.submissions.*`. Beans: `FnrhRetryPolicy` (das propriedades), `IbgeMunicipalities` (um só, carregado uma vez) e `FnrhLocalValidator(catalog: FnrhDomainCatalog, municipalities)`.

- [ ] **Step 1: Escreva o teste que falha:** o contexto tem um `FnrhRetryPolicy` com 8, 1 min e 1 h, e um único `IbgeMunicipalities`; o validador do contexto acusa município inexistente.
- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionConfigurationTest'` falha, implemente, passa.
- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 6: Envio — núcleo e operações da reserva

**Estimativa:** 45 min. **Depende de:** Tasks 2 a 5. **Risco:** alto (núcleo, integração externa).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionRecorder.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhAudit.kt` (`SEND_SUBMISSION`, `RETRY_SUBMISSION`, `DISMISS_SUBMISSION` e o retrato)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhReservationSendTest.kt` (`@IntegrationTest`, `MutableClockConfiguration`, WireMock)

**Interfaces:**
- Consumes: Tasks 1, 2, 4 e 5; `FnrhConnectionSource`, `ReservationsApi.reservation` e `booking`, `GuestsApi.guest`, `FnrhGateway`.
- Produces:
  - `data class FnrhSendRun(val sent: Int, val postponed: Int, val failed: Int, val invalid: Int)`
  - `FnrhSubmissionSender.sendDue(): FnrhSendRun` (até `batchSize`) e `sendDueFor(reservationId: UUID): FnrhSendRun`. **Sem `@Transactional`**: lê, chama o Serpro e grava pelo recorder. Cada submissão em `try/catch` (D-E08-17).
  - `FnrhSubmissionRecorder` (bean separado, `@Audited(FnrhAudit.SEND_SUBMISSION) @Transactional`): `fun sent(id: UUID)`, `fun failed(id: UUID, failure: FnrhSubmissionFailure)`, `fun failedLocally(id: UUID, reason: FnrhSubmissionFailure)`, `fun invalid(id: UUID, errors: List<FnrhSubmissionError>)`. No-op com log numa submissão terminal. O retrato da trilha leva id, operação, situação, tentativas e `lastFailure`; nunca o código da reserva nem dado de hóspede.
  - `fun interface FnrhOperationStep { fun send(submission: FnrhSubmission, connection: FnrhConnection): FnrhStepOutcome }`; `sealed interface FnrhStepOutcome { data object Sent; data class Invalid(val errors: List<FnrhSubmissionError>); data class FailedLocally(val reason: FnrhSubmissionFailure) }`. Falha do Serpro sobe como `FnrhGatewayException`. Esta tarefa registra os passos de `REGISTER_RESERVATION`, `UPDATE_RESERVATION` e `CANCEL_RESERVATION` num `Map<FnrhOperation, FnrhOperationStep>`; as Tasks 7 e 8 registram os demais.
  - `class FnrhReservationSync` (no arquivo do sender, público no pacote) com `fun ensureReservation(submission: FnrhSubmission, reservation: ReservationSummary, connection: FnrhConnection): FnrhRemoteId`: id guardado; senão, com `inFlight`, `findReservationByCode` (achou → grava o id, que limpa a marca); senão `markInFlight` → `registerReservation` → grava o id e o link (cifrado), o que limpa a marca. Conciliada por `findReservationByCode`, o link fica nulo (nota para a E09).
- Regras (D-E08-5, 6, 9, 15):
  - Sem conexão, nada é tocado.
  - `FnrhReservationData` sai da reserva e do `booking`: `otaCode = booking.externalId`; `minors` = o maior entre `childAges.size` e a contagem de hóspedes listados menores de 18 anos na chegada (`GuestsApi.guest(id).birthDate`).
  - `REGISTER_RESERVATION`: com `inFlight` e sem id remoto, concilia antes por `findReservationByCode` (achou → grava o id, mesmo com a reserva já cancelada, para o cancelamento seguinte o cancelar). Depois: reserva cancelada ou no-show sem id remoto → `Sent` sem chamada; senão `ensureReservation`.
  - `UPDATE_RESERVATION`: remota cancelada → `FailedLocally(REMOTE_CANCELLED)`; sem id remoto → `ensureReservation` (sem `PUT`); senão `updateReservation`.
  - `CANCEL_RESERVATION`: sem id remoto → `Sent` sem chamada; com ele, `cancelReservation` e `cancelled = true`.
  - `correlationRef` = id da submissão.

- [ ] **Step 1: Escreva os testes que falham:**
- Registro com sucesso grava o id remoto e o link (cifrado na coluna) e fica `SENT`; reserva de OTA envia `numero_reserva_ota` = `externalId` e `origem_reserva_id = OTA` (corpo no WireMock).
- `503` três vezes com o `MutableClock`: `attempts` 1, 2, 3 e `nextAttemptAt` = +1, +2, +4 min; antes de vencer, `sendDueFor` não chama o Serpro; na 8ª falha, `FAILED`.
- Timeout de leitura no `POST /reservas` → na tentativa seguinte, `findReservationByCode` acha a reserva e **não** há segundo `POST /reservas`.
- Queda simulada depois do 2xx: deixe a submissão `PENDING` com `inFlight = true` e sem id remoto (pelo store) e o Serpro com a reserva no `findReservationByCode` → nenhum `POST /reservas`.
- Integração desligada: `sendDueFor` não chama nada e `attempts` segue 0; ligada, envia.
- Registro com `inFlight` de uma reserva já cancelada, com o Serpro achando-a no `findReservationByCode`: o id é gravado, nenhum `POST /reservas`, e o `CANCEL_RESERVATION` seguinte chama o `POST /reservas/{id}/cancelar`.
- Fora de ordem: `CANCEL_RESERVATION` gravado antes do registro (pelo store) → o cancelamento fica `SENT` sem chamada e o registro, que chega depois, também (reserva cancelada) — nenhuma chamada ao Serpro.
- `UPDATE_RESERVATION` com a remota cancelada → `FAILED` com `REMOTE_CANCELLED`, sem chamada.
- Submissão envenenada (reserva inexistente na `ReservationsApi`) e outra saudável no mesmo `sendDue`: a saudável sai; a envenenada fica `PENDING` com `INTERNAL_ERROR` e `attempts = 1`.
- A trilha da submissão enviada não traz o código da reserva.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhReservationSendTest'`.

- [ ] **Step 3: Implemente.**

- [ ] **Step 4: Rode** o Step 2. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 7: Envio do check-in

**Estimativa:** 45 min. **Depende de:** Task 6. **Risco:** alto (dado pessoal e sensível, integração externa).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhCheckInStep.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt` (registra o passo)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhCheckInSendTest.kt` (`@IntegrationTest`, WireMock, `OutputCaptureExtension`)

**Interfaces:**
- Consumes: Tasks 2, 4, 5 e 6 (`FnrhReservationSync.ensureReservation`, `FnrhRemoteIds`, `FnrhSubmissionStore.markInFlight`, `FnrhOperationStep`); `GuestsApi.identification` e `fnrhData`, `FnrhPersonMapper.toPerson`, o `FnrhLocalValidator` da Task 5, `RequesterCpfPolicy.choose(submission.performedBy, FnrhRequesterDirectory::cpfOf, connection.defaultRequesterCpf)`.
- Produces: `class FnrhCheckInStep : FnrhOperationStep` para `CHECK_IN`, com `fun registerGuests(submission, reservation, connection): FnrhStepOutcome` público no pacote (a Task 8 o chama).
- Regras:
  0. Se a reserva não tem mais check-in (situação diferente de `IN_HOUSE` e `CHECKED_OUT`, ou nenhum hóspede com `checkedInAt`): com `inFlight`, concilia por `listReservationGuests` e grava os ids do que já existe no Serpro; não valida nem cria nada; `Sent` (D-E08-8).
  1. Lê a reserva; para cada `StayGuestSummary`: id do sobrevivente (`GuestsApi.identification(id).id`, D-E08-7); `fnrhData` → `toPerson`; `FnrhGuest(principal = primary, checkedInAt = checkedInAt ?: submission.occurredAt, travelReason, transportMode, responsibleGuestId = null)`; `responsibleKnown` = há `minorAuthorization` e o `responsibleGuestId` dela é hóspede da reserva. Valida **todos** antes de qualquer chamada; com erro, `Invalid` com `FnrhSubmissionError(guestId, field, code)`.
  2. `ensureReservation`.
  3. Ordem: hóspedes sem `minorAuthorization` primeiro (o principal à frente), depois os menores.
  4. Por hóspede ainda sem `guestRemoteId`: pessoa = `knownPerson`; senão `findPersonByDocument` (D-E08-6). Obtido o `person_id` por qualquer dos dois, se ele já está num `remote_guest` da reserva com `guestRemoteId`, pule o hóspede. Sem pessoa, `markInFlight` → `registerPerson` → `saveGuest` com o `person_id` (limpa a marca). Hóspede: com `inFlight`, procure em `listReservationGuests` o `pessoa_id`; senão `markInFlight` → `addGuestToReservation` com o `responsibleGuestId` = `guestRemoteId` do responsável → `saveGuest` (limpa a marca).
  5. Ao fim, `Sent`.

- [ ] **Step 1: Escreva os testes que falham:**
- Check-in com dois adultos: uma consulta por documento, uma pessoa e um hóspede por adulto, ids gravados, `SENT`; `cpf_solicitante` = CPF do usuário do check-in quando cadastrado em `/fnrh/requesters`, senão o padrão.
- Pessoa já cadastrada no meio de hospedagem (`GET /pessoas/documento` com `dados.id`): nenhum `POST /pessoas`.
- Menor listado antes do responsável: o `POST /reservas/{id}/hospedes` do menor leva `responsavel_id` = o `hospede_id` do responsável, e o do responsável vem antes.
- Hóspede sem gênero (não coletado): `INVALID` com `person.gender`/`REQUIRED` e o `guestId`, **nenhuma** chamada ao Serpro.
- Falha no `addGuest` do 2º hóspede (`503`) e nova tentativa: o 1º não é reenviado.
- Timeout no `POST /reservas/{id}/hospedes` → na tentativa seguinte, `GET /reservas/{id}/hospedes` acha o `pessoa_id` e não há segundo `POST`.
- Walk-in pela API da recepção: `REGISTER_RESERVATION` sai antes e o `CHECK_IN` usa o id dele.
- Reserva sem `REGISTER_RESERVATION` (apague a submissão no teste, como uma reserva anterior à E08): o `CHECK_IN` registra a reserva antes.
- Hóspede recorrente (segunda reserva, mesmo hóspede): nem consulta nem `POST /pessoas`.
- Hóspede mesclado depois de enviado (o `reservations` reaponta para o sobrevivente): nova tentativa do `CHECK_IN` não faz segundo `POST /reservas/{id}/hospedes` para a mesma pessoa.
- Hóspede com descrição de gênero e todos os campos preenchidos: nada dele no log capturado (nota da E15).
- Com o WireMock devolvendo `503` em tudo, `POST /api/v1/reservations/{id}/check-in` responde `200` e a submissão fica `PENDING`.
- No log capturado: nenhum nome, número de documento, raça, deficiência nem CPF.

- [ ] **Step 2: Rode e veja falhar:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhCheckInSendTest'`.

- [ ] **Step 3: Implemente.**

- [ ] **Step 4: Rode** o Step 2 e `--tests 'com.zeny.hotel.fnrh.*'`. Expected: PASS.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 8: Envio do check-out e do desfazer check-in

**Estimativa:** 30 min. **Depende de:** Task 7. **Risco:** alto (ordem, registro legal, correção manual).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhCheckOutSteps.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionSender.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhCheckOutSendTest.kt`

**Interfaces:**
- Produces:
  - Passo de `CHECK_OUT`: se algum hóspede da reserva tem `checkedInAt` e não tem `guestRemoteId` no ambiente, roda antes `FnrhCheckInStep.registerGuests` (e devolve o resultado dele se não for `Sent`). Depois, para cada `FnrhRemoteGuest` com `guestRemoteId` e `checkedOut = false`: `checkOut(guestRemoteId, at = StayGuestSummary.checkedOutAt ?: submission.occurredAt)` e grava `checkedOut = true` em seguida. Nunca `Sent` sem ter enviado o check-out de cada hóspede com check-in.
  - Passo de `UNDO_CHECK_IN` (D-E08-8): se a reserva está com check-in de novo (`IN_HOUSE` ou `CHECKED_OUT`), o desfazer foi superado: `Sent` sem chamada. Senão, sem hóspede remoto, `Sent` sem chamada; com algum, `FailedLocally(NO_REMOTE_OPERATION)`.

- [ ] **Step 1: Escreva os testes que falham:**
- Check-in e check-out gravados com o `CHECK_IN` ainda `PENDING` e o Serpro respondendo `503` ao check-in: `sendDueFor` nunca chama o `PATCH …/checkout` antes de o check-in sair; depois de sair, o check-out sai com o instante do check-out; falha no 2º hóspede e nova tentativa não repete o 1º.
- Fora de ordem: só o `CHECK_OUT` gravado (o `CHECK_IN` apagado no teste, como um listener que ainda não rodou): o passo registra a reserva e os hóspedes, depois faz o check-out; o `CHECK_IN` que chega depois fica `SENT` sem nova chamada de hóspede.
- Desfazer check-in antes do envio: `CHECK_IN` `CANCELLED`, `UNDO_CHECK_IN` `SENT`, nenhuma chamada de hóspede; depois do envio: `UNDO_CHECK_IN` `FAILED` com `NO_REMOTE_OPERATION`.
- `CHECK_IN` com `inFlight` (timeout no `POST` do 1º hóspede) e o check-in desfeito: o `CHECK_IN` não é cancelado; no envio, concilia por `GET /reservas/{id}/hospedes`, não faz nenhum `POST /pessoas` nem `POST …/hospedes` novo e fica `SENT`; o `UNDO_CHECK_IN` fica `FAILED` com `NO_REMOTE_OPERATION` se o Serpro tinha o hóspede, ou `SENT` se não tinha.
- Check-in, desfazer e novo check-in antes do envio: o `UNDO_CHECK_IN` fica `SENT` sem chamada e sem alerta (desfazer superado).

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhCheckOutSendTest'` falha, implemente, passa.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 9: Alerta de falha e permissão de gestão

**Estimativa:** 20 min. **Depende de:** Task 8. **Risco:** alto (privacidade na notificação).

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/FnrhPermissions.kt` (`SUBMISSIONS_MANAGE = PermissionCode("fnrh.submissions.manage")`, só `RoleTemplate.MANAGER`, não sensível)
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionRecorder.kt`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhFailureAlertTest.kt`

**Interfaces:**
- Produces: em `failed`, `failedLocally` e `invalid`, quando a submissão passa a `FAILED` ou `INVALID`, a chamada de D-E08-11 na mesma transação. Rótulos fixos em pt-BR por operação ("registro da reserva", "atualização da reserva", "cancelamento da reserva", "check-in", "desfazer check-in", "check-out") e por situação ("falhou", "dados inválidos").

- [ ] **Step 1: Escreva os testes que falham:** submissão que vira `FAILED` gera uma notificação `notifications.integration-failure` para um usuário com `fnrh.submissions.manage` e nenhuma para um Recepcionista; o título e o texto trazem o código da reserva e **não** trazem o nome, o nome social, o documento, o e-mail, o telefone nem a descrição de gênero de um hóspede que tem todos preenchidos; `INVALID` também notifica; falha temporária que reagenda não notifica.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhFailureAlertTest'` falha, implemente, passa; rode também `--tests '*Permission*' --tests '*RoleTemplate*'`.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 10: Job e métrica de pendentes

**Estimativa:** 25 min. **Depende de:** Task 9. **Risco:** alto (concorrência entre instâncias).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhSubmissionJob.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/fnrh/infrastructure/FnrhMetrics.kt`
- Modify (test): `src/test/kotlin/com/zeny/hotel/testsupport/IntegrationTest.kt` (`FNRH_SEND_DEFERRED = "zeny.fnrh.submissions.initial-delay=PT24H"`, na lista)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionJobTest.kt`, `FnrhMetricsTest.kt` (estender)

**Interfaces:**
- Produces: `FnrhSubmissionJob.send()` com `@Scheduled(fixedDelayString = "\${zeny.fnrh.submissions.interval}", initialDelayString = "\${zeny.fnrh.submissions.initial-delay}")` e `@SchedulerLock(name = "fnrh-submissions-send", lockAtMostFor = "PT10M")`, chamando `sendDue()` e logando as contagens só quando > 0; `Unit` (o ShedLock recusa primitivo). Gauge `zeny_fnrh_submissions` com a tag `status` (`pending`, `failed`, `invalid`), lido de `countByStatus()`.

- [ ] **Step 1: Escreva os testes que falham:** o job envia uma submissão da reserva do teste (as demais do banco compartilhado podem sair também: o teste só confere a sua); a métrica aparece em `/actuator/prometheus` com as três tags e conta uma `FAILED` criada no teste; `ActuatorExposureTest` intacto.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionJobTest' --tests 'com.zeny.hotel.fnrh.FnrhMetricsTest' --tests 'com.zeny.hotel.shared.web.ActuatorExposureTest'` falha, implemente, passa.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 11: Endpoints de pendências, reenvio e encerramento

**Estimativa:** 40 min. **Depende de:** Task 10. **Risco:** alto (contrato público).

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/application/FnrhSubmissionsService.kt`
- Create: `src/main/kotlin/com/zeny/hotel/fnrh/web/FnrhSubmissionsController.kt`
- Modify: `api/openapi.yaml` (por `./gradlew updateOpenApi`)
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhSubmissionsApiTest.kt`

**Interfaces:**
- Produces:
  - `GET /api/v1/fnrh/submissions?status=…` (`operationId` `listFnrhSubmissions`, `fnrh.submissions.read`), paginação por cursor do kernel (`PageParams`/`CursorPage`; o cursor é a `sequence`). Item: `id`, `reservationId`, `reservationCode`, `operation`, `status`, `attempts`, `nextAttemptAt`, `lastFailure` (nulo), `errors[]` (`guestId`, `field`, `code`), `createdAt`, `sentAt`. `operation`, `status` e `lastFailure` com `@ExtensibleEnum`. Sem o link de pré-check-in.
  - `POST /api/v1/fnrh/submissions/{id}/retry` (`retryFnrhSubmission`, `fnrh.submissions.manage`, `@Audited(FnrhAudit.RETRY_SUBMISSION)`): `200` com o item; inexistente `404`; outro estado `409 FNRH_SUBMISSION_NOT_RETRYABLE`.
  - `POST /api/v1/fnrh/submissions/{id}/dismiss` (`dismissFnrhSubmission`, `fnrh.submissions.manage`, `@Audited(FnrhAudit.DISMISS_SUBMISSION)`): `200` com o item; inexistente `404`; outro estado `409 FNRH_SUBMISSION_NOT_DISMISSABLE`.

- [ ] **Step 1: Escreva os testes que falham:** sem permissão `403` nos três; `GET ?status=FAILED` traz a submissão `FAILED` do teste e não a `SENT`; o corpo não traz nome, documento, CPF de hóspede nem o link; `retry` de `FAILED` → `PENDING`, `attempts = 0` e a submissão sai no `sendDueFor` seguinte; `retry` de `INVALID` limpa os erros; `retry` de `PENDING` → `409` com o código; `dismiss` de `FAILED` → `DISMISSED`, e a submissão seguinte da reserva sai no `sendDueFor`; `dismiss` de `SENT` → `409`; trilha do `retry` e do `dismiss` com o usuário e sem dado de hóspede; `ApiSecurityTest` cobre os caminhos novos.

- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhSubmissionsApiTest' --tests 'com.zeny.hotel.shared.web.ApiSecurityTest'` falha, implemente, `./gradlew updateOpenApi`, passa; `./gradlew checkOpenApi` verde.

- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 12: View `fnrh.report_submissions`

**Estimativa:** 15 min. **Depende de:** Task 2. **Risco:** alto (persistência, privilégios, contrato de privacidade da view).

**Files:**
- Create: `src/main/resources/db/migration/fnrh/V2026_11_10_1100__create_fnrh_report_views.sql`
- Test: `src/test/kotlin/com/zeny/hotel/fnrh/FnrhReportViewsTest.kt`

**Interfaces:**
- Produces: `fnrh.report_submissions` com `submission_id`, `reservation_id`, `operation`, `status`, `attempts`, `last_failure`, `created_at`, `sent_at`, `next_attempt_at`; contrato no comentário da migração; `GRANT SELECT` e `REVOKE` no modelo de `reservations/V2026_10_22_0351__create_reservation_report_views.sql`. Sem `reservation_code` (a convenção pede ids opacos; quem precisa do código junta com `reservations.report_reservations` pelo `reservation_id`), sem erros por campo, sem link e sem texto.

- [ ] **Step 1: Escreva o teste que falha:** com o papel que só lê views (como no teste das views do `reservations`), a view devolve a submissão do teste; o papel não lê `fnrh.submission` nem `fnrh.remote_reservation`; as colunas são exatamente as listadas.
- [ ] **Step 2–4:** `./gradlew test --tests 'com.zeny.hotel.fnrh.FnrhReportViewsTest' --tests 'com.zeny.hotel.persistence.MigrationConventionTest'` falha, implemente, passa.
- [ ] **Step 5: Commit** com `Soul: desenvolvedor`.

---

### Task 13: Documentos de referência (orquestrador)

**Estimativa:** 20 min. **Depende de:** Tasks 1 a 12. **Risco:** normal.

**Files:** só `.md`, pelo orquestrador:
- `docs/SPEC.md` §8.2.17: a porta `FnrhGateway` real (as operações da E07 mais `listReservationGuests` e `findPersonByDocument`) e a `FnrhSubmission` com as operações, a ordem causal e as situações de D-E08-1, 3 e 16; ids remotos e link em `remote_reservation`/`remote_guest` (D-E08-2).
- `docs/ARCHITECTURE.md` §13: o job `fnrh-submissions-send`; §12: a escrita em curso (D-E08-15) e o encerramento manual (D-E08-16), se o texto não os cobrir.
- `README.md`, seção "API": `GET /api/v1/fnrh/submissions`, `POST …/{id}/retry` e `POST …/{id}/dismiss`.
- `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`: "Decisões do lote E08" (com as alternativas descartadas), handoff (a escolha do `registerPerson`, D-E08-6; a divergência da SPEC §8.2.17, D-E08-2; o `RoomMoved` sem envio; o acumulado ao ligar, D-E08-5, para a E09 e a M8 E13; o que a etapa do no-show do M6 acrescenta: operação na porta, `markNoShow` publicando o evento e o listener gravando `NO_SHOW` com posto próprio na ordem causal; e as "Notas para a E09": `dados.id` da consulta por documento, `PUT` numa reserva `EM_ANDAMENTO`, documento repetido no `POST /pessoas`, e se o `GET /reservas` devolve o link de pré-check-in, que fica nulo numa reserva conciliada).

- [ ] **Step 1:** edite. **Step 2:** `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py docs/roadmaps/roadmap-m5-recepcao-fnrh.md`. **Step 3:** commit com `Soul: orquestrador`.

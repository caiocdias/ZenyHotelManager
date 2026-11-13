# M6 E11 — Etapas financeiras e operacionais do fechamento: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** registrar no motor da E10, na ordem da RF-FEC-02, as etapas de negócio que não dependem de `reporting` — no-show com a multa da política copiada, alerta de saídas pendentes, diárias/pacotes/taxa de turismo, liberação de bloqueios vencidos, expiração de pré-reservas, pré-autorizações "não aplicável", UHs ocupadas → Sujo e avanço da data —, provar a RF-FEC-05 (inclusive com lançamento concorrente ao avanço) e fechar as notas da E10 endereçadas a esta etapa.

**Architecture:** o `endofday` passa a depender também de `reservations`, `billing` e `rooms` (subconjunto da linha da ARCHITECTURE §3.1, sem aresta nova na tabela). Cada etapa é um bean `EndOfDayStepHandler` em `endofday/application/steps/`, nas posições 100…700 e 1000 (800 e 900 ficam para a E13), e age só pelas APIs públicas dos donos do dado. O que falta nessas APIs entra nelas: em `reservations`, as consultas por data, a transição `NO_SHOW` com evento e as cobranças de uma noite (a regra do check-out da M5 E04 sai do `frontdesk` e passa a ser do dono da reserva, usada pelos dois caminhos); em `hotel`, a leitura da data operacional travada contra o avanço, que o `billing` usa ao carimbar todo lançamento (RF-FEC-05 por construção). O trabalho por item roda numa transação própria (`REQUIRES_NEW`) por um apoio comum (`EndOfDayItems`); a recusa de negócio de um item vira pendência, qualquer outra falha falha a etapa e a deixa retomável sem duplicar (chaves `sourceReference`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, ShedLock 7, PostgreSQL 18 (Testcontainers 2), JUnit 5 + Kotest assertions.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E11 — Etapas financeiras e operacionais do fechamento" (objetivo, escopo, critérios, notas para o plano, riscos), o handoff da E10 ("Notas para a próxima etapa" e "Decisões do usuário", H12), H4, H5 e H10; `docs/SPEC.md` §5.13 (RF-FEC-02, RF-FEC-03, RF-FEC-05), RF-RES-07, RF-CFG-03, RN-03, RN-09, RN-11, RN-15, RN-16, RN-21, RN-23, §8.4; `docs/ARCHITECTURE.md` §3.1, §3.2, §5, §11 e §13.

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1/§3.2): `endofday` declara `allowedDependencies = ["shared", "hotel", "notifications", "reservations", "billing", "rooms"]` (subconjunto da linha da §3.1; a tabela não muda) e usa só a API pública da raiz de cada um. O `endofday` **não** usa tipos de `rates` nem de `frontdesk`: o que precisa da reserva vem pronto da `ReservationsApi`. `notifications` nunca escuta evento de negócio. `housekeeping` (E02) escuta `BusinessDateAdvanced`; o `endofday` não chama governança.
- Contrato de etapa: o KDoc de `EndOfDayStepHandler` (E10) vale para toda etapa — idempotente pela **data**, nunca pelo `runId`; item que tolera falha em `REQUIRES_NEW` (aqui pelo `EndOfDayItems`, Task 6), sem tocar na linha `endofday.run`; pendência da falha de item levantada na transação da etapa; nunca capturar exceção de bean `@Transactional` que participa da transação da etapa; nome estável `[a-z][a-z0-9-]{0,59}`; posições de 100 em 100; nada de dado pessoal no resultado, `kind`, referência ou mensagem de pendência (RN-13; a referência é o id).
- Etapas e posições (nomes estáveis, publicados): `no-show` 100, `pending-departures` 200, `room-charges` 300, `block-release` 400, `provisional-expiry` 500, `card-pre-authorizations` 600, `occupied-rooms-dirty` 700, `advance-business-date` 1000. Renomear ou remover `advance-business-date` é proibido.
- RN-21 e RF-FEC-03: cada etapa repetida para a mesma data não duplica efeito; lançamento pela chave `NewPosting.sourceReference` (H5): `reservations.reservation:<id>:<data>:room`, `…:<data>:package:<packageId>`, `…:<data>:tourism-fee` e `…:<data>:no-show`.
- RN-03: lançamento nunca muda nem some; correção por estorno na data corrente com referência ao original.
- RF-FEC-05: todo lançamento do `billing` carimba a data operacional com a linha de `hotel.business_day` travada em `FOR SHARE` até o commit (Task 5); o avanço (`FOR UPDATE`) espera os lançamentos em curso.
- Lançamentos do fechamento com origem `PostingSource.END_OF_DAY`; pacotes inclusos sem taxa de serviço (`serviceChargeRate = Percent.ZERO`), como no check-out (M5 E04 a).
- Migração nova em `src/main/resources/db/migration/reservations/` com versão posterior a `V2026_11_12_1001`: `V2026_11_13_1000__add_reservation_no_show.sql` (`outOfOrder` desligado).
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca à mão); enum de resposta que cresce sai como `x-extensible-enum`; `.github/scripts/check-api-breaking.sh` sem `ERR` em `/api/v1`.
- Auditoria (regra ArchUnit `writeUseCasesAreAudited`): todo método `@Transactional` de escrita em `application` é `@Audited`; chamadas que precisam do proxy atravessam beans, nunca `this.`. O `EndOfDayItems` abre a transação do item por `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`), sem `@Transactional`, porque a escrita do item já é auditada pela API do dono (evita uma entrada vazia por item).
- Erros de domínio: `DomainException` com `ErrorCode` estável na raiz do módulo dono; mensagens em pt-BR sem dado pessoal. Código, API e banco em inglês; KDoc, mensagens, documentação e commits em pt-BR.
- Testes (H10): **nenhum teste desta etapa roda o fechamento no banco compartilhado.** Três contextos no banco isolado `zeny_endofday`: (1) `@EndOfDayIntegrationTest` + `@Import(EndOfDayTestConfiguration::class)` — o do motor, só com as etapas falsas (as reais excluídas, Task 6); (2) `@EndOfDayIntegrationTest` + `@Import(EndOfDayRealStepsTestConfiguration::class)` — o das etapas reais (Task 6); (3) `EndOfDayNoStepsProductionTest`, sem etapas (as reais excluídas). **Regra do banco isolado:** todo teste termina com a data corrente sem execução ou `COMPLETED` (`EndOfDayTestRuns.settle(context)` no `finally`), devolve o `MutableClock` e desfaz a configuração do hotel que mudou (taxa de turismo, fuso, horário); asserções só sobre os próprios dados, porque o fechamento real age sobre tudo o que os testes anteriores deixaram. As datas partem da `HotelApi.currentBusinessDate()`.
- Testes do `frontdesk`, `reservations`, `billing` e `hotel` no banco compartilhado não avançam a data (o teste de concorrência da Task 5 roda no banco isolado).
- Máquina de 14 GB: um Gradle ou `docker build` por vez; nunca `./gradlew --stop`. As tarefas rodam em série (sem ondas paralelas).
- Contagem de "arquivos de produção" por tarefa: arquivos Kotlin de `src/main`; migração, YAML, `api/openapi.yaml` e documentação não contam.
- Commits de cada soul com o trailer `Soul: <soul>`.

## Review Focus

1. **Lançamento manual da recepção enquanto o fechamento avança a data:** cai inteiro na data que fecha (antes do avanço) ou inteiro na nova, nunca numa data já fechada — Tasks 5 e 16.
2. **Retomada depois de uma queda no meio das diárias:** as reservas já lançadas não ganham segundo lançamento; as que faltavam são lançadas uma vez — Tasks 9 e 16.
3. **Recepção faz o check-in de uma chegada enquanto o no-show roda:** a reserva fica hospedada ou no-show, nunca as duas coisas, sem multa indevida e sem 500 — Task 3 (a trava e a reconferência sob ela) e Task 7.
4. **Reserva reativada depois de um no-show e no-show de novo:** a multa não é cobrada duas vezes (`penaltiesCharged`) — Task 3.
5. **Hóspede que ficou além da saída (saída passada, ainda hospedado):** nenhuma diária automática, nenhum check-out, uma pendência por fechamento — Tasks 8 e 9.

---

## Mapa de arquivos

Produção:

| Arquivo | Responsabilidade | Task |
|---|---|---|
| `reservations/ReservationsApi.kt` | consultas por data, `markNoShow`, `stayNight`, `dueProvisionals`, `expireProvisional` | 1, 3, 4, 11 |
| `reservations/application/InventoryService.kt` | implementação da `ReservationsApi` (delegação) | 1, 3, 4, 11 |
| `reservations/infrastructure/JdbcReservationQueries.kt` (+ porta em `application/ReservationQueries.kt`) | SQL das consultas por data | 1 |
| `reservations/domain/ReservationLifecycle.kt` | ação `NO_SHOW` nas transições | 2 |
| `reservations/ReservationsEvents.kt`, `ReservationChanges.kt`, `RoomAssignments.kt` | evento `ReservationMarkedNoShow`, `ReservationHistoryKind.NO_SHOW`, `RoomAssignmentEndReason.RESERVATION_NO_SHOW` | 2 |
| `reservations/application/ReservationLifecycle.kt` (+ `application/RoomAssignments.kt`) | caso de uso do no-show | 3 |
| `reservations/NightCharges.kt` (novo) | cobranças de uma noite da reserva (regra única do check-out e do fechamento) | 4 |
| `frontdesk/domain/CheckOutRules.kt`, `frontdesk/application/CheckOutService.kt` | o check-out passa a usar `ReservationNightCharges` | 4 |
| `hotel/HotelApi.kt`, `hotel/application/HotelApiService.kt`, `hotel/infrastructure/HotelPersistence.kt` (+ porta) | `lockBusinessDate()` | 5 |
| `billing/application/BillingOperations.kt` | `stamp()` com a data travada | 5 |
| `endofday/EndOfDayModule.kt` | dependências novas | 6 |
| `endofday/application/EndOfDayItems.kt` (novo) | item em transação própria, recusa de negócio → pendência | 6 |
| `endofday/application/steps/*.kt` (novos) | uma etapa por arquivo | 6–12 |
| `endofday/application/EndOfDayTrigger.kt`, `EndOfDayTriggerProperties.kt` | gatilho só dispara e volta (H12) | 13 |
| `endofday/application/EndOfDayEngine.kt`, `EndOfDayRunTransitions.kt`, `EndOfDayRunCommands.kt` | tipo próprio da corrida no `fail`; retomada com `lock_timeout` | 14 |
| `endofday/application/EndOfDayIssues.kt`, `EndOfDayRunTransitions.kt` | avisos agrupados por etapa e tipo | 15 |

Teste (apoio): `endofday/EndOfDayTestConfiguration.kt`, `EndOfDayTestRuns.kt`, `EndOfDayNoStepsProductionTest.kt` (ajuste), `EndOfDayProductionStepsExcluded.kt`, `EndOfDayRealStepsTestConfiguration.kt`, `BillingPostFailureProbe.kt` (novos), `ModularityTest.kt`.

---

### Task 1: Consultas por data na `ReservationsApi` (H4)

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** normal

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/reservations/ReservationsApi.kt`, `reservations/application/InventoryService.kt`, `reservations/infrastructure/JdbcReservationQueries.kt` (e a porta `ReservationQueryStore` em `application/ReservationQueries.kt`)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationDateQueriesTest.kt`

**Interfaces:**
- Produces (na `ReservationsApi`, sem conferir permissão, leitura sem trava, por id ascendente, sem limite — hotel de até 30 UHs; KDoc diz que o fechamento chama com a data que fecha):
  - `fun pendingArrivals(date: LocalDate): List<ReservationId>` — `CONFIRMED` com `arrival <= date` (as chegadas pendentes; pré-reserva nunca vira no-show).
  - `fun pendingDepartures(date: LocalDate): List<ReservationId>` — `IN_HOUSE` com `departure <= date`.
  - `fun inHouseOn(date: LocalDate): List<ReservationId>` — `IN_HOUSE` com uma noite em `date`: `arrival <= date < departure`, ou day use `arrival == departure == date`.
- O SQL reaproveita os índices parciais por situação (literais de `statusList`), como as listas operacionais.

- [ ] **Step 1: Testes que falham** (`@IntegrationTest`, banco compartilhado, sem avançar a data; cada teste cria as próprias reservas num tipo de UH novo com `ReservationsTestSupport` e força a situação com `forceInHouse`/`forceCheckedOut`; `D = hotelApi.currentBusinessDate()`; asserções só sobre os ids do teste):
  - `pending arrivals are confirmed reservations arriving on or before the date`: confirmada com chegada `D` → contém; pré-reserva com chegada `D`, cancelada, hospedada e confirmada com chegada `D+1` → não contém.
  - `pending departures are in-house stays leaving on or before the date`: hospedada com saída `D` → contém; hospedada com saída `D+1`, encerrada com saída `D` → não contém.
  - `in house on the date has a night on the date`: hospedada `D..D+2` e day use hospedada `D..D` → contém; hospedada com saída `D` (sem noite em `D`) → não contém.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationDateQueriesTest'`.
- [ ] **Step 3: Implementar** as três consultas.
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(reservations): consultas por data para o fechamento do dia`.

---

### Task 2: No-show no domínio, no banco e no evento

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (persistência e contrato público)

**Files:**
- Modify: `reservations/domain/ReservationLifecycle.kt` (`ReservationAction.NO_SHOW`, transição só de `CONFIRMED`, rótulo `"marcada como no-show"`), `reservations/ReservationChanges.kt` (`ReservationHistoryKind.NO_SHOW`), `reservations/RoomAssignments.kt` (`RoomAssignmentEndReason.RESERVATION_NO_SHOW`), `reservations/ReservationsEvents.kt`
- Create: `src/main/resources/db/migration/reservations/V2026_11_13_1000__add_reservation_no_show.sql`
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationNoShowDomainTest.kt`, `ReservationNoShowPersistenceTest.kt`

**Interfaces:**
- Produces:
  - `data class ReservationMarkedNoShow(reservationId: UUID, bookingId: UUID, roomTypeId: UUID, roomId: UUID?, arrival: LocalDate, departure: LocalDate, dayUse: Boolean, businessDate: LocalDate, penaltyCharged: Boolean, performedBy: UUID?, version: Long, markedAt: Instant)`, KDoc: SPEC §8.4 `Confirmed → NoShow` no fechamento; `roomId` é a UH que estava alocada (solta no mesmo commit); publicado pelo outbox; a FNRH o escuta na E14.
  - A migração troca as `CHECK` de `reservation_history.kind` e de `room_assignment` (motivo do fim) para aceitar `NO_SHOW` e `RESERVATION_NO_SHOW`, mantendo os valores atuais (veja `V2026_10_22_1546__add_stay_transitions.sql`, linhas 77 e 85). Nenhuma coluna nova: a multa fica no lançamento, em `penalties_charged` e no histórico.
- Contrato: `ReservationHistoryKind` e `RoomAssignmentEndReason` aparecem em respostas; conferir que já saem como `x-extensible-enum` (senão, `@field:ExtensibleEnum` nos DTOs) antes de regenerar.

- [ ] **Step 1: Testes que falham:**
  - `ReservationNoShowDomainTest` (unidade): `NO_SHOW` só a partir de `CONFIRMED`; de `PROVISIONAL`, `IN_HOUSE`, `CHECKED_OUT`, `CANCELLED` e `NO_SHOW` → `ReservationTransitionNotAllowedException` com `action = NO_SHOW`; `REACTIVATE` continua aceitando `NO_SHOW`.
  - `ReservationNoShowPersistenceTest` (`@IntegrationTest`): gravar histórico `NO_SHOW` e alocação encerrada com `RESERVATION_NO_SHOW` passa pelas `CHECK`; um valor desconhecido continua recusado.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationNoShow*'`.
- [ ] **Step 3: Implementar** enums, transição, evento e migração; `./gradlew updateOpenApi`.
- [ ] **Step 4: Ver passar** o mesmo comando, `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` (sem `ERR`).
- [ ] **Step 5: Commit** `feat(reservations): situação no-show no domínio, no banco e no evento`.

---

### Task 3: Caso de uso `markNoShow` (RF-RES-07, RN-15)

**Estimativa:** 45 min · **Depende de:** Task 2 · **Risco:** alto (dinheiro imutável, concorrência com o check-in)

**Files:**
- Modify: `reservations/ReservationsApi.kt`, `reservations/application/InventoryService.kt`, `reservations/application/ReservationLifecycle.kt`, `reservations/application/RoomAssignments.kt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationNoShowTest.kt`

**Interfaces:**
- Consumes: `ReservationAction.NO_SHOW`, `ReservationMarkedNoShow`, `RoomAssignmentEndReason.RESERVATION_NO_SHOW` (Task 2).
- Produces: `ReservationsApi.markNoShow(id: ReservationId): ReservationSummary?` — KDoc: **só o fechamento chama**; na transação de quem chama (ou numa própria), auditada `reservations.reservation.no-show` em nome do ator corrente ou do sistema, sem permissão. Algoritmo (espelha `cancel`/`applyCancellation`):
  1. `requireWriteTransaction()`; trava a reserva. Se não é `CONFIRMED` ou `arrival` é depois da data operacional corrente → `audit.noChanges()` e devolve `null` (um check-in ou cancelamento confirmado antes da trava a tira daqui; nunca lança `409` por isso).
  2. Unidades das noites a partir de `max(arrival, data operacional)` devolvidas ao inventário ou ao bloqueio, pelo mesmo caminho do cancelamento (`blockUnits.onCancellation`).
  3. Multa: `CancellationPenalties.due(CancellationPenalties.amount(cancellationTerms, nightlyRates, roomTotal), penaltiesCharged)` — a política **copiada na reserva**, sem prazo (o no-show é sempre depois dele); positiva → `BillingApi.post(folioId, NewPosting(transactionCode(NO_SHOW_FEE).id, due, 1, "Multa de no-show da reserva <code>", PostingSource.END_OF_DAY, sourceReference = "reservations.reservation:<id>:<data operacional>:no-show"))`.
  4. Alocação ativa encerrada com `RESERVATION_NO_SHOW` (nova função ao lado de `releaseOnCancellation`), no mesmo commit (o gatilho adiado `reservation_assignment_released` confere).
  5. Grava `status = NO_SHOW`, `roomAssignment = null`, `provisionalUntil = null`, `penaltiesCharged += lançado`, `version + 1`; histórico `NO_SHOW`; publica `ReservationMarkedNoShow`.

- [ ] **Step 1: Testes que falham** (`@IntegrationTest`, banco compartilhado, sem avançar a data; reservas com chegada `D = currentBusinessDate()`):
  - `a confirmed reservation arriving today becomes no-show with the copied penalty and frees the room`: política `FIXED_AMOUNT` 150,00 copiada; depois de criar a reserva, a política do plano muda para 300,00 → `NO_SHOW`; um lançamento `MULTA-NOSHOW` de 150,00, origem `END_OF_DAY`, `sourceReference` `…:<D>:no-show`; alocação encerrada com `RESERVATION_NO_SHOW`; inventário de `D` devolvido; histórico `NO_SHOW`; um `ReservationMarkedNoShow` com `roomId` e `penaltyCharged = true`.
  - `percent and nights penalties use the frozen nightly rates`: `PERCENT_OF_STAY` 50% e `NIGHTS` 1 sobre as diárias congeladas.
  - `calling twice changes nothing the second time`: segunda chamada → `null`, um lançamento só, versão inalterada.
  - `a reservation that is no longer confirmed is left alone`: hospedada, pré-reserva e chegada `D+1` → `null`, nada lançado.
  - `a reactivated no-show marked again is not charged twice`: no-show (150,00) → reativar → no-show de novo → nenhum lançamento novo, `penaltyCharged = false`.
  - `check-in and no-show racing on the same reservation end in exactly one of them`: duas threads (check-in pelo `frontdesk` e `markNoShow`) com `CountDownLatch`; resultado `IN_HOUSE` sem multa ou `NO_SHOW` com check-in recusado, nunca 500 (`@Timeout(30)`).
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationNoShowTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando e `./gradlew test --tests 'com.zeny.hotel.reservations.*'`.
- [ ] **Step 5: Commit** `feat(reservations): no-show com a multa da política copiada (RN-15)`.

---

### Task 4: Cobranças de uma noite no `reservations` (check-out e fechamento)

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (dinheiro imutável; muda o check-out)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/reservations/NightCharges.kt`
- Modify: `reservations/ReservationsApi.kt`, `reservations/application/InventoryService.kt`, `frontdesk/domain/CheckOutRules.kt`, `frontdesk/application/CheckOutService.kt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/NightChargesTest.kt`, `ReservationStayNightTest.kt`

**Interfaces:**
- Produces (raiz de `reservations`, pública):
  - `enum class NightChargeKind { ROOM, PACKAGE }`.
  - `data class NightCharge(kind: NightChargeKind, date: LocalDate, transactionCodeId: TransactionCodeId?, packageId: UUID?, quantity: BigDecimal, unitPrice: Money, description: String?, sourceReference: String, includedInRate: Boolean)` com `val amount: Money` (= `unitPrice * quantity`) e `fun toNewPosting(roomCharge: TransactionCodeId): NewPosting` (código `transactionCodeId ?: roomCharge`, `PostingSource.END_OF_DAY`, `serviceChargeRate = Percent.ZERO` se `includedInRate`, senão nulo, a `sourceReference`).
  - `object ReservationNightCharges { fun of(reservation: ReservationSummary, night: LocalDate): List<NightCharge>; fun reference(id: ReservationId, night: LocalDate): String }` — a regra que hoje está em `StayCharges.pending` (frontdesk), para **qualquer** noite congelada: hospedagem = `roomAmount - includedPackagesAmount` (chave `<reference>:room`), cada pacote da noite no código dele (`<reference>:package:<packageId>`), valores zero fora; `reference = "reservations.reservation:<id>:<night>"`. Sem noite congelada em `night` → lista vazia.
  - `data class StayNight(reservationId: ReservationId, folioId: FolioId, date: LocalDate, persons: Int, charges: List<NightCharge>)` e `ReservationsApi.stayNight(id: ReservationId, night: LocalDate): StayNight?` — nulo se a reserva não existe ou não tem noite congelada em `night`; `persons = adults + childAges.size`.
- O check-out (`StayCharges.pending`) passa a ser `if (businessDate != reservation.arrival) emptyList() else ReservationNightCharges.of(reservation, reservation.arrival)` e o `CheckOutService.post` usa `NightCharge.toNewPosting`; `StayCharge`/`StayChargeKind` saem se ficarem sem uso (a resposta do check-out não muda). As chaves continuam idênticas às da M5 E04.
- Grupo (nota M4 E11): o lançamento segue só as noites congeladas; a tarifa de grupo sem pacotes do plano não ganha pacote no fechamento (preço congelado, RN-14).

- [ ] **Step 1: Testes que falham:**
  - `NightChargesTest` (unidade): noite do meio de uma estadia de 3 noites → hospedagem `roomAmount - inclusos` + um pacote incluso (`includedInRate = true`) + um adicional; chaves exatas; noite fora da estadia → vazia; pacote de valor zero fora; `toNewPosting` do incluso tem `serviceChargeRate = 0`, do adicional nulo, origem `END_OF_DAY`.
  - `ReservationStayNightTest` (`@IntegrationTest`): `stayNight` de uma reserva com 2 adultos e 1 criança → `persons = 3`, `folioId` da reserva; noite sem diária → `null`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.NightChargesTest' --tests 'com.zeny.hotel.reservations.ReservationStayNightTest'`.
- [ ] **Step 3: Implementar** e trocar o check-out para a regra nova.
- [ ] **Step 4: Ver passar** os mesmos e `./gradlew test --tests 'com.zeny.hotel.frontdesk.*'` (o check-out da M5 E04 continua igual: `FrontdeskCheckOutApiTest`, `CheckOutDomainTest`).
- [ ] **Step 5: Commit** `refactor(reservations): cobranças da noite no dono da reserva, usadas pelo check-out e pelo fechamento`.

---

### Task 5: Data operacional travada no lançamento (RF-FEC-05)

**Estimativa:** 40 min · **Depende de:** nenhuma · **Risco:** alto (concorrência; RF-FEC-05)

**Files:**
- Modify: `hotel/HotelApi.kt`, `hotel/application/HotelApiService.kt`, `hotel/infrastructure/HotelPersistence.kt` (e a porta `HotelStore`), `billing/application/BillingOperations.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/BusinessDateLockTest.kt` (banco isolado, contexto `@EndOfDayIntegrationTest` + `@Import(EndOfDayTestConfiguration::class)`), `src/test/kotlin/com/zeny/hotel/hotel/BusinessDateLockContractTest.kt` (`@IntegrationTest`)

**Interfaces:**
- Produces: `HotelApi.lockBusinessDate(): LocalDate` — KDoc: a data operacional corrente, com a linha de `hotel.business_day` travada em `FOR SHARE` até o fim da transação de quem chama: o avanço (`advanceBusinessDate`, `FOR UPDATE`) espera os lançamentos em curso, e um lançamento que espera o avanço lê a data nova (RF-FEC-05). **Sem `@Transactional`**: exige a transação de escrita de quem chama (sem ela, ou só leitura, `IllegalStateException`, como o `requireWriteTransaction` do `reservations`). Store: `businessDayForShare()` (`LockModeType.PESSIMISTIC_READ` ou `SELECT … FOR SHARE`).
- `BillingOperations.stamp()` passa a usar `hotel.lockBusinessDate()`: vale para lançamento, pagamento, devolução, transferência, caução e reabertura (todos carimbam por ele). `openFolio` e o caixa continuam com `currentBusinessDate()`. A ordem das travas fica: conta (ou turno) antes da data; o avanço só trava a data, então não há ciclo.

- [ ] **Step 1: Testes que falham:**
  - `BusinessDateLockContractTest`: fora de transação e em transação só de leitura → `IllegalStateException`; numa de escrita devolve a data corrente.
  - `BusinessDateLockTest` (cada teste termina com `settle`, que não acha execução, e não cria execução de fechamento):
    - `a posting in flight holds the advance until it commits and stays on the old date`: thread A abre transação, `billingApi.post` (lança na data `D`), espera um `CountDownLatch`; thread B chama `hotelApi.advanceBusinessDate(D)` → ainda não terminou depois de 500 ms; solta A → A confirma com `businessDate = D`; B termina; data corrente `D+1`; o instante do commit de A é anterior ao da trilha `hotel.business-date.advance` (`@Timeout(30)`).
    - `a posting that waits for the advance lands on the new date`: B trava a data (transação com `advanceBusinessDate` ainda aberta, segura por latch); A tenta lançar → espera; B confirma → o lançamento de A sai com `D+1`.
    - `a correction of a posting from a closed date goes to the current date with the original reference`: lançamento em `D`, avanço, correção pelo endpoint de correção do `billing` → `businessDate = D+1`, `correctsId` = original.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.BusinessDateLockTest' --tests 'com.zeny.hotel.hotel.BusinessDateLockContractTest'` (o primeiro cai: B avança sem esperar).
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** os mesmos e `./gradlew test --tests 'com.zeny.hotel.billing.*' --tests 'com.zeny.hotel.hotel.*'`.
- [ ] **Step 5: Commit** `feat(hotel,billing): lançamento trava a data operacional contra o avanço (RF-FEC-05)`.

---

### Task 6: Etapas reais — dependências, apoio por item, contextos de teste e avanço da data

**Estimativa:** 45 min · **Depende de:** Task 5 · **Risco:** alto (núcleo da etapa; contexto de testes)

**Files:**
- Modify: `endofday/EndOfDayModule.kt` (dependências e KDoc), `endofday/application/EndOfDaySteps.kt` (KDoc: o item em `REQUIRES_NEW` pelo `EndOfDayItems`)
- Create: `endofday/application/EndOfDayItems.kt`, `endofday/application/steps/AdvanceBusinessDateStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `endofday/EndOfDayProductionStepsExcluded.kt`, `endofday/EndOfDayRealStepsTestConfiguration.kt`, `endofday/EndOfDayTestConfiguration.kt` (importa a exclusão; o KDoc "Revisar na E11" sai), `endofday/EndOfDayNoStepsProductionTest.kt` (importa a exclusão), `endofday/EndOfDayTestRuns.kt`, `endofday/EndOfDayItemsTest.kt`, `endofday/EndOfDayAdvanceStepTest.kt`

**Interfaces:**
- Produces:
  - `@Component class EndOfDayItems(transactionManager: PlatformTransactionManager)`: `fun <T> inOwnTransaction(work: () -> T): T` (`TransactionTemplate` com `PROPAGATION_REQUIRES_NEW`) e `fun <I> each(context: EndOfDayStepContext, issueKind: String, items: List<I>, reference: (I) -> String, work: (I) -> Unit): Int` — roda cada item na transação dele; `DomainException` do item → depois do rollback dele, `context.issues.raise(issueKind, reference(item), "<issueKind>: <código do erro>")` na transação da etapa; qualquer outra exceção escapa e falha a etapa. Devolve quantos itens concluíram. KDoc: a recusa de negócio de um item não derruba o fechamento; erro de infraestrutura derruba e a retomada repete a etapa (idempotente pela chave de cada item).
  - `AdvanceBusinessDateStep`: `name = "advance-business-date"`, `position = 1000`, `execute` = `hotel.advanceBusinessDate(context.businessDate)` → `DONE`. KDoc: última etapa, nome proibido de mudar; o avanço trava a data (`FOR UPDATE`) e espera os lançamentos em curso (Task 5).
  - `EndOfDayModule`: `allowedDependencies = ["shared", "hotel", "notifications", "reservations", "billing", "rooms"]`.
- Apoio de teste:
  - `EndOfDayProductionStepsExcluded` (`@TestConfiguration`): um `BeanDefinitionRegistryPostProcessor` (bean `static`/`@JvmStatic`) que remove as definições cujo `beanClassName` começa com `com.zeny.hotel.endofday.application.steps.`. Importado pelo `EndOfDayTestConfiguration` e pelo `EndOfDayNoStepsProductionTest`, que continuam só com as falsas e sem etapas, respectivamente (mudança mínima nos testes do testador da E10; ruling no ledger).
  - `EndOfDayRealStepsTestConfiguration` (`@TestConfiguration`): importa `MutableClockConfiguration`, `IamTestConfiguration` e `InventoryTestConfiguration`; sem etapas falsas.
  - `EndOfDayTestRuns`: `settle(context)` passa a zerar o `FakeStepsProbe` só se o bean existir (`getBeanProvider(...).ifAvailable`); novo `fun closeCurrentDate(context: ApplicationContext): EndOfDayRunId` — cria a execução da data corrente (`EndOfDayRunCommands.createAutomatic`) e a roda pelo motor esperando até 60 s.

- [ ] **Step 1: Testes que falham:**
  - `ModularityTest`: o teste do `endofday` passa a exigir exatamente `shared, hotel, notifications, reservations, billing, rooms` (renomear o método).
  - `EndOfDayItemsTest` (contexto das falsas): três itens, o 2º lança `BusinessRuleException` → 2 concluídos, uma pendência com a referência do 2º e o código; o 1º e o 3º confirmados mesmo com a etapa depois falhando; item que lança `IllegalStateException` → a exceção escapa e o efeito dos itens anteriores fica.
  - `EndOfDayAdvanceStepTest` (contexto real): o registro termina em `advance-business-date` (1000) e não tem nenhuma etapa `fake-*`; `closeCurrentDate` → `COMPLETED`, data `D+1`, uma trilha `hotel.business-date.advance`; o `BusinessDateAdvanced` gera as rotinas de checklist de `D+1` (uma rotina criada pelo teste ganha execução em `D+1`, esperada com prazo).
  - O registro do contexto das falsas tem só `fake-one`…`fake-advance` (asserção em `EndOfDayStepRegistryTest` ou no novo `EndOfDayItemsTest`).
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.endofday.EndOfDayItemsTest' --tests 'com.zeny.hotel.endofday.EndOfDayAdvanceStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** os mesmos e `./gradlew test --tests 'com.zeny.hotel.endofday.*'` (os testes da E10 continuam verdes nos contextos com a exclusão).
- [ ] **Step 5: Commit** `feat(endofday): etapa de avanço da data e apoio das etapas reais`.

---

### Task 7: Etapa `no-show` (100)

**Estimativa:** 30 min · **Depende de:** Tasks 1, 3, 6 · **Risco:** alto (dinheiro imutável)

**Files:**
- Create: `endofday/application/steps/NoShowStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/NoShowStepTest.kt` (contexto real)

**Interfaces:**
- Consumes: `ReservationsApi.pendingArrivals`, `markNoShow` (Tasks 1, 3); `EndOfDayItems.each` (Task 6).
- Produces: `NoShowStep`: `name = "no-show"`, `position = 100`; itens = `pendingArrivals(context.businessDate)`; item = `markNoShow(id)`; pendência `NO_SHOW_FAILED` (referência = id da reserva). Resultado `NOT_APPLICABLE` sem item; senão `DONE`.

- [ ] **Step 1: Testes que falham** (`closeCurrentDate` + `settle`):
  - `only confirmed reservations with a pending arrival become no-show, with the copied penalty, and free the room in the same commit`: confirmada com chegada `D` e UH alocada → `NO_SHOW`, multa da política copiada, alocação encerrada; pré-reserva, hospedada e confirmada com chegada `D+1` intactas.
  - `a refused item becomes an issue and the run goes on`: uma reserva cuja conta foi encerrada antes (o `post` da multa recusa com `409 FOLIO_CLOSED`) → pendência `NO_SHOW_FAILED` com o id, a reserva continua `CONFIRMED`, as outras viram no-show e a execução termina `COMPLETED`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.NoShowStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): etapa de no-show`.

---

### Task 8: Etapa `pending-departures` (200)

**Estimativa:** 20 min · **Depende de:** Tasks 1, 6 · **Risco:** normal

**Files:**
- Create: `endofday/application/steps/PendingDeparturesStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/PendingDeparturesStepTest.kt` (contexto real)

**Interfaces:**
- Produces: `PendingDeparturesStep`: `name = "pending-departures"`, `position = 200`; para cada `pendingDepartures(context.businessDate)`, `context.issues.raise("PENDING_DEPARTURE", id, "Saída pendente: faça o check-out ou altere a saída; o fechamento não faz check-out.")`. Nunca chama check-out. `NOT_APPLICABLE` sem saída pendente.

- [ ] **Step 1: Teste que falha:** `a pending departure raises an issue and is never checked out automatically`: hospedada com saída `D` e outra com saída `D-1` (forçada) → uma `PENDING_DEPARTURE` para cada, com o id; as duas continuam `IN_HOUSE`, sem lançamento novo de check-out, a UH continua ocupada; aviso `end-of-day-pending` para quem tem `endofday.runs.read`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.PendingDeparturesStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): etapa de saídas pendentes`.

---

### Task 9: Etapa `room-charges` (300) — diárias, pacotes e taxa de turismo

**Estimativa:** 45 min · **Depende de:** Tasks 1, 4, 6 · **Risco:** alto (dinheiro imutável; núcleo)

**Files:**
- Create: `endofday/application/steps/RoomChargesStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/BillingPostFailureProbe.kt`, `endofday/RoomChargesStepTest.kt` (contexto real; o `EndOfDayRealStepsTestConfiguration` passa a declarar o probe)

**Interfaces:**
- Consumes: `inHouseOn`, `stayNight`, `NightCharge.toNewPosting` (Tasks 1, 4); `EndOfDayItems.each` (Task 6); `BillingApi.post`, `transactionCode(ROOM_CHARGE)`, `transactionCode(TOURISM_FEE)`; `HotelApi.taxSettings()`.
- Produces: `RoomChargesStep`: `name = "room-charges"`, `position = 300`; itens = `inHouseOn(context.businessDate)`; item = `stayNight(id, date)` (nulo → nada) → `post(folioId, charge.toNewPosting(roomChargeCode))` para cada cobrança e, com `tourismFeePerPersonNight` configurada, `post(folioId, NewPosting(TOURISM_FEE, unitPrice = taxa, quantity = persons, description = "Taxa de turismo", source = END_OF_DAY, sourceReference = "<reference>:tourism-fee"))`; pendência `ROOM_CHARGE_FAILED`. Só a noite da data que fecha (sem recuperar noites anteriores). Resultado `NOT_APPLICABLE` sem hospedada.
- `BillingPostFailureProbe`: `BeanPostProcessor` que embrulha o bean `BillingApi` num proxy; `armed: MutableSet<UUID>` de contas cujo `post` lança `IllegalStateException("Falha forçada pelo teste")` (não é `DomainException`: falha a etapa). Se algum bean injetar a classe concreta, troque por um `@Primary` que delega (mesmo comportamento).

- [ ] **Step 1: Testes que falham:**
  - `in-house stays get the night, its packages apart and the tourism fee once per reservation and date`: hospedada `D-1..D+1` com um pacote incluso e um adicional e taxa de turismo 5,00 com 3 pessoas → hospedagem (sem os inclusos), incluso sem taxa de serviço, adicional com a regra do código, `TX-TURISMO` 15,00 (3 × 5,00), todos com `businessDate = D`, origem `END_OF_DAY` e as chaves de `D`; a configuração da taxa volta a nula no `finally`.
  - `repeating the step for the same date posts nothing new`: executar o `RoomChargesStep` de novo para `D` (direto, dentro de `EndOfDayItems`) → mesmo número de lançamentos por chave.
  - `check-out and end of day on the same date do not duplicate`: reserva com chegada `D` e saída `D+1`, check-out pelo `frontdesk` em `D` (lança a noite de `D` pela chave) e outra hospedada cuja noite de `D` já foi lançada com a mesma chave pelo `BillingApi` → o fechamento de `D` não lança nenhuma linha a mais em nenhuma das duas.
  - `an injected failure mid-step and the resume post every charge exactly once`: três hospedadas; a conta da 2ª (na ordem por id) armada → execução `FAILED` em `room-charges`, a 1ª já lançada; desarma, retomada → `COMPLETED`; cada chave das três aparece uma vez; a data só avançou depois.
  - `an overstay gets no automatic night`: hospedada com saída `D` → nenhum lançamento em `D`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.RoomChargesStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): etapa de diárias, pacotes e taxa de turismo`.

---

### Task 10: Etapa `block-release` (400, RN-16)

**Estimativa:** 20 min · **Depende de:** Task 6 · **Risco:** normal

**Files:**
- Create: `endofday/application/steps/BlockReleaseStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/BlockReleaseStepTest.kt` (contexto real)

**Interfaces:**
- Produces: `BlockReleaseStep`: `name = "block-release"`, `position = 400`; itens = `blocksDueForRelease()`; item = `releaseUnused(id)`; pendência `BLOCK_RELEASE_FAILED` (referência = id do bloqueio). Só o fechamento chama `releaseUnused` (M4 E11).

- [ ] **Step 1: Teste que falha:** `blocks past the cutoff return unused rooms to inventory`: bloqueio com corte `D`, 3 unidades, 1 com pick-up → `RELEASED`, 2 unidades devolvidas (contador `blocked` do inventário); bloqueio com corte `D+1` intacto.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.BlockReleaseStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar.**
- [ ] **Step 5: Commit** `feat(endofday): etapa de liberação de bloqueios vencidos`.

---

### Task 11: Etapa `provisional-expiry` (500, RN-23)

**Estimativa:** 25 min · **Depende de:** Task 6 · **Risco:** normal

**Files:**
- Modify: `reservations/ReservationsApi.kt`, `reservations/application/InventoryService.kt`
- Create: `endofday/application/steps/ProvisionalExpiryStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/ProvisionalExpiryStepTest.kt` (contexto real)

**Interfaces:**
- Produces:
  - `ReservationsApi.dueProvisionals(): List<ReservationId>` — pré-reservas com a validade igual ou anterior a agora (`Clock`), por validade e id, até 1000 (mesmo critério do `ProvisionalExpiry`).
  - `ReservationsApi.expireProvisional(id: ReservationId): Boolean` — o `ProvisionalExpiryService.expire` (auditado `reservations.reservation.expire`, reconfere sob a trava; `false` se já não é pré-reserva vencida), na transação de quem chama.
  - `ProvisionalExpiryStep`: `name = "provisional-expiry"`, `position = 500`; pendência `PROVISIONAL_EXPIRY_FAILED`. Convive com o `ProvisionalExpiryJob` (o que um expirou o outro acha `false`).

- [ ] **Step 1: Teste que falha:** `expired provisional reservations are cancelled as expired, without penalty`: pré-reserva com validade vencida (relógio avançado pelo `MutableClock`, devolvido no `finally`) → `CANCELLED`, motivo `EXPIRED`, sem lançamento, inventário devolvido; pré-reserva ainda válida intacta.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.ProvisionalExpiryStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar.**
- [ ] **Step 5: Commit** `feat(endofday): etapa de expiração de pré-reservas`.

---

### Task 12: Etapas `card-pre-authorizations` (600) e `occupied-rooms-dirty` (700, RN-09)

**Estimativa:** 25 min · **Depende de:** Task 6 · **Risco:** normal

**Files:**
- Create: `endofday/application/steps/CardPreAuthorizationsStep.kt`, `endofday/application/steps/OccupiedRoomsDirtyStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/RoomsAndPreAuthorizationStepsTest.kt` (contexto real)

**Interfaces:**
- Produces:
  - `CardPreAuthorizationsStep`: `name = "card-pre-authorizations"`, `position = 600`, sempre `EndOfDayStepOutcome.NOT_APPLICABLE` (KDoc: a renovação é do M7, com `BillingApi.cardPreAuthorizationsExpiring`).
  - `OccupiedRoomsDirtyStep`: `name = "occupied-rooms-dirty"`, `position = 700`; itens = `activeRooms()` cujo `status(id)` tem `occupancy = OCCUPIED` e `housekeepingStatus != DIRTY`; item = `markDirty(id, RoomStatusSource.END_OF_DAY)`; pendência `ROOM_DIRTY_FAILED` (referência = id da UH).

- [ ] **Step 1: Testes que falham:**
  - `occupied rooms become dirty with the end-of-day source and vacant ones are left alone`: UH ocupada e limpa → `DIRTY`, histórico de status com origem `END_OF_DAY`; UH vaga e limpa intacta; UH ocupada já suja sem mudança nem erro.
  - `the pre-authorization step records not applicable`: a etapa `card-pre-authorizations` da execução termina `COMPLETED` com `result = "NOT_APPLICABLE"`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.RoomsAndPreAuthorizationStepsTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar.**
- [ ] **Step 5: Commit** `feat(endofday): UHs ocupadas a Sujo e pré-autorizações não aplicáveis`.

---

### Task 13: O gatilho só dispara o motor e volta (H12, decisão 4)

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (concorrência)

**Files:**
- Modify: `endofday/application/EndOfDayTrigger.kt`, `endofday/application/EndOfDayTriggerProperties.kt`, `src/main/resources/application.yaml`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayTriggerWaitTest.kt`, ajustes em `EndOfDayTriggerTest.kt`/`EndOfDayTriggerPropertiesTest.kt` (ruling no ledger), `testsupport/EndOfDayIntegrationTest.kt` (propriedade de teste)

**Interfaces:**
- Produces: propriedade `zeny.endofday.trigger.wait` (`Duration`, padrão `PT5S`; `EndOfDayTriggerProperties.wait`, positiva e no máximo `PT1M`, conferida no boot); `executed()` espera o motor só esse tempo: `StillRunning` conta como `STARTED`/`RESUMED` (o motor continua na thread dele, sob a trava `end-of-day-run`, e a rodada seguinte do gatilho acha a execução `RUNNING` e devolve `BUSY` ou `RESUMED` sem duplicar). O pool do agendador do Spring não muda. O KDoc do gatilho deixa de dizer "espera a execução até o fim". No `@EndOfDayIntegrationTest`, `zeny.endofday.trigger.wait=PT1S`.

- [ ] **Step 1: Testes que falham:**
  - `EndOfDayTriggerWaitTest` (contexto das falsas): `holdOn("fake-two", gate)` → `tick()` devolve `STARTED` em menos de 3 s com a execução ainda `RUNNING`; outra rodada enquanto o portão está fechado → `BUSY` ou `RESUMED`, `fake-one` chamado uma vez; solta o portão → `COMPLETED`; `settle` no `finally` (`@Timeout(60)`).
  - `EndOfDayTriggerPropertiesTest`: `wait` zero, negativa ou acima de `PT1M` falha o boot.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayTrigger*'`.
- [ ] **Step 3: Implementar;** os testes da E10 que dependiam da espera até o fim passam a esperar a execução terminar (`settle` ou espera com prazo), sem afrouxar asserção.
- [ ] **Step 4: Ver passar** `./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- [ ] **Step 5: Commit** `feat(endofday): gatilho automático só dispara o motor e volta`.

---

### Task 14: Motor — tipo próprio para a corrida no `fail` e retomada manual com `lock_timeout`

**Estimativa:** 35 min · **Depende de:** Task 13 · **Risco:** alto (concorrência)

**Files:**
- Modify: `endofday/application/EndOfDayEngine.kt`, `endofday/application/EndOfDayRunTransitions.kt`, `endofday/application/EndOfDayRunCommands.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayResumeLockTest.kt`, `EndOfDayEngineTest.kt` (caso novo)

**Interfaces:**
- Produces:
  - `internal class StepAttemptLostException : IllegalStateException` lançada por `EndOfDayRunTransitions.fail` quando a etapa já não está na tentativa deste executor; o motor captura só ela (hoje captura qualquer `IllegalStateException`). Outra `IllegalStateException` dentro do `fail` escapa (fica no log pela classe, como as falhas fora de etapa).
  - `EndOfDayRunCommands.resumeManually` trava a execução com `SET LOCAL lock_timeout = '5s'` antes do `lockForUpdate`; o tempo esgotado (a etapa em curso segura a linha) → `EndOfDayRunInProgressException` (`409 END_OF_DAY_RUN_IN_PROGRESS`, código já publicado no contrato da retomada).

- [ ] **Step 1: Testes que falham:**
  - `EndOfDayResumeLockTest` (contexto das falsas): `holdOn("fake-two", gate)` com a execução rodando; `POST …/runs/{id}/resume` com permissão → `409 END_OF_DAY_RUN_IN_PROGRESS` em menos de 10 s; solta o portão → `COMPLETED`; `settle` (`@Timeout(60)`).
  - `EndOfDayEngineTest`: `an unexpected IllegalStateException while recording a failure is not mistaken for a lost race` — a etapa falha e o `fail` lança uma `IllegalStateException` comum (forçada por um `spy`/sonda) → o `execute` deixa a exceção escapar em vez de devolver `Busy`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayResumeLockTest' --tests 'com.zeny.hotel.endofday.EndOfDayEngineTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** `./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- [ ] **Step 5: Commit** `fix(endofday): corrida do fail com tipo próprio e retomada que não espera a etapa`.

---

### Task 15: Avisos de pendência agrupados por etapa e tipo

**Estimativa:** 30 min · **Depende de:** Task 14 · **Risco:** normal

**Files:**
- Modify: `endofday/application/EndOfDayIssues.kt`, `endofday/application/EndOfDayRunTransitions.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayIssueGroupingTest.kt`, `EndOfDayTestConfiguration.kt` (`FakeStepsProbe.raiseIssuesOn(step, kind, count)`), ajuste em `EndOfDayIssuesTest.kt` se a contagem mudar (ruling no ledger)

**Interfaces:**
- Produces: as pendências levantadas pela etapa (pelo `EndOfDayStepContext.issues`) são gravadas na hora, e o aviso sai **uma vez por tipo** ao concluir a etapa, na transação do `complete`: título `"Pendência no fechamento de dd/MM/yyyy"`, texto `"<kind>: <n> pendência(s)"` (sem referência quando `n > 1`; com a referência quando `n = 1`, como hoje). `STEP_FAILED`, `STEP_UNREGISTERED` e o aviso fora da janela continuam um por ocorrência. Etapa que falha não avisa as pendências dela (são desfeitas). Pendência não ganha trilha própria (é registro da própria execução; a etapa é auditada) — decisão desta etapa, nota do handoff da E10.

- [ ] **Step 1: Testes que falham** (contexto das falsas):
  - `issues of the same kind in one step produce one notice per user with the count`: `raiseIssuesOn("fake-two", "TEST_PENDING", 3)` → 3 pendências, 1 aviso por usuário com `endofday.runs.read`, texto `TEST_PENDING: 3 pendência(s)`.
  - `two kinds produce two notices`; `a single issue keeps its reference in the notice`.
  - `a step that fails sends no grouped notice`: `raiseIssuesOn` + `failNext` → só o aviso de `STEP_FAILED`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayIssueGroupingTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** `./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- [ ] **Step 5: Commit** `feat(endofday): avisos de pendência agrupados por etapa e tipo`.

---

### Task 16: Fechamento real ponta a ponta e RF-FEC-05 com o fechamento

**Estimativa:** 45 min · **Depende de:** Tasks 7–12, 15 · **Risco:** alto (núcleo; concorrência)

**Files:**
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRealRunTest.kt` (contexto real)
- Modify: `README.md` só se descrever o fechamento ou o estado do marco; `docs/ARCHITECTURE.md` §13 só se a linha do fechamento precisar citar as etapas (sem mudar decisão)

**Interfaces:**
- Consumes: todas as etapas (Tasks 6–12), `closeCurrentDate`, `settle`, `BillingPostFailureProbe`.

- [ ] **Step 1: Testes** (devem passar sem mudança de produção; se algum falhar, é defeito das tarefas anteriores e volta como correção):
  - `a full run executes every step in order and advances the date last`: um cenário com chegada pendente, hospedada `D-1..D+1` numa UH ocupada, saída pendente, pré-reserva vencida e bloqueio com corte `D` → as 8 etapas `COMPLETED` na ordem das posições, início de cada uma ≥ fim da anterior, `card-pre-authorizations` com `NOT_APPLICABLE`, `advance-business-date` por último; efeitos das Tasks 7–12 sobre os dados do teste; data `D+1`.
  - `the date does not advance while an earlier step failed`: conta armada no probe → `FAILED` em `room-charges`, `advance-business-date` `PENDING`, data ainda `D`; desarma e retoma → `COMPLETED`, data `D+1`, cada chave uma vez.
  - `a manual posting racing the run lands entirely before or after the close`: thread A lança na conta de uma hospedada e segura a transação (latch); o fechamento roda em outra thread → a execução não conclui enquanto A segura (`advance-business-date` esperando a trava); solta A → o lançamento de A tem `businessDate = D` e foi confirmado antes do fim da execução de `D`; um lançamento depois do fim tem `businessDate = D+1`. Nenhum lançamento com data `D` tem `postedAt` posterior ao `finishedAt` da execução de `D` (`@Timeout(60)`).
- [ ] **Step 2: Rodar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRealRunTest'` → verde.
- [ ] **Step 3: Documentação** (se aplicável) e `./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- [ ] **Step 4: Commit** `test(endofday): fechamento real ponta a ponta e lançamento concorrente ao avanço`.

---

## Verificação da etapa (orquestrador)

- `./gradlew build` → `BUILD SUCCESSFUL` (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi`, `koverVerify`).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Critérios de aceite da E11 ↔ testes: (1) no-show com multa copiada e UH solta no mesmo commit — Tasks 3 e 7; (2) saída pendente gera pendência sem check-out — Task 8; (3) diárias, pacotes e taxas uma vez por reserva e data, check-out e fechamento na mesma data sem duplicar — Task 9; (4) pacotes inclusos à parte, sem taxa de serviço — Tasks 4 e 9; (5) falha no meio das diárias e retomada sem duplicar — Tasks 9 e 16; (6) bloqueios e pré-reservas — Tasks 10 e 11; (7) UHs ocupadas a Sujo e pré-autorizações "não aplicável" — Task 12; (8) data avança só depois das anteriores e gera as rotinas de checklist — Tasks 6 e 16; (9) lançamento depois do avanço na data nova e caminho concorrente — Tasks 5 e 16; (10) `ModularityTest`, build e contrato — Task 6 e acima.
- Notas para o handoff: a E13 encaixa `daily-statistics` (800) e `end-of-day-reports` (900) entre as pré-autorizações e o avanço; a E14 escuta `ReservationMarkedNoShow`; o relatório de pendências da RF-FEC-04 (contas com saldo, caixas abertos, FNRH, notas, ARI) fica com a E09 pelas views `report_*` (a E11 levanta só saídas pendentes e falhas de item); teste de unidade do *keep-alive* antes das etapas longas da E13.

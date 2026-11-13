# M6 E11 — Etapas financeiras e operacionais do fechamento: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** registrar no motor da E10, na ordem da RF-FEC-02, as etapas de negócio que não dependem de `reporting` — no-show com a multa da política copiada, alerta de saídas pendentes, diárias/pacotes/taxa de turismo, liberação de bloqueios vencidos, expiração de pré-reservas, pré-autorizações "não aplicável", UHs ocupadas → Sujo e avanço da data —, provar a RF-FEC-05 (inclusive com lançamento concorrente ao avanço) e fechar as notas da E10 endereçadas a esta etapa.

**Architecture:** o `endofday` passa a depender também de `reservations`, `billing` e `rooms` (subconjunto da linha da ARCHITECTURE §3.1, sem aresta nova na tabela). Cada etapa é um bean `EndOfDayStepHandler` em `endofday/application/steps/`, nas posições 100…700 e 1000 (800 e 900 ficam para a E13), e age só pelas APIs públicas dos donos do dado. O que falta nessas APIs entra nelas: em `reservations`, as consultas por data, a transição `NO_SHOW` com evento e as cobranças devidas de uma estadia (a regra do check-out da M5 E04, com a taxa de turismo, passa a ser do dono da reserva e é usada pelos dois caminhos); em `hotel`, a leitura escalar da data operacional travada contra o avanço, que o `billing` usa ao carimbar todo lançamento (RF-FEC-05 por construção, registrada na ARCHITECTURE §11). O trabalho por item roda numa transação própria (`REQUIRES_NEW`) por um apoio comum (`EndOfDayItems`); a recusa de negócio de um item vira pendência, qualquer outra falha falha a etapa e a deixa retomável sem duplicar (chaves `sourceReference`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Hibernate 7.4, Flyway 12, ShedLock 7, PostgreSQL 18 (Testcontainers 2), JUnit 5 + Kotest assertions.

**Spec:** `docs/roadmaps/roadmap-m6-operacao-fechamento.md`, seção "E11 — Etapas financeiras e operacionais do fechamento" (objetivo, escopo, critérios, notas para o plano, riscos), o handoff da E10 ("Notas para a próxima etapa" e "Decisões do usuário", H12), H4, H5 e H10; `docs/SPEC.md` §5.13 (RF-FEC-02, RF-FEC-03, RF-FEC-05), RF-RES-07, RF-CFG-03, RN-03, RN-09, RN-11, RN-15, RN-16, RN-21, RN-23, §8.4; `docs/ARCHITECTURE.md` §3.1, §3.2, §5, §11 e §13.

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1/§3.2): `endofday` declara `allowedDependencies = ["shared", "hotel", "notifications", "reservations", "billing", "rooms"]` (subconjunto da linha da §3.1; a tabela não muda) e usa só a API pública da raiz de cada um. O `endofday` **não** usa tipos de `rates` nem de `frontdesk`: o que precisa da reserva vem pronto da `ReservationsApi`. `notifications` nunca escuta evento de negócio. `housekeeping` (E02) escuta `BusinessDateAdvanced`; o `endofday` não chama governança.
- Contrato de etapa: o KDoc de `EndOfDayStepHandler` (E10) vale para toda etapa — idempotente pela **data**, nunca pelo `runId`; item que tolera falha em `REQUIRES_NEW` (aqui pelo `EndOfDayItems`, Task 9), sem tocar na linha `endofday.run`; pendência da falha de item levantada na transação da etapa; nunca capturar exceção de bean `@Transactional` que participa da transação da etapa; nome estável `[a-z][a-z0-9-]{0,59}`; posições de 100 em 100; nada de dado pessoal no resultado, `kind`, referência ou mensagem de pendência (RN-13; a referência é o id).
- Etapas e posições (nomes estáveis, publicados): `no-show` 100, `pending-departures` 200, `room-charges` 300, `block-release` 400, `provisional-expiry` 500, `card-pre-authorizations` 600, `occupied-rooms-dirty` 700, `advance-business-date` 1000. Renomear ou remover `advance-business-date` é proibido.
- RN-21 e RF-FEC-03: cada etapa repetida para a mesma data não duplica efeito; lançamento pela chave `NewPosting.sourceReference` (H5): `reservations.reservation:<id>:<noite>:room`, `…:<noite>:package:<packageId>`, `…:<noite>:tourism-fee` e `…:<data>:no-show`.
- Regras de dinheiro decididas nesta etapa (ruling; vão para "Decisões para o usuário"): multa de no-show = penalidade da política de cancelamento **copiada na reserva**, sem prazo, descontado o já cobrado (`penaltiesCharged`), sem isenção automática (isentar = estorno depois); taxa de turismo = `tourismFeePerPersonNight` **vigente** × (adultos + crianças), por noite, **nunca no day use** (não há noite), na mesma regra do check-out e do fechamento; o fechamento lança cada noite congelada de `max(chegada, data operacional do check-in)` até a data que fecha que ainda não tem lançamento (cobre o check-in feito durante a execução); hóspede além da saída não ganha diária automática (só a pendência); resíduo aceito: check-in durante a execução seguido de check-out antecipado antes do próximo fechamento deixa a noite do check-in sem lançamento (o check-out só cobra a noite da chegada no day use).
- RN-03: lançamento nunca muda nem some; correção por estorno na data corrente com referência ao original.
- RF-FEC-05: todo lançamento do `billing` carimba a data operacional lida por consulta escalar nativa com a linha de `hotel.business_day` em `FOR SHARE` até o commit (Tasks 7 e 8); o avanço (`FOR UPDATE`) espera os lançamentos em curso.
- Lançamentos do fechamento com origem `PostingSource.END_OF_DAY`; hospedagem e pacotes inclusos sem taxa de serviço (`serviceChargeRate = Percent.ZERO`), como no check-out (M5 E04 a).
- Migração nova em `src/main/resources/db/migration/reservations/` com versão posterior a `V2026_11_12_1001`: `V2026_11_13_1000__add_reservation_no_show.sql` (`outOfOrder` desligado).
- Toda mudança de API regenera `api/openapi.yaml` com `./gradlew updateOpenApi` na mesma mudança (nunca à mão); enum de resposta que cresce sai como `x-extensible-enum`; `.github/scripts/check-api-breaking.sh` sem `ERR` em `/api/v1`.
- Auditoria (regra ArchUnit `writeUseCasesAreAudited`): todo método `@Transactional` de escrita em `application` é `@Audited`; chamadas que precisam do proxy atravessam beans, nunca `this.`. O `EndOfDayItems` abre a transação do item por `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`), sem `@Transactional`, como `OfflineCommands` e `PrintingApiService`: a escrita do item já é auditada pela API do dono.
- Erros de domínio: `DomainException` com `ErrorCode` estável na raiz do módulo dono; mensagens em pt-BR sem dado pessoal. Código, API e banco em inglês; KDoc, mensagens, documentação e commits em pt-BR.
- Testes (H10): **nenhum teste desta etapa roda o fechamento no banco compartilhado.** No banco isolado `zeny_endofday`, quatro formas de contexto: (1) `@EndOfDayIntegrationTest` + `@Import(EndOfDayTestConfiguration::class)` — o do motor, só com as etapas falsas (as reais excluídas, Task 10); (2) `@EndOfDayIntegrationTest` + `@Import(EndOfDayRealStepsTestConfiguration::class)` — o das etapas reais; (3) `EndOfDayNoStepsProductionTest`, sem etapas; (4) os testes de persistência e transições da E10 com `@EndOfDayIntegrationTest` puro, que passam a importar a exclusão (Task 10). **Regra do banco isolado:** todo teste termina com a data corrente sem execução ou `COMPLETED` (`EndOfDayTestRuns.settle(context)` no `finally`), devolve o `MutableClock` e desfaz a configuração do hotel que mudou (taxa de turismo, fuso, horário); asserções só sobre os próprios dados, porque o fechamento real age sobre tudo o que os testes anteriores deixaram. As datas partem da `HotelApi.currentBusinessDate()`.
- Testes do `frontdesk`, `reservations`, `billing` e `hotel` no banco compartilhado não avançam a data (os testes de concorrência das Tasks 7 e 8 rodam no banco isolado).
- Máquina de 14 GB: um Gradle ou `docker build` por vez; nunca `./gradlew --stop`. As tarefas rodam em série (sem ondas paralelas).
- Contagem de "arquivos de produção" por tarefa: arquivos Kotlin de `src/main`; a interface pública e a delegação de uma linha na implementação (`ReservationsApi` + `InventoryService`, `HotelApi` + `HotelApiService`) contam como um, e uma porta de store com sua implementação também; migração, YAML, `api/openapi.yaml` e documentação não contam, nem o arquivo em que só muda KDoc ou descrição `@Schema` (texto do contrato, sem comportamento).
- Commits de cada soul com o trailer `Soul: <soul>`.

## Review Focus

1. **Lançamento manual da recepção enquanto o fechamento avança a data:** cai inteiro na data que fecha (antes do avanço) ou inteiro na nova, nunca numa data já fechada, mesmo quando a transação leu a data antes — Tasks 7, 8 e 20.
2. **Retomada depois de uma queda no meio das diárias:** as reservas já lançadas não ganham segundo lançamento; as que faltavam são lançadas uma vez — Tasks 13 e 20.
3. **Recepção faz o check-in de uma chegada enquanto o no-show roda:** a reserva fica hospedada ou no-show, nunca as duas coisas, sem multa indevida e sem 500 — Tasks 4 e 11.
4. **Check-in feito depois da etapa de diárias e antes do avanço:** a noite é lançada no fechamento seguinte, uma vez — Task 13.
5. **Hóspede que ficou além da saída (saída passada, ainda hospedado):** nenhuma diária automática, nenhum check-out, uma pendência por fechamento — Tasks 12 e 13.

---

## Mapa de arquivos

Produção:

| Arquivo | Responsabilidade | Task |
|---|---|---|
| `reservations/application/ReservationQueries.kt` (porta) + `reservations/infrastructure/JdbcReservationQueries.kt` | SQL das consultas por data | 1 |
| `reservations/ReservationsApi.kt` + `reservations/application/InventoryService.kt` | consultas por data, `markNoShow`, `chargesDue`, `dueProvisionals`, `expireProvisional` | 1, 4, 5, 15 |
| `reservations/ReservationChanges.kt`, `reservations/RoomAssignments.kt` | `ReservationHistoryKind.NO_SHOW`, `RoomAssignmentEndReason.RESERVATION_NO_SHOW` | 2 |
| `reservations/domain/ReservationLifecycle.kt`, `reservations/ReservationsEvents.kt` | ação `NO_SHOW`; evento `ReservationMarkedNoShow` | 3 |
| `reservations/application/ReservationLifecycle.kt`, `reservations/application/RoomAssignments.kt` | caso de uso do no-show | 4 |
| `reservations/NightCharges.kt` (novo) | cobranças de uma estadia por noite (regra única) | 5 |
| `frontdesk/domain/CheckOutRules.kt`, `frontdesk/application/CheckOutService.kt` | o check-out passa a usar `ReservationNightCharges` | 6 |
| `hotel/HotelApi.kt` + `hotel/application/HotelApiService.kt`, `hotel/infrastructure/HotelPersistence.kt` (+ porta `HotelStore`) | `lockBusinessDate()` | 7 |
| `billing/application/BillingOperations.kt` | `stamp()` com a data travada | 8 |
| `endofday/EndOfDayModule.kt`, `endofday/application/EndOfDaySteps.kt` (KDoc), `endofday/application/EndOfDayItems.kt` (novo) | dependências; item em transação própria | 9 |
| `endofday/application/steps/*.kt` (novos) | uma etapa por arquivo | 10–16 |
| `endofday/application/EndOfDayTrigger.kt`, `EndOfDayTriggerProperties.kt` | gatilho só dispara e volta (H12) | 17 |
| `endofday/application/EndOfDayEngine.kt`, `EndOfDayRunTransitions.kt`, `EndOfDayRunCommands.kt` | tipo próprio da corrida no `fail`; retomada com `lock_timeout` | 18 |
| `endofday/application/EndOfDayIssues.kt`, `EndOfDayRunTransitions.kt` | avisos agrupados por etapa e tipo | 19 |

Teste (apoio): `endofday/EndOfDayTestConfiguration.kt`, `EndOfDayTestRuns.kt`, `EndOfDayProductionStepsExcluded.kt`, `EndOfDayRealStepsTestConfiguration.kt`, `BillingPostProbe.kt` (novos ou ajustados), `ModularityTest.kt`. Documentação: `docs/ARCHITECTURE.md` §11 (Task 7).

---

### Task 1: Consultas por data no `reservations` (H4)

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (contrato público do módulo; decide quem é cobrado)

**Files:**
- Modify: `reservations/application/ReservationQueries.kt` (porta `ReservationQueryStore`) e `reservations/infrastructure/JdbcReservationQueries.kt`; `reservations/ReservationsApi.kt` + `reservations/application/InventoryService.kt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationDateQueriesTest.kt`

**Interfaces:**
- Produces (na `ReservationsApi`, sem conferir permissão, leitura sem trava, por id ascendente, sem limite — hotel de até 30 UHs; KDoc diz que o fechamento chama com a data que fecha):
  - `fun pendingArrivals(date: LocalDate): List<ReservationId>` — `CONFIRMED` com `arrival <= date` (as chegadas pendentes; pré-reserva nunca vira no-show).
  - `fun pendingDepartures(date: LocalDate): List<ReservationId>` — `IN_HOUSE` com `departure <= date`.
  - `fun inHouse(date: LocalDate): List<ReservationId>` — `IN_HOUSE` com `arrival <= date` (todas as hospedadas que já chegaram, inclusive as com saída passada; a Task 5 decide quais noites cada uma deve).
- O SQL reaproveita os índices parciais por situação (literais de `statusList`), como as listas operacionais.

- [ ] **Step 1: Testes que falham** (`@IntegrationTest`, banco compartilhado, sem avançar a data; cada teste cria as próprias reservas num tipo de UH novo com `ReservationsTestSupport` e força a situação com `forceInHouse`/`forceCheckedOut`; `D = hotelApi.currentBusinessDate()`; asserções só sobre os ids do teste):
  - `pending arrivals are confirmed reservations arriving on or before the date`: confirmada com chegada `D` → contém; pré-reserva com chegada `D`, cancelada, hospedada e confirmada com chegada `D+1` → não contém.
  - `pending departures are in-house stays leaving on or before the date`: hospedada com saída `D` → contém; hospedada com saída `D+1`, encerrada com saída `D` → não contém.
  - `in house lists every in-house stay that has arrived`: hospedadas `D..D+2`, day use `D..D` e com saída `D` → contém; encerrada e confirmada → não contém.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationDateQueriesTest'`.
- [ ] **Step 3: Implementar** as três consultas.
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(reservations): consultas por data para o fechamento do dia`.

---

### Task 2: Situação no-show — enums, migração e contrato

**Estimativa:** 20 min · **Depende de:** nenhuma · **Risco:** alto (persistência e contrato público)

**Files:**
- Modify: `reservations/ReservationChanges.kt` (`ReservationHistoryKind.NO_SHOW`), `reservations/RoomAssignments.kt` (`RoomAssignmentEndReason.RESERVATION_NO_SHOW`); as descrições dos campos `kind` e `releaseReason` nos DTOs (`reservations/web/ReservationLifecycleDtos.kt`, `reservations/web/RoomAssignmentsController.kt`) passam a citar os valores novos
- Create: `src/main/resources/db/migration/reservations/V2026_11_13_1000__add_reservation_no_show.sql`
- Modify: `api/openapi.yaml` (gerado)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationNoShowPersistenceTest.kt`

**Interfaces:**
- A migração troca as `CHECK` de `reservation_history.kind` e do motivo do fim da alocação (`V2026_10_22_1546__add_stay_transitions.sql`, linhas 74–85) para aceitar `NO_SHOW` e `RESERVATION_NO_SHOW`, mantendo os valores atuais. O gatilho adiado `reservation_assignment_released` já cobre `NO_SHOW`. Nenhuma coluna nova: a multa fica no lançamento, em `penalties_charged` e no histórico.
- Os dois enums já saem como `x-extensible-enum` (conferido na revisão do plano).

- [ ] **Step 1: Teste que falha** (`@IntegrationTest`): gravar histórico `NO_SHOW` e alocação encerrada com `RESERVATION_NO_SHOW` passa pelas `CHECK`; um valor desconhecido continua recusado.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationNoShowPersistenceTest'`.
- [ ] **Step 3: Implementar** enums, descrições e migração; `./gradlew updateOpenApi`.
- [ ] **Step 4: Ver passar** o mesmo comando, `./gradlew checkOpenApi` e `.github/scripts/check-api-breaking.sh` (sem `ERR`).
- [ ] **Step 5: Commit** `feat(reservations): situação no-show no banco e no contrato`.

---

### Task 3: No-show — transição e evento

**Estimativa:** 20 min · **Depende de:** Task 2 · **Risco:** alto (contrato de evento consumido pela E14)

**Files:**
- Modify: `reservations/domain/ReservationLifecycle.kt` (`ReservationAction.NO_SHOW`, transição só de `CONFIRMED`, rótulo `"marcada como no-show"`), `reservations/ReservationsEvents.kt`
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationNoShowDomainTest.kt`

**Interfaces:**
- Produces: `data class ReservationMarkedNoShow(reservationId: UUID, bookingId: UUID, roomTypeId: UUID, roomId: UUID?, arrival: LocalDate, departure: LocalDate, dayUse: Boolean, businessDate: LocalDate, penaltyCharged: Boolean, performedBy: UUID?, version: Long, markedAt: Instant)`, KDoc: SPEC §8.4 `Confirmed → NoShow` no fechamento; `roomId` é a UH que estava alocada (solta no mesmo commit); publicado pelo outbox; a FNRH o escuta na E14.

- [ ] **Step 1: Teste que falha** (unidade): `NO_SHOW` só a partir de `CONFIRMED`; de `PROVISIONAL`, `IN_HOUSE`, `CHECKED_OUT`, `CANCELLED` e `NO_SHOW` → `ReservationTransitionNotAllowedException` com `action = NO_SHOW`; `REACTIVATE` continua aceitando `NO_SHOW`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.ReservationNoShowDomainTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(reservations): transição e evento de no-show`.

---

### Task 4: Caso de uso `markNoShow` (RF-RES-07, RN-15)

**Estimativa:** 45 min · **Depende de:** Tasks 1, 3 · **Risco:** alto (dinheiro imutável, concorrência com o check-in)

**Files:**
- Modify: `reservations/ReservationsApi.kt` + `reservations/application/InventoryService.kt`, `reservations/application/ReservationLifecycle.kt`, `reservations/application/RoomAssignments.kt`; KDoc de `ReservationSummary.penaltiesCharged` (`reservations/Bookings.kt`) e a descrição do campo no DTO passam a citar o no-show
- Test: `src/test/kotlin/com/zeny/hotel/reservations/ReservationNoShowTest.kt`

**Interfaces:**
- Consumes: `ReservationAction.NO_SHOW`, `ReservationMarkedNoShow` (Task 3), `RoomAssignmentEndReason.RESERVATION_NO_SHOW` (Task 2).
- Produces: `ReservationsApi.markNoShow(id: ReservationId): ReservationSummary?` — KDoc: **só o fechamento chama**; na transação de quem chama (ou numa própria), auditada `reservations.reservation.no-show` em nome do ator corrente ou do sistema, sem permissão. Algoritmo (espelha `cancel`/`applyCancellation`):
  1. `requireWriteTransaction()`; trava a reserva. Se não é `CONFIRMED` ou `arrival` é depois da data operacional corrente → `audit.noChanges()` e devolve `null` (um check-in ou cancelamento confirmado antes da trava a tira daqui; nunca lança `409` por isso).
  2. Unidades das noites a partir de `max(arrival, data operacional)` devolvidas ao inventário ou ao bloqueio, pelo caminho do cancelamento (`blockUnits.onCancellation`).
  3. Multa: `CancellationPenalties.due(CancellationPenalties.amount(cancellationTerms, nightlyRates, roomTotal), penaltiesCharged)` — a política **copiada na reserva**, sem prazo; positiva → `BillingApi.post(folioId, NewPosting(transactionCode(NO_SHOW_FEE).id, due, 1, "Multa de no-show da reserva <code>", PostingSource.END_OF_DAY, sourceReference = "reservations.reservation:<id>:<data operacional>:no-show"))`.
  4. Alocação ativa encerrada com `RESERVATION_NO_SHOW` (nova função ao lado de `releaseOnCancellation`), no mesmo commit.
  5. Grava `status = NO_SHOW`, `roomAssignment = null`, `provisionalUntil = null`, `penaltiesCharged += lançado`, `version + 1`; histórico `NO_SHOW`; publica `ReservationMarkedNoShow`.

- [ ] **Step 1: Testes que falham** (`@IntegrationTest`, banco compartilhado, sem avançar a data; reservas com chegada `D = currentBusinessDate()`; planos e políticas criados pelo próprio teste):
  - `a confirmed reservation arriving today becomes no-show with the copied penalty and frees the room`: política `FIXED_AMOUNT` 150,00 copiada; depois de criar a reserva, a política do plano do teste muda para 300,00 → `NO_SHOW`; um lançamento `MULTA-NOSHOW` de 150,00, origem `END_OF_DAY`, `sourceReference` `…:<D>:no-show`; alocação encerrada com `RESERVATION_NO_SHOW`; inventário de `D` devolvido; histórico `NO_SHOW`; um `ReservationMarkedNoShow` com `roomId` e `penaltyCharged = true`.
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

### Task 5: Cobranças devidas de uma estadia no `reservations` (regra única)

**Estimativa:** 45 min · **Depende de:** Task 4 · **Risco:** alto (dinheiro imutável; contrato do módulo)

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/reservations/NightCharges.kt`
- Modify: `reservations/ReservationsApi.kt` + `reservations/application/InventoryService.kt` (a leitura da data do check-in vem do registro de transição já existente, `StayTransitionRecord.businessDate`)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/NightChargesTest.kt`, `ReservationChargesDueTest.kt`

**Interfaces:**
- Produces (raiz de `reservations`, pública):
  - `enum class NightChargeKind { ROOM, PACKAGE, TOURISM_FEE }`.
  - `data class NightCharge(kind: NightChargeKind, date: LocalDate, transactionCodeId: TransactionCodeId?, packageId: UUID?, quantity: BigDecimal, unitPrice: Money, description: String?, sourceReference: String, includedInRate: Boolean)` com `val amount: Money` (= `unitPrice * quantity`) e `fun toNewPosting(codes: (NightChargeKind) -> TransactionCodeId): NewPosting` (código `transactionCodeId ?: codes(kind)` — `ROOM` → `ROOM_CHARGE`, `TOURISM_FEE` → `TOURISM_FEE` —, `PostingSource.END_OF_DAY`, `serviceChargeRate = Percent.ZERO` se `includedInRate`, senão nulo, a `sourceReference`).
  - `object ReservationNightCharges { fun of(reservation: ReservationSummary, night: LocalDate, tourismFeePerPersonNight: Money?): List<NightCharge>; fun reference(id: ReservationId, night: LocalDate): String }` — para **qualquer** noite congelada: hospedagem = `roomAmount - includedPackagesAmount`, `includedInRate = true` (chave `<reference>:room`); cada pacote da noite no código dele, incluso com `includedInRate = true` e adicional `false` (`<reference>:package:<packageId>`); a taxa de turismo com `quantity = adults + childAges.size`, `unitPrice = tourismFeePerPersonNight`, descrição `"Taxa de turismo"`, `includedInRate = false` (`<reference>:tourism-fee`), **só fora do day use** e só com a taxa configurada; valores zero fora; `reference = "reservations.reservation:<id>:<night>"`. Sem noite congelada em `night` → lista vazia.
  - `data class StayCharges(reservationId: ReservationId, folioId: FolioId, charges: List<NightCharge>)` e `ReservationsApi.chargesDue(id: ReservationId, through: LocalDate): StayCharges?` — nulo se a reserva não existe ou não está `IN_HOUSE`; senão as cobranças de cada noite congelada `n` com `max(arrival, data operacional do check-in) <= n <= through` (no day use, a noite da chegada), em ordem de noite, com a taxa de turismo **vigente** (`HotelApi.taxSettings()`). As chaves deduplicam o que já foi lançado; quem chama lança todas. KDoc com o custo: cada fechamento repete o `post` das noites passadas de cada hospedada (cresce com o quadrado da estadia; aceitável em 30 UHs); se ficar lento, filtrar as chaves já lançadas.
- O valor da hospedagem e dos pacotes é o congelado (RN-14); a tarifa de grupo sem pacotes do plano não ganha pacote (nota M4 E11).

- [ ] **Step 1: Testes que falham:**
  - `NightChargesTest` (unidade): noite do meio de uma estadia de 3 noites com 2 adultos e 1 criança e taxa 5,00 → hospedagem `roomAmount - inclusos` com `includedInRate = true` + um pacote incluso (`true`) + um adicional (`false`) + taxa de turismo 15,00 (3 × 5,00); chaves exatas; day use → sem taxa de turismo; taxa nula → sem taxa; noite fora da estadia → vazia; pacote de valor zero fora; `toNewPosting` da hospedagem e do incluso tem `serviceChargeRate = 0`, do adicional e da taxa nulo, origem `END_OF_DAY`, código da taxa = `TOURISM_FEE`.
  - `ReservationChargesDueTest` (`@IntegrationTest`): hospedada com chegada `D-2` e check-in em `D-1` (forçado) → `chargesDue(id, D)` traz as noites `D-1` e `D`, não `D-2`; confirmada ou encerrada → `null`; `folioId` da reserva.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.reservations.NightChargesTest' --tests 'com.zeny.hotel.reservations.ReservationChargesDueTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** os mesmos.
- [ ] **Step 5: Commit** `feat(reservations): cobranças devidas da estadia, com a taxa de turismo, no dono da reserva`.

---

### Task 6: O check-out usa a regra única

**Estimativa:** 30 min · **Depende de:** Task 5 · **Risco:** alto (muda o caminho do dinheiro do check-out)

**Files:**
- Modify: `frontdesk/domain/CheckOutRules.kt`, `frontdesk/application/CheckOutService.kt`
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/CheckOutDomainTest.kt` (ajuste), `FrontdeskCheckOutApiTest.kt` (deve passar sem mudança)

**Interfaces:**
- Consumes: `ReservationNightCharges.of`, `NightCharge.toNewPosting` (Task 5).
- O check-out mantém a regra da M5 E04: só quando a data operacional é a da chegada, a noite da chegada, agora por `ReservationNightCharges.of(reservation, reservation.arrival, hotel.taxSettings().tourismFeePerPersonNight)` (no day use, sem taxa de turismo; na saída no dia da chegada a estadia vira day use, então o comportamento financeiro não muda); `CheckOutService.post` usa `NightCharge.toNewPosting`. `StayCharge`/`StayChargeKind` saem se ficarem sem uso; a resposta do check-out não muda e as chaves são as mesmas da M5 E04.

- [ ] **Step 1: Ajustar** `CheckOutDomainTest` para a regra nova (mesmos valores e chaves que hoje) e ver falhar a compilação/execução: `./gradlew test --tests 'com.zeny.hotel.frontdesk.CheckOutDomainTest'`.
- [ ] **Step 2: Implementar.**
- [ ] **Step 3: Ver passar** `./gradlew test --tests 'com.zeny.hotel.frontdesk.*'`.
- [ ] **Step 4: Commit** `refactor(frontdesk): check-out lança as cobranças pela regra do reservations`.

---

### Task 7: Data operacional travada para quem lança (`hotel`, RF-FEC-05)

**Estimativa:** 35 min · **Depende de:** nenhuma · **Risco:** alto (concorrência; garantia nova entre módulos)

**Files:**
- Modify: `hotel/HotelApi.kt` + `hotel/application/HotelApiService.kt`, `hotel/infrastructure/HotelPersistence.kt` (+ porta `HotelStore`); `docs/ARCHITECTURE.md` §11 ("Garantias no banco", ao lado da regra das travas do inventário)
- Test: `src/test/kotlin/com/zeny/hotel/hotel/BusinessDateLockContractTest.kt` (`@IntegrationTest`), `src/test/kotlin/com/zeny/hotel/endofday/BusinessDateLockTest.kt` (banco isolado, contexto `@EndOfDayIntegrationTest` + `@Import(EndOfDayTestConfiguration::class)`)

**Interfaces:**
- Produces: `HotelApi.lockBusinessDate(): LocalDate` — **consulta escalar nativa** `SELECT current FROM hotel.business_day FOR SHARE` (por `JdbcClient` ou consulta nativa que devolve `LocalDate`), nunca pela entidade `BusinessDay`: uma entidade já carregada na sessão por `currentBusinessDate()` devolveria a data velha. **Sem `@Transactional`**: exige a transação de escrita de quem chama (sem ela, ou só leitura, `IllegalStateException`, como o `requireWriteTransaction` do `reservations`). KDoc com o protocolo: o avanço (`advanceBusinessDate`, `FOR UPDATE`) espera todas as travas `FOR SHARE` em curso, e quem trava depois do avanço confirmado lê a data nova; não há ciclo de espera porque o avanço só trava `hotel.business_day` (e a linha da execução do fechamento, que nenhum lançamento trava), e as travas `FOR SHARE` são compatíveis entre si, qualquer que seja a ordem conta → data ou data → conta de quem lança.
- ARCHITECTURE §11: a garantia "todo lançamento, pagamento, devolução, transferência, caução e reabertura do `billing` carimba a data operacional sob `FOR SHARE` de `hotel.business_day`; o avanço espera" (RF-FEC-05; previsto pelo H5 do M6), com a alternativa descartada (conferir no commit se a data foi fechada e recusar: mais tarde, mais caro e falha o pedido do usuário em vez de esperá-lo).

- [ ] **Step 1: Testes que falham:**
  - `BusinessDateLockContractTest`: fora de transação e em transação só de leitura → `IllegalStateException`; numa de escrita devolve a data corrente.
  - `BusinessDateLockTest` (cada teste termina com `settle`, que não acha execução; nenhum cria execução de fechamento; `@Timeout(30)`):
    - `a holder of the share lock delays the advance until it commits`: A abre transação de escrita, `lockBusinessDate()` → `D`, espera um latch; B chama `advanceBusinessDate(D)` → ainda não terminou depois de 500 ms; solta A → A confirma; B termina; data `D+1`.
    - `a transaction that read the date before the advance gets the new date when it locks`: A abre transação, `currentBusinessDate()` → `D` (entidade carregada), espera; B avança e confirma; A chama `lockBusinessDate()` → `D+1`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.hotel.BusinessDateLockContractTest' --tests 'com.zeny.hotel.endofday.BusinessDateLockTest'`.
- [ ] **Step 3: Implementar** e registrar a garantia na §11.
- [ ] **Step 4: Ver passar** os mesmos e `./gradlew test --tests 'com.zeny.hotel.hotel.*'`.
- [ ] **Step 5: Commit** `feat(hotel): data operacional travada contra o avanço para quem lança (RF-FEC-05)`.

---

### Task 8: O `billing` carimba com a data travada

**Estimativa:** 35 min · **Depende de:** Task 7 · **Risco:** alto (concorrência; todo lançamento)

**Files:**
- Modify: `billing/application/BillingOperations.kt` (`stamp()` passa a usar `hotel.lockBusinessDate()`; vale para lançamento, pagamento, devolução, transferência, caução e reabertura; `openFolio` e o caixa continuam com `currentBusinessDate()`)
- Test: `src/test/kotlin/com/zeny/hotel/endofday/BillingDateLockTest.kt` (banco isolado, contexto das falsas)

- [ ] **Step 1: Testes que falham** (`@Timeout(30)`; `settle` no `finally`):
  - `a posting in flight holds the advance and stays on the old date`: A, numa transação, `billingApi.post` (data `D`) e espera um latch; B `advanceBusinessDate(D)` não termina em 500 ms; solta A → lançamento `businessDate = D`, confirmado antes do fim de B; data `D+1`.
  - `a posting whose transaction read the date before the advance lands on the new date`: A lê `currentBusinessDate()` (`D`) na transação, espera; B avança e confirma; A lança → `businessDate = D+1`.
  - `a share lock is granted while the advance waits, so the opposite lock orders do not deadlock`: na **mesma** conta X — A trava X (`SELECT … FOR UPDATE` na conta por JDBC, dentro da transação de A) e segura; B lança em X (trava a data em `FOR SHARE` e espera X); C chama `advanceBusinessDate(D)` e espera a data atrás de B; A chama `lockBusinessDate()` → atendido sem esperar C; A confirma, depois B, depois C, sem `deadlock detected`.
  - `a correction of a posting from a closed date goes to the current date with the original reference`: lançamento em `D`, avanço, correção pelo endpoint de correção do `billing` → `businessDate = D+1`, `correctsId` = original.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.BillingDateLockTest'` (os dois primeiros caem: o avanço não espera e a data lida antes é usada).
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo e `./gradlew test --tests 'com.zeny.hotel.billing.*' --tests 'com.zeny.hotel.frontdesk.*'`.
- [ ] **Step 5: Commit** `feat(billing): lançamento carimba a data operacional travada (RF-FEC-05)`.

---

### Task 9: `endofday` — dependências e item em transação própria

**Estimativa:** 30 min · **Depende de:** nenhuma · **Risco:** alto (núcleo das etapas)

**Files:**
- Modify: `endofday/EndOfDayModule.kt` (dependências e KDoc), `endofday/application/EndOfDaySteps.kt` (KDoc: o item em `REQUIRES_NEW` pelo `EndOfDayItems`)
- Create: `endofday/application/EndOfDayItems.kt`
- Test: `src/test/kotlin/com/zeny/hotel/ModularityTest.kt`, `endofday/EndOfDayItemsTest.kt` (contexto das falsas)

**Interfaces:**
- Produces:
  - `EndOfDayModule`: `allowedDependencies = ["shared", "hotel", "notifications", "reservations", "billing", "rooms"]`.
  - `@Component class EndOfDayItems(transactionManager: PlatformTransactionManager)`: `fun <T> inOwnTransaction(work: () -> T): T` (`TransactionTemplate` com `PROPAGATION_REQUIRES_NEW`) e `fun <I> each(context: EndOfDayStepContext, issueKind: String, items: List<I>, reference: (I) -> String, work: (I) -> Unit): Int` — roda cada item na transação dele; `DomainException` do item → depois do rollback dele, `context.issues.raise(issueKind, reference(item), "<issueKind>: <código do erro>")` na transação da etapa; qualquer outra exceção escapa e falha a etapa. Devolve quantos itens concluíram. KDoc: a recusa de negócio de um item não derruba o fechamento; erro de infraestrutura derruba e a retomada repete a etapa (idempotente pela chave de cada item).

- [ ] **Step 1: Testes que falham:**
  - `ModularityTest`: o teste do `endofday` passa a exigir exatamente `shared, hotel, notifications, reservations, billing, rooms` (renomear o método).
  - `EndOfDayItemsTest`: três itens, o 2º lança `BusinessRuleException` → 2 concluídos, uma pendência com a referência do 2º e o código; o 1º e o 3º confirmados mesmo com a etapa depois falhando; item que lança `IllegalStateException` → a exceção escapa e o efeito dos itens anteriores fica.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.ModularityTest' --tests 'com.zeny.hotel.endofday.EndOfDayItemsTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** os mesmos.
- [ ] **Step 5: Commit** `feat(endofday): dependências das etapas reais e item em transação própria`.

---

### Task 10: Etapa de avanço da data e contextos de teste das etapas reais

**Estimativa:** 45 min · **Depende de:** Tasks 8, 9 · **Risco:** alto (núcleo; contexto de testes)

**Files:**
- Create: `endofday/application/steps/AdvanceBusinessDateStep.kt`
- Test: `endofday/EndOfDayProductionStepsExcluded.kt`, `endofday/EndOfDayRealStepsTestConfiguration.kt`, `endofday/BillingPostProbe.kt` (novos); `endofday/EndOfDayTestConfiguration.kt` (importa a exclusão; o KDoc "Revisar na E11" sai), `endofday/EndOfDayNoStepsProductionTest.kt`, `EndOfDayRunTransitionsTest.kt`, `EndOfDayRunTransitionsAttackTest.kt`, `EndOfDayRunPersistenceTest.kt`, `EndOfDayRunPersistenceAttackTest.kt` (importam a exclusão; mudança mínima em testes do testador da E10, ruling no ledger), `endofday/EndOfDayTestRuns.kt`, `endofday/EndOfDayAdvanceStepTest.kt`

**Interfaces:**
- Produces:
  - `AdvanceBusinessDateStep`: `name = "advance-business-date"`, `position = 1000`, `execute` = `hotel.advanceBusinessDate(context.businessDate)` → `DONE`. KDoc: última etapa, nome proibido de mudar; o avanço espera os lançamentos em curso (Task 7).
- Apoio de teste:
  - `EndOfDayProductionStepsExcluded` (`@TestConfiguration`): um `BeanDefinitionRegistryPostProcessor` (bean `static`/`@JvmStatic`) que remove as definições cujo `beanClassName` começa com `com.zeny.hotel.endofday.application.steps.`.
  - `EndOfDayRealStepsTestConfiguration` (`@TestConfiguration`): importa `MutableClockConfiguration`, `IamTestConfiguration` e `InventoryTestConfiguration` e declara o `BillingPostProbe`; sem etapas falsas.
  - `BillingPostProbe`: `BeanPostProcessor` que embrulha o bean `BillingApi` num proxy (ordem: depois do proxy transacional, para o proxy dele envolver o transacional); por conta, `failOn(folioId)` faz o `post` lançar `IllegalStateException("Falha forçada pelo teste")` (não é `DomainException`: falha a etapa) e `holdOn(folioId, latch)` faz o `post` esperar o latch (até 30 s) antes de delegar; `reset()` desarma tudo. Se algum bean injetar a classe concreta, troque por um `@Primary` que delega.
  - `EndOfDayTestRuns`: `settle(context)` passa a zerar o `FakeStepsProbe` e o `BillingPostProbe` só se existirem (`getBeanProvider(...).ifAvailable`); novo `fun closeCurrentDate(context: ApplicationContext): EndOfDayRunId` — cria a execução da data corrente (`EndOfDayRunCommands.createAutomatic`) e a roda pelo motor esperando até 60 s.

- [ ] **Step 1: Testes que falham:**
  - `EndOfDayAdvanceStepTest` (contexto real): o registro termina em `advance-business-date` (1000) e não tem `fake-*`; `closeCurrentDate` → `COMPLETED`, data `D+1`, uma trilha `hotel.business-date.advance`; o `BusinessDateAdvanced` gera as rotinas de checklist de `D+1` (uma rotina criada pelo teste ganha execução em `D+1`, esperada com prazo).
  - No contexto das falsas, o registro tem só `fake-one`…`fake-advance` (asserção nova no `EndOfDayItemsTest` ou no `EndOfDayStepRegistryTest`).
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayAdvanceStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo e `./gradlew test --tests 'com.zeny.hotel.endofday.*' --tests 'com.zeny.hotel.testsupport.*'` (os testes da E10 continuam verdes).
- [ ] **Step 5: Commit** `feat(endofday): etapa de avanço da data e contextos das etapas reais`.

---

### Task 11: Etapa `no-show` (100)

**Estimativa:** 30 min · **Depende de:** Tasks 4, 10 · **Risco:** alto (dinheiro imutável)

**Files:**
- Create: `endofday/application/steps/NoShowStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/NoShowStepTest.kt` (contexto real)

**Interfaces:**
- Consumes: `ReservationsApi.pendingArrivals`, `markNoShow` (Tasks 1, 4); `EndOfDayItems.each` (Task 9).
- Produces: `NoShowStep`: `name = "no-show"`, `position = 100`; itens = `pendingArrivals(context.businessDate)`; item = `markNoShow(id)`; pendência `NO_SHOW_FAILED` (referência = id da reserva). Resultado `NOT_APPLICABLE` sem item; senão `DONE`.

- [ ] **Step 1: Testes que falham** (`closeCurrentDate` + `settle`):
  - `only confirmed reservations with a pending arrival become no-show, with the copied penalty, and free the room in the same commit`: confirmada com chegada `D` e UH alocada → `NO_SHOW`, multa da política copiada, alocação encerrada; pré-reserva, hospedada e confirmada com chegada `D+1` intactas.
  - `a refused item becomes an issue and the run goes on`: uma reserva cuja conta foi encerrada antes (o `post` da multa recusa com `409 FOLIO_CLOSED`) → pendência `NO_SHOW_FAILED` com o id, a reserva continua `CONFIRMED`, as outras viram no-show e a execução termina `COMPLETED`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.NoShowStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): etapa de no-show`.

---

### Task 12: Etapa `pending-departures` (200)

**Estimativa:** 20 min · **Depende de:** Tasks 1, 10 · **Risco:** normal

**Files:**
- Create: `endofday/application/steps/PendingDeparturesStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/PendingDeparturesStepTest.kt` (contexto real)

**Interfaces:**
- Produces: `PendingDeparturesStep`: `name = "pending-departures"`, `position = 200`; para cada `pendingDepartures(context.businessDate)`, `context.issues.raise("PENDING_DEPARTURE", id, "Saída pendente: faça o check-out ou altere a saída; o fechamento não faz check-out.")`. Nunca chama check-out. `NOT_APPLICABLE` sem saída pendente.

- [ ] **Step 1: Teste que falha:** `a pending departure raises an issue and is never checked out automatically`: hospedada com saída `D` e outra com saída `D-1` (forçada) → uma `PENDING_DEPARTURE` para cada, com o id; as duas continuam `IN_HOUSE`, a UH continua ocupada; aviso `end-of-day-pending` para quem tem `endofday.runs.read`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.PendingDeparturesStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): etapa de saídas pendentes`.

---

### Task 13: Etapa `room-charges` (300) — diárias, pacotes e taxa de turismo

**Estimativa:** 45 min · **Depende de:** Tasks 5, 10 · **Risco:** alto (dinheiro imutável; núcleo)

**Files:**
- Create: `endofday/application/steps/RoomChargesStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/RoomChargesStepTest.kt` (contexto real)

**Interfaces:**
- Consumes: `inHouse`, `chargesDue`, `NightCharge.toNewPosting` (Tasks 1, 5); `EndOfDayItems.each` (Task 9); `BillingApi.post`, `transactionCode(ROOM_CHARGE)`, `transactionCode(TOURISM_FEE)` (resolvidos uma vez por execução da etapa).
- Produces: `RoomChargesStep`: `name = "room-charges"`, `position = 300`; itens = `inHouse(context.businessDate)`; item = `chargesDue(id, context.businessDate)` (nulo → nada: a reserva saiu de `IN_HOUSE` depois da listagem) → `post(folioId, charge.toNewPosting(...))` para cada cobrança; pendência `ROOM_CHARGE_FAILED`. Resultado `NOT_APPLICABLE` sem hospedada.

- [ ] **Step 1: Testes que falham** (taxa de turismo configurada pelo teste e devolvida a nula no `finally`):
  - `in-house stays get the night, its packages apart and the tourism fee once per reservation and night`: hospedada `D-1..D+1` (check-in em `D-1`, com a noite `D-1` já lançada pela chave) com um pacote incluso e um adicional, 2 adultos e 1 criança, taxa 5,00 → em `D`: hospedagem (sem os inclusos, taxa de serviço zero), incluso sem taxa de serviço, adicional pela regra do código, `TX-TURISMO` 15,00; todos `businessDate = D`, origem `END_OF_DAY`, chaves da noite `D`; nada novo para `D-1`.
  - `a day use in house at the close is charged without tourism fee`.
  - `repeating the step for the same date posts nothing new`: rodar o `RoomChargesStep` de novo para `D` → mesmo número de lançamentos por chave.
  - `a check-in made after the charges step is charged by the next close`: com `holdOn` do `BillingPostProbe` segurando o lançamento de uma hospedada já listada, cria-se uma reserva com chegada `D` e saída a partir de `D+2` (ou um walk-in) e faz-se o check-in dela (a confirmada com chegada `D` criada antes do fechamento já teria virado no-show na etapa 100); solta → execução `COMPLETED`, data `D+1`; o fechamento de `D+1` lança a noite `D` e a `D+1` dessa reserva, uma vez cada (chaves das noites), com `businessDate = D+1`.
  - `a day use checked out between listing and posting is not charged twice`: duas hospedadas (a segurada criada antes da day use, para vir antes na ordem por id); `holdOn` segura o lançamento da primeira listada; enquanto isso o check-out da day use pelo `frontdesk` lança a noite e encerra a conta; solta → nenhuma linha a mais para a day use, nenhuma pendência dela.
  - `an injected failure mid-step and the resume post every charge exactly once`: três hospedadas; `failOn` na conta da 2ª (na ordem por id) → execução `FAILED` em `room-charges`, a 1ª já lançada, data ainda `D`; `reset`, retomada → `COMPLETED`; cada chave das três aparece uma vez; data `D+1`.
  - `an overstay gets no automatic night`: hospedada com saída `D` → nenhum lançamento novo.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.RoomChargesStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar** o mesmo comando.
- [ ] **Step 5: Commit** `feat(endofday): etapa de diárias, pacotes e taxa de turismo`.

---

### Task 14: Etapa `block-release` (400, RN-16)

**Estimativa:** 20 min · **Depende de:** Task 10 · **Risco:** normal

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

### Task 15: Etapa `provisional-expiry` (500, RN-23)

**Estimativa:** 25 min · **Depende de:** Tasks 5, 10 · **Risco:** alto (contrato do módulo; convive com o `ProvisionalExpiryJob`)

**Files:**
- Modify: `reservations/ReservationsApi.kt` + `reservations/application/InventoryService.kt`
- Create: `endofday/application/steps/ProvisionalExpiryStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/ProvisionalExpiryStepTest.kt` (contexto real)

**Interfaces:**
- Produces:
  - `ReservationsApi.dueProvisionals(): List<ReservationId>` — pré-reservas com a validade igual ou anterior a agora (`Clock`), por validade e id, até 1000 (mesmo critério do `ProvisionalExpiry`).
  - `ReservationsApi.expireProvisional(id: ReservationId): Boolean` — o `ProvisionalExpiryService.expire` (auditado `reservations.reservation.expire`, reconfere sob a trava; `false` se já não é pré-reserva vencida), na transação de quem chama.
  - `ProvisionalExpiryStep`: `name = "provisional-expiry"`, `position = 500`; pendência `PROVISIONAL_EXPIRY_FAILED`.

- [ ] **Step 1: Testes que falham:**
  - `expired provisional reservations are cancelled as expired, without penalty`: pré-reserva com validade vencida (relógio avançado pelo `MutableClock`, devolvido no `finally`) → `CANCELLED`, motivo `EXPIRED`, sem lançamento, inventário devolvido; pré-reserva ainda válida intacta.
  - `a reservation already expired by the job is skipped`: `expireProvisional` chamado antes pela API (como o job faz) → a etapa termina sem pendência e sem segundo histórico de cancelamento.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.ProvisionalExpiryStepTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar.**
- [ ] **Step 5: Commit** `feat(endofday): etapa de expiração de pré-reservas`.

---

### Task 16: Etapas `card-pre-authorizations` (600) e `occupied-rooms-dirty` (700, RN-09)

**Estimativa:** 25 min · **Depende de:** Task 10 · **Risco:** alto (escrita de status de UH concorrendo com check-out e governança)

**Files:**
- Create: `endofday/application/steps/CardPreAuthorizationsStep.kt`, `endofday/application/steps/OccupiedRoomsDirtyStep.kt`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/RoomsAndPreAuthorizationStepsTest.kt` (contexto real)

**Interfaces:**
- Produces:
  - `CardPreAuthorizationsStep`: `name = "card-pre-authorizations"`, `position = 600`, sempre `EndOfDayStepOutcome.NOT_APPLICABLE` (KDoc: a renovação é do M7, com `BillingApi.cardPreAuthorizationsExpiring`).
  - `OccupiedRoomsDirtyStep`: `name = "occupied-rooms-dirty"`, `position = 700`; itens = `activeRooms()` cujo `status(id)` tem `occupancy = OCCUPIED` e `housekeepingStatus != DIRTY`; item = `markDirty(id, RoomStatusSource.END_OF_DAY)` (o `rooms` trava a UH e reconfere); pendência `ROOM_DIRTY_FAILED` (referência = id da UH).

- [ ] **Step 1: Testes que falham:**
  - `occupied rooms become dirty with the end-of-day source and vacant ones are left alone`: UH ocupada e limpa → `DIRTY`, histórico de status com origem `END_OF_DAY`; UH vaga e limpa intacta; UH ocupada já suja sem mudança nem erro.
  - `a room vacated between listing and marking is still marked dirty without error`: check-out da UH (vaga e suja pelo `rooms`) entre a leitura do status e o item → sem pendência.
  - `the pre-authorization step records not applicable`: a etapa `card-pre-authorizations` da execução termina `COMPLETED` com `result = "NOT_APPLICABLE"`.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.RoomsAndPreAuthorizationStepsTest'`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ver passar.**
- [ ] **Step 5: Commit** `feat(endofday): UHs ocupadas a Sujo e pré-autorizações não aplicáveis`.

---

### Task 17: O gatilho só dispara o motor e volta (H12, decisão 4)

**Estimativa:** 30 min · **Depende de:** Task 10 · **Risco:** alto (concorrência)

**Files:**
- Modify: `endofday/application/EndOfDayTrigger.kt`, `endofday/application/EndOfDayTriggerProperties.kt`, `src/main/resources/application.yaml`
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayTriggerWaitTest.kt`, ajustes em `EndOfDayTriggerTest.kt`/`EndOfDayTriggerPropertiesTest.kt` (ruling no ledger), `testsupport/EndOfDayIntegrationTest.kt` (propriedade de teste)

**Interfaces:**
- Produces: propriedade `zeny.endofday.trigger.wait` (`Duration`, padrão `PT5S`; `EndOfDayTriggerProperties.wait`, positiva e no máximo `PT1M`, conferida no boot); `executed()` espera o motor só esse tempo: `StillRunning` conta como `STARTED`/`RESUMED` (o motor continua na thread dele, sob a trava `end-of-day-run`, e a rodada seguinte acha a execução `RUNNING` e devolve `BUSY` ou `RESUMED` sem duplicar). O pool do agendador do Spring não muda. O KDoc do gatilho deixa de dizer "espera a execução até o fim". No `@EndOfDayIntegrationTest`, `zeny.endofday.trigger.wait=PT1S`.

- [ ] **Step 1: Testes que falham:**
  - `EndOfDayTriggerWaitTest` (contexto das falsas): `holdOn("fake-two", gate)` → `tick()` devolve `STARTED` em menos de 3 s com a execução ainda `RUNNING`; outra rodada enquanto o portão está fechado → `BUSY` ou `RESUMED`, `fake-one` chamado uma vez; solta o portão → `COMPLETED`; `settle` no `finally` (`@Timeout(60)`).
  - `EndOfDayTriggerPropertiesTest`: `wait` zero, negativa ou acima de `PT1M` falha o boot.
- [ ] **Step 2: Ver falhar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayTrigger*'`.
- [ ] **Step 3: Implementar;** os testes da E10 que dependiam da espera até o fim passam a esperar a execução terminar (`settle` ou espera com prazo), sem afrouxar asserção.
- [ ] **Step 4: Ver passar** `./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- [ ] **Step 5: Commit** `feat(endofday): gatilho automático só dispara o motor e volta`.

---

### Task 18: Motor — tipo próprio para a corrida no `fail` e retomada manual com `lock_timeout`

**Estimativa:** 35 min · **Depende de:** Task 17 · **Risco:** alto (concorrência)

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

### Task 19: Avisos de pendência agrupados por etapa e tipo

**Estimativa:** 30 min · **Depende de:** Tasks 10, 18 · **Risco:** normal

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

### Task 20: Fechamento real ponta a ponta e RF-FEC-05 com o fechamento

**Estimativa:** 45 min · **Depende de:** Tasks 11–16, 19 · **Risco:** alto (núcleo; concorrência)

**Files:**
- Test: `src/test/kotlin/com/zeny/hotel/endofday/EndOfDayRealRunTest.kt` (contexto real)
- Modify: `README.md` só se descrever o fechamento ou o estado do marco; `docs/ARCHITECTURE.md` §13 só para citar as etapas na linha do fechamento (sem mudar decisão)

**Interfaces:**
- Consumes: todas as etapas (Tasks 10–16), `closeCurrentDate`, `settle`, `BillingPostProbe`.

- [ ] **Step 1: Testes** (devem passar sem mudança de produção; se algum falhar, é defeito das tarefas anteriores e volta como correção):
  - `a full run executes every step in order and advances the date last`: chegada pendente, hospedada `D-1..D+1` numa UH ocupada, saída pendente, pré-reserva vencida e bloqueio com corte `D` → as 8 etapas `COMPLETED` na ordem das posições, início de cada uma ≥ fim da anterior, `card-pre-authorizations` com `NOT_APPLICABLE`, `advance-business-date` por último; efeitos das Tasks 11–16 sobre os dados do teste; data `D+1`.
  - `the date does not advance while an earlier step failed`: `failOn` numa conta → `FAILED` em `room-charges`, `advance-business-date` `PENDING`, data ainda `D`; `reset` e retoma → `COMPLETED`, data `D+1`, cada chave uma vez.
  - `a manual posting racing the run lands entirely before or after the close`: thread A lança na conta de uma reserva **confirmada futura** (que o fechamento não toca) e segura a transação (latch); o fechamento roda em outra thread → a execução não conclui enquanto A segura (`advance-business-date` esperando a trava da data); solta A → o lançamento de A tem `businessDate = D` e foi confirmado antes do fim da execução de `D`; um lançamento depois do fim tem `businessDate = D+1`. Nenhum lançamento com data `D` tem `postedAt` posterior ao `finishedAt` da execução de `D` (`@Timeout(60)`).
- [ ] **Step 2: Rodar:** `./gradlew test --tests 'com.zeny.hotel.endofday.EndOfDayRealRunTest'` → verde.
- [ ] **Step 3: Documentação** (se aplicável) e `./gradlew test --tests 'com.zeny.hotel.endofday.*'`.
- [ ] **Step 4: Commit** `test(endofday): fechamento real ponta a ponta e lançamento concorrente ao avanço`.

---

## Verificação da etapa (orquestrador)

- `./gradlew build` → `BUILD SUCCESSFUL` (inclui `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi`, `koverVerify`).
- `.github/scripts/check-api-breaking.sh` → nenhuma mudança `ERR` em `/api/v1`.
- Critérios de aceite da E11 ↔ testes: (1) no-show com multa copiada e UH solta no mesmo commit — Tasks 4 e 11; (2) saída pendente gera pendência sem check-out — Task 12; (3) diárias, pacotes e taxas uma vez por reserva e data, check-out e fechamento na mesma data sem duplicar — Task 13; (4) pacotes inclusos à parte, sem taxa de serviço — Tasks 5 e 13; (5) falha no meio das diárias e retomada sem duplicar — Tasks 13 e 20; (6) bloqueios e pré-reservas — Tasks 14 e 15; (7) UHs ocupadas a Sujo e pré-autorizações "não aplicável" — Task 16; (8) data avança só depois das anteriores e gera as rotinas de checklist — Tasks 10 e 20; (9) lançamento depois do avanço na data nova e caminho concorrente — Tasks 7, 8 e 20; (10) `ModularityTest`, build e contrato — Task 9 e acima.
- Notas para o handoff: a E13 encaixa `daily-statistics` (800) e `end-of-day-reports` (900) entre as pré-autorizações e o avanço; a E14 escuta `ReservationMarkedNoShow`; o relatório de pendências da RF-FEC-04 (contas com saldo, caixas abertos, FNRH, notas, ARI) fica com a E09/E13 pelas views `report_*` (a E11 levanta só saídas pendentes e falhas de item); teste de unidade do *keep-alive* antes das etapas longas da E13; resíduo aceito: check-in durante a execução seguido de check-out antecipado antes do próximo fechamento deixa a noite do check-in sem lançamento (o check-out só cobra a noite da chegada no day use).

## Revisão de arquitetura

- **Veredito:** aprovado com notas (rodada 1), pelo `revisor-arquitetura` no modelo `opus`.
- **Rodadas:** 1 (rodada 0 bloqueado com 5 Importantes — leitura da data pela entidade JPA, ordem das travas e registro na §11, taxa de turismo fora da regra única, noite perdida no check-in durante a execução, forma das tarefas — e 9 Menores, todos corrigidos; rodada 1 aprovado com 5 Menores).
- **Notas Menores da rodada 1, aplicadas no plano:** M1 teste de deadlock na mesma conta (Task 8); M2 check-in depois da etapa 300 com reserva criada durante a espera e ordem por id do day use (Task 13); M3 resíduo do check-in durante a execução nas decisões do usuário (Global Constraints); M4 custo da recuperação de noites no KDoc de `chargesDue` (Task 5); M5 arquivo só com KDoc ou `@Schema` não conta como produção (Global Constraints).
- **Decisões que seguem ao usuário no fim do lote (regras de dinheiro, tomadas pela recomendada):** multa de no-show pela política copiada, sem prazo e sem isenção automática; taxa de turismo vigente, contando crianças, nunca no day use; recuperação das noites a partir da data do check-in; hóspede além da saída sem diária automática; o resíduo do check-in durante a execução seguido de check-out antecipado. A trava `FOR SHARE` da data estava prevista pelo H5 e fica registrada na ARCHITECTURE §11.

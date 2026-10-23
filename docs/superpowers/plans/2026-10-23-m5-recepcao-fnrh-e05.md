# M5 E05 — Troca de UH, extensão, redução e early check-in — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** expor no `frontdesk` a troca de UH do hospedado (RF-REC-04) com o status das duas UHs, cobrir de ponta a ponta a extensão e a redução do hospedado (RF-REC-05) pelo `PUT` da E01 e cobrar o early check-in no check-in e no walk-in com o preço informado antes (RF-REC-03, RN-11, H13).

**Architecture:** a transição `ReservationsApi.moveRoom` (E01) ganha a versão esperada conferida sob a trava; o `frontdesk` ganha `POST /api/v1/reservations/{id}/room-moves` (caso de uso `FrontdeskRoomMoveService`) que confere a UH de destino pronta, chama `moveRoom`, vaga e suja a origem e ocupa o destino com `markOccupiedIfReady`, numa transação. O early check-in reaproveita a regra do late da E04 (renomeada para servir aos dois) e é lançado nos passos comuns do check-in (`CheckInSteps.complete`). A redução da saída do hospedado deixa de passar pelas restrições de venda (decisão desta etapa, no `reservations`).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, JdbcClient, Testcontainers (`@IntegrationTest`), Kotest, MockMvc.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E05 — Troca de UH, extensão, redução e early check-in" (com H13, as notas para a E05 nos handoffs de E01, E02, E03 e E04) e SPEC RF-REC-03, RF-REC-04, RF-REC-05, RN-11, RN-14. Referência: notas do brain `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Módulo reservations (ciclo de vida, alocação, grupos e busca)`, `ZenyHotelManager - Módulo rooms`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Testes de integração`, e a captura pendente `00 Inbox/2026-10-23 - ZenyHotelManager - atualização m5-recepcao-fnrh E04 - 23ba6833.md` (o check-out e o lançamento idempotente).

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1): `frontdesk` só pelas APIs públicas (`ReservationsApi`, `RoomsApi`, `BillingApi`, `HotelApi`); nunca `fnrh` nem `iam`; do `rates` só os tipos públicos já usados; nenhuma aresta nova.
- Tudo ou nada (ARCHITECTURE §6.1): a troca (transição, status das duas UHs) e o check-in com o early (transição, caução, lançamento, ocupação) numa transação; qualquer recusa desfaz tudo.
- `@Idempotent` nos POSTs com efeito (troca de UH; o check-in e o walk-in já são); a troca exige `If-Match` da reserva (`428` sem, `412` com versão velha).
- Lançamentos só pela `BillingApi.post` (RN-03); early no código `ROOM_CHARGE`, descrição `Early check-in`, origem `MANUAL`, só com o valor da cotação ainda vigente (`409 EARLY_LATE_FEE_CHANGED`; sem oferta `422 EARLY_LATE_NOT_OFFERED`).
- RN-14: noites passadas nunca mudam no recálculo; RN-01: data operacional pela `HotelApi`.
- RN-13: trilha, eventos e problemas só com ids, datas, valores e situações; o motivo da troca é um código (`RoomAssignmentReason`), nunca texto livre.
- Ordem das travas: reserva → inventário → conta → UH(s); com duas UHs, em ordem crescente de id.
- Códigos de permissão sem hífen; um código publicado não muda. Contrato só por `./gradlew updateOpenApi`, só acréscimos; oasdiff sem `ERR` contra o HEAD e `ffae917`; `README.md` (seção "API") atualizado. Docs, mensagens e commits em pt-BR; KDoc na densidade dos vizinhos.

## Review Focus

1. **Troca para uma UH que outra recepção acabou de ocupar** (check-in ou outra troca simultâneos): o destino é ocupado por `markOccupiedIfReady` com a linha travada, e o perdedor recebe `409 ROOM_NOT_READY` com tudo desfeito (a alocação nova e a vaga da origem) — teste na Task 2.
2. **`If-Match` conferido fora da trava:** uma alteração da reserva entre a leitura e a troca precisa dar `412`, não sobrescrever — a versão esperada é conferida pelo `reservations` com a reserva travada (Task 1).
3. **Recálculo que mexe em noite passada:** troca com `RECALCULATE` depois de uma noite já passada mantém o preço dela (RN-14) e a diferença devolvida soma só as noites da data operacional em diante — teste na Task 2.
4. **Early cobrado duas vezes ou sem aceite:** sem `earlyCheckInFee` nada é lançado; a repetição do check-in com a mesma `Idempotency-Key` não lança de novo; valor mudado entre a cotação e o check-in → `409` e nada gravado — testes na Task 3.
5. **Redução do hospedado recusada por restrição de venda** (estadia mínima, CTD) enquanto o check-out antecipado passaria: a redução deixa de passar pelas restrições — teste na Task 1.

---

## Decisões do plano (registrar no handoff da E05)

- **D1 — Troca só para o mesmo tipo** (como a E01): outro tipo continua `422 ROOM_TYPE_MISMATCH`. O upgrade (trocar o `roomTypeId` da reserva e mover o inventário das noites restantes) fica como pendência para o usuário decidir quando entra (fora da Definição de pronto do M5; RF-REC-04 não o exige).
- **D2 — Ocupação do hospedado fixa:** adultos e crianças continuam `409 IN_HOUSE_STAY_FIXED` (mudaria o preço das noites e a ocupação da UH; RF-REC-05 fala só de datas).
- **D3 — Redução sem restrições de venda:** a redução da saída de uma reserva `IN_HOUSE` pelo `PUT` não confere estadia mínima/máxima, CTA/CTD nem fechamento de venda (é o mesmo efeito do check-out antecipado, que nunca é recusado); a extensão continua conferindo a venda das noites novas como hoje (é uma venda).
- **D4 — Origem da troca:** a UH de origem fica Vaga + Suja por `RoomsApi.markVacant(roomId, RoomStatusSource.CHECK_OUT)` (o hóspede saiu dela; RN-09), sem valor novo no `RoomStatusSource` (enum fechado na resposta do histórico do `rooms`); a tarefa de limpeza é do M6 pelo `RoomMoved`.
- **D5 — Versão esperada sob a trava:** `ReservationsApi.moveRoom(id, move, expectedVersion: Long? = null)`; com valor, o `reservations` confere com a reserva travada e recusa com o mesmo `412 PRECONDITION_FAILED` do `PUT`.
- **D6 — Early no check-in e no walk-in:** `earlyCheckInFee` opcional nos dois pedidos (dinheiro em texto, como `lateCheckOutFee`), conferido antes de qualquer escrita e lançado depois da transição; vale para o walk-in porque ele chega na data operacional, como o check-in.
- **D7 — Permissão** `frontdesk.stays.move` (não sensível; Gerente e Recepcionista).
- **D8 — Diferença da tarifa:** a resposta da troca traz `tariff` (`KEEP` ou `RECALCULATE`), `remainingBefore`, `remainingAfter` e `difference` = soma do `amount` das noites com data ≥ data operacional depois − antes (zero no `KEEP`).

---

### Task 1: `reservations` — versão esperada na troca e redução do hospedado sem restrições

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/reservations/ReservationsApi.kt` (`moveRoom`, KDoc)
- Modify: `src/main/kotlin/com/zeny/hotel/reservations/application/StayTransitions.kt` (implementação da troca)
- Modify: `src/main/kotlin/com/zeny/hotel/reservations/application/ReservationLifecycle.kt` e/ou `reservations/domain/StayTransitions.kt` / `domain/ReservationLifecycle.kt` (`StayRepricing.relevantRefusals` ou o ponto que decide as recusas da alteração do hospedado)
- Test: `src/test/kotlin/com/zeny/hotel/reservations/StayTransitionsApiTest.kt` (acrescentar) e `StayTransitionsDomainTest.kt` se a regra for pura

**Interfaces:**
- Produces: `fun ReservationsApi.moveRoom(id: ReservationId, move: RoomMove, expectedVersion: Long? = null): ReservationSummary` — com `expectedVersion` diferente da versão da reserva **travada**, a exceção de concorrência do `shared` que vira `412 PRECONDITION_FAILED` (a mesma que o `PUT /reservations/{id}` usa; procure em `shared/Concurrency.kt`). Os chamadores atuais não mudam.
- Produces: a alteração de um hospedado que **só reduz** a saída (mesma chegada, saída nova antes da atual, nada mais de estadia mudando) não devolve recusas de venda (`relevantRefusals` vazio para esse caso).

- [ ] **Step 1: Testes que falham:**
  - `moving an in-house guest with a stale expected version is refused under the lock`: hospedado (`support.forceInHouse`), `ReservationsApi.moveRoom(id, move, expectedVersion = version - 1)` dentro de uma transação → a exceção de `412`; nada mudou (alocação, `RoomMoved` não publicado); com a versão certa → troca feita.
  - `an in-house stay is shortened even against a minimum stay restriction`: hospedado com saída em +4; restrição de estadia mínima 4 nas datas (apoio de `RestrictionsTestSupport`); `PUT` reduzindo a saída para +2 → `200`, noites a partir de +2 devolvidas ao inventário (`sold`), noites que ficam com o preço congelado. A mesma restrição numa extensão continua conferida como antes (se já houver teste, não duplique).
- [ ] **Step 2:** rodar e ver falhar (`./gradlew test --tests 'com.zeny.hotel.reservations.StayTransitions*'`).
- [ ] **Step 3:** implementar (a conferência da versão logo depois de travar a reserva no caso de uso da troca; a regra da redução no ponto em que as recusas de venda da alteração são filtradas, com KDoc citando a decisão D3).
- [ ] **Step 4:** rodar e ver passar, mais `./gradlew test --tests 'com.zeny.hotel.reservations.*'`.
- [ ] **Step 5: Commit** `git commit -m "Confere a versão da reserva na troca de UH e libera a redução do hospedado das restrições de venda (M5 E05)"`.

---

### Task 2: Troca de UH no `frontdesk`

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/application/RoomMoveService.kt` (`RoomMoveCommand`, `RoomMoveOutcome`, `FrontdeskRoomMoveService`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/web/RoomMoveDtos.kt` (`RoomMoveRequest`, `RoomMoveResponse`)
- Modify: `frontdesk/web/StaysController.kt` (`POST /{id}/room-moves`, tag), `frontdesk/FrontdeskPermissions.kt` (`STAYS_MOVE`), `frontdesk/application/CheckInService.kt` (`FrontdeskAudit.ROOM_MOVE = "frontdesk.stay.room-move"`), `frontdesk/FrontdeskModule.kt` (KDoc), `frontdesk/domain/CheckOutRules.kt` ou um `RoomMoveRules` em `frontdesk/domain/` para a diferença da tarifa (pura, testável)
- Modify: `api/openapi.yaml` (só por `updateOpenApi`), `README.md`, `src/test/kotlin/com/zeny/hotel/iam/AuthorizationTest.kt` (expectativa do modelo do Gerente, se listar permissões)
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskRoomMoveApiTest.kt` (novo, mesmo contexto do `FrontdeskCheckOutApiTest`, com `FrontdeskTestSupport`) e casos puros em `CheckOutDomainTest` ou um `RoomMoveDomainTest`

**Interfaces:**
- Consumes: Task 1 (`moveRoom(..., expectedVersion)`); `RoomsApi.room/isReadyForCheckIn/status/markVacant/markOccupiedIfReady`; `CheckInSteps.reservation/roomNumber`; `StayRules` (situação `IN_HOUSE`, ação `MOVE_ROOM`, como `requireCheckOut`); `RoomNotReadyException`.
- Produces:
  - `data class RoomMoveCommand(val roomId: RoomId, val reason: RoomAssignmentReason, val tariff: RoomMoveTariff, val expectedVersion: Long)`
  - `data class RoomMoveOutcome(val reservation: ReservationSummary, val fromRoomNumber: String, val fromRoom: RoomStatus, val toRoomNumber: String, val toRoom: RoomStatus, val tariff: RoomMoveTariff, val remainingBefore: Money, val remainingAfter: Money, val difference: Money)`
  - `FrontdeskRoomMoveService.move(id: ReservationId, command: RoomMoveCommand): RoomMoveOutcome` — `@Audited(FrontdeskAudit.ROOM_MOVE)`, `@Transactional`.
  - `object RoomMoveTariffs { fun remaining(nights: List<NightlyRateSummary>, from: LocalDate): Money }` (soma do `amount` das noites com data ≥ `from`).
  - `FrontdeskPermissions.STAYS_MOVE = PermissionCode("frontdesk.stays.move")`, "Trocar a UH de um hóspede hospedado", `ALL`, Gerente e Recepcionista.
  - HTTP `POST /api/v1/reservations/{id}/room-moves`, `operationId = "moveReservationRoom"`, `@Idempotent`, `If-Match` obrigatório, corpo `{roomId, reason, tariff}` (`reason` e `tariff` como os `enum` da `ReservationsApi`; `tariff` padrão `KEEP`), `200` com o `ETag` novo e `RoomMoveResponse(reservation, fromRoom, toRoom, tariff, remainingBefore, remainingAfter, difference)` (dinheiro em texto; `fromRoom`/`toRoom` como `StayRoomResponse`).

**Ordem do caso de uso** (documentar no KDoc):
1. `authorization.require(STAYS_MOVE)`; reserva (`404`); só `IN_HOUSE` (`409 RESERVATION_TRANSITION_NOT_ALLOWED`, ação `MOVE_ROOM`); a UH nova existente (`400 UNKNOWN_ROOM`, campo `roomId`) e pronta sem trava (`RoomsApi.isReadyForCheckIn`, `409 ROOM_NOT_READY` com a situação); a origem = a UH alocada lida.
2. `moved = reservations.moveRoom(id, RoomMove(roomId, reason, tariff), expectedVersion)` (trava a reserva e confere a versão; as recusas da E01 passam: mesma UH `422 ROOM_MOVE_SAME_ROOM`, outro tipo `422 ROOM_TYPE_MISMATCH`, OOO `422 ROOM_OUT_OF_ORDER`, ocupada por outra reserva `409 ROOM_ALREADY_ASSIGNED`, motivo `INITIAL` `400 ROOM_MOVE_INVALID`).
3. As duas UHs em ordem crescente de id: a origem `markVacant(origem, CHECK_OUT)`; o destino `markOccupiedIfReady(destino, guestCount atual da origem, CHECK_IN)` (não pronto com a linha travada → `RoomNotReadyException`, tudo desfeito). O número de hóspedes vem do `RoomsApi.status(origem).guestCount` lido no passo 1.
4. A diferença (D8) sobre `reservation.nightlyRates` (antes) e `moved.nightlyRates` (depois) a partir da data operacional.
5. Trilha `frontdesk.stay` com `fromRoomId`, `toRoomId`, `reason`, `tariff`, `difference`, `businessDate`.

- [ ] **Step 1: Testes que falham** (`FrontdeskRoomMoveApiTest`; hospedado por check-in real da E02):
  - `a move to a ready room of the same type leaves the origin vacant and dirty, the destination occupied and publishes RoomMoved`: `200`; `fromRoom` `VACANT`/`DIRTY`, `toRoom` `OCCUPIED` com o mesmo número de hóspedes; a alocação antiga encerrada com o motivo `ROOM_MOVED` e a nova ativa com o motivo pedido (`support.allocationRow`); `RoomMoved` publicado (`Scenario`/listener de apoio); trilha `frontdesk.stay.room-move`; `ETag` = versão nova; `tariff` `KEEP` com `difference` `0.00` e as noites iguais.
  - `a recalculated move reprices only the remaining nights and returns the difference`: preço do tipo alterado depois da reserva (apoio de preços do `rates`), hospedado com uma noite já passada (`support.forceInHouse` com chegada no passado); `RECALCULATE` → a noite passada com o preço antigo (RN-14), as restantes com o novo, `difference` = soma nova − soma antiga das restantes.
  - `a move to a room that is not ready, of another type or the same room is refused with nothing changed`: suja/OOO → `409 ROOM_NOT_READY` (situação no problema); outro tipo → `422 ROOM_TYPE_MISMATCH` (D1); a mesma → `422 ROOM_MOVE_SAME_ROOM`; em todos, alocação, status das duas UHs e versão da reserva inalterados.
  - `two concurrent moves or a move and a check-in into the same room never both pass` (Review Focus 1): o mesmo padrão do teste B1 do check-in (`CheckInRendezvousProbe`/barreira, ou duas threads com `CyclicBarrier`): um vence, o outro `409 ROOM_NOT_READY` ou `409 ROOM_ALREADY_ASSIGNED`, sem resto do perdedor.
  - `the move needs If-Match, an Idempotency-Key and its permission`: sem `If-Match` → `428`; versão velha → `412`; sem chave → `400 IDEMPOTENCY_KEY_REQUIRED`; mesma chave → mesma resposta sem segunda troca; sem `frontdesk.stays.move` → `403`; reserva confirmada → `409 RESERVATION_TRANSITION_NOT_ALLOWED`.
  - Pura: `the remaining amount sums only the nights from the business date` (`RoomMoveTariffs.remaining`).
- [ ] **Step 2:** rodar e ver falhar.
- [ ] **Step 3:** implementar o caso de uso, a permissão, o DTO e o endpoint (o `@Operation` com as recusas na ordem acima e a nota de que outro tipo ainda não é aceito, D1).
- [ ] **Step 4:** rodar e ver passar, mais `./gradlew test --tests 'com.zeny.hotel.frontdesk.*'`.
- [ ] **Step 5:** `./gradlew updateOpenApi` (só acréscimos), `README.md`, `./gradlew lint`.
- [ ] **Step 6: Commit** `git commit -m "Implementa a troca de UH do hospedado no frontdesk (M5 E05)"`.

---

### Task 3: Early check-in no check-in e no walk-in

**Files:**
- Modify: `frontdesk/domain/CheckOutRules.kt` (renomear `LateCheckOutRules` para `EarlyLateFeeRules` com `acceptedFee(offer, quotedFee)`, usada pelo late e pelo early; ajuste o uso no `CheckOutService.kt` e nos testes)
- Modify: `frontdesk/application/CheckInService.kt` (`CheckInCommand.earlyCheckInFee: Money? = null`; conferência no `FrontdeskStayService.checkIn` antes das escritas; lançamento em `CheckInSteps.complete` depois da transição; `CheckInOutcome.earlyCheckIn: PostingSummary?`), `frontdesk/application/WalkInService.kt` (o mesmo campo, conferido antes da `createBooking`, com a chegada = data operacional)
- Modify: `frontdesk/web/StayDtos.kt` (`CheckInRequest.earlyCheckInFee`, `CheckInResponse.earlyCheckIn`), `frontdesk/web/WalkInDtos.kt` (`WalkInRequest.earlyCheckInFee`, `WalkInResponse.earlyCheckIn`), as descrições dos `@Operation` do check-in e do walk-in, `api/openapi.yaml` (por `updateOpenApi`), `README.md`
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskEarlyCheckInApiTest.kt` (novo, com `FrontdeskTestSupport`)

**Interfaces:**
- Consumes: `EarlyLateQuotes.early(policy, arrival, zone)`; `BillingApi.post`, `transactionCode(ROOM_CHARGE)`.
- Produces: `CheckInCommand(roomId, guests, guestCount, preAuthorization, earlyCheckInFee: Money? = null)`; `CheckInOutcome(..., earlyCheckIn: PostingSummary? = null)`; resposta `earlyCheckIn` com o mesmo formato de `CheckOutPostingResponse` (reaproveite o tipo).
- Ordem no check-in: as conferências atuais → **`EarlyLateFeeRules.acceptedFee(EarlyLateQuotes.early(policy, reservation.arrival, zone), command.earlyCheckInFee)`** (antes de qualquer escrita) → `complete` (alocação, transição, caução, **early** `billing.post(folioId, NewPosting(roomCharge, fee, description = "Early check-in", source = MANUAL))`, ocupação). O early vem depois da caução (as duas travam a conta; a reserva já foi travada pela transição).

- [ ] **Step 1: Testes que falham:**
  - `an early check-in is charged only as quoted and still current`: política com early 60.00 (`putPolicy`, devolvida no fim); cotação; check-in com `earlyCheckInFee 60.00` → `earlyCheckIn` com 60.00, `ROOM_CHARGE`, `MANUAL`, descrição `Early check-in`, na conta da reserva; sem o campo → nenhum lançamento; política mudada para 70.00 entre a cotação e o check-in → `409 EARLY_LATE_FEE_CHANGED` com `currentFee` e a reserva ainda `CONFIRMED`, UH vaga, sem caução nem lançamento; política sem early e campo enviado → `422 EARLY_LATE_NOT_OFFERED`, nada gravado.
  - `a repeated check-in with the same key does not charge the early twice` (Review Focus 4).
  - `a walk-in charges the early check-in in the same transaction`: walk-in com `earlyCheckInFee` vigente → `earlyCheckIn` na resposta e um lançamento; com valor velho → `409` e nenhuma reserva, conta, unidade ou lançamento (contadores como no `FrontdeskWalkInApiTest`).
  - `undoing a check-in keeps the early posting`: desfazer depois de um check-in com early → o lançamento continua (a descrição do desfazer já diz para estornar pelo `billing`).
  - `a walk-in with a merged guest id never leaves a partial stay` (pendência da E03): mescle um hóspede (apoio de `GuestsTestSupport`) e use o id antigo no walk-in → ou `2xx` com o sobrevivente como hóspede da estadia e titular, ou recusa `4xx` sem reserva, conta nem unidade; o teste fixa o comportamento observado e o relatório diz qual foi.
- [ ] **Step 2:** rodar e ver falhar.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** rodar e ver passar, mais `./gradlew test --tests 'com.zeny.hotel.frontdesk.*'` (os da E02, E03 e E04 continuam verdes).
- [ ] **Step 5:** `./gradlew updateOpenApi` (só acréscimos), `README.md`, `./gradlew lint`.
- [ ] **Step 6: Commit** `git commit -m "Cobra o early check-in no check-in e no walk-in (M5 E05)"`.

---

### Task 4: Extensão e redução do hospedado de ponta a ponta

**Files:**
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskInHouseChangeApiTest.kt` (novo; só testes, pelo `PUT /api/v1/reservations/{id}` da E01 com a UH ocupada por check-in real)

- [ ] **Step 1: Testes** (devem passar sem código novo; se algum falhar, é defeito: corrija no módulo dono e diga no relatório):
  - `extending an in-house stay with the room free takes the inventory and keeps the room occupied`: `200`, saída nova, `sold` das noites novas +1, UH continua `OCCUPIED` com o mesmo número de hóspedes, alocação estendida.
  - `extending without availability is refused with nothing changed`: esgote o tipo nas noites novas (outras reservas do mesmo tipo) → `409 ROOM_TYPE_UNAVAILABLE`; saída, inventário e alocação inalterados.
  - `extending into nights where the room is assigned to another reservation is refused`: outra reserva alocada na mesma UH depois da saída → `409 ROOM_ALREADY_ASSIGNED`; nada mudou.
  - `shortening an in-house stay returns the inventory and keeps the past nights`: redução → `sold` das noites retiradas −1, noites que ficam com o preço congelado, UH continua ocupada.
  - `changing the occupancy of an in-house stay is refused` (D2): `adults` diferente → `409 IN_HOUSE_STAY_FIXED` com `fields`.
- [ ] **Step 2:** rodar (`./gradlew test --tests 'com.zeny.hotel.frontdesk.FrontdeskInHouseChangeApiTest'`) e ver passar.
- [ ] **Step 3: Commit** `git commit -m "Cobre a extensão e a redução do hospedado de ponta a ponta (M5 E05)"`.

---

### Task 5: Verificação completa

- [ ] `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] `.github/scripts/check-api-breaking.sh` e `… ffae917` → sem `ERR`.

## Notas para o handoff (o orquestrador registra)

- Decisões D1 a D4 e D6 para a entrevista do fim do lote (upgrade para outro tipo pendente; ocupação fixa; redução sem restrições; origem `CHECK_OUT`; early também no walk-in).
- O M6 (`housekeeping`) gera a limpeza da origem pelo `RoomMoved`; a UH de origem já fica Suja aqui.

# M5 E04 — Check-out — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/reservations/{id}/check-out` no `frontdesk`, numa transação: lança as cobranças da estadia que o fechamento nunca lançará (H14), cobra o late check-out só com o preço informado antes (RN-11, H13), exige saldo zero em todas as contas da reserva (RN-06), libera as cauções em aberto (H12), encerra as contas, passa a reserva a `CHECKED_OUT` e a UH a Vaga + Suja (RN-09).

**Architecture:** o `billing` ganha três acréscimos mínimos na `BillingApi` (lançamento idempotente por `sourceReference`, a leitura das cauções em aberto de uma conta e a liberação sem permissão). O `frontdesk` ganha regras puras (cobranças pendentes, conferência do late, problema de saldo) e o caso de uso `FrontdeskCheckOutService`, que orquestra `ReservationsApi.checkOut`, `BillingApi` e `RoomsApi.markVacant(CHECK_OUT)` numa só transação de escrita. Nenhuma aresta de módulo nova (`frontdesk` → `billing`/`reservations`/`rooms`/`hotel` já existem).

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Modulith 2.1, Flyway, JdbcClient, Testcontainers (`@IntegrationTest`), Kotest matchers, MockMvc.

**Spec:** `docs/roadmaps/roadmap-m5-recepcao-fnrh.md`, seção "E04 — Check-out" (com as hipóteses H12, H13 e H14 e as notas para a E04 nos handoffs de E01, E02 e E03). Referência técnica: notas do brain `ZenyHotelManager - Hospedagem e recepção`, `ZenyHotelManager - Módulo billing (caixa, pagamentos, caução e encerramento)`, `ZenyHotelManager - Módulo billing (contas, roteamento e taxa de serviço)`, `ZenyHotelManager - Convenções de API`, `ZenyHotelManager - Schemas, migrações e views de relatório`, `ZenyHotelManager - Testes de integração`.

## Global Constraints

- Grafo de módulos (ARCHITECTURE §3.1): `frontdesk` só usa as APIs públicas (`ReservationsApi`, `BillingApi`, `RoomsApi`, `HotelApi`); nunca `fnrh` nem `iam`; nenhum módulo lê tabela de outro schema nem cria FK entre schemas. `ModularityTest` continua verde sem aresta nova.
- O check-out é tudo ou nada (ARCHITECTURE §6.1): cobranças, late, conferência do saldo, liberação das cauções, encerramento das contas, transição da reserva e status da UH na mesma transação; qualquer recusa desfaz tudo.
- Lançamentos financeiros seguem RN-03 (imutáveis, só pela `BillingApi.post`); `@Idempotent` no `POST …/check-out`.
- RN-01: data operacional por `HotelApi.currentBusinessDate()`; instantes pelo `Clock`; horário de late no fuso do hotel.
- RN-13 e privacidade: chave de idempotência do lançamento, trilha, eventos e problemas só com ids, datas, valores e situações; nenhum dado de hóspede.
- Migração nova com timestamp posterior a `V2026_10_22_1546` (a última aplicada), na pasta `src/main/resources/db/migration/billing/`, sem `GRANT` novo (coluna em tabela já concedida).
- Códigos de permissão sem hífen, regex `[a-z][a-z0-9]*(\.[a-z][a-zA-Z0-9]*)+`; um código publicado não muda de nome nem de sentido.
- Contrato regenerado por `./gradlew updateOpenApi` (nunca editar `api/openapi.yaml` à mão); `.github/scripts/check-api-breaking.sh` sem `ERR` (contra o HEAD e contra `ffae917`); `README.md` (seção "API") atualizado. Documentação, mensagens ao usuário e commits em pt-BR; código, API e banco em inglês.
- Comentários e KDoc em pt-BR, na densidade dos arquivos vizinhos (o projeto documenta cada regra e código de erro no KDoc).

## Review Focus

1. **Check-out em que o `409 FOLIO_BALANCE_NOT_ZERO` esconde as cobranças pendentes:** o hóspede paga o saldo mostrado e o check-out seguinte recusa de novo porque a diária do day use ou o late entraram depois. Esperado: o problema traz o saldo de cada conta **depois** das cobranças que o check-out lançaria (calculado dentro da transação, antes do rollback) — teste na Task 4.
2. **Cobrança dupla da noite da chegada:** um check-out repetido com outra `Idempotency-Key` depois de um primeiro que falhou por saldo, ou uma `BillingApi.post` posterior com a mesma chave (o fechamento do M6), não lança de novo — testes nas Tasks 1 e 4.
3. **Caução vencida ou de um check-in desfeito sobrando ativa:** todas as cauções não encerradas das contas da reserva (inclusive `EXPIRED` e a segunda de um check-in refeito, B4) terminam `RELEASED` — teste na Task 4.
4. **Conta roteada da reserva com saldo:** o saldo vem sempre da `BillingApi.balanceOf` (nunca recalculado no `frontdesk`), e uma segunda conta da reserva com saldo recusa o check-out — teste na Task 4.
5. **Late cotado com a política alterada entre a cotação e o check-out:** `409 EARLY_LATE_FEE_CHANGED` sem nada lançado; sem `lateCheckOutFee` no pedido, nenhuma cobrança de late — testes nas Tasks 3 e 4.

---

## Decisões do plano (registrar no handoff da E04)

- **D1 — Ordem das travas: reserva → inventário → conta → UH.** O caso de uso confere a situação sem trava (`409` antes de tudo) e chama `ReservationsApi.checkOut` **primeiro** (trava a reserva, devolve o inventário do antecipado e encerra a alocação), depois as cobranças, o late, o saldo, as cauções e o encerramento (travam as contas) e por fim a UH. É a mesma ordem do check-in (reserva → conta → UH) e do cancelamento (reserva → conta); a ordem de negócio do roadmap (cobranças → … → `checkOut` → UH) é preservada no resultado porque tudo está numa transação. As cobranças da H14 e o late são calculados sobre a reserva lida **antes** do `checkOut` (a chegada e a noite congelada da chegada).
- **D2 — Quais cobranças a H14 lança:** só quando a data operacional do check-out é a **data da chegada** (day use, ou pernoite que sai no dia em que chegou). A noite `date == arrival` de `nightlyRates` gera: a hospedagem `roomAmount − includedPackagesAmount` no código `ROOM_CHARGE`; e **cada pacote** da noite (`packages`, inclusos e adicionais, `ON_ARRIVAL` ou `DAILY`) no `transactionCodeId` dele, com `quantity` × `unitPrice` e a descrição = o nome do pacote. Total lançado = `amount` da noite. Valores zero não são lançados. Origem `END_OF_DAY`. Separar os inclusos segue a RF-TAR-06/RF-FIS-04 (receitas separadas); o M6 deve lançar igual (vai para "Decisões para o usuário").
- **D3 — Chaves de idempotência** (texto sem dado pessoal): `reservations.reservation:<reservationId>:<yyyy-MM-dd>:room` e `reservations.reservation:<reservationId>:<yyyy-MM-dd>:package:<packageId>`. O late não leva chave (só é lançado dentro do check-out, que é único por reserva e protegido pelo `@Idempotent`).
- **D4 — Late check-out:** o pedido traz `lateCheckOutFee` (o valor da cotação) ou nada. Com valor: a política vigente sem oferta `422 EARLY_LATE_NOT_OFFERED`; valor diferente do `fee` vigente `409 EARLY_LATE_FEE_CHANGED` (com `currentFee` no problema); igual, lança `fee` no `ROOM_CHARGE` com a descrição `Late check-out`, origem `MANUAL`. Sem valor, nada é cobrado.
- **D5 — Saldo não zero:** `409 FOLIO_BALANCE_NOT_ZERO` (o código do `billing`, reexportado) com `folios` (cada conta da reserva: `folioId`, `number`, `status`, `balance`) e `total`, calculado depois das cobranças pendentes e do late; tudo desfeito.
- **D6 — Cauções:** liberadas todas as não encerradas (`ACTIVE`, `PARTIALLY_CAPTURED` e `EXPIRED`) de todas as contas da reserva, na ordem das contas (`balanceOf`) e, dentro da conta, por id.
- **D7 — Permissão** `frontdesk.stays.checkout` (não sensível; Gerente e Recepcionista no modelo).

---

### Task 1: Lançamento idempotente por chave de origem na `BillingApi`

**Files:**
- Create: `src/main/resources/db/migration/billing/V2026_10_22_2330__add_posting_source_reference.sql`
- Modify: `src/main/kotlin/com/zeny/hotel/billing/BillingApi.kt` (`NewPosting`, `PostingSummary`, KDoc de `post`)
- Modify: `src/main/kotlin/com/zeny/hotel/billing/domain/Posting.kt` (campo `sourceReference` e validação)
- Modify: `src/main/kotlin/com/zeny/hotel/billing/application/BillingOperations.kt` (`post`)
- Modify: `src/main/kotlin/com/zeny/hotel/billing/application/BillingApiService.kt` (`post`)
- Modify: `src/main/kotlin/com/zeny/hotel/billing/application/BillingPorts.kt` (porta `PostingLedger.bySourceReference`)
- Modify: `src/main/kotlin/com/zeny/hotel/billing/infrastructure/JdbcPostingLedger.kt` (insert, leitura, mapeamento)
- Test: `src/test/kotlin/com/zeny/hotel/billing/PostingSourceReferenceTest.kt` (novo, `@IntegrationTest`, chamando a `BillingApi` direto)

**Interfaces:**
- Produces: `NewPosting(..., sourceReference: String? = null)` (último parâmetro, com default: nenhum chamador existente muda); `PostingSummary(..., sourceReference: String? = null)`; comportamento: `BillingApi.post(folioId, posting)` com `sourceReference` já gravado devolve o lançamento existente (com a taxa de serviço dele em `serviceCharge`) sem gravar nem auditar nada.

- [ ] **Step 1: Migração.** `ALTER TABLE billing.posting ADD COLUMN source_reference VARCHAR(200);` + `CONSTRAINT`/`CHECK` `posting_source_reference_ck CHECK (source_reference IS NULL OR source_reference ~ '^[A-Za-z0-9._:-]{1,200}$')` + `CREATE UNIQUE INDEX posting_source_reference_uk ON billing.posting (source_reference) WHERE source_reference IS NOT NULL;`. Comentário no topo explicando a finalidade (H14 do M5: o check-out e o fechamento do M6 usam a mesma chave; só ids, datas e o tipo da cobrança, nunca dado pessoal). O trigger de imutabilidade continua valendo.

- [ ] **Step 2: Testes que falham** em `PostingSourceReferenceTest` (abra uma conta pela `BillingApi.openFolio(NewFolio(RESERVATION, UUID))`, código `ROOM_CHARGE`):
  - `a posting with a source reference is recorded once, and the repetition returns the same posting`: dois `post` com `sourceReference = "reservations.reservation:$id:2026-11-02:room"` → mesmo `id`, `sourceReference` igual na resposta, uma única linha em `billing.posting` com essa chave (`jdbc`), saldo da conta = o valor uma vez, uma única entrada `billing.posting.create` na trilha para o lançamento.
  - `the repetition returns the existing posting even with the folio closed`: lança com chave, zera o saldo estornando o lançamento pelo endpoint HTTP de estorno com o administrador (ajudantes do `BillingTestSupport`) e encerra a conta pela `BillingApi.closeFolio`; depois do encerramento, `post` com a mesma chave devolve o existente, sem `409 FOLIO_CLOSED`; um `post` com **outra** chave na conta encerrada continua `409 FOLIO_CLOSED`.
  - `a service charge item repeated by key returns the item with its service charge`: código `FOOD_BEVERAGE` (com taxa) → a repetição traz `serviceCharge` com o mesmo id.
  - `an invalid source reference is refused`: `"com espaço"` e uma de 201 caracteres → `400 POSTING_INVALID` com o campo `sourceReference`.
  - `a posting without a source reference is never deduplicated`: dois `post` iguais sem chave → dois lançamentos.

- [ ] **Step 3: Rodar e ver falhar.** `./gradlew test --tests 'com.zeny.hotel.billing.PostingSourceReferenceTest'` → FAIL (compilação: `sourceReference` inexistente).

- [ ] **Step 4: Implementar.** `Posting.regular(...)` recebe `sourceReference: String?` e valida (`400 POSTING_INVALID`, campo `sourceReference`, mesma regex da migração) antes de qualquer trava. Em `BillingOperations.post`: depois de `store.foliosForUpdate(...)` e **antes** de `requested.requireOpen()`, se `sourceReference != null` e `ledger.bySourceReference(it)` existe, devolva `PostedItem(existing, ledger.serviceChargeOf(existing.id))` sem `record`. Isso põe a conferência e a gravação sob a trava da conta pedida (dois check-outs simultâneos da mesma reserva se enfileiram; o índice único é a rede de segurança). `auditState()` do lançamento inclui `sourceReference`. `BillingApiService.post` repassa o campo; `summary()` o devolve. A API HTTP de lançamentos **não** muda (nem pedido nem resposta).

- [ ] **Step 5: Rodar e ver passar** o teste novo e `./gradlew test --tests 'com.zeny.hotel.billing.*'` (nada quebrado no `billing`).

- [ ] **Step 6: Commit** `git commit -m "Acrescenta o lançamento idempotente por chave de origem à BillingApi (M5 E04, H14)"` com a migração, o código e o teste.

---

### Task 2: Cauções em aberto e liberação sem permissão na `BillingApi`

**Files:**
- Modify: `src/main/kotlin/com/zeny/hotel/billing/BillingApi.kt` (dois métodos novos com KDoc)
- Modify: `src/main/kotlin/com/zeny/hotel/billing/application/BillingApiService.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/billing/application/CardPreAuthorizationService.kt` (`releaseAuthorized`, `unsettledOf`)
- Test: `src/test/kotlin/com/zeny/hotel/billing/CardPreAuthorizationsApiTest.kt` (acrescentar) ou novo `CardPreAuthorizationBillingApiTest.kt` se o existente não tiver a `BillingApi` injetada

**Interfaces:**
- Produces:
  - `fun BillingApi.unsettledCardPreAuthorizations(folioId: FolioId): List<CardPreAuthorizationSummary>` — as não encerradas (nem `CAPTURED` nem `RELEASED`) da conta, por id, com a situação calculada agora (`EXPIRED` incluída); conta inexistente → lista vazia; sem trava, sem permissão (pelo `CardPreAuthorizationStore.unsettledOf`).
  - `fun BillingApi.releaseCardPreAuthorization(folioId: FolioId, id: CardPreAuthorizationId): CardPreAuthorizationSummary` — as regras de `CardPreAuthorizationService.release` **menos a permissão**, auditada `billing.card-pre-authorization.release` (mesma `@Audited` do `release`), na transação de quem chama: trava a conta e depois a caução; vale com a conta encerrada e depois da validade; encerrada `409 PRE_AUTHORIZATION_SETTLED`; de outra conta ou inexistente `404 PRE_AUTHORIZATION_NOT_FOUND`; precisa de usuário autenticado.

- [ ] **Step 1: Testes que falham:**
  - `the billing api lists the unsettled holds of a folio, expired included`: registre três cauções pela `BillingApi.registerCardPreAuthorization` (uma ativa, uma capturada em parte pelo endpoint HTTP de captura com turno, uma que vence — mova o `MutableClock` e devolva-o no fim), libere uma quarta → a lista traz as três, por id, com `ACTIVE`, `PARTIALLY_CAPTURED` e `EXPIRED`.
  - `the billing api releases a hold without the permission, even expired or on a closed folio`: num usuário **sem** `billing.payments.receive` (`asUser`), `releaseCardPreAuthorization` → `RELEASED`, a trilha `billing.card-pre-authorization.release` com o usuário; a vencida também termina `RELEASED`; repetir → `409 PRE_AUTHORIZATION_SETTLED`; id de outra conta → `404 PRE_AUTHORIZATION_NOT_FOUND`.

- [ ] **Step 2: Rodar e ver falhar** (`./gradlew test --tests '<a classe>'`).

- [ ] **Step 3: Implementar** `CardPreAuthorizationService.releaseAuthorized(folioId, id)` (o corpo de `release` extraído para um `releaseOn` privado, como `registerOn`) e `unsettledOf(folioId): List<CardPreAuthorizationSummary>`; os dois métodos da `BillingApi` delegam a eles. Atualize o KDoc da interface (lista dos métodos desde o M5 E04).

- [ ] **Step 4: Rodar e ver passar**, mais `./gradlew test --tests 'com.zeny.hotel.billing.*'`.

- [ ] **Step 5: Commit** `git commit -m "Acrescenta à BillingApi a leitura das cauções em aberto e a liberação sem permissão (M5 E04, H12)"`.

---

### Task 3: Regras puras do check-out no `frontdesk`

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/domain/CheckOutRules.kt`
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskErrorCodes.kt` (códigos novos e reexportados)
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/CheckOutDomainTest.kt` (novo, sem Spring)

**Interfaces:**
- Consumes: `ReservationSummary`, `NightlyRateSummary`, `PackageCharge` (`reservations`/`rates`), `EarlyLateOffer` (`EarlyLateQuotes.late`), `FolioSummary`/`OwnerBalance` (`billing`).
- Produces:
  - `enum class StayChargeKind { ROOM, PACKAGE }`
  - `data class StayCharge(val kind: StayChargeKind, val date: LocalDate, val transactionCodeId: TransactionCodeId?, val packageId: PackageId?, val quantity: BigDecimal, val unitPrice: Money, val description: String?, val sourceReference: String)` — `transactionCodeId` nulo em `ROOM` (o caso de uso usa `BillingApi.transactionCode(ROOM_CHARGE)`); `amount` calculado = `unitPrice × quantity` pela regra de `Money`.
  - `object StayCharges { fun pending(reservation: ReservationSummary, businessDate: LocalDate): List<StayCharge> }` — regra da decisão D2/D3; vazia quando `businessDate != reservation.arrival` ou sem a noite `date == arrival`.
  - `object LateCheckOutRules { fun acceptedFee(offer: EarlyLateOffer, quotedFee: Money?): Money? }` — nulo sem `quotedFee`; `422 EARLY_LATE_NOT_OFFERED` sem oferta; `EarlyLateFeeChangedException(currentFee)` (`409 EARLY_LATE_FEE_CHANGED`, `currentFee` no problema) se difere; senão o `fee`.
  - `class StayNotSettledException(balance: OwnerBalance) : ConflictException(FrontdeskErrorCodes.FOLIO_BALANCE_NOT_ZERO, …)` com `problemProperties` = `folios` (lista de mapas `folioId`, `number`, `status`, `balance` como texto `toPlainString()`) e `total`.
  - `StayRules.requireCheckOut(reservation)` (no `StayRules` existente de `CheckInRules.kt`): fora de `IN_HOUSE` → `409 RESERVATION_TRANSITION_NOT_ALLOWED` no mesmo formato do `requireCheckIn` (ação `CHECK_OUT`).
  - Em `FrontdeskErrorCodes`: `EARLY_LATE_NOT_OFFERED` (422), `EARLY_LATE_FEE_CHANGED` (409), reexportado `FOLIO_BALANCE_NOT_ZERO`, com KDoc.

- [ ] **Step 1: Testes de domínio que falham** (`CheckOutDomainTest`, montando `ReservationSummary` com um construtor de apoio do próprio teste):
  - `a day use checked out on its date charges the room without the included packages and every package of the night`: noite `roomAmount 300.00`, `includedPackagesAmount 40.00`, pacotes (café incluso `ON_ARRIVAL` 2 × 20.00; estacionamento adicional `DAILY` 1 × 25.00) → três cobranças: `ROOM` 260.00 com chave `reservations.reservation:<id>:<date>:room`, `PACKAGE` café quantidade 2, unitário 20.00, descrição = nome, chave `…:package:<packageId>`; estacionamento 25.00; soma = `amount` da noite (325.00).
  - `an overnight stay checked out on the arrival date charges the arrival night only`: 3 noites, `businessDate == arrival` → só as cobranças da noite `arrival`.
  - `a stay checked out after the arrival date charges nothing`: `businessDate == arrival + 1` → vazia.
  - `zero amounts are not charged`: plano cortesia (`roomAmount == includedPackagesAmount`) e pacote 0.00 → sem a cobrança de hospedagem nem a do pacote zero.
  - `the source reference never carries personal data`: todas as chaves casam `^reservations\.reservation:[0-9a-f-]{36}:\d{4}-\d{2}-\d{2}:(room|package:[0-9a-f-]{36})$`.
  - `the late fee is accepted only as quoted by the current policy`: sem `quotedFee` → nulo; oferta 80.00 e cotado 80.00 → 80.00; cotado 70.00 → `EarlyLateFeeChangedException` com `currentFee` "80.00"; sem oferta e cotado → `BusinessRuleException` `EARLY_LATE_NOT_OFFERED`.
  - `a balance not settled lists every folio with its balance`: duas contas (100.00 e 0.00) → `FOLIO_BALANCE_NOT_ZERO`, `folios` com as duas, `total` "100.00".
  - `only an in-house reservation checks out`: `CONFIRMED` e `CHECKED_OUT` → `RESERVATION_TRANSITION_NOT_ALLOWED`.

- [ ] **Step 2: Rodar e ver falhar:** `./gradlew test --tests 'com.zeny.hotel.frontdesk.CheckOutDomainTest'`.

- [ ] **Step 3: Implementar** os tipos e regras acima em `CheckOutRules.kt` (e o `requireCheckOut` no `StayRules`).

- [ ] **Step 4: Rodar e ver passar.**

- [ ] **Step 5: Commit** `git commit -m "Acrescenta as regras do check-out ao frontdesk (M5 E04)"`.

---

### Task 4: Caso de uso e endpoint do check-out

**Files:**
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/application/CheckOutService.kt` (`CheckOutCommand`, `CheckOutOutcome`, `FrontdeskCheckOutService`)
- Create: `src/main/kotlin/com/zeny/hotel/frontdesk/web/CheckOutDtos.kt` (`CheckOutRequest`, `CheckOutResponse` e as partes)
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/web/StaysController.kt` (`POST /{id}/check-out`, `FRONTDESK_TAG_DESCRIPTION`)
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskPermissions.kt` (`STAYS_CHECK_OUT`)
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/application/CheckInService.kt` (`FrontdeskAudit.CHECK_OUT = "frontdesk.stay.check-out"`)
- Modify: `src/main/kotlin/com/zeny/hotel/frontdesk/FrontdeskModule.kt` (KDoc: desde a E04, o check-out)
- Modify: `api/openapi.yaml` (só por `./gradlew updateOpenApi`), `README.md` (seção "API")
- Test: `src/test/kotlin/com/zeny/hotel/frontdesk/FrontdeskCheckOutApiTest.kt` (novo, mesmo contexto do `FrontdeskCheckInApiTest`: `@IntegrationTest`, `@EnableScenarios`, `@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)`; reaproveite os ajudantes do check-in copiando o mínimo necessário ou extraindo para um apoio compartilhado em `src/test/kotlin/com/zeny/hotel/frontdesk/` se a cópia passar de ~60 linhas)

**Interfaces:**
- Consumes: Task 1 (`NewPosting.sourceReference`), Task 2 (`unsettledCardPreAuthorizations`, `releaseCardPreAuthorization`), Task 3 (`StayCharges.pending`, `LateCheckOutRules.acceptedFee`, `StayNotSettledException`, `StayRules.requireCheckOut`); `ReservationsApi.checkOut(id)`, `RoomsApi.markVacant(roomId, RoomStatusSource.CHECK_OUT)`, `BillingApi.balanceOf/closeFolio/post/transactionCode(ROOM_CHARGE)`, `HotelApi.policy()/timezone()/currentBusinessDate()`, `EarlyLateQuotes.late`, `CheckInSteps.reservation/roomNumber`.
- Produces:
  - `data class CheckOutCommand(val lateCheckOutFee: Money?)`
  - `data class CheckOutOutcome(val reservation: ReservationSummary, val roomNumber: String, val room: RoomStatus, val stayCharges: List<PostingSummary>, val lateCheckOut: PostingSummary?, val releasedPreAuthorizations: List<CardPreAuthorizationSummary>, val closedFolios: List<FolioSummary>)`
  - `FrontdeskCheckOutService.checkOut(id: ReservationId, command: CheckOutCommand): CheckOutOutcome` — `@Audited(FrontdeskAudit.CHECK_OUT)`, `@Transactional`.
  - `FrontdeskPermissions.STAYS_CHECK_OUT = PermissionCode("frontdesk.stays.checkout")`, "Fazer o check-out de uma reserva hospedada", escopo `ALL`, Gerente e Recepcionista.
  - HTTP `POST /api/v1/reservations/{id}/check-out`, `operationId = "checkOutReservation"`, `@Idempotent`, corpo opcional `CheckOutRequest(lateCheckOutFee: BigDecimal?)` (`@RequestBody(required = false)`; positivo, até 2 casas, senão `400` de validação), `200` com `ETag` da reserva e `CheckOutResponse(reservation: StayReservationResponse, room: StayRoomResponse, stayCharges: List<CheckOutPostingResponse>, lateCheckOut: CheckOutPostingResponse?, releasedPreAuthorizations: List<StayPreAuthorizationResponse>, closedFolios: List<CheckOutFolioResponse>)`; `CheckOutPostingResponse(postingId, folioId, transactionCodeId, amount, businessDate)`; `CheckOutFolioResponse(folioId, number, status)`.

**Ordem do caso de uso** (decisão D1; documentar no KDoc da classe como o do check-in):
1. `authorization.require(STAYS_CHECK_OUT)`; `steps.reservation(id)` (`404`); `StayRules.requireCheckOut` (`409`); data operacional; `pending = StayCharges.pending(reservation, businessDate)`; `lateFee = LateCheckOutRules.acceptedFee(EarlyLateQuotes.late(policy, reservation.departure, zone), command.lateCheckOutFee)` (as recusas de late antes de qualquer escrita).
2. `checkedOut = reservations.checkOut(id)` (trava a reserva; o antecipado devolve o inventário; encerra a alocação; publica `GuestCheckedOut`).
3. Cada `StayCharge` → `billing.post(reservation.folioId, NewPosting(code, unitPrice, quantity, description, END_OF_DAY, sourceReference = charge.sourceReference))`.
4. Late aceito → `billing.post(reservation.folioId, NewPosting(roomChargeCode, fee, description = "Late check-out", source = MANUAL))`.
5. `balance = billing.balanceOf(FolioOwner(RESERVATION, id.value))`; `!balance.settled` → `throw StayNotSettledException(balance)`.
6. Para cada conta de `balance.folios`: `unsettledCardPreAuthorizations` → `releaseCardPreAuthorization` cada uma.
7. `balance.openFolios.forEach { billing.closeFolio(it.id) }`.
8. `room = rooms.markVacant(roomId, CHECK_OUT)` (a UH da alocação lida no passo 1; `checkNotNull`).
9. `audit.record(FrontdeskAudit.STAY, id.value, before, after)` com `status`, `roomId`, `postingIds`, `latePostingId`, `releasedPreAuthorizationIds`, `closedFolioIds`, `businessDate` — sem texto livre nem dado de hóspede.

- [ ] **Step 1: Testes de integração que falham** em `FrontdeskCheckOutApiTest` (as diárias de um pernoite não são lançadas por ninguém até o M6: os testes lançam pela API de lançamentos ou criam saldo e pagam pelo `BillingTestSupport`/endpoint de pagamentos com turno aberto):
  - `an in-house stay with a zero balance checks out, releasing holds, closing folios and leaving the room vacant and dirty`: hospedado por check-in real (E02) com caução; lança uma diária e paga; check-out → `200`, `reservation.status` `CHECKED_OUT`, `room.occupancy` `VACANT` e `housekeepingStatus` `DIRTY`, `releasedPreAuthorizations` com a caução `RELEASED`, `closedFolios` com a conta `CLOSED`, alocação encerrada (`support.allocationRow`), `GuestCheckedOut` publicado (`Scenario`/listener de apoio), trilha `frontdesk.stay.check-out`, `ETag` = versão da reserva; um `POST /api/v1/folios/{folio}/postings` depois → `409 FOLIO_CLOSED`.
  - `a non zero balance refuses the check-out with each folio balance, including the pending stay charges, and changes nothing`: (a) devedor 100.00 e (b) credor −50.00 (pagamento a mais), cada um → `409 FOLIO_BALANCE_NOT_ZERO` com `folios[*].balance` e `total`; reserva ainda `IN_HOUSE`, UH Ocupada, caução `ACTIVE`, conta aberta, nenhum lançamento novo; (c) day use não pago: o `balance` do problema já inclui a diária pendente (Review Focus 1); pagando exatamente esse valor, o check-out seguinte passa.
  - `a reservation that is not in house is refused`: confirmada e já `CHECKED_OUT` (`support.forceCheckedOut`) → `409 RESERVATION_TRANSITION_NOT_ALLOWED`.
  - `a day use or a same day check-out charges the arrival night and its packages once`: plano com pacote `ON_ARRIVAL` e um `DAILY` (apoio de `RatesTestSupport`/`ReservationsTestSupport.plan`); day use checado no dia: `stayCharges` com a hospedagem sem inclusos e os pacotes, `source` `END_OF_DAY` e `source_reference` na tabela; um pernoite que sai no dia da chegada (check-out antecipado) também; depois do check-out, `BillingApi.post` com a mesma chave devolve o lançamento existente (nenhuma linha nova).
  - `a check-out after the arrival date charges nothing for the stay`: `support.forceInHouse` com chegada passada (ou avançando a data operacional e devolvendo-a) → `stayCharges` vazio.
  - `a repeated check-out after a refused one does not charge the arrival night twice`: primeira tentativa com saldo (recusa, rollback), pagamento, segunda com **outra** `Idempotency-Key` → um lançamento por chave (Review Focus 2).
  - `the late check-out is charged only as quoted`: política com late 80.00 (`putPolicy` como no check-in); cotação → check-out com `lateCheckOutFee 80.00` e saldo pago antes no valor (diária + 80.00) → lançamento `Late check-out` 80.00 `MANUAL`; sem o campo → nada de late; política alterada para 90.00 entre a cotação e o check-out → `409 EARLY_LATE_FEE_CHANGED` com `currentFee` e nada alterado; política sem late e campo enviado → `422 EARLY_LATE_NOT_OFFERED`. Devolva a política original no fim.
  - `every open hold of the reservation is released, expired and from an undone check-in included (B4)`: check-in com caução, desfazer, novo check-in com outra caução; uma terceira vencida (relógio movido e devolvido); uma capturada em parte com a sobra; check-out → todas `RELEASED` (Review Focus 3).
  - `a routed second folio of the reservation with a balance refuses the check-out`: abra uma segunda conta `RESERVATION` com o mesmo dono (pela API de contas) com saldo → `409` listando as duas (Review Focus 4).
  - `the check-out is idempotent and needs its permission`: sem `Idempotency-Key` → `400 IDEMPOTENCY_KEY_REQUIRED`; mesma chave → mesma resposta, nenhum segundo lançamento; usuário sem `frontdesk.stays.checkout` → `403`.
  - `a forced failure at the end leaves nothing`: arme o `RoomStatusFailureProbe` (como no check-in) para falhar no `markVacant` → nada persistido (reserva `IN_HOUSE`, alocação ativa, contas abertas, caução `ACTIVE`, sem lançamento, sem publicação de `GuestCheckedOut`).

- [ ] **Step 2: Rodar e ver falhar:** `./gradlew test --tests 'com.zeny.hotel.frontdesk.FrontdeskCheckOutApiTest'`.

- [ ] **Step 3: Implementar** o caso de uso (ordem acima), a permissão, a auditoria, o DTO e o endpoint. A `description` do `@Operation` lista, na ordem, as recusas: `404 RESERVATION_NOT_FOUND`; `409 RESERVATION_TRANSITION_NOT_ALLOWED` (ação `CHECK_OUT`); `422 EARLY_LATE_NOT_OFFERED`; `409 EARLY_LATE_FEE_CHANGED`; `409 FOLIO_BALANCE_NOT_ZERO` com `folios` e `total` (já com as cobranças da noite da chegada e o late); e diz que, até o M10, o check-out só aceita saldo zero por pagamento (sem contas a receber), que a captura da caução é feita antes pelo `billing` e que a nota fiscal é do M7.

- [ ] **Step 4: Rodar e ver passar** o teste novo e `./gradlew test --tests 'com.zeny.hotel.frontdesk.*'` (os 25+ da E02/E03 continuam verdes).

- [ ] **Step 5: Contrato e documentação.** `./gradlew updateOpenApi`; conferir no diff de `api/openapi.yaml` só acréscimos (`checkOutReservation`, os schemas novos, a descrição da tag); `README.md` seção "API" com o check-out; `./gradlew lint` (corrija com `./gradlew ktlintFormat` se preciso).

- [ ] **Step 6: Commit** `git commit -m "Implementa o check-out no frontdesk (M5 E04)"`.

---

### Task 5: Verificação completa da etapa

- [ ] **Step 1:** `./gradlew build` → `BUILD SUCCESSFUL` (testes, `ModularityTest`, ArchUnit, detekt, ktlint, `checkOpenApi`, `koverVerify`).
- [ ] **Step 2:** `.github/scripts/check-api-breaking.sh` e `.github/scripts/check-api-breaking.sh ffae917` → "Nenhuma mudança incompatível (nível ERR) em /api/v1".
- [ ] **Step 3:** mutações rápidas (aplicar, rodar o teste da etapa, reverter conferindo com `git diff --exit-code`): sem a conferência do `settled` → falha o teste do saldo; sem a liberação das cauções → `closeFolio` recusa (`FOLIO_HAS_ACTIVE_PRE_AUTHORIZATION`) e falha o teste de sucesso; sem a `sourceReference` nas cobranças → falha o teste da repetição.
- [ ] **Step 4:** commit de ajustes, se houver.

## Notas para o handoff (o orquestrador registra)

- **Nota obrigatória para o M6:** o fechamento não lança de novo o que o check-out já lançou — usa a **mesma chave** (`reservations.reservation:<id>:<data>:room` / `…:package:<packageId>`) na `BillingApi.post`, e separa os pacotes inclusos da hospedagem como a D2.
- Até o M10, o check-out só aceita saldo zero por pagamento (sem transferência para contas a receber).
- Ordem das travas do check-out: reserva → inventário → conta(s) → UH (a troca de UH da E05 segue a mesma).

package com.zeny.hotel

import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.rates.RatePricesTestSupport
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.InventoryTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.infrastructure.ProvisionalExpiryJob
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.InventoryListenersQuiescence
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cenário ponta a ponta do marco M4 (gate, E13), pela API HTTP e com os modelos de role da SPEC §3.1 que o sistema
 * entrega (Gerente e Recepcionista): o Gerente cadastra os tipos de UH e as UHs (o inventário reflete pelo listener do
 * cadastro), as políticas, o plano base com preços e um derivado −10% com estadia mínima na chegada; a Recepcionista
 * cota (o derivado fica de fora pela restrição na estadia curta e entra na longa), cria uma reserva agrupadora de duas
 * UHs com um desconto acima do teto dela, aprovado pelo Gerente; a tarifa publicada muda depois (com a aprovação do
 * Gerente para quem só tem a permissão comum) e o preço congelado das reservas não muda; aloca as UHs (o banco recusa a
 * sobreposição mesmo por SQL direto); um OOO aberto pelo Gerente reduz a disponibilidade (listener de
 * `OutOfOrderChanged`), marca a reserva alocada e notifica a Recepcionista, que realoca; a estadia de uma reserva é
 * estendida (a noite nova pela tarifa nova, a UH acompanha); o Gerente cria um bloqueio e a Recepcionista faz o
 * pick-up sem consumir duas vezes; o cancelamento com isenção pede aprovação e sem ela lança a multa da política
 * copiada na conta, e a reativação pede aprovação; uma pré-reserva vence e o job a cancela devolvendo o inventário; N
 * reservas simultâneas disputam a última UH de um tipo; o titular é mesclado e o listener de `GuestsMerged` reponta as
 * reservas, a agrupadora, o bloqueio e o pick-up; a anonimização é recusada pela estadia ativa e a exportação traz a
 * seção `reservations`. No fim, as views só com `SELECT`, o histórico *append-only* e nada do titular na trilha, no
 * outbox nem nas notificações.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // os beans que o cenário usa; o cenário inteiro do marco
class MilestoneM4EndToEndTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val expiryJob: ProvisionalExpiryJob,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired flyway: Flyway,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing: BillingTestSupport = support.billing
    private val inventory: InventoryTestSupport = support.inventory
    private val rooms: RoomsTestSupport = inventory.rooms
    private val rates: RatesTestSupport = support.rates
    private val prices: RatePricesTestSupport = support.prices
    private val guests: GuestsTestSupport = support.guests
    private val owner = JdbcClient.create(flyway.configuration.dataSource)

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // o cenário inteiro do marco, passo a passo
    fun `rates, quote, approved discount, frozen price, assignment, OOO, change, block, penalty, expiry, merge`() {
        val today = hotelApi.currentBusinessDate()
        val receptionist = Operator(userWithTemplate("RECEPTIONIST"))
        val managerUser = userWithTemplate("MANAGER")
        val managerPin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(managerUser, managerPin)
        val manager = Operator(managerUser)

        // --- Cadastro: tipos de UH e UHs pelo Gerente; o inventário reflete pelo listener do cadastro -------------
        val type = api(POST, "/api/v1/room-types", manager, rooms.roomTypeBody()).expect(201).id()
        val roomIds = (1..3).map { api(POST, "/api/v1/rooms", manager, rooms.roomBody(type)).expect(201).id() }
        val single = api(POST, "/api/v1/room-types", manager, rooms.roomTypeBody()).expect(201).id()
        api(POST, "/api/v1/rooms", manager, rooms.roomBody(single)).expect(201)
        inventory.awaitRow(type, today) { it.total == 3 }
        inventory.awaitRow(single, today) { it.total == 1 }

        // --- Tarifas: políticas, plano base com preços, derivado −10% com estadia mínima na chegada --------------
        val arrival = today.plusDays(10)
        val policies = policies(manager, guarantee = "NONE")
        val planBody = rates.planBody(listOf(type, single), policies.first, policies.second)
        api(POST, "/api/v1/rate-plans", receptionist, planBody).problem(403, "PERMISSION_DENIED")
        val base = api(POST, "/api/v1/rate-plans", manager, planBody).expect(201).id()
        prices
            .update(
                base,
                prices.selection(listOf(type, single), today, today.plusDays(PRICE_DAYS)),
                prices.prices("300.00", "400.00", "120.00"),
                auth = manager.auth,
            ).expect(200)
        val derived =
            api(POST, "/api/v1/rate-plans", manager, rates.planBody(listOf(type), policies.first, policies.second))
                .expect(201)
                .id()
        prices
            .derive(derived, prices.derivation("PERCENT", base, percent = "-10.0000"), auth = manager.auth)
            .expect(200)
        api(
            POST,
            "/api/v1/rate-plans/$derived/restrictions/bulk-update",
            manager,
            mapOf(
                "roomTypeIds" to emptyList<UUID>(),
                "from" to arrival,
                "to" to arrival,
                "weekdays" to emptyList<String>(),
                "set" to mapOf("minStay" to 3),
                "clear" to emptyList<String>(),
            ),
        ).expect(200)

        // --- Cotação: a estadia curta só no base; a longa no base e no derivado, noite a noite ----------------
        val short = quote(receptionist, arrival, arrival.plusDays(2), type, listOf(base, derived))
        withClue("2 noites: o base a 400 por noite; o derivado recusado pela estadia mínima da chegada") {
            short["offers"].values().map { it["ratePlanId"].asString() } shouldContainExactly listOf(base.toString())
            val offer = short["offers"][0]
            offer["total"].asString() shouldBe "800.00"
            offer["nights"].values().map { it["total"].asString() } shouldContainExactly listOf("400.00", "400.00")
            val refused = short["unavailable"].values().single { it["ratePlanId"].asString() == derived.toString() }
            refused["reasons"].values().map { it["code"].asString() } shouldContainExactly listOf("MIN_STAY_NOT_MET")
        }
        val long = quote(receptionist, arrival, arrival.plusDays(3), type, listOf(base, derived))
        withClue("3 noites: o derivado a 360 por noite, a soma das noites é o total") {
            val offers = long["offers"].values().associateBy { it["ratePlanId"].asString() }
            offers.keys shouldContainExactlyInAnyOrder listOf(base.toString(), derived.toString())
            offers.getValue(base.toString())["total"].asString() shouldBe "1200.00"
            val derivedOffer = offers.getValue(derived.toString())
            derivedOffer["total"].asString() shouldBe "1080.00"
            derivedOffer["nights"].values().map { Money.of(it["total"].asString()) }.reduce(Money::plus) shouldBe
                Money.of("1080.00")
        }

        // --- Reserva agrupadora com desconto acima do teto da Recepcionista, aprovado pelo Gerente -------------
        val holderName = guests.uniqueName("Benedita")
        val holder = guest(receptionist, holderName)
        val requests = "Berço ao lado da janela ${guests.suffix(6)}"
        val bookingBody =
            support.booking(
                holder,
                support.reservation(
                    type,
                    base,
                    arrival,
                    arrival.plusDays(2),
                    specialRequests = requests,
                    nightlyRates = listOf(arrival to "340.00"),
                ),
                support.reservation(type, base, arrival, arrival.plusDays(2)),
            )
        val soldBefore = inventory.row(type, arrival)!!.sold
        val required =
            with(billing) { support.create(bookingBody, receptionist.auth).problem(403, "APPROVAL_REQUIRED") }
        required["permission"].asString() shouldBe "rates.discounts.apply"
        required["limit"]["key"].asString() shouldBe "maxPercent"
        BigDecimal(required["limit"]["amount"].asString()).compareTo(BigDecimal("15")) shouldBe 0
        inventory.row(type, arrival)!!.sold shouldBe soldBefore
        val discountApproval = approve(receptionist, required, managerUser, managerPin)
        val booking =
            with(billing) {
                support
                    .create(
                        bookingBody,
                        receptionist.auth,
                        approvalToken = discountApproval["approvalToken"].asString(),
                    ).expect(201)
            }
        val bookingId = booking.id()
        val r1 = booking["reservations"][0]
        val r2 = booking["reservations"][1]
        val r1Id = r1.id()
        val r2Id = r2.id()
        withClue("o desconto aprovado fica na reserva, com o aprovador, e na trilha com a solicitante") {
            r1["approvalId"].asString() shouldBe discountApproval["approvalId"].asString()
            r1["approvedBy"].asString() shouldBe managerUser.id.toString()
            r1["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
                listOf("340.00", "400.00")
            r1["nightlyRates"][0]["discountPercent"].asString() shouldBe "15.0000"
            val trail = support.auditEntries(r1Id).single { it["action"] == "reservations.booking.create" }
            trail["userId"] shouldBe receptionist.user.id.value
            trail["approvalId"].toString() shouldBe discountApproval["approvalId"].asString()
            approvalParties(discountApproval) shouldBe (receptionist.user.id.value to managerUser.id.value)
        }
        inventory.row(type, arrival)!!.sold shouldBe soldBefore + 2
        inventory.row(type, arrival.plusDays(1))!!.sold shouldBe 2

        // --- A tarifa publicada muda (aprovação para quem só tem a permissão comum); o preço congelado fica ----
        val pricesManage = RatesTestSupport.definition(RatesPermissions.PRICES_MANAGE)
        val pricing = Operator(iam.createUserWith(TestGrant(pricesManage, ALL)))
        val newPrices = prices.prices("350.00", "450.00", "120.00")
        val newSelection = prices.selection(listOf(type), arrival, arrival.plusDays(2))
        val priceRequired =
            with(iam) {
                prices.update(base, newSelection, newPrices, auth = pricing.auth).problem(403, "APPROVAL_REQUIRED")
            }
        priceRequired["permission"].asString() shouldBe "rates.prices.override"
        priceRequired["resourceRef"].asString() shouldBe "rates.rate-plan:$base"
        val priceApproval = approve(pricing, priceRequired, managerUser, managerPin)
        prices
            .update(
                base,
                newSelection,
                newPrices,
                auth = pricing.auth,
                approvalToken = priceApproval["approvalToken"].asString(),
            ).expect(200)
        approvalParties(priceApproval) shouldBe (pricing.user.id.value to managerUser.id.value)
        withClue("RN-14: as noites das reservas continuam com o preço congelado na criação") {
            nightAmounts(r1Id) shouldContainExactly listOf("340.00", "400.00")
            nightAmounts(r2Id) shouldContainExactly listOf("400.00", "400.00")
        }

        // --- Alocação; o banco recusa a sobreposição mesmo por SQL direto -----------------------------------
        val suggestions =
            api(GET, "/api/v1/reservations/$r1Id/room-suggestions", receptionist).ok()["items"].values().map {
                it["roomId"].asString()
            }
        suggestions shouldContainExactlyInAnyOrder roomIds.map { it.toString() }
        assign(receptionist, r1Id, roomIds[0]).ok()
        assign(receptionist, r2Id, roomIds[1]).ok()
        assign(receptionist, r2Id, roomIds[0]).problem(409, "ROOM_ALREADY_ASSIGNED")
        val overlap =
            shouldThrow<DataAccessException> {
                insertAssignment(r2Id, roomIds[0], arrival.plusDays(1), arrival.plusDays(2))
            }
        overlap.psql()?.sqlState shouldBe "23P01"
        overlap.psql()?.serverErrorMessage?.constraint shouldBe "room_assignment_overlap_ex"

        // --- OOO pelo Gerente: disponibilidade reduzida pelo listener, reserva marcada, Recepcionista avisada --
        val oooDate = arrival.plusDays(1)
        val ooo =
            api(
                POST,
                "/api/v1/rooms/${roomIds[0]}/out-of-order",
                manager,
                mapOf(
                    "kind" to "OUT_OF_ORDER",
                    "startsOn" to oooDate,
                    "endsOn" to oooDate,
                    "reason" to "Infiltração no teto",
                    "responsible" to "Manutenção",
                    "returnStatus" to "DIRTY",
                ),
            ).expect(201).id()
        inventory.awaitRow(type, oooDate) { it.outOfOrder == 1 }
        InventoryListenersQuiescence.await(jdbc)
        completedListeners("com.zeny.hotel.rooms.OutOfOrderChanged", "\"outOfOrderId\":\"$ooo\"") shouldContainExactly
            listOf("InventoryListeners", "ReallocationListener")
        withClue("a disponibilidade do tipo cai na data do OOO") {
            val calendar =
                api(
                    GET,
                    "/api/v1/availability?from=$arrival&to=${arrival.plusDays(1)}&roomTypeIds=$type",
                    receptionist,
                ).ok()
            val days = calendar["roomTypes"][0]["days"].values().associateBy { it["date"].asString() }
            days.getValue(arrival.toString())["available"].asInt() shouldBe 1
            days.getValue(oooDate.toString())["available"].asInt() shouldBe 0
            days.getValue(oooDate.toString())["outOfOrder"].asInt() shouldBe 1
        }
        support.reservation(r1Id)["reservation"]["roomAssignment"]["reallocation"]["outOfOrderId"].asString() shouldBe
            ooo.toString()
        support.reservation(r2Id)["reservation"]["roomAssignment"]["reallocation"].isNull shouldBe true
        rooms
            .listAll("/api/v1/room-assignments/reallocation-required", receptionist.auth)
            .map { it["reservationId"].asString() } shouldContain r1Id.toString()
        val notice =
            rooms.listAll("/api/v1/notifications", receptionist.auth).single {
                it["type"].asString() == "reservations.reallocation-required" &&
                    it["body"].asString().contains(r1["code"].asString())
            }
        notice["body"].asString() shouldNotContain holderName
        assign(receptionist, r1Id, roomIds[2]).ok()["reservation"]["roomAssignment"]["reallocation"].isNull shouldBe
            true

        // --- Alteração da estadia: a noite nova pela tarifa nova, as mantidas congeladas, a UH acompanha -------
        val current = support.reservation(r2Id)["reservation"]
        val changed =
            support
                .change(
                    r2Id,
                    support.changeBody(current, departure = arrival.plusDays(3)),
                    auth = receptionist.auth,
                ).let { with(iam) { it.ok() } }["reservation"]
        changed["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
            listOf("400.00", "400.00", "450.00")
        changed["roomAssignment"]["roomId"].asString() shouldBe roomIds[1].toString()
        changed["roomAssignment"]["endsOn"].asString() shouldBe arrival.plusDays(3).toString()
        inventory.row(type, arrival.plusDays(2))!!.sold shouldBe 1
        support.historyKinds(r2Id) shouldContainExactly listOf("CHANGE")

        // --- Bloqueio pelo Gerente e pick-up pela Recepcionista, sem consumir duas vezes ---------------------
        val blockArrival = today.plusDays(BLOCK_OFFSET)
        val block =
            api(
                POST,
                "/api/v1/room-blocks",
                manager,
                support.blockBody(
                    base,
                    blockArrival.minusDays(5),
                    support.allocation(type, blockArrival, blockArrival.plusDays(1), 2, "250.00"),
                    holderGuest = holder,
                ),
            ).expect(201)
        val blockId = block.id()
        listOf(blockArrival, blockArrival.plusDays(1)).forEach { date ->
            inventory.row(type, date)!!.let {
                it.blocked shouldBe 2
                it.sold shouldBe 0
            }
        }
        val pickUp =
            with(billing) {
                support
                    .pickUp(
                        blockId,
                        support.blockReservation(type, blockArrival, blockArrival.plusDays(2)),
                        auth = receptionist.auth,
                    ).expect(201)
            }
        val pickUpBooking = pickUp.id()
        pickUp["holderGuestId"].asString() shouldBe holder.toString()
        pickUp["reservations"][0]["nightlyRates"].values().map { it["roomAmount"].asString() } shouldContainExactly
            listOf("250.00", "250.00")
        listOf(blockArrival, blockArrival.plusDays(1)).forEach { date ->
            withClue(date) {
                val row = inventory.row(type, date)!!
                row.blocked shouldBe 1
                row.sold shouldBe 1
                row.blocked + row.sold shouldBe 2
            }
        }

        // --- Cancelamento: a isenção pede aprovação; sem ela, a multa da política copiada vai à conta ---------
        with(billing) {
            val waive =
                support.cancel(r1Id, waivePenalty = true, auth = receptionist.auth).problem(403, "APPROVAL_REQUIRED")
            waive["permission"].asString() shouldBe "reservations.penalties.waive"
        }
        support.status(r1Id) shouldBe "CONFIRMED"
        val cancelled =
            with(billing) { support.cancel(r1Id, auth = receptionist.auth).expect(200)["reservation"] }
        cancelled["status"].asString() shouldBe "CANCELLED"
        cancelled["cancellation"]["penalty"]["amount"].asString() shouldBe "340.00"
        cancelled["roomAssignment"].isNull shouldBe true
        val folio = UUID.fromString(r1["folioId"].asString())
        support.postings(folio).map { Triple(it["key"], it["amount"], it["businessDate"]) } shouldContainExactly
            listOf(Triple("CANCELLATION_FEE", "340.00", today))
        api(GET, "/api/v1/folios/$folio", receptionist).ok()["balance"].asString() shouldBe "340.00"
        inventory.row(type, arrival)!!.sold shouldBe 1

        // --- Reativação: ação sensível, aprovada pelo Gerente; nenhuma segunda multa -------------------------
        val reactivation =
            with(billing) { support.reactivate(r1Id, auth = receptionist.auth).problem(403, "APPROVAL_REQUIRED") }
        reactivation["permission"].asString() shouldBe "reservations.reservations.reactivate"
        val reactivationApproval = approve(receptionist, reactivation, managerUser, managerPin)
        with(billing) {
            support
                .reactivate(
                    r1Id,
                    auth = receptionist.auth,
                    approvalToken = reactivationApproval["approvalToken"].asString(),
                ).expect(200)["reservation"]["status"]
                .asString() shouldBe "CONFIRMED"
        }
        approvalParties(reactivationApproval) shouldBe (receptionist.user.id.value to managerUser.id.value)
        inventory.row(type, arrival)!!.sold shouldBe 2
        support.postings(folio).size shouldBe 1

        // --- Pré-reserva vencida: o job a cancela e devolve o inventário -----------------------------------
        val cardPolicies = policies(manager, guarantee = "CARD")
        val cardPlan =
            api(
                POST,
                "/api/v1/rate-plans",
                manager,
                rates.planBody(listOf(type), cardPolicies.first, cardPolicies.second),
            ).expect(201).id()
        val provisionalArrival = today.plusDays(PROVISIONAL_OFFSET)
        prices
            .update(
                cardPlan,
                prices.selection(listOf(type), provisionalArrival, provisionalArrival.plusDays(1)),
                prices.prices("300.00", "400.00", "120.00"),
                auth = manager.auth,
            ).expect(200)
        val start = clock.instant()
        try {
            val provisional =
                with(billing) {
                    support
                        .create(
                            support.booking(
                                guest(receptionist, guests.uniqueName("Provisória")),
                                support.reservation(type, cardPlan, provisionalArrival, provisionalArrival.plusDays(2)),
                            ),
                            receptionist.auth,
                        ).expect(201)["reservations"][0]
                }
            provisional["status"].asString() shouldBe "PROVISIONAL"
            inventory.row(type, provisionalArrival)!!.sold shouldBe 1
            clock.advance(hotelApi.operationalSettings().preReservationExpiry)
            expiryJob.expireProvisionalReservations()
            val expired = support.reservation(provisional.id())["reservation"]
            expired["status"].asString() shouldBe "CANCELLED"
            expired["cancellation"]["reason"].asString() shouldBe "EXPIRED"
            expired["cancellation"]["penalty"]["amount"].asString() shouldBe "0.00"
            inventory.row(type, provisionalArrival)!!.sold shouldBe 0
            inventory.row(type, provisionalArrival.plusDays(1))!!.sold shouldBe 0
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
        }

        // --- Concorrência: N reservas simultâneas pela última UH do tipo ------------------------------------
        val raceArrival = today.plusDays(CONCURRENT_OFFSET)
        val lastRoom =
            support.booking(
                guest(receptionist, guests.uniqueName("Corrida")),
                support.reservation(single, base, raceArrival, raceArrival.plusDays(2)),
            )
        val race = race { with(billing) { support.create(lastRoom, receptionist.auth) } }
        withClue(race.results) {
            race.results.count { it.first == 201 } shouldBe 1
            race.results.filter { it.first != 201 }.forEach {
                it.first shouldBe 409
                it.second.contains("\"code\":\"ROOM_TYPE_UNAVAILABLE\"") shouldBe true
            }
        }
        withClue("as requisições rodaram ao mesmo tempo (pico de requisições em andamento)") {
            race.peak shouldBeGreaterThanOrEqual 2
        }
        println("MILESTONE_M4_CONCURRENCY requests=$CONCURRENT peak=${race.peak}")
        support.bookingCount(single) shouldBe 1
        inventory.row(single, raceArrival)!!.sold shouldBe 1

        // --- Mesclagem do titular: o listener de GuestsMerged reponta reservas, agrupadoras e bloqueio --------
        val survivor = guest(receptionist, guests.uniqueName("Benedita"))
        api(
            POST,
            "/api/v1/guests/$survivor/merge",
            receptionist,
            mapOf("mergedGuestId" to holder, "takeFromMerged" to emptyList<String>()),
            ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
        ).problem(403, "PERMISSION_DENIED")
        api(
            POST,
            "/api/v1/guests/$survivor/merge",
            manager,
            mapOf("mergedGuestId" to holder, "takeFromMerged" to emptyList<String>()),
            ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
        ).ok()
        await().atMost(Duration.ofSeconds(TIMEOUT)).until {
            api(GET, "/api/v1/bookings/$bookingId", receptionist).ok()["holderGuestId"].asString() ==
                survivor.toString()
        }
        await().atMost(Duration.ofSeconds(TIMEOUT)).until {
            completedListeners("com.zeny.hotel.guests.GuestsMerged", "\"mergedId\":\"$holder\"") ==
                listOf("GuestsMergedListener")
        }
        withClue("o titular, os hóspedes, o bloqueio e a agrupadora do pick-up apontam para o sobrevivente") {
            support.reservation(r1Id)["reservation"]["guests"].values().map {
                it["guestId"].asString() to it["primary"].asBoolean()
            } shouldContainExactly listOf(survivor.toString() to true)
            support.reservation(r2Id)["holderGuestId"].asString() shouldBe survivor.toString()
            support.block(blockId)["holderGuestId"].asString() shouldBe survivor.toString()
            api(GET, "/api/v1/bookings/$pickUpBooking", receptionist).ok()["holderGuestId"].asString() shouldBe
                survivor.toString()
        }

        // --- Privacidade: anonimização recusada pela estadia ativa; exportação com a seção reservations -------
        api(POST, "/api/v1/guests/$survivor/anonymize", manager).problem(409, "GUEST_HAS_ACTIVE_STAY")
        val exported = api(POST, "/api/v1/guests/$survivor/export", manager).ok()
        val section = exported["sections"].values().single { it["name"].asString() == "reservations" }["data"]
        section["reservations"].values().map { it["code"].asString() } shouldContainExactlyInAnyOrder
            listOf(r1["code"].asString(), r2["code"].asString(), pickUp["reservations"][0]["code"].asString())

        // --- Views só com SELECT, histórico append-only e nada do titular na trilha, no outbox nem no aviso ----
        withClue("as views do marco existem, são legíveis e o papel da aplicação só tem SELECT nelas") {
            for (view in VIEWS) {
                privileges(view) shouldBe listOf(true, false, false, false)
            }
            jdbc
                .sql(
                    "SELECT holder_guest_id FROM reservations.report_reservations WHERE reservation_id IN (:ids)",
                ).param("ids", listOf(r1Id, r2Id))
                .query(UUID::class.java)
                .list() shouldContainExactly listOf(survivor, survivor)
            jdbc
                .sql("SELECT count(*) FROM rates.report_rate_prices WHERE rate_plan_id = :plan")
                .param("plan", base)
                .query(Long::class.java)
                .single() shouldNotBe 0L
        }
        withClue("o histórico das reservas é append-only para o papel da aplicação e para o dono") {
            val app =
                shouldThrow<DataAccessException> {
                    jdbc
                        .sql("UPDATE reservations.reservation_history SET kind = kind WHERE reservation_id = :id")
                        .param("id", r2Id)
                        .update()
                }
            app.psql()?.sqlState shouldBe INSUFFICIENT_PRIVILEGE
            shouldThrow<DataAccessException> {
                owner
                    .sql("DELETE FROM reservations.reservation_history WHERE reservation_id = :id")
                    .param("id", r2Id)
                    .update()
            }
            support.historyKinds(r2Id) shouldContainExactly listOf("CHANGE")
        }
        withClue("nenhuma entrada da trilha, publicação do outbox ou notificação cita o titular ou os pedidos") {
            for (value in listOf(holderName, requests)) {
                for (sql in PRIVACY_QUERIES) {
                    withClue(sql) { countLike(sql, value) shouldBe 0L }
                }
            }
        }
        support.reservation(r1Id)["reservation"]["specialRequests"].asString() shouldBe requests
    }

    // --- Apoio ---------------------------------------------------------------------------------------------------

    /** Quem opera numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private class Operator(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private class Race(
        val results: List<Pair<Int, String>>,
        val peak: Int,
    )

    /** Usuário novo com a role do modelo da SPEC §3.1 que o sistema cria no boot. */
    private fun userWithTemplate(template: String): TestUser {
        val role =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :template")
                .param("template", template)
                .query(UUID::class.java)
                .single()
        return iam.createUser().also { iam.assignRole(it, RoleId(role)) }
    }

    private fun api(
        method: HttpMethod,
        path: String,
        operator: Operator,
        body: Any? = null,
        ifMatch: String? = null,
    ): MockHttpServletResponse = billing.call(method, path, operator.auth, body, ifMatch = ifMatch)

    private fun MockHttpServletResponse.expect(status: Int): JsonNode = with(billing) { expect(status) }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(billing) { problem(status, code) }

    private fun MockHttpServletResponse.ok(): JsonNode = with(iam) { ok() }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    /** Uma política de cancelamento (uma noite de multa, sempre devida) e uma de garantia ([guarantee]) novas. */
    private fun policies(
        manager: Operator,
        guarantee: String,
    ): Pair<UUID, UUID> {
        val cancellation =
            api(
                POST,
                "/api/v1/cancellation-policies",
                manager,
                rates.cancellationBody(deadlineHours = ALWAYS_HOURS, penaltyNights = 1),
            ).expect(201).id()
        val guaranteePolicy =
            api(
                POST,
                "/api/v1/guarantee-policies",
                manager,
                rates.guaranteeBody(type = guarantee, depositPercent = null, dueDaysBefore = null),
            ).expect(201).id()
        return cancellation to guaranteePolicy
    }

    private fun guest(
        operator: Operator,
        name: String,
    ): UUID =
        api(
            POST,
            "/api/v1/guests",
            operator,
            guests.createBody(profile = guests.profileBody(fullName = name), documents = emptyList()),
        ).expect(201).id()

    private fun quote(
        operator: Operator,
        arrival: LocalDate,
        departure: LocalDate,
        type: UUID,
        plans: List<UUID>,
    ): JsonNode =
        api(
            GET,
            "/api/v1/quotes?arrival=$arrival&departure=$departure&adults=2&channel=DIRECT&roomTypeIds=$type" +
                "&ratePlanIds=${plans.joinToString(",")}&includeUnavailable=true",
            operator,
        ).ok()

    private fun approve(
        requester: Operator,
        required: JsonNode,
        approver: TestUser,
        pin: String,
    ): JsonNode =
        billing
            .call(
                POST,
                "/api/v1/auth/approvals",
                requester.auth,
                buildMap {
                    put("action", required["action"].asString())
                    put("permission", required["permission"].asString())
                    put("resourceRef", required["resourceRef"].asString())
                    required["limit"]?.takeUnless { it.isNull }?.let {
                        put("limit", mapOf("key" to it["key"].asString(), "amount" to it["amount"].asString()))
                    }
                    put("approverLogin", approver.login)
                    put("approverPin", pin)
                    put("reason", "Conferido pelo gerente")
                },
                idempotencyKey = null,
            ).expect(201)

    /** O solicitante e o aprovador gravados na aprovação (`iam.approval`). */
    private fun approvalParties(approval: JsonNode): Pair<UUID, UUID> =
        jdbc
            .sql("SELECT requester_id, approver_id FROM iam.approval WHERE id = :id")
            .param("id", UUID.fromString(approval["approvalId"].asString()))
            .query { rs, _ ->
                rs.getObject("requester_id", UUID::class.java) to rs.getObject("approver_id", UUID::class.java)
            }.single()

    private fun nightAmounts(reservation: UUID): List<String> =
        support.reservation(reservation)["reservation"]["nightlyRates"].values().map { it["roomAmount"].asString() }

    private fun assign(
        operator: Operator,
        reservation: UUID,
        room: UUID,
    ): MockHttpServletResponse =
        api(POST, "/api/v1/reservations/$reservation/assign-room", operator, mapOf("roomId" to room))

    /** Uma alocação gravada direto no banco pelo papel da aplicação, contornando a aplicação. */
    private fun insertAssignment(
        reservation: UUID,
        room: UUID,
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = transactions.executeWithoutResult {
        jdbc
            .sql(
                """
                INSERT INTO reservations.room_assignment (id, reservation_id, room_id, starts_on, ends_on, reason,
                    assigned_at)
                VALUES (:id, :reservation, :room, :startsOn, :endsOn, 'INITIAL', now())
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("reservation", reservation)
            .param("room", room)
            .param("startsOn", startsOn)
            .param("endsOn", endsOn)
            .update()
    }

    /** Os listeners (nome simples da classe) cujas publicações do evento [eventType] com [fragment] concluíram. */
    private fun completedListeners(
        eventType: String,
        fragment: String,
    ): List<String> =
        jdbc
            .sql(
                "SELECT listener_id FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :fragment AND completion_date IS NOT NULL ORDER BY listener_id",
            ).param("type", eventType)
            .param("fragment", "%$fragment%")
            .query(String::class.java)
            .list()
            .filterNotNull()
            .map { listener -> LISTENER_CLASS.find(listener)?.groupValues?.get(1) ?: listener }
            .sorted()

    /** Dispara [CONCURRENT] chamadas juntas e mede o pico de chamadas em andamento ao mesmo tempo. */
    private fun race(call: () -> MockHttpServletResponse): Race {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT)
        try {
            val timed =
                (1..CONCURRENT)
                    .map {
                        executor.submit(
                            Callable {
                                start.await()
                                val began = System.nanoTime()
                                val response = call()
                                val body = response.getContentAsString(Charsets.UTF_8)
                                Triple(began, System.nanoTime(), response.status to body)
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(RACE_TIMEOUT, TimeUnit.SECONDS) }
            val peak = timed.maxOf { (began, _, _) -> timed.count { (b, e, _) -> b <= began && began < e } }
            return Race(timed.map { it.third }, peak)
        } finally {
            executor.shutdownNow()
        }
    }

    /** `SELECT`, `INSERT`, `UPDATE` e `DELETE` do papel da aplicação na view. */
    private fun privileges(view: String): List<Boolean> =
        listOf("SELECT", "INSERT", "UPDATE", "DELETE").map { privilege ->
            jdbc
                .sql("SELECT has_table_privilege(current_user, :view, :privilege)")
                .param("view", view)
                .param("privilege", privilege)
                .query(Boolean::class.java)
                .single()
        }

    private fun countLike(
        sql: String,
        value: String,
    ): Long =
        jdbc
            .sql(sql)
            .param("v", "%$value%")
            .query(Long::class.java)
            .single()

    private fun DataAccessException.psql(): PSQLException? =
        generateSequence<Throwable>(this) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()

    private companion object {
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val PRICE_DAYS = 60L
        const val BLOCK_OFFSET = 30L
        const val PROVISIONAL_OFFSET = 20L
        const val CONCURRENT_OFFSET = 15L
        const val CONCURRENT = 8
        const val RACE_TIMEOUT = 60L
        const val TIMEOUT = 30L
        const val ALWAYS_HOURS = 8760
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val PRIVACY_QUERIES =
            listOf(
                "SELECT count(*) FROM audit.audit_entry WHERE before_state::text LIKE :v OR after_state::text LIKE :v",
                "SELECT count(*) FROM shared.event_publication WHERE serialized_event LIKE :v",
                "SELECT count(*) FROM notifications.notification WHERE title LIKE :v OR body LIKE :v",
                "SELECT count(*) FROM reservations.report_reservations r WHERE r::text LIKE :v",
                "SELECT count(*) FROM reservations.reservation_history h WHERE h::text LIKE :v",
            )
        val LISTENER_CLASS = Regex("\\.([A-Za-z]+Listeners?)\\.")
        val VIEWS =
            listOf(
                "reservations.report_reservations",
                "reservations.report_room_nights",
                "reservations.report_inventory",
                "reservations.report_room_assignments",
                "reservations.report_block_allocations",
                "rates.report_rate_plans",
                "rates.report_rate_prices",
            )
    }
}

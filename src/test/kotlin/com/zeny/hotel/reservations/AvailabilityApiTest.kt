package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatePricesTestSupport
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.rates.RestrictionsTestSupport
import com.zeny.hotel.reservations.application.RoomTypeInventory
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.sum
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Disponibilidade em calendário e cotação (M4 E06; RF-TAR-08 e 11): o calendário mostra os contadores do inventário
 * (as vendas, o OOO e as datas sobrevendidas), os preços efetivos do plano pedido e as restrições efetivas; a cotação
 * só oferece o tipo de UH com unidade em todas as noites e o plano vendável no canal (restrições, ocupação, preço), com
 * a soma das noites igual ao total, e diz por que as outras combinações ficaram de fora. Cada teste cria os próprios
 * tipos de UH (banco compartilhado). O mesmo contexto do `InventoryApiTest`.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList") // as portas que os cenários usam
class AvailabilityApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val inventory: RoomTypeInventory,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = InventoryTestSupport(iam, jdbc)
    private val rates = RatesTestSupport(iam)
    private val prices = RatePricesTestSupport(iam, jsonMapper)
    private val restrictions = RestrictionsTestSupport(iam)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Calendário -------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o calendário inteiro de dois tipos, com e sem plano
    fun `the calendar shows the counters with sales and OOO, the overbooked dates, the prices and the restrictions`() {
        val (type, typeRooms) = support.roomTypeWithRooms(2, today)
        val (other, otherRooms) = support.roomTypeWithRooms(1, today)
        val plan = rates.createPlanFor(listOf(type))
        val from = today.plusDays(1)
        val to = today.plusDays(5)
        setPrices(plan, listOf(type), from, to)
        restrict(plan, today.plusDays(3), mapOf("closedToArrival" to true, "minStay" to 2))

        // Uma venda nas noites +2 e +3; o OOO de uma UH em +3 e +4.
        sell(type, today.plusDays(2), today.plusDays(4))
        openOutOfOrder(typeRooms.first(), today.plusDays(3), today.plusDays(4))
        support.awaitRow(type, today.plusDays(4)) { it.outOfOrder == 1 }
        // O outro tipo vende a única UH em +4 e ela entra em OOO depois: a data fica sobrevendida.
        sell(other, today.plusDays(4), today.plusDays(5))
        openOutOfOrder(otherRooms.single(), today.plusDays(4), today.plusDays(4))
        support.awaitRow(other, today.plusDays(4)) { it.outOfOrder == 1 }

        val calendar = calendar(from, to, listOf(type, other), plan)

        calendar["ratePlan"]["id"].asString() shouldBe plan.toString()
        val typeDays = days(calendar, type)
        typeDays.keys.toList() shouldContainExactly (1L..5L).map { today.plusDays(it) }
        typeDays.mapValues { it.value["available"].asInt() }.values.toList() shouldContainExactly listOf(2, 1, 0, 1, 2)
        typeDays.mapValues { it.value["sold"].asInt() }.values.toList() shouldContainExactly listOf(0, 1, 1, 0, 0)
        typeDays.mapValues { it.value["outOfOrder"].asInt() }.values.toList() shouldContainExactly
            listOf(0, 0, 1, 1, 0)
        typeDays.values.map { it["overbooked"].asBoolean() }.distinct() shouldContainExactly listOf(false)
        roomType(calendar, type)["inRatePlan"].asBoolean() shouldBe true
        roomType(calendar, type)["overbookedDates"].values().shouldBeEmpty()

        val day1 = typeDays.getValue(today.plusDays(1))
        day1["prices"]["oneAdult"].asString() shouldBe "300.00"
        day1["prices"]["twoAdults"].asString() shouldBe "400.00"
        day1["prices"]["extraAdult"].asString() shouldBe "120.00"
        day1["restriction"]["closedToArrival"].asBoolean() shouldBe false
        day1["restriction"]["minStay"].isNull shouldBe true
        val day3 = typeDays.getValue(today.plusDays(3))
        day3["restriction"]["closedToArrival"].asBoolean() shouldBe true
        day3["restriction"]["minStay"].asInt() shouldBe 2
        day3["prices"]["oneAdult"].asString() shouldBe "300.00"

        // O tipo fora do plano: os contadores, sem preços nem restrições; a data sobrevendida aparece.
        val otherType = roomType(calendar, other)
        otherType["inRatePlan"].asBoolean() shouldBe false
        otherType["overbookedDates"].values().map { it.asString() } shouldContainExactly
            listOf(today.plusDays(4).toString())
        val otherDays = days(calendar, other)
        otherDays.getValue(today.plusDays(4))["available"].asInt() shouldBe -1
        otherDays.getValue(today.plusDays(4))["overbooked"].asBoolean() shouldBe true
        otherDays.getValue(today.plusDays(4))["prices"].isNull shouldBe true
        otherDays.getValue(today.plusDays(4))["restriction"].isNull shouldBe true

        // Sem plano: só os contadores.
        val plain = calendar(from, to, listOf(type), null)
        plain["ratePlan"].isNull shouldBe true
        roomType(plain, type)["inRatePlan"].isNull shouldBe true
        days(plain, type).getValue(today.plusDays(3)).let {
            it["available"].asInt() shouldBe 0
            it["prices"].isNull shouldBe true
            it["restriction"].isNull shouldBe true
        }
    }

    @Test
    fun `the calendar of a derived plan shows the derived prices, and dates out of the inventory do not appear`() {
        val (type, _) = support.roomTypeWithRooms(1, today)
        val base = rates.createPlanFor(listOf(type))
        val derived = rates.createPlanFor(listOf(type))
        setPrices(base, listOf(type), today, today.plusDays(2))
        with(iam) { prices.derive(derived, prices.derivation("PERCENT", base, percent = "-10.0000")).ok() }

        val calendar = calendar(today.minusDays(2), today.plusDays(2), listOf(type), derived)

        val typeDays = days(calendar, type)
        // As datas antes da data operacional não têm inventário (o tipo é novo).
        typeDays.keys.toList() shouldContainExactly (0L..2L).map { today.plusDays(it) }
        typeDays.getValue(today)["prices"]["twoAdults"].asString() shouldBe "360.00"
        typeDays.getValue(today)["prices"]["oneAdult"].asString() shouldBe "270.00"
    }

    // --- Cotação ----------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // os tipos, os planos e os motivos de cada combinação
    fun `the quote offers only room types with a unit on every night and plans sellable in the channel`() {
        val (double, doubleRooms) = support.roomTypeWithRooms(2, today)
        val (single, singleRooms) = support.roomTypeWithRooms(1, today)
        val (soldOut, _) = support.roomTypeWithRooms(1, today)
        val all = listOf(double, single, soldOut)
        val arrival = today.plusDays(20)
        val departure = arrival.plusDays(3)
        val direct = createPlan(all, channels = listOf("DIRECT", "WEBSITE"))
        val otaOnly = createPlan(listOf(double, single), channels = listOf("OTA"))
        val closedToArrival = createPlan(listOf(double), channels = listOf("DIRECT"))
        val breakfast = rates.createTransactionCode()
        val packageId =
            with(iam) {
                val body = rates.packageBody(breakfast, price = "35.00", includedInRate = false)
                UUID.fromString(call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!!["id"].asString())
            }
        val derived = createPlan(listOf(double, single), channels = listOf("DIRECT"), packages = listOf(packageId))
        listOf(direct, otaOnly, closedToArrival).forEach { setPrices(it, typesIn(it, all), arrival, departure) }
        with(iam) { prices.derive(derived, prices.derivation("PERCENT", direct, percent = "-10.0000")).ok() }
        restrict(closedToArrival, arrival, mapOf("closedToArrival" to true))

        // O tipo esgotado numa noite do meio; a única UH do single em OOO na última noite; uma das duas do double em
        // OOO em todas (sobra uma unidade).
        sell(soldOut, arrival.plusDays(1), arrival.plusDays(2))
        openOutOfOrder(singleRooms.single(), arrival.plusDays(2), arrival.plusDays(2))
        openOutOfOrder(doubleRooms.first(), arrival, departure)
        support.awaitRow(single, arrival.plusDays(2)) { it.outOfOrder == 1 }
        support.awaitRow(double, arrival) { it.outOfOrder == 1 }

        val quote = quote(arrival, departure, adults = 2, channel = "DIRECT", roomTypes = all, unavailable = true)

        quote["nights"].asInt() shouldBe 3
        val offers = quote["offers"].values()
        offers.map { it["roomTypeId"].asString() to it["ratePlanId"].asString() } shouldContainExactlyInAnyOrder
            listOf(double.toString() to direct.toString(), double.toString() to derived.toString())
        offers.forEach { offer ->
            withClue(offer) {
                offer["availableUnits"].asInt() shouldBe 1
                val nights = offer["nights"].values()
                nights.map { LocalDate.parse(it["date"].asString()) } shouldContainExactly
                    (0L..2L).map { arrival.plusDays(it) }
                nights.map { Money.of(it["total"].asString()) }.sum() shouldBe Money.of(offer["total"].asString())
                nights.map { Money.of(it["roomAmount"].asString()) }.sum() shouldBe
                    Money.of(offer["roomTotal"].asString())
            }
        }
        val directOffer = offers.single { it["ratePlanId"].asString() == direct.toString() }
        directOffer["total"].asString() shouldBe "1200.00"
        directOffer["ratePlanCode"].isString shouldBe true
        directOffer["mealPlan"].asString() shouldBe "BREAKFAST"
        // −10% sobre 400 = 360 por noite, mais o café adicional de 2 × 35 por noite.
        val derivedOffer = offers.single { it["ratePlanId"].asString() == derived.toString() }
        derivedOffer["nights"].values().map { it["roomAmount"].asString() }.distinct() shouldContainExactly
            listOf("360.00")
        derivedOffer["additionalPackagesTotal"].asString() shouldBe "210.00"
        derivedOffer["total"].asString() shouldBe "1290.00"

        val reasons =
            quote["unavailable"].values().associate { item ->
                (item["roomTypeId"].asString() to item["ratePlanId"].asString()) to
                    item["reasons"].values().map { it["code"].asString() }
            }
        reasons shouldBe
            mapOf(
                (single.toString() to direct.toString()) to listOf("ROOM_TYPE_UNAVAILABLE"),
                (soldOut.toString() to direct.toString()) to listOf("ROOM_TYPE_UNAVAILABLE"),
                (single.toString() to derived.toString()) to listOf("ROOM_TYPE_UNAVAILABLE"),
                (double.toString() to otaOnly.toString()) to listOf("SALES_CHANNEL_NOT_ALLOWED"),
                (single.toString() to otaOnly.toString()) to
                    listOf("SALES_CHANNEL_NOT_ALLOWED", "ROOM_TYPE_UNAVAILABLE"),
                (double.toString() to closedToArrival.toString()) to listOf("CLOSED_TO_ARRIVAL"),
            )
        val soldOutReason =
            quote["unavailable"]
                .values()
                .single { it["roomTypeId"].asString() == soldOut.toString() }["reasons"]
                .values()
                .single()
        soldOutReason["dates"].values().map { it.asString() } shouldContainExactly
            listOf(arrival.plusDays(1).toString())

        // No canal OTA, só o plano da OTA, e só no tipo com unidade.
        val ota = quote(arrival, departure, adults = 2, channel = "OTA", roomTypes = all)
        ota["offers"].values().map { it["roomTypeId"].asString() to it["ratePlanId"].asString() } shouldContainExactly
            listOf(double.toString() to otaOnly.toString())
        ota["unavailable"].isNull shouldBe true

        // A ocupação acima da máxima do tipo (3 adultos no RoomsTestSupport) não é oferecida.
        quote(arrival, departure, adults = 4, channel = "DIRECT", roomTypes = all)["offers"].values().shouldBeEmpty()
        // Só os planos pedidos.
        quote(arrival, departure, adults = 2, channel = "DIRECT", roomTypes = all, plans = listOf(derived))["offers"]
            .values()
            .map { it["ratePlanId"].asString() } shouldContainExactly listOf(derived.toString())
    }

    @Test
    fun `the quote takes the OOO and the sales into account as soon as the inventory reflects them`() {
        val (type, typeRooms) = support.roomTypeWithRooms(1, today)
        val plan = createPlan(listOf(type), channels = listOf("DIRECT"))
        val arrival = today.plusDays(40)
        setPrices(plan, listOf(type), arrival, arrival.plusDays(1))

        fun offered() =
            quote(arrival, arrival.plusDays(2), adults = 1, channel = "DIRECT", roomTypes = listOf(type))["offers"]
                .values()
                .map { it["total"].asString() }

        offered() shouldContainExactly listOf("600.00")
        val period = openOutOfOrder(typeRooms.single(), arrival.plusDays(1), arrival.plusDays(1))
        support.awaitRow(type, arrival.plusDays(1)) { it.outOfOrder == 1 }
        offered().shouldBeEmpty()
        roomsApi.releaseOutOfOrder(period, RoomStatusSource.MANUAL)
        support.awaitRow(type, arrival.plusDays(1)) { it.outOfOrder == 0 }
        offered() shouldContainExactly listOf("600.00")
        sell(type, arrival, arrival.plusDays(1))
        offered().shouldBeEmpty()
    }

    @Test
    fun `the quote and the calendar refuse bad input with stable codes and require the availability permission`() {
        val (type, _) = support.roomTypeWithRooms(1, today)
        val plan = createPlan(listOf(type), channels = listOf("DIRECT"))
        val arrival = today.plusDays(5)
        with(iam) {
            val admin = asAdmin()
            call(GET, quotePath(arrival, arrival, 2, "DIRECT", listOf(type)), admin).problem(400, "STAY_INVALID")
            call(GET, quotePath(arrival, arrival.plusDays(1), 2, "DIRECT", listOf(UUID.randomUUID())), admin)
                .problem(400, "UNKNOWN_ROOM_TYPE")["errors"][0]["field"]
                .asString() shouldBe "roomTypeIds"
            call(
                GET,
                quotePath(arrival, arrival.plusDays(1), 2, "DIRECT", listOf(type), listOf(UUID.randomUUID())),
                admin,
            ).problem(400, "UNKNOWN_RATE_PLAN")
            call(GET, quotePath(arrival, arrival.plusDays(1), 2, "DIRECT", listOf(type), childAges = listOf(30)), admin)
                .problem(400, "STAY_INVALID")["errors"][0]["field"]
                .asString() shouldBe "childAges[0]"
            call(GET, calendarPath(today, today.plusDays(366), listOf(type), null), admin)
                .problem(400, "INVENTORY_PERIOD_TOO_LONG")
            call(GET, calendarPath(today.plusDays(2), today, listOf(type), null), admin)
                .problem(400, "INVENTORY_PERIOD_INVALID")
            call(GET, calendarPath(today, today, listOf(type), UUID.randomUUID()), admin)
                .problem(400, "UNKNOWN_RATE_PLAN")["errors"][0]["field"]
                .asString() shouldBe "ratePlanId"
            call(GET, calendarPath(today, today, listOf(type, type), null), admin)
                .problem(400, "AVAILABILITY_QUERY_INVALID")

            val nobody = createUser()
            call(GET, quotePath(arrival, arrival.plusDays(1), 2, "DIRECT", listOf(type)), asTestUser(nobody))
                .problem(403, "PERMISSION_DENIED")
            call(GET, calendarPath(today, today, listOf(type), plan), asTestUser(nobody))
                .problem(403, "PERMISSION_DENIED")
            val reader =
                createUserWith(
                    TestGrant(
                        ReservationsPermissions.permissions.single {
                            it.code == ReservationsPermissions.AVAILABILITY_READ
                        },
                        ALL,
                    ),
                )
            call(GET, quotePath(arrival, arrival.plusDays(1), 2, "DIRECT", listOf(type)), asTestUser(reader)).ok()
            call(GET, calendarPath(today, today, listOf(type), plan), asTestUser(reader)).ok()
        }
    }

    // --- Apoio ------------------------------------------------------------------------------------------------------

    private val admin: RequestPostProcessor get() = iam.asAdmin()

    private fun calendarPath(
        from: LocalDate,
        to: LocalDate,
        types: List<UUID>,
        plan: UUID?,
    ) = "/api/v1/availability?from=$from&to=$to&roomTypeIds=${types.joinToString(",")}" +
        plan?.let { "&ratePlanId=$it" }.orEmpty()

    private fun calendar(
        from: LocalDate,
        to: LocalDate,
        types: List<UUID>,
        plan: UUID?,
    ): JsonNode = with(iam) { call(GET, calendarPath(from, to, types, plan), admin).ok() }

    private fun roomType(
        calendar: JsonNode,
        type: UUID,
    ): JsonNode = calendar["roomTypes"].values().single { it["roomTypeId"].asString() == type.toString() }

    private fun days(
        calendar: JsonNode,
        type: UUID,
    ): Map<LocalDate, JsonNode> =
        roomType(calendar, type)["days"].values().associateBy { LocalDate.parse(it["date"].asString()) }

    @Suppress("LongParameterList") // a cotação inteira, com padrões
    private fun quotePath(
        arrival: LocalDate,
        departure: LocalDate,
        adults: Int,
        channel: String,
        roomTypes: List<UUID>,
        plans: List<UUID>? = null,
        childAges: List<Int> = emptyList(),
        unavailable: Boolean = false,
    ) = "/api/v1/quotes?arrival=$arrival&departure=$departure&adults=$adults&channel=$channel" +
        "&roomTypeIds=${roomTypes.joinToString(",")}" +
        plans?.let { "&ratePlanIds=${it.joinToString(",")}" }.orEmpty() +
        (if (childAges.isEmpty()) "" else "&childAges=${childAges.joinToString(",")}") +
        "&includeUnavailable=$unavailable"

    @Suppress("LongParameterList") // a cotação inteira, com padrões
    private fun quote(
        arrival: LocalDate,
        departure: LocalDate,
        adults: Int,
        channel: String,
        roomTypes: List<UUID>,
        plans: List<UUID>? = null,
        unavailable: Boolean = false,
    ): JsonNode =
        with(iam) {
            call(
                GET,
                quotePath(arrival, departure, adults, channel, roomTypes, plans, unavailable = unavailable),
                admin,
            ).ok()
        }

    private fun createPlan(
        types: List<UUID>,
        channels: List<String>,
        packages: List<UUID> = emptyList(),
    ): UUID =
        with(iam) {
            val body =
                rates.planBody(
                    types,
                    rates.createCancellationPolicy(),
                    rates.createGuaranteePolicy(),
                    packages,
                    channels = channels,
                )
            UUID.fromString(call(POST, "/api/v1/rate-plans", asAdmin(), body).expectStatus(201)!!["id"].asString())
        }

    /** Os tipos de [all] que estão no plano (lidos do plano). */
    private fun typesIn(
        plan: UUID,
        all: List<UUID>,
    ): List<UUID> =
        with(iam) {
            val inPlan =
                call(
                    GET,
                    "/api/v1/rate-plans/$plan",
                    asAdmin(),
                ).ok()["roomTypeIds"].values().map { it.asString() }
            all.filter { it.toString() in inPlan }
        }

    private fun setPrices(
        plan: UUID,
        types: List<UUID>,
        from: LocalDate,
        to: LocalDate,
    ) = with(iam) {
        prices.update(plan, prices.selection(types, from, to), prices.prices("300.00", "400.00", "120.00")).ok()
    }

    private fun restrict(
        plan: UUID,
        day: LocalDate,
        set: Map<String, Any?>,
    ) = with(iam) { restrictions.update(plan, restrictions.body(from = day, set = set)).ok() }

    private fun sell(
        type: UUID,
        from: LocalDate,
        until: LocalDate,
    ) = transactions.executeWithoutResult { inventory.take(RoomTypeId(type), from, until) }

    private fun openOutOfOrder(
        room: UUID,
        startsOn: LocalDate,
        endsOn: LocalDate,
    ) = roomsApi
        .openOutOfOrder(
            RoomId(room),
            OutOfOrderKind.OUT_OF_ORDER,
            OutOfOrderRequest(startsOn, endsOn, "Infiltração", "Manutenção", HousekeepingStatus.DIRTY),
        ).id
}

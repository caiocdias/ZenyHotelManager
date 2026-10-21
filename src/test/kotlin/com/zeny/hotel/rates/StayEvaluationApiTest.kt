package com.zeny.hotel.rates

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.sum
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Preço e vendabilidade de uma estadia pela `RatesApi` (M4 E04), com os dados gravados pela API: o preço de um plano
 * derivado com pacotes e crianças pelas faixas do hotel, cuja soma das noites é o total; as recusas de "sem preço";
 * cada restrição pela data que importa (e não pelas outras); o tipo de UH e a ocupação pela `RoomsApi`; e a
 * antecedência pela **data operacional**, com o relógio da aplicação dias antes e dias depois dela.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class)
class StayEvaluationApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val ratesApi: RatesApi,
    @Autowired private val clock: MutableClock,
    @Autowired jsonMapper: JsonMapper,
) {
    private val rates = RatesTestSupport(iam)
    private val prices = RatePricesTestSupport(iam, jsonMapper)
    private val restrictions = RestrictionsTestSupport(iam)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a derived plan with packages and children by age band is priced night by night, adding up to the total`() =
        prices.withChildBands(12, listOf(Triple(0, 5, "Bebê"), Triple(6, 12, "Criança"))) {
            val type = rates.rooms.createRoomType()
            val base = rates.createPlanFor(listOf(type))
            val arrival = today.plusDays(5)
            setPrices(base, type, arrival, arrival.plusDays(2), children = true)
            val code = rates.createTransactionCode()
            val breakfast = createPackage(code, "35.00", "PER_PERSON_PER_NIGHT", "DAILY", included = false)
            val welcome = createPackage(code, "80.00", "PER_STAY", "ON_ARRIVAL", included = true)
            val derived = createPlan(listOf(type), listOf(breakfast, welcome))
            with(iam) { prices.derive(derived, prices.derivation("PERCENT", base, percent = "-10.0000")).ok() }

            val query = stay(derived, type, arrival, nights = 3, adults = 3, childAges = listOf(4, 9))
            val result = ratesApi.priceFor(query)

            val price = result.shouldBeInstanceOf<StayPriceResult.Priced>().price
            // −10%: dois adultos 360, adulto extra 108, criança de 0 a 5 0,00 e de 6 a 12 54 → diária 522.
            price.nights.map { it.roomAmount } shouldContainExactly List(3) { money("522.00") }
            price.nights.map { it.children.map { child -> child.label to child.amount } }.first() shouldContainExactly
                listOf("Bebê" to money("0.00"), "Criança" to money("54.00"))
            // Café adicional por pessoa e noite (5 × 35); boas-vindas incluso por estadia, só na chegada.
            price.nights.map { it.additionalPackagesAmount } shouldContainExactly List(3) { money("175.00") }
            price.nights.map { it.includedPackagesAmount } shouldContainExactly
                listOf(money("80.00"), money("0.00"), money("0.00"))
            price.total shouldBe money("2091.00")
            price.nights.map { it.total }.sum() shouldBe price.total
            price.accommodationTotal shouldBe money("1486.00")

            // O preço do base muda: o derivado acompanha na leitura seguinte (H1).
            setPrices(base, type, arrival, arrival, oneAdult = "300.00", twoAdults = "500.00", children = true)
            val repriced = ratesApi.priceFor(stay(derived, type, arrival, nights = 3, adults = 2))
            repriced
                .shouldBeInstanceOf<StayPriceResult.Priced>()
                .price.nights
                .map { it.baseRate } shouldContainExactly
                listOf(money("450.00"), money("360.00"), money("360.00"))
        }

    @Test
    fun `a stay without price, a room type out of the plan and an unknown plan say why`() {
        val type = rates.rooms.createRoomType()
        val other = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(type))
        val arrival = today.plusDays(3)
        setPrices(plan, type, arrival, arrival.plusDays(1), twoAdults = null)

        ratesApi.priceFor(stay(plan, type, arrival, nights = 3, adults = 1)).refusals() shouldContainExactly
            listOf(RatesErrorCodes.RATE_NOT_PRICED.value to listOf(arrival.plusDays(2)))
        ratesApi.priceFor(stay(plan, type, arrival, nights = 2, adults = 2)).refusals() shouldContainExactly
            listOf(RatesErrorCodes.OCCUPANCY_NOT_PRICED.value to listOf(arrival, arrival.plusDays(1)))
        ratesApi.priceFor(stay(plan, other, arrival, nights = 1)).refusals().map { it.first } shouldContainExactly
            listOf(RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN.value)
        ratesApi.priceFor(stay(UUID.randomUUID(), type, arrival, nights = 1)).refusals().map { it.first } shouldBe
            listOf(RatesErrorCodes.UNKNOWN_RATE_PLAN.value)
        shouldThrow<InvalidInputException> { ratesApi.priceFor(stay(plan, type, arrival, nights = -1)) }
            .code shouldBe RatesErrorCodes.STAY_INVALID
    }

    @Test
    fun `each restriction refuses only by the date that matters, and the plan, the channel and the room type too`() {
        val type = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(type))
        setPrices(plan, type, today.plusDays(2), today.plusDays(16))
        val cta = today.plusDays(4)
        val ctd = today.plusDays(8)
        val minStay = today.plusDays(11)
        val closed = today.plusDays(14)
        restrict(plan, cta, mapOf("closedToArrival" to true))
        restrict(plan, ctd, mapOf("closedToDeparture" to true))
        restrict(plan, minStay, mapOf("minStay" to 3))
        restrict(plan, closed, mapOf("closed" to true))

        fun codes(
            arrival: LocalDate,
            nights: Long,
            adults: Int = 2,
            channel: SalesChannel = SalesChannel.DIRECT,
        ) = ratesApi.isSellable(stay(plan, type, arrival, nights, adults), channel).codes.map { it.value }

        codes(today.plusDays(2), 2).shouldBeEmpty()
        codes(cta, 2) shouldContainExactly listOf("CLOSED_TO_ARRIVAL")
        codes(cta.minusDays(1), 2).shouldBeEmpty()
        codes(ctd.minusDays(2), 2) shouldContainExactly listOf("CLOSED_TO_DEPARTURE")
        codes(ctd.minusDays(1), 2).shouldBeEmpty()
        codes(minStay, 2) shouldContainExactly listOf("MIN_STAY_NOT_MET")
        codes(minStay, 3).shouldBeEmpty()
        codes(minStay.minusDays(1), 2).shouldBeEmpty()
        codes(closed.minusDays(1), 2) shouldContainExactly listOf("STAY_DATE_CLOSED")
        codes(closed.minusDays(2), 2).shouldBeEmpty()

        codes(today.plusDays(2), 2, channel = SalesChannel.OTA) shouldContainExactly listOf("SALES_CHANNEL_NOT_ALLOWED")
        // O tipo de UH recebe até 3 adultos (RoomsTestSupport); o 4º passa da ocupação máxima.
        codes(today.plusDays(2), 2, adults = 3).shouldBeEmpty()
        codes(today.plusDays(2), 2, adults = 4) shouldContain "OCCUPANCY_EXCEEDS_ROOM_TYPE"
        ratesApi
            .isSellable(stay(plan, UuidV7.generate(), today.plusDays(2), 2), SalesChannel.DIRECT)
            .codes
            .map { it.value } shouldContainExactly listOf("UNKNOWN_ROOM_TYPE")

        with(iam) { call(POST, "/api/v1/room-types/$type/deactivate", asAdmin()).ok() }
        codes(today.plusDays(2), 2) shouldContainExactly listOf("ROOM_TYPE_INACTIVE")
        prices.deactivatePlan(plan)
        codes(today.plusDays(2), 2) shouldContainExactly listOf("RATE_PLAN_INACTIVE", "ROOM_TYPE_INACTIVE")
    }

    @Test
    fun `the advance counts from the business date, never from the date of the clock`() {
        val type = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(type))
        setPrices(plan, type, today, today.plusDays(10))
        val arrival = today.plusDays(3)
        restrict(plan, arrival, mapOf("minAdvanceDays" to 2, "maxAdvanceDays" to 5))
        restrict(plan, today.plusDays(1), mapOf("minAdvanceDays" to 2))

        fun codes(arrival: LocalDate) =
            ratesApi.isSellable(stay(plan, type, arrival, 2), SalesChannel.DIRECT).codes.map { it.value }

        listOf(Duration.ofDays(-40), Duration.ofDays(40)).forEach { shift ->
            clock.advance(shift)
            try {
                val clockDate = LocalDate.ofInstant(clock.instant(), ZoneId.of("America/Sao_Paulo"))
                withClue("relógio em $clockDate, data operacional $today") {
                    (clockDate == today) shouldBe false
                    codes(arrival).shouldBeEmpty()
                    codes(today.plusDays(1)) shouldContainExactly listOf("MIN_ADVANCE_NOT_MET")
                    codes(today).shouldBeEmpty()
                    val before = codes(today.minusDays(1))
                    before shouldContain "ARRIVAL_BEFORE_BUSINESS_DATE"
                    before shouldNotContain "MIN_ADVANCE_NOT_MET"
                }
            } finally {
                clock.advance(shift.negated())
            }
        }
    }

    @Test
    @Suppress("LongMethod") // as combinações da cotação comparadas uma a uma e o calendário
    fun `stayOptions gives for each plan and room type exactly what isSellable and priceFor give`() {
        val typeA = rates.rooms.createRoomType()
        val typeB = rates.rooms.createRoomType()
        val arrival = today.plusDays(6)
        val base = rates.createPlanFor(listOf(typeA, typeB))
        val ota = createPlan(listOf(typeA, typeB), emptyList(), channels = listOf("OTA"))
        val derived =
            createPlan(
                listOf(typeA),
                listOf(
                    createPackage(
                        rates.createTransactionCode(),
                        "35.00",
                        "PER_PERSON_PER_NIGHT",
                        "DAILY",
                        included = false,
                    ),
                ),
            )
        setPrices(base, typeA, arrival, arrival.plusDays(1))
        setPrices(base, typeB, arrival, arrival)
        setPrices(ota, typeA, arrival, arrival.plusDays(1))
        with(iam) { prices.derive(derived, prices.derivation("PERCENT", base, percent = "-10.0000")).ok() }
        with(iam) {
            restrictions
                .update(
                    base,
                    restrictions.body(roomTypeIds = listOf(typeA), from = arrival, set = mapOf("minStay" to 3)),
                ).ok()
        }
        val query =
            StayOptionsQuery(
                arrival,
                arrival.plusDays(2),
                2,
                emptyList(),
                SalesChannel.DIRECT,
                setOf(RoomTypeId(typeA), RoomTypeId(typeB)),
            )

        val options = ratesApi.stayOptions(query)

        options.map { it.ratePlan.id.value to it.roomTypeId.value } shouldContainExactlyInAnyOrder
            listOf(base to typeA, base to typeB, ota to typeA, ota to typeB, derived to typeA)
        options.forEach { option ->
            val stay = query.stay(option.ratePlan.id, option.roomTypeId)
            withClue("${option.ratePlan.code} ${option.roomTypeId}") {
                option.sellability shouldBe ratesApi.isSellable(stay, SalesChannel.DIRECT)
                option.price shouldBe (ratesApi.priceFor(stay) as? StayPriceResult.Priced)?.price
            }
        }
        val derivedOption = options.single { it.ratePlan.id.value == derived }
        derivedOption.sellability.sellable shouldBe true
        derivedOption.price!!.total shouldBe money("860.00")
        options.single { it.ratePlan.id.value == base && it.roomTypeId.value == typeA }.sellability.codes.map {
            it.value
        } shouldContainExactly listOf("MIN_STAY_NOT_MET")
        options.single { it.ratePlan.id.value == base && it.roomTypeId.value == typeB }.price shouldBe null

        // Os planos pedidos: o desativado entra (recusado); o inexistente é 400.
        prices.deactivatePlan(ota)
        ratesApi
            .stayOptions(query.copy(ratePlanIds = setOf(RatePlanId(ota))))
            .map {
                it.sellability.codes
                    .first()
                    .value
            }.distinct() shouldContainExactly listOf("RATE_PLAN_INACTIVE")
        ratesApi.stayOptions(query).map { it.ratePlan.id.value }.distinct() shouldNotContain ota
        shouldThrow<InvalidInputException> {
            ratesApi.stayOptions(query.copy(ratePlanIds = setOf(RatePlanId(UuidV7.generate()))))
        }.code shouldBe RatesErrorCodes.UNKNOWN_RATE_PLAN
        shouldThrow<InvalidInputException> { ratesApi.stayOptions(query.copy(departure = arrival.minusDays(1))) }
            .code shouldBe RatesErrorCodes.STAY_INVALID

        // O calendário: os preços efetivos do derivado e a restrição efetiva do tipo; o tipo fora do plano não vem.
        val calendar =
            ratesApi.rateCalendar(
                RatePlanId(derived),
                listOf(RoomTypeId(typeA), RoomTypeId(typeB)),
                arrival,
                arrival.plusDays(2),
            )!!
        calendar.days.map { it.roomTypeId.value to it.date } shouldContainExactly
            (0L..2L).map { typeA to arrival.plusDays(it) }
        calendar.days.map { it.prices?.twoAdults } shouldContainExactly listOf(money("360.00"), money("360.00"), null)
        val baseCalendar =
            ratesApi.rateCalendar(RatePlanId(base), listOf(RoomTypeId(typeA)), arrival, arrival)!!
        baseCalendar.days
            .single()
            .restriction.minStay shouldBe 3
        ratesApi.rateCalendar(RatePlanId(UuidV7.generate()), listOf(RoomTypeId(typeA)), arrival, arrival) shouldBe null
    }

    // --- Apoio ------------------------------------------------------------------------------------------------------

    @Suppress("LongParameterList") // a estadia inteira, com padrões
    private fun stay(
        plan: UUID,
        type: UUID,
        arrival: LocalDate,
        nights: Long,
        adults: Int = 2,
        childAges: List<Int> = emptyList(),
    ) = StayQuery(RatePlanId(plan), RoomTypeId(type), arrival, arrival.plusDays(nights), adults, childAges)

    private fun StayPriceResult.refusals(): List<Pair<String, List<LocalDate>>> =
        shouldBeInstanceOf<StayPriceResult.NotPriced>().refusals.map { it.code.value to it.dates }

    @Suppress("LongParameterList") // o período e os valores, com padrões
    private fun setPrices(
        plan: UUID,
        type: UUID,
        from: LocalDate,
        to: LocalDate,
        oneAdult: String = "300.00",
        twoAdults: String? = "400.00",
        children: Boolean = false,
    ) = with(iam) {
        prices
            .update(
                plan,
                prices.selection(listOf(type), from, to),
                prices.prices(
                    oneAdult,
                    twoAdults,
                    "120.00",
                    if (children) listOf(Triple(0, 5, "0.00"), Triple(6, 12, "60.00")) else emptyList(),
                ),
            ).ok()
    }

    private fun restrict(
        plan: UUID,
        day: LocalDate,
        set: Map<String, Any?>,
    ) = with(iam) { restrictions.update(plan, restrictions.body(from = day, set = set)).ok() }

    private fun createPackage(
        transactionCode: UUID,
        price: String,
        pricing: String,
        rhythm: String,
        included: Boolean,
    ): UUID =
        with(iam) {
            val body =
                rates.packageBody(
                    transactionCode,
                    price = price,
                    pricing = pricing,
                    postingRhythm = rhythm,
                    includedInRate = included,
                )
            UUID.fromString(call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!!["id"].asString())
        }

    private fun createPlan(
        types: List<UUID>,
        packages: List<UUID>,
        channels: List<String> = listOf("DIRECT", "WEBSITE"),
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

    private fun money(value: String) = Money.of(value)
}

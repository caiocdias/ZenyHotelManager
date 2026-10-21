package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rates.RatePricesTestSupport
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.rates.RestrictionsTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Desempenho da cotação (M4 E06, RNF-06: p95 < 300 ms nas operações comuns): um hotel de exemplo com 30 UHs em 5 tipos,
 * 730 dias de inventário e de preços (dois planos com preços próprios, dois derivados — um com pacote — e um só da
 * OTA), restrições em todos os fins de semana do ano; a cotação de 7 noites de todos os tipos e planos pela API (o
 * caminho HTTP inteiro, com a segurança e a serialização), medida depois do aquecimento. O resultado vai ao handoff.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
class QuotePerformanceTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = InventoryTestSupport(iam, jdbc)
    private val rates = RatesTestSupport(iam)
    private val prices = RatePricesTestSupport(iam, jsonMapper)
    private val restrictions = RestrictionsTestSupport(iam)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    @Suppress("LongMethod") // o hotel de exemplo, a medição e a referência de escala
    fun `a 7-night quote of every room type and plan of a 30-room hotel with 730 days of data takes under 300 ms`() {
        val types = (1..ROOM_TYPES).map { support.roomTypeWithRooms(ROOMS_PER_TYPE, today).first }
        val last = today.plusDays(HORIZON_DAYS - 1)
        support.awaitRow(types.last(), last) { it.total == ROOMS_PER_TYPE }
        val counter = createPlan(types, listOf("DIRECT", "WEBSITE"))
        val nonRefundable = createPlan(types, listOf("DIRECT", "WEBSITE"))
        val ota = createPlan(types, listOf("OTA"))
        listOf(counter to "400.00", nonRefundable to "360.00", ota to "420.00").forEach { (plan, twoAdults) ->
            setPrices(plan, types, today, today.plusDays(MAX_EDIT_DAYS - 1), twoAdults)
            setPrices(plan, types, today.plusDays(MAX_EDIT_DAYS), last, twoAdults)
            weekendRestrictions(plan)
        }
        val discounted = createPlan(types, listOf("DIRECT"))
        val withBreakfast = createPlan(types, listOf("DIRECT", "WEBSITE"), listOf(createPackage()))
        with(iam) {
            prices.derive(discounted, prices.derivation("PERCENT", counter, percent = "-10.0000")).ok()
            prices.derive(withBreakfast, prices.derivation("AMOUNT", counter, amount = "40.00", perPerson = true)).ok()
        }
        val arrival = today.plusDays(ARRIVAL_IN_DAYS)
        val path =
            "/api/v1/quotes?arrival=$arrival&departure=${arrival.plusDays(NIGHTS)}&adults=2&channel=DIRECT" +
                "&roomTypeIds=${types.joinToString(",")}&includeUnavailable=true"

        // Os quatro planos do canal DIRECT (o da OTA não), nos 5 tipos.
        val body = with(iam) { call(GET, path, asAdmin()).ok() }
        body["offers"].values() shouldHaveSize ROOM_TYPES * 4
        body["unavailable"].values() shouldHaveSize ROOM_TYPES

        repeat(WARM_UP) { with(iam) { call(GET, path, asAdmin()).ok() } }
        val samples =
            (1..SAMPLES)
                .map { measureTime { with(iam) { call(GET, path, asAdmin()).ok() } } }
                .sorted()
        val p50 = samples[SAMPLES / 2]
        val p95 = samples[(SAMPLES * 95) / 100 - 1]
        val max = samples.last()
        println(
            "QUOTE_PERFORMANCE nights=$NIGHTS roomTypes=$ROOM_TYPES rooms=${ROOM_TYPES * ROOMS_PER_TYPE} " +
                "plans=5 offers=${ROOM_TYPES * 4} samples=$SAMPLES p50=${p50.ms()}ms p95=${p95.ms()}ms " +
                "max=${max.ms()}ms",
        )
        withClue("p50=${p50.ms()} ms, p95=${p95.ms()} ms, max=${max.ms()} ms") {
            p95.ms() shouldBeLessThan RNF_06_MILLIS
        }
        (types.size * ROOMS_PER_TYPE) shouldBe 30

        // Sem N+1 por noite: o tempo quase não muda de 1 a 28 noites (as leituras são por plano e por período); o
        // calendário de 31 dias com os preços e as restrições do plano, para referência.
        val byNights =
            listOf(1L, NIGHTS, 28L).associateWith { nights ->
                val quotePath =
                    path.replace(
                        "departure=${arrival.plusDays(NIGHTS)}",
                        "departure=${arrival.plusDays(nights)}",
                    )
                median { with(iam) { call(GET, quotePath, asAdmin()).ok() } }
            }
        val calendarPath =
            "/api/v1/availability?from=$arrival&to=${arrival.plusDays(30)}&roomTypeIds=${types.joinToString(",")}" +
                "&ratePlanId=$withBreakfast"
        val calendar = median { with(iam) { call(GET, calendarPath, asAdmin()).ok() } }
        println(
            "QUOTE_SCALING " + byNights.entries.joinToString(" ") { "nights${it.key}=${it.value.ms()}ms" } +
                " calendar31days=${calendar.ms()}ms",
        )
        byNights.values.forEach { it.ms() shouldBeLessThan RNF_06_MILLIS }
        calendar.ms() shouldBeLessThan RNF_06_MILLIS
    }

    private fun median(call: () -> Unit): Duration {
        repeat(WARM_UP) { call() }
        return (1..SCALING_SAMPLES).map { measureTime(call) }.sorted()[SCALING_SAMPLES / 2]
    }

    private fun Duration.ms(): Int = inWholeMilliseconds.toInt()

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

    private fun createPackage(): UUID =
        with(iam) {
            val body = rates.packageBody(rates.createTransactionCode(), price = "35.00", includedInRate = false)
            UUID.fromString(call(POST, "/api/v1/packages", asAdmin(), body).expectStatus(201)!!["id"].asString())
        }

    private fun setPrices(
        plan: UUID,
        types: List<UUID>,
        from: LocalDate,
        to: LocalDate,
        twoAdults: String,
    ) = with(iam) {
        prices.update(plan, prices.selection(types, from, to), prices.prices("300.00", twoAdults, "120.00")).ok()
    }

    /** Estadia mínima de 2 noites na chegada de sexta e sábado, o ano inteiro. */
    private fun weekendRestrictions(plan: UUID) =
        with(iam) {
            val until = today.plusDays(MAX_EDIT_DAYS - 1)
            restrictions
                .update(
                    plan,
                    restrictions.body(
                        from = today,
                        to = until,
                        weekdays = listOf("FRIDAY", "SATURDAY"),
                        set = mapOf("minStay" to 2),
                    ),
                ).ok()
        }

    private companion object {
        const val ROOM_TYPES = 5
        const val ROOMS_PER_TYPE = 6
        const val HORIZON_DAYS = 730L
        const val MAX_EDIT_DAYS = 366L
        const val ARRIVAL_IN_DAYS = 60L
        const val NIGHTS = 7L
        const val WARM_UP = 10
        const val SAMPLES = 60
        const val SCALING_SAMPLES = 15
        const val RNF_06_MILLIS = 300
    }
}

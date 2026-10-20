package com.zeny.hotel.rates

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatesTestSupport.Companion.definition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * Preços por tipo de UH, data e ocupação pela API (M4 E02, RF-TAR-02 e 04, H2): a edição em massa por período e dias
 * da semana (só as datas filtradas), a retirada, os preços de criança pelas faixas atuais do hotel (e a faixa única sem
 * elas; as faixas que mudaram ficam obsoletas e sinalizadas), as temporadas como atalho, as recusas com código e campo,
 * as permissões, a trilha e um teste simples de volume (366 dias × 3 tipos num só comando).
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, RatesChangedListenerConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta da edição em massa, das faixas e das temporadas
class RatePricesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val rates = RatesTestSupport(iam)
    private val prices = RatePricesTestSupport(iam, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Edição em massa ------------------------------------------------------------------------------------------

    @Test
    fun `a bulk update changes only the dates of the weekdays asked for, inside the period`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val from = nextMonday()
            val to = from.plusDays(13)

            val weekend = selection(roomType, from, to, listOf("FRIDAY", "SATURDAY"))
            val result = prices.update(plan, weekend, prices.prices("300.00", "350.00", "90.00")).ok()
            result["matchedDates"].asInt() shouldBe 4
            result["created"].asInt() shouldBe 4
            result["changed"].asInt() shouldBe 0
            result["from"].asString() shouldBe from.plusDays(4).toString()
            result["to"].asString() shouldBe from.plusDays(12).toString()

            val read = prices.byDate(plan, from, to)
            read.keys.sorted() shouldContainExactly listOf(4L, 5L, 11L, 12L).map(from::plusDays)
            read.values.forEach {
                it["oneAdult"].asString() shouldBe "300.00"
                it["twoAdults"].asString() shouldBe "350.00"
                it["extraAdult"].asString() shouldBe "90.00"
                it["roomTypeId"].asString() shouldBe roomType.toString()
            }
            rowCount(plan) shouldBe 4

            // A semana inteira com outro valor: os dias úteis são criados, o fim de semana muda.
            val week = prices.update(plan, selection(roomType, from, to), prices.prices("250.00")).ok()
            week["created"].asInt() shouldBe 10
            week["changed"].asInt() shouldBe 4
            prices
                .byDate(plan, from, to)
                .values
                .map { it["oneAdult"].asString() }
                .toSet() shouldBe setOf("250.00")
            prices.byDate(plan, from, to).values.forEach { it["twoAdults"].isNull shouldBe true }

            // Regravar os mesmos valores não muda nada (nem trilha).
            val same = prices.update(plan, selection(roomType, from, to), prices.prices("250.00")).ok()
            same["created"].asInt() + same["changed"].asInt() shouldBe 0
            same["from"].isNull shouldBe true
        }

    @Test
    fun `a bulk removal takes out only the filtered dates`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val from = nextMonday()
            val to = from.plusDays(6)
            prices.update(plan, selection(roomType, from, to), prices.prices("200.00")).ok()

            val removed = prices.remove(plan, selection(roomType, from, to, listOf("MONDAY", "WEDNESDAY"))).ok()
            removed["removed"].asInt() shouldBe 2

            prices.byDate(plan, from, to).keys.sorted() shouldContainExactly
                listOf(1L, 3L, 4L, 5L, 6L).map(from::plusDays)
            prices.remove(plan, selection(roomType, from, to, listOf("MONDAY"))).ok()["removed"].asInt() shouldBe 0
        }

    @Test
    fun `bulk edits refuse periods above 366 days, in the past, without dates, and room types out of the plan`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val value = prices.prices("100.00")
            val start = today

            val tooLong = prices.update(plan, selection(roomType, start, start.plusDays(366)), value)
            fieldOf(tooLong.problem(400, "RATE_PRICE_PERIOD_TOO_LONG")) shouldBe "to"
            prices.update(plan, selection(roomType, start, start.plusDays(365)), value).ok()["created"].asInt() shouldBe
                366
            fieldOf(
                prices
                    .update(plan, selection(roomType, start, start.minusDays(1)), value)
                    .problem(400, "RATE_PRICE_PERIOD_INVALID"),
            ) shouldBe "to"
            prices
                .update(plan, selection(roomType, start.minusDays(1), start), value)
                .problem(422, "RATE_PRICE_PERIOD_IN_PAST")
            val monday = nextMonday()
            fieldOf(
                prices
                    .update(plan, selection(roomType, monday, monday.plusDays(1), listOf("SUNDAY")), value)
                    .problem(400, "RATE_PRICE_PERIOD_INVALID"),
            ) shouldBe "weekdays"
            prices
                .update(plan, selection(rates.rooms.createRoomType(), start, start), value)
                .problem(422, "ROOM_TYPE_NOT_IN_PLAN")
            fieldOf(
                prices
                    .update(plan, prices.selection(listOf(roomType)), value)
                    .problem(400, "RATE_PRICE_SELECTION_INVALID"),
            ) shouldBe "seasonId"
            fieldOf(
                prices
                    .update(plan, selection(roomType, start, start), prices.prices("1000000.01"))
                    .problem(400, "RATE_PRICE_INVALID"),
            ) shouldBe "prices.oneAdult"
            prices
                .update(plan, selection(roomType, start, start), mapOf("twoAdults" to "1.00"))
                .problem(400, "VALIDATION_FAILED")
            prices
                .update(UUID.randomUUID(), selection(roomType, start, start), value)
                .problem(404, "RATE_PLAN_NOT_FOUND")
            prices.read(plan, start, start.plusDays(366)).problem(400, "RATE_PRICE_PERIOD_TOO_LONG")
        }

    @Test
    fun `reading needs the plans read permission and writing needs the prices permission`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val day = today.plusDays(3)

            prices.read(plan, day, day, asTestUser(reader)).ok()
            prices.read(plan, day, day, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            prices
                .update(plan, selection(roomType, day, day), prices.prices("1.00"), asTestUser(reader))
                .problem(403, "PERMISSION_DENIED")
            prices.remove(plan, selection(roomType, day, day), asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            // Quem só pode alterar a tarifa publicada, sem a permissão de preços, também não grava.
            val overrideOnly = createUserWith(TestGrant(definition(RatesPermissions.PRICES_OVERRIDE), ALL))
            prices
                .update(plan, selection(roomType, day, day), prices.prices("1.00"), asTestUser(overrideOnly))
                .problem(403, "PERMISSION_DENIED")
            rowCount(plan) shouldBe 0
        }

    @Test
    fun `each bulk edit goes to the trail with the selection and the values, and a no-op writes nothing`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(2)
            prices.update(plan, selection(roomType, day, day.plusDays(1)), prices.prices("180.00")).ok()
            prices.update(plan, selection(roomType, day, day.plusDays(1)), prices.prices("180.00")).ok()
            prices.remove(plan, selection(roomType, day, day)).ok()

            val entries = trail(plan)
            entries.map { it.first } shouldContainExactly
                listOf("rates.rate-price.bulk-update", "rates.rate-price.bulk-remove")
            val created = entries.first().second
            created["created"].asInt() shouldBe 2
            created["prices"]["oneAdult"].asString() shouldBe "180.00"
            created["roomTypeIds"].values().map { it.asString() } shouldContainExactly listOf(roomType.toString())
            entries.last().second["removed"].asInt() shouldBe 1
        }

    // --- Faixas de criança ----------------------------------------------------------------------------------------

    @Test
    fun `child prices use the hotel bands, a single child price without bands, and old bands are flagged obsolete`() {
        val roomType = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(roomType))
        val day = today.plusDays(5)

        prices.withChildBands(12, emptyList()) {
            with(iam) {
                // Sem faixas: um preço único de criança, de 0 à idade limite.
                prices
                    .update(
                        plan,
                        selection(roomType, day, day),
                        prices.prices("200.00", children = listOf(Triple(0, 12, "40.00"))),
                    ).ok()
                val single =
                    prices
                        .byDate(plan, day, day)
                        .getValue(day)["children"]
                        .values()
                        .single()
                single["minAge"].asInt() shouldBe 0
                single["maxAge"].asInt() shouldBe 12
                single["amount"].asString() shouldBe "40.00"
                fieldOf(
                    prices
                        .update(
                            plan,
                            selection(roomType, day, day),
                            prices.prices("200.00", children = listOf(Triple(0, 5, "1.00"))),
                        ).problem(400, "RATE_PRICE_CHILD_BANDS_INVALID"),
                ) shouldBe "prices.children[0]"
            }
        }
        prices.withChildBands(12, listOf(Triple(0, 5, "0 a 5 anos"), Triple(6, 12, "6 a 12 anos"))) {
            with(iam) {
                // A faixa única gravada antes não é uma das faixas de agora: obsoleta.
                val before = prices.byDate(plan, day, day).getValue(day)
                before["children"].values().shouldBeEmpty()
                before["obsoleteChildren"].values().single()["maxAge"].asInt() shouldBe 12

                val bands = listOf(Triple(0, 5, "0.00"), Triple(6, 12, "60.00"))
                prices.update(plan, selection(roomType, day, day), prices.prices("200.00", children = bands)).ok()
                val after = prices.byDate(plan, day, day).getValue(day)
                after["children"].values().map {
                    it["label"].asString() to it["amount"].asString()
                } shouldContainExactly
                    listOf("0 a 5 anos" to "0.00", "6 a 12 anos" to "60.00")
                after["obsoleteChildren"].values().shouldBeEmpty()
                childRows(plan) shouldBe 2
            }
        }
        prices.withChildBands(12, listOf(Triple(0, 3, "Bebê"), Triple(4, 12, "Criança"))) {
            // As faixas mudaram no hotel: nada é recalculado; os preços gravados ficam obsoletos, nunca adulto extra.
            val changed = prices.byDate(plan, day, day).getValue(day)
            changed["children"].values().shouldBeEmpty()
            changed["obsoleteChildren"].values().map { it["minAge"].asInt() } shouldContainExactly listOf(0, 6)
            changed["oneAdult"].asString() shouldBe "200.00"
        }
    }

    // --- Temporadas -----------------------------------------------------------------------------------------------

    @Test
    fun `seasons are kept with If-Match and serve as a period shortcut, cut at the business date`(): Unit =
        with(iam) {
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val name = rates.uniqueName("Alta temporada")
            val body = mapOf("name" to name, "startsOn" to today.minusDays(3), "endsOn" to today.plusDays(10))
            call(POST, SEASONS, asTestUser(reader), body).problem(403, "PERMISSION_DENIED")
            val created = call(POST, SEASONS, asAdmin(), body).expectStatus(201)!!
            val season = UUID.fromString(created["id"].asString())
            call(GET, "$SEASONS/$season", asTestUser(reader)).ok()["name"].asString() shouldBe name
            call(POST, SEASONS, asAdmin(), body + ("name" to name.uppercase()))
                .problem(409, "SEASON_NAME_ALREADY_IN_USE")
            fieldOf(
                call(
                    POST,
                    SEASONS,
                    asAdmin(),
                    body + mapOf("name" to rates.uniqueName("X"), "endsOn" to today.minusDays(4)),
                ).problem(400, "SEASON_INVALID"),
            ) shouldBe "endsOn"
            val etag = rates.etagOf("$SEASONS/$season")
            call(PUT, "$SEASONS/$season", asAdmin(), body).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, "$SEASONS/$season", asAdmin(), body + ("endsOn" to today.plusDays(6)), ifMatch = etag).ok()
            rates.listAll("$SEASONS?active=true").map { it["id"].asString() }.contains(season.toString()) shouldBe true

            withClue("a temporada nunca é apagada") {
                runCatching { jdbc.sql("DELETE FROM rates.season WHERE id = :id").param("id", season).update() }
                    .isFailure shouldBe true
            }
        }

    @Test
    fun `a season is a period shortcut cut at the business date, and must exist, be active and not over`(): Unit =
        with(iam) {
            val body =
                mapOf(
                    "name" to rates.uniqueName("Feriado"),
                    "startsOn" to today.minusDays(3),
                    "endsOn" to today.plusDays(6),
                )
            val season = UUID.fromString(call(POST, SEASONS, asAdmin(), body).expectStatus(201)!!["id"].asString())

            // A temporada como período: começa antes da data operacional, então a edição começa nela.
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val bySeason = prices.selection(listOf(roomType), seasonId = season)
            val result = prices.update(plan, bySeason, prices.prices("320.00")).ok()
            result["matchedDates"].asInt() shouldBe 7
            result["from"].asString() shouldBe today.toString()
            result["to"].asString() shouldBe today.plusDays(6).toString()
            prices.byDate(plan, today.minusDays(3), today.plusDays(10)).keys.minOrNull() shouldBe today

            fieldOf(
                prices
                    .update(plan, bySeason + mapOf("from" to today, "to" to today), prices.prices("1.00"))
                    .problem(400, "RATE_PRICE_SELECTION_INVALID"),
            ) shouldBe "seasonId"
            fieldOf(
                prices
                    .update(
                        plan,
                        prices.selection(listOf(roomType), seasonId = UUID.randomUUID()),
                        prices.prices("1.00"),
                    ).problem(400, "UNKNOWN_SEASON"),
            ) shouldBe "seasonId"
            call(POST, "$SEASONS/$season/deactivate", asAdmin()).ok()["active"].asBoolean() shouldBe false
            prices.update(plan, bySeason, prices.prices("1.00")).problem(422, "SEASON_INACTIVE")
            call(POST, "$SEASONS/$season/reactivate", asAdmin()).ok()

            val past =
                call(
                    POST,
                    SEASONS,
                    asAdmin(),
                    mapOf(
                        "name" to rates.uniqueName("Passada"),
                        "startsOn" to today.minusDays(9),
                        "endsOn" to today.minusDays(1),
                    ),
                ).expectStatus(201)!!["id"].asString()
            prices
                .update(
                    plan,
                    prices.selection(listOf(roomType), seasonId = UUID.fromString(past)),
                    prices.prices("1.00"),
                ).problem(422, "RATE_PRICE_PERIOD_IN_PAST")
        }

    // --- Volume ---------------------------------------------------------------------------------------------------

    @Test
    fun `a year of prices for three room types is written in one bulk edit, fast`(): Unit =
        with(iam) {
            val types = List(3) { rates.rooms.createRoomType() }
            val plan = rates.createPlanFor(types)
            val bands = listOf(Triple(0, 12, "30.00"))

            prices.withChildBands(12, emptyList()) {
                val started = System.nanoTime()
                val result =
                    prices
                        .update(
                            plan,
                            prices.selection(types, today, today.plusDays(365)),
                            prices.prices("300.00", "360.00", "80.00", bands),
                        ).ok()
                val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
                result["created"].asInt() shouldBe 366 * 3
                rowCount(plan) shouldBe 366 * 3
                childRows(plan) shouldBe 366 * 3
                withClue("366 dias × 3 tipos em $elapsedMillis ms") { elapsedMillis shouldBeLessThan MAX_BULK_MILLIS }
            }
        }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun selection(
        roomType: UUID,
        from: LocalDate,
        to: LocalDate,
        weekdays: List<String> = emptyList(),
    ) = prices.selection(listOf(roomType), from, to, weekdays)

    /** A primeira segunda-feira a partir de amanhã (na data operacional). */
    private fun nextMonday(): LocalDate =
        generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.MONDAY }

    private fun fieldOf(problem: JsonNode): String = problem["errors"].values().single()["field"].asString()

    private fun rowCount(plan: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM rates.rate_price WHERE rate_plan_id = :plan")
            .param("plan", plan)
            .query(Int::class.java)
            .single()

    private fun childRows(plan: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM rates.rate_price_child WHERE rate_plan_id = :plan")
            .param("plan", plan)
            .query(Int::class.java)
            .single()

    private fun trail(plan: UUID): List<Pair<String, JsonNode>> =
        jdbc
            .sql(
                "SELECT action, after_state::text FROM audit.audit_entry " +
                    "WHERE entity_type = 'rates.rate-price' AND entity_id = :id ORDER BY occurred_at, id",
            ).param("id", plan.toString())
            .query { rs, _ -> rs.getString(1) to jsonMapper.readTree(rs.getString(2)) }
            .list()

    private companion object {
        const val SEASONS = "/api/v1/seasons"
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_BULK_MILLIS = 5_000L
    }
}

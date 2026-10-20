package com.zeny.hotel.rates

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.rates.RatesTestSupport.Companion.definition
import com.zeny.hotel.rates.application.RestrictionReader
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.EnableScenarios
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/**
 * Restrições de venda pela API (M4 E03, RF-TAR-05): a precedência da restrição do tipo de UH sobre a geral do plano,
 * campo a campo, na leitura e na `RestrictionReader` da E04; a edição em massa parcial só nas datas e dias da semana
 * filtrados; as recusas com código e campo (valores fora da faixa, negativos, mínimo maior que o máximo no pedido e com
 * o que já está gravado); a temporada como atalho; as restrições próprias de um plano derivado; permissões e trilha.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, RatesChangedListenerConfiguration::class)
class RestrictionsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val reader: RestrictionReader,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val rates = RatesTestSupport(iam)
    private val restrictions = RestrictionsTestSupport(iam)
    private val prices = RatePricesTestSupport(iam, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Precedência ----------------------------------------------------------------------------------------------

    @Test
    fun `the room type restriction prevails over the general one of the plan, field by field`(): Unit =
        with(iam) {
            val single = rates.rooms.createRoomType()
            val double = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(single, double))
            val day = today.plusDays(4)

            restrictions
                .update(
                    plan,
                    restrictions.body(
                        from = day,
                        set = mapOf("closed" to true, "minStay" to 3, "closedToArrival" to true),
                    ),
                ).ok()
                .let {
                    it["general"].asBoolean() shouldBe true
                    it["created"].asInt() shouldBe 1
                    it["roomTypeIds"].values().map { id -> id.asString() }.toSet() shouldBe
                        setOf(single, double).map { id -> id.toString() }.toSet()
                }
            // O single abre a data (exceção sobre a geral fechada) e limita a estadia a 5 noites.
            restrictions
                .update(
                    plan,
                    restrictions.body(listOf(single), day, set = mapOf("closed" to false, "maxStay" to 5)),
                ).ok()["general"]
                .asBoolean() shouldBe false

            val item = restrictions.byDate(plan, day, day).getValue(day)
            item["general"]["closed"].asBoolean() shouldBe true
            item["general"]["minStay"].asInt() shouldBe 3
            item["general"]["maxStay"].isNull shouldBe true
            val byType = item["roomTypes"].values().associateBy { UUID.fromString(it["roomTypeId"].asString()) }

            val specific = byType.getValue(single)
            specific["specific"]["closed"].asBoolean() shouldBe false
            specific["specific"]["minStay"].isNull shouldBe true
            specific["effective"].let {
                it["closed"].asBoolean() shouldBe false
                it["closedToArrival"].asBoolean() shouldBe true
                it["closedToDeparture"].asBoolean() shouldBe false
                it["minStay"].asInt() shouldBe 3
                it["maxStay"].asInt() shouldBe 5
                it["minAdvanceDays"].isNull shouldBe true
                fields(it["fromRoomType"]) shouldContainExactly listOf("CLOSED", "MAX_STAY")
            }
            val inherited = byType.getValue(double)
            inherited["specific"].isNull shouldBe true
            inherited["effective"].let {
                it["closed"].asBoolean() shouldBe true
                it["minStay"].asInt() shouldBe 3
                it["maxStay"].isNull shouldBe true
                fields(it["fromRoomType"]).shouldBeEmpty()
            }
        }

    @Test
    fun `the reading filters one room type, and the E04 reader applies the same precedence`(): Unit =
        with(iam) {
            val single = rates.rooms.createRoomType()
            val double = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(single, double))
            val day = today.plusDays(4)
            restrictions.update(plan, restrictions.body(from = day, set = mapOf("closed" to true))).ok()
            restrictions.update(plan, restrictions.body(listOf(single), day, set = mapOf("closed" to false))).ok()

            restrictions
                .read(plan, day, day, roomTypeId = double)
                .ok()["items"]
                .single()["roomTypes"]
                .values()
                .map { it["roomTypeId"].asString() } shouldContainExactly listOf(double.toString())
            val effective =
                reader.effective(
                    RatePlanId(plan),
                    listOf(RoomTypeId(single), RoomTypeId(double)),
                    day,
                    day.plusDays(1),
                )
            effective.getValue(RoomTypeId(single) to day).closed shouldBe false
            effective.getValue(RoomTypeId(double) to day).closed shouldBe true
            // A data sem restrição gravada vem livre.
            effective.getValue(RoomTypeId(double) to day.plusDays(1)).closed shouldBe false
            effective.getValue(RoomTypeId(double) to day.plusDays(1)).minStay shouldBe null
        }

    // --- Edição em massa ------------------------------------------------------------------------------------------

    @Test
    fun `a bulk update touches only the dates of the weekdays asked for and only the fields given`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val from = nextMonday()
            val to = from.plusDays(13)

            val weekend =
                restrictions.body(
                    from = from,
                    to = to,
                    set = mapOf("closedToArrival" to true),
                    weekdays = listOf("FRIDAY", "SATURDAY"),
                )
            restrictions.update(plan, weekend).ok().let {
                it["matchedDates"].asInt() shouldBe 4
                it["created"].asInt() shouldBe 4
                it["from"].asString() shouldBe from.plusDays(4).toString()
                it["to"].asString() shouldBe from.plusDays(12).toString()
                fields(it["fields"]) shouldContainExactly listOf("CLOSED_TO_ARRIVAL")
            }
            restrictions.byDate(plan, from, to).keys.sorted() shouldContainExactly
                listOf(4L, 5L, 11L, 12L).map(from::plusDays)

            // A estadia mínima no período inteiro não apaga o CTA do fim de semana (edição parcial).
            restrictions.update(plan, restrictions.body(from = from, to = to, set = mapOf("minStay" to 2))).ok().let {
                it["created"].asInt() shouldBe 10
                it["changed"].asInt() shouldBe 4
            }
            val all = restrictions.byDate(plan, from, to)
            all.size shouldBe 14
            all.forEach { (date, item) ->
                item["general"]["minStay"].asInt() shouldBe 2
                item["general"]["closedToArrival"].isNull shouldBe
                    (date.dayOfWeek !in setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
            }
        }

    @Test
    fun `a bulk clear takes out only the fields asked for, and a row left empty goes away`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val from = nextMonday()
            val to = from.plusDays(13)
            restrictions
                .update(
                    plan,
                    restrictions.body(
                        from = from,
                        to = to,
                        set = mapOf("closedToArrival" to true),
                        weekdays = listOf("FRIDAY", "SATURDAY"),
                    ),
                ).ok()
            restrictions.update(plan, restrictions.body(from = from, to = to, set = mapOf("minStay" to 2))).ok()

            // Retirar o CTA só aos sábados e, depois, a estadia mínima nas segundas: a linha vazia sai.
            restrictions
                .update(
                    plan,
                    restrictions.body(
                        from = from,
                        to = to,
                        clear = listOf("CLOSED_TO_ARRIVAL"),
                        weekdays = listOf("SATURDAY"),
                    ),
                ).ok()["changed"]
                .asInt() shouldBe 2
            restrictions
                .update(
                    plan,
                    restrictions.body(from = from, to = to, clear = listOf("MIN_STAY"), weekdays = listOf("MONDAY")),
                ).ok()["removed"]
                .asInt() shouldBe 2
            rowCount(plan) shouldBe 12
            restrictions.byDate(plan, from, to).let { left ->
                left.keys shouldBe (0L..13L).map(from::plusDays).filter { it.dayOfWeek != DayOfWeek.MONDAY }.toSet()
                left.getValue(from.plusDays(4))["general"]["closedToArrival"].asBoolean() shouldBe true
                left.getValue(from.plusDays(5))["general"]["closedToArrival"].isNull shouldBe true
            }

            // Repetir a mesma edição não muda nada (nem trilha, nem evento).
            restrictions
                .update(
                    plan,
                    restrictions.body(from = from, to = to, set = mapOf("minStay" to 2), weekdays = listOf("TUESDAY")),
                ).ok()
                .let {
                    (it["created"].asInt() + it["changed"].asInt() + it["removed"].asInt()) shouldBe 0
                    it["from"].isNull shouldBe true
                }
        }

    @Test
    fun `invalid or negative values and a minimum above the maximum are refused with a stable code and field`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(2)

            fun refused(
                set: Map<String, Any?>,
                code: String,
                clear: List<String> = emptyList(),
            ): String =
                fieldOf(
                    restrictions
                        .update(
                            plan,
                            restrictions.body(from = day, set = set, clear = clear),
                        ).problem(400, code),
                )

            refused(mapOf("minStay" to 5, "maxStay" to 4), "RESTRICTION_RANGE_INVALID") shouldBe "set.maxStay"
            refused(mapOf("minAdvanceDays" to 30, "maxAdvanceDays" to 7), "RESTRICTION_RANGE_INVALID") shouldBe
                "set.maxAdvanceDays"
            refused(mapOf("minStay" to -1), "RESTRICTION_INVALID") shouldBe "set.minStay"
            refused(mapOf("maxStay" to 0), "RESTRICTION_INVALID") shouldBe "set.maxStay"
            refused(mapOf("minAdvanceDays" to -3), "RESTRICTION_INVALID") shouldBe "set.minAdvanceDays"
            refused(mapOf("maxAdvanceDays" to 731), "RESTRICTION_INVALID") shouldBe "set.maxAdvanceDays"
            refused(mapOf("maxStay" to 366), "RESTRICTION_INVALID") shouldBe "set.maxStay"
            refused(mapOf("minStay" to 2), "RESTRICTION_INVALID", listOf("MIN_STAY")) shouldBe "clear"
            refused(emptyMap(), "RESTRICTION_INVALID") shouldBe "set"
            rowCount(plan) shouldBe 0
        }

    @Test
    fun `a minimum above the maximum with what is recorded, in the row or across general and type, is a 422`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(2)

            restrictions.update(plan, restrictions.body(from = day, set = mapOf("maxStay" to 2))).ok()
            // A mesma linha: o pedido muda só o mínimo.
            restrictions
                .update(plan, restrictions.body(from = day, set = mapOf("minStay" to 3)))
                .problem(422, "RESTRICTION_RANGE_CONFLICT")
            // A efetiva do tipo: o mínimo dele com o máximo da geral.
            restrictions
                .update(plan, restrictions.body(listOf(roomType), day, set = mapOf("minStay" to 3)))
                .problem(422, "RESTRICTION_RANGE_CONFLICT")
            // O tipo que informa também o próprio máximo fica coerente (o dele prevalece sobre o da geral).
            restrictions
                .update(plan, restrictions.body(listOf(roomType), day, set = mapOf("minStay" to 3, "maxStay" to 4)))
                .ok()
            restrictions.update(plan, restrictions.body(from = day, set = mapOf("minAdvanceDays" to 10))).ok()
            restrictions
                .update(plan, restrictions.body(listOf(roomType), day, set = mapOf("maxAdvanceDays" to 9)))
                .problem(422, "RESTRICTION_RANGE_CONFLICT")
            // Retirar o máximo do tipo deixaria a efetiva com 3 noites mínimas e 2 máximas (o máximo da geral).
            restrictions
                .update(plan, restrictions.body(listOf(roomType), day, clear = listOf("MAX_STAY")))
                .problem(422, "RESTRICTION_RANGE_CONFLICT")
            rowCount(plan) shouldBe 2
            restrictions.effective(plan, roomType, day)!!.let {
                it["minStay"].asInt() shouldBe 3
                it["maxStay"].asInt() shouldBe 4
                it["minAdvanceDays"].asInt() shouldBe 10
            }
        }

    @Test
    fun `bulk edits refuse periods above 366 days, in the past, without dates, and room types out of the plan`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val closed = mapOf("closed" to true)

            fieldOf(
                restrictions
                    .update(plan, restrictions.body(from = today, to = today.plusDays(366), set = closed))
                    .problem(400, "RESTRICTION_PERIOD_TOO_LONG"),
            ) shouldBe "to"
            restrictions
                .update(plan, restrictions.body(from = today, to = today.plusDays(365), set = closed))
                .ok()["created"]
                .asInt() shouldBe 366
            fieldOf(
                restrictions
                    .update(plan, restrictions.body(from = today, to = today.minusDays(1), set = closed))
                    .problem(400, "RESTRICTION_PERIOD_INVALID"),
            ) shouldBe "to"
            restrictions
                .update(plan, restrictions.body(from = today.minusDays(1), to = today, set = closed))
                .problem(422, "RESTRICTION_PERIOD_IN_PAST")
            val monday = nextMonday()
            val sundays =
                restrictions.body(
                    from = monday,
                    to = monday.plusDays(1),
                    set = closed,
                    weekdays = listOf("SUNDAY"),
                )
            fieldOf(restrictions.update(plan, sundays).problem(400, "RESTRICTION_PERIOD_INVALID")) shouldBe "weekdays"
            fieldOf(
                restrictions
                    .update(plan, restrictions.body(from = null, to = null, set = closed))
                    .problem(400, "RESTRICTION_SELECTION_INVALID"),
            ) shouldBe "seasonId"
            restrictions
                .update(plan, restrictions.body(listOf(rates.rooms.createRoomType()), today, set = closed))
                .problem(422, "ROOM_TYPE_NOT_IN_PLAN")
            restrictions
                .update(UUID.randomUUID(), restrictions.body(from = today, set = closed))
                .problem(404, "RATE_PLAN_NOT_FOUND")
            restrictions.read(plan, today, today.plusDays(366)).problem(400, "RESTRICTION_PERIOD_TOO_LONG")
            restrictions.read(plan, today, today.minusDays(1)).problem(400, "RESTRICTION_PERIOD_INVALID")
        }

    @Test
    fun `a season is a period shortcut for restrictions, cut at the business date`(): Unit =
        with(iam) {
            val body =
                mapOf(
                    "name" to rates.uniqueName("Réveillon"),
                    "startsOn" to today.minusDays(2),
                    "endsOn" to today.plusDays(4),
                )
            val created = call(POST, "/api/v1/seasons", asAdmin(), body).expectStatus(201)!!
            val season = UUID.fromString(created["id"].asString())
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))

            val bySeason = restrictions.body(listOf(roomType), null, null, mapOf("minStay" to 4), seasonId = season)
            val result = restrictions.update(plan, bySeason).ok()
            result["matchedDates"].asInt() shouldBe 5
            result["from"].asString() shouldBe today.toString()
            result["to"].asString() shouldBe today.plusDays(4).toString()
            restrictions.byDate(plan, today.minusDays(2), today.plusDays(9)).keys.sorted() shouldContainExactly
                (0L..4L).map(today::plusDays)
            fieldOf(
                restrictions
                    .update(plan, restrictions.body(from = today, set = mapOf("closed" to true), seasonId = season))
                    .problem(400, "RESTRICTION_SELECTION_INVALID"),
            ) shouldBe "seasonId"
        }

    @Test
    fun `restrictions belong to each plan, a derived plan included, and are not inherited from the base`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val base = rates.createPlanFor(listOf(roomType))
            val derived = rates.createPlanFor(listOf(roomType))
            prices.derive(derived, prices.derivation("PERCENT", base, percent = "-10")).ok()
            val day = today.plusDays(6)

            restrictions.update(base, restrictions.body(from = day, set = mapOf("closed" to true))).ok()
            restrictions.byDate(derived, day, day).keys.shouldBeEmpty()
            restrictions.update(derived, restrictions.body(listOf(roomType), day, set = mapOf("minStay" to 2))).ok()
            restrictions.effective(derived, roomType, day)!!["closed"].asBoolean() shouldBe false
            restrictions.effective(base, roomType, day)!!["minStay"].isNull shouldBe true

            // Um plano inativo também guarda as suas.
            prices.deactivatePlan(base)
            restrictions.update(base, restrictions.body(from = day, set = mapOf("closedToDeparture" to true))).ok()
        }

    // --- Permissões e trilha --------------------------------------------------------------------------------------

    @Test
    fun `reading needs the plans read permission and writing needs the restrictions permission`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val reader = createUserWith(TestGrant(definition(RatesPermissions.PLANS_READ), ALL))
            val day = today.plusDays(3)
            val close = restrictions.body(from = day, set = mapOf("closed" to true))

            restrictions.read(plan, day, day, asTestUser(reader)).ok()
            restrictions.read(plan, day, day, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            restrictions.update(plan, close, asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            val pricesOnly = createUserWith(TestGrant(definition(RatesPermissions.PRICES_MANAGE), ALL))
            restrictions.update(plan, close, asTestUser(pricesOnly)).problem(403, "PERMISSION_DENIED")
            rowCount(plan) shouldBe 0
            val manager = createUserWith(TestGrant(definition(RatesPermissions.RESTRICTIONS_MANAGE), ALL))
            restrictions.update(plan, close, asTestUser(manager)).ok()
            rowCount(plan) shouldBe 1

            val permission = definition(RatesPermissions.RESTRICTIONS_MANAGE)
            permission.code.value shouldBe "rates.restrictions.manage"
            permission.sensitive shouldBe false
            permission.templates.keys shouldBe setOf(RoleTemplate.MANAGER)
        }

    @Test
    fun `each change goes to the trail with the selection and the fields, and a no-op writes nothing`(): Unit =
        with(iam) {
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(2)
            val twoNights = restrictions.body(listOf(roomType), day, day.plusDays(1), mapOf("minStay" to 2))
            restrictions.update(plan, twoNights).ok()
            restrictions.update(plan, twoNights).ok()
            restrictions.update(plan, restrictions.body(listOf(roomType), day, clear = listOf("MIN_STAY"))).ok()

            val entries = trail(plan)
            entries.map { it.first } shouldContainExactly
                listOf("rates.restriction.bulk-update", "rates.restriction.bulk-update")
            entries.first().second.let {
                it["created"].asInt() shouldBe 2
                it["general"].asBoolean() shouldBe false
                it["set"]["minStay"].asInt() shouldBe 2
                fields(it["fields"]) shouldContainExactly listOf("MIN_STAY")
                it["roomTypeIds"].values().map { id -> id.asString() } shouldContainExactly listOf(roomType.toString())
            }
            entries.last().second.let {
                it["removed"].asInt() shouldBe 1
                fields(it["clear"]) shouldContainExactly listOf("MIN_STAY")
            }
        }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** A primeira segunda-feira a partir de amanhã (na data operacional). */
    private fun nextMonday(): LocalDate =
        generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.MONDAY }

    private fun fields(node: JsonNode): List<String> = node.values().map { it.asString() }

    private fun fieldOf(problem: JsonNode): String = problem["errors"].values().single()["field"].asString()

    private fun rowCount(plan: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM rates.rate_restriction WHERE rate_plan_id = :plan")
            .param("plan", plan)
            .query(Int::class.java)
            .single()

    private fun trail(plan: UUID): List<Pair<String, JsonNode>> =
        jdbc
            .sql(
                "SELECT action, after_state::text FROM audit.audit_entry " +
                    "WHERE entity_type = 'rates.restriction' AND entity_id = :id ORDER BY occurred_at, id",
            ).param("id", plan.toString())
            .query { rs, _ -> rs.getString(1) to jsonMapper.readTree(rs.getString(2)) }
            .list()
}

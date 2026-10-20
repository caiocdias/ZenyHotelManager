package com.zeny.hotel.rates

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.function.Supplier
import kotlin.concurrent.thread

/**
 * `RestrictionsChanged` pelo outbox (M4 E03): numa gravação de uma data e numa em massa por dias da semana, com o
 * plano, os tipos, os campos e o intervalo; nenhuma publicação sem mudança nem com a edição recusada. E o que o banco
 * garante: a combinação geral × tipo conferida com a linha do plano travada (duas edições simultâneas nunca gravam o
 * mínimo acima do máximo) e os `CHECK` da tabela.
 */
@IntegrationTest
@EnableScenarios
@Import(IamTestConfiguration::class, RatesChangedListenerConfiguration::class)
class RestrictionsEventsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
) {
    private val rates = RatesTestSupport(iam)
    private val restrictions = RestrictionsTestSupport(iam)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    @Test
    fun `a single-date edit publishes RestrictionsChanged through the outbox with the plan, types and fields`(
        scenario: Scenario,
    ) {
        val single = rates.rooms.createRoomType()
        val double = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(single, double))
        val day = today.plusDays(5)

        scenario
            .stimulate(
                Supplier {
                    with(iam) {
                        restrictions
                            .update(
                                plan,
                                restrictions.body(
                                    listOf(single),
                                    day,
                                    set =
                                        mapOf(
                                            "closedToDeparture" to true,
                                            "minStay" to 2,
                                        ),
                                ),
                            ).ok()
                    }
                },
            ).andWaitForEventOfType(RestrictionsChanged::class.java)
            .matching { it.ratePlanId == plan }
            .toArriveAndVerify { event ->
                event.general shouldBe false
                event.roomTypeIds shouldContainExactly listOf(single)
                event.fields shouldContainExactly
                    listOf(RestrictionField.CLOSED_TO_DEPARTURE, RestrictionField.MIN_STAY)
                event.from shouldBe day
                event.to shouldBe day
            }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            RestrictionsChangedEvents.received.any { it.ratePlanId == plan }
        }
        publications(plan).size shouldBe 1
    }

    @Test
    fun `a bulk edit by weekdays publishes one RestrictionsChanged with all the plan types for the general one`(
        scenario: Scenario,
    ) {
        val single = rates.rooms.createRoomType()
        val double = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(single, double))
        val from = generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value == 1 }
        val to = from.plusDays(20)

        scenario
            .stimulate(
                Supplier {
                    with(iam) {
                        restrictions
                            .update(
                                plan,
                                restrictions.body(
                                    from = from,
                                    to = to,
                                    set = mapOf("closed" to true),
                                    weekdays = listOf("TUESDAY", "THURSDAY"),
                                ),
                            ).ok()
                    }
                },
            ).andWaitForEventOfType(RestrictionsChanged::class.java)
            .matching { it.ratePlanId == plan }
            .toArriveAndVerify { event ->
                event.general shouldBe true
                event.roomTypeIds shouldContainExactlyInAnyOrder listOf(single, double)
                event.fields shouldContainExactly listOf(RestrictionField.CLOSED)
                event.from shouldBe from.plusDays(1) // a primeira terça
                event.to shouldBe from.plusDays(17) // a última quinta
            }
        publications(plan).size shouldBe 1
    }

    @Test
    fun `an edit that changes nothing, or is refused, publishes nothing`() {
        val roomType = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(roomType))
        val day = today.plusDays(9)
        with(iam) {
            restrictions.update(plan, restrictions.body(from = day, set = mapOf("maxStay" to 3))).ok()
            restrictions.update(plan, restrictions.body(from = day, set = mapOf("maxStay" to 3))).ok()
            restrictions.update(plan, restrictions.body(from = day, clear = listOf("MIN_STAY"))).ok()
            restrictions
                .update(plan, restrictions.body(from = day, set = mapOf("minStay" to 4)))
                .problem(422, "RESTRICTION_RANGE_CONFLICT")
            restrictions
                .update(plan, restrictions.body(from = day, set = mapOf("minStay" to 4, "maxStay" to 2)))
                .problem(400, "RESTRICTION_RANGE_INVALID")
        }
        publications(plan).size shouldBe 1
    }

    @Test
    fun `two simultaneous edits of the general and the type restriction never record a minimum above the maximum`() {
        repeat(ROUNDS) { _ ->
            val roomType = rates.rooms.createRoomType()
            val plan = rates.createPlanFor(listOf(roomType))
            val day = today.plusDays(3)
            val bodies =
                listOf(
                    restrictions.body(from = day, set = mapOf("minStay" to 4)),
                    restrictions.body(listOf(roomType), day, set = mapOf("maxStay" to 2)),
                )
            val start = CountDownLatch(1)
            val results = arrayOfNulls<MockHttpServletResponse>(2)
            val threads =
                bodies.mapIndexed { index, body ->
                    thread {
                        start.await()
                        results[index] = restrictions.update(plan, body)
                    }
                }
            start.countDown()
            threads.forEach { it.join() }

            results.map { it!!.status }.sorted() shouldContainExactly listOf(200, 422)
            rowCount(plan) shouldBe 1
        }
    }

    @Test
    fun `the database refuses an empty row, a minimum above the maximum, out of range values and a 2nd general row`() {
        val roomType = rates.rooms.createRoomType()
        val plan = rates.createPlanFor(listOf(roomType))
        val day = today.plusDays(1)

        fun insert(
            type: UUID?,
            columns: String,
            values: String,
        ) = jdbc
            .sql(
                "INSERT INTO rates.rate_restriction (rate_plan_id, room_type_id, stay_date, $columns, updated_at) " +
                    "VALUES (:plan, CAST(:type AS uuid), :day, $values, now())",
            ).param("plan", plan)
            .param("type", type?.toString())
            .param("day", day)
            .update()

        shouldThrow<DataIntegrityViolationException> { insert(null, "closed", "NULL") }
        shouldThrow<DataIntegrityViolationException> { insert(null, "min_stay, max_stay", "5, 4") }
        shouldThrow<DataIntegrityViolationException> { insert(null, "min_advance_days, max_advance_days", "9, 8") }
        shouldThrow<DataIntegrityViolationException> { insert(null, "min_stay", "-1") }
        shouldThrow<DataIntegrityViolationException> { insert(roomType, "max_advance_days", "731") }
        insert(null, "closed", "true")
        insert(roomType, "closed", "false")
        shouldThrow<DataIntegrityViolationException> { insert(null, "min_stay", "2") } // a geral é uma por data
        rowCount(plan) shouldBe 2
        RestrictionsChangedEvents.received.filter { it.ratePlanId == plan }.shouldBeEmpty()
    }

    private fun rowCount(plan: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM rates.rate_restriction WHERE rate_plan_id = :plan")
            .param("plan", plan)
            .query(Int::class.java)
            .single()

    private fun publications(plan: UUID): List<String> =
        jdbc
            .sql(
                "SELECT serialized_event FROM shared.event_publication WHERE event_type = :type " +
                    "AND serialized_event LIKE :plan AND listener_id LIKE :listener",
            ).param("type", RestrictionsChanged::class.java.name)
            .param("plan", "%\"ratePlanId\":\"$plan\"%")
            .param("listener", "%RestrictionsChangedProbeListener%")
            .query(String::class.java)
            .list()
            .filterNotNull()

    private companion object {
        const val ROUNDS = 5
        const val TIMEOUT_SECONDS = 10L
    }
}

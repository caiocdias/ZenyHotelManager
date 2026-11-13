package com.zeny.hotel.endofday

import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * A etapa real `no-show` (M6 E11, 100) no contexto das etapas reais: só a confirmada com chegada pendente vira
 * no-show, com a multa da política copiada e a UH solta no mesmo commit; a recusa de um item vira a pendência
 * `NO_SHOW_FAILED` e o fechamento segue. Cada teste termina com [EndOfDayTestRuns.settle] e afirma só sobre os
 * próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class NoShowStepTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired iam: IamTestSupport,
    @Autowired roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val iamSupport = iam
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, roomsApi, jsonMapper)

    @Test
    @Suppress("LongMethod") // o cenário com quatro reservas e as conferências de cada uma
    fun `only confirmed pending arrivals become no-show with the copied penalty and free the room`() {
        val date = hotel.currentBusinessDate()
        val (type, rooms) = inventory.roomTypeWithRooms(3, date)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(10))
        val pending = created(type, plan.id, date, date.plusDays(2))
        assign(pending.id(), rooms.first())
        val provisionalPlan = support.plan(listOf(type), guarantee = "DEPOSIT")
        support.setPrices(provisionalPlan.id, listOf(type), date, date.plusDays(10))
        val provisional = created(type, provisionalPlan.id, date, date.plusDays(1))
        val future = created(type, plan.id, date.plusDays(1), date.plusDays(2))
        val stay = frontdesk.stay()
        val hosted = stay.checkedIn()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            stepOf(run, "no-show") shouldBe ("COMPLETED" to "DONE")
            support.status(pending.id()) shouldBe "NO_SHOW"
            support.postings(pending.folio()).map { it["amount"].toString() } shouldContainExactly listOf("150.00")
            jdbc
                .sql(
                    "SELECT count(*) FROM reservations.room_assignment " +
                        "WHERE reservation_id = :id AND released_at IS NULL",
                ).param("id", pending.id())
                .query(Int::class.java)
                .single() shouldBe 0
            support.reservation(pending.id())["reservation"]["penaltiesCharged"].asString() shouldBe "150.00"
            support.status(provisional.id()) shouldBe "PROVISIONAL"
            support.status(future.id()) shouldBe "CONFIRMED"
            support.status(hosted.id) shouldBe "IN_HOUSE"
            issuesOf(run, listOf(pending, provisional, future)).shouldBeEmpty()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a refused item becomes an issue and the run goes on`() {
        val date = hotel.currentBusinessDate()
        val (type, rooms) = inventory.roomTypeWithRooms(2, date)
        val plan = support.plan(listOf(type), cancellationBody = fixed("150.00"))
        support.setPrices(plan.id, listOf(type), date, date.plusDays(10))
        val refused = created(type, plan.id, date, date.plusDays(2))
        val accepted = created(type, plan.id, date, date.plusDays(2))
        assign(refused.id(), rooms[0])
        assign(accepted.id(), rooms[1])
        with(support.billing) { call(POST, "/api/v1/folios/${refused.folio()}/close", support.admin).expect(200) }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            statusOf(run) shouldBe "COMPLETED"
            stepOf(run, "no-show") shouldBe ("COMPLETED" to "DONE")
            support.status(refused.id()) shouldBe "CONFIRMED"
            support.status(accepted.id()) shouldBe "NO_SHOW"
            issuesOf(run, listOf(refused, accepted)) shouldContainExactly
                listOf("NO_SHOW_FAILED" to refused.id().toString())
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun fixed(amount: String) =
        support.rates.cancellationBody(
            deadlineHours = 8760,
            penaltyType = "FIXED_AMOUNT",
            penaltyNights = null,
            penaltyAmount = amount,
        )

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
        departure: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, departure)),
        )["reservations"][0]

    private fun assign(
        id: UUID,
        room: UUID,
    ) = with(
        iamSupport,
    ) { call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to room)).ok() }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private fun statusOf(run: com.zeny.hotel.endofday.domain.EndOfDayRunId): String =
        jdbc
            .sql("SELECT status FROM endofday.run WHERE id = :id")
            .param("id", run.value)
            .query(String::class.java)
            .single()

    private fun stepOf(
        run: com.zeny.hotel.endofday.domain.EndOfDayRunId,
        name: String,
    ): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = :name")
            .param("id", run.value)
            .param("name", name)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    /** As pendências `NO_SHOW_FAILED` da execução sobre as reservas deste teste, como `kind to referência`. */
    private fun issuesOf(
        run: com.zeny.hotel.endofday.domain.EndOfDayRunId,
        reservations: List<JsonNode>,
    ): List<Pair<String, String?>> {
        val ids = reservations.map { it.id().toString() }.toSet()
        return jdbc
            .sql("SELECT kind, reference FROM endofday.run_issue WHERE run_id = :id ORDER BY created_at, id")
            .param("id", run.value)
            .query { rs, _ -> rs.getString("kind") to rs.getString("reference") }
            .list()
            .filter { it.second in ids }
    }
}

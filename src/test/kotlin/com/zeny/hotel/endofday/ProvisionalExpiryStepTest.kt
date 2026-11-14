package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * A etapa real `provisional-expiry` (M6 E11, 500, RN-23) no contexto das etapas reais: a pré-reserva com a validade
 * vencida é cancelada como `EXPIRED`, sem multa e devolvendo o inventário; a ainda válida fica intacta; a que o job já
 * expirou é pulada. O relógio avança pelo [MutableClock] e volta no `finally`. Cada teste termina com
 * [EndOfDayTestRuns.settle] e afirma só sobre os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class ProvisionalExpiryStepTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val reservations: ReservationsApi,
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory

    @Test
    fun `expired provisional reservations are cancelled as expired, without penalty`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(3, date)
        val plan = depositPlan(type, date)
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val due = created(type, plan, arrival)
            clock.advance(Duration.ofHours(1))
            val valid = created(type, plan, arrival)
            val sold = inventory.row(type, arrival)!!.sold
            clock.advance(hotel.operationalSettings().preReservationExpiry.minusHours(1))

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run) shouldBe ("COMPLETED" to "DONE")
            val expired = support.reservation(due.id())["reservation"]
            expired["status"].asString() shouldBe "CANCELLED"
            expired["cancellation"]["reason"].asString() shouldBe "EXPIRED"
            expired["cancellation"]["penalty"]["amount"].asString() shouldBe "0.00"
            support.postings(due.folio()).shouldBeEmpty()
            support.status(valid.id()) shouldBe "PROVISIONAL"
            inventory.row(type, arrival)!!.sold shouldBe sold - 1
            issueCount(run) shouldBe 0
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a reservation already expired by the job is skipped`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(2, date)
        val plan = depositPlan(type, date)
        val arrival = date.plusDays(3)
        val start = clock.instant()
        try {
            val due = created(type, plan, arrival)
            clock.advance(hotel.operationalSettings().preReservationExpiry)
            reservations.expireProvisional(ReservationId(due.id())) shouldBe true

            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run).first shouldBe "COMPLETED"
            issueCount(run) shouldBe 0
            support.status(due.id()) shouldBe "CANCELLED"
            support.historyKinds(due.id()) shouldContainExactly listOf("CANCEL")
            reservations.expireProvisional(ReservationId(due.id())) shouldBe false
        } finally {
            clock.advance(Duration.between(clock.instant(), start))
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun depositPlan(
        type: UUID,
        date: LocalDate,
    ): UUID {
        val plan =
            support.plan(
                listOf(type),
                guarantee = "DEPOSIT",
                cancellationBody = support.rates.cancellationBody(deadlineHours = 8760, penaltyNights = 1),
            )
        support.setPrices(plan.id, listOf(type), date, date.plusDays(20))
        return plan.id
    }

    private fun created(
        type: UUID,
        plan: UUID,
        arrival: LocalDate,
    ): JsonNode =
        support.created(
            support.booking(support.guest(), support.reservation(type, plan, arrival, arrival.plusDays(2))),
        )["reservations"][0]

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private fun JsonNode.folio(): UUID = UUID.fromString(this["folioId"].asString())

    private fun stepOf(run: EndOfDayRunId): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = 'provisional-expiry'")
            .param("id", run.value)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    private fun issueCount(run: EndOfDayRunId): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = 'PROVISIONAL_EXPIRY_FAILED'")
            .param("id", run.value)
            .query(Int::class.java)
            .single()
}

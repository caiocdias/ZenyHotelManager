package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * A etapa real `pending-departures` (M6 E11, 200): cada hospedada com saída da data ou anterior vira a pendência
 * `PENDING_DEPARTURE` com o id da reserva, avisa quem acompanha o fechamento e nunca faz check-out.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class PendingDeparturesStepTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired roomsApi: RoomsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotel, roomsApi, jsonMapper)

    @Test
    fun `a pending departure raises an issue and is never checked out automatically`() {
        val date = hotel.currentBusinessDate()
        val reader =
            iam.createUserWith(
                TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
            )
        val outsider = iam.createUser()
        val stay = frontdesk.stay(rooms = 2)
        val today = stay.checkedIn(departure = date, room = stay.rooms[0])
        val overdue = stay.checkedIn(room = stay.rooms[1])
        jdbc
            .sql("UPDATE reservations.reservation SET arrival = :arrival, departure = :departure WHERE id = :id")
            .param("arrival", date.minusDays(2))
            .param("departure", date.minusDays(1))
            .param("id", overdue.id)
            .update()
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run) shouldBe ("COMPLETED" to "DONE")
            issuesOf(run, listOf(today.id, overdue.id)) shouldContainExactlyInAnyOrder
                listOf(
                    "PENDING_DEPARTURE" to today.id.toString(),
                    "PENDING_DEPARTURE" to overdue.id.toString(),
                )
            support.status(today.id) shouldBe "IN_HOUSE"
            support.status(overdue.id) shouldBe "IN_HOUSE"
            occupiedAssignments(listOf(today.id, overdue.id)) shouldBe 2
            // um aviso por tipo, com a contagem (as duas desta data e as que o banco isolado já tinha)
            val expected = "PENDING_DEPARTURE: ${departureIssues(run)} pendência(s)"
            noticeBodies(reader.id.value).filter { it.startsWith("PENDING_DEPARTURE:") } shouldBe listOf(expected)
            noticeBodies(outsider.id.value) shouldBe emptyList()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun occupiedAssignments(ids: List<UUID>): Int =
        jdbc
            .sql(
                "SELECT count(*) FROM reservations.room_assignment " +
                    "WHERE reservation_id IN (:ids) AND released_at IS NULL",
            ).param("ids", ids)
            .query(Int::class.java)
            .single()

    private fun noticeBodies(userId: UUID): List<String> =
        jdbc
            .sql(
                "SELECT body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id ORDER BY id",
            ).param("id", userId)
            .query { rs, _ -> rs.getString("body") }
            .list()

    private fun departureIssues(run: EndOfDayRunId): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = 'PENDING_DEPARTURE'")
            .param("id", run.value)
            .query(Int::class.java)
            .single()

    private fun stepOf(run: EndOfDayRunId): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = 'pending-departures'")
            .param("id", run.value)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    private fun issuesOf(
        run: EndOfDayRunId,
        reservations: List<UUID>,
    ): List<Pair<String, String?>> {
        val ids = reservations.map { it.toString() }.toSet()
        return jdbc
            .sql("SELECT kind, reference FROM endofday.run_issue WHERE run_id = :id")
            .param("id", run.value)
            .query { rs, _ -> rs.getString("kind") to rs.getString("reference") }
            .list()
            .filter { it.second in ids }
    }
}

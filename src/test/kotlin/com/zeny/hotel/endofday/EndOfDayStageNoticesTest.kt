package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * O aviso agrupado de uma etapa REAL com n > 1 pendências do mesmo tipo (M6 E11, Task 19): um aviso por usuário com
 * `endofday.runs.read`, com a contagem e sem as referências, e nenhum para quem não tem a permissão. Usa as recusas do
 * `no-show` (a conta encerrada). Banco isolado; a contagem esperada vem das pendências gravadas na execução, porque o
 * fechamento real age sobre tudo o que os testes anteriores deixaram.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayStageNoticesTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory

    @Test
    fun `several refusals of one real step notify each reader once with the count and nobody else`() {
        val date = hotel.currentBusinessDate()
        val readers = List(2) { reader() }
        val outsider = iam.createUser()
        val (type, rooms) = inventory.roomTypeWithRooms(3, date)
        val plan =
            support.plan(
                listOf(type),
                cancellationBody =
                    support.rates.cancellationBody(
                        deadlineHours = 8760,
                        penaltyType = "FIXED_AMOUNT",
                        penaltyNights = null,
                        penaltyAmount = "150.00",
                    ),
            )
        support.setPrices(plan.id, listOf(type), date, date.plusDays(10))
        val bookings =
            rooms.map { room ->
                val node =
                    support.created(
                        support.booking(support.guest(), support.reservation(type, plan.id, date, date.plusDays(2))),
                    )["reservations"][0]
                val id = UUID.fromString(node["id"].asString())
                with(iam) {
                    call(POST, "/api/v1/reservations/$id/assign-room", support.admin, mapOf("roomId" to room)).ok()
                }
                id to UUID.fromString(node["folioId"].asString())
            }
        val refused = bookings.take(2)
        refused.forEach { (_, folio) ->
            with(support.billing) { call(POST, "/api/v1/folios/$folio/close", support.admin).expect(200) }
        }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            val count = issueCount(run, "NO_SHOW_FAILED")
            count shouldBeGreaterThanOrEqual 2
            readers.forEach { reader ->
                noticesOf(reader.id.value, "NO_SHOW_FAILED") shouldContainExactly
                    listOf("NO_SHOW_FAILED: $count pendência(s)")
            }
            readers.forEach { reader ->
                refused.forEach { (id, _) ->
                    noticesOf(reader.id.value, "NO_SHOW_FAILED").single() shouldNotContain id.toString()
                }
            }
            noticesOf(outsider.id.value, "NO_SHOW_FAILED").shouldBeEmpty()
            bookings.map { support.status(it.first) } shouldContainExactly listOf("CONFIRMED", "CONFIRMED", "NO_SHOW")
            refused.size shouldBe 2
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun reader() =
        iam.createUserWith(
            TestGrant(EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }, ALL),
        )

    private fun issueCount(
        run: EndOfDayRunId,
        kind: String,
    ): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = :kind")
            .param("id", run.value)
            .param("kind", kind)
            .query(Int::class.java)
            .single()

    private fun noticesOf(
        userId: UUID,
        kind: String,
    ): List<String> =
        jdbc
            .sql(
                "SELECT body FROM notifications.notification " +
                    "WHERE type = 'notifications.end-of-day-pending' AND recipient_id = :id AND body LIKE :kind " +
                    "ORDER BY id",
            ).param("id", userId)
            .param("kind", "$kind:%")
            .query { rs, _ -> rs.getString("body") }
            .list()
}

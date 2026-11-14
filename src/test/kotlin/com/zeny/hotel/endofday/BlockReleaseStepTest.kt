package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * A etapa real `block-release` (M6 E11, 400, RN-16): o bloqueio cujo corte chegou é liberado e devolve ao inventário só
 * as unidades que não viraram reserva; o de corte posterior fica intacto. Cada teste termina com
 * [EndOfDayTestRuns.settle] e afirma só sobre os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class BlockReleaseStepTest(
    @Autowired private val hotel: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory

    @Test
    fun `blocks past the cutoff return unused rooms to inventory`() {
        val date = hotel.currentBusinessDate()
        val (type, _) = inventory.roomTypeWithRooms(5, date)
        val plan = support.plan(listOf(type))
        val night = date.plusDays(7)
        val due = block(plan.id, type, night, 3, cutoff = date)
        val later = block(plan.id, type, night, 1, cutoff = date.plusDays(1))
        with(support.billing) {
            support
                .pickUp(due, support.blockReservation(type, night, night.plusDays(1)), support.guest())
                .expect(201)
        }
        val before = inventory.row(type, night)!!
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run) shouldBe ("COMPLETED" to "DONE")
            statusOf(due) shouldBe "RELEASED"
            statusOf(later) shouldBe "ACTIVE"
            support.allocationRow(due, type, night) shouldBe Triple(3, 1, 2)
            support.allocationRow(later, type, night) shouldBe Triple(1, 0, 0)
            inventory.row(type, night)!!.blocked shouldBe before.blocked - 2
            issueCount(run) shouldBe 0
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun block(
        plan: UUID,
        type: UUID,
        night: LocalDate,
        quantity: Int,
        cutoff: LocalDate,
    ): UUID =
        UUID.fromString(
            support
                .createdBlock(
                    support.blockBody(
                        plan,
                        cutoff,
                        support.allocation(type, night, night.plusDays(1), quantity),
                        holderGuest = support.guest(),
                    ),
                )["id"]
                .asString(),
        )

    private fun statusOf(block: UUID): String =
        jdbc
            .sql("SELECT status FROM reservations.room_block WHERE id = :id")
            .param("id", block)
            .query(String::class.java)
            .single()

    private fun stepOf(run: EndOfDayRunId): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = 'block-release'")
            .param("id", run.value)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    private fun issueCount(run: EndOfDayRunId): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = 'BLOCK_RELEASE_FAILED'")
            .param("id", run.value)
            .query(Int::class.java)
            .single()
}

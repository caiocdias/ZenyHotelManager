package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
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
 * As etapas reais `card-pre-authorizations` (600) e `occupied-rooms-dirty` (700, RN-09) do M6 E11: a UH ocupada vira
 * Suja com a origem do fechamento e a vaga fica como está; a UH vaga entre a leitura e o item não gera pendência; a
 * etapa das pré-autorizações é "não aplicável" até o M7. Cada teste termina com [EndOfDayTestRuns.settle] e afirma só
 * sobre os próprios dados.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayRealStepsTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class RoomsAndPreAuthorizationStepsTest(
    @Autowired private val rooms: RoomsApi,
    @Autowired private val probe: RoomsMarkProbe,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val context: ApplicationContext,
    @Autowired iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)

    @Test
    fun `occupied rooms become dirty with the end-of-day source and vacant ones are left alone`() {
        val type = support.inventory.rooms.createRoomType()
        val occupiedClean = cleanRoom(type, occupied = true)
        val vacantClean = cleanRoom(type, occupied = false)
        val occupiedDirty = cleanRoom(type, occupied = true).also { rooms.markDirty(it, RoomStatusSource.MANUAL) }
        val dirtyChangesBefore = changes(occupiedDirty)
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run, "occupied-rooms-dirty") shouldBe ("COMPLETED" to "DONE")
            rooms.status(occupiedClean)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            changes(occupiedClean).last() shouldBe ("HOUSEKEEPING_STATUS" to "END_OF_DAY")
            rooms.status(vacantClean)!!.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            changes(vacantClean).none { it.second == "END_OF_DAY" } shouldBe true
            rooms.status(occupiedDirty)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            changes(occupiedDirty) shouldBe dirtyChangesBefore
            issueCount(run) shouldBe 0
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a room vacated between listing and marking is still marked dirty without error`() {
        val type = support.inventory.rooms.createRoomType()
        val room = cleanRoom(type, occupied = true)
        probe.beforeMarkDirty { api, id ->
            if (id == room) api.markVacant(id, RoomStatusSource.CHECK_OUT)
        }
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run, "occupied-rooms-dirty") shouldBe ("COMPLETED" to "DONE")
            rooms.status(room)!!.occupancy shouldBe OccupancyStatus.VACANT
            rooms.status(room)!!.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
            issueCount(run) shouldBe 0
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `the pre-authorization step records not applicable`() {
        try {
            val run = EndOfDayTestRuns.closeCurrentDate(context)

            stepOf(run, "card-pre-authorizations") shouldBe ("COMPLETED" to "NOT_APPLICABLE")
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun cleanRoom(
        type: UUID,
        occupied: Boolean,
    ): RoomId {
        val room = RoomId(support.inventory.rooms.createRoom(type))
        rooms.markClean(room, false, RoomStatusSource.MANUAL)
        if (occupied) rooms.markOccupied(room, 1, RoomStatusSource.CHECK_IN)
        return room
    }

    private fun changes(room: RoomId): List<Pair<String, String>> =
        jdbc
            .sql(
                "SELECT field, source FROM rooms.room_status_change WHERE room_id = :id " +
                    "AND field = 'HOUSEKEEPING_STATUS' ORDER BY changed_at, id",
            ).param("id", room.value)
            .query { rs, _ -> rs.getString("field") to rs.getString("source") }
            .list()

    private fun stepOf(
        run: EndOfDayRunId,
        name: String,
    ): Pair<String, String?> =
        jdbc
            .sql("SELECT status, result FROM endofday.run_step WHERE run_id = :id AND name = :name")
            .param("id", run.value)
            .param("name", name)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .single()

    private fun issueCount(run: EndOfDayRunId): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run_issue WHERE run_id = :id AND kind = 'ROOM_DIRTY_FAILED'")
            .param("id", run.value)
            .query(Int::class.java)
            .single()
}

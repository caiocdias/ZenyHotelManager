package com.zeny.hotel.rooms

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/**
 * A operação condicional da inspeção na `RoomsApi` (M6 E05, Task 1, D5): `markInspectedIfPending` leva a Inspecionada
 * só a UH Limpa **com** inspeção pendente, decidindo com a linha travada, e devolve o status antes e depois (com o
 * `readyForCheckIn` de cada um); a UH que perdeu a pendência (Suja, Limpa sem pendência, Inspecionada) não muda e não
 * é recusada (Review Focus 1). Task 2: `rejectInspectionIfPending` (Limpa com pendência → Suja; sem pendência, nada
 * muda, Review Focus 2), `markInspectedIfClean` (a inspeção voluntária: Limpa sem pendência → Inspecionada) e a
 * variante `markCleanIfDirtyWithStatus` da conclusão da E04, todas com o status antes e depois.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class RoomsApiInspectionOperationsTest(
    @Autowired iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
) {
    private val rooms = RoomsTestSupport(iam)

    private fun newRoom() = RoomId(rooms.createRoom(rooms.createRoomType()))

    private fun status(room: RoomId) = roomsApi.status(room).shouldNotBeNull()

    @Test
    fun `markInspectedIfPending inspects only a clean room with the inspection pending, with before and after`() {
        val pending = newRoom()
        roomsApi.markClean(pending, requiresInspection = true, source = RoomStatusSource.MANUAL)
        val before = status(pending)

        val outcome = roomsApi.markInspectedIfPending(pending, RoomStatusSource.TASK)

        outcome.changed shouldBe true
        outcome.before shouldBe before
        outcome.before.readyForCheckIn shouldBe false
        // O instante em memória tem nanossegundos; o gravado, microssegundos.
        outcome.after.copy(changedAt = Instant.EPOCH) shouldBe status(pending).copy(changedAt = Instant.EPOCH)
        outcome.after.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
        outcome.after.inspectionPending shouldBe false
        outcome.after.readyForCheckIn shouldBe true
        historySources(pending).last() shouldBe "TASK"
    }

    @Test
    fun `a room that lost the pending inspection does not change and is not refused (Review Focus 1)`() {
        val dirty = newRoom()
        val cleanWithout = newRoom()
        roomsApi.markClean(cleanWithout, requiresInspection = false, source = RoomStatusSource.MANUAL)
        val inspected = newRoom()
        roomsApi.markClean(inspected, requiresInspection = true, source = RoomStatusSource.MANUAL)
        roomsApi.markInspected(inspected, RoomStatusSource.MANUAL)

        listOf(dirty, cleanWithout, inspected).forEach { room ->
            withClue(room) {
                val before = status(room)

                val outcome = roomsApi.markInspectedIfPending(room, RoomStatusSource.TASK)

                outcome.changed shouldBe false
                outcome.before shouldBe before
                outcome.after shouldBe before
                status(room) shouldBe before
                historySources(room).none { it == "TASK" } shouldBe true
            }
        }
        status(cleanWithout).readyForCheckIn shouldBe true
        status(dirty).readyForCheckIn shouldBe false
    }

    @Test
    fun `an occupied room with the inspection pending is inspected but stays not ready`() {
        val occupied = newRoom()
        roomsApi.markClean(occupied, requiresInspection = true, source = RoomStatusSource.MANUAL)
        roomsApi.markOccupied(occupied, 2, RoomStatusSource.CHECK_IN)

        val outcome = roomsApi.markInspectedIfPending(occupied, RoomStatusSource.TASK)

        outcome.changed shouldBe true
        outcome.after.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
        outcome.before.readyForCheckIn shouldBe false
        outcome.after.readyForCheckIn shouldBe false
    }

    @Test
    fun `rejectInspectionIfPending dirties only a clean room with the inspection pending, with before and after`() {
        val pending = newRoom()
        roomsApi.markClean(pending, requiresInspection = true, source = RoomStatusSource.MANUAL)
        val before = status(pending)

        val outcome = roomsApi.rejectInspectionIfPending(pending, RoomStatusSource.TASK)

        outcome.changed shouldBe true
        outcome.before shouldBe before
        outcome.after.copy(changedAt = Instant.EPOCH) shouldBe status(pending).copy(changedAt = Instant.EPOCH)
        outcome.after.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        outcome.after.inspectionPending shouldBe false
        historySources(pending).last() shouldBe "TASK"
    }

    @Test
    fun `rejectInspectionIfPending leaves a room without the pending inspection as it is (Review Focus 2)`() {
        val dirty = newRoom()
        val cleanWithout = newRoom()
        roomsApi.markClean(cleanWithout, requiresInspection = false, source = RoomStatusSource.MANUAL)
        val inspected = newRoom()
        roomsApi.markClean(inspected, requiresInspection = true, source = RoomStatusSource.MANUAL)
        roomsApi.markInspected(inspected, RoomStatusSource.MANUAL)

        listOf(dirty, cleanWithout, inspected).forEach { room ->
            withClue(room) {
                val before = status(room)

                val outcome = roomsApi.rejectInspectionIfPending(room, RoomStatusSource.TASK)

                outcome.changed shouldBe false
                outcome.after shouldBe before
                status(room) shouldBe before
                historySources(room).none { it == "TASK" } shouldBe true
            }
        }
    }

    @Test
    fun `an occupied clean room with the inspection pending goes dirty on the rejection`() {
        val occupied = newRoom()
        roomsApi.markClean(occupied, requiresInspection = true, source = RoomStatusSource.MANUAL)
        roomsApi.markOccupied(occupied, 2, RoomStatusSource.CHECK_IN)

        val outcome = roomsApi.rejectInspectionIfPending(occupied, RoomStatusSource.TASK)

        outcome.changed shouldBe true
        outcome.after.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        outcome.after.inspectionPending shouldBe false
        outcome.after.occupancy shouldBe OccupancyStatus.OCCUPIED
        outcome.before.readyForCheckIn shouldBe false
        outcome.after.readyForCheckIn shouldBe false
    }

    @Test
    fun `markInspectedIfClean inspects only a clean room without the pending inspection`() {
        val clean = newRoom()
        roomsApi.markClean(clean, requiresInspection = false, source = RoomStatusSource.MANUAL)
        val before = status(clean)

        val outcome = roomsApi.markInspectedIfClean(clean, RoomStatusSource.TASK)

        outcome.changed shouldBe true
        outcome.before shouldBe before
        outcome.after.housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
        outcome.before.readyForCheckIn shouldBe true
        outcome.after.readyForCheckIn shouldBe true
        status(clean).housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
    }

    @Test
    fun `markInspectedIfClean leaves a dirty, pending or inspected room as it is, with the status that refused it`() {
        val dirty = newRoom()
        val pending = newRoom()
        roomsApi.markClean(pending, requiresInspection = true, source = RoomStatusSource.MANUAL)
        val inspected = newRoom()
        roomsApi.markClean(inspected, requiresInspection = false, source = RoomStatusSource.MANUAL)
        roomsApi.markInspected(inspected, RoomStatusSource.MANUAL)

        listOf(dirty, pending, inspected).forEach { room ->
            withClue(room) {
                val before = status(room)

                val outcome = roomsApi.markInspectedIfClean(room, RoomStatusSource.TASK)

                outcome.changed shouldBe false
                outcome.before shouldBe before
                status(room) shouldBe before
            }
        }
        roomsApi.markInspectedIfClean(pending, RoomStatusSource.TASK).before.inspectionPending shouldBe true
    }

    @Test
    fun `markCleanIfDirtyWithStatus cleans a dirty room and reports the readiness before and after`() {
        val dirty = newRoom()
        val alreadyClean = newRoom()
        roomsApi.markClean(alreadyClean, requiresInspection = false, source = RoomStatusSource.MANUAL)

        val cleaned = roomsApi.markCleanIfDirtyWithStatus(dirty, false, RoomStatusSource.TASK)
        val unchanged = roomsApi.markCleanIfDirtyWithStatus(alreadyClean, true, RoomStatusSource.TASK)

        cleaned.changed shouldBe true
        cleaned.before.readyForCheckIn shouldBe false
        cleaned.after.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
        cleaned.after.readyForCheckIn shouldBe true
        unchanged.changed shouldBe false
        unchanged.after.inspectionPending shouldBe false
    }

    @Test
    fun `a missing room is 404`() {
        shouldThrow<NotFoundException> {
            roomsApi.markInspectedIfPending(RoomId(UUID.randomUUID()), RoomStatusSource.TASK)
        }.code.value shouldBe "ROOM_NOT_FOUND"
        listOf<(RoomId) -> Unit>(
            { roomsApi.rejectInspectionIfPending(it, RoomStatusSource.TASK) },
            { roomsApi.markInspectedIfClean(it, RoomStatusSource.TASK) },
            { roomsApi.markCleanIfDirtyWithStatus(it, false, RoomStatusSource.TASK) },
        ).forEach { operation ->
            shouldThrow<NotFoundException> { operation(RoomId(UUID.randomUUID())) }.code.value shouldBe
                "ROOM_NOT_FOUND"
        }
    }

    private fun historySources(room: RoomId): List<String> =
        jdbc
            .sql("SELECT source FROM rooms.room_status_change WHERE room_id = :id ORDER BY changed_at, id")
            .param("id", room.value)
            .query(String::class.java)
            .list()
            .filterNotNull()
}

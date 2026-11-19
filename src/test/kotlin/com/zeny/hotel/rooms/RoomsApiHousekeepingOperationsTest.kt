package com.zeny.hotel.rooms

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * As operações condicionais da `RoomsApi` para a governança (M6 E04, Task 2): `markCleanIfDirty` (D3) e
 * `setServiceFlagIfOccupied` (D4) decidem com a linha da UH travada e devolvem se mudaram, sem recusar o caso em que
 * nada muda; `recordObservation` grava a observação de ocupação como o endpoint do `rooms` grava (RF-UH-08), com quem
 * observou e quando informados por quem chama.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class RoomsApiHousekeepingOperationsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
) {
    private val rooms = RoomsTestSupport(iam)

    private fun newRoom() = RoomId(rooms.createRoom(rooms.createRoomType()))

    private fun status(room: RoomId) = roomsApi.status(room).shouldNotBeNull()

    @Test
    fun `markCleanIfDirty cleans only a dirty room and says whether it changed (D3)`() {
        val withInspection = newRoom()
        roomsApi.markCleanIfDirty(withInspection, requiresInspection = true, source = RoomStatusSource.TASK) shouldBe
            true
        status(withInspection).let {
            it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            it.inspectionPending shouldBe true
        }
        // Já limpa com inspeção pendente: nada muda, sem recusa.
        val version = status(withInspection).version
        roomsApi.markCleanIfDirty(withInspection, requiresInspection = true, source = RoomStatusSource.TASK) shouldBe
            false
        roomsApi.markCleanIfDirty(withInspection, requiresInspection = false, source = RoomStatusSource.TASK) shouldBe
            false
        status(withInspection).let {
            it.inspectionPending shouldBe true
            it.version shouldBe version
        }

        val clean = newRoom()
        roomsApi.markCleanIfDirty(clean, requiresInspection = false, source = RoomStatusSource.TASK) shouldBe true
        status(clean).let {
            it.housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            it.inspectionPending shouldBe false
        }
        roomsApi.markCleanIfDirty(clean, requiresInspection = true, source = RoomStatusSource.TASK) shouldBe false
        status(clean).inspectionPending shouldBe false

        val inspected = newRoom()
        roomsApi.markClean(inspected, requiresInspection = false, source = RoomStatusSource.MANUAL)
        roomsApi.markInspected(inspected, RoomStatusSource.MANUAL)
        roomsApi.markCleanIfDirty(inspected, requiresInspection = true, source = RoomStatusSource.TASK) shouldBe false
        status(inspected).housekeepingStatus shouldBe HousekeepingStatus.INSPECTED

        // Só a mudança de fato fica no histórico, com a origem TASK.
        historySources(withInspection) shouldBe listOf("TASK", "TASK")
        historySources(inspected).none { it == "TASK" } shouldBe true

        shouldThrow<NotFoundException> {
            roomsApi.markCleanIfDirty(RoomId(UUID.randomUUID()), requiresInspection = false, RoomStatusSource.TASK)
        }.code.value shouldBe "ROOM_NOT_FOUND"
    }

    @Test
    fun `setServiceFlagIfOccupied sets the flag only on an occupied room (D4)`() {
        val occupied = newRoom()
        roomsApi.markOccupied(occupied, 2, RoomStatusSource.CHECK_IN)
        roomsApi.setServiceFlagIfOccupied(occupied, GuestServiceFlag.DO_NOT_DISTURB, RoomStatusSource.TASK) shouldBe
            true
        status(occupied).serviceFlag shouldBe GuestServiceFlag.DO_NOT_DISTURB
        // Já com o sinal: nada muda.
        roomsApi.setServiceFlagIfOccupied(occupied, GuestServiceFlag.DO_NOT_DISTURB, RoomStatusSource.TASK) shouldBe
            false

        val vacant = newRoom()
        val version = status(vacant).version
        roomsApi.setServiceFlagIfOccupied(vacant, GuestServiceFlag.DO_NOT_DISTURB, RoomStatusSource.TASK) shouldBe
            false
        status(vacant).let {
            it.serviceFlag shouldBe GuestServiceFlag.NONE
            it.version shouldBe version
        }

        shouldThrow<NotFoundException> {
            roomsApi.setServiceFlagIfOccupied(
                RoomId(UUID.randomUUID()),
                GuestServiceFlag.DO_NOT_DISTURB,
                RoomStatusSource.TASK,
            )
        }.code.value shouldBe "ROOM_NOT_FOUND"
    }

    @Test
    fun `recordObservation records the observation as the endpoint does, and a divergent one is a discrepancy`(): Unit =
        with(iam) {
            val room = newRoom()
            roomsApi.markOccupied(room, 2, RoomStatusSource.CHECK_IN)
            val observer = UserId(createUser().id.value)
            val at = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS)

            roomsApi.recordObservation(room, OccupancyStatus.VACANT, null, observer, at)

            val row =
                jdbc
                    .sql(
                        "SELECT id, occupancy, guest_count, observed_by, observed_at " +
                            "FROM rooms.housekeeping_observation WHERE room_id = :id",
                    ).param("id", room.value)
                    .query()
                    .singleRow()
            row["occupancy"] shouldBe "VACANT"
            row["guest_count"] shouldBe null
            row["observed_by"] shouldBe observer.value
            (row["observed_at"] as Timestamp).toInstant() shouldBe at
            auditActions(row["id"] as UUID) shouldBe listOf("rooms.housekeeping-observation.record")

            val found = rooms.listAll("/api/v1/rooms/discrepancies").filter { it["roomId"].asString() == "$room" }
            found.single().let {
                it["kind"].asString() shouldBe "OCCUPIED_BUT_FOUND_VACANT"
                it["observedBy"].asString() shouldBe observer.toString()
            }

            // As mesmas regras do endpoint: número de hóspedes, UH inexistente e UH desativada.
            shouldThrow<InvalidInputException> {
                roomsApi.recordObservation(room, OccupancyStatus.VACANT, 1, observer, at)
            }.code.value shouldBe "GUEST_COUNT_INVALID"
            shouldThrow<NotFoundException> {
                roomsApi.recordObservation(RoomId(UUID.randomUUID()), OccupancyStatus.VACANT, null, observer, at)
            }.code.value shouldBe "ROOM_NOT_FOUND"
            val inactive = newRoom()
            jdbc.sql("UPDATE rooms.room SET active = false WHERE id = :id").param("id", inactive.value).update()
            shouldThrow<BusinessRuleException> {
                roomsApi.recordObservation(inactive, OccupancyStatus.VACANT, null, observer, at)
            }.code.value shouldBe "ROOM_INACTIVE"
        }

    private fun historySources(room: RoomId): List<String> =
        jdbc
            .sql("SELECT source FROM rooms.room_status_change WHERE room_id = :id ORDER BY changed_at, id")
            .param("id", room.value)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun auditActions(entityId: UUID): List<String> =
        jdbc
            .sql("SELECT action FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id")
            .param("id", entityId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()
}

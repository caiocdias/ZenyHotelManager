package com.zeny.hotel.housekeeping

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * A inspeção voluntária de uma UH (M6 E05, Task 2; D4): `POST /api/v1/room-inspections` `{ roomId }`, sem checklist,
 * só aprova, pela `RoomsApi.markInspectedIfClean`. UH Limpa sem pendência → Inspecionada, com uma inspeção sem tarefa
 * `COMPLETED`/`APPROVED`; Limpa com pendência → `409 HOUSEKEEPING_INSPECTION_USE_TASK`; não Limpa → `409
 * HOUSEKEEPING_ROOM_NOT_CLEAN`; sem `housekeeping.inspections.perform` → `403`, antes de qualquer recusa.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingRoomInspectionApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired staffApi: StaffApi,
) {
    private val support = InspectionTestSupport(iam, jdbc, staffApi)

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        support.execution.neutralizeGeneralHousekeepingTemplates()
        support.execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `a clean room without a pending inspection is inspected, with an inspection without task`(): Unit =
        with(iam) {
            val room = cleanRoom(requiresInspection = false)
            val inspector = support.inspector()

            val body = inspect(room, asTestUser(inspector)).ok()

            body["inspection"]["taskId"].isNull shouldBe true
            body["inspection"]["roomId"].asString() shouldBe room.value.toString()
            body["inspection"]["status"].asString() shouldBe "COMPLETED"
            body["inspection"]["result"].asString() shouldBe "APPROVED"
            body["inspection"]["checklistExecutionId"].isNull shouldBe true
            body["inspection"]["rejectedItems"].values().toList().shouldBeEmpty()
            body["inspection"]["inspectorId"].asString() shouldBe inspector.id.value.toString()
            roomsApi.status(room).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.INSPECTED
            inspections(room) shouldContainExactly listOf("COMPLETED" to "APPROVED")
            support.housekeeping.auditActions(
                "housekeeping.inspection",
                UUID.fromString(body["inspection"]["id"].asString()),
            ) shouldContainExactly listOf("housekeeping.inspection.inspect-room")
        }

    @Test
    fun `a clean room with a pending inspection is 409 USE_TASK, and a room not clean is 409 NOT_CLEAN`(): Unit =
        with(iam) {
            val pending = cleanRoom(requiresInspection = true)
            val dirty = RoomId(support.housekeeping.createRoom())
            val inspected = cleanRoom(requiresInspection = false)
            roomsApi.markInspected(inspected, RoomStatusSource.MANUAL)
            val auth = asTestUser(support.inspector())

            listOf(
                pending to "HOUSEKEEPING_INSPECTION_USE_TASK",
                dirty to "HOUSEKEEPING_ROOM_NOT_CLEAN",
                inspected to "HOUSEKEEPING_ROOM_NOT_CLEAN",
            ).forEach { (room, code) ->
                withClue(code) {
                    val before = roomsApi.status(room).shouldNotBeNull()

                    inspect(room, auth).problem(409, code)

                    roomsApi.status(room) shouldBe before
                    inspections(room).shouldBeEmpty()
                }
            }
        }

    @Test
    fun `without the permission it is 403, before any refusal`(): Unit =
        with(iam) {
            val attendant = asTestUser(support.execution.userWithTemplate(RoleTemplate.ROOM_ATTENDANT))
            val clean = cleanRoom(requiresInspection = false)
            val dirty = RoomId(support.housekeeping.createRoom())

            listOf(clean, dirty, RoomId(UUID.randomUUID())).forEach { room ->
                inspect(room, attendant).problem(403, "PERMISSION_DENIED")
            }
            roomsApi.status(clean).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.CLEAN
        }

    @Test
    fun `an unknown room is 400 on the field`(): Unit =
        with(iam) {
            inspect(RoomId(UUID.randomUUID()), asTestUser(support.inspector()))
                .problem(400, "VALIDATION_FAILED")["errors"][0]["field"]
                .asString() shouldBe "roomId"
        }

    private fun cleanRoom(requiresInspection: Boolean): RoomId =
        RoomId(support.housekeeping.createRoom()).also {
            roomsApi.markClean(it, requiresInspection, RoomStatusSource.MANUAL)
        }

    private fun inspect(
        room: RoomId,
        auth: RequestPostProcessor,
    ) = iam.call(POST, "/api/v1/room-inspections", auth, mapOf("roomId" to room.value))

    private fun inspections(room: RoomId): List<Pair<String, String?>> =
        jdbc
            .sql("SELECT status, result FROM housekeeping.inspection WHERE room_id = :room AND task_id IS NULL")
            .param("room", room.value)
            .query { rs, _ -> rs.getString("status") to rs.getString("result") }
            .list()
}

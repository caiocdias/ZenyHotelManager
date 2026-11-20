package com.zeny.hotel.maintenance

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.maintenance.MaintenanceTestSupport.Companion.WORK_ORDERS
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsPermissions
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * O ataque do testador à E07 do M6: número com o ano da data operacional, bordas de tamanho dos textos livres, o
 * `403` de quem só lê, o `PUT` sem as chaves anuláveis, a aprovação de outra ação ou de outra ordem, a conclusão de
 * um bloqueio que ainda não começou e o técnico `OWN` no bloqueio e nos anexos de ordem alheia.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // os cenários do ataque, com os apoios do contexto
class WorkOrderStageAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)
    private val read = MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.READ }
    private val manage = MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.MANAGE }
    private val oooManage = RoomsPermissions.permissions.single { it.code == RoomsPermissions.OUT_OF_ORDER_MANAGE }
    private val oosManage = RoomsPermissions.permissions.single { it.code == RoomsPermissions.OUT_OF_SERVICE_MANAGE }

    private fun fieldsOf(problem: JsonNode): List<String> = problem["errors"].values().map { it["field"].asString() }

    private fun blockBody(
        kind: String = "OUT_OF_ORDER",
        startsOnOffset: Long = 0,
    ): Map<String, Any?> =
        mapOf(
            "kind" to kind,
            "startsOn" to hotelApi.currentBusinessDate().plusDays(startsOnOffset).toString(),
            "endsOn" to hotelApi.currentBusinessDate().plusDays(startsOnOffset + 2).toString(),
        )

    private fun approve(
        user: TestUser,
        required: JsonNode,
        approver: TestUser,
        pin: String,
    ): String =
        with(iam) {
            call(
                POST,
                "/api/v1/auth/approvals",
                asTestUser(user),
                mapOf(
                    "action" to required["action"].asString(),
                    "permission" to required["permission"].asString(),
                    "resourceRef" to required["resourceRef"].asString(),
                    "approverLogin" to approver.login,
                    "approverPin" to pin,
                    "reason" to "Obra",
                ),
            ).expectStatus(201)!!["approvalToken"].asString()
        }

    private fun approver(): Pair<TestUser, String> {
        val user = iam.createUserWith(TestGrant(oooManage, ALL))
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return user to pin
    }

    @Test
    fun `the number carries the year of the business date`(): Unit =
        with(iam) {
            val number = support.opened()["number"].asString()
            number.substring(3, 7).toInt() shouldBe hotelApi.currentBusinessDate().year
        }

    @Test
    fun `free texts above the limit are 400 on the field and change nothing, and the limit itself is accepted`(): Unit =
        with(iam) {
            val order = support.opened()
            val id = UUID.fromString(order["id"].asString())

            fieldsOf(
                support
                    .update(id, support.updateBody(order, "thirdPartyName" to "a".repeat(201)))
                    .problem(400, "VALIDATION_FAILED"),
            ) shouldContain "thirdPartyName"
            support.order(id)["thirdPartyName"].isNull shouldBe true

            support.act(id, "start").ok()
            fieldsOf(
                support.act(id, "hold", body = mapOf("reason" to "a".repeat(501))).problem(400, "VALIDATION_FAILED"),
            ) shouldContain "holdReason"
            support.order(id)["status"].asString() shouldBe "IN_PROGRESS"

            fieldsOf(
                support
                    .act(id, "complete", body = mapOf("notes" to "a".repeat(2001)))
                    .problem(400, "VALIDATION_FAILED"),
            ) shouldContain "completionNotes"
            support.order(id)["status"].asString() shouldBe "IN_PROGRESS"
            fieldsOf(
                support.act(id, "cancel", body = mapOf("reason" to "a".repeat(501))).problem(400, "VALIDATION_FAILED"),
            ) shouldContain "cancellationReason"
            support.order(id)["status"].asString() shouldBe "IN_PROGRESS"

            support
                .update(id, support.updateBody(support.order(id), "thirdPartyName" to "a".repeat(200)))
                .ok()["thirdPartyName"]
                .asString()
                .length shouldBe 200
            support.act(id, "hold", body = mapOf("reason" to "a".repeat(500))).ok()
            support.act(id, "complete", body = mapOf("notes" to "a".repeat(2000))).ok()["status"].asString() shouldBe
                "DONE"
        }

    @Test
    fun `whoever only reads gets 403 on an unknown order and on a final one, for every write`(): Unit =
        with(iam) {
            val reader = asTestUser(createUserWith(TestGrant(read, ALL)))
            val finished = support.openedId().also { support.act(it, "complete", body = emptyMap<String, Any>()).ok() }
            val current = support.order(finished)
            val file =
                files.uploadOk(
                    PHOTO,
                    asAdmin(),
                    com.zeny.hotel.files.TestImages
                        .jpeg(32, 32),
                    "x.jpg",
                )
            listOf(UUID.randomUUID(), finished).forEach { id ->
                withClue("$id") {
                    listOf("start", "resume", "complete").forEach {
                        support.act(id, it, reader, emptyMap<String, Any>(), "\"0\"").problem(403, "PERMISSION_DENIED")
                    }
                    support.act(id, "hold", reader, mapOf("reason" to "X"), "\"0\"").problem(403, "PERMISSION_DENIED")
                    support.act(id, "cancel", reader, mapOf("reason" to "X"), "\"0\"").problem(403, "PERMISSION_DENIED")
                    support.update(id, support.updateBody(current), reader, "\"0\"").problem(403, "PERMISSION_DENIED")
                    call(POST, "$WORK_ORDERS/$id/room-block", reader, blockBody(), "\"0\"")
                        .problem(403, "PERMISSION_DENIED")
                    call(POST, "$WORK_ORDERS/$id/attachments", reader, mapOf("fileId" to file))
                        .problem(403, "PERMISSION_DENIED")
                    call(POST, "$WORK_ORDERS/$id/attachments/$file/detach", reader).problem(403, "PERMISSION_DENIED")
                }
            }
            support.order(finished)["status"].asString() shouldBe "DONE"
        }

    @Test
    fun `PUT with the nullable keys omitted answers 200 and clears them`(): Unit =
        with(iam) {
            val room = support.room()
            val employee = support.employee()
            val created = support.opened(body = support.body(roomId = room))
            val id = UUID.fromString(created["id"].asString())
            val filled =
                support
                    .update(
                        id,
                        support.updateBody(
                            created,
                            "assigneeEmployeeId" to employee,
                            "thirdPartyName" to "Hidráulica Silva",
                            "dueAt" to "2030-01-01T10:00:00Z",
                        ),
                    ).ok()
            filled["dueAt"].isNull shouldBe false
            val minimal =
                support
                    .updateBody(filled)
                    .filterKeys { it !in setOf("areaId", "assigneeEmployeeId", "thirdPartyName", "dueAt") }
            val cleared = support.update(id, minimal).ok()
            listOf("areaId", "assigneeEmployeeId", "thirdPartyName", "dueAt").forEach {
                withClue(it) { cleared[it].isNull shouldBe true }
            }
            cleared["roomId"].asString() shouldBe room.toString()

            val area = support.area()
            val ofArea = support.opened(body = support.body(areaId = area))
            val areaId = UUID.fromString(ofArea["id"].asString())
            val clearedArea = support.update(areaId, support.updateBody(ofArea) - "roomId").ok()
            clearedArea["roomId"].isNull shouldBe true
            clearedArea["areaId"].asString() shouldBe area.toString()
        }

    @Test
    fun `an approval of another action or of another order is APPROVAL_TOKEN_MISMATCH`(): Unit =
        with(iam) {
            val technician = createUserWith(TestGrant(manage, ALL), TestGrant(oosManage, ALL))
            val tech = asTestUser(technician)
            val (approverUser, pin) = approver()
            val order = support.openedId()
            val other = support.openedId()

            fun required(id: UUID) =
                call(POST, "$WORK_ORDERS/$id/room-block", tech, blockBody(), support.etag(id))
                    .problem(403, "APPROVAL_REQUIRED")
            val token = approve(technician, required(order), approverUser, pin)
            // O token do bloqueio não vale para cancelar a mesma ordem (já com bloqueio vigente para exigir o step-up).
            call(POST, "$WORK_ORDERS/$order/room-block", asAdmin(), blockBody(), support.etag(order)).ok()
            call(POST, "$WORK_ORDERS/$order/cancel", tech, mapOf("reason" to "X"), support.etag(order), token)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")
            support.order(order)["status"].asString() shouldBe "OPEN"

            // O token pedido para a ordem `third` não abre o bloqueio da ordem `other`.
            val third = support.openedId()
            val forOther = approve(technician, required(third), approverUser, pin)
            call(POST, "$WORK_ORDERS/$other/room-block", tech, blockBody(), support.etag(other), forOther)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")
            support.order(other)["outOfOrderId"].isNull shouldBe true
        }

    @Test
    fun `completing an order whose period has not started cancels the period and leaves the room alone`(): Unit =
        with(iam) {
            val room = support.room()
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            val order = support.openedId(body = support.body(roomId = room))
            val period =
                call(
                    POST,
                    "$WORK_ORDERS/$order/room-block",
                    asAdmin(),
                    blockBody(startsOnOffset = 5),
                    support.etag(order),
                ).ok()["outOfOrderId"]
                    .asString()
            val historyBefore = support.rooms.listAll("/api/v1/rooms/$room/status-history").size

            support.act(order, "complete", body = emptyMap<String, Any>()).ok()["status"].asString() shouldBe "DONE"

            jdbc
                .sql("SELECT released_on FROM rooms.out_of_order WHERE id = :id")
                .param("id", UUID.fromString(period))
                .query(java.time.LocalDate::class.java)
                .single()
                .shouldNotBeNull()
            roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus shouldBe HousekeepingStatus.CLEAN
            support.rooms.listAll("/api/v1/rooms/$room/status-history").size shouldBe historyBefore
        }

    @Test
    fun `an OWN technician gets 403 OUT_OF_SCOPE on block, attach and detach of someone else's order`(): Unit =
        with(iam) {
            val user =
                createUserWith(
                    TestGrant(read, OWN),
                    TestGrant(manage, OWN),
                    TestGrant(oosManage, ALL),
                    TestGrant(oooManage, ALL),
                )
            support.employee(userId = user.id.value)
            val tech = asTestUser(user)
            val foreign = support.openedId()
            val attached =
                files.uploadOk(
                    PHOTO,
                    asAdmin(),
                    com.zeny.hotel.files.TestImages
                        .jpeg(32, 32),
                    "a.jpg",
                )
            val extra =
                files.uploadOk(
                    PHOTO,
                    asAdmin(),
                    com.zeny.hotel.files.TestImages
                        .jpeg(32, 32),
                    "b.jpg",
                )
            call(POST, "$WORK_ORDERS/$foreign/attachments", asAdmin(), mapOf("fileId" to attached)).ok()

            listOf("OUT_OF_SERVICE", "OUT_OF_ORDER").forEach { kind ->
                call(POST, "$WORK_ORDERS/$foreign/room-block", tech, blockBody(kind), support.etag(foreign))
                    .problem(403, "RESOURCE_OUT_OF_SCOPE")
            }
            call(POST, "$WORK_ORDERS/$foreign/attachments", tech, mapOf("fileId" to extra))
                .problem(403, "RESOURCE_OUT_OF_SCOPE")
            call(POST, "$WORK_ORDERS/$foreign/attachments/$attached/detach", tech)
                .problem(403, "RESOURCE_OUT_OF_SCOPE")

            val after = support.order(foreign)
            after["outOfOrderId"].isNull shouldBe true
            after["attachments"].size() shouldBe 1
            jdbc
                .sql("SELECT detached_at FROM maintenance.work_order_attachment WHERE file_id = :id")
                .param("id", attached)
                .query { rs, _ -> rs.getObject("detached_at") != null }
                .single() shouldBe false
        }

    private companion object {
        const val PHOTO = "maintenance.work-order-photo"
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
    }
}

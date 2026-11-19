package com.zeny.hotel.maintenance

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
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

/**
 * O bloqueio da UH pela ordem de serviço (M6 E07, Task 3, D7): `POST /{id}/room-block` abre o OOO/OOS do `rooms` com
 * o step-up do OOO (aprovação de superior sem `rooms.outoforder.manage`) ou `rooms.outofservice.manage`; concluir a
 * ordem libera o período sem step-up e deixa a UH Suja (origem `MANUAL`); cancelar com bloqueio vigente exige o que o
 * `rooms` exige para liberar; o local fica travado enquanto há bloqueio; a ordem das recusas; a liberação feita à mão
 * no `rooms` não quebra a conclusão.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // os cenários do bloqueio, da liberação, do cancelamento e da ordem das recusas
class WorkOrderRoomBlockTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private val manage = MaintenancePermissions.permissions.single { it.code == MaintenancePermissions.MANAGE }
    private val oooManage = RoomsPermissions.permissions.single { it.code == RoomsPermissions.OUT_OF_ORDER_MANAGE }
    private val oosManage = RoomsPermissions.permissions.single { it.code == RoomsPermissions.OUT_OF_SERVICE_MANAGE }

    /** O técnico: gere as ordens e abre OOS, mas só pede OOO (como a Manutenção do SPEC §3.1). */
    private inner class Technician(
        val user: TestUser = iam.createUserWith(TestGrant(manage, ALL), TestGrant(oosManage, ALL)),
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

    private fun approver(): Approver {
        val user = iam.createUserWith(TestGrant(oooManage, ALL))
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        technician: Technician,
        required: JsonNode,
        approver: Approver,
    ): String =
        with(iam) {
            call(
                POST,
                "/api/v1/auth/approvals",
                technician.auth,
                mapOf(
                    "action" to required["action"].asString(),
                    "permission" to required["permission"].asString(),
                    "resourceRef" to required["resourceRef"].asString(),
                    "approverLogin" to approver.user.login,
                    "approverPin" to approver.pin,
                    "reason" to "Obra no banheiro",
                ),
            ).expectStatus(201)!!["approvalToken"].asString()
        }

    private fun today(): LocalDate = hotelApi.currentBusinessDate()

    private fun blockBody(
        kind: String? = "OUT_OF_ORDER",
        days: Long = 2,
    ): Map<String, Any?> =
        buildMap {
            if (kind != null) put("kind", kind)
            put("startsOn", today().toString())
            put("endsOn", today().plusDays(days).toString())
        }

    private fun block(
        id: UUID,
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Map<String, Any?> = blockBody(),
        token: String? = null,
        ifMatch: String? = support.etag(id),
    ): MockHttpServletResponse = iam.call(POST, "$WORK_ORDERS/$id/room-block", auth, body, ifMatch, token)

    private fun cancel(
        id: UUID,
        auth: RequestPostProcessor = iam.asAdmin(),
        token: String? = null,
        ifMatch: String? = support.etag(id),
    ): MockHttpServletResponse =
        iam.call(POST, "$WORK_ORDERS/$id/cancel", auth, mapOf("reason" to "Já resolvido"), ifMatch, token)

    private fun complete(
        id: UUID,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = support.act(id, "complete", auth, emptyMap<String, Any?>())

    private fun roomOf(order: UUID): UUID = UUID.fromString(support.order(order)["roomId"].asString())

    /** Uma OS numa UH nova e limpa (para ver a UH voltar Suja). */
    private fun cleanRoomOrder(): Pair<UUID, UUID> {
        val room = support.room()
        roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
        return support.openedId(body = support.body(roomId = room)) to room
    }

    private fun periodRow(period: String): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM rooms.out_of_order WHERE id = :id")
            .param("id", UUID.fromString(period))
            .query()
            .singleRow()

    private fun housekeeping(room: UUID): HousekeepingStatus =
        roomsApi.status(RoomId(room)).shouldNotBeNull().housekeepingStatus

    private fun history(room: UUID): List<JsonNode> = support.rooms.listAll("/api/v1/rooms/$room/status-history")

    @Test
    fun `an OOO without the rooms permission nor approval is 403, and with approval opens the period`(): Unit =
        with(iam) {
            val technician = Technician()
            val (order, room) = cleanRoomOrder()
            val number = support.order(order)["number"].asString()

            val required = block(order, technician.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "maintenance.work-order.block-room"
            required["permission"].asString() shouldBe "rooms.outoforder.manage"
            required["resourceRef"].asString() shouldBe "maintenance.work-order:$order"
            support.order(order)["outOfOrderId"].isNull shouldBe true

            val token = approve(technician, required, approver())
            val blocked = block(order, technician.auth, token = token).ok()
            val period = blocked["outOfOrderId"].asString()
            support.row(order)["version"] shouldBe 1L
            support.row(order)["out_of_order_id"].toString() shouldBe period
            periodRow(period).let {
                it["room_id"] shouldBe room
                it["kind"] shouldBe "OUT_OF_ORDER"
                it["reason"] shouldBe "OS $number"
                it["return_status"] shouldBe "DIRTY"
                it["released_on"].shouldBeNull()
            }
            // A trilha da ação traz a aprovação.
            jdbc
                .sql(
                    "SELECT count(*) FROM audit.audit_entry WHERE action = 'maintenance.work-order.block-room' " +
                        "AND entity_id = :id AND approval_id IS NOT NULL",
                ).param("id", order.toString())
                .query(Int::class.java)
                .single() shouldBe 1
        }

    @Test
    fun `whoever holds the OOO permission blocks without approval, and an OOS needs its own permission`(): Unit =
        with(iam) {
            val owner = Technician(createUserWith(TestGrant(manage, ALL), TestGrant(oooManage, ALL)))
            block(support.openedId(), owner.auth).ok()["outOfOrderId"].isNull shouldBe false

            val technician = Technician()
            val oos = block(support.openedId(), technician.auth, blockBody("OUT_OF_SERVICE")).ok()
            periodRow(oos["outOfOrderId"].asString())["kind"] shouldBe "OUT_OF_SERVICE"

            val manageOnly = Technician(createUserWith(TestGrant(manage, ALL)))
            block(support.openedId(), manageOnly.auth, blockBody("OUT_OF_SERVICE")).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `an OOO in an occupied room is 422, an area order is 422 and a second block is 409`(): Unit =
        with(iam) {
            val occupied = support.room()
            roomsApi.markOccupied(RoomId(occupied), 1, RoomStatusSource.CHECK_IN)
            val occupiedOrder = support.openedId(body = support.body(roomId = occupied))
            block(occupiedOrder).problem(422, "OUT_OF_ORDER_ROOM_OCCUPIED")
            support.order(occupiedOrder)["outOfOrderId"].isNull shouldBe true

            val areaOrder = support.openedId(body = support.body(areaId = support.area()))
            block(areaOrder).problem(422, "WORK_ORDER_NOT_ROOM")

            val order = support.openedId()
            block(order).ok()
            block(order).problem(409, "WORK_ORDER_ROOM_ALREADY_BLOCKED")
            block(order, body = blockBody("OUT_OF_SERVICE")).problem(409, "WORK_ORDER_ROOM_ALREADY_BLOCKED")
        }

    @Test
    fun `completing releases the period without step-up and leaves the room Dirty with source MANUAL`(): Unit =
        with(iam) {
            val (order, room) = cleanRoomOrder()
            val period = block(order).ok()["outOfOrderId"].asString()
            housekeeping(room) shouldBe HousekeepingStatus.CLEAN

            // Quem só tem `manage` (sem a permissão do `rooms`) conclui: o bloqueio foi autorizado para esta ordem.
            val technician = Technician(createUserWith(TestGrant(manage, ALL)))
            val done = complete(order, technician.auth).ok()
            done["status"].asString() shouldBe "DONE"
            done["outOfOrderId"].asString() shouldBe period
            periodRow(period)["released_on"].shouldNotBeNull()
            housekeeping(room) shouldBe HousekeepingStatus.DIRTY
            history(room).first().let {
                it["field"].asString() shouldBe "HOUSEKEEPING_STATUS"
                it["to"].asString() shouldBe "DIRTY"
                it["source"].asString() shouldBe "MANUAL"
            }
        }

    @Test
    fun `completing an order without a block leaves the room alone`(): Unit =
        with(iam) {
            val (order, room) = cleanRoomOrder()
            val before = history(room).size
            complete(order).ok()["outOfOrderId"].isNull shouldBe true
            housekeeping(room) shouldBe HousekeepingStatus.CLEAN
            history(room).size shouldBe before
        }

    @Test
    fun `cancelling needs approval for an OOO, the permission for an OOS, and just manage without a block`(): Unit =
        with(iam) {
            val technician = Technician()
            val (order, room) = cleanRoomOrder()
            block(order).ok()

            val required = cancel(order, technician.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "maintenance.work-order.cancel"
            required["permission"].asString() shouldBe "rooms.outoforder.manage"
            required["resourceRef"].asString() shouldBe "maintenance.work-order:$order"
            support.order(order)["status"].asString() shouldBe "OPEN"

            val token = approve(technician, required, approver())
            cancel(order, technician.auth, token).ok()["status"].asString() shouldBe "CANCELLED"
            housekeeping(room) shouldBe HousekeepingStatus.DIRTY
            periodRow(support.order(order)["outOfOrderId"].asString())["released_on"].shouldNotBeNull()

            // OOS: `rooms.outofservice.manage`, sem aprovação; quem não a tem leva 403.
            val oosOrder = support.openedId()
            block(oosOrder, body = blockBody("OUT_OF_SERVICE")).ok()
            val manageOnly = Technician(createUserWith(TestGrant(manage, ALL)))
            cancel(oosOrder, manageOnly.auth).problem(403, "PERMISSION_DENIED")
            cancel(oosOrder, technician.auth).ok()["status"].asString() shouldBe "CANCELLED"

            // Sem bloqueio vigente, o cancelamento só pede `manage`.
            cancel(support.openedId(), manageOnly.auth).ok()["status"].asString() shouldBe "CANCELLED"
        }

    @Test
    fun `changing the location with a block in effect is 409 LOCATION_LOCKED, the same location is fine`(): Unit =
        with(iam) {
            val order = support.openedId()
            block(order).ok()
            val current = support.order(order)
            support
                .update(order, support.updateBody(current, "roomId" to support.room()))
                .problem(409, "WORK_ORDER_LOCATION_LOCKED")
            support
                .update(
                    order,
                    support.updateBody(current, "priority" to "HIGH"),
                ).ok()["priority"]
                .asString() shouldBe
                "HIGH"
            support.order(order)["roomId"].asString() shouldBe current["roomId"].asString()
        }

    @Test
    fun `an order whose period was released by hand in the rooms still completes and cancels`(): Unit =
        with(iam) {
            val (order, room) = cleanRoomOrder()
            val period = block(order).ok()["outOfOrderId"].asString()
            call(POST, "/api/v1/rooms/$room/out-of-order/$period/release", asAdmin()).ok()
            // A UH já voltou Suja; a limpeza seguinte a deixa Limpa e a conclusão não mexe nela de novo.
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            val before = history(room).size

            complete(order).ok()["status"].asString() shouldBe "DONE"
            housekeeping(room) shouldBe HousekeepingStatus.CLEAN
            history(room).size shouldBe before

            // A liberação à mão também deixa o local livre para um novo bloqueio e o cancelamento sem step-up.
            val other = support.openedId()
            val otherPeriod = block(other).ok()["outOfOrderId"].asString()
            call(POST, "/api/v1/rooms/${roomOf(other)}/out-of-order/$otherPeriod/release", asAdmin()).ok()
            val technician = Technician()
            cancel(other, technician.auth).ok()["status"].asString() shouldBe "CANCELLED"
        }

    @Test
    fun `the refusals follow the order of D7`(): Unit =
        with(iam) {
            val technician = Technician()
            val nobody = asTestUser(createUser())

            // 400 do corpo (kind ausente) antes de tudo; 403 sem manage antes do 404.
            block(support.openedId(), technician.auth, blockBody(kind = null)).problem(400, "VALIDATION_FAILED")
            block(UUID.randomUUID(), nobody, ifMatch = "\"0\"").problem(403, "PERMISSION_DENIED")
            block(UUID.randomUUID(), technician.auth, ifMatch = "\"0\"").problem(404, "WORK_ORDER_NOT_FOUND")

            // 412 antes do estado, do local e do step-up.
            val order = support.openedId()
            block(order, technician.auth, ifMatch = "\"7\"").problem(412, "PRECONDITION_FAILED")

            // Ordem final (409) antes da área e do step-up.
            val finalOrder = support.openedId()
            complete(finalOrder).ok()
            block(finalOrder, technician.auth).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")

            // Área (422) e bloqueio vigente (409) antes do step-up (403 APPROVAL_REQUIRED).
            block(support.openedId(body = support.body(areaId = support.area())), technician.auth)
                .problem(422, "WORK_ORDER_NOT_ROOM")
            val blocked = support.openedId()
            block(blocked).ok()
            block(blocked, technician.auth).problem(409, "WORK_ORDER_ROOM_ALREADY_BLOCKED")

            // Cancelar: o estado final (409) vem antes do step-up (403).
            cancel(finalOrder, technician.auth).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
            // Sem versão certa (412) também antes do step-up.
            cancel(blocked, technician.auth, ifMatch = "\"9\"").problem(412, "PRECONDITION_FAILED")
            // Sem permissão `manage`: 403 de permissão, não o de aprovação.
            cancel(blocked, nobody).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `the trail of a blocked order has the period id and the blocked order lists the audit of the block`(): Unit =
        with(iam) {
            val order = support.openedId()
            val period = block(order).ok()["outOfOrderId"].asString()
            support.audit(order).map { it["action"] } shouldContainExactly
                listOf("maintenance.work-order.open", "maintenance.work-order.block-room")
            support
                .audit(order)
                .last()["after_state"]
                .toString()
                .contains(period) shouldBe true
        }

    private companion object {
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
    }
}

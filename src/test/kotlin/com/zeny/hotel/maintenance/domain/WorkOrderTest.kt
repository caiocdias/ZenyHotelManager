package com.zeny.hotel.maintenance.domain

import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.maintenance.WorkOrderStatus.CANCELLED
import com.zeny.hotel.maintenance.WorkOrderStatus.DONE
import com.zeny.hotel.maintenance.WorkOrderStatus.IN_PROGRESS
import com.zeny.hotel.maintenance.WorkOrderStatus.ON_HOLD
import com.zeny.hotel.maintenance.WorkOrderStatus.OPEN
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** A máquina de estados da ordem de serviço (D3-E07) e as regras dos campos, sem Spring nem banco. */
class WorkOrderTest {
    private val t0 = Instant.parse("2026-11-19T10:00:00Z")
    private val t1 = t0.plusSeconds(60)
    private val t2 = t0.plusSeconds(120)

    private fun open(
        room: RoomId? = RoomId(UUID.randomUUID()),
        area: AreaId? = null,
    ) = WorkOrder.open(
        WorkOrderId.new(),
        DocumentNumber("OS", 2026, 1),
        room,
        area,
        WorkOrderCategory.PLUMBING,
        WorkOrderPriority.NORMAL,
        "Torneira pingando",
        WorkOrderOrigin.RECEPTION,
        null,
        UserId(UUID.randomUUID()),
        t0,
    )

    /** Uma ordem no estado pedido, pelo caminho válido. */
    private fun inStatus(status: WorkOrderStatus): WorkOrder {
        val order = open()
        when (status) {
            OPEN -> {
                Unit
            }

            IN_PROGRESS -> {
                order.start(t1)
            }

            ON_HOLD -> {
                order.start(t1)
                order.hold("Aguardando peça", t1)
            }

            DONE -> {
                order.complete(null, null, null, null, t1)
            }

            CANCELLED -> {
                order.cancel("Duplicada", t1)
            }
        }
        return order
    }

    private val actions: Map<WorkOrderAction, (WorkOrder) -> Unit> =
        mapOf(
            WorkOrderAction.START to { it.start(t2) },
            WorkOrderAction.HOLD to { it.hold("Motivo", t2) },
            WorkOrderAction.RESUME to { it.resume(t2) },
            WorkOrderAction.COMPLETE to { it.complete("Feito", null, null, null, t2) },
            WorkOrderAction.CANCEL to { it.cancel("Motivo", t2) },
        )

    private val valid: Map<WorkOrderAction, Pair<Set<WorkOrderStatus>, WorkOrderStatus>> =
        mapOf(
            WorkOrderAction.START to (setOf(OPEN) to IN_PROGRESS),
            WorkOrderAction.HOLD to (setOf(IN_PROGRESS) to ON_HOLD),
            WorkOrderAction.RESUME to (setOf(ON_HOLD) to IN_PROGRESS),
            WorkOrderAction.COMPLETE to (setOf(OPEN, IN_PROGRESS, ON_HOLD) to DONE),
            WorkOrderAction.CANCEL to (setOf(OPEN, IN_PROGRESS, ON_HOLD) to CANCELLED),
        )

    @Test
    fun `every action in every status either makes the transition of D3 or refuses with the transition error`() {
        valid.forEach { (action, rule) ->
            val (from, to) = rule
            WorkOrderStatus.entries.forEach { status ->
                withClue("$action em $status") {
                    val order = inStatus(status)
                    val version = order.version
                    if (status in from) {
                        actions.getValue(action)(order)
                        order.status shouldBe to
                    } else {
                        val error =
                            shouldThrow<WorkOrderTransitionNotAllowedException> { actions.getValue(action)(order) }
                        error.action shouldBe action
                        error.status shouldBe status
                        error.code.value shouldBe "WORK_ORDER_TRANSITION_NOT_ALLOWED"
                        order.status shouldBe status
                        order.version shouldBe version
                    }
                }
            }
        }
    }

    @Test
    fun `a new order is open, with zero costs and no start`() {
        val order = open()
        order.status shouldBe OPEN
        order.laborCost shouldBe Money.ZERO
        order.thirdPartyCost shouldBe Money.ZERO
        order.partsCost shouldBe Money.ZERO
        order.startedAt shouldBe null
        order.closedAt shouldBe null
        order.number.toString() shouldBe "OS-2026-000001"
    }

    @Test
    fun `startedAt is set by the first start and kept by a resume, closedAt by complete and cancel`() {
        val order = open()
        order.start(t1)
        order.startedAt shouldBe t1
        order.hold("Peça", t2)
        order.resume(t2.plusSeconds(60))
        order.startedAt shouldBe t1
        order.closedAt shouldBe null
        order.complete(null, null, null, null, t2.plusSeconds(120))
        order.closedAt shouldBe t2.plusSeconds(120)
        inStatus(CANCELLED).closedAt shouldBe t1
    }

    @Test
    fun `hold keeps the reason only while on hold`() {
        val order = inStatus(ON_HOLD)
        order.holdReason shouldBe "Aguardando peça"
        order.resume(t2)
        order.holdReason shouldBe null
    }

    @Test
    fun `hold and cancel without a reason are refused and change nothing`() {
        listOf<String?>(null, "", "   ").forEach { reason ->
            withClue("motivo '$reason'") {
                val inProgress = inStatus(IN_PROGRESS)
                shouldThrow<InvalidInputException> { inProgress.hold(reason, t2) }
                inProgress.status shouldBe IN_PROGRESS
                val open = open()
                shouldThrow<InvalidInputException> { open.cancel(reason, t2) }
                open.status shouldBe OPEN
            }
        }
    }

    @Test
    fun `complete takes optional notes and costs, and keeps the costs it is not given`() {
        val order = open()
        order.update(edit(labor = "10.00", parts = "5.50"), t1)
        order.complete("Trocado o vedante", null, Money.of("20.00"), null, t2)
        order.completionNotes shouldBe "Trocado o vedante"
        order.laborCost shouldBe Money.of("10.00")
        order.thirdPartyCost shouldBe Money.of("20.00")
        order.partsCost shouldBe Money.of("5.50")
    }

    @Test
    fun `negative costs or above the cap are refused with the field`() {
        listOf("-0.01", "100000.00").forEach { cost ->
            withClue(cost) {
                val order = open()
                val error = shouldThrow<InvalidInputException> { order.update(edit(labor = cost), t1) }
                error.violations.single().field shouldBe "laborCost"
                shouldThrow<InvalidInputException> { order.complete(null, null, Money.of(cost), null, t1) }
                    .violations
                    .single()
                    .field shouldBe "thirdPartyCost"
                order.laborCost shouldBe Money.ZERO
                order.status shouldBe OPEN
            }
        }
        open().update(edit(labor = "99999.99", third = "0.00", parts = "0.01"), t1)
    }

    @Test
    fun `the location is the room or the area, one only`() {
        val room = RoomId(UUID.randomUUID())
        val area = AreaId(UUID.randomUUID())
        shouldThrow<InvalidInputException> { open(room, area) }.violations.map { it.field } shouldBe
            listOf("roomId", "areaId")
        shouldThrow<InvalidInputException> { open(null, null) }
        shouldThrow<InvalidInputException> { open().update(edit().copy(roomId = null, areaId = null), t1) }
        shouldThrow<InvalidInputException> { open().update(edit().copy(areaId = area), t1) }
        open(null, area).areaId shouldBe area
    }

    @Test
    fun `update refuses a final order with the transition error and changes nothing`() {
        listOf(DONE, CANCELLED).forEach { status ->
            val order = inStatus(status)
            val error = shouldThrow<WorkOrderTransitionNotAllowedException> { order.update(edit(), t2) }
            error.action shouldBe WorkOrderAction.UPDATE
            error.status shouldBe status
        }
    }

    @Test
    fun `checkUpdate validates an edit like update does and never changes the order`() {
        val order = open()
        val before = order.auditState()
        order.checkUpdate(edit(third = "50.00", thirdParty = "Hidráulica Silva"))
        order.auditState() shouldBe before
        order.updatedAt shouldBe t0
        shouldThrow<InvalidInputException> { order.checkUpdate(edit().copy(areaId = AreaId(UUID.randomUUID()))) }
        shouldThrow<InvalidInputException> { order.checkUpdate(edit(third = "100000.00")) }
        shouldThrow<WorkOrderTransitionNotAllowedException> { inStatus(DONE).checkUpdate(edit()) }
        order.auditState() shouldBe before
    }

    @Test
    fun `update replaces the editable fields of an order that is not final, including on hold`() {
        val order = inStatus(ON_HOLD)
        val assignee =
            com.zeny.hotel.staff.EmployeeId
                .new()
        order.update(edit(assignee = assignee, third = "50.00", thirdParty = "Hidráulica Silva"), t2)
        order.assigneeEmployeeId shouldBe assignee
        order.thirdPartyName shouldBe "Hidráulica Silva"
        order.thirdPartyCost shouldBe Money.of("50.00")
        order.status shouldBe ON_HOLD
        order.updatedAt shouldBe t2
    }

    @Test
    fun `descriptions must have 1 to 2000 characters and no NUL`() {
        listOf("", "   ", "a".repeat(2001), "a\u0000b").forEach { text ->
            withClue(text.take(10)) {
                shouldThrow<InvalidInputException> { open().update(edit(description = text), t1) }
                    .violations
                    .single()
                    .field shouldBe "description"
            }
        }
        open().update(edit(description = "a".repeat(2000)), t1)
    }

    @Test
    fun `the audit state never carries the free texts`() {
        val order = inStatus(ON_HOLD)
        order.update(edit(thirdParty = "Hidráulica Silva", description = "Segredo da descrição"), t2)
        val state = order.auditState().toString()
        listOf("Segredo da descrição", "Hidráulica Silva", "Aguardando peça").forEach {
            withClue(it) { (it in state) shouldBe false }
        }
        order.auditState()["status"] shouldBe "ON_HOLD"
    }

    @Suppress("LongParameterList") // os campos da edição, com padrões
    private fun edit(
        room: RoomId? = null,
        area: AreaId? = null,
        description: String = "Torneira pingando",
        assignee: com.zeny.hotel.staff.EmployeeId? = null,
        thirdParty: String? = null,
        labor: String = "0.00",
        third: String = "0.00",
        parts: String = "0.00",
    ) = WorkOrderEdit(
        roomId = room ?: if (area == null) ROOM else null,
        areaId = area,
        category = WorkOrderCategory.PLUMBING,
        priority = WorkOrderPriority.HIGH,
        description = description,
        assigneeEmployeeId = assignee,
        thirdPartyName = thirdParty,
        dueAt = null,
        laborCost = Money.of(labor),
        thirdPartyCost = Money.of(third),
        partsCost = Money.of(parts),
    )

    private companion object {
        val ROOM = RoomId(UUID.randomUUID())
    }
}

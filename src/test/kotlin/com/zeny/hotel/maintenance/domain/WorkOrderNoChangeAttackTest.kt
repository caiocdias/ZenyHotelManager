package com.zeny.hotel.maintenance.domain

import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
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

/**
 * Uma ação recusada na ordem de serviço não muda nada (M6 E07, Task 1): o retrato da trilha, a versão e as datas ficam
 * como estavam, inclusive quando a recusa vem do texto ou do custo e não do estado.
 */
class WorkOrderNoChangeAttackTest {
    private val t0 = Instant.parse("2026-11-19T10:00:00Z")
    private val t1 = t0.plusSeconds(60)
    private val t2 = t0.plusSeconds(120)

    private fun open() =
        WorkOrder.open(
            WorkOrderId.new(),
            DocumentNumber("OS", 2026, 1),
            RoomId(UUID.randomUUID()),
            null,
            WorkOrderCategory.PLUMBING,
            WorkOrderPriority.NORMAL,
            "Torneira pingando",
            WorkOrderOrigin.RECEPTION,
            null,
            UserId(UUID.randomUUID()),
            t0,
        )

    private fun snapshot(order: WorkOrder) =
        listOf(order.auditState(), order.version, order.updatedAt, order.startedAt, order.closedAt)

    @Test
    fun `hold with a reason of 501 characters or blank changes nothing and 500 is accepted`() {
        val order = open().also { it.start(t1) }
        val before = snapshot(order)
        listOf("a".repeat(501), "", "  ", "a\u0000b").forEach {
            withClue(it.take(8)) {
                shouldThrow<InvalidInputException> { order.hold(it, t2) }.violations.single().field shouldBe
                    "holdReason"
                snapshot(order) shouldBe before
            }
        }
        order.hold("a".repeat(500), t2)
    }

    @Test
    fun `cancel with a reason of 501 characters changes nothing and 500 is accepted`() {
        val order = open()
        val before = snapshot(order)
        shouldThrow<InvalidInputException> { order.cancel("a".repeat(501), t2) }.violations.single().field shouldBe
            "cancellationReason"
        snapshot(order) shouldBe before
        order.cancel("a".repeat(500), t2)
    }

    @Test
    fun `complete refused by the notes or by any one cost changes nothing, even the costs before the bad one`() {
        val order = open()
        val before = snapshot(order)
        val ok = Money.of("10.00")
        val bad = Money.of("100000.00")
        shouldThrow<InvalidInputException> { order.complete("a".repeat(2001), ok, ok, ok, t2) }
            .violations
            .single()
            .field shouldBe "completionNotes"
        snapshot(order) shouldBe before
        listOf(
            Triple(bad, ok, ok) to "laborCost",
            Triple(ok, bad, ok) to "thirdPartyCost",
            Triple(ok, ok, bad) to "partsCost",
        ).forEach { (costs, field) ->
            withClue(field) {
                val (labor, third, parts) = costs
                shouldThrow<InvalidInputException> { order.complete("Feito", labor, third, parts, t2) }
                    .violations
                    .single()
                    .field shouldBe field
                snapshot(order) shouldBe before
                order.laborCost shouldBe Money.of("0.00")
                order.completionNotes shouldBe null
            }
        }
        order.complete("a".repeat(2000), ok, ok, ok, t2)
    }

    @Test
    fun `update refused by the third party of 201 characters changes nothing and 200 is accepted`() {
        val order = open()
        val before = snapshot(order)
        val edit =
            WorkOrderEdit(
                roomId = order.roomId,
                areaId = null,
                category = WorkOrderCategory.ELECTRICAL,
                priority = WorkOrderPriority.URGENT,
                description = "Outro texto",
                assigneeEmployeeId = null,
                thirdPartyName = "a".repeat(201),
                dueAt = null,
                laborCost = Money.of("1.00"),
                thirdPartyCost = Money.of("1.00"),
                partsCost = Money.of("1.00"),
            )
        shouldThrow<InvalidInputException> { order.update(edit, t2) }.violations.single().field shouldBe
            "thirdPartyName"
        snapshot(order) shouldBe before
        order.update(edit.copy(thirdPartyName = "a".repeat(200)), t2)
        order.thirdPartyName?.length shouldBe 200
    }
}

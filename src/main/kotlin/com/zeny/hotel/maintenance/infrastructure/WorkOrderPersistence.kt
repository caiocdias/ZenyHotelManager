package com.zeny.hotel.maintenance.infrastructure

import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.maintenance.application.WorkOrderFilter
import com.zeny.hotel.maintenance.application.WorkOrderPageKey
import com.zeny.hotel.maintenance.application.WorkOrderStore
import com.zeny.hotel.maintenance.domain.WorkOrder
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.EmployeeId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * As ordens de serviço (D2-E07) por `JdbcClient`, sempre parametrizado e só em `maintenance.work_order`: `INSERT`, a
 * leitura (com a linha travada, `FOR UPDATE`, nas ações), o `UPDATE` com a versão e a página da listagem, com o escopo
 * e os filtros na própria consulta. Nunca apaga.
 */
@Component
class WorkOrderPersistence(
    private val jdbc: JdbcClient,
) : WorkOrderStore {
    override fun insert(order: WorkOrder) {
        jdbc
            .sql(
                """
                INSERT INTO $TABLE (id, number, room_id, area_id, category, priority, description, origin,
                    assignee_employee_id, third_party_name, status, hold_reason, labor_cost, third_party_cost,
                    parts_cost, opened_by, opened_at, due_at, started_at, closed_at, completion_notes,
                    cancellation_reason, out_of_order_id, updated_at, version)
                VALUES (:id, :number, :room, :area, :category, :priority, :description, :origin, :assignee,
                    :thirdParty, :status, :holdReason, :labor, :third, :parts, :openedBy, :openedAt, :dueAt,
                    :startedAt, :closedAt, :notes, :cancellation, :outOfOrder, :updatedAt, :version)
                """.trimIndent(),
            ).param("id", order.id.value)
            .param("number", order.number.toString())
            .param("room", order.roomId?.value)
            .param("area", order.areaId?.value)
            .param("category", order.category.name)
            .param("priority", order.priority.name)
            .param("description", order.description)
            .param("origin", order.origin.name)
            .param("assignee", order.assigneeEmployeeId?.value)
            .param("thirdParty", order.thirdPartyName)
            .param("status", order.status.name)
            .param("holdReason", order.holdReason)
            .param("labor", order.laborCost.amount)
            .param("third", order.thirdPartyCost.amount)
            .param("parts", order.partsCost.amount)
            .param("openedBy", order.openedBy.value)
            .param("openedAt", utc(order.openedAt))
            .param("dueAt", order.dueAt?.let(::utc))
            .param("startedAt", order.startedAt?.let(::utc))
            .param("closedAt", order.closedAt?.let(::utc))
            .param("notes", order.completionNotes)
            .param("cancellation", order.cancellationReason)
            .param("outOfOrder", order.outOfOrderId?.value)
            .param("updatedAt", utc(order.updatedAt))
            .param("version", order.version)
            .update()
    }

    override fun find(id: WorkOrderId): WorkOrder? = byId(id, lock = false)

    override fun findForUpdate(id: WorkOrderId): WorkOrder? = byId(id, lock = true)

    override fun update(order: WorkOrder) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE $TABLE SET room_id = :room, area_id = :area, category = :category, priority = :priority,
                        description = :description, assignee_employee_id = :assignee, third_party_name = :thirdParty,
                        status = :status, hold_reason = :holdReason, labor_cost = :labor, third_party_cost = :third,
                        parts_cost = :parts, due_at = :dueAt, started_at = :startedAt, closed_at = :closedAt,
                        completion_notes = :notes, cancellation_reason = :cancellation, out_of_order_id = :outOfOrder,
                        updated_at = :updatedAt, version = version + 1
                    WHERE id = :id AND version = :version
                    """.trimIndent(),
                ).param("id", order.id.value)
                .param("room", order.roomId?.value)
                .param("area", order.areaId?.value)
                .param("category", order.category.name)
                .param("priority", order.priority.name)
                .param("description", order.description)
                .param("assignee", order.assigneeEmployeeId?.value)
                .param("thirdParty", order.thirdPartyName)
                .param("status", order.status.name)
                .param("holdReason", order.holdReason)
                .param("labor", order.laborCost.amount)
                .param("third", order.thirdPartyCost.amount)
                .param("parts", order.partsCost.amount)
                .param("dueAt", order.dueAt?.let(::utc))
                .param("startedAt", order.startedAt?.let(::utc))
                .param("closedAt", order.closedAt?.let(::utc))
                .param("notes", order.completionNotes)
                .param("cancellation", order.cancellationReason)
                .param("outOfOrder", order.outOfOrderId?.value)
                .param("updatedAt", utc(order.updatedAt))
                .param("version", order.version)
                .update()
        check(updated == 1) { "A ordem de serviço ${order.id} mudou sem trava (versão ${order.version})" }
        order.version += 1
    }

    override fun page(
        filter: WorkOrderFilter,
        after: WorkOrderPageKey?,
        limit: Int,
    ): List<WorkOrder> {
        val conditions = mutableListOf<String>()
        val params = linkedMapOf<String, Any?>()
        filter.ownerUserId?.let {
            val owns = "opened_by = :ownerUser"
            conditions +=
                if (filter.ownerEmployeeId != null) {
                    "($owns OR assignee_employee_id = :ownerEmployee)"
                } else {
                    owns
                }
            params["ownerUser"] = it
            filter.ownerEmployeeId?.let { employee -> params["ownerEmployee"] = employee }
        }
        filter.status?.let {
            conditions += "status = :status"
            params["status"] = it.name
        }
        filter.roomId?.let {
            conditions += "room_id = :room"
            params["room"] = it.value
        }
        filter.areaId?.let {
            conditions += "area_id = :area"
            params["area"] = it.value
        }
        filter.priority?.let {
            conditions += "priority = :priority"
            params["priority"] = it.name
        }
        filter.assigneeEmployeeId?.let {
            conditions += "assignee_employee_id = :assignee"
            params["assignee"] = it.value
        }
        after?.let {
            conditions += "(opened_at, id) < (:afterAt, :afterId)"
            params["afterAt"] = utc(it.openedAt)
            params["afterId"] = it.id
        }
        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        var statement =
            jdbc
                .sql("SELECT * FROM $TABLE $where ORDER BY opened_at DESC, id DESC LIMIT :limit")
                .param("limit", limit)
        params.forEach { (name, value) -> statement = statement.param(name, value) }
        return statement.query { rs, _ -> order(rs) }.list()
    }

    private fun byId(
        id: WorkOrderId,
        lock: Boolean,
    ): WorkOrder? =
        jdbc
            .sql("SELECT * FROM $TABLE WHERE id = :id" + if (lock) " FOR UPDATE" else "")
            .param("id", id.value)
            .query { rs, _ -> order(rs) }
            .optional()
            .orElse(null)

    private fun order(rs: ResultSet) =
        WorkOrder.restore(
            WorkOrderId(rs.getObject("id", UUID::class.java)),
            DocumentNumber.parse(rs.getString("number")),
            rs.getObject("room_id", UUID::class.java)?.let(::RoomId),
            rs.getObject("area_id", UUID::class.java)?.let(::AreaId),
            WorkOrderCategory.valueOf(rs.getString("category")),
            WorkOrderPriority.valueOf(rs.getString("priority")),
            rs.getString("description"),
            WorkOrderOrigin.valueOf(rs.getString("origin")),
            rs.getObject("assignee_employee_id", UUID::class.java)?.let(::EmployeeId),
            rs.getString("third_party_name"),
            WorkOrderStatus.valueOf(rs.getString("status")),
            rs.getString("hold_reason"),
            Money.of(rs.getBigDecimal("labor_cost")),
            Money.of(rs.getBigDecimal("third_party_cost")),
            Money.of(rs.getBigDecimal("parts_cost")),
            UserId(rs.getObject("opened_by", UUID::class.java)),
            checkNotNull(instant(rs, "opened_at")),
            instant(rs, "due_at"),
            instant(rs, "started_at"),
            instant(rs, "closed_at"),
            rs.getString("completion_notes"),
            rs.getString("cancellation_reason"),
            rs.getObject("out_of_order_id", UUID::class.java)?.let(::OutOfOrderId),
            checkNotNull(instant(rs, "updated_at")),
            rs.getLong("version"),
        )

    private fun instant(
        rs: ResultSet,
        column: String,
    ): Instant? = rs.getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val TABLE = "maintenance.work_order"
    }
}

package com.zeny.hotel.maintenance.infrastructure

import com.zeny.hotel.maintenance.MaintenanceOffline
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.staff.StaffApi
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * As ordens de serviço como fonte do download offline ([MaintenanceOffline.WORK_ORDERS], D4-E08). A posição é do
 * trigger `work_order_offline_change` (abertura, edição e cada ação mudam a linha).
 *
 * - **Escopo:** com `ALL`, todas; com `OWN`, as que o usuário abriu e as de que o colaborador vinculado a ele
 *   (`StaffApi.employeeOfUser`) é o responsável. A ordem que sai do responsável (reatribuída ou sem responsável) vai a
 *   ele como *tombstone* gravado pela edição.
 * - **Itens:** as ordens não finais; `DONE` e `CANCELLED` vão como *tombstone* derivado da linha. Sem o motivo da
 *   espera, as notas de conclusão, o terceiro e os custos, nem dado de hóspede (RN-13).
 * - **Local:** o número da UH ou o nome da área pela `RoomsApi`, do momento da mudança.
 */
@Component
class WorkOrderOfflineSource(
    private val jdbc: JdbcClient,
    private val staff: StaffApi,
    private val rooms: RoomsApi,
) : OfflineChangeSource {
    override val source = MaintenanceOffline.WORK_ORDERS

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> {
        var ownerUser: UUID? = null
        var ownerEmployee: UUID? = null
        when (val scope = request.scope) {
            is ScopeFilter.All -> {
                Unit
            }

            is ScopeFilter.Own -> {
                ownerUser = scope.userId.value
                ownerEmployee = staff.employeeOfUser(scope.userId)?.id?.value
            }
        }
        return jdbc
            .sql(
                """
                SELECT w.id, w.number, w.room_id, w.area_id, w.category, w.priority, w.status, w.description,
                       w.assignee_employee_id, w.due_at, w.version, ${OfflineChangeRequest.positionColumns("w")}
                FROM maintenance.work_order w
                WHERE ${OfflineChangeRequest.window("w")}
                  AND (CAST(:ownerUser AS uuid) IS NULL OR w.opened_by = CAST(:ownerUser AS uuid)
                       OR (CAST(:ownerEmployee AS uuid) IS NOT NULL
                           AND w.assignee_employee_id = CAST(:ownerEmployee AS uuid)))
                ORDER BY w.change_xid, w.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams() + mapOf("ownerUser" to ownerUser, "ownerEmployee" to ownerEmployee))
            .query { rs, _ -> toChange(rs) }
            .list()
    }

    private fun toChange(rs: ResultSet): OfflineChange {
        val position = OfflinePosition.from(rs)
        val id = rs.getObject("id", UUID::class.java)
        val version = rs.getLong("version")
        val status = WorkOrderStatus.valueOf(rs.getString("status"))
        if (status.final) return OfflineChange.tombstone(position, id, version)
        val roomId = rs.getObject("room_id", UUID::class.java)
        val areaId = rs.getObject("area_id", UUID::class.java)
        return OfflineChange.upsert(
            position,
            id,
            version,
            OfflineWorkOrder(
                number = rs.getString("number"),
                roomId = roomId,
                roomNumber = roomId?.let { rooms.room(RoomId(it))?.number },
                areaId = areaId,
                areaName = areaId?.let { rooms.area(AreaId(it))?.name },
                category = WorkOrderCategory.valueOf(rs.getString("category")),
                priority = WorkOrderPriority.valueOf(rs.getString("priority")),
                status = status,
                description = rs.getString("description"),
                assigneeEmployeeId = rs.getObject("assignee_employee_id", UUID::class.java),
                dueAt = rs.getObject("due_at", OffsetDateTime::class.java)?.toInstant(),
            ),
        )
    }

    /**
     * O que o app do técnico recebe de cada ordem (RN-13): o local (UH **ou** área, com o número ou o nome do
     * momento), a categoria, a prioridade, o estado, a descrição, o responsável e o prazo. Sem o motivo da espera, as
     * notas, o terceiro nem os custos.
     */
    data class OfflineWorkOrder(
        val number: String,
        val roomId: UUID?,
        val roomNumber: String?,
        val areaId: UUID?,
        val areaName: String?,
        val category: WorkOrderCategory,
        val priority: WorkOrderPriority,
        val status: WorkOrderStatus,
        val description: String,
        val assigneeEmployeeId: UUID?,
        val dueAt: Instant?,
    )
}

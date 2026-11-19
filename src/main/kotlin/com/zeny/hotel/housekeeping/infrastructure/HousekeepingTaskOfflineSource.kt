package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.RefusalReason
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.staff.StaffApi
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * As tarefas de governança como fonte do download offline ([HousekeepingOffline.TASKS], D6-E19). A posição é do
 * trigger `housekeeping_task_offline_change` (geração, atribuição e cada transição mudam a linha).
 *
 * - **Escopo:** com `ALL`, todas; com `OWN`, só as da camareira vinculada ao usuário (`StaffApi.employeeOfUser`,
 *   filtrando por `assigned_employee_id`, a única fonte da posse atual). Usuário sem colaborador não recebe nenhuma.
 *   A tarefa que sai da camareira (reatribuída ou retirada) vai a ela como *tombstone* gravado pela publicação do plano.
 * - **Itens:** as tarefas abertas ([OPEN]), de qualquer data (o cursor é a posição, não a data), com a `version` que é o
 *   `ETag` do `If-Match` online; as demais (`PENDING`, `AWAITING_INSPECTION`, `DONE`, `CANCELLED`) vão como
 *   *tombstone*. Sem as `notes` (texto livre da recepção) nem dado de hóspede ou de reserva (RN-13).
 */
@Component
class HousekeepingTaskOfflineSource(
    private val jdbc: JdbcClient,
    private val staff: StaffApi,
) : OfflineChangeSource {
    override val source = HousekeepingOffline.TASKS

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> {
        val employee =
            when (val scope = request.scope) {
                is ScopeFilter.All -> null
                is ScopeFilter.Own -> staff.employeeOfUser(scope.userId)?.id?.value ?: return emptyList()
            }
        return jdbc
            .sql(
                """
                SELECT t.id, t.room_id, t.area_id, t.cleaning_type_id, t.business_date, t.status, t.priority,
                       t.assignment_position, t.arrival_expected, t.checklist_execution_id, t.refusal_reason,
                       t.version, ${OfflineChangeRequest.positionColumns("t")}
                FROM housekeeping.housekeeping_task t
                WHERE ${OfflineChangeRequest.window("t")}
                  AND (CAST(:employee AS uuid) IS NULL OR t.assigned_employee_id = CAST(:employee AS uuid))
                ORDER BY t.change_xid, t.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams() + ("employee" to employee))
            .query { rs, _ -> toChange(rs) }
            .list()
    }

    private fun toChange(rs: ResultSet): OfflineChange {
        val position = OfflinePosition.from(rs)
        val id = rs.getObject("id", UUID::class.java)
        val version = rs.getLong("version")
        val status = TaskStatus.valueOf(rs.getString("status"))
        if (status !in OPEN) return OfflineChange.tombstone(position, id, version)
        return OfflineChange.upsert(
            position,
            id,
            version,
            OfflineTask(
                roomId = rs.getObject("room_id", UUID::class.java),
                areaId = rs.getObject("area_id", UUID::class.java),
                cleaningTypeId = rs.getObject("cleaning_type_id", UUID::class.java),
                businessDate = rs.getObject("business_date", LocalDate::class.java),
                status = status,
                priority = rs.getInt("priority"),
                position = rs.getInt("assignment_position").takeUnless { rs.wasNull() },
                arrivalExpected = rs.getBoolean("arrival_expected"),
                checklistExecutionId = rs.getObject("checklist_execution_id", UUID::class.java),
                refusalReason = rs.getString("refusal_reason")?.let(RefusalReason::valueOf),
            ),
        )
    }

    /** O que o app da camareira recebe de cada tarefa (RN-13): sem `notes` nem dado de hóspede ou de reserva. */
    data class OfflineTask(
        val roomId: UUID?,
        val areaId: UUID?,
        val cleaningTypeId: UUID,
        val businessDate: LocalDate,
        val status: TaskStatus,
        val priority: Int,
        val position: Int?,
        val arrivalExpected: Boolean,
        val checklistExecutionId: UUID?,
        val refusalReason: RefusalReason?,
    )

    private companion object {
        /** As tarefas que a camareira ainda executa: as demais saem do aparelho. */
        val OPEN =
            setOf(
                TaskStatus.ASSIGNED,
                TaskStatus.IN_PROGRESS,
                TaskStatus.PAUSED,
                TaskStatus.REJECTED,
                TaskStatus.REFUSED,
            )
    }
}

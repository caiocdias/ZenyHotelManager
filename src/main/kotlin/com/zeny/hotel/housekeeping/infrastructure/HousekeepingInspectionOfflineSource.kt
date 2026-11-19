package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.HousekeepingOffline
import com.zeny.hotel.housekeeping.TaskStatus
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.ScopeFilter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * As tarefas que aguardam inspeção como fonte do download offline do inspetor ([HousekeepingOffline.INSPECTIONS],
 * D7-E05). A posição é a do trigger `housekeeping_task_offline_change` da tarefa: a limpeza concluída, o início da
 * inspeção (a versão sobe) e a conclusão mudam a linha.
 *
 * - **Escopo:** a permissão `housekeeping.inspections.perform` só existe com `ALL`: todas as tarefas. Um escopo `OWN`
 *   (que o catálogo não concede) não recebe nada.
 * - **Itens:** as tarefas `AWAITING_INSPECTION`, de qualquer data, com a `version` que é o `ETag` do `If-Match` online;
 *   as demais vão como *tombstone* (a que deixa de aguardar sai do aparelho). Só ids, tipo e data: sem as `notes`
 *   (texto livre da recepção) nem dado de hóspede ou de reserva (RN-13).
 */
@Component
class HousekeepingInspectionOfflineSource(
    private val jdbc: JdbcClient,
) : OfflineChangeSource {
    override val source = HousekeepingOffline.INSPECTIONS

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> {
        if (request.scope !is ScopeFilter.All) return emptyList()
        return jdbc
            .sql(
                """
                SELECT t.id, t.room_id, t.area_id, t.cleaning_type_id, t.business_date, t.status,
                       t.checklist_execution_id, t.version, ${OfflineChangeRequest.positionColumns("t")}
                FROM housekeeping.housekeeping_task t
                WHERE ${OfflineChangeRequest.window("t")}
                ORDER BY t.change_xid, t.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams())
            .query { rs, _ -> toChange(rs) }
            .list()
    }

    private fun toChange(rs: ResultSet): OfflineChange {
        val position = OfflinePosition.from(rs)
        val id = rs.getObject("id", UUID::class.java)
        val version = rs.getLong("version")
        if (TaskStatus.valueOf(rs.getString("status")) != TaskStatus.AWAITING_INSPECTION) {
            return OfflineChange.tombstone(position, id, version)
        }
        return OfflineChange.upsert(
            position,
            id,
            version,
            OfflineInspectionTask(
                roomId = rs.getObject("room_id", UUID::class.java),
                areaId = rs.getObject("area_id", UUID::class.java),
                cleaningTypeId = rs.getObject("cleaning_type_id", UUID::class.java),
                businessDate = rs.getObject("business_date", LocalDate::class.java),
                checklistExecutionId = rs.getObject("checklist_execution_id", UUID::class.java),
            ),
        )
    }

    /**
     * O que o aparelho do inspetor recebe de cada tarefa que aguarda inspeção (RN-13): o local, o tipo, a data e a
     * execução do checklist da limpeza ([checklistExecutionId], id opaco do `checklists`, nulo sem checklist).
     */
    data class OfflineInspectionTask(
        val roomId: UUID?,
        val areaId: UUID?,
        val cleaningTypeId: UUID,
        val businessDate: LocalDate,
        val checklistExecutionId: UUID?,
    )
}

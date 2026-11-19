package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.InspectionId
import com.zeny.hotel.housekeeping.InspectionResult
import com.zeny.hotel.housekeeping.InspectionStatus
import com.zeny.hotel.housekeeping.application.InspectionStore
import com.zeny.hotel.housekeeping.domain.Inspection
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * As inspeções (D2-E05) por `JdbcClient`, sempre parametrizadas e só em `housekeeping.inspection`: `INSERT`, a leitura
 * da aberta de uma tarefa com a linha travada (`FOR UPDATE`, depois da tarefa) e o `UPDATE` da conclusão. Nunca
 * apaga. Os itens reprovados vão como `text[]` pelo `createArrayOf` do driver.
 */
@Component
class JdbcInspectionStore(
    private val jdbc: JdbcClient,
) : InspectionStore {
    override fun insert(inspection: Inspection) {
        jdbc
            .sql(
                """
                INSERT INTO $INSPECTION_TABLE (id, task_id, room_id, checklist_execution_id, status, result,
                    rejected_items, inspector_id, started_at, completed_at)
                VALUES (:id, :task, :room, :execution, :status, :result, :items, :inspector,
                    :startedAt, :completedAt)
                """.trimIndent(),
            ).param("id", inspection.id.value)
            .param("task", inspection.taskId?.value)
            .param("room", inspection.roomId.value)
            .param("execution", inspection.checklistExecutionId?.value)
            .param("status", inspection.status.name)
            .param("result", inspection.result?.name)
            .param("items", textArray(inspection.rejectedItems))
            .param("inspector", inspection.inspectorId.value)
            .param("startedAt", utc(inspection.startedAt))
            .param("completedAt", inspection.completedAt?.let(::utc))
            .update()
    }

    override fun openForUpdate(taskId: HousekeepingTaskId): Inspection? =
        jdbc
            .sql("SELECT * FROM $INSPECTION_TABLE WHERE task_id = :task AND status = 'IN_PROGRESS' FOR UPDATE")
            .param("task", taskId.value)
            .query { rs, _ -> inspection(rs) }
            .optional()
            .orElse(null)

    override fun lastRejectedItems(taskIds: Collection<HousekeepingTaskId>): Map<HousekeepingTaskId, List<String>> {
        if (taskIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT DISTINCT ON (task_id) task_id, rejected_items FROM $INSPECTION_TABLE
                WHERE task_id IN (:tasks) AND status = 'COMPLETED' AND result = 'REJECTED'
                ORDER BY task_id, completed_at DESC, id DESC
                """.trimIndent(),
            ).param("tasks", taskIds.map { it.value })
            .query { rs, _ -> HousekeepingTaskId(rs.getObject("task_id", UUID::class.java)) to items(rs) }
            .list()
            .toMap()
    }

    override fun update(inspection: Inspection) {
        jdbc
            .sql(
                """
                UPDATE $INSPECTION_TABLE SET status = :status, result = :result,
                    rejected_items = :items, inspector_id = :inspector, completed_at = :completedAt
                WHERE id = :id
                """.trimIndent(),
            ).param("id", inspection.id.value)
            .param("status", inspection.status.name)
            .param("result", inspection.result?.name)
            .param("items", textArray(inspection.rejectedItems))
            .param("inspector", inspection.inspectorId.value)
            .param("completedAt", inspection.completedAt?.let(::utc))
            .update()
    }

    private fun inspection(rs: ResultSet): Inspection =
        Inspection.restore(
            InspectionId(rs.getObject("id", UUID::class.java)),
            rs.getObject("task_id", UUID::class.java)?.let(::HousekeepingTaskId),
            RoomId(rs.getObject("room_id", UUID::class.java)),
            rs.getObject("checklist_execution_id", UUID::class.java)?.let(::ChecklistExecutionId),
            UserId(rs.getObject("inspector_id", UUID::class.java)),
            rs.getObject("started_at", OffsetDateTime::class.java).toInstant(),
            InspectionStatus.valueOf(rs.getString("status")),
            rs.getString("result")?.let(InspectionResult::valueOf),
            items(rs),
            rs.getObject("completed_at", OffsetDateTime::class.java)?.toInstant(),
        )

    private fun items(rs: ResultSet): List<String> =
        (rs.getArray("rejected_items").array as Array<*>).map { it.toString() }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    /** As chaves como `text[]` do driver (`createArrayOf`), sem montar literal à mão: as chaves vêm do cliente. */
    private fun textArray(items: List<String>): AbstractSqlTypeValue =
        object : AbstractSqlTypeValue() {
            override fun createTypeValue(
                connection: Connection,
                sqlType: Int,
                typeName: String?,
            ): Any = connection.createArrayOf("text", items.toTypedArray())
        }
}

/** As inspeções de UH (D2-E05): alteradas pela conclusão, nunca apagadas. */
internal const val INSPECTION_TABLE = "housekeeping.inspection"

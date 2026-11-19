package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.application.TaskConflict
import com.zeny.hotel.housekeeping.application.TaskConflictStore
import com.zeny.hotel.staff.EmployeeId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * O registro dos conflitos offline (D4-E19) por `JdbcClient`: só `INSERT` e `SELECT` em `housekeeping.task_conflict`
 * (só de inserção, declarada em [HousekeepingAppendOnlyTablesConfiguration]), sempre parametrizados; o resumo vai como
 * `jsonb`.
 */
@Component
class JdbcTaskConflictStore(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : TaskConflictStore {
    override fun record(conflict: TaskConflict) {
        jdbc
            .sql(
                """
                INSERT INTO $TASK_CONFLICT_TABLE (id, task_id, command_id, command_type, user_id, employee_id,
                    occurred_at, task_status, summary, recorded_at)
                VALUES (:id, :task, :command, :type, :user, :employee, :occurredAt, :status, CAST(:summary AS jsonb),
                    :recordedAt)
                """.trimIndent(),
            ).param("id", conflict.id)
            .param("task", conflict.taskId.value)
            .param("command", conflict.commandId)
            .param("type", conflict.commandType.value)
            .param("user", conflict.userId.value)
            .param("employee", conflict.employeeId.value)
            .param("occurredAt", OffsetDateTime.ofInstant(conflict.occurredAt, ZoneOffset.UTC))
            .param("status", conflict.taskStatus.name)
            .param("summary", jsonMapper.writeValueAsString(conflict.summary))
            .param("recordedAt", OffsetDateTime.ofInstant(conflict.recordedAt, ZoneOffset.UTC))
            .update()
    }

    override fun hasConflict(
        taskId: HousekeepingTaskId,
        employeeId: EmployeeId,
    ): Boolean =
        jdbc
            .sql(
                "SELECT EXISTS (SELECT 1 FROM $TASK_CONFLICT_TABLE WHERE task_id = :task AND employee_id = :employee)",
            ).param("task", taskId.value)
            .param("employee", employeeId.value)
            .query(Boolean::class.java)
            .single()
}

/** O registro dos conflitos offline das tarefas (D4-E19), só de inserção. */
internal const val TASK_CONFLICT_TABLE = "housekeeping.task_conflict"

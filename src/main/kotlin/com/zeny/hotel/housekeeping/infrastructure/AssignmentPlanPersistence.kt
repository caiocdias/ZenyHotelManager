package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.AssignmentPlanId
import com.zeny.hotel.housekeeping.AssignmentPlanStatus
import com.zeny.hotel.housekeeping.HousekeepingTaskId
import com.zeny.hotel.housekeeping.application.AssignmentPlanStore
import com.zeny.hotel.housekeeping.domain.AssignmentPlan
import com.zeny.hotel.housekeeping.domain.AssignmentPlanItem
import com.zeny.hotel.housekeeping.domain.AttendantId
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os planos do dia pelo `JdbcClient`, só no schema `housekeeping` e sempre parametrizado: a criação por `INSERT … ON
 * CONFLICT DO NOTHING` na data única, a leitura com a linha travada (`FOR UPDATE`) e a gravação que confere a versão e
 * regrava os itens inteiros.
 */
@Component
class JdbcAssignmentPlanStore(
    private val jdbc: JdbcClient,
) : AssignmentPlanStore {
    override fun createIfAbsent(plan: AssignmentPlan): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO housekeeping.assignment_plan (id, business_date, status, version, created_at, updated_at)
                VALUES (:id, :date, :status, :version, :createdAt, :updatedAt)
                ON CONFLICT ON CONSTRAINT assignment_plan_date_uk DO NOTHING
                """.trimIndent(),
            ).param("id", plan.id.value)
            .param("date", plan.businessDate)
            .param("status", plan.status.name)
            .param("version", plan.version)
            .param("createdAt", plan.createdAt.utc())
            .param("updatedAt", plan.updatedAt.utc())
            .update() == 1

    override fun lockByDate(date: LocalDate): AssignmentPlan? = byDate(date, lock = true)

    override fun byDate(date: LocalDate): AssignmentPlan? = byDate(date, lock = false)

    override fun update(plan: AssignmentPlan) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE housekeeping.assignment_plan SET status = :status, version = :version, updated_at = :now
                    WHERE id = :id AND version = :version - 1
                    """.trimIndent(),
                ).param("id", plan.id.value)
                .param("status", plan.status.name)
                .param("version", plan.version)
                .param("now", plan.updatedAt.utc())
                .update()
        check(updated == 1) { "Plano ${plan.id} não gravado: versão ${plan.version - 1} não confere" }
        jdbc
            .sql("DELETE FROM housekeeping.assignment_plan_item WHERE plan_id = :id")
            .param("id", plan.id.value)
            .update()
        plan.items.forEach { item ->
            jdbc
                .sql(
                    "INSERT INTO housekeeping.assignment_plan_item (plan_id, task_id, employee_id, position) " +
                        "VALUES (:plan, :task, :employee, :position)",
                ).param("plan", plan.id.value)
                .param("task", item.taskId.value)
                .param("employee", item.attendant?.value)
                .param("position", item.position)
                .update()
        }
    }

    private fun byDate(
        date: LocalDate,
        lock: Boolean,
    ): AssignmentPlan? {
        val row =
            jdbc
                .sql(
                    "SELECT * FROM housekeeping.assignment_plan WHERE business_date = :date" +
                        if (lock) " FOR UPDATE" else "",
                ).param("date", date)
                .query { rs, _ -> PlanRow.of(rs) }
                .optional()
                .orElse(null) ?: return null
        val items =
            jdbc
                .sql(
                    "SELECT task_id, employee_id, position FROM housekeeping.assignment_plan_item " +
                        "WHERE plan_id = :id ORDER BY position, task_id",
                ).param("id", row.id)
                .query { rs, _ ->
                    AssignmentPlanItem(
                        HousekeepingTaskId(rs.getObject("task_id", UUID::class.java)),
                        rs.getObject("employee_id", UUID::class.java)?.let(::AttendantId),
                        rs.getInt("position"),
                    )
                }.list()
        return row.plan(items)
    }

    private data class PlanRow(
        val id: UUID,
        val date: LocalDate,
        val status: AssignmentPlanStatus,
        val version: Long,
        val createdAt: Instant,
        val updatedAt: Instant,
        val publishedAt: Instant?,
        val publishedBy: UUID?,
    ) {
        fun plan(items: List<AssignmentPlanItem>) =
            AssignmentPlan(
                AssignmentPlanId(id),
                date,
                status,
                version,
                items,
                createdAt,
                updatedAt,
                publishedAt,
                publishedBy?.let(::UserId),
            )

        companion object {
            fun of(rs: ResultSet) =
                PlanRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("business_date", LocalDate::class.java),
                    AssignmentPlanStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant(),
                    rs.getObject("published_by", UUID::class.java),
                )
        }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}

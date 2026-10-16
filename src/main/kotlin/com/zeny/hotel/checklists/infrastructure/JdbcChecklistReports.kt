package com.zeny.hotel.checklists.infrastructure

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.application.CheckCounts
import com.zeny.hotel.checklists.application.ChecklistReportsStore
import com.zeny.hotel.checklists.application.FailedItemRow
import com.zeny.hotel.checklists.application.LocationHistoryRow
import com.zeny.hotel.checklists.application.ReportQuery
import com.zeny.hotel.checklists.application.TemplateComplianceRow
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
 * As consultas dos relatórios de checklists pelo `JdbcClient` (ARCHITECTURE §11, D5). Leem **só** as views
 * `checklists.report_*`, a API de leitura do módulo (a mesma que o `reporting` usará no M6), nunca as tabelas
 * internas; agregam e filtram aqui, porque cada linha das views é um fato (uma execução ou uma resposta).
 */
@Component
class JdbcChecklistReports(
    private val jdbc: JdbcClient,
) : ChecklistReportsStore {
    override fun compliance(query: ReportQuery): List<TemplateComplianceRow> =
        jdbc
            .sql(
                """
                SELECT r.template_id, r.template_name, r.context, count(*) AS executions,
                       sum(r.ok_count) AS ok_count, sum(r.not_ok_count) AS not_ok_count,
                       sum(r.not_applicable_count) AS not_applicable_count
                FROM checklists.report_execution_compliance r
                WHERE ${filters("r", "completed_at")}
                GROUP BY r.template_id, r.template_name, r.context
                ORDER BY r.template_name, r.template_id
                """.trimIndent(),
            ).params(params(query))
            .query { rs, _ ->
                TemplateComplianceRow(
                    ChecklistTemplateId(rs.uuid("template_id")),
                    rs.getString("template_name"),
                    ChecklistContext.valueOf(rs.getString("context")),
                    rs.getLong("executions"),
                    outcomes(rs),
                )
            }.list()

    override fun failedItems(
        query: ReportQuery,
        limit: Int,
    ): List<FailedItemRow> =
        jdbc
            .sql(
                """
                SELECT o.template_id, o.template_name, o.item_key,
                       (array_agg(o.item_label ORDER BY o.version_number DESC, o.completed_at DESC))[1] AS item_label,
                       count(*) FILTER (WHERE o.outcome = 'OK') AS ok_count,
                       count(*) FILTER (WHERE o.outcome = 'NOT_OK') AS not_ok_count,
                       count(*) FILTER (WHERE o.outcome = 'NOT_APPLICABLE') AS not_applicable_count
                FROM checklists.report_check_item_outcome o
                WHERE ${filters("o", "completed_at")}
                GROUP BY o.template_id, o.template_name, o.item_key
                HAVING count(*) FILTER (WHERE o.outcome = 'NOT_OK') > 0
                ORDER BY not_ok_count DESC,
                         CAST(count(*) FILTER (WHERE o.outcome = 'NOT_OK') AS numeric)
                             / count(*) FILTER (WHERE o.outcome IN ('OK', 'NOT_OK')) DESC,
                         o.template_name, o.template_id, o.item_key
                LIMIT :limit
                """.trimIndent(),
            ).params(params(query))
            .param("limit", limit)
            .query { rs, _ ->
                FailedItemRow(
                    ChecklistTemplateId(rs.uuid("template_id")),
                    rs.getString("template_name"),
                    rs.getString("item_key"),
                    rs.getString("item_label"),
                    outcomes(rs),
                )
            }.list()

    override fun locationHistory(
        query: ReportQuery,
        before: Pair<Instant, UUID>?,
        limit: Int,
    ): List<LocationHistoryRow> =
        jdbc
            .sql(
                """
                SELECT h.* FROM checklists.report_location_history h
                WHERE ${filters("h", "reference_at")}
                  AND (CAST(:beforeAt AS timestamptz) IS NULL
                       OR (h.reference_at, h.execution_id) < (CAST(:beforeAt AS timestamptz), CAST(:beforeId AS uuid)))
                ORDER BY h.reference_at DESC, h.execution_id DESC LIMIT :limit
                """.trimIndent(),
            ).params(params(query))
            .param("beforeAt", before?.first?.let(::utc))
            .param("beforeId", before?.second)
            .param("limit", limit)
            .query { rs, _ -> historyRow(rs) }
            .list()

    /** Os filtros comuns sobre o [alias] da view, com o período na coluna de instante [instantColumn]. */
    private fun filters(
        alias: String,
        instantColumn: String,
    ): String =
        """
        (CAST(:from AS timestamptz) IS NULL OR $alias.$instantColumn >= CAST(:from AS timestamptz))
          AND (CAST(:to AS timestamptz) IS NULL OR $alias.$instantColumn < CAST(:to AS timestamptz))
          AND (CAST(:templateId AS uuid) IS NULL OR $alias.template_id = CAST(:templateId AS uuid))
          AND (CAST(:subjectType AS text) IS NULL OR $alias.subject_type = CAST(:subjectType AS text))
          AND (CAST(:subjectId AS uuid) IS NULL OR $alias.subject_id = CAST(:subjectId AS uuid))
        """.trimIndent()

    private fun params(query: ReportQuery): Map<String, Any?> =
        mapOf(
            "from" to query.from?.let(::utc),
            "to" to query.to?.let(::utc),
            "templateId" to query.templateId?.value,
            "subjectType" to query.subjectType,
            "subjectId" to query.subjectId,
        )

    private fun historyRow(rs: ResultSet) =
        LocationHistoryRow(
            executionId = ChecklistExecutionId(rs.uuid("execution_id")),
            templateId = ChecklistTemplateId(rs.uuid("template_id")),
            templateName = rs.getString("template_name"),
            context = ChecklistContext.valueOf(rs.getString("context")),
            versionNumber = rs.getInt("version_number").takeUnless { rs.wasNull() },
            status = ChecklistExecutionStatus.valueOf(rs.getString("status")),
            routineId = rs.uuidOrNull("routine_id")?.let(::ChecklistRoutineId),
            businessDate = rs.getObject("business_date", LocalDate::class.java),
            dueAt = rs.instantOrNull("due_at"),
            duplicateOf = rs.uuidOrNull("duplicate_of")?.let(::ChecklistExecutionId),
            executorId = rs.uuidOrNull("executor_id")?.let(::UserId),
            startedAt = rs.instantOrNull("started_at"),
            completedAt = rs.instantOrNull("completed_at"),
            referenceAt = rs.getObject("reference_at", OffsetDateTime::class.java).toInstant(),
            outcomes = outcomes(rs),
        )

    private fun outcomes(rs: ResultSet) =
        CheckCounts(rs.getLong("ok_count"), rs.getLong("not_ok_count"), rs.getLong("not_applicable_count"))
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

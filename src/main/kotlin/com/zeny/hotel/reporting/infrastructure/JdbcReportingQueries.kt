package com.zeny.hotel.reporting.infrastructure

import com.zeny.hotel.reporting.application.AttendantProductivityRow
import com.zeny.hotel.reporting.application.CleaningTypeRow
import com.zeny.hotel.reporting.application.InspectionCountsRow
import com.zeny.hotel.reporting.application.ReportingQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * As consultas dos relatórios pelo `JdbcClient` (ARCHITECTURE §11). Leem **só** as views `report_*` dos módulos
 * (`housekeeping.report_tasks`, `housekeeping.report_inspections`, `rooms.report_room_status`…), nunca as tabelas
 * internas: nenhum módulo de negócio é importado, e o `ReportingViewsOnlyTest` roda cada método com um papel que só lê
 * as views. Cada linha das views é um fato; a agregação é daqui.
 */
@Component
class JdbcReportingQueries(
    private val jdbc: JdbcClient,
) : ReportingQueries {
    override fun attendantProductivity(
        from: LocalDate,
        to: LocalDate,
    ): List<AttendantProductivityRow> =
        jdbc
            .sql(
                """
                SELECT t.assigned_employee_id AS employee_id,
                       count(*) FILTER (WHERE t.status IN $DONE AND t.room_id IS NOT NULL) AS rooms,
                       coalesce(sum(t.credits) FILTER (WHERE t.status IN $DONE), 0) AS credits
                FROM housekeeping.report_tasks t
                WHERE t.business_date BETWEEN :from AND :to
                  AND t.assigned_employee_id IS NOT NULL
                GROUP BY t.assigned_employee_id
                ORDER BY credits DESC, t.assigned_employee_id
                """.trimIndent(),
            ).param("from", from)
            .param("to", to)
            .query {
                rs,
                _,
                ->
                AttendantProductivityRow(rs.uuid("employee_id"), rs.getLong("rooms"), rs.getLong("credits"))
            }.list()

    override fun cleaningByType(
        from: LocalDate,
        to: LocalDate,
    ): List<CleaningTypeRow> =
        jdbc
            .sql(
                """
                SELECT t.cleaning_type_id, s.room_type_id,
                       count(*) FILTER (WHERE t.status IN $DONE) AS tasks,
                       coalesce(sum(t.worked_seconds) FILTER (WHERE t.status IN $DONE), 0) AS worked_seconds,
                       coalesce(sum(i.inspections), 0) AS inspections,
                       coalesce(sum(i.rejections), 0) AS rejections
                FROM housekeeping.report_tasks t
                         JOIN rooms.report_room_status s ON s.room_id = t.room_id
                         LEFT JOIN LATERAL (
                    SELECT count(*) AS inspections, count(*) FILTER (WHERE r.result = 'REJECTED') AS rejections
                    FROM housekeeping.report_inspections r
                    WHERE r.task_id = t.task_id) i ON true
                WHERE t.business_date BETWEEN :from AND :to
                GROUP BY t.cleaning_type_id, s.room_type_id
                ORDER BY t.cleaning_type_id, s.room_type_id
                """.trimIndent(),
            ).param("from", from)
            .param("to", to)
            .query { rs, _ ->
                CleaningTypeRow(
                    rs.uuid("cleaning_type_id"),
                    rs.uuid("room_type_id"),
                    rs.getLong("tasks"),
                    rs.getLong("worked_seconds"),
                    rs.getLong("inspections"),
                    rs.getLong("rejections"),
                )
            }.list()

    override fun voluntaryInspections(
        from: Instant,
        to: Instant,
    ): InspectionCountsRow =
        jdbc
            .sql(
                """
                SELECT count(*) AS inspections, count(*) FILTER (WHERE result = 'REJECTED') AS rejections
                FROM housekeeping.report_inspections
                WHERE task_id IS NULL AND completed_at >= :from AND completed_at < :to
                """.trimIndent(),
            ).param("from", from.atOffset(ZoneOffset.UTC))
            .param("to", to.atOffset(ZoneOffset.UTC))
            .query { rs, _ -> InspectionCountsRow(rs.getLong("inspections"), rs.getLong("rejections")) }
            .single()

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private companion object {
        /** Os status que contam como limpeza feita (contrato de `housekeeping.report_tasks`). */
        const val DONE = "('AWAITING_INSPECTION', 'DONE')"
    }
}

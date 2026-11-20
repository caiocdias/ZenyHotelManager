package com.zeny.hotel.reporting.infrastructure

import com.zeny.hotel.reporting.application.AttendantProductivityRow
import com.zeny.hotel.reporting.application.CleaningTypeRow
import com.zeny.hotel.reporting.application.DayCapacityRow
import com.zeny.hotel.reporting.application.DayMovementsRow
import com.zeny.hotel.reporting.application.InspectionCountsRow
import com.zeny.hotel.reporting.application.OpenCashierShiftRow
import com.zeny.hotel.reporting.application.OpenFolioBalanceRow
import com.zeny.hotel.reporting.application.OutOfOrderCountsRow
import com.zeny.hotel.reporting.application.PendingFnrhSubmissionRow
import com.zeny.hotel.reporting.application.ReportingQueries
import com.zeny.hotel.reporting.application.RoomCountsRow
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
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

    override fun dayMovements(
        from: LocalDate,
        to: LocalDate,
    ): List<DayMovementsRow> =
        jdbc
            .sql(
                """
                SELECT d, sum(arrivals) AS arrivals, sum(departures) AS departures, sum(hosted) AS hosted,
                       sum(occupied) AS occupied
                FROM (SELECT arrival AS d, 1 AS arrivals, 0 AS departures, 0 AS hosted, 0 AS occupied
                      FROM reservations.report_reservations
                      WHERE status IN $SOLD AND arrival BETWEEN :from AND :to
                      UNION ALL
                      SELECT departure, 0, 1, 0, 0
                      FROM reservations.report_reservations
                      WHERE status IN $SOLD AND departure BETWEEN :from AND :to
                      UNION ALL
                      SELECT stay_date, 0, 0, CASE WHEN day_use THEN 0 ELSE 1 END,
                             CASE WHEN counts_in_occupancy THEN 1 ELSE 0 END
                      FROM reservations.report_room_nights
                      WHERE sold AND stay_date BETWEEN :from AND :to) movements
                GROUP BY d
                ORDER BY d
                """.trimIndent(),
            ).param("from", from)
            .param("to", to)
            .query { rs, _ ->
                DayMovementsRow(
                    rs.getObject("d", LocalDate::class.java),
                    rs.getLong("arrivals"),
                    rs.getLong("departures"),
                    rs.getLong("hosted"),
                    rs.getLong("occupied"),
                )
            }.list()

    override fun dayCapacity(
        from: LocalDate,
        to: LocalDate,
    ): List<DayCapacityRow> =
        jdbc
            .sql(
                """
                SELECT stay_date, sum(total - out_of_order) AS rooms
                FROM reservations.report_inventory
                WHERE stay_date BETWEEN :from AND :to
                GROUP BY stay_date
                ORDER BY stay_date
                """.trimIndent(),
            ).param("from", from)
            .param("to", to)
            .query { rs, _ -> DayCapacityRow(rs.getObject("stay_date", LocalDate::class.java), rs.getLong("rooms")) }
            .list()

    override fun roomCounts(): RoomCountsRow =
        jdbc
            .sql(
                """
                SELECT count(*) AS active,
                       count(*) FILTER (WHERE occupancy = 'OCCUPIED') AS occupied,
                       count(*) FILTER (WHERE occupancy = 'VACANT') AS vacant,
                       count(*) FILTER (WHERE housekeeping_status = 'DIRTY') AS dirty,
                       count(*) FILTER (WHERE housekeeping_status = 'CLEAN') AS clean,
                       count(*) FILTER (WHERE housekeeping_status = 'INSPECTED') AS inspected,
                       count(*) FILTER (WHERE inspection_pending) AS inspection_pending
                FROM rooms.report_room_status
                WHERE active
                """.trimIndent(),
            ).query { rs, _ ->
                RoomCountsRow(
                    rs.getLong("active"),
                    rs.getLong("occupied"),
                    rs.getLong("vacant"),
                    rs.getLong("dirty"),
                    rs.getLong("clean"),
                    rs.getLong("inspected"),
                    rs.getLong("inspection_pending"),
                )
            }.single()

    override fun outOfOrderOn(on: LocalDate): OutOfOrderCountsRow =
        jdbc
            .sql(
                """
                SELECT count(DISTINCT b.room_id) FILTER (WHERE b.kind = 'OUT_OF_ORDER') AS out_of_order,
                       count(DISTINCT b.room_id) FILTER (WHERE b.kind = 'OUT_OF_SERVICE') AS out_of_service
                FROM rooms.report_out_of_order b
                         JOIN rooms.report_room_status s ON s.room_id = b.room_id AND s.active
                WHERE b.starts_on <= :on AND b.ends_on >= :on AND (b.released_on IS NULL OR b.released_on > :on)
                """.trimIndent(),
            ).param("on", on)
            .query { rs, _ -> OutOfOrderCountsRow(rs.getLong("out_of_order"), rs.getLong("out_of_service")) }
            .single()

    override fun openFolioBalances(): List<OpenFolioBalanceRow> =
        jdbc
            .sql(
                """
                SELECT f.folio_id, f.folio_number, r.reservation_id, r.reservation_code, r.departure,
                       coalesce(p.total, 0) - coalesce(y.total, 0) AS balance
                FROM billing.report_folios f
                         JOIN reservations.report_reservations r
                              ON f.owner_type = 'RESERVATION' AND f.owner_id = r.reservation_id
                         LEFT JOIN LATERAL (SELECT sum(amount) AS total FROM billing.report_postings
                                            WHERE folio_id = f.folio_id) p ON true
                         LEFT JOIN LATERAL (SELECT sum(net_amount) AS total FROM billing.report_payments
                                            WHERE folio_id = f.folio_id) y ON true
                WHERE f.status = 'OPEN' AND r.status = 'CHECKED_OUT'
                  AND coalesce(p.total, 0) - coalesce(y.total, 0) <> 0
                ORDER BY r.departure, f.folio_number
                """.trimIndent(),
            ).query { rs, _ ->
                OpenFolioBalanceRow(
                    rs.uuid("folio_id"),
                    rs.getString("folio_number"),
                    rs.uuid("reservation_id"),
                    rs.getString("reservation_code"),
                    rs.getObject("departure", LocalDate::class.java),
                    rs.getBigDecimal("balance"),
                )
            }.list()

    override fun openCashierShifts(): List<OpenCashierShiftRow> =
        jdbc
            .sql(
                """
                SELECT shift_id, shift_number, cash_point_name, operator_id, opened_on, opened_at
                FROM billing.report_cashier_shifts
                WHERE status = 'OPEN'
                ORDER BY opened_at, shift_id
                """.trimIndent(),
            ).query { rs, _ ->
                OpenCashierShiftRow(
                    rs.uuid("shift_id"),
                    rs.getString("shift_number"),
                    rs.getString("cash_point_name"),
                    rs.uuid("operator_id"),
                    rs.getObject("opened_on", LocalDate::class.java),
                    rs.getObject("opened_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    override fun pendingFnrhSubmissions(): List<PendingFnrhSubmissionRow> =
        jdbc
            .sql(
                """
                SELECT s.operation, s.status, s.reservation_id, r.reservation_code, s.attempts, s.last_failure,
                       s.created_at
                FROM fnrh.report_submissions s
                         LEFT JOIN reservations.report_reservations r ON r.reservation_id = s.reservation_id
                WHERE s.status IN ('PENDING', 'FAILED', 'INVALID')
                ORDER BY s.created_at, s.submission_id
                """.trimIndent(),
            ).query { rs, _ ->
                PendingFnrhSubmissionRow(
                    rs.getString("operation"),
                    rs.getString("status"),
                    rs.uuid("reservation_id"),
                    rs.getString("reservation_code"),
                    rs.getInt("attempts"),
                    rs.getString("last_failure"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private companion object {
        /** Os status que contam como limpeza feita (contrato de `housekeeping.report_tasks`). */
        const val DONE = "('AWAITING_INSPECTION', 'DONE')"

        /** As situações de reserva "vendida" (contrato de `reservations.report_room_nights`). */
        const val SOLD = "('PROVISIONAL', 'CONFIRMED', 'IN_HOUSE', 'CHECKED_OUT')"
    }
}

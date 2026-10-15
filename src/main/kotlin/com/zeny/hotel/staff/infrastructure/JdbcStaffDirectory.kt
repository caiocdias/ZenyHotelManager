package com.zeny.hotel.staff.infrastructure

import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.EmployeeSummary
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.ScheduleEntrySource
import com.zeny.hotel.staff.SchedulePatternKind
import com.zeny.hotel.staff.ShiftAssignment
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.ShiftSummary
import com.zeny.hotel.staff.application.DepartmentView
import com.zeny.hotel.staff.application.EmployeeView
import com.zeny.hotel.staff.application.EntryShiftView
import com.zeny.hotel.staff.application.ScheduleEntryView
import com.zeny.hotel.staff.application.ScheduleFilter
import com.zeny.hotel.staff.application.SchedulePatternView
import com.zeny.hotel.staff.application.ShiftView
import com.zeny.hotel.staff.application.StaffDirectory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/** Leituras de `staff` pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizadas. */
@Component
@Suppress("TooManyFunctions") // uma consulta por leitura da API
class JdbcStaffDirectory(
    private val jdbc: JdbcClient,
) : StaffDirectory {
    override fun departments(
        afterId: UUID?,
        limit: Int,
    ): List<DepartmentView> =
        jdbc
            .sql(
                """
                SELECT id, system_key, name, created_at, updated_at, version FROM staff.department
                WHERE CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid)
                ORDER BY id LIMIT :limit
                """.trimIndent(),
            ).param("after", afterId)
            .param("limit", limit)
            .query { rs, _ ->
                DepartmentView(
                    DepartmentId(rs.uuid("id")),
                    rs.getString("system_key")?.let(DepartmentKey::valueOf),
                    rs.getString("name"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun employees(
        departmentId: DepartmentId?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<EmployeeView> =
        jdbc
            .sql(
                """
                SELECT * FROM staff.employee
                WHERE (CAST(:department AS uuid) IS NULL OR department_id = CAST(:department AS uuid))
                  AND (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                  AND (CAST(:afterName AS text) IS NULL
                       OR (lower(name), id) > (CAST(:afterName AS text), CAST(:afterId AS uuid)))
                ORDER BY lower(name), id LIMIT :limit
                """.trimIndent(),
            ).param("department", departmentId?.value)
            .param("active", active)
            .param("afterName", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.employee() }
            .list()

    override fun shifts(
        departmentId: DepartmentId?,
        afterId: UUID?,
        limit: Int,
    ): List<ShiftView> =
        jdbc
            .sql(
                """
                SELECT * FROM staff.shift
                WHERE (CAST(:department AS uuid) IS NULL OR department_id = CAST(:department AS uuid))
                  AND (CAST(:after AS uuid) IS NULL OR id > CAST(:after AS uuid))
                ORDER BY id LIMIT :limit
                """.trimIndent(),
            ).param("department", departmentId?.value)
            .param("after", afterId)
            .param("limit", limit)
            .query { rs, _ ->
                ShiftView(
                    ShiftId(rs.uuid("id")),
                    DepartmentId(rs.uuid("department_id")),
                    rs.getString("name"),
                    rs.time("start_time"),
                    rs.time("end_time"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun patterns(
        employeeId: EmployeeId?,
        ownerId: UUID?,
        afterId: UUID?,
        limit: Int,
    ): List<SchedulePatternView> =
        jdbc
            .sql(
                """
                SELECT p.* FROM staff.schedule_pattern p JOIN staff.employee e ON e.id = p.employee_id
                WHERE (CAST(:employee AS uuid) IS NULL OR p.employee_id = CAST(:employee AS uuid))
                  AND (CAST(:owner AS uuid) IS NULL OR e.user_id = CAST(:owner AS uuid))
                  AND (CAST(:after AS uuid) IS NULL OR p.id > CAST(:after AS uuid))
                ORDER BY p.id LIMIT :limit
                """.trimIndent(),
            ).param("employee", employeeId?.value)
            .param("owner", ownerId)
            .param("after", afterId)
            .param("limit", limit)
            .query { rs, _ ->
                val mask = rs.getInt("weekday_mask")
                SchedulePatternView(
                    rs.uuid("id"),
                    EmployeeId(rs.uuid("employee_id")),
                    ShiftId(rs.uuid("shift_id")),
                    SchedulePatternKind.valueOf(rs.getString("kind")),
                    rs.date("starts_on"),
                    rs.dateOrNull("ends_on"),
                    DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet(),
                    rs.getBoolean("active"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun entries(
        filter: ScheduleFilter,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<ScheduleEntryView> =
        jdbc
            .sql(
                """
                SELECT s.*, e.name AS employee_name, sh.name AS shift_name, sh.start_time, sh.end_time
                FROM staff.schedule_entry s
                JOIN staff.employee e ON e.id = s.employee_id
                LEFT JOIN staff.shift sh ON sh.id = s.shift_id
                WHERE s.work_date BETWEEN :from AND :to
                  AND (CAST(:department AS uuid) IS NULL OR s.department_id = CAST(:department AS uuid))
                  AND (CAST(:employee AS uuid) IS NULL OR s.employee_id = CAST(:employee AS uuid))
                  AND (CAST(:owner AS uuid) IS NULL OR e.user_id = CAST(:owner AS uuid))
                  AND (CAST(:afterDate AS date) IS NULL
                       OR (s.work_date, s.id) > (CAST(:afterDate AS date), CAST(:afterId AS uuid)))
                ORDER BY s.work_date, s.id LIMIT :limit
                """.trimIndent(),
            ).param("from", filter.period.from)
            .param("to", filter.period.to)
            .param("department", filter.departmentId?.value)
            .param("employee", filter.employeeId?.value)
            .param("owner", filter.ownerId)
            .param("afterDate", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                ScheduleEntryView(
                    rs.uuid("id"),
                    EmployeeId(rs.uuid("employee_id")),
                    rs.getString("employee_name"),
                    rs.date("work_date"),
                    ScheduleEntryKind.valueOf(rs.getString("kind")),
                    ScheduleEntrySource.valueOf(rs.getString("source")),
                    rs.uuidOrNull("shift_id")?.let {
                        EntryShiftView(
                            ShiftId(it),
                            rs.getString("shift_name"),
                            rs.time("start_time"),
                            rs.time("end_time"),
                        )
                    },
                    DepartmentId(rs.uuid("department_id")),
                    rs.uuidOrNull("pattern_id"),
                    rs.getString("note"),
                    rs.instant("updated_at"),
                )
            }.list()

    override fun assignments(
        date: LocalDate,
        departmentId: DepartmentId,
    ): List<ShiftAssignment> =
        jdbc
            .sql(
                """
                SELECT s.kind, e.id AS employee_id, e.name AS employee_name, e.position, e.department_id AS home_department,
                       e.user_id, e.active, sh.id AS shift_id, sh.department_id AS shift_department, sh.name AS shift_name,
                       sh.start_time, sh.end_time
                FROM staff.schedule_entry s
                JOIN staff.employee e ON e.id = s.employee_id
                JOIN staff.shift sh ON sh.id = s.shift_id
                WHERE s.work_date = :date AND s.department_id = :department AND s.kind IN ('SHIFT', 'SWAP')
                ORDER BY sh.start_time, lower(e.name), e.id
                """.trimIndent(),
            ).param("date", date)
            .param("department", departmentId.value)
            .query { rs, _ ->
                ShiftAssignment(
                    EmployeeSummary(
                        EmployeeId(rs.uuid("employee_id")),
                        rs.getString("employee_name"),
                        rs.getString("position"),
                        DepartmentId(rs.uuid("home_department")),
                        rs.uuidOrNull("user_id")?.let(::UserId),
                        rs.getBoolean("active"),
                    ),
                    ShiftSummary(
                        ShiftId(rs.uuid("shift_id")),
                        DepartmentId(rs.uuid("shift_department")),
                        rs.getString("shift_name"),
                        rs.time("start_time"),
                        rs.time("end_time"),
                    ),
                    ScheduleEntryKind.valueOf(rs.getString("kind")),
                )
            }.list()

    private fun ResultSet.employee() =
        EmployeeView(
            EmployeeId(uuid("id")),
            getString("name"),
            getString("position"),
            getString("phone"),
            getString("email"),
            DepartmentId(uuid("department_id")),
            uuidOrNull("user_id")?.let(::UserId),
            getBoolean("active"),
            instant("created_at"),
            instant("updated_at"),
            getLong("version"),
        )
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

private fun ResultSet.date(column: String): LocalDate = getObject(column, LocalDate::class.java)

private fun ResultSet.dateOrNull(column: String): LocalDate? = getObject(column, LocalDate::class.java)

private fun ResultSet.time(column: String): LocalTime = getObject(column, LocalTime::class.java)

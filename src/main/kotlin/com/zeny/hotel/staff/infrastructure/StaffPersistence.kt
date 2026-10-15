package com.zeny.hotel.staff.infrastructure

import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.application.StaffConstraints
import com.zeny.hotel.staff.application.StaffStore
import com.zeny.hotel.staff.domain.Department
import com.zeny.hotel.staff.domain.Employee
import com.zeny.hotel.staff.domain.ScheduleEntry
import com.zeny.hotel.staff.domain.SchedulePattern
import com.zeny.hotel.staff.domain.SchedulePatternId
import com.zeny.hotel.staff.domain.SchedulePeriod
import com.zeny.hotel.staff.domain.Shift
import jakarta.persistence.LockModeType
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

interface DepartmentJpaRepository : JpaRepository<Department, UUID>

interface EmployeeJpaRepository : JpaRepository<Employee, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.id = :id")
    fun findForUpdate(id: UUID): Employee?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e ORDER BY e.id")
    fun findAllForUpdate(): List<Employee>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Employee e WHERE e.departmentId = :departmentId ORDER BY e.id")
    fun findForUpdateInDepartment(departmentId: UUID): List<Employee>

    @Query("SELECT e FROM Employee e WHERE e.userIdValue = :userId")
    fun findByUser(userId: UUID): Employee?
}

interface ShiftJpaRepository : JpaRepository<Shift, UUID>

interface SchedulePatternJpaRepository : JpaRepository<SchedulePattern, UUID> {
    @Query("SELECT p FROM SchedulePattern p WHERE p.employeeId IN :employeeIds AND p.active = true ORDER BY p.id")
    fun findActiveOf(employeeIds: Collection<UUID>): List<SchedulePattern>
}

interface ScheduleEntryJpaRepository : JpaRepository<ScheduleEntry, UUID> {
    @Query(
        "SELECT s FROM ScheduleEntry s WHERE s.employeeId IN :employeeIds " +
            "AND s.workDate BETWEEN :from AND :to ORDER BY s.workDate, s.id",
    )
    fun findOf(
        employeeIds: Collection<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): List<ScheduleEntry>

    @Query(
        "SELECT s FROM ScheduleEntry s WHERE s.employeeId = :employeeId AND s.workDate >= :from " +
            "AND s.kind IN (com.zeny.hotel.staff.ScheduleEntryKind.SHIFT, com.zeny.hotel.staff.ScheduleEntryKind.SWAP)",
    )
    fun findWorkFrom(
        employeeId: UUID,
        from: LocalDate,
    ): List<ScheduleEntry>
}

/** Os agregados de `staff` por JPA, na transação corrente. */
@Component
@Suppress("TooManyFunctions", "LongParameterList") // um repositório e os acessos de cada agregado
class JpaStaffStore(
    private val departments: DepartmentJpaRepository,
    private val employees: EmployeeJpaRepository,
    private val shifts: ShiftJpaRepository,
    private val patterns: SchedulePatternJpaRepository,
    private val entries: ScheduleEntryJpaRepository,
) : StaffStore {
    override fun department(id: DepartmentId): Department? = departments.findById(id.value).orElse(null)

    override fun tryAdd(department: Department): Boolean =
        unlessViolated(StaffConstraints.DEPARTMENT_NAME) { departments.saveAndFlush(department) }

    override fun employee(id: EmployeeId): Employee? = employees.findById(id.value).orElse(null)

    override fun employeeForUpdate(id: EmployeeId): Employee? = employees.findForUpdate(id.value)

    override fun employeesForUpdate(departmentId: DepartmentId?): List<Employee> =
        if (departmentId ==
            null
        ) {
            employees.findAllForUpdate()
        } else {
            employees.findForUpdateInDepartment(departmentId.value)
        }

    override fun employeeOfUser(userId: UserId): Employee? = employees.findByUser(userId.value)

    override fun tryAdd(employee: Employee): Boolean =
        unlessViolated(StaffConstraints.EMPLOYEE_USER) { employees.saveAndFlush(employee) }

    override fun shift(id: ShiftId): Shift? = shifts.findById(id.value).orElse(null)

    override fun shifts(ids: Collection<ShiftId>): List<Shift> = shifts.findAllById(ids.map { it.value })

    override fun tryAdd(shift: Shift): Boolean =
        unlessViolated(StaffConstraints.SHIFT_NAME) { shifts.saveAndFlush(shift) }

    override fun pattern(id: SchedulePatternId): SchedulePattern? = patterns.findById(id.value).orElse(null)

    override fun activePatternsOf(employeeIds: Collection<EmployeeId>): List<SchedulePattern> =
        if (employeeIds.isEmpty()) emptyList() else patterns.findActiveOf(employeeIds.map { it.value })

    override fun add(pattern: SchedulePattern) {
        patterns.saveAndFlush(pattern)
    }

    override fun entries(
        employeeIds: Collection<EmployeeId>,
        period: SchedulePeriod,
    ): List<ScheduleEntry> =
        if (employeeIds.isEmpty()) emptyList() else entries.findOf(employeeIds.map { it.value }, period.from, period.to)

    override fun workEntriesFrom(
        employeeId: EmployeeId,
        from: LocalDate,
    ): List<ScheduleEntry> = entries.findWorkFrom(employeeId.value, from)

    override fun add(entries: Collection<ScheduleEntry>) {
        this.entries.saveAll(entries)
    }

    override fun remove(entries: Collection<ScheduleEntry>) {
        this.entries.deleteAll(entries)
    }

    override fun flush() = departments.flush()

    override fun tryFlush(constraint: String): Boolean = unlessViolated(constraint) { departments.flush() }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        /**
         * Violação da unicidade [constraint] (`23505`, reconhecida pelo campo `constraint` do erro do
         * servidor na cadeia de causas, nunca pelo texto) vira `false`; qualquer outra segue.
         */
        fun unlessViolated(
            constraint: String,
            write: () -> Unit,
        ): Boolean =
            try {
                write()
                true
            } catch (e: DataIntegrityViolationException) {
                val violated =
                    generateSequence<Throwable>(e) { it.cause }
                        .filterIsInstance<PSQLException>()
                        .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }
                if (!violated) throw e
                false
            }
    }
}

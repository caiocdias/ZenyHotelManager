package com.zeny.hotel.staff.application

import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.ScheduleEntrySource
import com.zeny.hotel.staff.SchedulePatternKind
import com.zeny.hotel.staff.ShiftAssignment
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.domain.Department
import com.zeny.hotel.staff.domain.Employee
import com.zeny.hotel.staff.domain.ScheduleEntry
import com.zeny.hotel.staff.domain.SchedulePattern
import com.zeny.hotel.staff.domain.SchedulePatternId
import com.zeny.hotel.staff.domain.SchedulePeriod
import com.zeny.hotel.staff.domain.Shift
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Os agregados de `staff` para escrita (JPA), na transação corrente. As gravações que podem violar
 * uma unicidade do banco devolvem `false` em vez de falhar, para o caso de uso responder `409`.
 */
@Suppress("TooManyFunctions") // um método por acesso de cada agregado
interface StaffStore {
    fun department(id: DepartmentId): Department?

    /** `false` se o nome já existe (sem diferenciar maiúsculas). */
    fun tryAdd(department: Department): Boolean

    fun employee(id: EmployeeId): Employee?

    /** O colaborador travado para escrita (`SELECT … FOR UPDATE`) até o fim da transação. */
    fun employeeForUpdate(id: EmployeeId): Employee?

    /**
     * Os colaboradores do setor (todos, se nulo) travados para escrita, em ordem de id (as travas saem
     * sempre na mesma ordem).
     */
    fun employeesForUpdate(departmentId: DepartmentId?): List<Employee>

    fun employeeOfUser(userId: UserId): Employee?

    /** `false` se o usuário já está vinculado a outro colaborador. */
    fun tryAdd(employee: Employee): Boolean

    fun shift(id: ShiftId): Shift?

    fun shifts(ids: Collection<ShiftId>): List<Shift>

    /** `false` se o setor já tem um turno com este nome (sem diferenciar maiúsculas). */
    fun tryAdd(shift: Shift): Boolean

    fun pattern(id: SchedulePatternId): SchedulePattern?

    /** Os padrões ativos dos colaboradores. */
    fun activePatternsOf(employeeIds: Collection<EmployeeId>): List<SchedulePattern>

    fun add(pattern: SchedulePattern)

    /** As entradas dos colaboradores no período. */
    fun entries(
        employeeIds: Collection<EmployeeId>,
        period: SchedulePeriod,
    ): List<ScheduleEntry>

    /** As entradas de trabalho (turno ou troca) do colaborador a partir de [from], inclusive. */
    fun workEntriesFrom(
        employeeId: EmployeeId,
        from: LocalDate,
    ): List<ScheduleEntry>

    fun add(entries: Collection<ScheduleEntry>)

    fun remove(entries: Collection<ScheduleEntry>)

    /** Grava já as mudanças pendentes (conferindo a versão: lock otimista). */
    fun flush()

    /** Como [flush], mas `false` se violar a unicidade [constraint] (nome de restrição ou índice único). */
    fun tryFlush(constraint: String): Boolean
}

/** Nomes das unicidades do banco que viram `409`. */
object StaffConstraints {
    const val DEPARTMENT_NAME = "department_name_uk"
    const val EMPLOYEE_USER = "employee_user_uk"
    const val SHIFT_NAME = "shift_name_uk"
}

data class DepartmentView(
    val id: DepartmentId,
    val key: DepartmentKey?,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class EmployeeView(
    val id: EmployeeId,
    val name: String,
    val position: String,
    val phone: String?,
    val email: String?,
    val departmentId: DepartmentId,
    val userId: UserId?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class ShiftView(
    val id: ShiftId,
    val departmentId: DepartmentId,
    val name: String,
    val start: LocalTime,
    val end: LocalTime,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    val crossesMidnight: Boolean get() = end < start
}

data class SchedulePatternView(
    val id: UUID,
    val employeeId: EmployeeId,
    val shiftId: ShiftId,
    val kind: SchedulePatternKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    val weekdays: Set<DayOfWeek>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** O turno de uma entrada da escala, como a consulta o mostra. */
data class EntryShiftView(
    val id: ShiftId,
    val name: String,
    val start: LocalTime,
    val end: LocalTime,
) {
    val crossesMidnight: Boolean get() = end < start
}

data class ScheduleEntryView(
    val id: UUID,
    val employeeId: EmployeeId,
    val employeeName: String,
    val date: LocalDate,
    val kind: ScheduleEntryKind,
    val source: ScheduleEntrySource,
    val shift: EntryShiftView?,
    val departmentId: DepartmentId,
    val patternId: UUID?,
    val note: String?,
    val updatedAt: Instant,
)

/** Filtro da consulta da escala. [ownerId] é o do escopo `OWN` (só o colaborador vinculado a ele). */
data class ScheduleFilter(
    val period: SchedulePeriod,
    val departmentId: DepartmentId?,
    val employeeId: EmployeeId?,
    val ownerId: UUID?,
)

/** Leituras de `staff` para a API e para os outros módulos (`JdbcClient`, ARCHITECTURE §11). */
interface StaffDirectory {
    /** Uma página de setores em ordem de criação (id UUIDv7), depois de [afterId]. */
    fun departments(
        afterId: UUID?,
        limit: Int,
    ): List<DepartmentView>

    /** Uma página de colaboradores em ordem de nome (sem diferenciar maiúsculas) e id. */
    fun employees(
        departmentId: DepartmentId?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<EmployeeView>

    /** Uma página de turnos em ordem de criação, depois de [afterId]. */
    fun shifts(
        departmentId: DepartmentId?,
        afterId: UUID?,
        limit: Int,
    ): List<ShiftView>

    /** Uma página de padrões em ordem de criação, depois de [afterId]; [ownerId] é o filtro `OWN`. */
    fun patterns(
        employeeId: EmployeeId?,
        ownerId: UUID?,
        afterId: UUID?,
        limit: Int,
    ): List<SchedulePatternView>

    /** Uma página de entradas em ordem de data e id, depois de [after]. */
    fun entries(
        filter: ScheduleFilter,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<ScheduleEntryView>

    /** Quem trabalha na data no setor, em ordem de início do turno e nome. */
    fun assignments(
        date: LocalDate,
        departmentId: DepartmentId,
    ): List<ShiftAssignment>
}

internal fun Department.view() = DepartmentView(id, key, name, createdAt, updatedAt, checkNotNull(version))

internal fun Employee.view() =
    EmployeeView(
        id,
        name,
        position,
        phone,
        email,
        departmentId,
        userId,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

internal fun Shift.view() = ShiftView(id, departmentId, name, start, end, createdAt, updatedAt, checkNotNull(version))

internal fun SchedulePattern.view() =
    SchedulePatternView(
        id.value,
        employeeId,
        shiftId,
        kind,
        startsOn,
        endsOn,
        weekdays,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

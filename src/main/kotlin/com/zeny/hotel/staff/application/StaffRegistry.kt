package com.zeny.hotel.staff.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.StaffErrorCodes
import com.zeny.hotel.staff.StaffPermissions
import com.zeny.hotel.staff.domain.Department
import com.zeny.hotel.staff.domain.Employee
import com.zeny.hotel.staff.domain.EmployeeDetails
import com.zeny.hotel.staff.domain.Shift
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalTime

/** Dados de um colaborador como chegam da API: os cadastrais e o usuário vinculado (nulo: nenhum). */
data class EmployeeCommand(
    val name: String,
    val position: String,
    val phone: String?,
    val email: String?,
    val departmentId: DepartmentId,
    val userId: UserId?,
) {
    internal val details: EmployeeDetails get() = EmployeeDetails(name, position, phone, email, departmentId)
}

/**
 * Cadastro de setores, colaboradores e turnos (RF-PES-01 e 02). Consultar exige
 * `staff.employees.read`; escrever, `staff.employees.manage`, e as edições conferem a versão da
 * última leitura (`If-Match`). Nada é apagado: colaborador só é desativado (RF-ACS-12).
 *
 * O vínculo colaborador ↔ usuário (H7) é validado pela API pública do `iam` ([IamApi]): o usuário
 * existe (`400 UNKNOWN_USER`), está ativo (`422 USER_INACTIVE`) e não está vinculado a outro
 * colaborador (`409 USER_ALREADY_LINKED`, garantido também pelo banco). Desativar um colaborador
 * retira as entradas de trabalho dele a partir da data operacional; folgas, faltas e férias ficam.
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // um caso de uso por operação do cadastro
class StaffRegistryService(
    private val store: StaffStore,
    private val directory: StaffDirectory,
    private val iam: IamApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // --- Setores ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun departments(page: PageParams): CursorPage<DepartmentView> {
        authorization.require(StaffPermissions.EMPLOYEES_READ)
        val rows = directory.departments(page.decodedCursor()?.uuid(0), page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun department(id: DepartmentId): DepartmentView {
        authorization.require(StaffPermissions.EMPLOYEES_READ)
        return findDepartment(id).view()
    }

    @Audited(StaffAudit.CREATE_DEPARTMENT)
    @Transactional
    fun createDepartment(name: String): DepartmentView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        val department = Department.create(name, clock.instant())
        audit.created(StaffAudit.DEPARTMENT, department.id.value) { department.auditState() }
        if (!store.tryAdd(department)) throw departmentNameInUse()
        return department.view()
    }

    @Audited(StaffAudit.UPDATE_DEPARTMENT)
    @Transactional
    fun renameDepartment(
        id: DepartmentId,
        expectedVersion: Long,
        name: String,
    ): DepartmentView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        val department = findDepartment(id)
        requireCurrentVersion(expectedVersion, checkNotNull(department.version))
        audit.track(StaffAudit.DEPARTMENT, id.value) { department.auditState() }
        if (department.rename(name, clock.instant()) && !store.tryFlush(StaffConstraints.DEPARTMENT_NAME)) {
            throw departmentNameInUse()
        }
        return department.view()
    }

    // --- Colaboradores -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun employees(
        departmentId: DepartmentId?,
        active: Boolean?,
        page: PageParams,
    ): CursorPage<EmployeeView> {
        authorization.require(StaffPermissions.EMPLOYEES_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.employees(departmentId, active, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.name.lowercase(), it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun employee(id: EmployeeId): EmployeeView {
        authorization.require(StaffPermissions.EMPLOYEES_READ)
        return findEmployee(id).view()
    }

    @Audited(StaffAudit.CREATE_EMPLOYEE)
    @Transactional
    fun createEmployee(command: EmployeeCommand): EmployeeView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        requireDepartment(command.details.departmentId)
        command.userId?.let { requireLinkable(it, linkedTo = null) }
        val employee = Employee.create(command.details, command.userId, clock.instant())
        audit.created(StaffAudit.EMPLOYEE, employee.id.value) { employee.auditState() }
        if (!store.tryAdd(employee)) throw userAlreadyLinked()
        log.info("Colaborador {} criado", employee.id)
        return employee.view()
    }

    @Audited(StaffAudit.UPDATE_EMPLOYEE)
    @Transactional
    fun updateEmployee(
        id: EmployeeId,
        expectedVersion: Long,
        command: EmployeeCommand,
    ): EmployeeView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        val employee = findEmployee(id)
        requireCurrentVersion(expectedVersion, checkNotNull(employee.version))
        audit.track(StaffAudit.EMPLOYEE, id.value) { employee.auditState() }
        if (command.details.departmentId != employee.departmentId) requireDepartment(command.details.departmentId)
        if (command.userId != null && command.userId != employee.userId) requireLinkable(command.userId, linkedTo = id)
        val now = clock.instant()
        val changed = employee.update(command.details, now) or employee.linkUser(command.userId, now)
        if (changed && !store.tryFlush(StaffConstraints.EMPLOYEE_USER)) throw userAlreadyLinked()
        return employee.view()
    }

    @Audited(StaffAudit.DEACTIVATE_EMPLOYEE)
    @Transactional
    fun deactivateEmployee(id: EmployeeId): EmployeeView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        val employee = store.employeeForUpdate(id) ?: throw employeeNotFound()
        audit.track(StaffAudit.EMPLOYEE, id.value) { employee.auditState() }
        if (employee.deactivate(clock.instant())) {
            val work = store.workEntriesFrom(id, hotel.currentBusinessDate())
            work.forEach { entry -> audit.record(StaffAudit.SCHEDULE_ENTRY, entry.id.value, entry.auditState(), null) }
            store.remove(work)
            store.flush()
            log.info("Colaborador {} desativado; {} entradas de trabalho futuras retiradas", id, work.size)
        }
        return employee.view()
    }

    @Audited(StaffAudit.REACTIVATE_EMPLOYEE)
    @Transactional
    fun reactivateEmployee(id: EmployeeId): EmployeeView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        val employee = store.employeeForUpdate(id) ?: throw employeeNotFound()
        audit.track(StaffAudit.EMPLOYEE, id.value) { employee.auditState() }
        if (employee.reactivate(clock.instant())) store.flush()
        return employee.view()
    }

    // --- Turnos ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun shifts(
        departmentId: DepartmentId?,
        page: PageParams,
    ): CursorPage<ShiftView> {
        authorization.require(StaffPermissions.EMPLOYEES_READ)
        val rows = directory.shifts(departmentId, page.decodedCursor()?.uuid(0), page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun shift(id: ShiftId): ShiftView {
        authorization.require(StaffPermissions.EMPLOYEES_READ)
        return (store.shift(id) ?: throw shiftNotFound()).view()
    }

    @Audited(StaffAudit.CREATE_SHIFT)
    @Transactional
    fun createShift(
        departmentId: DepartmentId,
        name: String,
        start: LocalTime,
        end: LocalTime,
    ): ShiftView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        requireDepartment(departmentId)
        val shift = Shift.create(departmentId, name, start, end, clock.instant())
        audit.created(StaffAudit.SHIFT, shift.id.value) { shift.auditState() }
        if (!store.tryAdd(shift)) throw shiftNameInUse()
        return shift.view()
    }

    @Audited(StaffAudit.UPDATE_SHIFT)
    @Transactional
    fun updateShift(
        id: ShiftId,
        expectedVersion: Long,
        name: String,
        start: LocalTime,
        end: LocalTime,
    ): ShiftView {
        authorization.require(StaffPermissions.EMPLOYEES_MANAGE)
        val shift = store.shift(id) ?: throw shiftNotFound()
        requireCurrentVersion(expectedVersion, checkNotNull(shift.version))
        audit.track(StaffAudit.SHIFT, id.value) { shift.auditState() }
        if (shift.update(name, start, end, clock.instant()) && !store.tryFlush(StaffConstraints.SHIFT_NAME)) {
            throw shiftNameInUse()
        }
        return shift.view()
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun findDepartment(id: DepartmentId) =
        store.department(id) ?: throw NotFoundException(StaffErrorCodes.DEPARTMENT_NOT_FOUND, "Setor não encontrado.")

    private fun findEmployee(id: EmployeeId) = store.employee(id) ?: throw employeeNotFound()

    private fun requireDepartment(id: DepartmentId) {
        if (store.department(id) == null) {
            throw invalidReference(StaffErrorCodes.UNKNOWN_DEPARTMENT, "departmentId", "O setor informado não existe.")
        }
    }

    /** O usuário existe, está ativo e não está vinculado a outro colaborador que não [linkedTo]. */
    private fun requireLinkable(
        userId: UserId,
        linkedTo: EmployeeId?,
    ) {
        val user = iam.findUser(userId)
        val problem =
            when {
                user == null -> {
                    invalidReference(StaffErrorCodes.UNKNOWN_USER, "userId", "O usuário informado não existe.")
                }

                !user.active -> {
                    BusinessRuleException(StaffErrorCodes.USER_INACTIVE, "O usuário informado está desativado.")
                }

                store.employeeOfUser(userId)?.takeIf { it.id != linkedTo } != null -> {
                    userAlreadyLinked()
                }

                else -> {
                    null
                }
            }
        if (problem != null) throw problem
    }

    private fun departmentNameInUse() =
        ConflictException(StaffErrorCodes.DEPARTMENT_NAME_ALREADY_IN_USE, "Já existe um setor com este nome.")

    private fun shiftNameInUse() =
        ConflictException(StaffErrorCodes.SHIFT_NAME_ALREADY_IN_USE, "O setor já tem um turno com este nome.")

    private fun userAlreadyLinked() =
        ConflictException(StaffErrorCodes.USER_ALREADY_LINKED, "O usuário já está vinculado a outro colaborador.")
}

internal fun employeeNotFound() = NotFoundException(StaffErrorCodes.EMPLOYEE_NOT_FOUND, "Colaborador não encontrado.")

internal fun shiftNotFound() = NotFoundException(StaffErrorCodes.SHIFT_NOT_FOUND, "Turno não encontrado.")

internal fun invalidReference(
    code: ErrorCode,
    field: String,
    message: String,
) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))

@Configuration(proxyBeanMethods = false)
class StaffPermissionsConfiguration {
    @Bean
    fun staffPermissions(): ModulePermissions = StaffPermissions
}

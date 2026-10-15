package com.zeny.hotel.staff.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.SchedulePatternKind
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.StaffErrorCodes
import com.zeny.hotel.staff.StaffPermissions
import com.zeny.hotel.staff.domain.Employee
import com.zeny.hotel.staff.domain.ManualEntry
import com.zeny.hotel.staff.domain.PatternDefinition
import com.zeny.hotel.staff.domain.ScheduleEntry
import com.zeny.hotel.staff.domain.SchedulePattern
import com.zeny.hotel.staff.domain.SchedulePatternId
import com.zeny.hotel.staff.domain.SchedulePeriod
import com.zeny.hotel.staff.domain.SchedulePlan
import com.zeny.hotel.staff.domain.SchedulePlanner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

/** Definição de um padrão como chega da API (validada no domínio, [PatternDefinition]). */
data class PatternCommand(
    val kind: SchedulePatternKind,
    val shiftId: ShiftId,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    val weekdays: Set<DayOfWeek>,
) {
    internal fun toDefinition() = PatternDefinition(kind, shiftId, startsOn, endsOn, weekdays)
}

/** Pedido de geração da escala: o período e, opcionalmente, só um setor (o do colaborador) ou um colaborador. */
data class GenerateScheduleCommand(
    val from: LocalDate,
    val to: LocalDate,
    val departmentId: DepartmentId?,
    val employeeId: EmployeeId?,
)

/** O que a geração fez. [keptManual] conta as entradas lançadas à mão que ela deixou como estavam. */
data class GenerationResult(
    val from: LocalDate,
    val to: LocalDate,
    val created: Int,
    val updated: Int,
    val removed: Int,
    val keptManual: Int,
)

/** Lançamento de uma atribuição ou exceção para cada data de [from] a [to]. */
data class RecordEntriesCommand(
    val employeeId: EmployeeId,
    val kind: ScheduleEntryKind,
    val from: LocalDate,
    val to: LocalDate,
    val shiftId: ShiftId?,
    val note: String?,
)

/** Consulta da escala; período nulo é a data operacional. */
data class ScheduleQuery(
    val from: LocalDate?,
    val to: LocalDate?,
    val departmentId: DepartmentId?,
    val employeeId: EmployeeId?,
)

/**
 * Padrões de escala e a escala (RF-PES-03). Consultar exige `staff.schedule.read`: com escopo `OWN`,
 * só os padrões e as entradas do colaborador vinculado ao usuário (H7), filtrados na própria
 * consulta. Escrever exige `staff.schedule.manage`.
 *
 * A geração cria, a partir dos padrões ativos dos colaboradores ativos, as entradas de um período que
 * começa na data operacional ou depois (o passado não é reescrito); refaz as geradas que mudaram e
 * retira as geradas que nenhum padrão pede mais. As lançadas à mão (atribuição, troca, folga, falta e
 * férias) prevalecem sempre: a geração nunca as sobrescreve nem retira. As escritas sobre um
 * colaborador o travam antes (`SELECT … FOR UPDATE`), então geração e lançamento simultâneos não se
 * atropelam.
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // um caso de uso por operação da escala
class ScheduleService(
    private val store: StaffStore,
    private val directory: StaffDirectory,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // --- Padrões -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun patterns(
        employeeId: EmployeeId?,
        page: PageParams,
    ): CursorPage<SchedulePatternView> {
        val owner = authorization.scopeFilter(StaffPermissions.SCHEDULE_READ).ownerId
        val rows = directory.patterns(employeeId, owner, page.decodedCursor()?.uuid(0), page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }
    }

    /** Com escopo `OWN`, o padrão de outro colaborador responde `404` (não revela que existe). */
    @Transactional(readOnly = true)
    fun pattern(id: UUID): SchedulePatternView {
        val owner = authorization.scopeFilter(StaffPermissions.SCHEDULE_READ).ownerId
        val pattern = store.pattern(SchedulePatternId(id)) ?: throw patternNotFound()
        if (owner != null && store.employee(pattern.employeeId)?.userId?.value != owner) throw patternNotFound()
        return pattern.view()
    }

    @Audited(StaffAudit.CREATE_PATTERN)
    @Transactional
    fun createPattern(
        employeeId: EmployeeId,
        command: PatternCommand,
    ): SchedulePatternView {
        authorization.require(StaffPermissions.SCHEDULE_MANAGE)
        val definition = command.toDefinition()
        val employee = activeEmployeeForUpdate(employeeId)
        requireShift(definition.shiftId)
        requireNoOverlap(employee, definition, except = null)
        val pattern = SchedulePattern.create(employee.id, definition, clock.instant())
        audit.created(StaffAudit.SCHEDULE_PATTERN, pattern.id.value) { pattern.auditState() }
        store.add(pattern)
        return pattern.view()
    }

    /** Troca a definição; as entradas já geradas só mudam na próxima geração. */
    @Audited(StaffAudit.UPDATE_PATTERN)
    @Transactional
    fun updatePattern(
        id: UUID,
        expectedVersion: Long,
        command: PatternCommand,
    ): SchedulePatternView {
        authorization.require(StaffPermissions.SCHEDULE_MANAGE)
        val definition = command.toDefinition()
        val pattern = store.pattern(SchedulePatternId(id)) ?: throw patternNotFound()
        requireCurrentVersion(expectedVersion, checkNotNull(pattern.version))
        if (!pattern.active) {
            throw BusinessRuleException(
                StaffErrorCodes.SCHEDULE_PATTERN_INACTIVE,
                "Padrão desativado não é editado; crie outro.",
            )
        }
        val employee = activeEmployeeForUpdate(pattern.employeeId)
        requireShift(definition.shiftId)
        requireNoOverlap(employee, definition, except = pattern.id)
        audit.track(StaffAudit.SCHEDULE_PATTERN, id) { pattern.auditState() }
        if (pattern.update(definition, clock.instant())) store.flush()
        return pattern.view()
    }

    /**
     * Desativa o padrão: ele deixa de gerar entradas (as já geradas saem na próxima geração). Trava o
     * colaborador antes, como as outras escritas da escala dele, para não correr com uma geração.
     */
    @Audited(StaffAudit.DEACTIVATE_PATTERN)
    @Transactional
    fun deactivatePattern(id: UUID): SchedulePatternView {
        authorization.require(StaffPermissions.SCHEDULE_MANAGE)
        val pattern = store.pattern(SchedulePatternId(id)) ?: throw patternNotFound()
        store.employeeForUpdate(pattern.employeeId)
        audit.track(StaffAudit.SCHEDULE_PATTERN, id) { pattern.auditState() }
        if (pattern.deactivate(clock.instant())) store.flush()
        return pattern.view()
    }

    // --- Escala ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    fun entries(
        query: ScheduleQuery,
        page: PageParams,
    ): CursorPage<ScheduleEntryView> {
        val owner = authorization.scopeFilter(StaffPermissions.SCHEDULE_READ).ownerId
        val from = query.from ?: hotel.currentBusinessDate()
        val period = SchedulePeriod(from, query.to ?: from)
        val after = page.decodedCursor()?.let { it.localDate(0) to it.uuid(1) }
        val rows =
            directory.entries(
                ScheduleFilter(period, query.departmentId, query.employeeId, owner),
                after,
                page.limit + 1,
            )
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.date.toString(), it.id.toString()) }
    }

    @Audited(StaffAudit.GENERATE_SCHEDULE)
    @Transactional
    fun generate(command: GenerateScheduleCommand): GenerationResult {
        authorization.require(StaffPermissions.SCHEDULE_MANAGE)
        val period = SchedulePeriod(command.from, command.to)
        val businessDate = hotel.currentBusinessDate()
        if (period.from < businessDate) {
            throw BusinessRuleException(
                StaffErrorCodes.SCHEDULE_PERIOD_IN_PAST,
                "A geração começa na data operacional ($businessDate) ou depois; o passado não é reescrito.",
            )
        }
        val employees = employeesInScope(command)
        val ids = employees.map { it.id }
        val patterns = store.activePatternsOf(ids)
        val departments = store.shifts(patterns.map { it.shiftId }.toSet()).associate { it.id to it.departmentId }
        val existing = store.entries(ids, period)
        val plan =
            SchedulePlanner.plan(
                period,
                employees.filter { it.active }.map { it.id }.toSet(),
                patterns,
                { departments.getValue(it) },
                existing,
            )
        val result =
            GenerationResult(
                period.from,
                period.to,
                created = plan.toCreate.size,
                updated = plan.toUpdate.size,
                removed = plan.toRemove.size,
                keptManual = plan.keptManual.size,
            )
        // Antes de aplicar: o retrato das refeitas e retiradas é o "antes".
        recordGeneration(command, plan, result)
        val now = clock.instant()
        plan.toUpdate.forEach { (entry, planned) -> entry.replan(planned, now) }
        store.remove(plan.toRemove)
        store.add(plan.toCreate.map { ScheduleEntry.planned(it, now) })
        store.flush()
        log.info("Escala gerada de {} a {}: {}", period.from, period.to, result)
        return result
    }

    /**
     * Lança uma atribuição (turno), troca, folga, falta ou férias em cada data do período: a entrada
     * da data, gerada ou lançada antes, passa a ser esta, e a geração não a toca mais.
     */
    @Audited(StaffAudit.RECORD_ENTRIES)
    @Transactional
    fun record(command: RecordEntriesCommand): List<ScheduleEntryView> {
        authorization.require(StaffPermissions.SCHEDULE_MANAGE)
        val period = SchedulePeriod(command.from, command.to)
        val employee =
            store.employeeForUpdate(command.employeeId)
                ?: throw invalidReference(StaffErrorCodes.UNKNOWN_EMPLOYEE, "employeeId", "O colaborador não existe.")
        requireActive(employee)
        val shift = command.shiftId?.let { requireShift(it) }
        val manual = ManualEntry(command.kind, shift?.id, shift?.departmentId ?: employee.departmentId, command.note)
        val existing = store.entries(listOf(employee.id), period).associateBy { it.date }
        val now = clock.instant()
        val created = mutableListOf<ScheduleEntry>()
        val entries =
            period.dates().toList().map { date ->
                existing[date]?.also { entry ->
                    audit.track(StaffAudit.SCHEDULE_ENTRY, entry.id.value) { entry.auditState() }
                    entry.overrideWith(manual, now)
                } ?: ScheduleEntry.manual(employee.id, date, manual, now).also { entry ->
                    audit.created(StaffAudit.SCHEDULE_ENTRY, entry.id.value) { entry.auditState() }
                    created += entry
                }
            }
        store.add(created)
        store.flush()
        val shiftView = shift?.let { EntryShiftView(it.id, it.name, it.start, it.end) }
        return entries.map {
            ScheduleEntryView(
                it.id.value,
                employee.id,
                employee.name,
                it.date,
                it.kind,
                it.source,
                shiftView,
                it.departmentId,
                it.patternId?.value,
                it.note,
                it.updatedAt,
            )
        }
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /**
     * Uma entrada só para a geração, gravada antes de aplicá-la: as contagens e o retrato de antes das
     * entradas geradas que ela refaz e retira (as criadas só contam; as lançadas à mão não mudam).
     */
    private fun recordGeneration(
        command: GenerateScheduleCommand,
        plan: SchedulePlan,
        result: GenerationResult,
    ) = audit.record(
        StaffAudit.SCHEDULE_GENERATION,
        UuidV7.generate(),
        null,
        mapOf(
            "from" to result.from.toString(),
            "to" to result.to.toString(),
            "departmentId" to command.departmentId?.toString(),
            "employeeId" to command.employeeId?.toString(),
            "created" to result.created,
            "updated" to plan.toUpdate.map { (entry, _) -> entry.auditState() },
            "removed" to plan.toRemove.map { it.auditState() },
            "keptManual" to result.keptManual,
        ),
    )

    private fun employeesInScope(command: GenerateScheduleCommand): List<Employee> {
        if (command.departmentId != null && store.department(command.departmentId) == null) {
            throw invalidReference(StaffErrorCodes.UNKNOWN_DEPARTMENT, "departmentId", "O setor informado não existe.")
        }
        if (command.employeeId != null) {
            val employee =
                store.employeeForUpdate(command.employeeId)
                    ?: throw invalidReference(
                        StaffErrorCodes.UNKNOWN_EMPLOYEE,
                        "employeeId",
                        "O colaborador não existe.",
                    )
            return listOfNotNull(
                employee.takeIf {
                    command.departmentId == null ||
                        it.departmentId == command.departmentId
                },
            )
        }
        return store.employeesForUpdate(command.departmentId)
    }

    private fun activeEmployeeForUpdate(id: EmployeeId): Employee {
        val employee =
            store.employeeForUpdate(id)
                ?: throw invalidReference(StaffErrorCodes.UNKNOWN_EMPLOYEE, "employeeId", "O colaborador não existe.")
        requireActive(employee)
        return employee
    }

    private fun requireActive(employee: Employee) {
        if (!employee.active) {
            throw BusinessRuleException(StaffErrorCodes.EMPLOYEE_INACTIVE, "O colaborador está desativado.")
        }
    }

    private fun requireShift(id: ShiftId) =
        store.shift(id)
            ?: throw invalidReference(StaffErrorCodes.UNKNOWN_SHIFT, "shiftId", "O turno informado não existe.")

    private fun requireNoOverlap(
        employee: Employee,
        definition: PatternDefinition,
        except: SchedulePatternId?,
    ) {
        val overlapping =
            store.activePatternsOf(listOf(employee.id)).any {
                it.id != except && it.overlaps(definition.startsOn, definition.endsOn)
            }
        if (overlapping) {
            throw ConflictException(
                StaffErrorCodes.SCHEDULE_PATTERN_OVERLAP,
                "O colaborador já tem um padrão ativo nesse período; encerre-o antes.",
            )
        }
    }

    private fun patternNotFound() =
        NotFoundException(StaffErrorCodes.SCHEDULE_PATTERN_NOT_FOUND, "Padrão de escala não encontrado.")
}

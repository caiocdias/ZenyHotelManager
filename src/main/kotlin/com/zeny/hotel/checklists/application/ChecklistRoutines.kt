package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistRoutineView
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChecklistsPermissions
import com.zeny.hotel.checklists.RoutineDefinition
import com.zeny.hotel.checklists.domain.ChecklistExecution
import com.zeny.hotel.checklists.domain.ChecklistRoutine
import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import com.zeny.hotel.staff.DaySchedule
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.StaffApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Rotinas de checklist (RF-CHK-08, H14): criar, editar (`If-Match`), desativar e reativar; nada é apagado.
 * Consultar exige `checklists.routines.read`; escrever, `checklists.routines.manage`. O modelo precisa existir e
 * estar ativo, e o setor existir na `StaffApi`.
 */
@Service
class ChecklistRoutinesService(
    private val routines: ChecklistRoutineStore,
    private val templates: ChecklistTemplateStore,
    private val staff: StaffApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun routines(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<ChecklistRoutineView> {
        authorization.require(ChecklistsPermissions.ROUTINES_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = routines.routines(active, after, page.limit + 1).map(ChecklistRoutine::view)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.name, it.id.toString()) }
    }

    @Transactional(readOnly = true)
    fun routine(id: ChecklistRoutineId): ChecklistRoutineView {
        authorization.require(ChecklistsPermissions.ROUTINES_READ)
        return (routines.find(id) ?: throw notFound()).view()
    }

    @Audited(ChecklistsAudit.CREATE_ROUTINE)
    @Transactional
    fun create(definition: RoutineDefinition): ChecklistRoutineView {
        authorization.require(ChecklistsPermissions.ROUTINES_MANAGE)
        validateReferences(definition, null)
        val routine = ChecklistRoutine.create(definition, clock.instant())
        audit.created(ChecklistsAudit.ROUTINE, routine.id.value) { routine.auditState() }
        routines.add(routine)
        return routine.view()
    }

    @Audited(ChecklistsAudit.UPDATE_ROUTINE)
    @Transactional
    fun update(
        id: ChecklistRoutineId,
        expectedVersion: Long,
        definition: RoutineDefinition,
    ): ChecklistRoutineView {
        authorization.require(ChecklistsPermissions.ROUTINES_MANAGE)
        val routine = routines.findForUpdate(id) ?: throw notFound()
        requireCurrentVersion(expectedVersion, routine.version)
        validateReferences(definition, routine.definition.templateId)
        audit.track(ChecklistsAudit.ROUTINE, id.value) { routine.auditState() }
        if (routine.edit(definition, clock.instant())) routines.update(routine)
        return routine.view()
    }

    @Audited(ChecklistsAudit.DEACTIVATE_ROUTINE)
    @Transactional
    fun deactivate(id: ChecklistRoutineId): ChecklistRoutineView {
        authorization.require(ChecklistsPermissions.ROUTINES_MANAGE)
        val routine = routines.findForUpdate(id) ?: throw notFound()
        audit.track(ChecklistsAudit.ROUTINE, id.value) { routine.auditState() }
        if (routine.deactivate(clock.instant())) routines.update(routine)
        return routine.view()
    }

    @Audited(ChecklistsAudit.REACTIVATE_ROUTINE)
    @Transactional
    fun reactivate(id: ChecklistRoutineId): ChecklistRoutineView {
        authorization.require(ChecklistsPermissions.ROUTINES_MANAGE)
        val routine = routines.findForUpdate(id) ?: throw notFound()
        audit.track(ChecklistsAudit.ROUTINE, id.value) { routine.auditState() }
        if (routine.reactivate(clock.instant())) routines.update(routine)
        return routine.view()
    }

    /** O modelo existe e está ativo (manter o atual não falha), e o setor existe. */
    private fun validateReferences(
        definition: RoutineDefinition,
        currentTemplate: ChecklistTemplateId?,
    ) {
        val template =
            templates.find(definition.templateId)
                ?: invalid(
                    "templateId",
                    ChecklistsErrorCodes.UNKNOWN_CHECKLIST_TEMPLATE,
                    "O modelo de checklist informado não existe.",
                )
        if (!template.active && definition.templateId != currentTemplate) {
            throw BusinessRuleException(
                ChecklistsErrorCodes.CHECKLIST_TEMPLATE_INACTIVE,
                "O modelo de checklist está desativado.",
            )
        }
        if (staff.departments().none { it.id == definition.departmentId }) {
            invalid("departmentId", ChecklistsErrorCodes.UNKNOWN_DEPARTMENT, "O setor informado não existe.")
        }
    }

    private fun invalid(
        field: String,
        code: ErrorCode,
        message: String,
    ): Nothing = throw InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))

    private fun notFound() =
        NotFoundException(ChecklistsErrorCodes.CHECKLIST_ROUTINE_NOT_FOUND, "Rotina de checklist não encontrada.")
}

/**
 * Geração das rotinas de uma data operacional (RF-CHK-08, H14, ARCHITECTURE §13): para cada rotina ativa, de
 * modelo ativo e publicado, que ocorre na data, uma execução pendente com os candidatos escalados no setor num
 * turno que cobre o horário dela (`StaffApi.daySchedule`). Idempotente por rotina e data: gerar de novo (o evento
 * reentregue) não cria nada (`checklist_execution_routine_day_uk`). Auditada com o ator sistema (uma entrada por
 * execução criada).
 */
@Service
class ChecklistRoutineGeneration(
    private val routines: ChecklistRoutineStore,
    private val executions: ChecklistExecutionStore,
    private val staff: StaffApi,
    private val hotel: HotelApi,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** Gera as execuções da [date]; devolve quantas criou. */
    @Audited(ChecklistsAudit.GENERATE_ROUTINES)
    @Transactional
    fun generate(date: LocalDate): Int {
        val zone = hotel.timezone()
        val schedules = mutableMapOf<Pair<LocalDate, DepartmentId>, DaySchedule>()

        fun schedule(
            day: LocalDate,
            department: DepartmentId,
        ) = schedules.getOrPut(day to department) { staff.daySchedule(day, department) }

        var created = 0
        for ((routine, context) in routines.generating()) {
            if (!routine.occursOn(date)) continue
            val definition = routine.definition
            val candidates =
                ChecklistRoutine.candidates(
                    definition.dueAt,
                    schedule(date, definition.departmentId),
                    schedule(date.minusDays(1), definition.departmentId),
                )
            val execution =
                ChecklistExecution.scheduled(
                    routine.id,
                    definition.templateId,
                    context,
                    definition.location,
                    date,
                    date.atTime(definition.dueAt).atZone(zone).toInstant(),
                    candidates,
                    clock.instant(),
                )
            if (executions.addScheduled(execution)) {
                audit.created(ChecklistsAudit.EXECUTION, execution.id.value) { execution.auditState() }
                created++
            }
        }
        if (created == 0) audit.noChanges()
        return created
    }
}

/**
 * Escuta a virada da data operacional (D1, ARCHITECTURE §3.1 e §13) e gera as rotinas da data nova. Assíncrono,
 * em transação própria e reentregue pelo outbox se falhar: idempotente pela geração.
 */
@Component
class ChecklistRoutineListener(
    private val generation: ChecklistRoutineGeneration,
) {
    @ApplicationModuleListener
    fun on(event: BusinessDateAdvanced) {
        val created = generation.generate(event.current)
        log.info("Rotinas de checklist de {}: {} execuções geradas", event.current, created)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ChecklistRoutineListener::class.java)
    }
}

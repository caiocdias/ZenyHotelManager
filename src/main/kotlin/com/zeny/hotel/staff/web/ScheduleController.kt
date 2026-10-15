package com.zeny.hotel.staff.web

import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.ScheduleEntrySource
import com.zeny.hotel.staff.SchedulePatternKind
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.application.GenerateScheduleCommand
import com.zeny.hotel.staff.application.GenerationResult
import com.zeny.hotel.staff.application.PatternCommand
import com.zeny.hotel.staff.application.RecordEntriesCommand
import com.zeny.hotel.staff.application.ScheduleEntryView
import com.zeny.hotel.staff.application.SchedulePatternView
import com.zeny.hotel.staff.application.ScheduleQuery
import com.zeny.hotel.staff.application.ScheduleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Padrões de escala (RF-PES-03): 12x36, 6x1 ou fixo, por colaborador e turno. Consultar exige
 * `staff.schedule.read` (com escopo `OWN`, só os do colaborador vinculado ao usuário); escrever,
 * `staff.schedule.manage`. Padrões não são apagados: desativados, deixam de gerar a escala.
 */
@ApiV1Controller
@RequestMapping("/schedule-patterns")
@Tag(name = "staff", description = "Setores, colaboradores, turnos e escala.")
class SchedulePatternsController(
    private val schedule: ScheduleService,
) {
    @GetMapping
    @Operation(
        operationId = "listSchedulePatterns",
        summary = "Listar padrões de escala",
        description =
            "Em ordem de criação, opcionalmente de um colaborador. Exige `staff.schedule.read`; com escopo `OWN`, " +
                "só os do colaborador vinculado ao usuário.",
    )
    fun list(
        @Parameter(description = "Só os padrões deste colaborador.")
        @RequestParam(required = false) employeeId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<SchedulePatternResponse> =
        schedule.patterns(employeeId?.let(::EmployeeId), page).map(SchedulePatternResponse::of)

    @PostMapping
    @Operation(
        operationId = "createSchedulePattern",
        summary = "Criar um padrão de escala",
        description =
            "Exige `staff.schedule.manage`. 12x36: um dia sim, um não, a partir de `startsOn`; 6x1: seis dias de " +
                "trabalho e um de folga, a partir de `startsOn`; fixo: os `weekdays` (obrigatórios só nele). Fim " +
                "antes do início ou dias da semana incoerentes: 400 `SCHEDULE_PATTERN_INVALID`. Colaborador ou turno " +
                "inexistente: 400 `UNKNOWN_EMPLOYEE`/`UNKNOWN_SHIFT`; colaborador desativado: 422 " +
                "`EMPLOYEE_INACTIVE`; outro padrão ativo do colaborador no período: 409 `SCHEDULE_PATTERN_OVERLAP`. " +
                "A escala só muda ao gerar (`POST /schedule/generate`).",
    )
    @ApiResponse(responseCode = "201", description = "Padrão criado.")
    fun create(
        @Valid @RequestBody request: CreateSchedulePatternRequest,
    ): ResponseEntity<SchedulePatternResponse> {
        val view = schedule.createPattern(EmployeeId(request.employeeId), request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/schedule-patterns/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(SchedulePatternResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getSchedulePattern",
        summary = "Consultar um padrão de escala",
        description =
            "Exige `staff.schedule.read`. Inexistente (ou de outro colaborador, com escopo `OWN`): 404 " +
                "`SCHEDULE_PATTERN_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<SchedulePatternResponse> = ok(schedule.pattern(id))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateSchedulePattern",
        summary = "Editar um padrão de escala",
        description =
            "Troca tipo, turno, período e dias da semana (o colaborador não muda), com as regras da criação. Exige " +
                "`staff.schedule.manage` e o `If-Match` (ausente: 428; versão diferente: 412). Padrão desativado: " +
                "422 `SCHEDULE_PATTERN_INACTIVE`. As entradas já geradas só mudam na próxima geração.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: SchedulePatternRequest,
    ): ResponseEntity<SchedulePatternResponse> =
        ok(schedule.updatePattern(id, ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateSchedulePattern",
        summary = "Desativar um padrão de escala",
        description =
            "Exige `staff.schedule.manage`. O padrão deixa de gerar entradas; as que ele gerou saem na próxima " +
                "geração do período. Desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<SchedulePatternResponse> = ok(schedule.deactivatePattern(id))

    private fun ok(view: SchedulePatternView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(SchedulePatternResponse.of(view))
}

/**
 * A escala por data (RF-PES-03): consulta por período, setor e colaborador; geração a partir dos
 * padrões; lançamento de atribuições e exceções (troca, folga, falta, férias), que prevalecem sobre a
 * geração.
 */
@ApiV1Controller
@RequestMapping("/schedule")
@Tag(name = "staff", description = "Setores, colaboradores, turnos e escala.")
class ScheduleController(
    private val schedule: ScheduleService,
) {
    @GetMapping
    @Operation(
        operationId = "listScheduleEntries",
        summary = "Consultar a escala",
        description =
            "As entradas de `from` a `to` (no máximo 92 dias; sem `from`, a data operacional; sem `to`, o próprio " +
                "`from`), em ordem de data, opcionalmente de um setor (o do turno, ou o do colaborador nas " +
                "ausências) ou de um colaborador. Exige `staff.schedule.read`; com escopo `OWN`, só as do " +
                "colaborador vinculado ao usuário. Período inválido: 400 `SCHEDULE_PERIOD_INVALID`.",
    )
    fun list(
        @Parameter(description = "Primeira data (YYYY-MM-DD); padrão: a data operacional.")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Última data, inclusive (YYYY-MM-DD); padrão: `from`.")
        @RequestParam(required = false) to: LocalDate?,
        @Parameter(description = "Só as entradas deste setor.")
        @RequestParam(required = false) departmentId: UUID?,
        @Parameter(description = "Só as entradas deste colaborador.")
        @RequestParam(required = false) employeeId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<ScheduleEntryResponse> =
        schedule
            .entries(
                ScheduleQuery(from, to, departmentId?.let(::DepartmentId), employeeId?.let(::EmployeeId)),
                page,
            ).map(ScheduleEntryResponse::of)

    @PostMapping("/generate")
    @Operation(
        operationId = "generateSchedule",
        summary = "Gerar a escala a partir dos padrões",
        description =
            "Para o período (no máximo 92 dias, começando na data operacional ou depois: 422 " +
                "`SCHEDULE_PERIOD_IN_PAST`), opcionalmente só dos colaboradores de um setor ou de um colaborador: " +
                "cria as entradas que os padrões ativos dos colaboradores ativos pedem, refaz as geradas que " +
                "mudaram e retira as geradas que nenhum padrão pede mais. As lançadas à mão (atribuição, troca, " +
                "folga, falta, férias) nunca são sobrescritas. Gerar de novo sem mudança não faz nada. Exige " +
                "`staff.schedule.manage`.",
    )
    fun generate(
        @Valid @RequestBody request: GenerateScheduleRequest,
    ): GenerationResponse =
        GenerationResponse.of(
            schedule.generate(
                GenerateScheduleCommand(
                    request.from,
                    request.to,
                    request.departmentId?.let(::DepartmentId),
                    request.employeeId?.let(::EmployeeId),
                ),
            ),
        )

    @PostMapping("/entries")
    @Operation(
        operationId = "recordScheduleEntries",
        summary = "Lançar atribuição ou exceção na escala",
        description =
            "Lança, em cada data de `from` a `to` (no máximo 92 dias), um turno (`SHIFT`), uma troca (`SWAP`), uma " +
                "folga (`DAY_OFF`), uma falta (`ABSENCE`) ou férias (`VACATION`) do colaborador. Turno e troca " +
                "exigem `shiftId`; folga, falta e férias não o aceitam (400 `SCHEDULE_ENTRY_INVALID`). A entrada " +
                "de cada data, gerada ou lançada antes, passa a ser esta e a geração não a toca mais. Colaborador " +
                "ou turno inexistente: 400 `UNKNOWN_EMPLOYEE`/`UNKNOWN_SHIFT`; colaborador desativado: 422 " +
                "`EMPLOYEE_INACTIVE`. Exige `staff.schedule.manage`.",
    )
    fun record(
        @Valid @RequestBody request: RecordScheduleEntriesRequest,
    ): ScheduleEntriesResponse =
        ScheduleEntriesResponse(
            schedule
                .record(
                    RecordEntriesCommand(
                        EmployeeId(request.employeeId),
                        request.kind,
                        request.from,
                        request.to ?: request.from,
                        request.shiftId?.let(::ShiftId),
                        request.note,
                    ),
                ).map(ScheduleEntryResponse::of),
        )
}

@Schema(description = "A definição de um padrão de escala.")
data class SchedulePatternRequest(
    @field:NotNull
    val kind: SchedulePatternKind,
    @field:Schema(description = "Turno em que o colaborador trabalha nos dias do padrão.")
    @field:NotNull
    val shiftId: UUID,
    @field:Schema(description = "Primeiro dia do padrão (o ciclo de 12x36 e de 6x1 conta a partir dele).")
    @field:NotNull
    val startsOn: LocalDate,
    @field:Schema(description = "Último dia, inclusive; nulo: sem fim.", nullable = true)
    val endsOn: LocalDate? = null,
    @field:ArraySchema(arraySchema = Schema(description = "Dias da semana do padrão fixo; vazio nos outros."))
    @field:Size(max = 7)
    val weekdays: Set<DayOfWeek> = emptySet(),
) {
    fun toCommand() = PatternCommand(kind, ShiftId(shiftId), startsOn, endsOn, weekdays)
}

@Schema(description = "Um padrão de escala novo, para um colaborador.")
data class CreateSchedulePatternRequest(
    @field:NotNull
    val employeeId: UUID,
    @field:NotNull
    val kind: SchedulePatternKind,
    @field:NotNull
    val shiftId: UUID,
    @field:NotNull
    val startsOn: LocalDate,
    @field:Schema(nullable = true)
    val endsOn: LocalDate? = null,
    @field:Size(max = 7)
    val weekdays: Set<DayOfWeek> = emptySet(),
) {
    fun toCommand() = PatternCommand(kind, ShiftId(shiftId), startsOn, endsOn, weekdays)
}

@Schema(description = "Um padrão de escala de um colaborador.")
data class SchedulePatternResponse(
    val id: UUID,
    val employeeId: UUID,
    val shiftId: UUID,
    val kind: SchedulePatternKind,
    val startsOn: LocalDate,
    @field:Schema(nullable = true)
    val endsOn: LocalDate?,
    val weekdays: List<DayOfWeek>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: SchedulePatternView) =
            SchedulePatternResponse(
                view.id,
                view.employeeId.value,
                view.shiftId.value,
                view.kind,
                view.startsOn,
                view.endsOn,
                view.weekdays.sorted(),
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

@Schema(description = "Período e alcance da geração da escala.")
data class GenerateScheduleRequest(
    @field:NotNull
    val from: LocalDate,
    @field:NotNull
    val to: LocalDate,
    @field:Schema(description = "Só os colaboradores deste setor.", nullable = true)
    val departmentId: UUID? = null,
    @field:Schema(description = "Só este colaborador.", nullable = true)
    val employeeId: UUID? = null,
)

@Schema(description = "O que a geração fez.")
data class GenerationResponse(
    val from: LocalDate,
    val to: LocalDate,
    val created: Int,
    val updated: Int,
    val removed: Int,
    @field:Schema(description = "Entradas lançadas à mão que a geração deixou como estavam.")
    val keptManual: Int,
) {
    companion object {
        fun of(result: GenerationResult) =
            GenerationResponse(
                result.from,
                result.to,
                result.created,
                result.updated,
                result.removed,
                result.keptManual,
            )
    }
}

@Schema(description = "Atribuição ou exceção a lançar em cada data do período.")
data class RecordScheduleEntriesRequest(
    @field:NotNull
    val employeeId: UUID,
    @field:NotNull
    val kind: ScheduleEntryKind,
    @field:NotNull
    val from: LocalDate,
    @field:Schema(description = "Última data, inclusive; padrão: `from`.", nullable = true)
    val to: LocalDate? = null,
    @field:Schema(description = "Turno, obrigatório em `SHIFT` e `SWAP` e proibido nos demais.", nullable = true)
    val shiftId: UUID? = null,
    @field:Schema(nullable = true)
    @field:Size(max = 200)
    val note: String? = null,
)

@Schema(description = "O turno de uma entrada da escala.")
data class EntryShiftResponse(
    val id: UUID,
    val name: String,
    @field:Schema(example = "19:00")
    val start: LocalTime,
    @field:Schema(example = "07:00")
    val end: LocalTime,
    @field:Schema(description = "Se termina no dia seguinte à data da entrada.")
    val crossesMidnight: Boolean,
)

@Schema(description = "Uma entrada da escala: o que o colaborador faz na data.")
data class ScheduleEntryResponse(
    val id: UUID,
    val employeeId: UUID,
    val employeeName: String,
    @field:Schema(description = "Data da entrada; um turno que atravessa a meia-noite é da data em que começa.")
    val date: LocalDate,
    val kind: ScheduleEntryKind,
    @field:Schema(description = "`PATTERN`: gerada por um padrão; `MANUAL`: lançada à mão, prevalece sobre a geração.")
    val source: ScheduleEntrySource,
    @field:Schema(description = "Nulo em folga, falta e férias.", nullable = true)
    val shift: EntryShiftResponse?,
    val departmentId: UUID,
    @field:Schema(nullable = true)
    val patternId: UUID?,
    @field:Schema(nullable = true)
    val note: String?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: ScheduleEntryView) =
            ScheduleEntryResponse(
                view.id,
                view.employeeId.value,
                view.employeeName,
                view.date,
                view.kind,
                view.source,
                view.shift?.let { EntryShiftResponse(it.id.value, it.name, it.start, it.end, it.crossesMidnight) },
                view.departmentId.value,
                view.patternId,
                view.note,
                view.updatedAt,
            )
    }
}

@Schema(description = "As entradas lançadas, uma por data.")
data class ScheduleEntriesResponse(
    val items: List<ScheduleEntryResponse>,
)

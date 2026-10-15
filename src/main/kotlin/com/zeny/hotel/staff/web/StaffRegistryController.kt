package com.zeny.hotel.staff.web

import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.DepartmentKey
import com.zeny.hotel.staff.EmployeeId
import com.zeny.hotel.staff.ShiftId
import com.zeny.hotel.staff.application.DepartmentView
import com.zeny.hotel.staff.application.EmployeeCommand
import com.zeny.hotel.staff.application.EmployeeView
import com.zeny.hotel.staff.application.ShiftView
import com.zeny.hotel.staff.application.StaffRegistryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
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
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

private const val STAFF_TAG = "staff"
private const val STAFF_TAG_DESCRIPTION = "Setores, colaboradores, turnos e escala."

/**
 * Setores (RF-PES-01, H6): os seis da SPEC §5.3 vêm pré-carregados. Consultar exige
 * `staff.employees.read`; criar e renomear, `staff.employees.manage` (a edição com `If-Match`).
 * Setores nunca são apagados.
 */
@ApiV1Controller
@RequestMapping("/departments")
@Tag(name = STAFF_TAG, description = STAFF_TAG_DESCRIPTION)
class DepartmentsController(
    private val registry: StaffRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listDepartments",
        summary = "Listar setores",
        description = "Em ordem de criação (os seis pré-carregados primeiro). Exige `staff.employees.read`.",
    )
    fun list(
        @Valid page: PageParams,
    ): CursorPage<DepartmentResponse> = registry.departments(page).map(DepartmentResponse::of)

    @PostMapping
    @Operation(
        operationId = "createDepartment",
        summary = "Criar um setor",
        description =
            "Exige `staff.employees.manage`. Nome já usado, sem diferenciar maiúsculas: 409 " +
                "`DEPARTMENT_NAME_ALREADY_IN_USE`.",
    )
    @ApiResponse(responseCode = "201", description = "Setor criado.")
    fun create(
        @Valid @RequestBody request: DepartmentRequest,
    ): ResponseEntity<DepartmentResponse> {
        val view = registry.createDepartment(request.name)
        return ResponseEntity
            .created(URI.create("/api/v1/departments/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(DepartmentResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getDepartment",
        summary = "Consultar um setor",
        description = "Exige `staff.employees.read`. Inexistente: 404 `DEPARTMENT_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<DepartmentResponse> = ok(registry.department(DepartmentId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateDepartment",
        summary = "Renomear um setor",
        description =
            "Exige `staff.employees.manage` e o `If-Match` (ausente: 428; versão diferente: 412). A chave dos " +
                "setores pré-carregados não muda. Nome já usado: 409 `DEPARTMENT_NAME_ALREADY_IN_USE`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: DepartmentRequest,
    ): ResponseEntity<DepartmentResponse> =
        ok(registry.renameDepartment(DepartmentId(id), ETags.expectedVersion(ifMatch), request.name))

    private fun ok(view: DepartmentView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(DepartmentResponse.of(view))
}

/**
 * Colaboradores (RF-PES-01, RF-ACS-12): nome, cargo, contato, setor e o vínculo opcional com um
 * usuário (H7). Consultar exige `staff.employees.read`; escrever, `staff.employees.manage`.
 * Colaboradores nunca são apagados, só desativados.
 */
@ApiV1Controller
@RequestMapping("/employees")
@Tag(name = STAFF_TAG, description = STAFF_TAG_DESCRIPTION)
class EmployeesController(
    private val registry: StaffRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listEmployees",
        summary = "Listar colaboradores",
        description =
            "Em ordem de nome, opcionalmente de um setor e só ativos ou inativos. Exige `staff.employees.read`.",
    )
    fun list(
        @Parameter(description = "Só os colaboradores deste setor.")
        @RequestParam(required = false) departmentId: UUID?,
        @Parameter(description = "Só os ativos (`true`) ou só os inativos (`false`); omita para todos.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<EmployeeResponse> =
        registry.employees(departmentId?.let(::DepartmentId), active, page).map(EmployeeResponse::of)

    @PostMapping
    @Operation(
        operationId = "createEmployee",
        summary = "Cadastrar um colaborador",
        description =
            "Exige `staff.employees.manage`. Setor inexistente: 400 `UNKNOWN_DEPARTMENT`. O usuário vinculado " +
                "(opcional) precisa existir (400 `UNKNOWN_USER`), estar ativo (422 `USER_INACTIVE`) e não estar " +
                "vinculado a outro colaborador (409 `USER_ALREADY_LINKED`).",
    )
    @ApiResponse(responseCode = "201", description = "Colaborador cadastrado.")
    fun create(
        @Valid @RequestBody request: EmployeeRequest,
    ): ResponseEntity<EmployeeResponse> {
        val view = registry.createEmployee(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/employees/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(EmployeeResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getEmployee",
        summary = "Consultar um colaborador",
        description = "Exige `staff.employees.read`. Inexistente: 404 `EMPLOYEE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<EmployeeResponse> = ok(registry.employee(EmployeeId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateEmployee",
        summary = "Editar um colaborador",
        description =
            "Troca os dados e o usuário vinculado (nulo: sem vínculo). Exige `staff.employees.manage` e o " +
                "`If-Match` (ausente: 428; versão diferente: 412). Um usuário novo no vínculo segue as regras do " +
                "cadastro (`UNKNOWN_USER`, `USER_INACTIVE`, `USER_ALREADY_LINKED`).",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: EmployeeRequest,
    ): ResponseEntity<EmployeeResponse> =
        ok(registry.updateEmployee(EmployeeId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateEmployee",
        summary = "Desativar um colaborador",
        description =
            "Exige `staff.employees.manage`. Nada é apagado: o cadastro, o vínculo e o histórico ficam; as " +
                "entradas de trabalho (turno e troca) a partir da data operacional saem da escala, e a geração " +
                "deixa de escalá-lo. Desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<EmployeeResponse> = ok(registry.deactivateEmployee(EmployeeId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateEmployee",
        summary = "Reativar um colaborador",
        description = "Exige `staff.employees.manage`. A próxima geração volta a escalá-lo pelos padrões ativos.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<EmployeeResponse> = ok(registry.reactivateEmployee(EmployeeId(id)))

    private fun ok(view: EmployeeView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(EmployeeResponse.of(view))
}

/**
 * Turnos por setor (RF-PES-02). Consultar exige `staff.employees.read`; criar e editar,
 * `staff.employees.manage` (a edição com `If-Match`).
 */
@ApiV1Controller
@RequestMapping("/shifts")
@Tag(name = STAFF_TAG, description = STAFF_TAG_DESCRIPTION)
class ShiftsController(
    private val registry: StaffRegistryService,
) {
    @GetMapping
    @Operation(
        operationId = "listShifts",
        summary = "Listar turnos",
        description = "Em ordem de criação, opcionalmente de um setor. Exige `staff.employees.read`.",
    )
    fun list(
        @Parameter(description = "Só os turnos deste setor.")
        @RequestParam(required = false) departmentId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<ShiftResponse> = registry.shifts(departmentId?.let(::DepartmentId), page).map(ShiftResponse::of)

    @PostMapping
    @Operation(
        operationId = "createShift",
        summary = "Criar um turno",
        description =
            "Exige `staff.employees.manage`. Horários em horas e minutos, no fuso do hotel; fim antes do início é " +
                "um turno que atravessa a meia-noite (pertence à data em que começa). Início igual ao fim ou com " +
                "segundos: 400 `SHIFT_TIMES_INVALID`. Setor inexistente: 400 `UNKNOWN_DEPARTMENT`. Nome já usado no " +
                "setor: 409 `SHIFT_NAME_ALREADY_IN_USE`.",
    )
    @ApiResponse(responseCode = "201", description = "Turno criado.")
    fun create(
        @Valid @RequestBody request: CreateShiftRequest,
    ): ResponseEntity<ShiftResponse> {
        val view =
            registry.createShift(
                DepartmentId(request.departmentId),
                request.name,
                request.start,
                request.end,
            )
        return ResponseEntity
            .created(URI.create("/api/v1/shifts/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(ShiftResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getShift",
        summary = "Consultar um turno",
        description = "Exige `staff.employees.read`. Inexistente: 404 `SHIFT_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ShiftResponse> = ok(registry.shift(ShiftId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateShift",
        summary = "Editar um turno",
        description =
            "Troca nome e horários (o setor não muda). Exige `staff.employees.manage` e o `If-Match` (ausente: 428; " +
                "versão diferente: 412). As entradas da escala que usam o turno passam a mostrar os horários novos.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: UpdateShiftRequest,
    ): ResponseEntity<ShiftResponse> =
        ok(
            registry.updateShift(
                ShiftId(id),
                ETags.expectedVersion(ifMatch),
                request.name,
                request.start,
                request.end,
            ),
        )

    private fun ok(view: ShiftView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(ShiftResponse.of(view))
}

@Schema(description = "Nome de um setor.")
data class DepartmentRequest(
    @field:NotBlank
    @field:Size(max = 80)
    val name: String,
)

@Schema(description = "Um setor.")
data class DepartmentResponse(
    val id: UUID,
    @field:Schema(description = "Chave dos seis setores pré-carregados; nula nos criados depois.", nullable = true)
    val key: DepartmentKey?,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: DepartmentView) =
            DepartmentResponse(view.id.value, view.key, view.name, view.createdAt, view.updatedAt)
    }
}

@Schema(description = "Dados de um colaborador.")
data class EmployeeRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val name: String,
    @field:Schema(description = "Cargo.")
    @field:NotBlank
    @field:Size(max = 80)
    val position: String,
    @field:Schema(description = "Telefone de contato.", nullable = true, example = "+55 11 98765-4321")
    @field:Size(max = 30)
    @field:Pattern(regexp = "[0-9+() -]*")
    val phone: String? = null,
    @field:Schema(description = "E-mail de contato.", nullable = true)
    @field:Size(max = 254)
    @field:Email
    val email: String? = null,
    @field:Schema(description = "Setor do colaborador.")
    @field:NotNull
    val departmentId: UUID,
    @field:Schema(description = "Usuário vinculado (opcional; no máximo um colaborador por usuário).", nullable = true)
    val userId: UUID? = null,
) {
    fun toCommand() = EmployeeCommand(name, position, phone, email, DepartmentId(departmentId), userId?.let(::UserId))
}

@Schema(description = "Um colaborador.")
data class EmployeeResponse(
    val id: UUID,
    val name: String,
    val position: String,
    @field:Schema(nullable = true)
    val phone: String?,
    @field:Schema(nullable = true)
    val email: String?,
    val departmentId: UUID,
    @field:Schema(description = "Usuário vinculado.", nullable = true)
    val userId: UUID?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: EmployeeView) =
            EmployeeResponse(
                view.id.value,
                view.name,
                view.position,
                view.phone,
                view.email,
                view.departmentId.value,
                view.userId?.value,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

@Schema(description = "Um turno novo.")
data class CreateShiftRequest(
    @field:NotNull
    val departmentId: UUID,
    @field:NotBlank
    @field:Size(max = 60)
    val name: String,
    @field:Schema(description = "Início, no fuso do hotel.", example = "07:00")
    @field:NotNull
    val start: LocalTime,
    @field:Schema(description = "Fim, no fuso do hotel; antes do início, termina no dia seguinte.", example = "19:00")
    @field:NotNull
    val end: LocalTime,
)

@Schema(description = "Nome e horários de um turno.")
data class UpdateShiftRequest(
    @field:NotBlank
    @field:Size(max = 60)
    val name: String,
    @field:Schema(description = "Início, no fuso do hotel.", example = "07:00")
    @field:NotNull
    val start: LocalTime,
    @field:Schema(description = "Fim, no fuso do hotel; antes do início, termina no dia seguinte.", example = "19:00")
    @field:NotNull
    val end: LocalTime,
)

@Schema(description = "Um turno de um setor.")
data class ShiftResponse(
    val id: UUID,
    val departmentId: UUID,
    val name: String,
    @field:Schema(example = "19:00")
    val start: LocalTime,
    @field:Schema(example = "07:00")
    val end: LocalTime,
    @field:Schema(description = "Se o turno termina no dia seguinte ao início.")
    val crossesMidnight: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: ShiftView) =
            ShiftResponse(
                view.id.value,
                view.departmentId.value,
                view.name,
                view.start,
                view.end,
                view.crossesMidnight,
                view.createdAt,
                view.updatedAt,
            )
    }
}

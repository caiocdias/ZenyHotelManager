package com.zeny.hotel.checklists.web

import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistRoutineView
import com.zeny.hotel.checklists.application.ChecklistRoutinesService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
import java.util.UUID

private const val ROUTINE_RULES =
    "Recusas: 400 `CHECKLIST_ROUTINE_INVALID` (nome, dias da semana só na semanal e obrigatórios nela, horário com " +
        "segundos, local), 400 `UNKNOWN_CHECKLIST_TEMPLATE` e `UNKNOWN_DEPARTMENT`, 422 `CHECKLIST_TEMPLATE_INACTIVE`."

/**
 * Rotinas de checklist (RF-CHK-08, H14): checklists recorrentes por setor e escala. A geração das execuções é só
 * pela virada da data operacional (listener de `BusinessDateAdvanced`): não há endpoint de geração. Consultar
 * exige `checklists.routines.read`; escrever, `checklists.routines.manage`. Nada é apagado.
 */
@ApiV1Controller
@RequestMapping("/checklist-routines")
@Tag(name = "checklists", description = "Modelos de checklist, rascunhos e versões publicadas.")
class ChecklistRoutinesController(
    private val routines: ChecklistRoutinesService,
) {
    @GetMapping
    @Operation(
        operationId = "listChecklistRoutines",
        summary = "Listar rotinas de checklist",
        description = "Em ordem de nome. Exige `checklists.routines.read`.",
    )
    fun list(
        @Parameter(description = "Só as ativas (`true`) ou só as inativas (`false`); omita para todas.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<ChecklistRoutineResponse> = routines.routines(active, page).map(ChecklistRoutineResponse::of)

    @PostMapping
    @Operation(
        operationId = "createChecklistRoutine",
        summary = "Criar uma rotina de checklist",
        description =
            "A rotina gera, a cada virada da data operacional em que ocorre, uma execução pendente com os candidatos " +
                "escalados no setor num turno que cobre o horário (H14). Exige `checklists.routines.manage`. " +
                ROUTINE_RULES,
    )
    @ApiResponse(responseCode = "201", description = "Rotina criada.")
    fun create(
        @Valid @RequestBody request: ChecklistRoutineRequest,
    ): ResponseEntity<ChecklistRoutineResponse> {
        val view = routines.create(request.toDefinition())
        return ResponseEntity
            .created(URI.create("/api/v1/checklist-routines/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(ChecklistRoutineResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getChecklistRoutine",
        summary = "Consultar uma rotina",
        description = "Exige `checklists.routines.read`. Inexistente: 404 `CHECKLIST_ROUTINE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistRoutineResponse> = ok(routines.routine(ChecklistRoutineId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateChecklistRoutine",
        summary = "Editar uma rotina",
        description =
            "Troca todos os dados; vale nas próximas gerações (as execuções já geradas ficam). Exige " +
                "`checklists.routines.manage` e o `If-Match` (ausente: 428; versão diferente: 412). $ROUTINE_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: ChecklistRoutineRequest,
    ): ResponseEntity<ChecklistRoutineResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return ok(routines.update(ChecklistRoutineId(id), expected, request.toDefinition()))
    }

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateChecklistRoutine",
        summary = "Desativar uma rotina",
        description = "Desativada, não gera mais execuções. Exige `checklists.routines.manage`.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistRoutineResponse> = ok(routines.deactivate(ChecklistRoutineId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateChecklistRoutine",
        summary = "Reativar uma rotina",
        description = "Exige `checklists.routines.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistRoutineResponse> = ok(routines.reactivate(ChecklistRoutineId(id)))

    private fun ok(view: ChecklistRoutineView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(ChecklistRoutineResponse.of(view))
}

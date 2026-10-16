package com.zeny.hotel.checklists.web

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.application.ChecklistTemplateView
import com.zeny.hotel.checklists.application.ChecklistTemplatesService
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

private const val IF_MATCH_RULE = "o `If-Match` (ausente: 428; versão diferente: 412)"
private const val CONTENT_RULES =
    "Recusas do conteúdo, `400` com o campo em `errors[]`: `CHECKLIST_DATA_INVALID`, " +
        "`CHECKLIST_ITEM_KEY_DUPLICATED`, `CHECKLIST_ITEM_OPTIONS_INVALID`, `CHECKLIST_CONDITION_UNKNOWN_ITEM`, " +
        "`CHECKLIST_CONDITION_FORWARD_REFERENCE` (a condição só cita item anterior), " +
        "`CHECKLIST_CONDITION_INCOMPATIBLE`, `CHECKLIST_ACTION_INVALID`, `CHECKLIST_ACTION_ITEM_TYPE_MISMATCH` " +
        "(baixa de estoque e troca de enxoval só em item de quantidade; ocorrência só em item de marcação) e " +
        "`CHECKLIST_APPLICABILITY_INVALID`."

/**
 * Modelos de checklist (RF-CHK-01 a 06): o rascunho é editado com `If-Match` e cada publicação congela uma
 * versão nova, que nunca muda. Consultar exige `checklists.templates.read`; escrever,
 * `checklists.templates.manage`. Nada é apagado: não há `DELETE`, só desativação.
 */
@ApiV1Controller
@RequestMapping("/checklist-templates")
@Tag(name = "checklists", description = "Modelos de checklist, rascunhos e versões publicadas.")
class ChecklistTemplatesController(
    private val templates: ChecklistTemplatesService,
) {
    @GetMapping
    @Operation(
        operationId = "listChecklistTemplates",
        summary = "Listar modelos de checklist",
        description = "Em ordem de nome, sem o conteúdo. Exige `checklists.templates.read`.",
    )
    fun list(
        @Parameter(description = "Só os modelos deste contexto.")
        @RequestParam(required = false) context: ChecklistContext?,
        @Parameter(description = "Só os ativos (`true`) ou só os inativos (`false`); omita para todos.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<ChecklistTemplateSummaryResponse> =
        templates.templates(context, active, page).map(ChecklistTemplateSummaryResponse::of)

    @PostMapping
    @Operation(
        operationId = "createChecklistTemplate",
        summary = "Criar um modelo de checklist",
        description =
            "Cria o modelo com o rascunho, sem publicar. Exige `checklists.templates.manage`. $CONTENT_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Modelo criado (rascunho).")
    fun create(
        @Valid @RequestBody request: CreateChecklistTemplateRequest,
    ): ResponseEntity<ChecklistTemplateResponse> {
        val command = request.toCommand()
        val view = templates.create(request.context, command)
        return ResponseEntity
            .created(URI.create("/api/v1/checklist-templates/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(ChecklistTemplateResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getChecklistTemplate",
        summary = "Consultar um modelo de checklist",
        description =
            "Com o rascunho e o número da última versão publicada. Exige `checklists.templates.read`. Inexistente: " +
                "404 `CHECKLIST_TEMPLATE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistTemplateResponse> = ok(templates.template(ChecklistTemplateId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateChecklistTemplate",
        summary = "Editar o rascunho de um modelo",
        description =
            "Troca o nome (vale na hora) e o rascunho inteiro (aplicabilidade, seções e itens; vale na próxima " +
                "publicação). As versões publicadas não mudam. Exige `checklists.templates.manage` e $IF_MATCH_RULE. " +
                "Modelo desativado: 422 `CHECKLIST_TEMPLATE_INACTIVE`. $CONTENT_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: UpdateChecklistTemplateRequest,
    ): ResponseEntity<ChecklistTemplateResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return ok(templates.update(ChecklistTemplateId(id), expected, request.toCommand()))
    }

    @PostMapping("/{id}/publish")
    @Operation(
        operationId = "publishChecklistTemplate",
        summary = "Publicar o rascunho como uma versão nova",
        description =
            "Congela o rascunho na versão seguinte (1, 2, …); as anteriores continuam iguais. Exige " +
                "`checklists.templates.manage` e $IF_MATCH_RULE: publica-se o rascunho que se viu. Rascunho igual à " +
                "última versão: 422 `CHECKLIST_NOTHING_TO_PUBLISH`; modelo desativado: 422 " +
                "`CHECKLIST_TEMPLATE_INACTIVE`.",
    )
    @ApiResponse(responseCode = "201", description = "Versão publicada.")
    fun publish(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<ChecklistVersionResponse> {
        val view = templates.publish(ChecklistTemplateId(id), ETags.expectedVersion(ifMatch))
        return ResponseEntity
            .created(URI.create("/api/v1/checklist-templates/$id/versions/${view.number}"))
            .body(ChecklistVersionResponse.of(view))
    }

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateChecklistTemplate",
        summary = "Desativar um modelo",
        description =
            "Exige `checklists.templates.manage`. Nada é apagado: as versões publicadas ficam, mas o modelo deixa de " +
                "ser escolhido para os sujeitos e não é editado nem publicado. Desativar de novo não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistTemplateResponse> = ok(templates.deactivate(ChecklistTemplateId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateChecklistTemplate",
        summary = "Reativar um modelo",
        description = "Exige `checklists.templates.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<ChecklistTemplateResponse> = ok(templates.reactivate(ChecklistTemplateId(id)))

    @GetMapping("/{id}/versions")
    @Operation(
        operationId = "listChecklistTemplateVersions",
        summary = "Listar as versões publicadas de um modelo",
        description = "Da mais nova para a mais antiga, sem o conteúdo. Exige `checklists.templates.read`.",
    )
    fun versions(
        @PathVariable id: UUID,
        @Valid page: PageParams,
    ): CursorPage<ChecklistVersionSummaryResponse> =
        templates.versions(ChecklistTemplateId(id), page).map(ChecklistVersionSummaryResponse::of)

    @GetMapping("/{id}/versions/{number}")
    @Operation(
        operationId = "getChecklistTemplateVersion",
        summary = "Consultar uma versão publicada",
        description =
            "O conteúdo congelado na publicação. Exige `checklists.templates.read`. Inexistente: 404 " +
                "`CHECKLIST_VERSION_NOT_FOUND`.",
    )
    fun version(
        @PathVariable id: UUID,
        @PathVariable number: Int,
    ): ChecklistVersionResponse = ChecklistVersionResponse.of(templates.version(ChecklistTemplateId(id), number))

    private fun ok(view: ChecklistTemplateView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(ChecklistTemplateResponse.of(view))
}

package com.zeny.hotel.housekeeping.web

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.CleaningTypeKey
import com.zeny.hotel.housekeeping.application.CleaningCreditView
import com.zeny.hotel.housekeeping.application.CleaningTypeCommand
import com.zeny.hotel.housekeeping.application.CleaningTypeView
import com.zeny.hotel.housekeeping.application.CleaningTypesService
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
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
import java.time.Instant
import java.util.UUID

internal const val HOUSEKEEPING_TAG = "housekeeping"
internal const val HOUSEKEEPING_TAG_DESCRIPTION =
    "Governança: tipos de limpeza, créditos por tipo de UH, faxinas programadas e tarefas."
internal const val READ = "Exige `housekeeping.config.read`."
internal const val MANAGE = "Exige `housekeeping.config.manage`."
private const val MAX_CREDITS = 200
private const val MAX_CREDIT = 100L
private const val MAX_PRIORITY = 5L
private const val MAX_LINEN_NIGHTS = 60L
private const val LINEN_DESCRIPTION =
    "Troca de enxoval a cada N noites (de 1 a 60). Nulo significa \"sem regra de troca por noites\": a troca é só a " +
        "pedido."

/**
 * Tipos de limpeza (RF-GOV-01, M6 E01): consultar exige `housekeeping.config.read`; escrever,
 * `housekeeping.config.manage`. Nunca são apagados, só desativados.
 */
@ApiV1Controller
@RequestMapping("/cleaning-types")
@Tag(name = HOUSEKEEPING_TAG, description = HOUSEKEEPING_TAG_DESCRIPTION)
class CleaningTypesController(
    private val cleaningTypes: CleaningTypesService,
) {
    @GetMapping
    @Operation(
        operationId = "listCleaningTypes",
        summary = "Listar tipos de limpeza",
        description = "Em ordem de código. $READ",
    )
    fun list(
        @Parameter(description = "Só os ativos (`true`) ou só os inativos (`false`); omita para todos.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<CleaningTypeResponse> = cleaningTypes.list(active, page).map(CleaningTypeResponse::of)

    @PostMapping
    @Operation(
        operationId = "createCleaningType",
        summary = "Cadastrar um tipo de limpeza",
        description =
            "$MANAGE Código repetido (inclusive de um desativado): 409 `CLEANING_TYPE_CODE_ALREADY_IN_USE`. $RULES",
    )
    @ApiResponse(responseCode = "201", description = "Tipo de limpeza cadastrado.")
    fun create(
        @Valid @RequestBody request: CleaningTypeRequest,
    ): ResponseEntity<CleaningTypeResponse> {
        val view = cleaningTypes.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/cleaning-types/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(CleaningTypeResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getCleaningType",
        summary = "Consultar um tipo de limpeza",
        description = "$READ Inexistente: 404 `CLEANING_TYPE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<CleaningTypeResponse> = ok(cleaningTypes.get(CleaningTypeId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateCleaningType",
        summary = "Editar um tipo de limpeza",
        description =
            "Troca código, nome, configuração e os créditos (a lista inteira); a chave (`key`) não muda. $MANAGE " +
                "Exige também o `If-Match` (ausente: 428; versão diferente: 412). Código de outro tipo: 409 " +
                "`CLEANING_TYPE_CODE_ALREADY_IN_USE`. $RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: CleaningTypeRequest,
    ): ResponseEntity<CleaningTypeResponse> =
        ok(cleaningTypes.update(CleaningTypeId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateCleaningType",
        summary = "Desativar um tipo de limpeza",
        description =
            "$MANAGE Nada é apagado; desativar de novo não muda nada. Com faxina programada ativa no tipo: 422 " +
                "`CLEANING_TYPE_HAS_ACTIVE_SCHEDULES` (desative a faxina antes).",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CleaningTypeResponse> = ok(cleaningTypes.deactivate(CleaningTypeId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateCleaningType",
        summary = "Reativar um tipo de limpeza",
        description = "$MANAGE Reativar um tipo ativo não muda nada.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CleaningTypeResponse> = ok(cleaningTypes.reactivate(CleaningTypeId(id)))

    private fun ok(view: CleaningTypeView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(CleaningTypeResponse.of(view))

    private companion object {
        const val RULES =
            "Créditos de 1 a 100, até 200, cada tipo de UH uma vez só (repetido: 400 " +
                "`CLEANING_TYPE_CREDIT_DUPLICATED`); tipo de UH inexistente: 400 `CLEANING_TYPE_UNKNOWN_ROOM_TYPE` " +
                "(campo `credits`); tipo de UH desativado é aceito. Um tipo ativo sem crédito para algum tipo de UH " +
                "ativo não é recusado: a resposta o aponta em `missingCreditRoomTypeIds`. Nome com NUL: 400 " +
                "`HOUSEKEEPING_DATA_INVALID`."
    }
}

@Schema(description = "Os dados de um tipo de limpeza; na edição, os créditos são a lista inteira.")
data class CleaningTypeRequest(
    @field:Schema(description = "Código único, em maiúsculas: letras, dígitos, `_` e `-`.", example = "CHECKOUT")
    @field:NotBlank
    @field:Size(max = 20)
    val code: String?,
    @field:Schema(example = "Saída")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(description = "Se a limpeza deste tipo exige inspeção da governanta.")
    @field:NotNull
    val requiresInspection: Boolean?,
    @field:Schema(description = "Prioridade padrão das tarefas deste tipo, de 1 (mais baixa) a 5 (mais alta).")
    @field:NotNull
    @field:Min(1)
    @field:Max(MAX_PRIORITY)
    val defaultPriority: Int?,
    @field:Schema(description = LINEN_DESCRIPTION, nullable = true)
    @field:Min(1)
    @field:Max(MAX_LINEN_NIGHTS)
    val linenChangeEveryNights: Int? = null,
    @field:Schema(
        description =
            "Modelo de checklist vinculado, ou `null` (sem vínculo). Se informado, precisa ser do contexto " +
                "`HOUSEKEEPING`, ativo e publicado no momento do vínculo; depois, desativar o modelo não o desfaz.",
        nullable = true,
    )
    val checklistTemplateId: UUID? = null,
    @field:Schema(
        description =
            "Créditos por tipo de UH (até 200, cada tipo de UH uma vez só). Obrigatório, para que a edição nunca " +
                "zere os créditos por omissão: sem créditos, envie a lista vazia.",
    )
    @field:Size(max = MAX_CREDITS)
    val credits: List<
        @NotNull @Valid
        CleaningCreditRequest,
    >,
) {
    fun toCommand() =
        CleaningTypeCommand(
            checkNotNull(code),
            checkNotNull(name),
            checkNotNull(requiresInspection),
            checkNotNull(defaultPriority),
            linenChangeEveryNights,
            checklistTemplateId,
            credits.map { CleaningCreditView(RoomTypeId(checkNotNull(it.roomTypeId)), checkNotNull(it.credits)) },
        )
}

@Schema(description = "Os créditos de um tipo de UH.")
data class CleaningCreditRequest(
    @field:NotNull
    val roomTypeId: UUID?,
    @field:Schema(description = "Créditos, de 1 a 100.")
    @field:NotNull
    @field:Min(1)
    @field:Max(MAX_CREDIT)
    val credits: Int?,
)

@Schema(description = "Os créditos de um tipo de UH.")
data class CleaningCreditResponse(
    val roomTypeId: UUID,
    val credits: Int,
)

@Schema(description = "Um tipo de limpeza.")
data class CleaningTypeResponse(
    val id: UUID,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "Chave estável dos tipos da carga inicial, que nunca muda; nula nos criados pela API (trate um valor " +
                "desconhecido sem falhar).",
        nullable = true,
    )
    val key: CleaningTypeKey?,
    val code: String,
    val name: String,
    val requiresInspection: Boolean,
    val defaultPriority: Int,
    @field:Schema(description = LINEN_DESCRIPTION, nullable = true)
    val linenChangeEveryNights: Int?,
    @field:Schema(description = "Modelo de checklist vinculado; nulo sem vínculo.", nullable = true)
    val checklistTemplateId: UUID?,
    @field:Schema(description = "Créditos por tipo de UH, em ordem de tipo de UH.")
    val credits: List<CleaningCreditResponse>,
    @field:Schema(
        description =
            "Tipos de UH ativos sem crédito neste tipo, calculados na leitura (vazio num tipo desativado): o gestor " +
                "configura aos poucos, e o aviso muda quando um tipo de UH é criado depois.",
    )
    val missingCreditRoomTypeIds: List<UUID>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: CleaningTypeView) =
            CleaningTypeResponse(
                view.id.value,
                view.key,
                view.code,
                view.name,
                view.requiresInspection,
                view.defaultPriority,
                view.linenChangeEveryNights,
                view.checklistTemplateId,
                view.credits.map { CleaningCreditResponse(it.roomTypeId.value, it.credits) },
                view.missingCreditRoomTypeIds.map { it.value },
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

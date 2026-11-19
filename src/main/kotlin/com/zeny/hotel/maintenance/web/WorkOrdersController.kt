package com.zeny.hotel.maintenance.web

import com.zeny.hotel.files.FileId
import com.zeny.hotel.maintenance.WorkOrderCategory
import com.zeny.hotel.maintenance.WorkOrderId
import com.zeny.hotel.maintenance.WorkOrderOrigin
import com.zeny.hotel.maintenance.WorkOrderPriority
import com.zeny.hotel.maintenance.WorkOrderStatus
import com.zeny.hotel.maintenance.application.NewWorkOrder
import com.zeny.hotel.maintenance.application.WorkOrderAttachmentView
import com.zeny.hotel.maintenance.application.WorkOrderAttachments
import com.zeny.hotel.maintenance.application.WorkOrderCompletion
import com.zeny.hotel.maintenance.application.WorkOrderFilter
import com.zeny.hotel.maintenance.application.WorkOrderView
import com.zeny.hotel.maintenance.application.WorkOrdersService
import com.zeny.hotel.maintenance.domain.WorkOrderEdit
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.staff.EmployeeId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
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

internal const val MAINTENANCE_TAG = "maintenance"
internal const val MAINTENANCE_TAG_DESCRIPTION = "Manutenção: ordens de serviço corretivas."

// Os limites dos textos (os do domínio, que os confere), só para o contrato.
private const val MAX_DESCRIPTION = 2000
private const val MAX_REASON = 500
private const val MAX_THIRD_PARTY = 200
private const val TEXT_WARNING =
    "**Não escreva dado de hóspede** (nome, documento, contato): a manutenção vê a ordem."
private const val COST_DESCRIPTION = "De 0.00 a 99999.99, com até 2 casas."

// O schema de Money sobrescreve a descrição do campo: a faixa dos custos também vai na descrição das operações.
private const val COST_RANGE = "Cada custo vai de 0.00 a 99999.99, com até 2 casas."

private const val ATTACHMENT_RULES =
    "Não pede `If-Match` e não muda a versão nem o `ETag` da ordem (a versão não cobre a lista de anexos). Ordem " +
        "`DONE` ou `CANCELLED`: 409 `WORK_ORDER_TRANSITION_NOT_ALLOWED`."

private const val READ_RULES =
    "Exige `maintenance.workorders.read`; com o escopo `OWN`, só as ordens que o usuário abriu ou de que o " +
        "colaborador dele é o responsável (a ordem de outro, ou inexistente, é 403 `RESOURCE_OUT_OF_SCOPE`)."
private const val ACTION_RULES =
    "Exige `maintenance.workorders.manage` (403 `PERMISSION_DENIED` antes de qualquer recusa que dependa da " +
        "ordem; com o escopo `OWN`, só nas ordens do usuário, e a de outro, ou inexistente, é 403 " +
        "`RESOURCE_OUT_OF_SCOPE`; o 400 de formato do corpo e o 428 sem `If-Match` vêm antes do 403). Ordem " +
        "inexistente: 404 `WORK_ORDER_NOT_FOUND`. Com o `If-Match` da ordem (412 com outra versão; 428 sem ele). " +
        "Ação fora do estado, ou numa ordem `DONE` ou `CANCELLED`: 409 `WORK_ORDER_TRANSITION_NOT_ALLOWED` (com " +
        "`workOrderStatus` e `action`), sem mudança. Responde a ordem com o `ETag` novo."

/**
 * Ordens de serviço corretivas (RF-MAN-01 e RF-MAN-02, M6 E07): a abertura, a lista com cursor e filtros, a consulta,
 * a edição e as ações como subrecursos, com o `If-Match` da ordem (ARCHITECTURE §7). Não há `DELETE`: a ordem se
 * cancela.
 */
@ApiV1Controller
@RequestMapping("/work-orders")
@Tag(name = MAINTENANCE_TAG, description = MAINTENANCE_TAG_DESCRIPTION)
@Suppress("TooManyFunctions") // uma função por operação da API
class WorkOrdersController(
    private val workOrders: WorkOrdersService,
    private val attachments: WorkOrderAttachments,
) {
    @GetMapping
    @Operation(
        operationId = "listWorkOrders",
        summary = "Listar as ordens de serviço",
        description = "Da mais recente para a mais antiga, com os filtros opcionais. $READ_RULES",
    )
    @Suppress("LongParameterList") // os filtros da listagem
    fun list(
        @Parameter(description = "Só as ordens neste estado.")
        @RequestParam(required = false) status: WorkOrderStatus?,
        @Parameter(description = "Só as ordens desta UH.")
        @RequestParam(required = false) roomId: UUID?,
        @Parameter(description = "Só as ordens desta área comum.")
        @RequestParam(required = false) areaId: UUID?,
        @Parameter(description = "Só as ordens desta prioridade.")
        @RequestParam(required = false) priority: WorkOrderPriority?,
        @Parameter(description = "Só as ordens deste colaborador responsável.")
        @RequestParam(required = false) assigneeEmployeeId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<WorkOrderResponse> =
        workOrders
            .list(
                WorkOrderFilter(
                    status = status,
                    roomId = roomId?.let(::RoomId),
                    areaId = areaId?.let(::AreaId),
                    priority = priority,
                    assigneeEmployeeId = assigneeEmployeeId?.let(::EmployeeId),
                ),
                page,
            ).map(WorkOrderResponse::of)

    @PostMapping
    @Operation(
        operationId = "openWorkOrder",
        summary = "Abrir uma ordem de serviço",
        description =
            "Numa UH (`roomId`) ou numa área comum (`areaId`), exatamente um: os dois ou nenhum, 400 " +
                "`WORK_ORDER_LOCATION_INVALID`; local inexistente, 400 `UNKNOWN_ROOM` ou `UNKNOWN_AREA` no campo; " +
                "local desativado, 422 `WORK_ORDER_LOCATION_INACTIVE`. A ordem nasce `OPEN`, com número " +
                "`OS-<ano>-NNNNNN` (ano da data operacional), quem a abriu em `openedBy` e os três custos em zero. A " +
                "origem (`origin`) aceita `RECEPTION`, `HOUSEKEEPING` e `MAINTENANCE`; `CHECKLIST` é reservada ao " +
                "pedido por item de checklist e dá 400 no campo. Responsável, terceiro e custos só entram pela " +
                "edição (`PUT`), por quem tem `maintenance.workorders.manage`: o corpo da abertura não os tem, e o " +
                "que vier a mais é ignorado. Exige `maintenance.workorders.request` (só `ALL`; 403 " +
                "`PERMISSION_DENIED` antes de qualquer recusa que dependa do local; o 400 do corpo vem antes).",
    )
    @ApiResponse(responseCode = "201", description = "Ordem aberta.")
    fun open(
        @Valid @RequestBody request: WorkOrderOpenRequest,
    ): ResponseEntity<WorkOrderResponse> {
        val view =
            workOrders.open(
                NewWorkOrder(
                    request.roomId?.let(::RoomId),
                    request.areaId?.let(::AreaId),
                    request.category,
                    request.priority,
                    request.description,
                    request.origin,
                    request.dueAt,
                ),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/work-orders/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(WorkOrderResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getWorkOrder",
        summary = "Consultar uma ordem de serviço",
        description = "$READ_RULES Inexistente: 404 `WORK_ORDER_NOT_FOUND` (com `OWN`, 403).",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<WorkOrderResponse> = respond(workOrders.get(WorkOrderId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateWorkOrder",
        summary = "Editar uma ordem de serviço",
        description =
            "Substitui os campos editáveis inteiros: local, categoria, prioridade, descrição, responsável, terceiro, " +
                "prazo e os três custos (obrigatórios, sem valor padrão). Responsável inexistente: 400 " +
                "`UNKNOWN_EMPLOYEE`; desativado: 422 `WORK_ORDER_ASSIGNEE_INACTIVE`. Local inexistente: 400 " +
                "`UNKNOWN_ROOM` ou `UNKNOWN_AREA`; desativado: 422 `WORK_ORDER_LOCATION_INACTIVE` (conferidos só " +
                "quando mudam). $COST_RANGE Custo fora da faixa ou com mais de 2 casas: 400 no campo. Vale " +
                "enquanto a ordem não é final (`DONE` ou `CANCELLED`: 409). $ACTION_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: WorkOrderUpdateRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<WorkOrderResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        val edit =
            WorkOrderEdit(
                request.roomId?.let(::RoomId),
                request.areaId?.let(::AreaId),
                request.category,
                request.priority,
                request.description,
                request.assigneeEmployeeId?.let(::EmployeeId),
                request.thirdPartyName,
                request.dueAt,
                request.laborCost,
                request.thirdPartyCost,
                request.partsCost,
            )
        return respond(workOrders.update(WorkOrderId(id), edit, expected))
    }

    @PostMapping("/{id}/start")
    @Operation(
        operationId = "startWorkOrder",
        summary = "Iniciar uma ordem de serviço",
        description = "`OPEN` vai a `IN_PROGRESS` e grava `startedAt`. $ACTION_RULES",
    )
    fun start(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<WorkOrderResponse> = respond(workOrders.start(WorkOrderId(id), ETags.expectedVersion(ifMatch)))

    @PostMapping("/{id}/hold")
    @Operation(
        operationId = "holdWorkOrder",
        summary = "Pôr uma ordem de serviço em espera",
        description =
            "`IN_PROGRESS` vai a `ON_HOLD` (aguardando peça ou terceiro), com o motivo obrigatório. $ACTION_RULES",
    )
    fun hold(
        @PathVariable id: UUID,
        @Valid @RequestBody request: WorkOrderHoldRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<WorkOrderResponse> =
        respond(workOrders.hold(WorkOrderId(id), request.reason, ETags.expectedVersion(ifMatch)))

    @PostMapping("/{id}/resume")
    @Operation(
        operationId = "resumeWorkOrder",
        summary = "Retomar uma ordem de serviço em espera",
        description = "`ON_HOLD` volta a `IN_PROGRESS`; o motivo da espera sai. $ACTION_RULES",
    )
    fun resume(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<WorkOrderResponse> = respond(workOrders.resume(WorkOrderId(id), ETags.expectedVersion(ifMatch)))

    @PostMapping("/{id}/complete")
    @Operation(
        operationId = "completeWorkOrder",
        summary = "Concluir uma ordem de serviço",
        description =
            "`OPEN`, `IN_PROGRESS` ou `ON_HOLD` vai a `DONE` e grava `closedAt`, com as notas e os custos que vierem " +
                "(os omitidos mantêm o valor gravado). $COST_RANGE $ACTION_RULES",
    )
    fun complete(
        @PathVariable id: UUID,
        @Valid @RequestBody request: WorkOrderCompleteRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<WorkOrderResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        val completion =
            WorkOrderCompletion(request.notes, request.laborCost, request.thirdPartyCost, request.partsCost)
        return respond(workOrders.complete(WorkOrderId(id), completion, expected))
    }

    @PostMapping("/{id}/cancel")
    @Operation(
        operationId = "cancelWorkOrder",
        summary = "Cancelar uma ordem de serviço",
        description =
            "`OPEN`, `IN_PROGRESS` ou `ON_HOLD` vai a `CANCELLED` e grava `closedAt`, com o motivo obrigatório. " +
                ACTION_RULES,
    )
    fun cancel(
        @PathVariable id: UUID,
        @Valid @RequestBody request: WorkOrderCancelRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<WorkOrderResponse> =
        respond(workOrders.cancel(WorkOrderId(id), request.reason, ETags.expectedVersion(ifMatch)))

    @PostMapping("/{id}/attachments")
    @Operation(
        operationId = "attachWorkOrderFile",
        summary = "Vincular um anexo à ordem de serviço",
        description =
            "O anexo já enviado por `POST /api/v1/files` com `maintenance.work-order-photo` (JPEG ou PNG, sem " +
                "EXIF/GPS) ou `maintenance.work-order-document` (PDF), até 10 MB. Responde a ordem com a lista de " +
                "`attachments` vigentes; o download de cada um é pela URL assinada " +
                "(`GET /api/v1/files/{id}/download-url`, com `maintenance.workorders.read`). $ATTACHMENT_RULES Com " +
                "`maintenance.workorders.manage` (no escopo dele) ou, sem ela, com `maintenance.workorders.request` " +
                "na ordem que o próprio usuário abriu; a ordem alheia é 403 `PERMISSION_DENIED`. Anexo inexistente: " +
                "400 `UNKNOWN_FILE` no campo `fileId`; de outra finalidade: 422 `WORK_ORDER_ATTACHMENT_INVALID`; já " +
                "vinculado a outro registro: 409 `FILE_ALREADY_ATTACHED`. Já vinculado a esta ordem: 200, sem mudar " +
                "nada.",
    )
    fun attach(
        @PathVariable id: UUID,
        @Valid @RequestBody request: WorkOrderAttachRequest,
    ): ResponseEntity<WorkOrderResponse> =
        respond(attachments.attach(WorkOrderId(id), FileId(checkNotNull(request.fileId))))

    @PostMapping("/{id}/attachments/{fileId}/detach")
    @Operation(
        operationId = "detachWorkOrderFile",
        summary = "Desvincular um anexo da ordem de serviço",
        description =
            "O anexo sai da lista da ordem e volta a órfão no `files` (o expurgo o apaga depois da retenção). " +
                "Responde a ordem. $ATTACHMENT_RULES Exige `maintenance.workorders.manage` (403 `PERMISSION_DENIED` " +
                "antes de qualquer recusa que dependa da ordem; com `OWN`, só nas ordens do usuário). Ordem " +
                "inexistente: 404 `WORK_ORDER_NOT_FOUND`; anexo que não está vinculado a esta ordem: 404 " +
                "`WORK_ORDER_ATTACHMENT_NOT_FOUND`.",
    )
    fun detach(
        @PathVariable id: UUID,
        @PathVariable fileId: UUID,
    ): ResponseEntity<WorkOrderResponse> = respond(attachments.detach(WorkOrderId(id), FileId(fileId)))

    private fun respond(view: WorkOrderView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(WorkOrderResponse.of(view))
}

@Schema(description = "A abertura de uma ordem de serviço, numa UH ou numa área comum (exatamente um local).")
data class WorkOrderOpenRequest(
    @field:Schema(description = "A UH da ordem; informe esta ou `areaId`, uma só.", nullable = true)
    val roomId: UUID? = null,
    @field:Schema(description = "A área comum da ordem; informe esta ou `roomId`, uma só.", nullable = true)
    val areaId: UUID? = null,
    @field:Schema(description = "A categoria do problema.")
    val category: WorkOrderCategory,
    @field:Schema(description = "A prioridade da ordem.")
    val priority: WorkOrderPriority,
    // Os limites são do domínio (em code points, depois de aparar); aqui só os documentam.
    @field:NotBlank
    @field:Schema(description = "O problema, até 2000 caracteres. $TEXT_WARNING", maxLength = MAX_DESCRIPTION)
    val description: String?,
    @field:Schema(description = "Quem pede: `RECEPTION`, `HOUSEKEEPING` ou `MAINTENANCE` (`CHECKLIST` é recusada).")
    val origin: WorkOrderOrigin,
    @field:Schema(description = "O prazo desejado; omita se não houver.", nullable = true)
    val dueAt: Instant? = null,
)

@Schema(description = "A edição de uma ordem de serviço: substitui todos os campos editáveis.")
data class WorkOrderUpdateRequest(
    @field:Schema(description = "A UH da ordem; informe esta ou `areaId`, uma só.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum da ordem; informe esta ou `roomId`, uma só.", nullable = true)
    val areaId: UUID?,
    @field:Schema(description = "A categoria do problema.")
    val category: WorkOrderCategory,
    @field:Schema(description = "A prioridade da ordem.")
    val priority: WorkOrderPriority,
    @field:NotBlank
    @field:Schema(description = "O problema, até 2000 caracteres. $TEXT_WARNING", maxLength = MAX_DESCRIPTION)
    val description: String?,
    @field:Schema(description = "O colaborador responsável; nulo ou omitido retira.", nullable = true)
    val assigneeEmployeeId: UUID?,
    @field:Schema(
        description = "O terceiro contratado, até 200 caracteres; nulo ou omitido retira.",
        nullable = true,
        maxLength = MAX_THIRD_PARTY,
    )
    val thirdPartyName: String?,
    @field:Schema(description = "O prazo; nulo ou omitido retira.", nullable = true)
    val dueAt: Instant?,
    @field:Schema(description = "O custo de mão de obra. $COST_DESCRIPTION", example = "0.00")
    val laborCost: Money,
    @field:Schema(description = "O custo de terceiros. $COST_DESCRIPTION", example = "0.00")
    val thirdPartyCost: Money,
    @field:Schema(description = "O custo das peças informadas. $COST_DESCRIPTION", example = "0.00")
    val partsCost: Money,
)

@Schema(description = "O vínculo de um anexo (já enviado ao `files`) com a ordem de serviço.")
data class WorkOrderAttachRequest(
    @field:Schema(
        description = "O id do anexo, de `maintenance.work-order-photo` ou `maintenance.work-order-document`.",
    )
    @field:NotNull
    val fileId: UUID?,
)

@Schema(description = "O anexo vigente de uma ordem de serviço (foto ou PDF).")
data class WorkOrderAttachmentResponse(
    @field:Schema(description = "O id do anexo no `files` (para `GET /api/v1/files/{id}/download-url`).")
    val id: UUID,
    @field:Schema(
        description = "A finalidade do anexo: `maintenance.work-order-photo` ou `maintenance.work-order-document`.",
    )
    val purpose: String,
) {
    companion object {
        fun of(view: WorkOrderAttachmentView) = WorkOrderAttachmentResponse(view.fileId.value, view.purpose.value)
    }
}

@Schema(description = "A espera de uma ordem de serviço.")
data class WorkOrderHoldRequest(
    @field:NotBlank
    @field:Schema(description = "O motivo (peça, terceiro…), até 500 caracteres. $TEXT_WARNING", maxLength = MAX_REASON)
    val reason: String?,
)

@Schema(description = "A conclusão de uma ordem de serviço.")
data class WorkOrderCompleteRequest(
    @field:Schema(
        description = "As notas da conclusão, até 2000 caracteres. $TEXT_WARNING",
        nullable = true,
        maxLength = MAX_DESCRIPTION,
    )
    val notes: String? = null,
    @field:Schema(description = "O custo de mão de obra; omita para manter. $COST_DESCRIPTION", nullable = true)
    val laborCost: Money? = null,
    @field:Schema(description = "O custo de terceiros; omita para manter. $COST_DESCRIPTION", nullable = true)
    val thirdPartyCost: Money? = null,
    @field:Schema(description = "O custo das peças; omita para manter. $COST_DESCRIPTION", nullable = true)
    val partsCost: Money? = null,
)

@Schema(description = "O cancelamento de uma ordem de serviço.")
data class WorkOrderCancelRequest(
    @field:NotBlank
    @field:Schema(description = "O motivo, até 500 caracteres. $TEXT_WARNING", maxLength = MAX_REASON)
    val reason: String?,
)

@Schema(description = "Uma ordem de serviço corretiva. A descrição e os motivos são texto livre do operador.")
data class WorkOrderResponse(
    val id: UUID,
    @field:Schema(description = "O número legível, `OS-<ano>-NNNNNN`.", example = "OS-2026-000001")
    val number: String,
    @field:Schema(description = "A UH da ordem; nula numa ordem de área.", nullable = true)
    val roomId: UUID?,
    @field:Schema(description = "A área comum da ordem; nula numa ordem de UH.", nullable = true)
    val areaId: UUID?,
    @field:ExtensibleEnum
    @field:Schema(description = "A categoria (trate um valor desconhecido sem falhar).")
    val category: WorkOrderCategory,
    @field:ExtensibleEnum
    @field:Schema(description = "A prioridade (trate um valor desconhecido sem falhar).")
    val priority: WorkOrderPriority,
    val description: String,
    @field:ExtensibleEnum
    @field:Schema(description = "A origem do pedido (trate um valor desconhecido sem falhar).")
    val origin: WorkOrderOrigin,
    @field:Schema(description = "O colaborador responsável; nulo sem responsável.", nullable = true)
    val assigneeEmployeeId: UUID?,
    @field:Schema(description = "O terceiro contratado; nulo sem terceiro.", nullable = true)
    val thirdPartyName: String?,
    @field:ExtensibleEnum
    @field:Schema(description = "O estado da ordem (trate um valor desconhecido sem falhar).")
    val status: WorkOrderStatus,
    @field:Schema(description = "O motivo da espera; nulo fora de `ON_HOLD`.", nullable = true)
    val holdReason: String?,
    val laborCost: Money,
    val thirdPartyCost: Money,
    val partsCost: Money,
    @field:Schema(description = "O usuário que abriu a ordem (dono no escopo `OWN`).")
    val openedBy: UUID,
    val openedAt: Instant,
    @field:Schema(description = "O prazo; nulo sem prazo.", nullable = true)
    val dueAt: Instant?,
    @field:Schema(description = "Quando foi iniciada pela primeira vez; nulo antes.", nullable = true)
    val startedAt: Instant?,
    @field:Schema(description = "Quando foi concluída ou cancelada; nulo antes.", nullable = true)
    val closedAt: Instant?,
    @field:Schema(description = "As notas da conclusão; nulo fora de `DONE` ou sem notas.", nullable = true)
    val completionNotes: String?,
    @field:Schema(description = "O motivo do cancelamento; nulo fora de `CANCELLED`.", nullable = true)
    val cancellationReason: String?,
    @field:Schema(description = "O período OOO/OOS aberto para a ordem; nulo sem bloqueio.", nullable = true)
    val outOfOrderId: UUID?,
    val updatedAt: Instant,
    @field:Schema(
        description =
            "Os anexos vigentes, em ordem de vínculo. A versão e o `ETag` da ordem não cobrem esta lista (vincular e " +
                "desvincular não os mudam).",
    )
    val attachments: List<WorkOrderAttachmentResponse>,
) {
    companion object {
        fun of(view: WorkOrderView) =
            WorkOrderResponse(
                view.id.value,
                view.number,
                view.roomId?.value,
                view.areaId?.value,
                view.category,
                view.priority,
                view.description,
                view.origin,
                view.assigneeEmployeeId?.value,
                view.thirdPartyName,
                view.status,
                view.holdReason,
                view.laborCost,
                view.thirdPartyCost,
                view.partsCost,
                view.openedBy,
                view.openedAt,
                view.dueAt,
                view.startedAt,
                view.closedAt,
                view.completionNotes,
                view.cancellationReason,
                view.outOfOrderId?.value,
                view.updatedAt,
                view.attachments.map(WorkOrderAttachmentResponse::of),
            )
    }
}

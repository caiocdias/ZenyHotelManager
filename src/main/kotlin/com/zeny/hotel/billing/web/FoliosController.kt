package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.application.FolioFilter
import com.zeny.hotel.billing.application.FolioService
import com.zeny.hotel.billing.application.FolioView
import com.zeny.hotel.billing.application.PostingService
import com.zeny.hotel.billing.application.PostingTransferService
import com.zeny.hotel.billing.application.PostingView
import com.zeny.hotel.billing.application.TransferView
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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

private const val CORRECTION_RULES =
    "Exige antes `billing.folios.read` (sem ela, 403 `PERMISSION_DENIED`, antes de ler a conta). Ação " +
        "**sensível** com o limite `maxAmount` sobre o total das correções do lançamento (as já feitas mais esta, " +
        "em valor absoluto): sem a permissão, ou acima do limite, 403 `APPROVAL_REQUIRED` (com `action`, " +
        "`permission`, `resourceRef` e `limit`); repita com o " +
        "`X-Approval-Token` de `POST /api/v1/auth/approvals` e **outra** `Idempotency-Key`. Só um lançamento normal " +
        "é corrigido (422 `POSTING_NOT_CORRECTABLE`); lançamento já estornado: 409 `POSTING_ALREADY_REVERSED`; " +
        "conta encerrada: 409 `FOLIO_CLOSED`. Essas regras são conferidas antes da aprovação de superior."

private const val TRANSFER_RULES =
    "Só um lançamento normal ou a entrada de uma transferência é transferido (422 `POSTING_NOT_TRANSFERABLE`); já " +
        "estornado: 409 `POSTING_ALREADY_REVERSED`; nada restando: 422 `POSTING_NOTHING_TO_TRANSFER`; conta " +
        "encerrada: 409 `FOLIO_CLOSED`; conta de destino inexistente: 400 `UNKNOWN_FOLIO`."

/**
 * Contas e lançamentos (RF-CTA-01 a 03 e 05). Consultar exige `billing.folios.read`; abrir e trocar o pagador e o
 * limite, `billing.folios.manage`; lançar, `billing.postings.create`; estornar e ajustar são sensíveis
 * (`billing.postings.reverse`/`adjust`, com limite); transferir e dividir, `billing.postings.transfer`. Os POSTs que
 * criam conta ou lançamento exigem `Idempotency-Key`. Lançamentos nunca mudam nem são apagados (RN-03).
 */
@ApiV1Controller
@RequestMapping("/folios")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
@Suppress("TooManyFunctions") // contas, lançamentos, correções, transferência e divisão: um recurso só
class FoliosController(
    private val folios: FolioService,
    private val postings: PostingService,
    private val transfers: PostingTransferService,
) {
    @GetMapping
    @Operation(
        operationId = "listFolios",
        summary = "Listar contas",
        description = "Da mais nova para a mais antiga, com o saldo. Exige `billing.folios.read`.",
    )
    fun list(
        @RequestParam(required = false) ownerType: FolioOwnerType?,
        @Parameter(description = "Id do dono (com ou sem `ownerType`).")
        @RequestParam(required = false) ownerId: UUID?,
        @RequestParam(required = false) status: FolioStatus?,
        @RequestParam(required = false) payerCompanyId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<FolioResponse> =
        folios
            .list(FolioFilter(ownerType, ownerId, status, payerCompanyId?.let(::CompanyId)), page)
            .map(FolioResponse::of)

    @PostMapping
    @Idempotent
    @Operation(
        operationId = "openFolio",
        summary = "Abrir uma conta",
        description =
            "Na data operacional, com número `C-<ano>-<sequência>`. Exige `billing.folios.manage`. Dono sem id (fora " +
                "da avulsa): 400 `FOLIO_OWNER_INVALID`; empresa (dona ou pagadora) inexistente: 400 " +
                "`UNKNOWN_COMPANY`; desativada: 422 `COMPANY_INACTIVE`; limite negativo: 400 `BILLING_DATA_INVALID`.",
    )
    @ApiResponse(responseCode = "201", description = "Conta aberta.")
    fun open(
        @Valid @RequestBody request: FolioOpenRequest,
    ): ResponseEntity<FolioResponse> {
        val view = folios.open(request.ownerInput(), request.payerCompanyId?.let(::CompanyId), request.creditLimit)
        return ResponseEntity
            .created(URI.create("/api/v1/folios/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(FolioResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getFolio",
        summary = "Consultar uma conta",
        description =
            "Com o saldo; o `ETag` muda a cada lançamento. Exige `billing.folios.read`. Inexistente: 404 " +
                "`FOLIO_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<FolioResponse> = ok(folios.get(FolioId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateFolio",
        summary = "Trocar o pagador e o limite de crédito",
        description =
            "O dono não muda. Exige `billing.folios.manage` e o `If-Match` (ausente: 428; versão diferente: 412). " +
                "Pagador novo inexistente: 400 `UNKNOWN_COMPANY`; desativado: 422 `COMPANY_INACTIVE`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: FolioUpdateRequest,
    ): ResponseEntity<FolioResponse> =
        ok(
            folios.update(
                FolioId(id),
                ETags.expectedVersion(ifMatch),
                request.payerCompanyId?.let(::CompanyId),
                request.creditLimit,
            ),
        )

    @GetMapping("/{id}/postings")
    @Operation(
        operationId = "listPostings",
        summary = "Listar os lançamentos de uma conta",
        description =
            "Em ordem de lançamento, com o estorno e o que ainda resta de cada um. Exige `billing.folios.read`.",
    )
    fun listPostings(
        @PathVariable id: UUID,
        @Valid page: PageParams,
    ): CursorPage<PostingResponse> = postings.list(FolioId(id), page).map(PostingResponse::of)

    @GetMapping("/{id}/postings/{postingId}")
    @Operation(
        operationId = "getPosting",
        summary = "Consultar um lançamento",
        description = "Exige `billing.folios.read`. Inexistente nesta conta: 404 `POSTING_NOT_FOUND`.",
    )
    fun getPosting(
        @PathVariable id: UUID,
        @PathVariable postingId: UUID,
    ): PostingResponse = PostingResponse.of(postings.get(FolioId(id), PostingId(postingId)))

    @PostMapping("/{id}/postings")
    @Idempotent
    @Operation(
        operationId = "createPosting",
        summary = "Lançar numa conta",
        description =
            "Lançamento manual na data operacional do hotel (RN-01): valor = quantidade × valor unitário, " +
                "arredondado pela regra de `Money`, negativo num código `CREDIT`. Exige `billing.postings.create`. " +
                "Conta encerrada: 409 `FOLIO_CLOSED`; código inexistente: 400 `UNKNOWN_TRANSACTION_CODE`; " +
                "desativado: 422 `TRANSACTION_CODE_INACTIVE`; quantidade ou valor fora da faixa: 400 " +
                "`POSTING_INVALID`. Roteamento (RF-CTA-04): uma regra vigente na data operacional desta conta " +
                "(por código, que prevalece, ou por grupo de receita) grava o lançamento na conta de destino, " +
                "seguindo a cadeia, com `routedFromFolioId` = esta conta e `routingRuleId`; a resposta e o " +
                "`Location` são os da conta final; destino encerrado: 409 `ROUTING_TARGET_CLOSED`.",
    )
    @ApiResponse(responseCode = "201", description = "Lançado.")
    fun post(
        @PathVariable id: UUID,
        @Valid @RequestBody request: PostingRequest,
    ): ResponseEntity<PostingResponse> =
        created(
            postings.post(
                FolioId(id),
                TransactionCodeId(checkNotNull(request.transactionCodeId)),
                request.quantity,
                checkNotNull(request.unitPrice),
                request.description,
            ),
        )

    @PostMapping("/{id}/postings/{postingId}/reverse")
    @Idempotent
    @Operation(
        operationId = "reversePosting",
        summary = "Estornar um lançamento",
        description =
            "Cria o lançamento inverso de tudo o que ainda resta do original (ele menos os ajustes), vinculado e com " +
                "motivo; um estorno por lançamento. Os ajustes já zerando o original: 422 " +
                "`POSTING_NOTHING_TO_REVERSE`. Exige `billing.postings.reverse`. $CORRECTION_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Estornado.")
    fun reverse(
        @PathVariable id: UUID,
        @PathVariable postingId: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: ReversalRequest,
    ): ResponseEntity<PostingResponse> =
        created(postings.reverse(FolioId(id), PostingId(postingId), checkNotNull(request.reason), approvalToken))

    @PostMapping("/{id}/postings/{postingId}/adjust")
    @Idempotent
    @Operation(
        operationId = "adjustPosting",
        summary = "Ajustar um lançamento",
        description =
            "Cria um lançamento vinculado no mesmo código, com o valor informado e o sinal oposto ao do original, e " +
                "motivo. Parcial: até o que ainda resta (acima: 422 `ADJUSTMENT_EXCEEDS_REMAINING`). Exige " +
                "`billing.postings.adjust`. $CORRECTION_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Ajustado.")
    fun adjust(
        @PathVariable id: UUID,
        @PathVariable postingId: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: AdjustmentRequest,
    ): ResponseEntity<PostingResponse> =
        created(
            postings.adjust(
                FolioId(id),
                PostingId(postingId),
                checkNotNull(request.amount),
                checkNotNull(request.reason),
                approvalToken,
            ),
        )

    @PostMapping("/{id}/postings/transfer")
    @Idempotent
    @Operation(
        operationId = "transferPostings",
        summary = "Transferir lançamentos para outra conta",
        description =
            "Transfere tudo o que ainda resta de cada lançamento para a conta de destino (RF-CTA-05), sem mudar o " +
                "lançamento: cada um ganha uma saída nesta conta (`TRANSFER_OUT`, o inverso) e uma entrada no " +
                "destino (`TRANSFER_IN`), vinculadas, na data operacional. Vai exatamente para o destino (o " +
                "roteamento não se aplica). Exige `billing.postings.transfer`. $TRANSFER_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Transferidos.")
    fun transfer(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TransferRequest,
    ): ResponseEntity<TransferResponse> =
        moved(
            transfers.transfer(
                FolioId(id),
                FolioId(checkNotNull(request.targetFolioId)),
                checkNotNull(request.postingIds).map(::PostingId),
                request.reason,
            ),
        )

    @PostMapping("/{id}/postings/{postingId}/split")
    @Idempotent
    @Operation(
        operationId = "splitPosting",
        summary = "Dividir um lançamento entre contas",
        description =
            "Reparte o que ainda resta do lançamento entre 2 e 10 contas (esta mesma = a parte que fica), por " +
                "percentual (`PERCENT`, somando exatamente 100, repartido sem perder centavos) ou por valor " +
                "(`AMOUNT`, somando exatamente o que resta, senão 422 `SPLIT_TOTAL_MISMATCH`); cada parte movida é " +
                "um par saída/entrada como na transferência. Partes fora do formato: 400 `SPLIT_INVALID`; conta de " +
                "uma parte inexistente: 400 `UNKNOWN_FOLIO`. Exige `billing.postings.transfer`. $TRANSFER_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Dividido.")
    fun split(
        @PathVariable id: UUID,
        @PathVariable postingId: UUID,
        @Valid @RequestBody request: SplitRequest,
    ): ResponseEntity<TransferResponse> =
        moved(
            transfers.split(
                FolioId(id),
                PostingId(postingId),
                checkNotNull(request.by),
                checkNotNull(request.parts).map(SplitPartDto::toCommand),
                request.reason,
            ),
        )

    private fun moved(views: List<TransferView>) =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(TransferResponse(views.map(TransferredPostingResponse::of)))

    private fun ok(view: FolioView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(FolioResponse.of(view))

    private fun created(view: PostingView) =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .location(URI.create("/api/v1/folios/${view.folioId}/postings/${view.id}"))
            .body(PostingResponse.of(view))
}

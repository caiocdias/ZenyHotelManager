package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.application.TransactionCodeFilter
import com.zeny.hotel.billing.application.TransactionCodeService
import com.zeny.hotel.billing.application.TransactionCodeView
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

internal const val BILLING_TAG = "billing"
internal const val BILLING_TAG_DESCRIPTION = "Códigos de lançamento, contas e lançamentos imutáveis."

private const val CODE_RULES =
    "Código curto repetido (inclusive de um desativado): 409 `TRANSACTION_CODE_ALREADY_IN_USE`; taxa de serviço " +
        "fora de um código `CHARGE`, taxa própria sem a taxa aplicável ou fora de 0 a 100: 400 " +
        "`TRANSACTION_CODE_INVALID`; texto ou código fora do formato: 400 `BILLING_DATA_INVALID`."

/**
 * Códigos de lançamento (RF-CTA-02): consultar exige `billing.codes.read`; escrever, `billing.codes.manage`. Os oito
 * pré-carregados têm `key`; nada é apagado, só desativado.
 */
@ApiV1Controller
@RequestMapping("/transaction-codes")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class TransactionCodesController(
    private val codes: TransactionCodeService,
) {
    @GetMapping
    @Operation(
        operationId = "listTransactionCodes",
        summary = "Listar os códigos de lançamento",
        description = "Em ordem de código. Exige `billing.codes.read`.",
    )
    fun list(
        @Parameter(description = "Só os ativos (`true`) ou só os desativados (`false`); omita para todos.")
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) revenueGroup: RevenueGroup?,
        @Valid page: PageParams,
    ): CursorPage<TransactionCodeResponse> =
        codes.list(TransactionCodeFilter(active, revenueGroup), page).map(TransactionCodeResponse::of)

    @PostMapping
    @Operation(
        operationId = "createTransactionCode",
        summary = "Cadastrar um código de lançamento",
        description = "Exige `billing.codes.manage`. O tipo e o grupo de receita não mudam depois. $CODE_RULES",
    )
    @ApiResponse(responseCode = "201", description = "Código cadastrado.")
    fun create(
        @Valid @RequestBody request: TransactionCodeCreateRequest,
    ): ResponseEntity<TransactionCodeResponse> {
        val view = codes.create(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/transaction-codes/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(TransactionCodeResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getTransactionCode",
        summary = "Consultar um código de lançamento",
        description = "Exige `billing.codes.read`. Inexistente: 404 `TRANSACTION_CODE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<TransactionCodeResponse> = ok(codes.get(TransactionCodeId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateTransactionCode",
        summary = "Editar um código de lançamento",
        description =
            "Troca código, nome, taxa de serviço e classificação fiscal (o tipo e o grupo de receita são fixos). " +
                "Exige `billing.codes.manage` e o `If-Match` (ausente: 428; versão diferente: 412). $CODE_RULES",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: TransactionCodeUpdateRequest,
    ): ResponseEntity<TransactionCodeResponse> =
        ok(codes.update(TransactionCodeId(id), ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateTransactionCode",
        summary = "Desativar um código de lançamento",
        description =
            "Exige `billing.codes.manage`. Os lançamentos feitos ficam; um lançamento novo no código é recusado " +
                "(422 `TRANSACTION_CODE_INACTIVE`).",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<TransactionCodeResponse> = ok(codes.deactivate(TransactionCodeId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateTransactionCode",
        summary = "Reativar um código de lançamento",
        description = "Exige `billing.codes.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<TransactionCodeResponse> = ok(codes.reactivate(TransactionCodeId(id)))

    private fun ok(view: TransactionCodeView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(TransactionCodeResponse.of(view))
}

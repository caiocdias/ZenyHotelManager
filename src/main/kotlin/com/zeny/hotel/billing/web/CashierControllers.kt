package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.CashPointId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftStatus
import com.zeny.hotel.billing.application.CashPointService
import com.zeny.hotel.billing.application.CashPointView
import com.zeny.hotel.billing.application.CashierShiftFilter
import com.zeny.hotel.billing.application.CashierShiftService
import com.zeny.hotel.billing.application.CashierShiftView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
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

/**
 * Pontos de caixa (RF-CTA-09, M3 E07): consultar exige `billing.shifts.read` (qualquer escopo) ou
 * `billing.cashpoints.manage`; escrever, `billing.cashpoints.manage`. Nada é apagado, só desativado.
 */
@ApiV1Controller
@RequestMapping("/cash-points")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class CashPointsController(
    private val cashPoints: CashPointService,
) {
    @GetMapping
    @Operation(
        operationId = "listCashPoints",
        summary = "Listar os pontos de caixa",
        description = "Em ordem de nome. Exige `billing.shifts.read` (qualquer escopo) ou `billing.cashpoints.manage`.",
    )
    fun list(
        @Parameter(description = "Só os ativos (`true`) ou só os desativados (`false`); omita para todos.")
        @RequestParam(required = false) active: Boolean?,
        @Valid page: PageParams,
    ): CursorPage<CashPointResponse> = cashPoints.list(active, page).map(CashPointResponse::of)

    @PostMapping
    @Operation(
        operationId = "createCashPoint",
        summary = "Cadastrar um ponto de caixa",
        description =
            "Exige `billing.cashpoints.manage`. Nome repetido (sem diferenciar maiúsculas, inclusive de um " +
                "desativado): 409 `CASH_POINT_NAME_ALREADY_IN_USE`.",
    )
    @ApiResponse(responseCode = "201", description = "Ponto de caixa cadastrado.")
    fun create(
        @Valid @RequestBody request: CashPointRequest,
    ): ResponseEntity<CashPointResponse> {
        val view = cashPoints.create(checkNotNull(request.name), request.blindClose)
        return ResponseEntity
            .created(URI.create("/api/v1/cash-points/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(CashPointResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getCashPoint",
        summary = "Consultar um ponto de caixa",
        description =
            "Exige `billing.shifts.read` (qualquer escopo) ou `billing.cashpoints.manage`. Inexistente: 404 " +
                "`CASH_POINT_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<CashPointResponse> = ok(cashPoints.get(CashPointId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateCashPoint",
        summary = "Editar um ponto de caixa",
        description =
            "Troca o nome e o fechamento cego (vale para os turnos abertos depois). Exige " +
                "`billing.cashpoints.manage` e o `If-Match` (ausente: 428; versão diferente: 412). Nome " +
                "repetido: 409 `CASH_POINT_NAME_ALREADY_IN_USE`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: CashPointRequest,
    ): ResponseEntity<CashPointResponse> =
        ok(
            cashPoints.update(
                CashPointId(id),
                ETags.expectedVersion(ifMatch),
                checkNotNull(request.name),
                request.blindClose,
            ),
        )

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateCashPoint",
        summary = "Desativar um ponto de caixa",
        description =
            "Exige `billing.cashpoints.manage`. Os turnos abertos seguem até o fechamento; abrir um turno novo no " +
                "ponto é recusado (422 `CASH_POINT_INACTIVE`).",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CashPointResponse> = ok(cashPoints.deactivate(CashPointId(id)))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateCashPoint",
        summary = "Reativar um ponto de caixa",
        description = "Exige `billing.cashpoints.manage`.",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<CashPointResponse> = ok(cashPoints.reactivate(CashPointId(id)))

    private fun ok(view: CashPointView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(CashPointResponse.of(view))
}

private const val BLIND_RULE =
    "Fechamento cego: num turno cego aberto, `expected` sai nulo (e `expectedVisible` falso) a quem não tem " +
        "`billing.shifts.review`; depois do fechamento, sai a todos que leem o turno."

/**
 * Turnos de caixa (RF-CTA-09 e 10, H13 e H14 do M3, M3 E07). Operar o próprio turno exige `billing.cashier.operate`
 * (no máximo um aberto por usuário, em qualquer ponto); consultar, `billing.shifts.read` (com `OWN`, só os próprios).
 * Os POSTs que abrem, movimentam e fecham exigem `Idempotency-Key`. Movimentos e contagens nunca mudam (RN-03).
 */
@ApiV1Controller
@RequestMapping("/cashier-shifts")
@Tag(name = BILLING_TAG, description = BILLING_TAG_DESCRIPTION)
class CashierShiftsController(
    private val shifts: CashierShiftService,
) {
    @GetMapping
    @Operation(
        operationId = "listCashierShifts",
        summary = "Listar os turnos de caixa",
        description =
            "Do mais novo para o mais antigo. Exige `billing.shifts.read`; com `OWN`, só os turnos do próprio " +
                "usuário. Sem o esperado (veja o turno).",
    )
    fun list(
        @RequestParam(required = false) status: CashierShiftStatus?,
        @RequestParam(required = false) cashPointId: UUID?,
        @RequestParam(required = false) operatorId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<CashierShiftListItem> =
        shifts
            .list(
                CashierShiftFilter(status, cashPointId?.let(::CashPointId), operatorId?.let(::UserId)),
                page,
            ).map(CashierShiftListItem::of)

    @PostMapping
    @Idempotent
    @Operation(
        operationId = "openCashierShift",
        summary = "Abrir o próprio turno de caixa",
        description =
            "Com o fundo de troco, na data operacional, com número `CX-<ano>-<sequência>`. Exige " +
                "`billing.cashier.operate`. O usuário já tem um turno aberto (em qualquer ponto): 409 " +
                "`CASHIER_SHIFT_ALREADY_OPEN`; ponto inexistente: 400 `UNKNOWN_CASH_POINT`; desativado: 422 " +
                "`CASH_POINT_INACTIVE`; fundo fora de 0,00 a 1.000.000,00: 400 `CASHIER_SHIFT_INVALID`. $BLIND_RULE",
    )
    @ApiResponse(responseCode = "201", description = "Turno aberto.")
    fun open(
        @Valid @RequestBody request: CashierShiftOpenRequest,
    ): ResponseEntity<CashierShiftResponse> {
        val view = shifts.open(CashPointId(checkNotNull(request.cashPointId)), checkNotNull(request.openingFloat))
        return ResponseEntity
            .created(URI.create("/api/v1/cashier-shifts/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(CashierShiftResponse.of(view))
    }

    @GetMapping("/current")
    @Operation(
        operationId = "getCurrentCashierShift",
        summary = "Consultar o próprio turno aberto",
        description =
            "Exige `billing.cashier.operate`. Sem turno aberto: 404 `CASHIER_SHIFT_NOT_FOUND`. $BLIND_RULE",
    )
    fun current(): ResponseEntity<CashierShiftResponse> = ok(shifts.current())

    @GetMapping("/{id}")
    @Operation(
        operationId = "getCashierShift",
        summary = "Consultar um turno de caixa",
        description =
            "Com os movimentos; o `ETag` muda a cada movimento. Exige `billing.shifts.read`; com `OWN`, o turno de " +
                "outro usuário responde 404 `CASHIER_SHIFT_NOT_FOUND`. $BLIND_RULE",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<CashierShiftResponse> = ok(shifts.get(CashierShiftId(id)))

    @PostMapping("/{id}/movements")
    @Idempotent
    @Operation(
        operationId = "recordCashMovement",
        summary = "Registrar uma sangria ou um suprimento",
        description =
            "Em dinheiro, com motivo; imutável. Exige `billing.cashier.operate`, e só o operador do turno registra " +
                "(403 `CASHIER_SHIFT_OPERATOR_REQUIRED`). Turno fechado: 409 `CASHIER_SHIFT_CLOSED`; valor fora de " +
                "0,01 a 1.000.000,00 ou motivo vazio: 400 `CASHIER_SHIFT_INVALID`. A sangria não é conferida contra " +
                "o dinheiro esperado (o fechamento cego não o revela); a falta aparece como diferença.",
    )
    @ApiResponse(responseCode = "201", description = "Movimento registrado.")
    fun recordMovement(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CashMovementRequest,
    ): ResponseEntity<CashMovementResponse> {
        val movement =
            shifts.recordMovement(
                CashierShiftId(id),
                checkNotNull(request.type),
                checkNotNull(request.amount),
                request.reason,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(CashMovementResponse.of(movement))
    }

    @PostMapping("/{id}/close")
    @Idempotent
    @Operation(
        operationId = "closeCashierShift",
        summary = "Fechar o próprio turno de caixa",
        description =
            "Com a contagem declarada por forma de pagamento (a omitida vale zero); devolve o relatório de " +
                "fechamento, com o esperado e a diferença por forma (declarado − esperado). Exige " +
                "`billing.cashier.operate`, e só o operador fecha (403 `CASHIER_SHIFT_OPERATOR_REQUIRED`). Já " +
                "fechado: 409 `CASHIER_SHIFT_CLOSED`; forma repetida, dinheiro fora de 0,00 a 1.000.000,00 ou " +
                "outra forma fora de -1.000.000,00 a 1.000.000,00 (o líquido do relatório pode ser negativo): 400 " +
                "`CASHIER_COUNT_INVALID`. Com alguma diferença, quem tem `billing.shifts.review` (menos o operador) " +
                "recebe a notificação `billing.cashier-shift-difference`.",
    )
    fun close(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CashierShiftCloseRequest,
    ): CashierShiftReportResponse =
        CashierShiftReportResponse.of(
            shifts.close(CashierShiftId(id), checkNotNull(request.counts).map(CashCountRequest::toCount)),
        )

    @GetMapping("/{id}/report")
    @Operation(
        operationId = "getCashierShiftReport",
        summary = "Consultar o relatório de fechamento de um turno",
        description =
            "Em JSON (o PDF é do M5). Exige `billing.shifts.read`; com `OWN`, só o dos próprios turnos (o de outro: " +
                "404 `CASHIER_SHIFT_NOT_FOUND`). Turno aberto: 409 `CASHIER_SHIFT_NOT_CLOSED`.",
    )
    fun report(
        @PathVariable id: UUID,
    ): CashierShiftReportResponse = CashierShiftReportResponse.of(shifts.report(CashierShiftId(id)))

    private fun ok(view: CashierShiftView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(CashierShiftResponse.of(view))
}

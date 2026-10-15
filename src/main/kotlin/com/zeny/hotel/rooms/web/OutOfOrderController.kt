package com.zeny.hotel.rooms.web

import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.application.OutOfOrderCommand
import com.zeny.hotel.rooms.application.OutOfOrderService
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RequestParam
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val MAX_REASON = 500
private const val MAX_RESPONSIBLE = 120

/**
 * Períodos fora de ordem (OOO) e fora de serviço (OOS) das UHs (RF-UH-05, RN-05, RN-07, RF-ACS-08).
 * Abrir, alterar e liberar OOO é ação sensível: sem `rooms.outoforder.manage`, `403 APPROVAL_REQUIRED`
 * e a repetição com `X-Approval-Token`; OOS exige `rooms.outofservice.manage`. Consultar exige
 * `rooms.outoforder.read`. Nada é apagado.
 */
@ApiV1Controller
@Tag(name = "rooms", description = "Tipos de UH, características, UHs e áreas comuns.")
class OutOfOrderController(
    private val outOfOrders: OutOfOrderService,
) {
    @GetMapping("/out-of-order")
    @Operation(
        operationId = "listOutOfOrderPeriods",
        summary = "Listar períodos OOO e OOS",
        description =
            "Os períodos que bloqueiam algum dia de `from` (padrão: a data operacional) a `to` (sem `to`, de " +
                "`from` em diante), em ordem de início. Um período liberado só bloqueia até a véspera da " +
                "liberação (`blockedUntil`); liberado antes de começar, não aparece. `to` antes de `from`: 400 " +
                "`OUT_OF_ORDER_PERIOD_INVALID`. Exige `rooms.outoforder.read`.",
    )
    fun list(
        @Parameter(description = "Primeiro dia (data de negócio); padrão: a data operacional.")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Último dia, inclusive; sem ele, sem limite.")
        @RequestParam(required = false) to: LocalDate?,
        @Parameter(description = "Só os períodos desta UH.")
        @RequestParam(required = false) roomId: UUID?,
        @Parameter(description = "Só os períodos deste tipo.")
        @RequestParam(required = false) kind: OutOfOrderKind?,
        @Valid page: PageParams,
    ): CursorPage<OutOfOrderResponse> =
        outOfOrders.list(from, to, roomId?.let(::RoomId), kind, page).map(OutOfOrderResponse::of)

    @PostMapping("/rooms/{id}/out-of-order")
    @Operation(
        operationId = "openOutOfOrder",
        summary = "Colocar a UH em OOO ou OOS",
        description =
            "Abre um período de até 365 dias que não começa antes da data operacional (422 " +
                "`OUT_OF_ORDER_PERIOD_IN_PAST`), numa UH ativa (422 `ROOM_INACTIVE`), sem sobrepor outro período " +
                "da UH (409 `OUT_OF_ORDER_OVERLAP`). OOO não se aplica a UH ocupada se o período inclui a data " +
                "operacional (RN-07, 422 `OUT_OF_ORDER_ROOM_OCCUPIED`); OOS sim. OOO exige " +
                "`rooms.outoforder.manage` ou a aprovação de um superior (ação `rooms.out-of-order.open`, " +
                "recurso `rooms.room:<id da UH>`); OOS, `rooms.outofservice.manage`. Não muda o status de " +
                "governança. Publica `OutOfOrderChanged`.",
    )
    @ApiResponse(responseCode = "201", description = "Período aberto.")
    fun open(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: OpenOutOfOrderRequest,
    ): ResponseEntity<OutOfOrderResponse> {
        val period = outOfOrders.open(RoomId(id), request.kind, request.command(), approvalToken)
        return ResponseEntity
            .created(URI.create("/api/v1/rooms/$id/out-of-order/${period.id}"))
            .eTag(ETags.of(period.version))
            .body(OutOfOrderResponse.of(period))
    }

    @GetMapping("/rooms/{id}/out-of-order/{periodId}")
    @Operation(
        operationId = "getOutOfOrder",
        summary = "Consultar um período OOO/OOS",
        description =
            "Com o `ETag`, para a edição. Exige `rooms.outoforder.read`. Inexistente ou de outra UH: 404 " +
                "`OUT_OF_ORDER_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
        @PathVariable periodId: UUID,
    ): ResponseEntity<OutOfOrderResponse> = ok(outOfOrders.get(RoomId(id), OutOfOrderId(periodId)))

    @PutMapping("/rooms/{id}/out-of-order/{periodId}")
    @Operation(
        operationId = "updateOutOfOrder",
        summary = "Alterar um período OOO/OOS",
        description =
            "Troca o período, o motivo, o responsável e o status de retorno; o tipo não muda (libere e abra " +
                "outro). Exige o `If-Match` (ausente: 428; versão diferente: 412). Um início ou fim alterado não " +
                "fica antes da data operacional (422 `OUT_OF_ORDER_PERIOD_IN_PAST`); período liberado: 422 " +
                "`OUT_OF_ORDER_RELEASED`; sobreposição: 409 `OUT_OF_ORDER_OVERLAP`; OOO que passa a incluir a " +
                "data operacional numa UH ocupada: 422 `OUT_OF_ORDER_ROOM_OCCUPIED`. OOO exige " +
                "`rooms.outoforder.manage` ou aprovação (ação `rooms.out-of-order.update`, recurso " +
                "`rooms.out-of-order:<id>`). Publica `OutOfOrderChanged`.",
    )
    fun update(
        @PathVariable id: UUID,
        @PathVariable periodId: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: OutOfOrderRequestBody,
    ): ResponseEntity<OutOfOrderResponse> =
        ok(
            outOfOrders.update(
                RoomId(id),
                OutOfOrderId(periodId),
                ETags.expectedVersion(ifMatch),
                request.command(),
                approvalToken,
            ),
        )

    @PostMapping("/rooms/{id}/out-of-order/{periodId}/release")
    @Operation(
        operationId = "releaseOutOfOrder",
        summary = "Liberar um período OOO/OOS",
        description =
            "Libera na data operacional: a UH deixa de estar bloqueada a partir dela. Se o período estava em " +
                "vigor, a UH volta ao status de governança de retorno, com o histórico (origem `MANUAL`); um " +
                "período que ainda não começou é cancelado, e um que já terminou só é encerrado, sem mudar o " +
                "status. Liberar de novo não muda nada. OOO exige `rooms.outoforder.manage` ou aprovação (ação " +
                "`rooms.out-of-order.release`, recurso `rooms.out-of-order:<id>`); OOS, " +
                "`rooms.outofservice.manage`. Publica `OutOfOrderChanged`.",
    )
    fun release(
        @PathVariable id: UUID,
        @PathVariable periodId: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<OutOfOrderResponse> = ok(outOfOrders.release(RoomId(id), OutOfOrderId(periodId), approvalToken))

    private fun ok(period: OutOfOrderPeriod) =
        ResponseEntity.ok().eTag(ETags.of(period.version)).body(OutOfOrderResponse.of(period))
}

@Schema(description = "Um período OOO/OOS para abrir.")
data class OpenOutOfOrderRequest(
    @field:NotNull
    val kind: OutOfOrderKind,
    @field:Schema(description = "Primeiro dia (data de negócio), inclusive.")
    @field:NotNull
    val startsOn: LocalDate,
    @field:Schema(description = "Último dia, inclusive; até 365 dias no período.")
    @field:NotNull
    val endsOn: LocalDate,
    @field:Schema(example = "Vazamento no banheiro")
    @field:NotBlank
    @field:Size(max = MAX_REASON)
    val reason: String,
    @field:Schema(description = "Pessoa ou empresa responsável.", example = "Equipe de manutenção")
    @field:NotBlank
    @field:Size(max = MAX_RESPONSIBLE)
    val responsible: String,
    @field:Schema(description = "Status de governança na liberação. Padrão: `DIRTY` (para limpeza).")
    val returnStatus: HousekeepingStatus? = null,
) {
    fun command() = OutOfOrderCommand(startsOn, endsOn, reason, responsible, returnStatus ?: HousekeepingStatus.DIRTY)
}

@Schema(description = "Os dados de um período OOO/OOS para alterar (o tipo não muda).")
data class OutOfOrderRequestBody(
    @field:NotNull
    val startsOn: LocalDate,
    @field:NotNull
    val endsOn: LocalDate,
    @field:NotBlank
    @field:Size(max = MAX_REASON)
    val reason: String,
    @field:NotBlank
    @field:Size(max = MAX_RESPONSIBLE)
    val responsible: String,
    @field:NotNull
    val returnStatus: HousekeepingStatus,
) {
    fun command() = OutOfOrderCommand(startsOn, endsOn, reason, responsible, returnStatus)
}

@Schema(description = "Um período fora de ordem (OOO) ou fora de serviço (OOS) de uma UH (RF-UH-05).")
data class OutOfOrderResponse(
    val id: UUID,
    val roomId: UUID,
    val kind: OutOfOrderKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val reason: String,
    val responsible: String,
    val returnStatus: HousekeepingStatus,
    val openedAt: Instant,
    @field:Schema(description = "Quem abriu; nulo = o sistema.", nullable = true)
    val openedBy: UUID?,
    val updatedAt: Instant,
    @field:Schema(description = "Data operacional da liberação; nula enquanto não liberado.", nullable = true)
    val releasedOn: LocalDate?,
    @field:Schema(nullable = true)
    val releasedAt: Instant?,
    @field:Schema(description = "Quem liberou; nulo = o sistema ou não liberado.", nullable = true)
    val releasedBy: UUID?,
    @field:Schema(
        description =
            "Último dia que o período bloqueia a UH (o fim, ou a véspera da liberação); nulo se não bloqueia " +
                "nenhum (liberado antes de começar).",
        nullable = true,
    )
    val blockedUntil: LocalDate?,
    @field:Schema(description = "Versão, a mesma do `ETag`.")
    val version: Long,
) {
    companion object {
        fun of(period: OutOfOrderPeriod) =
            OutOfOrderResponse(
                period.id.value,
                period.roomId.value,
                period.kind,
                period.startsOn,
                period.endsOn,
                period.reason,
                period.responsible,
                period.returnStatus,
                period.openedAt,
                period.openedBy?.value,
                period.updatedAt,
                period.releasedOn,
                period.releasedAt,
                period.releasedBy?.value,
                period.blockedUntil,
                period.version,
            )
    }
}

package com.zeny.hotel.rooms.web

import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusField
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.application.HousekeepingObservationView
import com.zeny.hotel.rooms.application.HousekeepingTransition
import com.zeny.hotel.rooms.application.OutOfOrderOverlay
import com.zeny.hotel.rooms.application.RoomDiscrepancyKind
import com.zeny.hotel.rooms.application.RoomDiscrepancyView
import com.zeny.hotel.rooms.application.RoomStatusChangeView
import com.zeny.hotel.rooms.application.RoomStatusService
import com.zeny.hotel.rooms.application.RoomStatusView
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
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val MAX_GUEST_COUNT = 40L

/**
 * Status das UHs (RF-UH-04 a 08, SPEC §8.4, RN-05, RN-08): painel, status de uma UH, histórico, mudança
 * manual do status de governança, sinalização do hóspede e discrepâncias entre recepção e governança.
 * Consultar exige `rooms.status.read`. Toda mudança fica no histórico com quem, quando e a origem.
 */
@ApiV1Controller
@RequestMapping("/rooms")
@Tag(name = "rooms", description = "Tipos de UH, características, UHs e áreas comuns.")
class RoomStatusController(
    private val status: RoomStatusService,
) {
    @GetMapping("/status")
    @Operation(
        operationId = "listRoomStatuses",
        summary = "Painel de status das UHs",
        description =
            "As UHs ativas em ordem de número, com ocupação, status de governança, inspeção pendente, " +
                "sinalização do hóspede, o período OOO/OOS em vigor na data operacional e se estão prontas " +
                "para check-in (RN-05). Exige `rooms.status.read`.",
    )
    fun board(
        @Parameter(description = "Só as UHs com esta ocupação.")
        @RequestParam(required = false) occupancy: OccupancyStatus?,
        @Parameter(description = "Só as UHs com este status de governança.")
        @RequestParam(required = false) housekeepingStatus: HousekeepingStatus?,
        @Valid page: PageParams,
    ): CursorPage<RoomStatusResponse> = status.board(occupancy, housekeepingStatus, page).map(RoomStatusResponse::of)

    @GetMapping("/discrepancies")
    @Operation(
        operationId = "listRoomDiscrepancies",
        summary = "Discrepâncias entre recepção e governança",
        description =
            "As UHs ativas cuja última observação da governança, feita depois da última mudança da ocupação " +
                "ou do número de hóspedes pela recepção, diverge dela (RF-UH-08): ocupada mas vazia, vaga mas " +
                "ocupada, ou outro número de hóspedes. Em ordem de número. Exige `rooms.status.read`.",
    )
    fun discrepancies(
        @Valid page: PageParams,
    ): CursorPage<RoomDiscrepancyResponse> = status.discrepancies(page).map(RoomDiscrepancyResponse::of)

    @GetMapping("/{id}/status")
    @Operation(
        operationId = "getRoomStatus",
        summary = "Consultar o status de uma UH",
        description =
            "Com o `ETag` da UH (o mesmo do cadastro), para a mudança manual. Exige `rooms.status.read`. " +
                "Inexistente: 404 `ROOM_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomStatusResponse> = ok(status.status(RoomId(id)))

    @GetMapping("/{id}/status-history")
    @Operation(
        operationId = "listRoomStatusHistory",
        summary = "Histórico de status de uma UH",
        description =
            "Um registro por campo que mudou, das mudanças mais recentes às mais antigas, com quem (`changedBy` " +
                "nulo = o sistema), quando e a origem (RF-UH-06). Exige `rooms.status.read`.",
    )
    fun history(
        @PathVariable id: UUID,
        @Valid page: PageParams,
    ): CursorPage<RoomStatusChangeResponse> = status.history(RoomId(id), page).map(RoomStatusChangeResponse::of)

    @PostMapping("/{id}/housekeeping-status")
    @Operation(
        operationId = "changeRoomHousekeepingStatus",
        summary = "Mudar à mão o status de governança",
        description =
            "Só as transições da SPEC §8.4: Sujo → Limpo (`MARK_CLEAN`, com `requiresInspection` para deixar a " +
                "inspeção pendente), Limpo → Inspecionado (`MARK_INSPECTED`), Limpo com inspeção pendente → Sujo " +
                "(`REJECT_INSPECTION`) e Limpo ou Inspecionado → Sujo (`MARK_DIRTY`); as outras: 422 " +
                "`HOUSEKEEPING_TRANSITION_NOT_ALLOWED`. Repetir o estado atual não muda nada. Exige " +
                "`rooms.status.manage` e o `If-Match` (ausente: 428; versão diferente: 412). Fica no histórico " +
                "com a origem `MANUAL` (RN-08).",
    )
    fun changeHousekeeping(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: HousekeepingStatusRequest,
    ): ResponseEntity<RoomStatusResponse> =
        ok(
            status.changeHousekeeping(
                RoomId(id),
                ETags.expectedVersion(ifMatch),
                request.transition,
                request.requiresInspection ?: false,
            ),
        )

    @PostMapping("/{id}/service-flag")
    @Operation(
        operationId = "setRoomServiceFlag",
        summary = "Registrar a sinalização do hóspede",
        description =
            "Não perturbe ou arrumar agora, só numa UH ocupada (422 `ROOM_NOT_OCCUPIED`); `NONE` retira a " +
                "sinalização. Definir o valor atual não muda nada. Exige `rooms.status.flag`. A UH ficar vaga " +
                "também retira a sinalização.",
    )
    fun setServiceFlag(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ServiceFlagRequest,
    ): ResponseEntity<RoomStatusResponse> = ok(status.setServiceFlag(RoomId(id), request.flag))

    @PostMapping("/{id}/housekeeping-observations")
    @Operation(
        operationId = "recordRoomHousekeepingObservation",
        summary = "Registrar o que a governança encontrou na UH",
        description =
            "A ocupação encontrada e, se contou, o número de pessoas (só com a UH ocupada; vazia com número: 400 " +
                "`GUEST_COUNT_INVALID`). Comparada com a recepção, mostra as discrepâncias (RF-UH-08). Exige " +
                "`rooms.status.observe`; UH desativada: 422 `ROOM_INACTIVE`.",
    )
    @ApiResponse(responseCode = "201", description = "Observação registrada.")
    fun recordObservation(
        @PathVariable id: UUID,
        @Valid @RequestBody request: HousekeepingObservationRequest,
    ): ResponseEntity<HousekeepingObservationResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                HousekeepingObservationResponse.of(
                    status.recordObservation(RoomId(id), request.occupancy, request.guestCount),
                ),
            )

    private fun ok(view: RoomStatusView) =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RoomStatusResponse.of(view))
}

@Schema(description = "Uma mudança manual do status de governança.")
data class HousekeepingStatusRequest(
    @field:NotNull
    val transition: HousekeepingTransition,
    @field:Schema(description = "Só com `MARK_CLEAN`: deixa a inspeção pendente. Padrão: `false`.")
    val requiresInspection: Boolean? = null,
)

@Schema(description = "A sinalização do hóspede.")
data class ServiceFlagRequest(
    @field:NotNull
    val flag: GuestServiceFlag,
)

@Schema(description = "O que a governança encontrou na UH.")
data class HousekeepingObservationRequest(
    @field:NotNull
    val occupancy: OccupancyStatus,
    @field:Schema(description = "Quantas pessoas, se contou (só com a UH ocupada), de 1 a 40.", nullable = true)
    @field:Min(1)
    @field:Max(MAX_GUEST_COUNT)
    val guestCount: Int? = null,
)

@Schema(description = "O status de uma UH (RF-UH-04).")
data class RoomStatusResponse(
    val roomId: UUID,
    val number: String,
    @field:Schema(nullable = true)
    val floor: String?,
    val roomTypeId: UUID,
    val active: Boolean,
    val occupancy: OccupancyStatus,
    @field:Schema(description = "Número de hóspedes informado pela recepção; só com a UH ocupada.", nullable = true)
    val guestCount: Int?,
    val housekeepingStatus: HousekeepingStatus,
    @field:Schema(description = "Limpa, aguardando inspeção.")
    val inspectionPending: Boolean,
    val serviceFlag: GuestServiceFlag,
    @field:Schema(
        description = "O período OOO/OOS em vigor na data operacional (a sobreposição); nulo sem nenhum.",
        nullable = true,
    )
    val outOfOrder: OutOfOrderOverlayResponse?,
    @field:Schema(description = "Vaga, fora de OOO e Inspecionada, ou Limpa sem inspeção pendente (RN-05).")
    val readyForCheckIn: Boolean,
    @field:Schema(description = "Instante da última mudança de status.")
    val changedAt: Instant,
    @field:Schema(description = "Versão da UH, a mesma do `ETag`.")
    val version: Long,
) {
    companion object {
        fun of(view: RoomStatusView) =
            RoomStatusResponse(
                view.roomId.value,
                view.number,
                view.floor,
                view.roomTypeId.value,
                view.active,
                view.occupancy,
                view.guestCount,
                view.housekeepingStatus,
                view.inspectionPending,
                view.serviceFlag,
                view.outOfOrder?.let(OutOfOrderOverlayResponse::of),
                view.readyForCheckIn,
                view.changedAt,
                view.version,
            )
    }
}

@Schema(description = "O período OOO/OOS em vigor numa UH (a sobreposição do painel).")
data class OutOfOrderOverlayResponse(
    val id: UUID,
    val kind: OutOfOrderKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
) {
    companion object {
        fun of(overlay: OutOfOrderOverlay) =
            OutOfOrderOverlayResponse(overlay.id.value, overlay.kind, overlay.startsOn, overlay.endsOn)
    }
}

@Schema(description = "Um registro do histórico de status (RF-UH-06): um campo que mudou.")
data class RoomStatusChangeResponse(
    val id: UUID,
    val field: RoomStatusField,
    @field:Schema(description = "Valor anterior (nome do status, número ou `true`/`false`).", nullable = true)
    val from: String?,
    @field:Schema(description = "Valor novo.", nullable = true)
    val to: String?,
    val changedAt: Instant,
    @field:Schema(description = "Usuário que fez a mudança; nulo = o sistema.", nullable = true)
    val changedBy: UUID?,
    val source: RoomStatusSource,
    @field:Schema(description = "Versão da UH depois da mudança.")
    val version: Long,
) {
    companion object {
        fun of(view: RoomStatusChangeView) =
            RoomStatusChangeResponse(
                view.id,
                view.field,
                view.from,
                view.to,
                view.changedAt,
                view.changedBy?.value,
                view.source,
                view.roomVersion,
            )
    }
}

@Schema(description = "O que a governança encontrou numa UH.")
data class HousekeepingObservationResponse(
    val id: UUID,
    val roomId: UUID,
    val occupancy: OccupancyStatus,
    @field:Schema(nullable = true)
    val guestCount: Int?,
    val observedAt: Instant,
    val observedBy: UUID,
) {
    companion object {
        fun of(view: HousekeepingObservationView) =
            HousekeepingObservationResponse(
                view.id,
                view.roomId.value,
                view.occupancy,
                view.guestCount,
                view.observedAt,
                view.observedBy.value,
            )
    }
}

@Schema(description = "Uma discrepância vigente entre recepção e governança (RF-UH-08).")
data class RoomDiscrepancyResponse(
    val roomId: UUID,
    val number: String,
    val kind: RoomDiscrepancyKind,
    val frontDeskOccupancy: OccupancyStatus,
    @field:Schema(nullable = true)
    val frontDeskGuestCount: Int?,
    val observationId: UUID,
    val observedOccupancy: OccupancyStatus,
    @field:Schema(nullable = true)
    val observedGuestCount: Int?,
    val observedAt: Instant,
    val observedBy: UUID,
) {
    companion object {
        fun of(view: RoomDiscrepancyView) =
            RoomDiscrepancyResponse(
                view.roomId.value,
                view.number,
                view.kind,
                view.frontDeskOccupancy,
                view.frontDeskGuestCount,
                view.observation.id,
                view.observation.occupancy,
                view.observation.guestCount,
                view.observation.observedAt,
                view.observation.observedBy.value,
            )
    }
}

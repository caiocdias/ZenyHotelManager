package com.zeny.hotel.reservations.web

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.BlockAllocationRange
import com.zeny.hotel.reservations.BlockAllocationSummary
import com.zeny.hotel.reservations.BlockReservationRequest
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.NewRoomBlock
import com.zeny.hotel.reservations.NewStayGuest
import com.zeny.hotel.reservations.ReservationOrigin
import com.zeny.hotel.reservations.RoomBlockChange
import com.zeny.hotel.reservations.RoomBlockId
import com.zeny.hotel.reservations.RoomBlockStatus
import com.zeny.hotel.reservations.RoomBlockSummary
import com.zeny.hotel.reservations.RoomingList
import com.zeny.hotel.reservations.application.BookingView
import com.zeny.hotel.reservations.application.RoomBlockService
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
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
import java.time.LocalDate
import java.util.UUID

private const val UNKNOWN_VALUE = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

@Schema(
    description =
        "As unidades de um tipo de UH num período de noites (de `from` a `to`, inclusive; a saída não é noite): " +
            "`quantity` UHs por noite (de 1 a 50) à tarifa do grupo `rate` por UH e noite.",
)
data class BlockAllocationRequest(
    @field:NotNull
    val roomTypeId: UUID?,
    @field:NotNull
    val from: LocalDate?,
    @field:NotNull
    val to: LocalDate?,
    @field:NotNull
    val quantity: Int?,
    @field:NotNull
    val rate: Money?,
) {
    fun toRange() =
        BlockAllocationRange(
            RoomTypeId(checkNotNull(roomTypeId)),
            checkNotNull(from),
            checkNotNull(to),
            checkNotNull(quantity),
            checkNotNull(rate),
        )
}

@Schema(
    description =
        "Um bloqueio de grupo: o nome do grupo, o titular (um hóspede **ou** uma empresa), o plano tarifário (as " +
            "políticas que as reservas do pick-up copiam), a data de corte e as unidades por tipo de UH e período.",
)
data class RoomBlockRequest(
    @field:NotNull
    val name: String?,
    val holderGuestId: UUID? = null,
    val holderCompanyId: UUID? = null,
    @field:NotNull
    val ratePlanId: UUID?,
    @field:NotNull
    val cutoffDate: LocalDate?,
    val allocations: List<@Valid BlockAllocationRequest> = emptyList(),
) {
    fun toNew() =
        NewRoomBlock(
            name = checkNotNull(name),
            holderGuestId = holderGuestId?.let(::GuestId),
            holderCompanyId = holderCompanyId?.let(::CompanyId),
            ratePlanId = RatePlanId(checkNotNull(ratePlanId)),
            cutoffDate = checkNotNull(cutoffDate),
            allocations = allocations.map { it.toRange() },
        )
}

@Schema(
    description =
        "A alteração de um bloqueio ativo: o nome, a data de corte e as unidades das noites **a partir da data " +
            "operacional**, que substituem as gravadas dessas noites (as anteriores ficam como estão; não as envie).",
)
data class RoomBlockChangeRequest(
    @field:NotNull
    val name: String?,
    @field:NotNull
    val cutoffDate: LocalDate?,
    val allocations: List<@Valid BlockAllocationRequest> = emptyList(),
) {
    fun toChange() = RoomBlockChange(checkNotNull(name), checkNotNull(cutoffDate), allocations.map { it.toRange() })
}

@Schema(
    description =
        "Uma reserva do pick-up: o tipo de UH, a chegada e a saída (noites do bloqueio; sem day use), a ocupação, o " +
            "segmento (padrão `EVENT`), os pedidos especiais e os hóspedes da estadia (vazio = o titular como " +
            "principal; senão, exatamente um principal). A diária é a tarifa do grupo de cada noite, congelada.",
)
data class BlockReservationDto(
    @field:NotNull
    val roomTypeId: UUID?,
    @field:NotNull
    val arrival: LocalDate?,
    @field:NotNull
    val departure: LocalDate?,
    @field:NotNull
    @field:Min(1)
    val adults: Int?,
    @field:Schema(description = "A idade de cada criança na chegada, em anos completos.")
    val childAges: List<Int> = emptyList(),
    val segment: MarketSegment = MarketSegment.EVENT,
    @field:Schema(description = "Pedidos especiais (texto livre, até 1000 caracteres; nunca vai à trilha).")
    val specialRequests: String? = null,
    val guests: List<@Valid StayGuestRequest> = emptyList(),
) {
    fun toRequest() =
        BlockReservationRequest(
            roomTypeId = RoomTypeId(checkNotNull(roomTypeId)),
            arrival = checkNotNull(arrival),
            departure = checkNotNull(departure),
            adults = checkNotNull(adults),
            childAges = childAges,
            segment = segment,
            specialRequests = specialRequests,
            guests = guests.map { NewStayGuest(GuestId(checkNotNull(it.guestId)), it.primary) },
        )
}

@Schema(
    description =
        "O pick-up de uma reserva do bloqueio: o titular da reserva agrupadora (sem ele, o hóspede titular do " +
            "bloqueio; obrigatório num bloqueio de empresa), a origem e a reserva.",
)
data class BlockPickUpRequest(
    val holderGuestId: UUID? = null,
    @field:NotNull
    val origin: ReservationOrigin?,
    @field:NotNull
    @field:Valid
    val reservation: BlockReservationDto?,
) {
    fun toList() =
        RoomingList(
            holderGuestId?.let(::GuestId),
            checkNotNull(origin),
            listOf(checkNotNull(reservation).toRequest()),
        )
}

@Schema(
    description =
        "A rooming list: as reservas do bloqueio (de 1 a 30) criadas numa só reserva agrupadora, com o titular (sem " +
            "ele, o hóspede titular do bloqueio; obrigatório num bloqueio de empresa) e a origem.",
)
data class RoomingListRequest(
    val holderGuestId: UUID? = null,
    @field:NotNull
    val origin: ReservationOrigin?,
    val reservations: List<@Valid BlockReservationDto> = emptyList(),
) {
    fun toList() = RoomingList(holderGuestId?.let(::GuestId), checkNotNull(origin), reservations.map { it.toRequest() })
}

@Schema(
    description =
        "As unidades do bloqueio de um tipo de UH numa noite: `quantity` contratadas, `pickedUp` já convertidas em " +
            "reservas ativas, `released` devolvidas ao inventário pela liberação, `remaining` as que ainda aceitam " +
            "pick-up (num bloqueio ativo) e a tarifa do grupo.",
)
data class BlockAllocationResponse(
    val roomTypeId: UUID,
    val date: LocalDate,
    val quantity: Int,
    val pickedUp: Int,
    val released: Int,
    val remaining: Int,
    val rate: Money,
) {
    companion object {
        fun of(allocation: BlockAllocationSummary) =
            BlockAllocationResponse(
                allocation.roomTypeId.value,
                allocation.date,
                allocation.quantity,
                allocation.pickedUp,
                allocation.released,
                allocation.remaining,
                allocation.rate,
            )
    }
}

@Schema(description = "Um bloqueio de grupo, com as unidades por tipo de UH e noite e o acompanhamento do pick-up.")
data class RoomBlockResponse(
    val id: UUID,
    val code: String,
    val name: String,
    val holderGuestId: UUID?,
    val holderCompanyId: UUID?,
    val ratePlanId: UUID,
    val cutoffDate: LocalDate,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`ACTIVE` (segura as unidades não utilizadas e aceita pick-up até a data de corte) ou `RELEASED` (o não " +
                "utilizado voltou ao inventário). $UNKNOWN_VALUE",
    )
    val status: RoomBlockStatus,
    @field:Schema(description = "A conta master do grupo no `billing` (dono `GROUP`).")
    val folioId: UUID,
    val allocations: List<BlockAllocationResponse>,
    @field:Schema(description = "O total de unidades contratadas (a soma das noites).")
    val totalQuantity: Int,
    @field:Schema(description = "O total de unidades já convertidas em reservas ativas (a soma das noites).")
    val totalPickedUp: Int,
    val releasedAt: Instant?,
    val releasedOn: LocalDate?,
    val releasedBy: UUID?,
    val version: Long,
    val createdAt: Instant,
    val createdBy: UUID?,
) {
    companion object {
        fun of(block: RoomBlockSummary) =
            RoomBlockResponse(
                id = block.id.value,
                code = block.code.toString(),
                name = block.name,
                holderGuestId = block.holderGuestId?.value,
                holderCompanyId = block.holderCompanyId?.value,
                ratePlanId = block.ratePlanId.value,
                cutoffDate = block.cutoffDate,
                status = block.status,
                folioId = block.folioId.value,
                allocations = block.allocations.map(BlockAllocationResponse::of),
                totalQuantity = block.allocations.sumOf { it.quantity },
                totalPickedUp = block.allocations.sumOf { it.pickedUp },
                releasedAt = block.releasedAt,
                releasedOn = block.releasedOn,
                releasedBy = block.releasedBy?.value,
                version = block.version,
                createdAt = block.createdAt,
                createdBy = block.createdBy?.value,
            )
    }
}

/**
 * Bloqueios de grupo (M4 E11; RF-RES-09, RN-16): criar e alterar exigem `reservations.blocks.manage`; consultar,
 * `reservations.blocks.read`; o pick-up e a rooming list, `reservations.blocks.pickup`. A liberação do não utilizado é
 * só da `ReservationsApi` (o fechamento do dia, M6).
 */
@ApiV1Controller
@RequestMapping("/room-blocks")
@Tag(name = RESERVATIONS_TAG, description = RESERVATIONS_TAG_DESCRIPTION)
class RoomBlocksController(
    private val blocks: RoomBlockService,
) {
    @PostMapping
    @Idempotent
    @Operation(
        operationId = "createRoomBlock",
        summary = "Criar um bloqueio de grupo",
        description =
            "N UHs por tipo e noite com a tarifa do grupo, a data de corte e o titular (`holderGuestId` **ou** " +
                "`holderCompanyId`). Exige `reservations.blocks.manage` e `Idempotency-Key`. Formato: 400 " +
                "`ROOM_BLOCK_INVALID` com o campo. Titular hóspede inexistente: 400 `UNKNOWN_GUEST`; mesclado vira " +
                "o sobrevivente; anonimizado: 422 `GUEST_ANONYMIZED`. Empresa inexistente: 400 `UNKNOWN_COMPANY`; " +
                "desativada: 422 `COMPANY_INACTIVE`. Plano inexistente: 400 `UNKNOWN_RATE_PLAN`; desativado: 422 " +
                "`RATE_PLAN_INACTIVE`. Tipo de UH inexistente: 400 `UNKNOWN_ROOM_TYPE`; desativado: 422 " +
                "`ROOM_TYPE_INACTIVE`; fora do plano do bloqueio: 422 `ROOM_TYPE_NOT_IN_PLAN` (campo em `field`). " +
                "Data de corte ou noites antes da data operacional: 422 " +
                "`ROOM_BLOCK_CUTOFF_IN_PAST`/`ROOM_BLOCK_PERIOD_IN_PAST`. As unidades saem da disponibilidade " +
                "(contador `blocked`): sem disponibilidade, 409 `ROOM_TYPE_UNAVAILABLE` e nada é gravado; fora do " +
                "inventário mantido, 422 `INVENTORY_DATE_OUT_OF_HORIZON`. Abre a conta master do grupo no `billing` " +
                "(dono `GROUP`; a empresa titular como pagadora).",
    )
    @ApiResponse(responseCode = "201", description = "Bloqueio criado.")
    fun create(
        @Valid @RequestBody request: RoomBlockRequest,
    ): ResponseEntity<RoomBlockResponse> {
        val block = blocks.create(request.toNew())
        return ResponseEntity
            .created(URI.create("/api/v1/room-blocks/${block.id}"))
            .eTag(ETags.of(block.version))
            .body(RoomBlockResponse.of(block))
    }

    @GetMapping
    @Operation(
        operationId = "listRoomBlocks",
        summary = "Listar os bloqueios de grupo",
        description =
            "Do mais novo ao mais antigo, por cursor; filtro opcional pela situação. Exige `reservations.blocks.read`.",
    )
    fun list(
        @RequestParam(required = false) status: RoomBlockStatus?,
        @Valid page: PageParams,
    ): CursorPage<RoomBlockResponse> = blocks.list(status, page).map(RoomBlockResponse::of)

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRoomBlock",
        summary = "Consultar um bloqueio de grupo",
        description =
            "Com as unidades por tipo de UH e noite, o pick-up e a conta master. Exige `reservations.blocks.read`. " +
                "Inexistente: 404 `ROOM_BLOCK_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoomBlockResponse> = detail(blocks.block(RoomBlockId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "changeRoomBlock",
        summary = "Alterar um bloqueio de grupo",
        description =
            "Troca o nome, a data de corte e as unidades das noites a partir da data operacional (as pedidas " +
                "substituem as gravadas dessas noites; noites anteriores: 422 `ROOM_BLOCK_PERIOD_IN_PAST`), com " +
                "`If-Match` (412 com outra versão; 428 sem ele). Exige `reservations.blocks.manage`. " +
                "Todo tipo de UH das " +
                "unidades precisa estar no plano do bloqueio (422 `ROOM_TYPE_NOT_IN_PLAN`). Só num " +
                "bloqueio ativo (409 `ROOM_BLOCK_RELEASED`). Nenhuma noite fica com menos unidades que as já " +
                "convertidas em reservas (422 `ROOM_BLOCK_BELOW_PICK_UP`). A diferença vai à disponibilidade na " +
                "mesma transação: sem disponibilidade, 409 `ROOM_TYPE_UNAVAILABLE` e nada muda. A data de corte " +
                "nova não pode ser anterior à data operacional (422 `ROOM_BLOCK_CUTOFF_IN_PAST`). A tarifa nova " +
                "vale só para os pick-ups seguintes.",
    )
    fun change(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RoomBlockChangeRequest,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
    ): ResponseEntity<RoomBlockResponse> {
        val expected = ETags.expectedVersion(ifMatch)
        return detail(blocks.change(RoomBlockId(id), expected, request.toChange()))
    }

    @PostMapping("/{id}/pick-up")
    @Idempotent
    @Operation(
        operationId = "pickUpRoomBlock",
        summary = "Converter uma unidade do bloqueio numa reserva (pick-up)",
        description = PICK_UP_DESCRIPTION,
    )
    @ApiResponse(responseCode = "201", description = "Reserva agrupadora criada com a reserva do bloqueio.")
    fun pickUp(
        @PathVariable id: UUID,
        @Valid @RequestBody request: BlockPickUpRequest,
    ): ResponseEntity<BookingResponse> = created(blocks.pickUp(RoomBlockId(id), request.toList()))

    @PostMapping("/{id}/rooming-list")
    @Idempotent
    @Operation(
        operationId = "createRoomBlockRoomingList",
        summary = "Criar as reservas de uma rooming list do bloqueio",
        description =
            "As reservas da lista (de 1 a 30) numa só reserva agrupadora, com as regras do pick-up, tudo ou nada. " +
                PICK_UP_DESCRIPTION,
    )
    @ApiResponse(responseCode = "201", description = "Reserva agrupadora criada com as reservas do bloqueio.")
    fun roomingList(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RoomingListRequest,
    ): ResponseEntity<BookingResponse> = created(blocks.pickUp(RoomBlockId(id), request.toList()))

    private fun created(view: BookingView): ResponseEntity<BookingResponse> =
        ResponseEntity
            .created(URI.create("/api/v1/bookings/${view.booking.id}"))
            .eTag(ETags.of(view.booking.version))
            .body(BookingResponse.of(view))

    private fun detail(block: RoomBlockSummary): ResponseEntity<RoomBlockResponse> =
        ResponseEntity.ok().eTag(ETags.of(block.version)).body(RoomBlockResponse.of(block))

    private companion object {
        const val PICK_UP_DESCRIPTION =
            "Exige `reservations.blocks.pickup` e `Idempotency-Key` (a repetição com a mesma chave não cria nada). " +
                "Bloqueio inexistente: 404 `ROOM_BLOCK_NOT_FOUND`; liberado: 409 `ROOM_BLOCK_RELEASED`; depois da " +
                "data de corte: 422 `ROOM_BLOCK_CUTOFF_PASSED`. Cada noite da reserva precisa estar no bloqueio (422 " +
                "`ROOM_BLOCK_DATES_NOT_COVERED`) e ter unidade ainda não convertida (409 `ROOM_BLOCK_UNAVAILABLE`); " +
                "sem day use e com as crianças até a idade limite do hotel (400 `STAY_INVALID`); chegada antes da " +
                "data operacional ou ocupação acima da máxima do tipo de UH: 422 com o código do `rates`. A diária " +
                "de cada noite é a tarifa do grupo, congelada (RN-14); as políticas são as do plano do bloqueio, " +
                "copiadas (RN-15), e a situação inicial segue a garantia delas. Cada noite passa uma unidade de " +
                "`blocked` para `sold` na mesma transação (a disponibilidade não muda). Abre a conta de cada reserva " +
                "no `billing`. Responde a reserva agrupadora (`Location` em `/api/v1/bookings/{id}`)."
    }
}

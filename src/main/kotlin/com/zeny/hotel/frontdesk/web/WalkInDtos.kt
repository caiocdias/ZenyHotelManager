package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.application.WalkInCommand
import com.zeny.hotel.frontdesk.application.WalkInOutcome
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.UUID

private const val MAX_WALK_IN_GUESTS = 40
private const val MAX_SPECIAL_REQUESTS = 1000

@Schema(
    description =
        "O walk-in (RF-REC-02): a reserva criada na data operacional, a UH alocada e o check-in feito numa transação " +
            "só. Os hóspedes já estão cadastrados no `guests` (`POST /api/v1/guests`); o preço é o calculado pela " +
            "tarifa (sem diária editada nem desconto: o desconto, se houver, vem depois pelo " +
            "`PUT /api/v1/reservations/{id}` com a aprovação de superior).",
)
data class WalkInRequest(
    @field:Schema(description = "O titular da reserva; nulo = o hóspede principal.", nullable = true)
    val holderGuestId: UUID? = null,
    @field:Schema(description = "A UH escolhida (alocada à reserva e ocupada no check-in).")
    @field:NotNull
    val roomId: UUID?,
    @field:Schema(description = "O tipo de UH; nulo = o tipo da UH escolhida.", nullable = true)
    val roomTypeId: UUID? = null,
    @field:Schema(description = "O plano tarifário (vendável no canal `DIRECT`).")
    @field:NotNull
    val ratePlanId: UUID?,
    @field:Schema(
        description =
            "A saída. A chegada é a data operacional; a saída igual a ela é um day use (sem pernoite, sem inventário).",
    )
    @field:NotNull
    val departure: LocalDate?,
    @field:Schema(description = "O número de adultos (ao menos 1).")
    @field:NotNull
    val adults: Int?,
    @field:Schema(description = "A idade de cada criança em anos completos na chegada (até a idade máxima do hotel).")
    val childAges: List<Int> = emptyList(),
    @field:NotNull
    val segment: MarketSegment?,
    @field:Schema(description = "Pedidos especiais (texto livre, até 1000).", nullable = true)
    @field:Size(max = MAX_SPECIAL_REQUESTS)
    val specialRequests: String? = null,
    @field:Schema(
        description =
            "A lista **completa** dos hóspedes da estadia com os dados de check-in (exatamente um principal, até a " +
                "ocupação), como no check-in de uma reserva.",
    )
    @field:NotNull
    @field:Size(min = 1, max = MAX_WALK_IN_GUESTS)
    val guests: List<@Valid CheckInGuestRequest>?,
    @field:Schema(
        description = "O número de pessoas na UH (de 1 a 40, não abaixo da lista); nulo, o tamanho da lista.",
        nullable = true,
    )
    @field:Min(1)
    @field:Max(MAX_WALK_IN_GUESTS.toLong())
    val guestCount: Int? = null,
    @field:Valid
    @field:Schema(nullable = true)
    val preAuthorization: CheckInPreAuthorizationRequest? = null,
    @field:Schema(description = EARLY_FEE_DESCRIPTION, nullable = true, example = "60.00")
    val earlyCheckInFee: Money? = null,
) {
    fun toCommand() =
        WalkInCommand(
            holderGuestId = holderGuestId?.let(::GuestId),
            roomId = RoomId(checkNotNull(roomId)),
            roomTypeId = roomTypeId?.let(::RoomTypeId),
            ratePlanId = RatePlanId(checkNotNull(ratePlanId)),
            departure = checkNotNull(departure),
            adults = checkNotNull(adults),
            childAges = childAges,
            segment = checkNotNull(segment),
            specialRequests = specialRequests,
            guests = checkNotNull(guests).map { it.toGuest() },
            guestCount = guestCount,
            preAuthorization = preAuthorization?.toNew(),
            earlyCheckInFee = requirePositiveEarlyFee(earlyCheckInFee),
        )
}

@Schema(
    description =
        "O walk-in feito: a reserva agrupadora criada (origem `FRONT_DESK`), o total calculado, a reserva " +
            "`IN_HOUSE`, a UH Ocupada, a caução registrada (nula sem caução) e os alertas da lista de restrição (dos " +
            "hóspedes e do titular) e o early check-in lançado (nulo sem ele). O detalhe da reserva, com as noites e " +
            "os termos, é `GET /api/v1/reservations/{id}`. Nenhum número de documento nem dado sensível.",
)
data class WalkInResponse(
    val bookingId: UUID,
    @field:Schema(description = "O número da reserva agrupadora.", example = "B-2026-000123")
    val bookingCode: String,
    val holderGuestId: UUID,
    @field:Schema(description = "O total da reserva (as diárias calculadas e os pacotes adicionais).")
    val total: Money,
    val reservation: StayReservationResponse,
    val room: StayRoomResponse,
    @field:Schema(nullable = true)
    val preAuthorization: StayPreAuthorizationResponse?,
    val restrictionAlerts: List<StayRestrictionAlertResponse>,
    @field:Schema(
        description = "O early check-in lançado na conta da reserva; nulo sem `earlyCheckInFee`.",
        nullable = true,
    )
    val earlyCheckIn: CheckOutPostingResponse? = null,
) {
    companion object {
        fun of(outcome: WalkInOutcome): WalkInResponse {
            val checkIn = CheckInResponse.of(outcome.checkIn)
            return WalkInResponse(
                bookingId = outcome.booking.id.value,
                bookingCode = outcome.booking.code.toString(),
                holderGuestId = outcome.booking.holderGuestId.value,
                total = outcome.checkIn.reservation.total,
                reservation = checkIn.reservation,
                room = checkIn.room,
                preAuthorization = checkIn.preAuthorization,
                restrictionAlerts = checkIn.restrictionAlerts,
                earlyCheckIn = checkIn.earlyCheckIn,
            )
        }
    }
}

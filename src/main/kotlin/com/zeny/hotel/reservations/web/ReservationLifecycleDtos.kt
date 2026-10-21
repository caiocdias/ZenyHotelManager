package com.zeny.hotel.reservations.web

import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.NightlyRateOverride
import com.zeny.hotel.reservations.PendingGuarantee
import com.zeny.hotel.reservations.PendingGuaranteeKind
import com.zeny.hotel.reservations.ReservationCancellationRequest
import com.zeny.hotel.reservations.ReservationChange
import com.zeny.hotel.reservations.ReservationHistoryEntry
import com.zeny.hotel.reservations.ReservationHistoryKind
import com.zeny.hotel.reservations.ReservationState
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ExtensibleEnum
import com.zeny.hotel.shared.Money
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val UNKNOWN_VALUE = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

@Schema(
    description =
        "A alteração de uma reserva: a estadia inteira como deve ficar, o segmento e os pedidos especiais (nulo " +
            "apaga). `nightlyRates` só nas noites que a alteração recalcula (menor que a calculada é desconto).",
)
data class ReservationChangeRequest(
    @field:NotNull
    val roomTypeId: UUID?,
    @field:NotNull
    val ratePlanId: UUID?,
    @field:NotNull
    val arrival: LocalDate?,
    @field:NotNull
    val departure: LocalDate?,
    @field:NotNull
    @field:Min(1)
    val adults: Int?,
    @field:Schema(description = "A idade de cada criança na chegada, em anos completos.")
    val childAges: List<Int> = emptyList(),
    @field:NotNull
    val segment: MarketSegment?,
    @field:Schema(description = "Pedidos especiais (texto livre, até 1000 caracteres; nunca vai à trilha).")
    val specialRequests: String? = null,
    val nightlyRates: List<@Valid NightlyRateRequest> = emptyList(),
) {
    fun toChange() =
        ReservationChange(
            roomTypeId = RoomTypeId(checkNotNull(roomTypeId)),
            ratePlanId = RatePlanId(checkNotNull(ratePlanId)),
            arrival = checkNotNull(arrival),
            departure = checkNotNull(departure),
            adults = checkNotNull(adults),
            childAges = childAges,
            segment = checkNotNull(segment),
            specialRequests = specialRequests,
            nightlyRates = nightlyRates.map { NightlyRateOverride(checkNotNull(it.date), checkNotNull(it.roomAmount)) },
        )
}

@Schema(
    description =
        "O cancelamento: o motivo, uma observação opcional (texto livre até 500 caracteres; nunca vai à trilha) e " +
            "se a multa devida é isentada (ação sensível).",
)
data class ReservationCancelRequest(
    @field:NotNull
    @field:Schema(
        description =
            "Motivo do cancelamento. `EXPIRED` é só da expiração automática da pré-reserva: pedido com ele é 400 " +
                "`RESERVATION_CANCELLATION_INVALID`.",
    )
    val reason: CancellationReason?,
    val notes: String? = null,
    val waivePenalty: Boolean = false,
) {
    fun toRequest() = ReservationCancellationRequest(checkNotNull(reason), notes, waivePenalty)
}

@Schema(
    description =
        "A confirmação de uma pré-reserva (ou o registro da garantia de uma reserva confirmada sem ela): a " +
            "garantia recebida, `CARD` (o cartão informado pelo hóspede) ou `DEPOSIT` (o depósito pago na conta da " +
            "reserva, conferido). A política copiada na reserva decide qual.",
)
data class ReservationConfirmRequest(
    @field:NotNull
    val guarantee: GuaranteeMethod?,
)

@Schema(
    description =
        "Uma reserva que ainda deve a garantia: o tipo do item (`PROVISIONAL`, a pré-reserva; `UNGUARANTEED`, a " +
            "confirmada sem garantia), o vencimento (`overdue` quando já passou), a estadia, o total, a garantia que " +
            "a política pede e, no depósito, o exigido, o recebido na conta (os depósitos menos os reembolsos deles) " +
            "e o prazo da política.",
)
data class PendingGuaranteeResponse(
    val id: UUID,
    val code: String,
    val bookingId: UUID,
    val holderGuestId: UUID,
    @field:ExtensibleEnum
    @field:Schema(description = "`PROVISIONAL` ou `UNGUARANTEED`. $UNKNOWN_VALUE")
    val kind: PendingGuaranteeKind,
    @field:ExtensibleEnum
    @field:Schema(description = "Situação da reserva. $UNKNOWN_VALUE")
    val status: ReservationStatus,
    @field:Schema(
        description =
            "O vencimento: a validade da pré-reserva, ou o horário de check-in (no fuso do hotel) do dia do prazo " +
                "do depósito ou, no cartão, do dia da chegada.",
    )
    val dueAt: Instant,
    val overdue: Boolean,
    val provisionalUntil: Instant?,
    val roomTypeId: UUID,
    val ratePlanId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val total: Money,
    @field:ExtensibleEnum
    @field:Schema(description = "Tipo de garantia que a política copiada pede. $UNKNOWN_VALUE")
    val guaranteeType: GuaranteeType,
    val guaranteePolicyId: UUID,
    val requiredDeposit: Money?,
    val depositReceived: Money?,
    val depositDueOn: LocalDate?,
    val folioId: UUID,
) {
    companion object {
        fun of(item: PendingGuarantee) =
            PendingGuaranteeResponse(
                id = item.reservationId.value,
                code = item.code.toString(),
                bookingId = item.bookingId.value,
                holderGuestId = item.holderGuestId.value,
                kind = item.kind,
                status = item.status,
                dueAt = item.dueAt,
                overdue = item.overdue,
                provisionalUntil = item.provisionalUntil,
                roomTypeId = item.roomTypeId.value,
                ratePlanId = item.ratePlanId.value,
                arrival = item.arrival,
                departure = item.departure,
                total = item.total,
                guaranteeType = item.guaranteeTerms.type,
                guaranteePolicyId = item.guaranteeTerms.policyId.value,
                requiredDeposit = item.requiredDeposit,
                depositReceived = item.depositReceived,
                depositDueOn = item.depositDueOn,
                folioId = item.folioId.value,
            )
    }
}

@Schema(description = "Uma noite no retrato do histórico.")
data class HistoryNightResponse(
    val date: LocalDate,
    val ratePlanId: UUID,
    val roomAmount: Money,
    val amount: Money,
    val overridden: Boolean,
)

@Schema(description = "A multa no retrato do histórico de um cancelamento.")
data class HistoryCancellationResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "Motivo do cancelamento. $UNKNOWN_VALUE")
    val reason: CancellationReason,
    val penaltyAmount: Money,
    val penaltyWaived: Boolean,
    val penaltyPostingId: UUID?,
)

@Schema(description = "O retrato de uma reserva no histórico (sem texto livre nem dado de hóspede).")
data class ReservationStateResponse(
    @field:ExtensibleEnum
    @field:Schema(description = "Situação da reserva. $UNKNOWN_VALUE")
    val status: ReservationStatus,
    val roomTypeId: UUID,
    val ratePlanId: UUID,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
    @field:ExtensibleEnum
    @field:Schema(description = "Segmento de mercado. $UNKNOWN_VALUE")
    val segment: MarketSegment,
    val hasSpecialRequests: Boolean,
    val nights: List<HistoryNightResponse>,
    val roomTotal: Money,
    val additionalPackagesTotal: Money,
    val total: Money,
    val cancellationPolicyId: UUID,
    val cancellationPolicyVersion: Long,
    val guaranteePolicyId: UUID,
    val guaranteePolicyVersion: Long,
    val cancellation: HistoryCancellationResponse?,
    @field:ExtensibleEnum
    @field:Schema(description = "A garantia recebida na confirmação da pré-reserva; nula sem ela. $UNKNOWN_VALUE")
    val guaranteeMethod: GuaranteeMethod?,
) {
    companion object {
        fun of(state: ReservationState) =
            ReservationStateResponse(
                status = state.status,
                roomTypeId = state.roomTypeId.value,
                ratePlanId = state.ratePlanId.value,
                arrival = state.arrival,
                departure = state.departure,
                adults = state.adults,
                childAges = state.childAges,
                segment = state.segment,
                hasSpecialRequests = state.hasSpecialRequests,
                nights =
                    state.nights.map {
                        HistoryNightResponse(it.date, it.ratePlanId.value, it.roomAmount, it.amount, it.overridden)
                    },
                roomTotal = state.roomTotal,
                additionalPackagesTotal = state.additionalPackagesTotal,
                total = state.total,
                cancellationPolicyId = state.cancellationPolicyId.value,
                cancellationPolicyVersion = state.cancellationPolicyVersion,
                guaranteePolicyId = state.guaranteePolicyId.value,
                guaranteePolicyVersion = state.guaranteePolicyVersion,
                cancellation =
                    state.cancellation?.let {
                        HistoryCancellationResponse(
                            it.reason,
                            it.penaltyAmount,
                            it.penaltyWaived,
                            it.penaltyPostingId?.value,
                        )
                    },
                guaranteeMethod = state.guaranteeMethod,
            )
    }
}

@Schema(
    description =
        "Uma entrada do histórico da reserva: o tipo, a data operacional, o instante, quem (nulo = sistema), a " +
            "aprovação de superior, os campos mudados e o retrato antes e depois, com a versão depois.",
)
data class ReservationHistoryResponse(
    val id: UUID,
    @field:ExtensibleEnum
    @field:Schema(
        description =
            "`CHANGE`, `CANCEL` (inclusive a expiração da pré-reserva, com o motivo `EXPIRED`), `REACTIVATE`, " +
                "`CONFIRM` (a confirmação da pré-reserva com a garantia) ou `GUARANTEE` (a garantia registrada numa " +
                "reserva confirmada sem ela). $UNKNOWN_VALUE",
    )
    val kind: ReservationHistoryKind,
    val businessDate: LocalDate,
    val occurredAt: Instant,
    val actorId: UUID?,
    val approvalId: UUID?,
    val approvedBy: UUID?,
    val changedFields: List<String>,
    val before: ReservationStateResponse,
    val after: ReservationStateResponse,
    val version: Long,
) {
    companion object {
        fun of(entry: ReservationHistoryEntry) =
            ReservationHistoryResponse(
                id = entry.id.value,
                kind = entry.kind,
                businessDate = entry.businessDate,
                occurredAt = entry.occurredAt,
                actorId = entry.actorId?.value,
                approvalId = entry.approvalId,
                approvedBy = entry.approvedBy?.value,
                changedFields = entry.changedFields,
                before = ReservationStateResponse.of(entry.before),
                after = ReservationStateResponse.of(entry.after),
                version = entry.version,
            )
    }
}

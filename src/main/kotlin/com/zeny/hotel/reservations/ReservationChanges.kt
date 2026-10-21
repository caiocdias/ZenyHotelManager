package com.zeny.hotel.reservations

import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A alteração de uma reserva (M4 E08; RF-RES-05): a estadia inteira como deve ficar (tipo de UH, plano, chegada,
 * saída, adultos e a idade de cada criança na chegada), o segmento, os pedidos especiais (texto livre, até 1000; nulo
 * apaga) e as diárias editadas ([nightlyRates], só nas noites que a alteração recalcula). As noites mantidas guardam o
 * preço congelado (RN-14).
 */
data class ReservationChange(
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int> = emptyList(),
    val segment: MarketSegment,
    val specialRequests: String? = null,
    val nightlyRates: List<NightlyRateOverride> = emptyList(),
)

/**
 * Por que a reserva foi cancelada (RF-RES-06), lista que cresce (a expiração da pré-reserva entra na E09; o channel
 * manager no M8): a pedido do hóspede, pelo hotel, reserva em duplicidade, garantia não recebida ou outro. Nas
 * respostas e no evento é `x-extensible-enum`.
 */
enum class CancellationReason {
    GUEST_REQUEST,
    HOTEL_REQUEST,
    DUPLICATE,
    NO_GUARANTEE,
    OTHER,
}

/**
 * O pedido de cancelamento: o [reason], uma observação opcional ([notes], texto livre até 500 caracteres, que fica só
 * na reserva: nunca vai à trilha, ao histórico, a eventos nem a log) e se a multa devida deve ser **isentada**
 * ([waivePenalty]: ação sensível pela API HTTP, `reservations.penalties.waive`).
 */
data class ReservationCancellationRequest(
    val reason: CancellationReason,
    val notes: String? = null,
    val waivePenalty: Boolean = false,
)

/**
 * A multa de um cancelamento (RF-TAR-07, RN-15, H15): o prazo ([deadline], o horário de check-in da data de chegada,
 * no fuso do hotel, menos as horas da política copiada), o valor devido ([amount]: zero no cancelamento antes do
 * prazo, ou quando os cancelamentos anteriores da mesma reserva já lançaram a multa inteira), se foi isentada
 * ([waived], com a aprovação de superior quando houve) e o lançamento na conta da reserva ([postingId], no código
 * `CANCELLATION_FEE`; nulo sem multa lançada).
 */
data class CancellationPenalty(
    val deadline: Instant,
    val amount: Money,
    val waived: Boolean,
    val postingId: PostingId?,
    val waiverApprovalId: UUID?,
    val waiverApprovedBy: UserId?,
)

/** O cancelamento corrente de uma reserva cancelada: o motivo, a observação, quando, por quem, a situação anterior. */
data class ReservationCancellation(
    val reason: CancellationReason,
    val notes: String?,
    val previousStatus: ReservationStatus,
    val cancelledAt: Instant,
    val cancelledBy: UserId?,
    val penalty: CancellationPenalty,
) {
    override fun toString(): String = "ReservationCancellation(reason=$reason, previousStatus=$previousStatus)"
}

/** Id de uma entrada do histórico de uma reserva. */
@JvmInline
value class ReservationHistoryId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ReservationHistoryId = ReservationHistoryId(UuidV7.generate())
    }
}

/** O que uma entrada do histórico registra: uma alteração, um cancelamento ou uma reativação. */
enum class ReservationHistoryKind {
    CHANGE,
    CANCEL,
    REACTIVATE,
}

/** Uma noite no retrato do histórico: a data, o plano, a diária gravada, o total e se foi editada. */
data class HistoryNight(
    val date: LocalDate,
    val ratePlanId: RatePlanId,
    val roomAmount: Money,
    val amount: Money,
    val overridden: Boolean,
)

/** A multa no retrato do histórico (sem a observação do cancelamento). */
data class HistoryCancellation(
    val reason: CancellationReason,
    val penaltyAmount: Money,
    val penaltyWaived: Boolean,
    val penaltyPostingId: PostingId?,
)

/**
 * O retrato de uma reserva no histórico (H10 do M3): a situação, a estadia, o segmento, as noites com o preço, os
 * totais, as políticas copiadas pelo id e a versão e, cancelada, o motivo e a multa. Sem texto livre (os pedidos
 * especiais só como [hasSpecialRequests]) nem dado de hóspede.
 */
data class ReservationState(
    val status: ReservationStatus,
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
    val segment: MarketSegment,
    val hasSpecialRequests: Boolean,
    val nights: List<HistoryNight>,
    val roomTotal: Money,
    val additionalPackagesTotal: Money,
    val total: Money,
    val cancellationPolicyId: CancellationPolicyId,
    val cancellationPolicyVersion: Long,
    val guaranteePolicyId: GuaranteePolicyId,
    val guaranteePolicyVersion: Long,
    val cancellation: HistoryCancellation?,
)

/**
 * Uma entrada do histórico de uma reserva (RF-RES-05; *append-only*): o tipo, a data operacional, o instante, quem
 * ([actorId]; nulo = sistema), a aprovação de superior (se houve), os campos mudados ([changedFields], nomes dos
 * campos da reserva) e o retrato antes e depois, com a versão da reserva depois.
 */
data class ReservationHistoryEntry(
    val id: ReservationHistoryId,
    val reservationId: ReservationId,
    val kind: ReservationHistoryKind,
    val businessDate: LocalDate,
    val occurredAt: Instant,
    val actorId: UserId?,
    val approvalId: UUID?,
    val approvedBy: UserId?,
    val changedFields: List<String>,
    val before: ReservationState,
    val after: ReservationState,
    val version: Long,
)

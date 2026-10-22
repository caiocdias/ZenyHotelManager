package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SalesChannel
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Id de uma reserva agrupadora (`Booking`, RF-RES-03): a compra com uma ou mais reservas. */
@JvmInline
value class BookingId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): BookingId = BookingId(UuidV7.generate())
    }
}

/** Id de uma reserva (uma UH num período). */
@JvmInline
value class ReservationId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ReservationId = ReservationId(UuidV7.generate())
    }
}

/**
 * Situação da reserva (RF-RES-02, SPEC §8.4). A criação (M4 E07) faz [PROVISIONAL] (pré-reserva, com validade) ou
 * [CONFIRMED]; o cancelamento ([CANCELLED]) e a reativação (de volta a [CONFIRMED]) entram na E08; a confirmação da
 * pré-reserva com a garantia e a expiração dela (cancelada com o motivo `EXPIRED`), na E09; o check-in
 * ([IN_HOUSE], desfeito de volta a [CONFIRMED]) e o check-out ([CHECKED_OUT]) no M5 E01; o no-show no M6. Nas respostas
 * é `x-extensible-enum`.
 */
enum class ReservationStatus {
    PROVISIONAL,
    CONFIRMED,
    IN_HOUSE,
    CHECKED_OUT,
    CANCELLED,
    NO_SHOW,
    ;

    /** Estadia ativa (H12): a reserva ainda vai acontecer ou está acontecendo. */
    val active: Boolean get() = this == PROVISIONAL || this == CONFIRMED || this == IN_HOUSE
}

/**
 * Por onde a reserva chegou (RF-RES-01, "origem"; SPEC §8.2.6 `source`), e o canal de venda que ela implica
 * ([channel]: o plano precisa ser vendido nele, RF-TAR-01). Balcão, telefone, e-mail, WhatsApp e outros são venda
 * direta; o site é o canal do site; a OTA, o do channel manager (M8, que pode acrescentar origens por OTA). Nas
 * respostas é `x-extensible-enum`.
 */
enum class ReservationOrigin(
    val channel: SalesChannel,
) {
    FRONT_DESK(SalesChannel.DIRECT),
    PHONE(SalesChannel.DIRECT),
    EMAIL(SalesChannel.DIRECT),
    WHATSAPP(SalesChannel.DIRECT),
    WEBSITE(SalesChannel.WEBSITE),
    OTA(SalesChannel.OTA),
    OTHER(SalesChannel.DIRECT),
}

/**
 * Segmento de mercado da reserva (RF-RES-01: lazer, negócios, evento; RF-REL-04), lista fixa (decisão do implementador
 * na E07, pendente de confirmação), com [OTHER] para o que não se encaixa. Nas respostas é `x-extensible-enum`.
 */
enum class MarketSegment {
    LEISURE,
    BUSINESS,
    EVENT,
    OTHER,
}

/**
 * Uma reserva agrupadora a criar (`ReservationsApi.createBooking` e `POST /api/v1/bookings`): o titular (um perfil do
 * `guests`), a origem (que define o canal), o id externo opcional (o número da OTA, M8) e de 1 a 30 reservas.
 */
data class NewBooking(
    val holderGuestId: GuestId,
    val origin: ReservationOrigin,
    val externalId: String? = null,
    val reservations: List<NewReservation>,
)

/**
 * Uma reserva a criar: o tipo de UH, o plano, a chegada e a saída (iguais = day use, H5), os adultos e a idade de cada
 * criança na chegada, o segmento, os pedidos especiais (texto livre, até 1000), os hóspedes da estadia (vazio = o
 * titular como principal; senão, exatamente um principal) e as diárias editadas ([nightlyRates], só as que mudam).
 */
data class NewReservation(
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int> = emptyList(),
    val segment: MarketSegment,
    val specialRequests: String? = null,
    val guests: List<NewStayGuest> = emptyList(),
    val nightlyRates: List<NightlyRateOverride> = emptyList(),
)

/** Um hóspede da estadia: o perfil e se é o principal. */
data class NewStayGuest(
    val guestId: GuestId,
    val primary: Boolean = false,
)

/** A diária gravada numa noite em vez da calculada (RF-RES-01): menor que a calculada é desconto (H7). */
data class NightlyRateOverride(
    val date: LocalDate,
    val roomAmount: Money,
)

/** Os termos da política de cancelamento copiados na reserva (RN-15): a edição da política depois não os muda. */
data class CancellationTerms(
    val policyId: CancellationPolicyId,
    val name: String,
    val version: Long,
    val deadlineHours: Int,
    val penaltyType: CancellationPenaltyType,
    val penaltyPercent: Percent?,
    val penaltyNights: Int?,
    val penaltyAmount: Money?,
)

/** Os termos da política de garantia copiados na reserva (RN-15). */
data class GuaranteeTerms(
    val policyId: GuaranteePolicyId,
    val name: String,
    val version: Long,
    val type: GuaranteeType,
    val depositPercent: Percent?,
    val dueDaysBefore: Int?,
) {
    /**
     * O depósito que a política exige da reserva de [total] (M4 E09; RF-TAR-07 "depósito de X%"): o percentual sobre o
     * total da reserva (as diárias e os pacotes adicionais), pela regra única de `Money`; nulo fora de `DEPOSIT`.
     */
    fun requiredDeposit(total: Money): Money? = if (type == GuaranteeType.DEPOSIT) depositPercent?.of(total) else null

    /** Até quando a política pede o depósito ("até N dias antes" da [arrival]); nulo fora de `DEPOSIT`. */
    fun depositDueOn(arrival: LocalDate): LocalDate? =
        if (type == GuaranteeType.DEPOSIT) dueDaysBefore?.let { arrival.minusDays(it.toLong()) } else null
}

/**
 * O preço congelado de uma noite (RN-14; no day use, a própria data): a diária calculada na criação
 * ([calculatedRoomAmount]), a gravada ([roomAmount]; [overridden] quando difere, com [discountPercent] quando é menor),
 * os pacotes inclusos (parte da diária) e os adicionais, com o detalhe de cada pacote ([packages], para o lançamento no
 * check-in e no fechamento), e o total da noite ([amount] = diária + adicionais).
 */
data class NightlyRateSummary(
    val date: LocalDate,
    val ratePlanId: RatePlanId,
    val calculatedRoomAmount: Money,
    val roomAmount: Money,
    val includedPackagesAmount: Money,
    val additionalPackagesAmount: Money,
    val amount: Money,
    val overridden: Boolean,
    val discountPercent: Percent?,
    val packages: List<PackageCharge>,
)

/**
 * Um hóspede da estadia (`StayGuest`). Desde o M5 E01, com os dados de check-in (H10): o motivo de viagem e o meio de
 * transporte (códigos opacos da FNRH), os instantes de entrada e de saída e a comprovação do menor (RN-24); nulos antes
 * do check-in (e limpos quando ele é desfeito).
 */
data class StayGuestSummary(
    val guestId: GuestId,
    val primary: Boolean,
    val travelReason: String? = null,
    val transportMode: String? = null,
    val checkedInAt: Instant? = null,
    val checkedOutAt: Instant? = null,
    val minorAuthorization: MinorAuthorization? = null,
)

/** Uma reserva como os outros módulos a veem (M5 check-in, M6 fechamento, M8 channel manager). */
data class ReservationSummary(
    val id: ReservationId,
    val code: DocumentNumber,
    val bookingId: BookingId,
    val status: ReservationStatus,
    val provisionalUntil: Instant?,
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    /**
     * A categoria do plano da reserva (SPEC Q-15: cortesia conta na ocupação e não no ADR; uso da casa em nenhum dos
     * dois), copiada do `rates` na criação e na troca de plano (M4 E12): a categoria de um plano é fixa desde a
     * criação, e as views de relatório do `reservations` a leem daqui, sem ler o schema do `rates`.
     */
    val ratePlanCategory: RatePlanCategory,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
    val segment: MarketSegment,
    val specialRequests: String?,
    val guests: List<StayGuestSummary>,
    val nightlyRates: List<NightlyRateSummary>,
    val roomTotal: Money,
    val additionalPackagesTotal: Money,
    val total: Money,
    val cancellationTerms: CancellationTerms,
    val guaranteeTerms: GuaranteeTerms,
    val folioId: FolioId,
    val approvalId: UUID?,
    val approvedBy: UserId?,
    val version: Long,
    val createdAt: Instant,
    val createdBy: UserId?,
    /** O cancelamento corrente (M4 E08); só numa reserva cancelada. */
    val cancellation: ReservationCancellation? = null,
    /**
     * O total das multas de cancelamento já lançadas na conta desta reserva (M4 E08): um cancelamento depois de uma
     * reativação lança só o que falta para a multa da política, nunca a multa de novo.
     */
    val penaltiesCharged: Money = Money.ZERO,
    /** A garantia registrada na confirmação da pré-reserva (M4 E09); nula na reserva que nunca foi pré-reserva. */
    val guarantee: ReservationGuarantee? = null,
    /**
     * A UH alocada (M4 E10; RF-RES-04): a alocação ativa, com "não mover" e a marca de realocação; nula sem UH alocada
     * (a alocação é opcional até o check-in).
     */
    val roomAssignment: RoomAssignmentSummary? = null,
    /**
     * O bloqueio de grupo de onde a reserva veio pelo pick-up (M4 E11; RF-RES-09); nulo na reserva comum. A diária é a
     * tarifa do grupo congelada, e a estadia (tipo, plano e datas) não muda depois.
     */
    val blockId: RoomBlockId? = null,
    /** O check-in (M5 E01): só numa reserva hospedada ou encerrada (desfeito, volta a nulo). */
    val checkIn: StayTransitionRecord? = null,
    /** O check-out (M5 E01): só numa reserva encerrada. */
    val checkOut: StayTransitionRecord? = null,
) {
    val dayUse: Boolean get() = arrival == departure

    /** O depósito que a política copiada exige desta reserva (E09); nulo fora de `DEPOSIT`. */
    val requiredDeposit: Money? get() = guaranteeTerms.requiredDeposit(total)

    override fun toString(): String = "ReservationSummary(id=$id, code=$code, status=$status)"
}

/** Uma reserva agrupadora com as reservas, em ordem de criação. */
data class BookingSummary(
    val id: BookingId,
    val code: DocumentNumber,
    val holderGuestId: GuestId,
    val origin: ReservationOrigin,
    val channel: SalesChannel,
    val externalId: String?,
    val reservations: List<ReservationSummary>,
    val version: Long,
    val createdAt: Instant,
    val createdBy: UserId?,
)

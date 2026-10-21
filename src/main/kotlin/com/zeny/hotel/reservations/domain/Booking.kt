package com.zeny.hotel.reservations.domain

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.StayPrice
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.NewReservation
import com.zeny.hotel.reservations.NewStayGuest
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.sum
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * As regras de formato de uma reserva agrupadora (M4 E07; RF-RES-01 e 03): de 1 a [MAX_RESERVATIONS] reservas, id
 * externo de 1 a [MAX_EXTERNAL_ID] caracteres, até [MAX_ADULTS] adultos e [MAX_CHILDREN] crianças por reserva, pedidos
 * especiais até [MAX_SPECIAL_REQUESTS] caracteres, hóspedes sem repetir, com exatamente um principal e no máximo a
 * ocupação, e diárias editadas só nas noites da estadia, sem repetir, de 0,00 a [MAX_ROOM_AMOUNT]. Junta todas as
 * recusas (`400 BOOKING_INVALID` ou `NIGHTLY_RATE_INVALID`, a primeira dá o código) com o campo de cada uma.
 */
object BookingRules {
    const val MAX_RESERVATIONS = 30
    const val MAX_EXTERNAL_ID = 60
    const val MAX_ADULTS = 40
    const val MAX_CHILDREN = 20
    const val MAX_SPECIAL_REQUESTS = 1000
    val MAX_ROOM_AMOUNT: Money = Money.of("1000000.00")

    fun requireValid(booking: NewBooking) {
        val violations = mutableListOf<FieldViolation>()

        fun violation(
            code: String,
            field: String,
            message: String,
        ) {
            violations += FieldViolation(field, code, message)
        }
        val invalid = ReservationsErrorCodes.BOOKING_INVALID.value
        if (booking.reservations.isEmpty() || booking.reservations.size > MAX_RESERVATIONS) {
            violation(invalid, "reservations", "Informe de 1 a $MAX_RESERVATIONS reservas.")
        }
        booking.externalId?.let {
            if (it.isBlank() || it.length > MAX_EXTERNAL_ID || it.trim() != it) {
                violation(
                    invalid,
                    "externalId",
                    "O id externo tem de 1 a $MAX_EXTERNAL_ID caracteres, sem espaços nas pontas.",
                )
            }
        }
        booking.reservations.forEachIndexed { index, reservation ->
            reservationViolations(reservation, "reservations[$index]").forEach { violations += it }
        }
        if (violations.isNotEmpty()) {
            val code =
                if (violations.first().code == ReservationsErrorCodes.NIGHTLY_RATE_INVALID.value) {
                    ReservationsErrorCodes.NIGHTLY_RATE_INVALID
                } else {
                    ReservationsErrorCodes.BOOKING_INVALID
                }
            throw InvalidInputException(code, violations.first().message, violations)
        }
    }

    private fun reservationViolations(
        reservation: NewReservation,
        path: String,
    ): List<FieldViolation> =
        buildList {
            val invalid = ReservationsErrorCodes.BOOKING_INVALID.value
            if (reservation.adults > MAX_ADULTS) {
                add(FieldViolation("$path.adults", invalid, "Informe até $MAX_ADULTS adultos."))
            }
            if (reservation.childAges.size > MAX_CHILDREN) {
                add(FieldViolation("$path.childAges", invalid, "Informe até $MAX_CHILDREN crianças."))
            }
            reservation.specialRequests?.let {
                if (it.isBlank() || it.length > MAX_SPECIAL_REQUESTS) {
                    add(
                        FieldViolation(
                            "$path.specialRequests",
                            invalid,
                            "Os pedidos especiais têm de 1 a $MAX_SPECIAL_REQUESTS caracteres.",
                        ),
                    )
                }
            }
            addAll(guestViolations(reservation, "$path.guests"))
            addAll(nightlyRateViolations(reservation, "$path.nightlyRates"))
        }

    private fun guestViolations(
        reservation: NewReservation,
        path: String,
    ): List<FieldViolation> {
        val guests = reservation.guests
        if (guests.isEmpty()) return emptyList()
        val invalid = ReservationsErrorCodes.BOOKING_INVALID.value
        return buildList {
            if (guests.map { it.guestId }.toSet().size != guests.size) {
                add(FieldViolation(path, invalid, "Um hóspede aparece mais de uma vez na reserva."))
            }
            if (guests.count { it.primary } != 1) {
                add(FieldViolation(path, invalid, "Indique exatamente um hóspede principal."))
            }
            val occupancy = reservation.adults + reservation.childAges.size
            if (guests.size > occupancy) {
                add(FieldViolation(path, invalid, "A reserva tem mais hóspedes (${guests.size}) que a ocupação."))
            }
        }
    }

    private fun nightlyRateViolations(
        reservation: NewReservation,
        path: String,
    ): List<FieldViolation> {
        val nights = Stays.nights(reservation.arrival, reservation.departure).toSet()
        val code = ReservationsErrorCodes.NIGHTLY_RATE_INVALID.value
        val seen = mutableSetOf<LocalDate>()
        return buildList {
            reservation.nightlyRates.forEachIndexed { index, override ->
                if (override.date !in nights) {
                    add(FieldViolation("$path[$index].date", code, "A data não é uma noite da estadia."))
                } else if (!seen.add(override.date)) {
                    add(FieldViolation("$path[$index].date", code, "A noite aparece mais de uma vez."))
                }
                if (override.roomAmount < Money.ZERO || override.roomAmount > MAX_ROOM_AMOUNT) {
                    add(FieldViolation("$path[$index].roomAmount", code, "Informe a diária de 0,00 a 1.000.000,00."))
                }
            }
        }
    }
}

/** As datas de uma estadia (H5). */
object Stays {
    /** As noites: da chegada à véspera da saída; no day use (chegada = saída), a própria data. */
    fun nights(
        arrival: LocalDate,
        departure: LocalDate,
    ): List<LocalDate> =
        when {
            departure.isBefore(arrival) -> emptyList()
            arrival == departure -> listOf(arrival)
            else -> arrival.datesUntil(departure).toList()
        }
}

/**
 * A situação inicial da reserva (SPEC §8.4, H15; decisão do implementador na E07, pendente de confirmação): sem
 * garantia exigida pela política (`NONE`), a reserva nasce **confirmada**; com garantia (cartão ou depósito, registrada
 * na E09), nasce **pré-reserva**, válida até o instante da criação mais a validade do hotel
 * (`HotelApi.operationalSettings().preReservationExpiry`).
 */
object InitialStatus {
    data class Outcome(
        val status: ReservationStatus,
        val provisionalUntil: Instant?,
    )

    fun of(
        guarantee: GuaranteeType,
        createdAt: Instant,
        expiry: Duration,
    ): Outcome =
        if (guarantee == GuaranteeType.NONE) {
            Outcome(ReservationStatus.CONFIRMED, null)
        } else {
            Outcome(ReservationStatus.PROVISIONAL, createdAt.plus(expiry))
        }
}

/**
 * O preço congelado de cada noite (RN-14; RF-RES-01; H7), a partir do preço calculado pela `RatesApi.priceFor`: a
 * diária gravada é a calculada, salvo a editada ([overrides]); a editada pode ficar acima (sem desconto) ou abaixo
 * (desconto, em percentual da diária calculada, arredondado **para cima** em 4 casas, para o limite `maxPercent` nunca
 * deixar passar um desconto um pouco maior), nunca abaixo dos pacotes inclusos na noite (a hospedagem ficaria negativa,
 * `422 NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES`). Os adicionais ficam como calculados; o total da noite é a diária mais os
 * adicionais.
 */
object NightlyRates {
    fun freeze(
        price: StayPrice,
        ratePlanId: RatePlanId,
        overrides: Map<LocalDate, Money>,
    ): List<NightlyRateSummary> {
        val below = mutableListOf<LocalDate>()
        val nights =
            price.nights.map { night ->
                val room = overrides[night.date] ?: night.roomAmount
                if (room < night.includedPackagesAmount) below += night.date
                NightlyRateSummary(
                    date = night.date,
                    ratePlanId = ratePlanId,
                    calculatedRoomAmount = night.roomAmount,
                    roomAmount = room,
                    includedPackagesAmount = night.includedPackagesAmount,
                    additionalPackagesAmount = night.additionalPackagesAmount,
                    amount = room + night.additionalPackagesAmount,
                    overridden = room != night.roomAmount,
                    discountPercent = discount(night.roomAmount, room),
                    packages = night.packages,
                )
            }
        if (below.isNotEmpty()) throw NightlyRateBelowIncludedPackagesException(below)
        return nights
    }

    /** O desconto da diária [recorded] sobre a [calculated], em percentual (4 casas, para cima); `null` se não há. */
    fun discount(
        calculated: Money,
        recorded: Money,
    ): Percent? {
        if (recorded >= calculated) return null
        val fraction =
            (calculated.amount - recorded.amount)
                .multiply(HUNDRED)
                .divide(calculated.amount, Percent.SCALE, RoundingMode.UP)
        return Percent.of(fraction)
    }

    /** O maior desconto das noites; `null` se nenhuma tem desconto. */
    fun maxDiscount(nights: Collection<NightlyRateSummary>): Percent? =
        nights.mapNotNull { it.discountPercent }.maxOrNull()

    /** Os totais das noites: as diárias, os adicionais e o total. */
    fun totals(nights: Collection<NightlyRateSummary>): Triple<Money, Money, Money> =
        Triple(
            nights.map { it.roomAmount }.sum(),
            nights.map { it.additionalPackagesAmount }.sum(),
            nights.map { it.amount }.sum(),
        )

    private val HUNDRED = BigDecimal(100)
}

/**
 * Os hóspedes de uma estadia depois de resolvidos os perfis mesclados (H8): cada id pedido vira o do sobrevivente; dois
 * pedidos que levam ao mesmo perfil ficam um só (principal se algum era). Sem hóspedes pedidos, o titular é o
 * principal.
 */
object StayGuests {
    fun resolve(
        requested: List<NewStayGuest>,
        holder: GuestId,
        resolved: (GuestId) -> GuestId,
    ): List<StayGuestSummary> {
        if (requested.isEmpty()) return listOf(StayGuestSummary(holder, primary = true))
        val byGuest = linkedMapOf<GuestId, Boolean>()
        requested.forEach { guest ->
            val id = resolved(guest.guestId)
            byGuest[id] = (byGuest[id] ?: false) || guest.primary
        }
        return byGuest.map { (id, primary) -> StayGuestSummary(id, primary) }
    }
}

/**
 * `422` com o código do primeiro motivo de recusa do `rates` (a estadia não é vendável no canal), a reserva em
 * `reservationIndex` e todos os motivos em `refusals`.
 */
class StayNotSellableException(
    val reservationIndex: Int,
    val refusals: List<SaleRefusal>,
) : BusinessRuleException(refusals.first().code, refusals.first().message) {
    override val problemProperties: Map<String, Any>
        get() =
            mapOf(
                "reservationIndex" to reservationIndex,
                "refusals" to
                    refusals.map { refusal ->
                        mapOf(
                            "code" to refusal.code.value,
                            "message" to refusal.message,
                            "dates" to refusal.dates.map { it.toString() },
                        )
                    },
            )
}

/** `422 NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES`, com as datas em `dates`. */
class NightlyRateBelowIncludedPackagesException(
    val dates: List<LocalDate>,
) : BusinessRuleException(
        ReservationsErrorCodes.NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES,
        "A diária editada fica abaixo dos pacotes inclusos na noite.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("dates" to dates.map { it.toString() }.distinct().sorted())
}

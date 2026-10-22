package com.zeny.hotel.reservations.domain

import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationCancellationRequest
import com.zeny.hotel.reservations.ReservationChange
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.sum
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** O que se pede a uma reserva depois de criada (SPEC §8.4). */
enum class ReservationAction {
    CHANGE,
    CANCEL,
    REACTIVATE,

    /** Confirmar a pré-reserva com a garantia (E09). */
    CONFIRM,

    /** Fazer o check-in (M5 E01). */
    CHECK_IN,

    /** Desfazer o check-in no mesmo dia (M5 E01). */
    UNDO_CHECK_IN,

    /** Fazer o check-out, inclusive antecipado (M5 E01). */
    CHECK_OUT,

    /** Trocar a UH do hospedado (M5 E01). */
    MOVE_ROOM,
}

/**
 * As transições da reserva (SPEC §8.4): alterar uma pré-reserva, uma confirmada ou, desde o M5 E01, uma hospedada (só a
 * saída, [InHouseChangeRules]); cancelar só uma pré-reserva ou uma confirmada (a encerrada, a cancelada e o no-show não
 * mudam); reativar só uma cancelada ou no-show, que volta a confirmada; desde a E09, confirmar só uma pré-reserva
 * ("garantia recebida"); desde o M5 E01, o check-in só de uma confirmada (a pré-reserva confirma antes), e desfazê-lo,
 * o check-out e a troca de UH só de uma hospedada. Fora disso, `409 RESERVATION_TRANSITION_NOT_ALLOWED`.
 */
object ReservationTransitions {
    private val allowed =
        mapOf(
            ReservationAction.CHANGE to
                setOf(ReservationStatus.PROVISIONAL, ReservationStatus.CONFIRMED, ReservationStatus.IN_HOUSE),
            ReservationAction.CANCEL to setOf(ReservationStatus.PROVISIONAL, ReservationStatus.CONFIRMED),
            ReservationAction.REACTIVATE to setOf(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW),
            ReservationAction.CONFIRM to setOf(ReservationStatus.PROVISIONAL),
            ReservationAction.CHECK_IN to setOf(ReservationStatus.CONFIRMED),
            ReservationAction.UNDO_CHECK_IN to setOf(ReservationStatus.IN_HOUSE),
            ReservationAction.CHECK_OUT to setOf(ReservationStatus.IN_HOUSE),
            ReservationAction.MOVE_ROOM to setOf(ReservationStatus.IN_HOUSE),
        )

    fun allows(
        action: ReservationAction,
        status: ReservationStatus,
    ): Boolean = status in allowed.getValue(action)

    fun require(
        action: ReservationAction,
        status: ReservationStatus,
    ) {
        if (!allows(action, status)) throw ReservationTransitionNotAllowedException(action, status)
    }
}

/** `409 RESERVATION_TRANSITION_NOT_ALLOWED`, com a situação em `reservationStatus` e a ação em `action`. */
class ReservationTransitionNotAllowedException(
    val action: ReservationAction,
    val status: ReservationStatus,
) : ConflictException(
        ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED,
        "A reserva ${STATUS_LABELS.getValue(status)} não pode ser ${ACTION_LABELS.getValue(action)}.",
    ) {
    override val problemProperties: Map<String, Any>
        get() = mapOf("reservationStatus" to status.name, "action" to action.name)

    private companion object {
        val STATUS_LABELS =
            mapOf(
                ReservationStatus.PROVISIONAL to "pré-reserva",
                ReservationStatus.CONFIRMED to "confirmada",
                ReservationStatus.IN_HOUSE to "hospedada",
                ReservationStatus.CHECKED_OUT to "encerrada",
                ReservationStatus.CANCELLED to "cancelada",
                ReservationStatus.NO_SHOW to "no-show",
            )
        val ACTION_LABELS =
            mapOf(
                ReservationAction.CHANGE to "alterada",
                ReservationAction.CANCEL to "cancelada",
                ReservationAction.REACTIVATE to "reativada",
                ReservationAction.CONFIRM to "confirmada",
                ReservationAction.CHECK_IN to "hospedada (check-in)",
                ReservationAction.UNDO_CHECK_IN to "voltar a confirmada (desfazer o check-in)",
                ReservationAction.CHECK_OUT to "encerrada (check-out)",
                ReservationAction.MOVE_ROOM to "trocada de UH como hospedada",
            )
    }
}

/** A forma de uma estadia: o que decide o preço de cada noite e o inventário. */
data class StayShape(
    val roomTypeId: RoomTypeId,
    val ratePlanId: RatePlanId,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val childAges: List<Int>,
) {
    val dayUse: Boolean get() = arrival == departure

    val nights: List<LocalDate> get() = Stays.nights(arrival, departure)

    companion object {
        fun of(reservation: ReservationSummary) =
            StayShape(
                reservation.roomTypeId,
                reservation.ratePlanId,
                reservation.arrival,
                reservation.departure,
                reservation.adults,
                reservation.childAges,
            )

        fun of(change: ReservationChange) =
            StayShape(
                change.roomTypeId,
                change.ratePlanId,
                change.arrival,
                change.departure,
                change.adults,
                change.childAges,
            )
    }
}

/**
 * O que uma alteração recalcula (RF-RES-05, RN-14 "congelado na criação ou alteração"; decisão do implementador na
 * E08, pendente de confirmação):
 * - trocar o tipo de UH, o plano, a ocupação (adultos ou idades) ou passar de pernoite a day use (ou o contrário)
 *   recalcula **todas** as noites: o preço de cada uma depende disso;
 * - mudar só as datas recalcula só as noites **novas** (as que a estadia anterior não tinha); as mantidas guardam o
 *   preço congelado, inclusive a diária editada;
 * - com a chegada mudada, a noite da chegada nova e a da chegada antiga (se continua na estadia) também são
 *   recalculadas **quando a noite da chegada antiga tinha um pacote por estadia** (`PER_STAY`, que vai na noite da
 *   chegada): o pacote passa de uma à outra.
 */
object StayRepricing {
    fun affectedNights(
        old: StayShape,
        new: StayShape,
        oldArrivalHasPerStayPackage: Boolean,
    ): List<LocalDate> {
        val newNights = new.nights
        val reshaped =
            old.roomTypeId != new.roomTypeId ||
                old.ratePlanId != new.ratePlanId ||
                old.adults != new.adults ||
                old.childAges != new.childAges ||
                old.dayUse != new.dayUse
        if (reshaped || new.dayUse) return if (old == new) emptyList() else newNights
        val oldNights = old.nights.toSet()
        val affected = newNights.filterTo(sortedSetOf()) { it !in oldNights }
        if (old.arrival != new.arrival && oldArrivalHasPerStayPackage) {
            affected += new.arrival
            if (old.arrival in newNights) affected += old.arrival
        }
        return affected.toList()
    }

    /** As noites afetadas em sequências contínuas: cada uma é precificada como uma estadia (de, até a véspera de). */
    fun runs(nights: List<LocalDate>): List<Pair<LocalDate, LocalDate>> {
        val runs = mutableListOf<Pair<LocalDate, LocalDate>>()
        nights.sorted().forEach { night ->
            val last = runs.lastOrNull()
            if (last != null && last.second == night) {
                runs[runs.lastIndex] = last.first to night.plusDays(1)
            } else {
                runs += night to night.plusDays(1)
            }
        }
        return runs
    }

    /**
     * A noite precificada numa sequência que não começa na chegada da estadia: o `rates` pôs nela os pacotes por
     * estadia (que só valem na noite da chegada), aqui retirados, com os inclusos de volta à hospedagem e os
     * adicionais fora do total.
     */
    fun withoutPerStayPackages(night: NightPrice): NightPrice {
        val removed = night.packages.filter { it.pricing == PackagePricing.PER_STAY }
        if (removed.isEmpty()) return night
        val included = removed.filter { it.includedInRate }.map { it.amount }.sum()
        val additional = removed.filterNot { it.includedInRate }.map { it.amount }.sum()
        return night.copy(
            packages = night.packages - removed.toSet(),
            includedPackagesAmount = night.includedPackagesAmount - included,
            accommodationAmount = night.accommodationAmount + included,
            additionalPackagesAmount = night.additionalPackagesAmount - additional,
            total = night.total - additional,
        )
    }

    /** As noites da estadia nova: as mantidas como estavam e as recalculadas, em ordem de data. */
    fun merge(
        kept: List<NightlyRateSummary>,
        repriced: List<NightlyRateSummary>,
        nights: List<LocalDate>,
    ): List<NightlyRateSummary> {
        val byDate = kept.associateBy { it.date } + repriced.associateBy { it.date }
        return nights.map { checkNotNull(byDate[it]) { "Noite sem preço na alteração: $it" } }
    }

    /**
     * Os motivos de "não vendável" que dizem respeito ao que a alteração mudou (decisão do implementador na E08,
     * pendente de confirmação): uma regra que valia na venda e não mudou não recusa a alteração (um plano desativado
     * depois não impede mudar a ocupação; uma restrição de antecedência não impede estender a saída). Na ordem:
     * antecedência, chegada antes da data operacional e CTA só com a chegada mudada; CTD só com a saída mudada;
     * estadia mínima e máxima com alguma das duas; plano desativado e canal só com o plano mudado; tipo fora do plano
     * ou desativado com o tipo ou o plano mudados; ocupação acima do tipo com a ocupação ou o tipo mudados; as noites
     * fechadas e sem preço só nas noites [affected] (as datas da recusa ficam só as afetadas). Qualquer outro motivo
     * conta.
     */
    fun relevantRefusals(
        refusals: List<SaleRefusal>,
        old: StayShape,
        new: StayShape,
        affected: Collection<LocalDate>,
    ): List<SaleRefusal> {
        val change = Change(old, new)
        val affectedSet = affected.toSet()
        return refusals.mapNotNull { refusal ->
            if (refusal.code in NIGHT_RULES) {
                nightRefusal(refusal, affectedSet)
            } else {
                refusal.takeIf { concerns(refusal.code, change) }
            }
        }
    }

    /** O que mudou da estadia antiga para a nova. */
    private class Change(
        old: StayShape,
        new: StayShape,
    ) {
        val arrival = old.arrival != new.arrival
        val departure = old.departure != new.departure
        val plan = old.ratePlanId != new.ratePlanId
        val type = old.roomTypeId != new.roomTypeId
        val occupancy = old.adults != new.adults || old.childAges != new.childAges
    }

    private fun concerns(
        code: ErrorCode,
        change: Change,
    ): Boolean =
        when (code) {
            in ARRIVAL_RULES -> change.arrival
            RatesErrorCodes.CLOSED_TO_DEPARTURE -> change.departure
            in LENGTH_RULES -> change.arrival || change.departure
            in PLAN_RULES -> change.plan
            in TYPE_RULES -> change.plan || change.type
            RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE -> change.occupancy || change.type
            else -> true
        }

    /** Uma recusa por noite: só nas noites afetadas (sem datas, conta). */
    private fun nightRefusal(
        refusal: SaleRefusal,
        affected: Set<LocalDate>,
    ): SaleRefusal? {
        if (refusal.dates.isEmpty()) return refusal
        return refusal.dates
            .filter { it in affected }
            .takeIf { it.isNotEmpty() }
            ?.let { refusal.copy(dates = it) }
    }

    private val ARRIVAL_RULES =
        setOf(
            RatesErrorCodes.ARRIVAL_BEFORE_BUSINESS_DATE,
            RatesErrorCodes.MIN_ADVANCE_NOT_MET,
            RatesErrorCodes.MAX_ADVANCE_EXCEEDED,
            RatesErrorCodes.CLOSED_TO_ARRIVAL,
        )
    private val LENGTH_RULES = setOf(RatesErrorCodes.MIN_STAY_NOT_MET, RatesErrorCodes.MAX_STAY_EXCEEDED)
    private val PLAN_RULES = setOf(RatesErrorCodes.RATE_PLAN_INACTIVE, RatesErrorCodes.SALES_CHANNEL_NOT_ALLOWED)
    private val TYPE_RULES = setOf<ErrorCode>(RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN, RoomsErrorCodes.ROOM_TYPE_INACTIVE)
    private val NIGHT_RULES =
        setOf(
            RatesErrorCodes.STAY_DATE_CLOSED,
            RatesErrorCodes.RATE_NOT_PRICED,
            RatesErrorCodes.OCCUPANCY_NOT_PRICED,
            RatesErrorCodes.CHILD_AGE_NOT_PRICED,
            RatesErrorCodes.INCLUDED_PACKAGES_EXCEED_RATE,
        )
}

/**
 * Os campos da reserva que uma alteração mudou, com os nomes do contrato (o histórico, a trilha e o
 * `ReservationChanged`).
 */
object ReservationFields {
    fun changed(
        before: ReservationSummary,
        after: ReservationSummary,
    ): List<String> =
        buildList {
            if (before.roomTypeId != after.roomTypeId) add("roomTypeId")
            if (before.ratePlanId != after.ratePlanId) add("ratePlanId")
            if (before.arrival != after.arrival) add("arrival")
            if (before.departure != after.departure) add("departure")
            if (before.adults != after.adults) add("adults")
            if (before.childAges != after.childAges) add("childAges")
            if (before.segment != after.segment) add("segment")
            if (before.specialRequests != after.specialRequests) add("specialRequests")
            if (before.nightlyRates != after.nightlyRates) add("nightlyRates")
            if (before.cancellationTerms != after.cancellationTerms) add("cancellationTerms")
            if (before.guaranteeTerms != after.guaranteeTerms) add("guaranteeTerms")
            if (before.status != after.status) add("status")
        }
}

/**
 * O formato de uma alteração (`400 RESERVATION_CHANGE_INVALID` ou `NIGHTLY_RATE_INVALID`, com o campo): até
 * [BookingRules.MAX_ADULTS] adultos e [BookingRules.MAX_CHILDREN] crianças, pedidos especiais até
 * [BookingRules.MAX_SPECIAL_REQUESTS], ocupação não menor que os hóspedes da estadia ([guestCount]) e diárias editadas
 * só nas noites que a alteração recalcula ([repriced]), sem repetir, de 0,00 a [BookingRules.MAX_ROOM_AMOUNT].
 */
object ReservationChangeRules {
    fun requireValid(
        change: ReservationChange,
        guestCount: Int,
    ) {
        val code = ReservationsErrorCodes.RESERVATION_CHANGE_INVALID
        val violations = mutableListOf<FieldViolation>()
        if (change.adults > BookingRules.MAX_ADULTS) {
            violations += FieldViolation("adults", code.value, "Informe até ${BookingRules.MAX_ADULTS} adultos.")
        }
        if (change.childAges.size > BookingRules.MAX_CHILDREN) {
            violations +=
                FieldViolation("childAges", code.value, "Informe até ${BookingRules.MAX_CHILDREN} crianças.")
        }
        change.specialRequests?.let {
            if (it.isBlank() || it.length > BookingRules.MAX_SPECIAL_REQUESTS) {
                violations +=
                    FieldViolation(
                        "specialRequests",
                        code.value,
                        "Os pedidos especiais têm de 1 a ${BookingRules.MAX_SPECIAL_REQUESTS} caracteres.",
                    )
            }
        }
        if (change.adults + change.childAges.size < guestCount) {
            violations +=
                FieldViolation("adults", code.value, "A estadia tem $guestCount hóspedes, mais que a ocupação pedida.")
        }
        if (violations.isNotEmpty()) throw InvalidInputException(code, violations.first().message, violations)
    }

    fun requireOverridesOn(
        change: ReservationChange,
        repriced: Collection<LocalDate>,
    ) {
        val code = ReservationsErrorCodes.NIGHTLY_RATE_INVALID.value
        val nights = repriced.toSet()
        val seen = mutableSetOf<LocalDate>()
        val violations =
            buildList {
                change.nightlyRates.forEachIndexed { index, override ->
                    if (override.date !in nights) {
                        add(
                            FieldViolation(
                                "nightlyRates[$index].date",
                                code,
                                "A noite não é recalculada por esta alteração (o preço dela fica congelado).",
                            ),
                        )
                    } else if (!seen.add(override.date)) {
                        add(FieldViolation("nightlyRates[$index].date", code, "A noite aparece mais de uma vez."))
                    }
                    if (override.roomAmount < Money.ZERO || override.roomAmount > BookingRules.MAX_ROOM_AMOUNT) {
                        add(
                            FieldViolation(
                                "nightlyRates[$index].roomAmount",
                                code,
                                "Informe a diária de 0,00 a 1.000.000,00.",
                            ),
                        )
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(
                ReservationsErrorCodes.NIGHTLY_RATE_INVALID,
                violations.first().message,
                violations,
            )
        }
    }
}

/**
 * O formato de um cancelamento: a observação de 1 a [MAX_NOTES] caracteres (`400`, campo `notes`) e nunca o motivo
 * `EXPIRED`, que é só do job de expiração (E09; `400`, campo `reason`).
 */
object CancellationRules {
    const val MAX_NOTES = 500

    fun requireValid(request: ReservationCancellationRequest) {
        if (request.reason == CancellationReason.EXPIRED) {
            val code = ReservationsErrorCodes.RESERVATION_CANCELLATION_INVALID
            val message = "O motivo \"expirada\" é só da expiração automática da pré-reserva."
            throw InvalidInputException(code, message, listOf(FieldViolation("reason", code.value, message)))
        }
        val notes = request.notes ?: return
        if (notes.isBlank() || notes.length > MAX_NOTES) {
            val code = ReservationsErrorCodes.RESERVATION_CANCELLATION_INVALID
            val message = "A observação do cancelamento tem de 1 a $MAX_NOTES caracteres."
            throw InvalidInputException(code, message, listOf(FieldViolation("notes", code.value, message)))
        }
    }
}

/**
 * A multa de cancelamento pela política **copiada na reserva** (RF-TAR-07, RN-15, H15; o cálculo e as bases são
 * decisões do implementador na E08, pendentes de confirmação):
 * - o prazo é o horário de check-in da data de chegada, no fuso do hotel, menos as horas da política; cancelar
 *   **antes** dele não tem multa, e no instante do prazo ou depois tem;
 * - `PERCENT_OF_STAY`: o percentual das diárias da estadia ([ReservationSummary.roomTotal]: as diárias congeladas, com
 *   os inclusos; os pacotes adicionais, que não serão consumidos, ficam fora), pela regra única de `Money`;
 * - `NIGHTS`: a soma das diárias congeladas das primeiras N noites (no máximo todas);
 * - `FIXED_AMOUNT`: o valor da política.
 *
 * Devido = a multa menos o que cancelamentos anteriores da mesma reserva já lançaram (uma reserva reativada e cancelada
 * de novo nunca paga a multa duas vezes), nunca negativo.
 */
object CancellationPenalties {
    fun deadline(
        arrival: LocalDate,
        checkInTime: LocalTime,
        zone: ZoneId,
        deadlineHours: Int,
    ): Instant =
        arrival
            .atTime(checkInTime)
            .atZone(zone)
            .minusHours(deadlineHours.toLong())
            .toInstant()

    fun applies(
        now: Instant,
        deadline: Instant,
    ): Boolean = !now.isBefore(deadline)

    fun amount(
        terms: CancellationTerms,
        nights: List<NightlyRateSummary>,
        roomTotal: Money,
    ): Money =
        when (terms.penaltyType) {
            CancellationPenaltyType.PERCENT_OF_STAY -> {
                checkNotNull(terms.penaltyPercent).of(roomTotal)
            }

            CancellationPenaltyType.NIGHTS -> {
                nights
                    .sortedBy { it.date }
                    .take(checkNotNull(terms.penaltyNights))
                    .map { it.roomAmount }
                    .sum()
            }

            CancellationPenaltyType.FIXED_AMOUNT -> {
                checkNotNull(terms.penaltyAmount)
            }
        }

    fun due(
        penalty: Money,
        alreadyCharged: Money,
    ): Money = (penalty - alreadyCharged).takeIf { it.isPositive() } ?: Money.ZERO
}

/**
 * As noites que mexem no inventário (decisão do implementador na E08, pendente de confirmação): só as noites a partir
 * da data operacional; as anteriores são passado e ficam como estão (a recontagem também só refaz da data operacional
 * em diante). O day use não toma inventário (H5). `null` quando não há noite a mexer.
 */
object InventoryNights {
    fun of(
        roomTypeId: RoomTypeId,
        arrival: LocalDate,
        departure: LocalDate,
        businessDate: LocalDate,
        take: Boolean,
    ): InventoryChange? {
        val from = maxOf(arrival, businessDate)
        return when {
            arrival == departure || !departure.isAfter(from) -> null
            take -> InventoryChange.take(roomTypeId, from, departure, 1, InventoryUse.SOLD)
            else -> InventoryChange.release(roomTypeId, from, departure, 1, InventoryUse.SOLD)
        }
    }

    /** Se a estadia ainda tem alguma noite a partir da data operacional (no day use, a data não passou). */
    fun hasFutureNight(
        arrival: LocalDate,
        departure: LocalDate,
        businessDate: LocalDate,
    ): Boolean = if (arrival == departure) !arrival.isBefore(businessDate) else departure.isAfter(businessDate)
}

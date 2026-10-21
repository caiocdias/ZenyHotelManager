package com.zeny.hotel.reservations

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.reservations.domain.CancellationPenalties
import com.zeny.hotel.reservations.domain.CancellationRules
import com.zeny.hotel.reservations.domain.InventoryNights
import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationChangeRules
import com.zeny.hotel.reservations.domain.ReservationTransitionNotAllowedException
import com.zeny.hotel.reservations.domain.ReservationTransitions
import com.zeny.hotel.reservations.domain.StayRepricing
import com.zeny.hotel.reservations.domain.StayShape
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * As regras do ciclo de vida da reserva (M4 E08): a máquina de estados da SPEC §8.4, o prazo e o valor da multa pela
 * política copiada (H15), as noites que uma alteração recalcula, os motivos de venda que contam numa alteração e as
 * noites que mexem no inventário.
 */
class ReservationLifecycleDomainTest {
    // --- Máquina de estados ---------------------------------------------------------------------------------------

    @Test
    fun `only a provisional or confirmed is changed or cancelled, only a cancelled or no-show reactivated`() {
        // Desde a E09, a pré-reserva também é confirmada.
        val table =
            ReservationStatus.entries.associateWith { status ->
                ReservationAction.entries.filter { ReservationTransitions.allows(it, status) }
            }

        table shouldBe
            mapOf(
                ReservationStatus.PROVISIONAL to
                    listOf(ReservationAction.CHANGE, ReservationAction.CANCEL, ReservationAction.CONFIRM),
                ReservationStatus.CONFIRMED to listOf(ReservationAction.CHANGE, ReservationAction.CANCEL),
                ReservationStatus.IN_HOUSE to emptyList(),
                ReservationStatus.CHECKED_OUT to emptyList(),
                ReservationStatus.CANCELLED to listOf(ReservationAction.REACTIVATE),
                ReservationStatus.NO_SHOW to listOf(ReservationAction.REACTIVATE),
            )
        val refused =
            shouldThrow<ReservationTransitionNotAllowedException> {
                ReservationTransitions.require(ReservationAction.CHANGE, ReservationStatus.CHECKED_OUT)
            }
        refused.code shouldBe ReservationsErrorCodes.RESERVATION_TRANSITION_NOT_ALLOWED
        refused.problemProperties shouldBe mapOf("reservationStatus" to "CHECKED_OUT", "action" to "CHANGE")
    }

    // --- Multa ----------------------------------------------------------------------------------------------------

    @Test
    fun `the deadline is the check-in time of the arrival in the hotel zone minus the hours, and it already charges`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        // 14:00 em São Paulo (UTC-3) de 10/11, menos 24 h: 09/11 às 17:00Z.
        val deadline = CancellationPenalties.deadline(ARRIVAL, LocalTime.of(14, 0), zone, 24)

        deadline shouldBe Instant.parse("2026-11-09T17:00:00Z")
        CancellationPenalties.applies(deadline.minusNanos(1_000), deadline) shouldBe false
        CancellationPenalties.applies(deadline, deadline) shouldBe true
        // Atravessando a meia-noite: 2 h antes das 01:00 é a véspera, 23:00 locais.
        CancellationPenalties.deadline(ARRIVAL, LocalTime.of(1, 0), zone, 2) shouldBe
            Instant.parse("2026-11-10T02:00:00Z")
        CancellationPenalties.deadline(ARRIVAL, LocalTime.of(14, 0), zone, 0) shouldBe
            Instant.parse("2026-11-10T17:00:00Z")
    }

    @Test
    fun `the penalty follows the copied terms over the frozen room rates, and what was already charged is not due`() {
        val nights =
            listOf(night(ARRIVAL, "400.00"), night(ARRIVAL.plusDays(1), "350.00"), night(ARRIVAL.plusDays(2), "333.33"))
        val roomTotal = Money.of("1083.33")

        CancellationPenalties.amount(terms(CancellationPenaltyType.NIGHTS, nights = 1), nights, roomTotal) shouldBe
            money("400.00")
        CancellationPenalties.amount(terms(CancellationPenaltyType.NIGHTS, nights = 2), nights, roomTotal) shouldBe
            money("750.00")
        // Mais noites que a estadia: todas.
        CancellationPenalties.amount(terms(CancellationPenaltyType.NIGHTS, nights = 9), nights, roomTotal) shouldBe
            roomTotal
        // 15% de 1083,33 = 162,4995: HALF_EVEN dá 162,50.
        CancellationPenalties.amount(
            terms(CancellationPenaltyType.PERCENT_OF_STAY, percent = "15"),
            nights,
            roomTotal,
        ) shouldBe
            money("162.50")
        CancellationPenalties.amount(
            terms(CancellationPenaltyType.FIXED_AMOUNT, amount = "90.00"),
            nights,
            roomTotal,
        ) shouldBe
            money("90.00")
        CancellationPenalties.amount(terms(CancellationPenaltyType.NIGHTS, nights = 0), nights, roomTotal) shouldBe
            Money.ZERO

        CancellationPenalties.due(money("400.00"), Money.ZERO) shouldBe money("400.00")
        CancellationPenalties.due(money("400.00"), money("150.00")) shouldBe money("250.00")
        CancellationPenalties.due(money("400.00"), money("400.00")) shouldBe Money.ZERO
        CancellationPenalties.due(money("90.00"), money("400.00")) shouldBe Money.ZERO
    }

    @Test
    fun `the cancellation notes have 1 to 500 characters`() {
        CancellationRules.requireValid(ReservationCancellationRequest(CancellationReason.OTHER, "a".repeat(500)))
        listOf(" ", "a".repeat(501)).forEach { notes ->
            shouldThrow<InvalidInputException> {
                CancellationRules.requireValid(ReservationCancellationRequest(CancellationReason.OTHER, notes))
            }.violations.single().field shouldBe "notes"
        }
    }

    // --- Noites recalculadas --------------------------------------------------------------------------------------

    @Test
    fun `changing only the dates reprices only the new nights, the kept ones stay frozen`() {
        val old = shape(ARRIVAL, ARRIVAL.plusDays(2))

        // Estender uma noite: só a nova.
        StayRepricing.affectedNights(old, shape(ARRIVAL, ARRIVAL.plusDays(3)), false) shouldContainExactly
            listOf(ARRIVAL.plusDays(2))
        // Encurtar: nada a recalcular.
        StayRepricing.affectedNights(old, shape(ARRIVAL, ARRIVAL.plusDays(1)), false).shouldBeEmpty()
        // Antecipar a chegada sem pacote por estadia: só a noite nova.
        StayRepricing.affectedNights(old, shape(ARRIVAL.minusDays(1), ARRIVAL.plusDays(2)), false) shouldContainExactly
            listOf(ARRIVAL.minusDays(1))
        // Com pacote por estadia, as noites da chegada antiga e da nova também.
        StayRepricing.affectedNights(old, shape(ARRIVAL.minusDays(1), ARRIVAL.plusDays(2)), true) shouldContainExactly
            listOf(ARRIVAL.minusDays(1), ARRIVAL)
        StayRepricing.affectedNights(old, shape(ARRIVAL.plusDays(1), ARRIVAL.plusDays(3)), true) shouldContainExactly
            listOf(ARRIVAL.plusDays(1), ARRIVAL.plusDays(2))
        // Sem mudança, nada.
        StayRepricing.affectedNights(old, old, true).shouldBeEmpty()
    }

    @Test
    fun `changing the room type, the plan, the occupancy or between day use and overnight reprices every night`() {
        val old = shape(ARRIVAL, ARRIVAL.plusDays(2))
        val all = listOf(ARRIVAL, ARRIVAL.plusDays(1))

        StayRepricing.affectedNights(old, old.copy(roomTypeId = RoomTypeId(UuidV7.generate())), false) shouldBe all
        StayRepricing.affectedNights(old, old.copy(ratePlanId = RatePlanId(UuidV7.generate())), false) shouldBe all
        StayRepricing.affectedNights(old, old.copy(adults = 3), false) shouldBe all
        StayRepricing.affectedNights(old, old.copy(childAges = listOf(4)), false) shouldBe all
        StayRepricing.affectedNights(old, shape(ARRIVAL, ARRIVAL), false) shouldBe listOf(ARRIVAL)
        StayRepricing.affectedNights(
            shape(ARRIVAL, ARRIVAL),
            shape(ARRIVAL.plusDays(1), ARRIVAL.plusDays(1)),
            false,
        ) shouldBe
            listOf(ARRIVAL.plusDays(1))
    }

    @Test
    fun `affected nights are priced in contiguous runs, and a run off the arrival drops the per-stay packages`() {
        StayRepricing.runs(listOf(ARRIVAL.plusDays(4), ARRIVAL, ARRIVAL.plusDays(1))) shouldContainExactly
            listOf(ARRIVAL to ARRIVAL.plusDays(2), ARRIVAL.plusDays(4) to ARRIVAL.plusDays(5))

        val night =
            NightPrice(
                date = ARRIVAL,
                baseRate = money("400.00"),
                extraAdults = 0,
                extraAdultsAmount = Money.ZERO,
                children = emptyList(),
                roomAmount = money("400.00"),
                packages =
                    listOf(
                        pkg(PackagePricing.PER_STAY, included = true, amount = "50.00"),
                        pkg(PackagePricing.PER_STAY, included = false, amount = "80.00"),
                        pkg(PackagePricing.PER_NIGHT, included = false, amount = "20.00"),
                    ),
                includedPackagesAmount = money("50.00"),
                accommodationAmount = money("350.00"),
                additionalPackagesAmount = money("100.00"),
                total = money("500.00"),
            )

        val stripped = StayRepricing.withoutPerStayPackages(night)

        stripped.packages.map { it.pricing } shouldContainExactly listOf(PackagePricing.PER_NIGHT)
        stripped.roomAmount shouldBe money("400.00")
        stripped.includedPackagesAmount shouldBe Money.ZERO
        stripped.accommodationAmount shouldBe money("400.00")
        stripped.additionalPackagesAmount shouldBe money("20.00")
        stripped.total shouldBe money("420.00")
    }

    @Test
    fun `an unchanged sale rule does not refuse the change, and night rules count only the affected nights`() {
        val old = shape(ARRIVAL, ARRIVAL.plusDays(2))
        val extended = shape(ARRIVAL, ARRIVAL.plusDays(3))
        val refusals =
            listOf(
                SaleRefusal(RatesErrorCodes.RATE_PLAN_INACTIVE, "inativo"),
                SaleRefusal(RatesErrorCodes.MIN_ADVANCE_NOT_MET, "antecedência"),
                SaleRefusal(RatesErrorCodes.CLOSED_TO_ARRIVAL, "cta"),
                SaleRefusal(RatesErrorCodes.CLOSED_TO_DEPARTURE, "ctd"),
                SaleRefusal(RatesErrorCodes.MIN_STAY_NOT_MET, "mínima"),
                SaleRefusal(RatesErrorCodes.STAY_DATE_CLOSED, "fechada", listOf(ARRIVAL, ARRIVAL.plusDays(2))),
                SaleRefusal(RatesErrorCodes.RATE_NOT_PRICED, "sem preço", listOf(ARRIVAL)),
            )

        val relevant = StayRepricing.relevantRefusals(refusals, old, extended, listOf(ARRIVAL.plusDays(2)))

        relevant.map { it.code } shouldContainExactly
            listOf(
                RatesErrorCodes.CLOSED_TO_DEPARTURE,
                RatesErrorCodes.MIN_STAY_NOT_MET,
                RatesErrorCodes.STAY_DATE_CLOSED,
            )
        relevant.last().dates shouldContainExactly listOf(ARRIVAL.plusDays(2))
        // Só a ocupação: nada da venda conta, salvo a ocupação máxima.
        val occupancy = old.copy(adults = 3)
        StayRepricing
            .relevantRefusals(
                refusals + SaleRefusal(RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE, "ocupação"),
                old,
                occupancy,
                emptyList(),
            ).map { it.code } shouldContainExactly listOf(RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE)
        // O plano trocado: o plano desativado conta.
        StayRepricing
            .relevantRefusals(refusals, old, old.copy(ratePlanId = RatePlanId(UuidV7.generate())), old.nights)
            .map { it.code } shouldContainExactly
            listOf(
                RatesErrorCodes.RATE_PLAN_INACTIVE,
                RatesErrorCodes.STAY_DATE_CLOSED,
                RatesErrorCodes.RATE_NOT_PRICED,
            )
    }

    @Test
    fun `nightly rate overrides only on the repriced nights, and the occupancy never below the stay guests`() {
        val change =
            ReservationChange(
                TYPE,
                PLAN,
                ARRIVAL,
                ARRIVAL.plusDays(3),
                adults = 1,
                segment = MarketSegment.LEISURE,
                nightlyRates = listOf(NightlyRateOverride(ARRIVAL, money("100.00"))),
            )

        shouldThrow<InvalidInputException> {
            ReservationChangeRules.requireOverridesOn(change, listOf(ARRIVAL.plusDays(2)))
        }.let {
            it.code shouldBe ReservationsErrorCodes.NIGHTLY_RATE_INVALID
            it.violations.single().field shouldBe "nightlyRates[0].date"
        }
        ReservationChangeRules.requireOverridesOn(change, listOf(ARRIVAL))
        shouldThrow<InvalidInputException> { ReservationChangeRules.requireValid(change, guestCount = 2) }.let {
            it.code shouldBe ReservationsErrorCodes.RESERVATION_CHANGE_INVALID
            it.violations.single().field shouldBe "adults"
        }
        ReservationChangeRules.requireValid(change, guestCount = 1)
    }

    // --- Inventário ----------------------------------------------------------------------------------------------

    @Test
    fun `only the nights from the business date on move the inventory, and the day use never does`() {
        val today = ARRIVAL.plusDays(1)

        InventoryNights.of(TYPE, ARRIVAL, ARRIVAL.plusDays(3), ARRIVAL, take = true)!!.let {
            it.from shouldBe ARRIVAL
            it.quantity shouldBe 1
        }
        InventoryNights.of(TYPE, ARRIVAL, ARRIVAL.plusDays(3), today, take = false)!!.let {
            it.from shouldBe today
            it.until shouldBe ARRIVAL.plusDays(3)
            it.quantity shouldBe -1
        }
        InventoryNights.of(TYPE, ARRIVAL, ARRIVAL.plusDays(1), today, take = true) shouldBe null
        InventoryNights.of(TYPE, ARRIVAL, ARRIVAL, ARRIVAL, take = true) shouldBe null
        InventoryNights.hasFutureNight(ARRIVAL, ARRIVAL.plusDays(1), today) shouldBe false
        InventoryNights.hasFutureNight(ARRIVAL, ARRIVAL.plusDays(2), today) shouldBe true
        InventoryNights.hasFutureNight(today, today, today) shouldBe true
        InventoryNights.hasFutureNight(ARRIVAL, ARRIVAL, today) shouldBe false
    }

    private fun shape(
        arrival: LocalDate,
        departure: LocalDate,
    ) = StayShape(TYPE, PLAN, arrival, departure, 2, emptyList())

    private fun night(
        date: LocalDate,
        room: String,
    ) = NightlyRateSummary(
        date = date,
        ratePlanId = PLAN,
        calculatedRoomAmount = money(room),
        roomAmount = money(room),
        includedPackagesAmount = Money.ZERO,
        additionalPackagesAmount = Money.ZERO,
        amount = money(room),
        overridden = false,
        discountPercent = null,
        packages = emptyList(),
    )

    private fun terms(
        type: CancellationPenaltyType,
        nights: Int? = null,
        percent: String? = null,
        amount: String? = null,
    ) = CancellationTerms(
        CancellationPolicyId(UuidV7.generate()),
        "Política",
        0,
        48,
        type,
        percent?.let(Percent::of),
        nights,
        amount?.let(Money::of),
    )

    private fun pkg(
        pricing: PackagePricing,
        included: Boolean,
        amount: String,
    ) = PackageCharge(
        PackageId(UuidV7.generate()),
        "P",
        "Pacote",
        TransactionCodeId(UuidV7.generate()),
        pricing,
        PackagePostingRhythm.ON_ARRIVAL,
        included,
        1,
        money(amount),
        money(amount),
    )

    private fun money(value: String) = Money.of(value)

    private companion object {
        val ARRIVAL: LocalDate = LocalDate.of(2026, 11, 10)
        val TYPE = RoomTypeId(UuidV7.generate())
        val PLAN = RatePlanId(UuidV7.generate())
    }
}

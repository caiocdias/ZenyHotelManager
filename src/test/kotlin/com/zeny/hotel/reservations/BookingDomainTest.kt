package com.zeny.hotel.reservations

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.StayPrice
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.reservations.domain.BookingRules
import com.zeny.hotel.reservations.domain.InitialStatus
import com.zeny.hotel.reservations.domain.NightlyRateBelowIncludedPackagesException
import com.zeny.hotel.reservations.domain.NightlyRates
import com.zeny.hotel.reservations.domain.StayGuests
import com.zeny.hotel.reservations.domain.Stays
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.sum
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * As regras da criação de reservas (M4 E07): o preço congelado por noite com as diárias editadas e o desconto
 * arredondado para cima, a diária abaixo dos inclusos, a situação inicial pela garantia, o formato e os hóspedes depois
 * da mesclagem.
 */
class BookingDomainTest {
    @Test
    fun `the frozen nights keep the calculated rate, record the edited one and the discount rounded up`() {
        val nights =
            NightlyRates.freeze(
                price(night(ARRIVAL, "400.00"), night(ARRIVAL.plusDays(1), "300.00")),
                PLAN,
                mapOf(ARRIVAL to money("380.00")),
            )

        nights.map { it.roomAmount } shouldContainExactly listOf(money("380.00"), money("300.00"))
        nights.map { it.calculatedRoomAmount } shouldContainExactly listOf(money("400.00"), money("300.00"))
        nights.map { it.overridden } shouldContainExactly listOf(true, false)
        nights.map { it.discountPercent } shouldContainExactly listOf(Percent.of("5.0000"), null)
        nights.map { it.amount } shouldContainExactly listOf(money("410.00"), money("330.00"))
        NightlyRates.maxDiscount(nights) shouldBe Percent.of("5")
        NightlyRates.totals(nights) shouldBe Triple(money("680.00"), money("60.00"), money("740.00"))
        nights.map { it.amount }.sum() shouldBe money("740.00")
    }

    @Test
    fun `the discount is a percent of the calculated rate, rounded up in 4 places, and none above it`() {
        NightlyRates.discount(money("300.00"), money("200.00")) shouldBe Percent.of("33.3334")
        NightlyRates.discount(money("400.00"), money("0.00")) shouldBe Percent.of("100")
        NightlyRates.discount(money("400.00"), money("400.00")) shouldBe null
        NightlyRates.discount(money("400.00"), money("450.00")) shouldBe null
        NightlyRates.discount(money("0.00"), money("10.00")) shouldBe null
    }

    @Test
    fun `an edited rate below the included packages of the night is refused with the dates`() {
        val error =
            shouldThrow<NightlyRateBelowIncludedPackagesException> {
                NightlyRates.freeze(
                    price(night(ARRIVAL, "400.00", included = "70.00")),
                    PLAN,
                    mapOf(ARRIVAL to money("69.99")),
                )
            }

        error.dates shouldContainExactly listOf(ARRIVAL)
        error.code shouldBe ReservationsErrorCodes.NIGHTLY_RATE_BELOW_INCLUDED_PACKAGES
    }

    @Test
    fun `without a required guarantee the reservation is confirmed, with one it is provisional until the expiry`() {
        val now = Instant.parse("2026-10-21T12:00:00Z")

        InitialStatus.of(GuaranteeType.NONE, now, Duration.ofHours(48)) shouldBe
            InitialStatus.Outcome(ReservationStatus.CONFIRMED, null)
        InitialStatus.of(GuaranteeType.DEPOSIT, now, Duration.ofHours(48)) shouldBe
            InitialStatus.Outcome(ReservationStatus.PROVISIONAL, Instant.parse("2026-10-23T12:00:00Z"))
        InitialStatus.of(GuaranteeType.CARD, now, Duration.ofHours(1)).status shouldBe ReservationStatus.PROVISIONAL
    }

    @Test
    fun `the stay nights go from the arrival to the eve of the departure, and a day use is its own date`() {
        Stays.nights(ARRIVAL, ARRIVAL.plusDays(2)) shouldContainExactly listOf(ARRIVAL, ARRIVAL.plusDays(1))
        Stays.nights(ARRIVAL, ARRIVAL) shouldContainExactly listOf(ARRIVAL)
        Stays.nights(ARRIVAL, ARRIVAL.minusDays(1)) shouldContainExactly emptyList()
    }

    @Test
    fun `the format refuses every problem with its field`() {
        val guest = GuestId(UuidV7.generate())
        val reservation =
            NewReservation(
                roomTypeId = TYPE,
                ratePlanId = PLAN,
                arrival = ARRIVAL,
                departure = ARRIVAL.plusDays(1),
                adults = 1,
                segment = MarketSegment.LEISURE,
                specialRequests = " ",
                guests = listOf(NewStayGuest(guest, true), NewStayGuest(guest, false)),
                nightlyRates = listOf(NightlyRateOverride(ARRIVAL.plusDays(1), money("10.00"))),
            )

        val error =
            shouldThrow<InvalidInputException> {
                BookingRules.requireValid(
                    NewBooking(guest, ReservationOrigin.PHONE, "x".repeat(61), listOf(reservation)),
                )
            }

        error.code shouldBe ReservationsErrorCodes.BOOKING_INVALID
        error.violations.map { it.field } shouldContainExactly
            listOf(
                "externalId",
                "reservations[0].specialRequests",
                "reservations[0].guests",
                "reservations[0].guests",
                "reservations[0].nightlyRates[0].date",
            )
        shouldThrow<InvalidInputException> {
            BookingRules.requireValid(NewBooking(guest, ReservationOrigin.PHONE, null, emptyList()))
        }.violations.map { it.field } shouldContainExactly listOf("reservations")
    }

    @Test
    fun `the stay guests become the survivors, without repeating, and the holder is the primary when none is given`() {
        val holder = GuestId(UuidV7.generate())
        val absorbed = GuestId(UuidV7.generate())
        val survivor = GuestId(UuidV7.generate())

        StayGuests.resolve(emptyList(), holder) { it } shouldContainExactly listOf(StayGuestSummary(holder, true))
        StayGuests.resolve(
            listOf(NewStayGuest(survivor, false), NewStayGuest(absorbed, true)),
            holder,
        ) { if (it == absorbed) survivor else it } shouldContainExactly listOf(StayGuestSummary(survivor, true))
    }

    @Test
    fun `the origin defines the sales channel`() {
        ReservationOrigin.entries.associateWith { it.channel.name } shouldBe
            mapOf(
                ReservationOrigin.FRONT_DESK to "DIRECT",
                ReservationOrigin.PHONE to "DIRECT",
                ReservationOrigin.EMAIL to "DIRECT",
                ReservationOrigin.WHATSAPP to "DIRECT",
                ReservationOrigin.WEBSITE to "WEBSITE",
                ReservationOrigin.OTA to "OTA",
                ReservationOrigin.OTHER to "DIRECT",
            )
    }

    private fun price(vararg nights: NightPrice): StayPrice {
        val list = nights.toList()
        return StayPrice(
            StayQuery(PLAN, TYPE, ARRIVAL, ARRIVAL.plusDays(list.size.toLong()), 2),
            list,
            list.map { it.roomAmount }.sum(),
            list.map { it.includedPackagesAmount }.sum(),
            list.map { it.accommodationAmount }.sum(),
            list.map { it.additionalPackagesAmount }.sum(),
            list.map { it.total }.sum(),
        )
    }

    private fun night(
        date: LocalDate,
        room: String,
        included: String = "0.00",
    ): NightPrice {
        val packages =
            listOfNotNull(
                charge("ESTAC", "30.00", included = false),
                included.takeIf { it != "0.00" }?.let { charge("CAFE", it, included = true) },
            )
        val additional = money("30.00")
        return NightPrice(
            date = date,
            baseRate = money(room),
            extraAdults = 0,
            extraAdultsAmount = Money.ZERO,
            children = emptyList(),
            roomAmount = money(room),
            packages = packages,
            includedPackagesAmount = money(included),
            accommodationAmount = money(room) - money(included),
            additionalPackagesAmount = additional,
            total = money(room) + additional,
        )
    }

    private fun charge(
        code: String,
        amount: String,
        included: Boolean,
    ) = PackageCharge(
        PackageId(UuidV7.generate()),
        code,
        "Pacote $code",
        TransactionCodeId(UuidV7.generate()),
        PackagePricing.PER_NIGHT,
        PackagePostingRhythm.DAILY,
        included,
        1,
        money(amount),
        money(amount),
    )

    private companion object {
        val ARRIVAL: LocalDate = LocalDate.of(2026, 11, 10)
        val PLAN = RatePlanId(UuidV7.generate())
        val TYPE = RoomTypeId(UuidV7.generate())

        fun money(value: String) = Money.of(value)
    }
}

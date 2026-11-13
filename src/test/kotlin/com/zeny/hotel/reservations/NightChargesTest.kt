package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rates.CancellationPenaltyType
import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.PackageCharge
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.PackagePostingRhythm
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.sum
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** As cobranças de uma noite congelada (M6 E11, Task 5): hospedagem, pacotes e taxa de turismo, regra única. */
class NightChargesTest {
    private val breakfast = packageCharge("Café da manhã", true, 2, "20.00")
    private val parking = packageCharge("Estacionamento", false, 1, "25.00")
    private val free = packageCharge("Cortesia", false, 1, "0.00")
    private val fee = Money.of("5.00")
    private val tourismCode = TransactionCodeId.new()
    private val roomCode = TransactionCodeId.new()
    private val codes: (NightChargeKind) -> TransactionCodeId = {
        when (it) {
            NightChargeKind.ROOM -> roomCode
            NightChargeKind.TOURISM_FEE -> tourismCode
            NightChargeKind.PACKAGE -> error("o pacote traz o código dele")
        }
    }

    @Test
    fun `the middle night of a three night stay charges room, packages and the tourism fee`() {
        val middle = ARRIVAL.plusDays(1)
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(3), listOf(breakfast, parking, free))

        val charges = ReservationNightCharges.of(reservation, middle, fee)

        val base = "reservations.reservation:${reservation.id}:$middle"
        charges.map { it.sourceReference } shouldBe
            listOf(
                "$base:room",
                "$base:package:${breakfast.packageId}",
                "$base:package:${parking.packageId}",
                "$base:tourism-fee",
            )
        charges.forEach { it.date shouldBe middle }
        val room = charges.single { it.kind == NightChargeKind.ROOM }
        room.amount shouldBe Money.of("260.00")
        room.includedInRate shouldBe true
        room.transactionCodeId.shouldBeNull()
        val coffee = charges.single { it.packageId == breakfast.packageId.value }
        coffee.kind shouldBe NightChargeKind.PACKAGE
        coffee.includedInRate shouldBe true
        coffee.transactionCodeId shouldBe breakfast.transactionCodeId
        coffee.description shouldBe "Café da manhã"
        coffee.unitPrice shouldBe Money.of("20.00")
        val extra = charges.single { it.packageId == parking.packageId.value }
        extra.includedInRate shouldBe false
        extra.amount shouldBe Money.of("25.00")
        val tourism = charges.single { it.kind == NightChargeKind.TOURISM_FEE }
        tourism.amount shouldBe Money.of("15.00")
        tourism.quantity.compareTo(3.toBigDecimal()) shouldBe 0
        tourism.unitPrice shouldBe fee
        tourism.description shouldBe "Taxa de turismo"
        tourism.includedInRate shouldBe false
        tourism.packageId.shouldBeNull()
        charges.filter { it.kind != NightChargeKind.TOURISM_FEE }.map { it.amount }.sum() shouldBe
            reservation.nightlyRates[1].amount
    }

    @Test
    fun `a day use never charges the tourism fee`() {
        val reservation = reservation(ARRIVAL, ARRIVAL, listOf(parking))

        val charges = ReservationNightCharges.of(reservation, ARRIVAL, fee)

        charges.map { it.kind } shouldBe listOf(NightChargeKind.ROOM, NightChargeKind.PACKAGE)
    }

    @Test
    fun `without a configured tourism fee or with a zero one nothing is charged for it`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), emptyList())

        ReservationNightCharges.of(reservation, ARRIVAL, null).map { it.kind } shouldBe listOf(NightChargeKind.ROOM)
        ReservationNightCharges.of(reservation, ARRIVAL, Money.ZERO).map { it.kind } shouldBe
            listOf(NightChargeKind.ROOM)
    }

    @Test
    fun `a date outside the frozen nights has no charges`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), emptyList())

        ReservationNightCharges.of(reservation, ARRIVAL.minusDays(1), fee).shouldBeEmpty()
        ReservationNightCharges.of(reservation, ARRIVAL.plusDays(2), fee).shouldBeEmpty()
    }

    @Test
    fun `a zero value package is left out`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), listOf(free))

        ReservationNightCharges.of(reservation, ARRIVAL, null) shouldHaveSize 1
    }

    @Test
    fun `room and included package postings carry no service charge, the extra package and the fee carry none set`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), listOf(breakfast, parking))

        val postings =
            ReservationNightCharges
                .of(reservation, ARRIVAL, fee)
                .associate { it.kind to it.packageId to it.toNewPosting(codes) }

        postings.values.forEach { it.source shouldBe PostingSource.END_OF_DAY }
        val room = postings.getValue(NightChargeKind.ROOM to null)
        room.serviceChargeRate shouldBe Percent.ZERO
        room.transactionCodeId shouldBe roomCode
        room.unitPrice shouldBe Money.of("260.00")
        postings.getValue(NightChargeKind.PACKAGE to breakfast.packageId.value).serviceChargeRate shouldBe Percent.ZERO
        val extra = postings.getValue(NightChargeKind.PACKAGE to parking.packageId.value)
        extra.serviceChargeRate.shouldBeNull()
        extra.transactionCodeId shouldBe parking.transactionCodeId
        val tourism = postings.getValue(NightChargeKind.TOURISM_FEE to null)
        tourism.serviceChargeRate.shouldBeNull()
        tourism.transactionCodeId shouldBe tourismCode
        tourism.sourceReference shouldBe "${ReservationNightCharges.reference(reservation.id, ARRIVAL)}:tourism-fee"
    }

    private fun packageCharge(
        name: String,
        included: Boolean,
        quantity: Int,
        unitPrice: String,
    ) = PackageCharge(
        packageId = PackageId.new(),
        code = name.take(3).uppercase(),
        name = name,
        transactionCodeId = TransactionCodeId.new(),
        pricing = PackagePricing.PER_NIGHT,
        postingRhythm = PackagePostingRhythm.DAILY,
        includedInRate = included,
        quantity = quantity,
        unitPrice = Money.of(unitPrice),
        amount = Money.of(unitPrice) * quantity,
    )

    private fun night(
        date: LocalDate,
        packages: List<PackageCharge>,
    ): NightlyRateSummary {
        val additional = packages.filter { !it.includedInRate }.map { it.amount }.sum()
        return NightlyRateSummary(
            date = date,
            ratePlanId = PLAN,
            calculatedRoomAmount = Money.of("300.00"),
            roomAmount = Money.of("300.00"),
            includedPackagesAmount = packages.filter { it.includedInRate }.map { it.amount }.sum(),
            additionalPackagesAmount = additional,
            amount = Money.of("300.00") + additional,
            overridden = false,
            discountPercent = null,
            packages = packages,
        )
    }

    @Suppress("LongMethod") // a reserva inteira, com todos os campos
    private fun reservation(
        arrival: LocalDate,
        departure: LocalDate,
        packages: List<PackageCharge>,
    ): ReservationSummary {
        val nights = if (arrival == departure) listOf(arrival) else arrival.datesUntil(departure).toList()
        return ReservationSummary(
            id = ReservationId.new(),
            code = DocumentNumber.parse("R-2026-000001"),
            bookingId = BookingId.new(),
            status = ReservationStatus.IN_HOUSE,
            provisionalUntil = null,
            roomTypeId = RoomTypeId(UuidV7.generate()),
            ratePlanId = PLAN,
            ratePlanCategory = RatePlanCategory.NORMAL,
            arrival = arrival,
            departure = departure,
            adults = 2,
            childAges = listOf(7),
            segment = MarketSegment.LEISURE,
            specialRequests = null,
            guests = emptyList(),
            nightlyRates = nights.map { night(it, packages) },
            roomTotal = Money.ZERO,
            additionalPackagesTotal = Money.ZERO,
            total = Money.ZERO,
            cancellationTerms =
                CancellationTerms(
                    CancellationPolicyId(UuidV7.generate()),
                    "Flexível",
                    0,
                    24,
                    CancellationPenaltyType.PERCENT_OF_STAY,
                    Percent.of("10"),
                    null,
                    null,
                ),
            guaranteeTerms =
                GuaranteeTerms(GuaranteePolicyId(UuidV7.generate()), "Sem", 0, GuaranteeType.NONE, null, null),
            folioId = FolioId.new(),
            approvalId = null,
            approvedBy = null,
            version = 0,
            createdAt = Instant.parse("2026-10-01T12:00:00Z"),
            createdBy = null,
        )
    }

    private companion object {
        val ARRIVAL: LocalDate = LocalDate.of(2026, 10, 22)
        val PLAN = RatePlanId(UuidV7.generate())
    }
}

package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.frontdesk.domain.StayCharges
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
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Ataque as cobrancas de uma noite congelada (M6 E11, Task 5): paridade com o check-out, arredondamento, taxa de
 * turismo, bordas de data e dados pessoais.
 */
class NightChargesAttackTest {
    private val breakfast = packageCharge("Café da manhã", true, 3, "20.00")
    private val parking = packageCharge("Estacionamento", false, 1, "25.00")
    private val fee = Money.of("3.33")

    @Test
    fun `the arrival night room and packages equal the check-out rule, key by key`() {
        listOf(ARRIVAL to ARRIVAL, ARRIVAL to ARRIVAL.plusDays(3)).forEach { (arrival, departure) ->
            val reservation = reservation(arrival, departure, listOf(breakfast, parking))

            val mine =
                ReservationNightCharges
                    .of(reservation, ARRIVAL, null)
                    .map {
                        listOf(
                            it.sourceReference,
                            it.amount,
                            it.quantity.toPlainString(),
                            it.unitPrice,
                            it.description,
                            it.transactionCodeId,
                            it.packageId,
                            it.includedInRate,
                            it.date,
                        )
                    }
            val theirs =
                StayCharges
                    .pending(reservation, ARRIVAL)
                    .map {
                        listOf(
                            it.sourceReference,
                            it.amount,
                            it.quantity.toPlainString(),
                            it.unitPrice,
                            it.description,
                            it.transactionCodeId,
                            it.packageId?.value,
                            it.includedInRate,
                            it.date,
                        )
                    }

            mine shouldBe theirs
            mine shouldHaveSize 3
        }
    }

    @Test
    fun `the check-out rule and this one agree when a package has quantity above one`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(1), listOf(breakfast))

        val charge =
            ReservationNightCharges
                .of(
                    reservation,
                    ARRIVAL,
                    null,
                ).single { it.kind == NightChargeKind.PACKAGE }

        charge.quantity.compareTo(BigDecimal(3)) shouldBe 0
        charge.amount shouldBe Money.of("60.00")
        charge.unitPrice shouldBe Money.of("20.00")
    }

    @Test
    fun `the amount of a fractional quantity rounds half to even with the Money rule`() {
        fun charge(
            quantity: String,
            price: String,
        ) = NightCharge(
            NightChargeKind.PACKAGE,
            ARRIVAL,
            TransactionCodeId.new(),
            null,
            BigDecimal(quantity),
            Money.of(price),
            null,
            "k",
            false,
        )

        charge("0.5", "3.35").amount shouldBe Money.of("1.68")
        charge("0.5", "3.37").amount shouldBe Money.of("1.68")
        charge("1.5", "0.01").amount shouldBe Money.of("0.02")
        charge("2.5", "0.01").amount shouldBe Money.of("0.02")
    }

    @Test
    fun `the tourism fee with cents multiplies exactly by adults plus children`() {
        val reservation =
            reservation(ARRIVAL, ARRIVAL.plusDays(2), emptyList(), adults = 1, childAges = listOf(2, 9, 11))

        val tourism =
            ReservationNightCharges.of(reservation, ARRIVAL, fee).single {
                it.kind ==
                    NightChargeKind.TOURISM_FEE
            }

        tourism.quantity.compareTo(BigDecimal(4)) shouldBe 0
        tourism.amount shouldBe Money.of("13.32")
        tourism.unitPrice shouldBe fee
    }

    @Test
    fun `a stay with adults only charges the fee per adult`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), emptyList(), adults = 2, childAges = emptyList())

        val tourism =
            ReservationNightCharges.of(reservation, ARRIVAL, fee).single {
                it.kind ==
                    NightChargeKind.TOURISM_FEE
            }

        tourism.amount shouldBe Money.of("6.66")
    }

    @Test
    fun `a reservation without packages charges the room at the full frozen amount`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), emptyList())

        val charges = ReservationNightCharges.of(reservation, ARRIVAL, null)

        charges.map { it.kind } shouldBe listOf(NightChargeKind.ROOM)
        charges.single().amount shouldBe Money.of("300.00")
    }

    @Test
    fun `when the room amount equals the included packages the room is left out and the package stays`() {
        val only = packageCharge("Tudo incluso", true, 1, "300.00")
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), listOf(only))

        val charges = ReservationNightCharges.of(reservation, ARRIVAL, null)

        charges.map { it.kind } shouldBe listOf(NightChargeKind.PACKAGE)
        charges.single().amount shouldBe Money.of("300.00")
    }

    @Test
    fun `no charge is ever negative or zero`() {
        val reservation =
            reservation(ARRIVAL, ARRIVAL.plusDays(3), listOf(breakfast, parking, packageCharge("Z", false, 1, "0.00")))

        (0L..2L).flatMap { ReservationNightCharges.of(reservation, ARRIVAL.plusDays(it), fee) }.forEach {
            it.amount.isPositive() shouldBe true
        }
    }

    @Test
    fun `the arrival night of a stay and its last night are charged and the departure night is not`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(3), emptyList())

        ReservationNightCharges.of(reservation, ARRIVAL, fee) shouldHaveSize 2
        ReservationNightCharges.of(reservation, ARRIVAL.plusDays(2), fee) shouldHaveSize 2
        ReservationNightCharges.of(reservation, ARRIVAL.plusDays(3), fee).shouldBeEmpty()
        ReservationNightCharges.of(reservation, ARRIVAL.plusDays(30), fee).shouldBeEmpty()
        ReservationNightCharges.of(reservation, ARRIVAL.minusDays(1), fee).shouldBeEmpty()
    }

    @Test
    fun `a day use charges the room and packages on its only night, never the fee`() {
        val reservation = reservation(ARRIVAL, ARRIVAL, listOf(breakfast, parking))

        val charges = ReservationNightCharges.of(reservation, ARRIVAL, fee)

        charges.map { it.kind } shouldBe listOf(NightChargeKind.ROOM, NightChargeKind.PACKAGE, NightChargeKind.PACKAGE)
        ReservationNightCharges.of(reservation, ARRIVAL.plusDays(1), fee).shouldBeEmpty()
    }

    @Test
    fun `the keys are unique across nights and across charges, and stable between calls`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(4), listOf(breakfast, parking))

        fun all() =
            (0L..3L)
                .flatMap {
                    ReservationNightCharges.of(reservation, ARRIVAL.plusDays(it), fee)
                }.map { it.sourceReference }

        val keys = all()

        keys.toSet() shouldHaveSize keys.size
        keys shouldBe all()
    }

    @Test
    fun `the posting maps code, source, quantity, price, description and service charge rate`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), listOf(breakfast, parking))
        val roomCode = TransactionCodeId.new()
        val tourismCode = TransactionCodeId.new()

        ReservationNightCharges.of(reservation, ARRIVAL, fee).forEach { charge ->
            val posting =
                charge.toNewPosting {
                    when (it) {
                        NightChargeKind.ROOM -> roomCode
                        NightChargeKind.TOURISM_FEE -> tourismCode
                        NightChargeKind.PACKAGE -> error("o pacote traz o código dele")
                    }
                }

            posting.source shouldBe PostingSource.END_OF_DAY
            posting.sourceReference shouldBe charge.sourceReference
            posting.quantity shouldBe charge.quantity
            posting.unitPrice shouldBe charge.unitPrice
            posting.description shouldBe charge.description
            posting.serviceChargeRate shouldBe if (charge.includedInRate) Percent.ZERO else null
            posting.transactionCodeId shouldBe
                when (charge.kind) {
                    NightChargeKind.ROOM -> roomCode
                    NightChargeKind.TOURISM_FEE -> tourismCode
                    NightChargeKind.PACKAGE -> charge.transactionCodeId
                }
        }
    }

    @Test
    fun `keys and descriptions carry only ids, dates and fixed texts`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), listOf(breakfast, parking))
        val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        ReservationNightCharges.of(reservation, ARRIVAL, fee).forEach {
            it.sourceReference shouldMatch
                Regex("reservations\\.reservation:$uuid:\\d{4}-\\d{2}-\\d{2}:(room|package:$uuid|tourism-fee)")
            (it.description ?: "") shouldMatch Regex("|Taxa de turismo|Café da manhã|Estacionamento")
        }
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
        adults: Int = 2,
        childAges: List<Int> = listOf(7),
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
            adults = adults,
            childAges = childAges,
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

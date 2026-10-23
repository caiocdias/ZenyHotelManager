package com.zeny.hotel.frontdesk

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.FolioSummary
import com.zeny.hotel.billing.OwnerBalance
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.frontdesk.domain.EarlyLateFeeChangedException
import com.zeny.hotel.frontdesk.domain.LateCheckOutRules
import com.zeny.hotel.frontdesk.domain.StayCharge
import com.zeny.hotel.frontdesk.domain.StayChargeKind
import com.zeny.hotel.frontdesk.domain.StayCharges
import com.zeny.hotel.frontdesk.domain.StayNotSettledException
import com.zeny.hotel.frontdesk.domain.StayRules
import com.zeny.hotel.frontdesk.domain.StayTransitionNotAllowedException
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
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.MarketSegment
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.sum
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** As regras puras do check-out da recepção (M5 E04): cobranças pendentes (H14), late (D4), saldo (D5) e situação. */
class CheckOutDomainTest {
    private val breakfast = packageCharge("Café da manhã", true, PackagePostingRhythm.ON_ARRIVAL, 2, "20.00")
    private val parking = packageCharge("Estacionamento", false, PackagePostingRhythm.DAILY, 1, "25.00")

    @Test
    fun `a day use checked out on its date charges the room without included packages and every package`() {
        val reservation = reservation(ARRIVAL, ARRIVAL, night(ARRIVAL, "300.00", "40.00", listOf(breakfast, parking)))

        val charges = StayCharges.pending(reservation, ARRIVAL)

        charges shouldHaveSize 3
        val room = charges.single { it.kind == StayChargeKind.ROOM }
        room.amount shouldBe Money.of("260.00")
        room.transactionCodeId.shouldBeNull()
        room.sourceReference shouldBe "reservations.reservation:${reservation.id}:$ARRIVAL:room"
        val coffee = charges.single { it.packageId == breakfast.packageId }
        coffee.kind shouldBe StayChargeKind.PACKAGE
        coffee.quantity.compareTo(BigDecimal(2)) shouldBe 0
        coffee.unitPrice shouldBe Money.of("20.00")
        coffee.description shouldBe "Café da manhã"
        coffee.transactionCodeId shouldBe breakfast.transactionCodeId
        coffee.sourceReference shouldBe
            "reservations.reservation:${reservation.id}:$ARRIVAL:package:${breakfast.packageId}"
        charges.single { it.packageId == parking.packageId }.amount shouldBe Money.of("25.00")
        charges.map(StayCharge::amount).sum() shouldBe reservation.nightlyRates.single().amount
    }

    @Test
    fun `only the room and the included packages are flagged as included in the rate`() {
        val reservation = reservation(ARRIVAL, ARRIVAL, night(ARRIVAL, "300.00", "40.00", listOf(breakfast, parking)))

        val charges = StayCharges.pending(reservation, ARRIVAL)

        charges.single { it.kind == StayChargeKind.ROOM }.includedInRate shouldBe true
        charges.single { it.packageId == breakfast.packageId }.includedInRate shouldBe true
        charges.single { it.packageId == parking.packageId }.includedInRate shouldBe false
    }

    @Test
    fun `an overnight stay checked out on the arrival date charges the arrival night only`() {
        val nights = (0L..2L).map { night(ARRIVAL.plusDays(it), "300.00", "0.00", emptyList()) }
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(3), *nights.toTypedArray())

        val charges = StayCharges.pending(reservation, ARRIVAL)

        charges shouldHaveSize 1
        charges.single().sourceReference shouldBe "reservations.reservation:${reservation.id}:$ARRIVAL:room"
        charges.single().amount shouldBe Money.of("300.00")
    }

    @Test
    fun `a stay checked out after the arrival date charges nothing`() {
        val reservation = reservation(ARRIVAL, ARRIVAL.plusDays(2), night(ARRIVAL, "300.00", "0.00", emptyList()))

        StayCharges.pending(reservation, ARRIVAL.plusDays(1)).shouldBeEmpty()
    }

    @Test
    fun `zero amounts are not charged`() {
        val free = packageCharge("Cortesia", true, PackagePostingRhythm.ON_ARRIVAL, 1, "0.00")
        val included = packageCharge("Jantar", true, PackagePostingRhythm.ON_ARRIVAL, 1, "100.00")
        val reservation =
            reservation(ARRIVAL, ARRIVAL.plusDays(1), night(ARRIVAL, "100.00", "100.00", listOf(free, included)))

        val charges = StayCharges.pending(reservation, ARRIVAL)

        charges shouldHaveSize 1
        charges.single().kind shouldBe StayChargeKind.PACKAGE
        charges.single().packageId shouldBe included.packageId
    }

    @Test
    fun `the source reference never carries personal data`() {
        val reservation = reservation(ARRIVAL, ARRIVAL, night(ARRIVAL, "300.00", "40.00", listOf(breakfast, parking)))
        val pattern =
            Regex("""^reservations\.reservation:[0-9a-f-]{36}:\d{4}-\d{2}-\d{2}:(room|package:[0-9a-f-]{36})$""")

        val charges = StayCharges.pending(reservation, ARRIVAL)

        charges shouldHaveSize 3
        charges.forEach { pattern.matches(it.sourceReference) shouldBe true }
    }

    @Test
    fun `the late fee is accepted only as quoted by the current policy`() {
        val offer = EarlyLateOffer(true, null, Money.of("80.00"), null, null)
        val none = EarlyLateOffer(false, null, null, null, null)

        LateCheckOutRules.acceptedFee(offer, null).shouldBeNull()
        LateCheckOutRules.acceptedFee(offer, Money.of("80.00")) shouldBe Money.of("80.00")
        val changed =
            shouldThrow<EarlyLateFeeChangedException> { LateCheckOutRules.acceptedFee(offer, Money.of("70.00")) }
        changed.code.value shouldBe "EARLY_LATE_FEE_CHANGED"
        changed.problemProperties["currentFee"] shouldBe "80.00"
        val refused = shouldThrow<BusinessRuleException> { LateCheckOutRules.acceptedFee(none, Money.of("80.00")) }
        refused.code.value shouldBe "EARLY_LATE_NOT_OFFERED"
    }

    @Test
    fun `a balance not settled lists every folio with its balance`() {
        val debt = folio("100.00")
        val settled = folio("0.00")

        val refused =
            shouldThrow<StayNotSettledException> {
                throw StayNotSettledException(OwnerBalance(debt.owner, listOf(debt, settled)))
            }

        refused.code.value shouldBe "FOLIO_BALANCE_NOT_ZERO"
        val folios = refused.problemProperties["folios"] as List<*>
        folios shouldHaveSize 2
        val first = folios[0] as Map<*, *>
        first["folioId"] shouldBe debt.id.toString()
        first["number"] shouldBe debt.number.toString()
        first["status"] shouldBe "OPEN"
        first["balance"] shouldBe "100.00"
        (folios[1] as Map<*, *>)["balance"] shouldBe "0.00"
        refused.problemProperties["total"] shouldBe "100.00"
    }

    @Test
    fun `only an in-house reservation checks out`() {
        StayRules.requireCheckOut(reservation(ARRIVAL, ARRIVAL, status = ReservationStatus.IN_HOUSE))
        listOf(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_OUT).forEach { status ->
            val refused =
                shouldThrow<StayTransitionNotAllowedException> {
                    StayRules.requireCheckOut(reservation(ARRIVAL, ARRIVAL, status = status))
                }
            refused.code.value shouldBe "RESERVATION_TRANSITION_NOT_ALLOWED"
            refused.problemProperties["reservationStatus"] shouldBe status.name
            refused.problemProperties["action"] shouldBe "CHECK_OUT"
        }
    }

    private fun packageCharge(
        name: String,
        included: Boolean,
        rhythm: PackagePostingRhythm,
        quantity: Int,
        unitPrice: String,
    ) = PackageCharge(
        packageId = PackageId(UuidV7.generate()),
        code = name.take(3).uppercase(),
        name = name,
        transactionCodeId = TransactionCodeId.new(),
        pricing = PackagePricing.PER_NIGHT,
        postingRhythm = rhythm,
        includedInRate = included,
        quantity = quantity,
        unitPrice = Money.of(unitPrice),
        amount = Money.of(unitPrice) * quantity,
    )

    private fun night(
        date: LocalDate,
        room: String,
        included: String,
        packages: List<PackageCharge>,
    ): NightlyRateSummary {
        val additional = packages.filter { !it.includedInRate }.map { it.amount }.sum()
        return NightlyRateSummary(
            date = date,
            ratePlanId = PLAN,
            calculatedRoomAmount = Money.of(room),
            roomAmount = Money.of(room),
            includedPackagesAmount = Money.of(included),
            additionalPackagesAmount = additional,
            amount = Money.of(room) + additional,
            overridden = false,
            discountPercent = null,
            packages = packages,
        )
    }

    private fun folio(balance: String) =
        FolioSummary(
            id = FolioId.new(),
            number = DocumentNumber.parse("C-2026-000001"),
            owner = FolioOwner(FolioOwnerType.RESERVATION, UUID.randomUUID()),
            payerCompanyId = null,
            creditLimit = null,
            status = FolioStatus.OPEN,
            openedOn = ARRIVAL,
            balance = Money.of(balance),
        )

    @Suppress("LongMethod") // a reserva inteira, com todos os campos
    private fun reservation(
        arrival: LocalDate,
        departure: LocalDate,
        vararg nights: NightlyRateSummary,
        status: ReservationStatus = ReservationStatus.IN_HOUSE,
    ): ReservationSummary =
        ReservationSummary(
            id = ReservationId.new(),
            code = DocumentNumber.parse("R-2026-000001"),
            bookingId = BookingId.new(),
            status = status,
            provisionalUntil = null,
            roomTypeId = RoomTypeId(UuidV7.generate()),
            ratePlanId = PLAN,
            ratePlanCategory = RatePlanCategory.NORMAL,
            arrival = arrival,
            departure = departure,
            adults = 2,
            childAges = emptyList(),
            segment = MarketSegment.LEISURE,
            specialRequests = null,
            guests = emptyList(),
            nightlyRates = nights.toList(),
            roomTotal = Money.ZERO,
            additionalPackagesTotal = Money.ZERO,
            total = nights.map { it.amount }.sum(),
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

    private companion object {
        val ARRIVAL: LocalDate = LocalDate.of(2026, 10, 22)
        val PLAN = RatePlanId(UuidV7.generate())
    }
}

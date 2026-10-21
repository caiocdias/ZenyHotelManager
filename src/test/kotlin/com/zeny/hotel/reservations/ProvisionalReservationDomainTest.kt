package com.zeny.hotel.reservations

import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.reservations.domain.CancellationRules
import com.zeny.hotel.reservations.domain.DepositInsufficientException
import com.zeny.hotel.reservations.domain.GuaranteeMethodNotAcceptedException
import com.zeny.hotel.reservations.domain.GuaranteeRules
import com.zeny.hotel.reservations.domain.ProvisionalValidity
import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationTransitions
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * As regras da pré-reserva (M4 E09; RF-RES-08 no recorte manual, RN-23, RF-TAR-07, H13, H15): só a pré-reserva é
 * confirmada; a garantia informada tem de ser a da política copiada; o depósito exigido é o percentual da política
 * sobre o total da reserva, pela regra de `Money`, e o depósito recebido precisa cobri-lo; a validade vence no instante
 * exato; o motivo "expirada" é só do sistema.
 */
class ProvisionalReservationDomainTest {
    @Test
    fun `only a provisional reservation is confirmed`() {
        ReservationStatus.entries.filter { ReservationTransitions.allows(ReservationAction.CONFIRM, it) } shouldBe
            listOf(ReservationStatus.PROVISIONAL)
    }

    @Test
    fun `the guarantee informed has to be the one the copied policy asks for`() {
        GuaranteeRules.requireAccepted(terms(GuaranteeType.CARD), GuaranteeMethod.CARD)
        GuaranteeRules.requireAccepted(deposit("30"), GuaranteeMethod.DEPOSIT)
        // Uma política sem garantia aceita qualquer uma (a pré-reserva nela não nasce, mas a regra é total).
        GuaranteeRules.requireAccepted(terms(GuaranteeType.NONE), GuaranteeMethod.CARD)
        GuaranteeRules.requireAccepted(terms(GuaranteeType.NONE), GuaranteeMethod.DEPOSIT)

        val card =
            shouldThrow<GuaranteeMethodNotAcceptedException> {
                GuaranteeRules.requireAccepted(deposit("30"), GuaranteeMethod.CARD)
            }
        card.code shouldBe ReservationsErrorCodes.GUARANTEE_METHOD_NOT_ACCEPTED
        card.problemProperties shouldBe mapOf("guaranteeType" to "DEPOSIT", "method" to "CARD")
        shouldThrow<GuaranteeMethodNotAcceptedException> {
            GuaranteeRules.requireAccepted(terms(GuaranteeType.CARD), GuaranteeMethod.DEPOSIT)
        }
    }

    @Test
    fun `the required deposit is the percent of the total by the Money rule, and the received has to cover it`() {
        deposit("30").requiredDeposit(Money.of("800.00")) shouldBe Money.of("240.00")
        // 12,5% de 333,32 = 41,665: o arredondamento único de Money (HALF_EVEN) dá 41,66.
        deposit("12.5").requiredDeposit(Money.of("333.32")) shouldBe Money.of("41.66")
        deposit("100").requiredDeposit(Money.of("0.00")) shouldBe Money.ZERO
        terms(GuaranteeType.CARD).requiredDeposit(Money.of("800.00")) shouldBe null
        deposit("30").depositDueOn(LocalDate.of(2026, 11, 10)) shouldBe LocalDate.of(2026, 11, 3)
        terms(GuaranteeType.CARD).depositDueOn(LocalDate.of(2026, 11, 10)) shouldBe null

        GuaranteeRules.requireDeposit(Money.of("240.00"), Money.of("240.00"))
        GuaranteeRules.requireDeposit(Money.of("240.00"), Money.of("300.00"))
        val short =
            shouldThrow<DepositInsufficientException> {
                GuaranteeRules.requireDeposit(Money.of("240.00"), Money.of("239.99"))
            }
        short.code shouldBe ReservationsErrorCodes.DEPOSIT_INSUFFICIENT
        short.problemProperties shouldBe mapOf("requiredDeposit" to "240.00", "depositReceived" to "239.99")
        // Um depósito devolvido além do recebido deixa o líquido negativo, nunca suficiente.
        shouldThrow<DepositInsufficientException> { GuaranteeRules.requireDeposit(Money.ZERO, Money.of("-0.01")) }
    }

    @Test
    fun `the validity expires at the exact instant`() {
        val until = Instant.parse("2026-11-08T02:59:59.999999Z")
        ProvisionalValidity.expired(until, until.minusNanos(1_000)) shouldBe false
        ProvisionalValidity.expired(until, until) shouldBe true
        ProvisionalValidity.expired(until, until.plusSeconds(1)) shouldBe true
    }

    @Test
    fun `nobody cancels by hand with the expiry reason`() {
        val refused =
            shouldThrow<InvalidInputException> {
                CancellationRules.requireValid(ReservationCancellationRequest(CancellationReason.EXPIRED))
            }
        refused.code shouldBe ReservationsErrorCodes.RESERVATION_CANCELLATION_INVALID
        refused.violations.single().field shouldBe "reason"
        CancellationRules.requireValid(ReservationCancellationRequest(CancellationReason.NO_GUARANTEE))
    }

    private fun terms(type: GuaranteeType) =
        GuaranteeTerms(GuaranteePolicyId(UuidV7.generate()), "Política", 0, type, null, null)

    private fun deposit(percent: String) =
        GuaranteeTerms(
            GuaranteePolicyId(UuidV7.generate()),
            "Depósito",
            0,
            GuaranteeType.DEPOSIT,
            Percent.of(percent),
            7,
        )
}

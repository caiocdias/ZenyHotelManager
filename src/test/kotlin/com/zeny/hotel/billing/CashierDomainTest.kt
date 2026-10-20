package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.CashMovement
import com.zeny.hotel.billing.domain.CashPoint
import com.zeny.hotel.billing.domain.CashierClosing
import com.zeny.hotel.billing.domain.CashierDifferenceNotice
import com.zeny.hotel.billing.domain.CashierShift
import com.zeny.hotel.billing.domain.ShiftExpectation
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Regras do caixa (M3 E07, RF-CTA-09): o esperado por forma de pagamento (fundo + suprimentos − sangrias no dinheiro;
 * as demais só com pagamentos, zero até a E08), o fechamento com a diferença por forma, a contagem declarada, a
 * visibilidade do esperado no fechamento cego, os movimentos e o texto da notificação de diferença.
 */
class CashierDomainTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val now = Instant.parse("2026-10-19T15:00:00Z")
    private val operator = UserId(UuidV7.generate())
    private val number = DocumentNumber("CX", 2026, 12)

    @Test
    fun `the expected cash is the opening float plus supplies minus withdrawals, and the other methods are zero`() {
        val shift = open(blind = true, float = "200.00")
        val movements =
            listOf(
                movement(shift, CashMovementType.SUPPLY, "50.00"),
                movement(shift, CashMovementType.WITHDRAWAL, "120.00"),
                movement(shift, CashMovementType.WITHDRAWAL, "30.00"),
            )
        val expectation = ShiftExpectation.of(shift.openingFloat, movements)

        val cash = expectation.of(PaymentMethod.CASH)
        cash.openingFloat shouldBe Money.of("200.00")
        cash.supplies shouldBe Money.of("50.00")
        cash.withdrawals shouldBe Money.of("150.00")
        cash.expected shouldBe Money.of("100.00")
        expectation.methods.map { it.method } shouldContainExactly PaymentMethod.entries
        PaymentMethod.entries.filter { it != PaymentMethod.CASH }.forEach {
            expectation.of(it).expected shouldBe Money.ZERO
        }
        expectation.expectedTotal shouldBe Money.of("100.00")
        // Sangria acima do dinheiro esperado não é recusada (revelaria o esperado): o negativo vira diferença.
        ShiftExpectation
            .of(Money.ZERO, listOf(movement(shift, CashMovementType.WITHDRAWAL, "10.00")))
            .of(PaymentMethod.CASH)
            .expected shouldBe Money.of("-10.00")
    }

    @Test
    fun `closing records one count per method with the difference, the omitted methods declared as zero`() {
        val shift = open(blind = true, float = "100.00")
        val expectation = ShiftExpectation.of(shift.openingFloat, emptyList())
        val declared =
            CashierClosing.declared(
                listOf(
                    PaymentMethod.CASH to Money.of("90.00"),
                    PaymentMethod.PIX to Money.of("5.00"),
                ),
            )

        val closing = shift.close(declared, expectation, day, now)

        closing.counts.map { it.method } shouldContainExactly PaymentMethod.entries
        val cash = closing.counts.single { it.method == PaymentMethod.CASH }
        cash.declared shouldBe Money.of("90.00")
        cash.expected shouldBe Money.of("100.00")
        cash.difference shouldBe Money.of("-10.00")
        closing.counts.single { it.method == PaymentMethod.PIX }.difference shouldBe Money.of("5.00")
        closing.counts.single { it.method == PaymentMethod.DEBIT_CARD }.declared shouldBe Money.ZERO
        closing.hasDifference shouldBe true
        closing.differences.map { it.method } shouldContainExactly listOf(PaymentMethod.CASH, PaymentMethod.PIX)
        closing.differenceTotal shouldBe Money.of("-5.00")
        closing.declaredTotal shouldBe Money.of("95.00")
        shift.status shouldBe CashierShiftStatus.CLOSED
        shift.closedOn shouldBe day
        shift.differenceTotal shouldBe Money.of("-5.00")

        shouldThrow<ConflictException> { shift.close(declared, expectation, day, now) }.code.value shouldBe
            "CASHIER_SHIFT_CLOSED"

        val exact = open(blind = false, float = "100.00")
        val noDifference =
            exact.close(
                CashierClosing.declared(listOf(PaymentMethod.CASH to Money.of("100.00"))),
                ShiftExpectation.of(exact.openingFloat, emptyList()),
                day,
                now,
            )
        noDifference.hasDifference shouldBe false
        noDifference.differenceTotal shouldBe Money.ZERO
    }

    @Test
    fun `the declared count refuses a repeated method and a negative or too large amount, with the field`() {
        val repeated =
            shouldThrow<InvalidInputException> {
                CashierClosing.declared(
                    listOf(PaymentMethod.CASH to Money.of("1.00"), PaymentMethod.CASH to Money.of("2.00")),
                )
            }
        repeated.code.value shouldBe "CASHIER_COUNT_INVALID"
        repeated.violations.map { it.field } shouldContainExactly listOf("counts[1].method")
        shouldThrow<InvalidInputException> {
            CashierClosing.declared(
                listOf(PaymentMethod.PIX to Money.of("-0.01"), PaymentMethod.CASH to Money.of("1000000.01")),
            )
        }.violations.map { it.field } shouldContainExactly listOf("counts[0].amount", "counts[1].amount")
    }

    @Test
    fun `the expected amount of a blind shift is hidden until it closes, except to a reviewer`() {
        val blind = open(blind = true)
        blind.expectedVisible(reviewer = false) shouldBe false
        blind.expectedVisible(reviewer = true) shouldBe true
        val open = open(blind = false)
        open.expectedVisible(reviewer = false) shouldBe true

        blind.close(emptyMap(), ShiftExpectation.of(blind.openingFloat, emptyList()), day, now)
        blind.expectedVisible(reviewer = false) shouldBe true
    }

    @Test
    fun `a movement needs the operator, an open shift, a positive amount and a reason`() {
        val shift = open(blind = true)
        movement(shift, CashMovementType.SUPPLY, "0.01").amount shouldBe Money.of("0.01")
        shouldThrow<PermissionDeniedException> {
            CashMovement.record(
                shift,
                CashMovementType.SUPPLY,
                Money.of("1.00"),
                "Troco",
                day,
                now,
                UserId(UuidV7.generate()),
            )
        }.code.value shouldBe "CASHIER_SHIFT_OPERATOR_REQUIRED"
        listOf("0.00", "-1.00", "1000000.01").forEach { amount ->
            shouldThrow<InvalidInputException> { movement(shift, CashMovementType.WITHDRAWAL, amount) }
                .violations
                .single()
                .field shouldBe "amount"
        }
        shouldThrow<InvalidInputException> {
            CashMovement.record(shift, CashMovementType.SUPPLY, Money.of("1.00"), "  ", day, now, operator)
        }.violations.single().field shouldBe "reason"

        shift.close(emptyMap(), ShiftExpectation.of(shift.openingFloat, emptyList()), day, now)
        shouldThrow<ConflictException> { movement(shift, CashMovementType.SUPPLY, "1.00") }.code.value shouldBe
            "CASHIER_SHIFT_CLOSED"
    }

    @Test
    fun `a shift opens only on an active cash point, with a float from zero to the ceiling, keeping the blind mode`() {
        val point = CashPoint.create("Recepção", blindClose = false, now)
        CashierShift.open(number, point, operator, Money.ZERO, day, now).blindClose shouldBe false
        shouldThrow<InvalidInputException> { CashierShift.open(number, point, operator, Money.of("-1.00"), day, now) }
            .violations
            .single()
            .field shouldBe "openingFloat"
        point.deactivate(now)
        shouldThrow<BusinessRuleException> { CashierShift.open(number, point, operator, Money.ZERO, day, now) }
            .code
            .value shouldBe "CASH_POINT_INACTIVE"
    }

    @Test
    fun `the difference notice has the shift, the cash point, the business date and the values, and no person`() {
        val shift = open(blind = true, float = "100.00")
        val closing =
            shift.close(
                CashierClosing.declared(
                    listOf(PaymentMethod.CASH to Money.of("90.00"), PaymentMethod.CREDIT_CARD to Money.of("1234.50")),
                ),
                ShiftExpectation.of(shift.openingFloat, emptyList()),
                day,
                now,
            )

        CashierDifferenceNotice.title(number) shouldBe "Diferença no fechamento do caixa CX-2026-000012"
        val body = CashierDifferenceNotice.body(number, "Bar da piscina", day, closing)
        body shouldContain "CX-2026-000012"
        body shouldContain "Bar da piscina"
        body shouldContain "19/10/2026"
        body shouldContain "Dinheiro: falta de R$ 10,00."
        body shouldContain "Cartão de crédito: sobra de R$ 1.234,50."
        body shouldContain "Total: sobra de R$ 1.224,50."
        body shouldNotContain "Pix"
        body shouldNotContain operator.value.toString()
    }

    private fun open(
        blind: Boolean,
        float: String = "0.00",
    ): CashierShift =
        CashierShift.open(
            number,
            CashPoint.create("Caixa ${UuidV7.generate()}", blind, now),
            operator,
            Money.of(float),
            day,
            now,
        )

    private fun movement(
        shift: CashierShift,
        type: CashMovementType,
        amount: String,
    ): CashMovement = CashMovement.record(shift, type, Money.of(amount), "Motivo", day, now, operator)
}

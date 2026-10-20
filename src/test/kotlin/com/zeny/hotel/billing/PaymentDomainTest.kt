package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.CashPoint
import com.zeny.hotel.billing.domain.CashierShift
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Payment
import com.zeny.hotel.billing.domain.PaymentDraft
import com.zeny.hotel.billing.domain.PaymentReferences
import com.zeny.hotel.billing.domain.PaymentRefunds
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.billing.domain.ShiftExpectation
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Regras dos pagamentos (M3 E08, RF-CTA-06, 07 e 15, RNF-10): a forma aceita (`CITY_LEDGER` só no M10), o troco só no
 * dinheiro, os dados da transação sem nada com cara de número de cartão, o reembolso na mesma forma e até o
 * reembolsável, e o esperado do turno com os pagamentos e os reembolsos.
 */
class PaymentDomainTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val now = Instant.parse("2026-10-19T15:00:00Z")
    private val operator = UserId(UuidV7.generate())

    @Test
    fun `cash change is the tendered amount minus the applied one, and only in cash`() {
        val cash = draft("CASH", "100.00", tendered = "150.00")
        cash.change shouldBe Money.of("50.00")
        draft("CASH", "100.00", tendered = "100.00").change shouldBe Money.ZERO
        draft("CASH", "100.00").change shouldBe null
        refused("tendered") { draft("CASH", "100.00", tendered = "99.99") }
        refused("tendered") { draft("PIX", "100.00", tendered = "100.00") }
        refused("amount") { draft("PIX", "0.00") }
        refused("amount") { draft("PIX", "-1.00") }
        refused("amount") { draft("PIX", "1000000.01") }
        draft("PIX", "1000000.00").amount shouldBe Money.of("1000000.00")
    }

    @Test
    fun `CITY_LEDGER is not available until M10 and an unknown method is a format error`() {
        shouldThrow<BusinessRuleException> { draft("CITY_LEDGER", "10.00") }.code.value shouldBe
            "PAYMENT_METHOD_NOT_AVAILABLE"
        refused("method") { draft("BITCOIN", "10.00") }
        PaymentMethod.entries.forEach { draft(it.name, "10.00").method shouldBe it }
    }

    @Test
    fun `transaction data never carries a card number`() {
        val card = PaymentReferences.of(PaymentMethod.CREDIT_CARD, " 00451 ", "ab12cd", "master  card", "4242")
        card shouldBe PaymentReferences("00451", "AB12CD", "MASTER CARD", "4242")
        card.providerReference shouldBe "00451"
        PaymentReferences.of(PaymentMethod.PIX, null, "e2e1").providerReference shouldBe "E2E1"
        PaymentReferences.of(PaymentMethod.PIX, "", " ") shouldBe PaymentReferences()
        PaymentReferences.of(PaymentMethod.OTHER, "123456789012", null).nsu shouldBe "123456789012"

        withClue("13 a 19 dígitos, juntos ou entremeados, têm cara de número de cartão") {
            listOf("4111111111111", "4111111111111111", "4111111111111111111", "A41111111111111", "1234567890123X")
                .forEach { value ->
                    refused("nsu") { PaymentReferences.of(PaymentMethod.CREDIT_CARD, value, null) }
                    refused("authorizationCode") { PaymentReferences.of(PaymentMethod.CREDIT_CARD, null, value) }
                }
        }
        refused("nsu") { PaymentReferences.of(PaymentMethod.CREDIT_CARD, "4111-1111", null) }
        refused("nsu") { PaymentReferences.of(PaymentMethod.CREDIT_CARD, "A".repeat(21), null) }
        refused("cardLast4") { PaymentReferences.of(PaymentMethod.CREDIT_CARD, null, null, "VISA", "4111111111111111") }
        refused("cardLast4") { PaymentReferences.of(PaymentMethod.DEBIT_CARD, null, null, null, "12a4") }
        refused("cardBrand") { PaymentReferences.of(PaymentMethod.DEBIT_CARD, null, null, "4111111111111111") }
        refused("cardBrand") { PaymentReferences.of(PaymentMethod.PIX, null, null, "VISA") }
        refused("cardLast4") { PaymentReferences.of(PaymentMethod.BANK_TRANSFER, null, null, null, "1234") }
        refused("nsu") { PaymentReferences.of(PaymentMethod.CASH, "123", null) }
        refused("authorizationCode") { PaymentReferences.of(PaymentMethod.CASH, null, "ABC") }
    }

    @Test
    fun `a payment needs an open folio and the open shift of who receives`() {
        val shift = shift()
        val folio = folio()
        val payment = Payment.receive(folio, draft("PIX", "80.00", deposit = true), shift, UuidV7.generate(), stamp())
        payment.type shouldBe PaymentType.PAYMENT
        payment.deposit shouldBe true
        payment.received shouldBe Money.of("80.00")
        payment.shiftId shouldBe shift.id

        shouldThrow<PermissionDeniedException> {
            Payment.receive(folio, draft("PIX", "1.00"), shift, UuidV7.generate(), stamp(UserId(UuidV7.generate())))
        }
    }

    @Test
    fun `a refund keeps the method, never passes the refundable and never refunds a refund`() {
        val shift = shift()
        val folio = folio()
        val card = draft("CREDIT_CARD", "100.00", brand = "VISA", last4 = "4242")
        val payment = Payment.receive(folio, card, shift, UuidV7.generate(), stamp())

        val first = PaymentRefunds(payment, emptyList())
        first.refundable shouldBe Money.of("100.00")
        first.requireRefundable(Money.of("30.00")) shouldBe Money.of("30.00")
        val refund =
            first.refund(
                folio,
                Money.of("30.00"),
                "Desistência",
                PaymentReferences.of(PaymentMethod.CREDIT_CARD, "998877", null),
                shift,
                stamp(),
                null,
            )
        refund.type shouldBe PaymentType.REFUND
        refund.method shouldBe PaymentMethod.CREDIT_CARD
        refund.refundOfId shouldBe payment.id
        refund.references shouldBe PaymentReferences("998877", null, "VISA", "4242")
        refund.received shouldBe Money.of("-30.00")

        val second = PaymentRefunds(payment, listOf(refund))
        second.refunded shouldBe Money.of("30.00")
        second.refundable shouldBe Money.of("70.00")
        withClue("o total que o limite confere é o reembolsado mais este") {
            second.requireRefundable(Money.of("70.00")) shouldBe Money.of("100.00")
        }
        shouldThrow<BusinessRuleException> { second.requireRefundable(Money.of("70.01")) }.code.value shouldBe
            "REFUND_EXCEEDS_REFUNDABLE"
        shouldThrow<BusinessRuleException> {
            PaymentRefunds(refund, emptyList()).requireRefundable(Money.of("1.00"))
        }.code.value shouldBe "PAYMENT_NOT_REFUNDABLE"
    }

    @Test
    fun `the shift expected adds payments and subtracts refunds per method`() {
        val expectation =
            ShiftExpectation.of(
                Money.of("100.00"),
                emptyList(),
                mapOf(PaymentMethod.CASH to Money.of("-40.00"), PaymentMethod.PIX to Money.of("250.00")),
            )
        expectation.of(PaymentMethod.CASH).expected shouldBe Money.of("60.00")
        expectation.of(PaymentMethod.PIX).expected shouldBe Money.of("250.00")
        expectation.expectedTotal shouldBe Money.of("310.00")
    }

    @Suppress("LongParameterList") // os campos opcionais do recebimento
    private fun draft(
        method: String,
        amount: String,
        tendered: String? = null,
        deposit: Boolean = false,
        brand: String? = null,
        last4: String? = null,
    ) = PaymentDraft.of(method, Money.of(amount), tendered?.let(Money::of), deposit, null, null, brand, last4)

    private fun refused(
        field: String,
        block: () -> Any?,
    ) {
        val error = shouldThrow<InvalidInputException> { block() }
        (error as DomainException).code.value shouldBe "PAYMENT_INVALID"
        error.violations.single().field shouldBe field
    }

    private fun shift(): CashierShift =
        CashierShift.open(
            DocumentNumber("CX", 2026, (1L..999_999L).random()),
            CashPoint.create("Recepção", blindClose = true, now),
            operator,
            Money.ZERO,
            day,
            now,
        )

    private fun folio() =
        Folio.open(
            DocumentNumber("C", 2026, (1L..999_999L).random()),
            FolioOwnerType.RESERVATION,
            UuidV7.generate(),
            null,
            null,
            day,
            null,
            now,
        )

    private fun stamp(by: UserId = operator) = PostingStamp(day, now, by)
}

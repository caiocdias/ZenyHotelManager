package com.zeny.hotel.payments

import com.zeny.hotel.payments.application.PaymentProviders
import com.zeny.hotel.payments.application.ProviderOutcome
import com.zeny.hotel.payments.domain.PaymentIntent
import com.zeny.hotel.payments.infrastructure.ManualPaymentProvider
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A intenção de pagamento neutra (M3 E08, H1): nasce pendente, o provedor a confirma ou recusa uma vez só, a referência
 * nunca tem cara de número de cartão, o provedor `MANUAL` confirma na hora e o registro recusa código repetido.
 */
class PaymentIntentTest {
    private val now = Instant.parse("2026-10-19T15:00:00Z")
    private val target = PaymentTarget("billing.folio", UuidV7.generate())

    @Test
    fun `an intent starts pending and settles once`() {
        val intent = intent("50.00")
        intent.status shouldBe PaymentIntentStatus.PENDING
        intent.markSucceeded("NSU123", now) shouldBe true
        intent.status shouldBe PaymentIntentStatus.SUCCEEDED
        intent.providerReference shouldBe "NSU123"
        intent.succeededAt shouldBe now
        intent.markSucceeded("NSU123", now) shouldBe false
        shouldThrow<IllegalStateException> { intent.markFailed("Recusado", now) }

        val failed = intent("10.00")
        failed.markFailed("Saldo insuficiente", now) shouldBe true
        failed.status shouldBe PaymentIntentStatus.FAILED
        shouldThrow<IllegalStateException> { failed.markSucceeded(null, now) }
    }

    @Test
    fun `an intent needs a positive amount and a reference that is never a card number`() {
        shouldThrow<InvalidInputException> { intent("0.00") }.code shouldBe PaymentsErrorCodes.PAYMENT_INTENT_INVALID
        PaymentIntent.reference(" E2E-123.a_b ") shouldBe "E2E-123.a_b"
        PaymentIntent.reference("  ") shouldBe null
        listOf("4111111111111111", "4111-1111-1111-1111", "x".repeat(41), "com espaço").forEach {
            shouldThrow<InvalidInputException> { PaymentIntent.reference(it) }
        }
        shouldThrow<IllegalArgumentException> { PaymentPurpose("Billing.Payment") }
        shouldThrow<IllegalArgumentException> { PaymentTarget("folio", UuidV7.generate()) }
        shouldThrow<IllegalArgumentException> { PaymentProviderCode("manual") }
    }

    @Test
    fun `the manual provider confirms at once with the informed reference, and provider codes are unique`() {
        val manual = ManualPaymentProvider()
        manual.code shouldBe PaymentProviderCode.MANUAL
        manual.start(intent("10.00"), "NSU9") shouldBe ProviderOutcome.Succeeded("NSU9")
        PaymentProviders(listOf(manual)).of(PaymentProviderCode.MANUAL) shouldBe manual
        shouldThrow<IllegalArgumentException> {
            PaymentProviders(listOf(manual)).of(PaymentProviderCode("PIX_PSP"))
        }
        shouldThrow<IllegalStateException> { PaymentProviders(listOf(manual, ManualPaymentProvider())) }
            .message shouldContain "MANUAL"
    }

    private fun intent(amount: String) =
        PaymentIntent.start(
            PaymentPurpose("billing.folio-payment"),
            target,
            Money.of(amount),
            PaymentProviderCode.MANUAL,
            null,
            now,
        )
}

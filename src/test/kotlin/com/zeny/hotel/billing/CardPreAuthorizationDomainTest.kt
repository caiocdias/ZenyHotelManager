package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.CardPreAuthorization
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Regras da caução (M3 E09, RF-CTA-08; decisões do usuário em 2026-10-19): a expiração calculada na leitura, as
 * capturas parciais até o autorizado, a captura total que encerra, a liberação da sobra e o registro com os dados da
 * maquininha sem nada com cara de número de cartão.
 */
class CardPreAuthorizationDomainTest {
    private val day = LocalDate.of(2026, 10, 20)
    private val now = Instant.parse("2026-10-20T15:00:00Z")
    private val operator = UserId(UuidV7.generate())
    private val expiresAt = now.plus(Duration.ofDays(3))

    @Test
    fun `expiry is computed on read and only while the pre-authorization can still be captured`() {
        val active = registered("500.00")
        active.statusAt(now) shouldBe CardPreAuthorizationStatus.ACTIVE
        active.statusAt(expiresAt.minusNanos(1)) shouldBe CardPreAuthorizationStatus.ACTIVE
        active.statusAt(expiresAt) shouldBe CardPreAuthorizationStatus.EXPIRED
        active.capturableAt(expiresAt) shouldBe Money.ZERO
        active.status shouldBe CardPreAuthorizationStatus.ACTIVE

        val captured = active.capture(Money.of("500.00"), now)
        captured.statusAt(expiresAt.plus(Duration.ofDays(1))) shouldBe CardPreAuthorizationStatus.CAPTURED
        val released = active.release(stamp()).first
        released.statusAt(expiresAt.plus(Duration.ofDays(1))) shouldBe CardPreAuthorizationStatus.RELEASED
        shouldThrow<IllegalArgumentException> { active.copy(status = CardPreAuthorizationStatus.EXPIRED) }
    }

    @Test
    fun `partial captures add up to the authorized amount, never beyond it`() {
        val first = registered("500.00").capture(Money.of("200.00"), now)
        first.status shouldBe CardPreAuthorizationStatus.PARTIALLY_CAPTURED
        first.capturedAmount shouldBe Money.of("200.00")
        first.capturableAt(now) shouldBe Money.of("300.00")
        first.version shouldBe 1L

        shouldThrow<BusinessRuleException> { first.capture(Money.of("300.01"), now) }.code shouldBe
            BillingErrorCodes.CAPTURE_EXCEEDS_AUTHORIZED
        val total = first.capture(Money.of("300.00"), now)
        total.status shouldBe CardPreAuthorizationStatus.CAPTURED
        total.capturableAt(now) shouldBe Money.ZERO
        shouldThrow<ConflictException> { total.capture(Money.of("0.01"), now) }.code shouldBe
            BillingErrorCodes.PRE_AUTHORIZATION_SETTLED
        shouldThrow<ConflictException> { total.release(stamp()) }.code shouldBe
            BillingErrorCodes.PRE_AUTHORIZATION_SETTLED
        refused("amount") { first.capture(Money.ZERO, now) }
    }

    @Test
    fun `an expired pre-authorization is never captured, but its leftover can still be released`() {
        val partial = registered("500.00").capture(Money.of("100.00"), now)
        shouldThrow<BusinessRuleException> { partial.capture(Money.of("1.00"), expiresAt) }.code shouldBe
            BillingErrorCodes.PRE_AUTHORIZATION_EXPIRED
        val (released, release) = partial.release(stamp(at = expiresAt.plus(Duration.ofDays(1))))
        released.status shouldBe CardPreAuthorizationStatus.RELEASED
        released.statusAt(expiresAt.plus(Duration.ofDays(2))) shouldBe CardPreAuthorizationStatus.RELEASED
        release.amount shouldBe Money.of("400.00")
        shouldThrow<ConflictException> { released.capture(Money.of("1.00"), expiresAt) }.code shouldBe
            BillingErrorCodes.PRE_AUTHORIZATION_SETTLED
    }

    @Test
    fun `the release frees what is still authorized and settles the pre-authorization`() {
        val partial = registered("500.00").capture(Money.of("120.00"), now)
        val (released, release) = partial.release(stamp())
        released.status shouldBe CardPreAuthorizationStatus.RELEASED
        released.capturedAmount shouldBe Money.of("120.00")
        release.amount shouldBe Money.of("380.00")
        release.preAuthorizationId shouldBe partial.id
        release.releasedBy shouldBe operator
        shouldThrow<ConflictException> { released.capture(Money.of("1.00"), now) }.code shouldBe
            BillingErrorCodes.PRE_AUTHORIZATION_SETTLED
        registered("80.00").release(stamp()).second.amount shouldBe Money.of("80.00")
    }

    @Test
    fun `registration checks the amount, the validity and the terminal data, never a card number`() {
        refused("amount") { registered("0.00") }
        refused("amount") { registered("1000000.01") }
        refused("expiresAt") { registered("10.00", expires = now) }
        refused("expiresAt") { registered("10.00", expires = now.plus(Duration.ofDays(90)).plusSeconds(1)) }
        registered("10.00", expires = now.plus(Duration.ofDays(90))).expiresAt shouldBe now.plus(Duration.ofDays(90))
        refused("authorizationCode") { registered("10.00", authorization = null) }
        refused("authorizationCode") { registered("10.00", authorization = "4111111111111111") }
        refused("nsu") { CardPreAuthorization.references("1234567890123", "A1") }
        refused("cardLast4") { CardPreAuthorization.references(null, "A1", cardLast4 = "41111111") }
        val references = CardPreAuthorization.references(" 0045 ".trim(), "ab12", " visa ", "4242")
        references.authorizationCode shouldBe "AB12"
        references.cardBrand shouldBe "VISA"
    }

    private fun registered(
        amount: String,
        expires: Instant = expiresAt,
        authorization: String? = "AUT1",
    ): CardPreAuthorization =
        CardPreAuthorization.register(
            folio(),
            Money.of(amount),
            expires,
            CardPreAuthorization.references(null, authorization),
            stamp(),
        )

    private fun refused(
        field: String,
        block: () -> Unit,
    ) {
        val error = shouldThrow<InvalidInputException>(block)
        error.code shouldBe BillingErrorCodes.PRE_AUTHORIZATION_INVALID
        error.violations.single().field shouldBe field
    }

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

    private fun stamp(at: Instant = now) = PostingStamp(day, at, operator)
}

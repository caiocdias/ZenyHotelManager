package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.FolioHasActivePreAuthorizationException
import com.zeny.hotel.billing.domain.FolioNotSettledException
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Encerramento e reabertura da conta no domínio (M3 E10, RF-CTA-11, RN-06): só com saldo exatamente zero (um centavo,
 * devedor ou credor, impede), sem caução em aberto, nunca duas vezes; a reabertura só da encerrada, com motivo.
 */
class FolioLifecycleDomainTest {
    private val day = LocalDate.of(2026, 10, 20)
    private val user = UserId(UuidV7.generate())
    private val stamp = PostingStamp(day, Instant.parse("2026-10-20T15:00:00Z"), user)

    @Test
    fun `a folio closes only with an exactly zero balance and no open pre-authorization`() {
        val folio = open()
        for (balance in listOf("0.01", "-0.01", "150.00")) {
            shouldThrow<FolioNotSettledException> { folio.close(Money.of(balance), emptyList(), stamp) }
                .also {
                    it.code.value shouldBe "FOLIO_BALANCE_NOT_ZERO"
                    it.problemProperties shouldBe mapOf("balance" to balance)
                }
        }
        val preAuthorization = CardPreAuthorizationId.new()
        shouldThrow<FolioHasActivePreAuthorizationException> {
            folio.close(Money.ZERO, listOf(preAuthorization), stamp)
        }.problemProperties shouldBe mapOf("preAuthorizationIds" to listOf(preAuthorization.toString()))
        folio.status shouldBe FolioStatus.OPEN

        folio.close(Money.of("0.00"), emptyList(), stamp)
        folio.status shouldBe FolioStatus.CLOSED
        folio.closedOn shouldBe day
        folio.closedAt shouldBe stamp.postedAt
        folio.closedBy shouldBe user
        refused<ConflictException>("FOLIO_CLOSED") { folio.close(Money.ZERO, emptyList(), stamp) }
        refused<ConflictException>("FOLIO_CLOSED") { folio.requireOpen() }
        refused<ConflictException>("FOLIO_CLOSED") { folio.update(CompanyId(UuidV7.generate()), null, stamp.postedAt) }
    }

    @Test
    fun `only a closed folio is reopened, with a reason, and the reopening keeps the close it undid`() {
        val folio = open()
        refused<ConflictException>("FOLIO_NOT_CLOSED") { folio.reopen("Lançamento esquecido", stamp, null) }
        folio.close(Money.ZERO, emptyList(), stamp)
        refused<InvalidInputException>("BILLING_DATA_INVALID") { folio.reopen("  ", stamp, null) }
        refused<InvalidInputException>("BILLING_DATA_INVALID") { folio.reopen("x".repeat(501), stamp, null) }
        folio.status shouldBe FolioStatus.CLOSED

        val later = PostingStamp(day.plusDays(1), stamp.postedAt.plusSeconds(3600), user)
        val approval = PostingApproval(UuidV7.generate(), UserId(UuidV7.generate()))
        val reopening = folio.reopen(" Lançamento esquecido ", later, approval)
        reopening.reason shouldBe "Lançamento esquecido"
        reopening.closedOn shouldBe day
        reopening.closedAt shouldBe stamp.postedAt
        reopening.businessDate shouldBe later.businessDate
        reopening.reopenedBy shouldBe user
        reopening.approval shouldBe approval
        reopening.toString().contains("esquecido") shouldBe false
        folio.status shouldBe FolioStatus.OPEN
        folio.closedOn.shouldBeNull()
        folio.closedAt.shouldBeNull()
        folio.closedBy.shouldBeNull()
        folio.reopenedOn shouldBe later.businessDate
        folio.reopenedBy shouldBe user
        folio.requireOpen()
    }

    private fun open() =
        Folio.open(
            DocumentNumber("C", 2026, (1L..999_999L).random()),
            FolioOwnerType.RESERVATION,
            UuidV7.generate(),
            null,
            null,
            day,
            null,
            stamp.postedAt,
        )

    private inline fun <reified T : DomainException> refused(
        code: String,
        block: () -> Unit,
    ) {
        shouldThrow<T>(block).code.value shouldBe code
    }
}

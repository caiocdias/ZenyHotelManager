package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.CorrectionLimit
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.FolioBalance
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingCorrections
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.billing.domain.TransactionCodeDetails
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Regras de códigos, contas e lançamentos (M3 E04, RF-CTA-01 a 03, RN-02, RN-03): valor do lançamento pela regra de
 * `Money`, sinal do crédito, estorno do que resta, ajuste parcial com sinal oposto, as recusas e o saldo.
 */
class BillingDomainTest {
    private val day = LocalDate.of(2026, 10, 17)
    private val stamp = PostingStamp(day, Instant.parse("2026-10-20T12:00:00Z"), null)
    private val folio = openFolio()
    private val room = code(TransactionCodeKind.CHARGE, RevenueGroup.ROOM, "DIARIA")
    private val food = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "AB", serviceCharge = true)
    private val courtesy = code(TransactionCodeKind.CREDIT, RevenueGroup.OTHER, "CORTESIA")

    @Test
    fun `a regular posting is quantity times unit price rounded by Money, negative on a credit code`() {
        val stay = post(room, "3", "199.90")
        stay.amount shouldBe Money.of("599.70")
        stay.type shouldBe PostingType.REGULAR
        stay.businessDate shouldBe day
        // Quantidade fracionária: arredonda pela regra única (HALF_EVEN) de Money.
        post(food, "0.125", "10.10").amount shouldBe Money.of("1.26")
        post(food, "0.375", "0.10").amount shouldBe Money.of("0.04")
        post(food, "0.5", "0.05").amount shouldBe Money.of("0.02")
        post(courtesy, "1", "50.00").amount shouldBe Money.of("-50.00")
        // A descrição vazia vira o nome do código.
        post(room, "1", "100.00").description shouldBe "Nome DIARIA"
    }

    @Test
    fun `quantity, unit price and the rounded amount are checked`() {
        for ((quantity, price) in listOf("0" to "10.00", "-1" to "10.00", "1.0001" to "10.00", "10000" to "1.00")) {
            refused<InvalidInputException>("POSTING_INVALID") { post(room, quantity, price) }
        }
        refused<InvalidInputException>("POSTING_INVALID") { post(room, "1", "0.00") }
        refused<InvalidInputException>("POSTING_INVALID") { post(room, "1", "1000000.01") }
        // 0,001 × 0,01 arredonda a zero.
        refused<InvalidInputException>("POSTING_INVALID") { post(room, "0.001", "0.01") }
        val inactive =
            code(
                TransactionCodeKind.CHARGE,
                RevenueGroup.OTHER,
                "VELHO",
            ).also { it.deactivate(stamp.postedAt) }
        refused<BusinessRuleException>("TRANSACTION_CODE_INACTIVE") { post(inactive, "1", "1.00") }
    }

    @Test
    fun `a reversal inverts what remains of the original and only once`() {
        val stay = post(room, "2", "250.00")
        val reversal = PostingCorrections(stay, emptyList()).reverse(folio, " Hóspede não ficou ", stamp)
        reversal.type shouldBe PostingType.REVERSAL
        reversal.correctsId shouldBe stay.id
        reversal.amount shouldBe Money.of("-500.00")
        reversal.unitPrice shouldBe reversal.amount
        reversal.quantity.compareTo(BigDecimal.ONE) shouldBe 0
        reversal.reason shouldBe "Hóspede não ficou"
        reversal.transactionCodeId shouldBe stay.transactionCodeId

        val reversed = PostingCorrections(stay, listOf(reversal))
        reversed.remaining shouldBe Money.ZERO
        reversed.isReversed shouldBe true
        refused<ConflictException>("POSTING_ALREADY_REVERSED") { reversed.reverse(folio, "de novo", stamp) }
        refused<ConflictException>("POSTING_ALREADY_REVERSED") {
            reversed.adjust(folio, Money.of("1.00"), "depois do estorno", stamp)
        }
        // Estorno e ajuste não são corrigidos.
        refused<BusinessRuleException>("POSTING_NOT_CORRECTABLE") {
            PostingCorrections(reversal, emptyList()).reverse(folio, "estorno do estorno", stamp)
        }
        refused<InvalidInputException>(
            "POSTING_INVALID",
        ) { PostingCorrections(stay, emptyList()).reverse(folio, " ", stamp) }
    }

    @Test
    fun `an adjustment is partial, opposite to the original, and the reversal then inverts the rest`() {
        val dinner = post(food, "1", "100.00")
        val first = PostingCorrections(dinner, emptyList()).adjust(folio, Money.of("30.00"), "Prato trocado", stamp)
        first.type shouldBe PostingType.ADJUSTMENT
        first.amount shouldBe Money.of("-30.00")
        first.correctsId shouldBe dinner.id

        val adjusted = PostingCorrections(dinner, listOf(first))
        adjusted.remaining shouldBe Money.of("70.00")
        refused<BusinessRuleException>("ADJUSTMENT_EXCEEDS_REMAINING") {
            adjusted.adjust(folio, Money.of("70.01"), "demais", stamp)
        }
        refused<InvalidInputException>("POSTING_INVALID") { adjusted.adjust(folio, Money.of("0.00"), "zero", stamp) }
        refused<BusinessRuleException>("POSTING_NOT_CORRECTABLE") {
            PostingCorrections(first, emptyList()).adjust(folio, Money.of("1.00"), "ajuste do ajuste", stamp)
        }
        // O limite é cumulativo: as correções já feitas mais a nova.
        CorrectionLimit.cumulative(listOf(first), Money.of("-20.00")) shouldBe Money.of("50.00")
        val reversal = adjusted.reverse(folio, "Cancelado", stamp)
        reversal.amount shouldBe Money.of("-70.00")
        CorrectionLimit.cumulative(listOf(first), reversal.amount) shouldBe Money.of("100.00")
        PostingCorrections(dinner, listOf(first, reversal)).remaining shouldBe Money.ZERO

        // Ajustado até zerar: nada a estornar.
        val all = PostingCorrections(dinner, emptyList()).adjust(folio, Money.of("100.00"), "Tudo", stamp)
        refused<BusinessRuleException>("POSTING_NOTHING_TO_REVERSE") {
            PostingCorrections(dinner, listOf(all)).reverse(folio, "nada", stamp)
        }
        // O ajuste de um crédito é positivo.
        val gift = post(courtesy, "1", "40.00")
        PostingCorrections(gift, emptyList()).adjust(folio, Money.of("15.00"), "Cortesia menor", stamp).amount shouldBe
            Money.of("15.00")
    }

    @Test
    fun `the balance sums postings, reversals and adjustments`() {
        val stay = post(room, "3", "200.00")
        val dinner = post(food, "1", "50.00")
        val adjustment = PostingCorrections(dinner, emptyList()).adjust(folio, Money.of("10.00"), "Erro", stamp)
        val reversal = PostingCorrections(dinner, listOf(adjustment)).reverse(folio, "Cancelado", stamp)
        val gift = post(courtesy, "1", "30.00")
        val laundry = post(room, "1", "25.50")
        val laundryReversal = PostingCorrections(laundry, emptyList()).reverse(folio, "Indevido", stamp)

        FolioBalance.of(listOf(stay, dinner, adjustment, reversal, gift, laundry, laundryReversal)) shouldBe
            Money.of("570.00")
        FolioBalance.of(emptyList()) shouldBe Money.ZERO
    }

    @Test
    fun `transaction codes keep the service charge coherent with the kind`() {
        refused<InvalidInputException>("TRANSACTION_CODE_INVALID") {
            code(TransactionCodeKind.PENALTY, RevenueGroup.FEES, "MULTA", serviceCharge = true)
        }
        refused<InvalidInputException>("TRANSACTION_CODE_INVALID") {
            TransactionCode.create(
                TransactionCodeKind.CHARGE,
                RevenueGroup.OTHER,
                TransactionCodeDetails("X", "X", serviceChargeApplies = false, serviceChargeRate = Percent.of("10")),
                stamp.postedAt,
            )
        }
        refused<InvalidInputException>("TRANSACTION_CODE_INVALID") {
            TransactionCode.create(
                TransactionCodeKind.CHARGE,
                RevenueGroup.OTHER,
                TransactionCodeDetails(
                    "X",
                    "X",
                    serviceChargeApplies = true,
                    serviceChargeRate = Percent.of("100.0001"),
                ),
                stamp.postedAt,
            )
        }
        refused<InvalidInputException>("BILLING_DATA_INVALID") {
            code(TransactionCodeKind.CHARGE, RevenueGroup.OTHER, "com espaço")
        }
        val bar =
            TransactionCode.create(
                TransactionCodeKind.CHARGE,
                RevenueGroup.FOOD_BEVERAGE,
                TransactionCodeDetails(
                    " bar-1 ",
                    " Bar ",
                    serviceChargeApplies = true,
                    serviceChargeRate = Percent.of("12"),
                ),
                stamp.postedAt,
            )
        bar.code shouldBe "BAR-1"
        bar.name shouldBe "Bar"
        bar.update(TransactionCodeDetails("BAR-1", "Bar", true, Percent.of("12")), stamp.postedAt) shouldBe false
        bar.update(TransactionCodeDetails("BAR-1", "Bar", false, null), stamp.postedAt) shouldBe true
    }

    @Test
    fun `a folio needs the owner id except a walk-in, which is its own owner, and refuses postings when closed`() {
        refused<InvalidInputException>("FOLIO_OWNER_INVALID") {
            Folio.open(number(), FolioOwnerType.RESERVATION, null, null, null, day, null, stamp.postedAt)
        }
        refused<InvalidInputException>("BILLING_DATA_INVALID") {
            Folio.open(
                number(),
                FolioOwnerType.EVENT,
                UuidV7.generate(),
                null,
                Money.of("-1.00"),
                day,
                null,
                stamp.postedAt,
            )
        }
        val walkIn = Folio.open(number(), FolioOwnerType.WALK_IN, null, null, null, day, null, stamp.postedAt)
        walkIn.owner shouldBe FolioOwner(FolioOwnerType.WALK_IN, walkIn.id.value)
        walkIn.status shouldBe FolioStatus.OPEN
        walkIn.openedOn shouldBe day
    }

    private fun openFolio() =
        Folio.open(number(), FolioOwnerType.RESERVATION, UuidV7.generate(), null, null, day, null, Instant.EPOCH)

    private fun number() = DocumentNumber("C", 2026, (1L..999_999L).random())

    private fun code(
        kind: TransactionCodeKind,
        group: RevenueGroup,
        code: String,
        serviceCharge: Boolean = false,
    ) = TransactionCode.create(kind, group, TransactionCodeDetails(code, "Nome $code", serviceCharge), Instant.EPOCH)

    private fun post(
        code: TransactionCode,
        quantity: String,
        unitPrice: String,
    ): Posting =
        Posting.regular(folio, code, BigDecimal(quantity), Money.of(unitPrice), null, PostingSource.MANUAL, stamp)

    private inline fun <reified T : DomainException> refused(
        code: String,
        block: () -> Unit,
    ) {
        shouldThrow<T>(block).code.value shouldBe code
    }
}

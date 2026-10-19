package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingCorrections
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.billing.domain.ServiceCharge
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.billing.domain.TransactionCodeDetails
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Taxa de serviço (M3 E06, RF-CTA-14, RN-02, RN-10): o percentual resolvido (informado, senão o do código, senão o do
 * hotel), o valor pela regra única de `Money`, o lançamento separado e vinculado, o ajuste que acompanha o do item sem
 * deriva de centavos, a divisão proporcional sem perder centavos e o grupo de receita de repasse.
 */
class ServiceChargeDomainTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val stamp = PostingStamp(day, Instant.parse("2026-10-19T12:00:00Z"), null)
    private val folio = openFolio()
    private val chargeCode = code(TransactionCodeKind.SERVICE_CHARGE, RevenueGroup.SERVICE_CHARGE, "TX-SERVICO")
    private val hotelRate = Percent.of("10")

    @Test
    fun `the rate is the informed one, else the code one, else the hotel default, only when the code accepts it`() {
        val food = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "AB", serviceCharge = true)
        val own =
            code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "BAR", serviceCharge = true, rate = "12.5")
        val room = code(TransactionCodeKind.CHARGE, RevenueGroup.ROOM, "DIARIA")

        ServiceCharge.rate(food, null, hotelRate) shouldBe hotelRate
        ServiceCharge.rate(own, null, hotelRate) shouldBe Percent.of("12.5")
        ServiceCharge.rate(own, Percent.of("15"), hotelRate) shouldBe Percent.of("15")
        ServiceCharge.rate(food, Percent.of("8"), hotelRate) shouldBe Percent.of("8")
        // O código decide se a taxa se aplica: o percentual informado não a cria num código sem ela.
        ServiceCharge.rate(room, Percent.of("10"), hotelRate).shouldBeNull()
        // Percentual informado fora de 0 a 100.
        for (invalid in listOf("-1", "100.0001")) {
            refused<InvalidInputException>(
                "POSTING_INVALID",
            ) { ServiceCharge.rate(food, Percent.of(invalid), hotelRate) }
        }
    }

    @Test
    fun `the charge is a separate posting linked to the item, rounded by the single Money rule`() {
        val food = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "AB", serviceCharge = true)
        val item = post(food, "3", "33.33")
        val charge = ServiceCharge.on(item, chargeCode, Percent.of("12.5"), stamp).shouldNotBeNull()
        charge.type shouldBe PostingType.SERVICE_CHARGE
        charge.folioId shouldBe item.folioId
        charge.transactionCodeId shouldBe chargeCode.id
        charge.serviceCharge?.itemId shouldBe item.id
        charge.serviceCharge?.rate shouldBe Percent.of("12.5")
        // 12,5% de 99,99 = 12,49875 → 12,50; o item fica com o valor dele (a taxa não é embutida).
        charge.amount shouldBe Money.of("12.50")
        item.amount shouldBe Money.of("99.99")
        charge.unitPrice shouldBe charge.amount
        charge.quantity.compareTo(BigDecimal.ONE) shouldBe 0
        charge.businessDate shouldBe day

        // HALF_EVEN de Money: 10% de 0,25 = 0,025 → 0,02 (HALF_UP daria 0,03); 10% de 0,35 = 0,035 → 0,04.
        ServiceCharge.on(post(food, "1", "0.25"), chargeCode, hotelRate, stamp)?.amount shouldBe Money.of("0.02")
        ServiceCharge.on(post(food, "1", "0.35"), chargeCode, hotelRate, stamp)?.amount shouldBe Money.of("0.04")
        // Arredonda a zero, ou percentual zero: não há taxa.
        ServiceCharge.on(post(food, "1", "0.04"), chargeCode, hotelRate, stamp).shouldBeNull()
        ServiceCharge.on(post(food, "1", "100.00"), chargeCode, Percent.ZERO, stamp).shouldBeNull()
        // O código da taxa desativado recusa.
        val inactive =
            code(TransactionCodeKind.SERVICE_CHARGE, RevenueGroup.SERVICE_CHARGE, "TX-VELHA").also {
                it.deactivate(stamp.postedAt)
            }
        refused<BusinessRuleException>("TRANSACTION_CODE_INACTIVE") {
            ServiceCharge.on(post(food, "1", "10.00"), inactive, hotelRate, stamp)
        }
        // Salvo com a taxa zero: o item passa sem taxa e o código nem é conferido.
        ServiceCharge.on(post(food, "1", "10.00"), inactive, Percent.ZERO, stamp).shouldBeNull()
        ServiceCharge.on(post(food, "1", "0.04"), inactive, hotelRate, stamp).shouldBeNull()
    }

    @Test
    fun `a linked charge is corrected only along with the item or by the removal`() {
        val food = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "AB", serviceCharge = true)
        val item = post(food, "1", "100.00")
        val charge = checkNotNull(ServiceCharge.on(item, chargeCode, hotelRate, stamp))
        refused<BusinessRuleException>("SERVICE_CHARGE_FOLLOWS_ITEM") { charge.requireNotServiceCharge() }
        item.requireNotServiceCharge()

        // A remoção a pedido é o estorno de tudo o que resta da taxa, com o motivo fixo e sem vínculo com o item.
        val removal = PostingCorrections(charge, emptyList()).reverse(folio, ServiceCharge.REMOVAL_REASON, stamp)
        removal.type shouldBe PostingType.REVERSAL
        removal.correctsId shouldBe charge.id
        removal.amount shouldBe Money.of("-10.00")
        removal.triggeredById.shouldBeNull()
        removal.reason shouldBe "Taxa de serviço removida a pedido do cliente."

        // O estorno que acompanha o do item cita o estorno do item.
        val itemReversal = PostingCorrections(item, emptyList()).reverse(folio, "Não consumiu", stamp)
        val following =
            PostingCorrections(charge, emptyList()).reverse(folio, "Não consumiu", stamp, triggeredBy = itemReversal.id)
        following.triggeredById shouldBe itemReversal.id
        following.amount shouldBe Money.of("-10.00")
    }

    @Test
    fun `the adjustment of the charge follows the item without drifting cents`() {
        val food = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "AB", serviceCharge = true)
        val item = post(food, "1", "100.00")
        val charge = checkNotNull(ServiceCharge.on(item, chargeCode, hotelRate, stamp))
        var itemCorrections = PostingCorrections(item, emptyList())
        var chargeCorrections = PostingCorrections(charge, emptyList())
        val itemAdjustments = mutableListOf<Posting>()
        val chargeAdjustments = mutableListOf<Posting>()
        val following = mutableListOf<Money>()
        for (step in listOf("33.33", "33.33", "33.34")) {
            val adjustment = itemCorrections.adjust(folio, Money.of(step), "Ajuste", stamp)
            itemAdjustments += adjustment
            itemCorrections = PostingCorrections(item, itemAdjustments)
            val magnitude = ServiceCharge.followingAdjustment(chargeCorrections, itemCorrections.remaining)
            following += magnitude
            chargeAdjustments +=
                chargeCorrections.adjust(folio, magnitude, "Ajuste", stamp, triggeredBy = adjustment.id)
            chargeCorrections = PostingCorrections(charge, chargeAdjustments)
        }
        // A taxa que resta é sempre 10% do que resta do item (6,67, 3,33 e zero), e as três somam a taxa inteira.
        following shouldBe listOf(Money.of("3.33"), Money.of("3.34"), Money.of("3.33"))
        chargeCorrections.remaining shouldBe Money.ZERO
        chargeAdjustments.forEach { it.amount.isNegative() shouldBe true }

        // Sem nada restando da taxa (removida), nada acompanha; a taxa nunca aumenta nem passa do que resta.
        val removed =
            PostingCorrections(
                charge,
                listOf(PostingCorrections(charge, emptyList()).reverse(folio, ServiceCharge.REMOVAL_REASON, stamp)),
            )
        ServiceCharge.followingAdjustment(removed, Money.of("50.00")) shouldBe Money.ZERO
        ServiceCharge.followingAdjustment(PostingCorrections(charge, emptyList()), Money.of("100.00")) shouldBe
            Money.ZERO
        ServiceCharge.followingAdjustment(PostingCorrections(charge, emptyList()), Money.ZERO) shouldBe
            Money.of("10.00")
    }

    @Test
    fun `the charge is split along the parts of the item, adding up to exactly what remains of it`() {
        val parts = listOf(Money.of("33.33"), Money.of("33.33"), Money.of("33.34"))
        ServiceCharge.alongside(Money.of("10.00"), parts) shouldBe
            listOf(Money.of("3.33"), Money.of("3.33"), Money.of("3.34"))
        // Parte do item zerada não leva taxa; a soma é sempre exata.
        val uneven =
            ServiceCharge.alongside(
                Money.of("0.05"),
                listOf(Money.of("0.00"), Money.of("1.00"), Money.of("2.00")),
            )
        uneven.first() shouldBe Money.ZERO
        uneven.fold(Money.ZERO, Money::plus) shouldBe Money.of("0.05")
        for (total in listOf("0.01", "7.77", "10.01", "99.99")) {
            ServiceCharge
                .alongside(Money.of(total), listOf(Money.of("1.00"), Money.of("1.00"), Money.of("1.00")))
                .fold(Money.ZERO, Money::plus) shouldBe Money.of(total)
        }
    }

    @Test
    fun `only the service charge revenue group is a pass-through to the employees, not revenue`() {
        RevenueGroup.entries.filter { it.passThrough } shouldBe listOf(RevenueGroup.SERVICE_CHARGE)
    }

    private fun openFolio() =
        Folio.open(
            DocumentNumber("C", 2026, (1L..999_999L).random()),
            FolioOwnerType.RESERVATION,
            UuidV7.generate(),
            null,
            null,
            day,
            null,
            Instant.EPOCH,
        )

    private fun code(
        kind: TransactionCodeKind,
        group: RevenueGroup,
        code: String,
        serviceCharge: Boolean = false,
        rate: String? = null,
    ) = TransactionCode.create(
        kind,
        group,
        TransactionCodeDetails(code, "Nome $code", serviceCharge, rate?.let(Percent::of)),
        Instant.EPOCH,
    )

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

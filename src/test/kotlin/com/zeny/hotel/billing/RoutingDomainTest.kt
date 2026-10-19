package com.zeny.hotel.billing

import com.zeny.hotel.billing.domain.BusinessPeriod
import com.zeny.hotel.billing.domain.CorrectionLimit
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingCorrections
import com.zeny.hotel.billing.domain.PostingSplit
import com.zeny.hotel.billing.domain.PostingStamp
import com.zeny.hotel.billing.domain.RoutingCriteria
import com.zeny.hotel.billing.domain.RoutingNetwork
import com.zeny.hotel.billing.domain.RoutingRule
import com.zeny.hotel.billing.domain.SplitShares
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.billing.domain.TransactionCodeDetails
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.sum
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Roteamento, transferência e divisão no domínio (M3 E05, RF-CTA-04 e 05, RN-02, RN-03): a regra vigente e a
 * precedência do código sobre o grupo, a cadeia, a recusa de sobreposição e de ciclo, a divisão que soma exatamente o
 * que resta (inclusive com dízima) e a transferência por pares vinculados que não muda o original.
 */
class RoutingDomainTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val stamp = PostingStamp(day, Instant.parse("2026-10-19T12:00:00Z"), null)
    private val room = code(TransactionCodeKind.CHARGE, RevenueGroup.ROOM, "DIARIA")
    private val food = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "AB")
    private val bar = code(TransactionCodeKind.CHARGE, RevenueGroup.FOOD_BEVERAGE, "BAR")
    private val courtesy = code(TransactionCodeKind.CREDIT, RevenueGroup.OTHER, "CORTESIA")
    private val groups = listOf(room, food, bar, courtesy).associate { it.id to it.revenueGroup }
    private val a = folio()
    private val b = folio()
    private val c = folio()
    private val d = folio()

    // --- Regra vigente e cadeia ----------------------------------------------------------------------

    @Test
    fun `the rule in force routes the code, outside its period or deactivated it does not`() {
        val rule = rule(a, b, RoutingCriteria.Codes(setOf(room.id)), day, day.plusDays(2))
        val network = network(rule)
        network.applicable(a.id, room.id, RevenueGroup.ROOM, day) shouldBe rule
        network.applicable(a.id, room.id, RevenueGroup.ROOM, day.plusDays(2)) shouldBe rule
        network.applicable(a.id, room.id, RevenueGroup.ROOM, day.minusDays(1)) shouldBe null
        network.applicable(a.id, room.id, RevenueGroup.ROOM, day.plusDays(3)) shouldBe null
        network.applicable(a.id, food.id, RevenueGroup.FOOD_BEVERAGE, day) shouldBe null
        network.applicable(b.id, room.id, RevenueGroup.ROOM, day) shouldBe null
        // Sem fim: vale de startsOn em diante.
        val endless = rule(a, b, RoutingCriteria.Group(RevenueGroup.ROOM), day, null)
        network(endless).applicable(a.id, room.id, RevenueGroup.ROOM, day.plusYears(5)) shouldBe endless
        network(endless).applicable(a.id, room.id, RevenueGroup.ROOM, day.minusDays(1)) shouldBe null
        rule.deactivate(stamp.postedAt)
        network(rule).applicable(a.id, room.id, RevenueGroup.ROOM, day) shouldBe null
    }

    @Test
    fun `a rule by code prevails over a rule by revenue group on the same folio`() {
        val byGroup = rule(a, b, RoutingCriteria.Group(RevenueGroup.FOOD_BEVERAGE), day, null)
        val byCode = rule(a, c, RoutingCriteria.Codes(setOf(bar.id)), day, null)
        // A ordem de cadastro não importa.
        for (network in listOf(network(byGroup, byCode), network(byCode, byGroup))) {
            network.applicable(a.id, bar.id, RevenueGroup.FOOD_BEVERAGE, day) shouldBe byCode
            network.applicable(a.id, food.id, RevenueGroup.FOOD_BEVERAGE, day) shouldBe byGroup
        }
    }

    @Test
    fun `a posting follows the chain to the folio without an applicable rule`() {
        val ab = rule(a, b, RoutingCriteria.Group(RevenueGroup.ROOM), day, null)
        val bc = rule(b, c, RoutingCriteria.Codes(setOf(room.id)), day, null)
        val cd = rule(c, d, RoutingCriteria.Codes(setOf(food.id)), day, null)
        val network = network(ab, bc, cd)
        network.route(a.id, room.id, RevenueGroup.ROOM, day) shouldContainExactly listOf(ab, bc)
        network.route(b.id, room.id, RevenueGroup.ROOM, day) shouldContainExactly listOf(bc)
        network.route(a.id, food.id, RevenueGroup.FOOD_BEVERAGE, day).shouldBeEmpty()
        network.route(c.id, food.id, RevenueGroup.FOOD_BEVERAGE, day) shouldContainExactly listOf(cd)
    }

    // --- Sobreposição ------------------------------------------------------------------------------

    @Test
    fun `two rules of the same level and source that cover the same thing in crossing periods are refused`() {
        val existing = rule(a, b, RoutingCriteria.Codes(setOf(room.id, food.id)), day, day.plusDays(5))
        val sameCode = rule(a, c, RoutingCriteria.Codes(setOf(food.id, bar.id)), day.plusDays(5), null)
        refused("ROUTING_RULE_OVERLAP") { network(existing, sameCode).requireNoOverlap(sameCode) }

        val groupRule = rule(a, b, RoutingCriteria.Group(RevenueGroup.ROOM), day, null)
        val sameGroup = rule(a, c, RoutingCriteria.Group(RevenueGroup.ROOM), day.minusDays(30), day)
        refused("ROUTING_RULE_OVERLAP") { network(groupRule, sameGroup).requireNoOverlap(sameGroup) }

        // Aceitas: outro código, outro grupo, código × grupo (a precedência resolve), período sem dia em comum,
        // outra origem e a regra desativada.
        val otherCode = rule(a, c, RoutingCriteria.Codes(setOf(bar.id)), day, null)
        network(existing, otherCode).requireNoOverlap(otherCode)
        val otherGroup = rule(a, c, RoutingCriteria.Group(RevenueGroup.FOOD_BEVERAGE), day, null)
        network(groupRule, otherGroup).requireNoOverlap(otherGroup)
        val codeOverGroup = rule(a, c, RoutingCriteria.Codes(setOf(room.id)), day, null)
        network(groupRule, codeOverGroup).requireNoOverlap(codeOverGroup)
        val later = rule(a, c, RoutingCriteria.Codes(setOf(room.id)), day.plusDays(6), null)
        network(existing, later).requireNoOverlap(later)
        val otherSource = rule(b, c, RoutingCriteria.Codes(setOf(room.id)), day, null)
        network(existing, otherSource).requireNoOverlap(otherSource)
        existing.deactivate(stamp.postedAt)
        network(existing, sameCode).requireNoOverlap(sameCode)
    }

    // --- Ciclo -------------------------------------------------------------------------------------

    @Test
    fun `a rule that would bring a posting back to its source is a cycle, even through a chain`() {
        refused("ROUTING_RULE_CYCLE") {
            val self = rule(a, a, RoutingCriteria.Codes(setOf(room.id)), day, null)
            network(self).requireNoCycle(self)
        }
        val ab = rule(a, b, RoutingCriteria.Codes(setOf(room.id)), day, null)
        val ba = rule(b, a, RoutingCriteria.Group(RevenueGroup.ROOM), day, null)
        refused("ROUTING_RULE_CYCLE") { network(ab, ba).requireNoCycle(ba) }

        // A → B → C → A, com o grupo restringido pelo caminho.
        val bc = rule(b, c, RoutingCriteria.Group(RevenueGroup.ROOM), day, null)
        val ca = rule(c, a, RoutingCriteria.Codes(setOf(room.id, food.id)), day.plusDays(10), null)
        refused("ROUTING_RULE_CYCLE") { network(ab, bc, ca).requireNoCycle(ca) }
    }

    @Test
    fun `opposite rules for different codes or disjoint periods are not a cycle`() {
        val ab = rule(a, b, RoutingCriteria.Group(RevenueGroup.ROOM), day, day.plusDays(3))
        val baFood = rule(b, a, RoutingCriteria.Group(RevenueGroup.FOOD_BEVERAGE), day, null)
        network(ab, baFood).requireNoCycle(baFood)
        val baLater = rule(b, a, RoutingCriteria.Codes(setOf(room.id)), day.plusDays(4), null)
        network(ab, baLater).requireNoCycle(baLater)
        // Por código de outro grupo: não cruza a regra por grupo do caminho.
        val bc = rule(b, c, RoutingCriteria.Codes(setOf(food.id)), day, null)
        val ca = rule(c, a, RoutingCriteria.Group(RevenueGroup.FOOD_BEVERAGE), day, null)
        network(ab, bc, ca).requireNoCycle(ca)
    }

    // --- Divisão (RN-02) ---------------------------------------------------------------------------

    @Test
    fun `a split by percent always adds up to exactly what remains, also with repeating decimals`() {
        val thirds = listOf("33.3333", "33.3333", "33.3334").map(Percent::of)
        for (total in listOf("100.00", "10.00", "0.10", "0.01", "1234.57", "-100.00", "-0.05")) {
            val parts = PostingSplit.amounts(Money.of(total), SplitShares.ByPercent(thirds))
            parts.sum() shouldBe Money.of(total)
            parts.filterNot(Money::isZero).forEach { it.isNegative() shouldBe Money.of(total).isNegative() }
        }
        PostingSplit.amounts(Money.of("10.00"), SplitShares.ByPercent(thirds)) shouldContainExactly
            listOf(Money.of("3.33"), Money.of("3.33"), Money.of("3.34"))
        PostingSplit.amounts(
            Money.of("0.05"),
            SplitShares.ByPercent(listOf(Percent.of("50"), Percent.of("50"))),
        ) shouldBe
            listOf(Money.of("0.03"), Money.of("0.02"))
        PostingSplit
            .amounts(Money.of("199.99"), SplitShares.ByPercent(listOf("12.5", "37.5", "50").map(Percent::of)))
            .sum() shouldBe Money.of("199.99")
    }

    @Test
    fun `a split by amount must add up to exactly what remains, and the shares are checked`() {
        PostingSplit.amounts(
            Money.of("100.00"),
            SplitShares.ByAmount(listOf("33.33", "33.33", "33.34").map(Money::of)),
        ) shouldContainExactly listOf("33.33", "33.33", "33.34").map(Money::of)
        // Um crédito (negativo) é dividido com o sinal dele.
        PostingSplit.amounts(Money.of("-50.00"), SplitShares.ByAmount(listOf("20.00", "30.00").map(Money::of))) shouldBe
            listOf(Money.of("-20.00"), Money.of("-30.00"))
        refused("SPLIT_TOTAL_MISMATCH") {
            PostingSplit.amounts(
                Money.of("100.00"),
                SplitShares.ByAmount(listOf("33.33", "33.33", "33.33").map(Money::of)),
            )
        }
        refused("SPLIT_INVALID") {
            PostingSplit.amounts(
                Money.of("100.00"),
                SplitShares.ByPercent(listOf("33.3333", "33.3333", "33.3333").map(Percent::of)),
            )
        }
        refused("SPLIT_INVALID") {
            PostingSplit.amounts(Money.of("100.00"), SplitShares.ByPercent(listOf("100", "0").map(Percent::of)))
        }
        refused("SPLIT_INVALID") {
            PostingSplit.amounts(Money.of("100.00"), SplitShares.ByAmount(listOf("100.00").map(Money::of)))
        }
        refused("SPLIT_INVALID") {
            PostingSplit.amounts(Money.of("100.00"), SplitShares.ByAmount(listOf("100.00", "0.00").map(Money::of)))
        }
        refused("SPLIT_INVALID") {
            PostingSplit.amounts(Money.of("100.00"), SplitShares.ByAmount(List(11) { Money.of("1.00") }))
        }
    }

    // --- Transferência (RN-03) ---------------------------------------------------------------------

    @Test
    fun `a transfer is a linked pair that never changes the original, and only what remains moves`() {
        val original = post(a, food, "100.00")
        val corrections = PostingCorrections(original, emptyList())
        val (outgoing, incoming) = corrections.transfer(a, corrections.transferable(a), b, null, stamp)
        outgoing.type shouldBe PostingType.TRANSFER_OUT
        outgoing.folioId shouldBe a.id
        outgoing.correctsId shouldBe original.id
        outgoing.amount shouldBe Money.of("-100.00")
        incoming.type shouldBe PostingType.TRANSFER_IN
        incoming.folioId shouldBe b.id
        incoming.transferredFromId shouldBe outgoing.id
        incoming.amount shouldBe Money.of("100.00")
        incoming.transactionCodeId shouldBe original.transactionCodeId
        original.amount shouldBe Money.of("100.00")

        val moved = PostingCorrections(original, listOf(outgoing))
        moved.remaining shouldBe Money.ZERO
        refused("POSTING_NOTHING_TO_TRANSFER") { moved.transferable(a) }
        // A saída não é correção: não conta no limite cumulativo do estorno e do ajuste.
        CorrectionLimit.cumulative(listOf(outgoing, incoming), Money.of("1.00")) shouldBe Money.of("1.00")
        refused("POSTING_NOT_TRANSFERABLE") { PostingCorrections(outgoing, emptyList()).transferable(a) }
        refused("POSTING_NOT_CORRECTABLE") { PostingCorrections(outgoing, emptyList()).reversalAmount(a) }

        // A entrada vale como um lançamento normal no destino: é ajustada, estornada e transferida de novo.
        val arrived = PostingCorrections(incoming, emptyList())
        arrived.adjustmentAmount(b, Money.of("10.00")) shouldBe Money.of("-10.00")
        arrived.reversalAmount(b) shouldBe Money.of("-100.00")
        arrived.transferable(b) shouldBe Money.of("100.00")

        val reversal = corrections.reverse(a, "Lançado errado", stamp)
        refused("POSTING_ALREADY_REVERSED") { PostingCorrections(original, listOf(reversal)).transferable(a) }
    }

    @Test
    fun `a partial transfer of a credit keeps its sign, and the rest can still be moved or corrected`() {
        val original = post(a, courtesy, "40.00")
        original.amount shouldBe Money.of("-40.00")
        val corrections = PostingCorrections(original, emptyList())
        val (outgoing, incoming) = corrections.transfer(a, Money.of("-15.00"), b, "Parte da empresa", stamp)
        outgoing.amount shouldBe Money.of("15.00")
        incoming.amount shouldBe Money.of("-15.00")
        incoming.reason shouldBe "Parte da empresa"
        val rest = PostingCorrections(original, listOf(outgoing))
        rest.remaining shouldBe Money.of("-25.00")
        rest.transferable(a) shouldBe Money.of("-25.00")
        rest.reversalAmount(a) shouldBe Money.of("25.00")
        (outgoing.amount + incoming.amount) shouldBe Money.ZERO
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun network(vararg rules: RoutingRule) = RoutingNetwork(rules.toList()) { groups[it] }

    private fun rule(
        source: Folio,
        target: Folio,
        criteria: RoutingCriteria,
        startsOn: LocalDate,
        endsOn: LocalDate?,
    ) = RoutingRule.create(source.id, target.id, criteria, BusinessPeriod(startsOn, endsOn), null, Instant.EPOCH)

    private fun folio() =
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
    ) = TransactionCode.create(kind, group, TransactionCodeDetails(code, "Nome $code"), Instant.EPOCH)

    private fun post(
        folio: Folio,
        code: TransactionCode,
        unitPrice: String,
    ): Posting = Posting.regular(folio, code, BigDecimal.ONE, Money.of(unitPrice), null, PostingSource.MANUAL, stamp)

    private fun refused(
        code: String,
        block: () -> Unit,
    ) {
        shouldThrow<DomainException>(block).code.value shouldBe code
    }
}

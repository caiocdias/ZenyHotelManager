package com.zeny.hotel.rates

import com.zeny.hotel.rates.domain.AgeBand
import com.zeny.hotel.rates.domain.ChildBands
import com.zeny.hotel.rates.domain.ChildPrice
import com.zeny.hotel.rates.domain.DerivationGaps
import com.zeny.hotel.rates.domain.DerivationRule
import com.zeny.hotel.rates.domain.Derivations
import com.zeny.hotel.rates.domain.PriceAdjustment
import com.zeny.hotel.rates.domain.PriceChanges
import com.zeny.hotel.rates.domain.PriceKey
import com.zeny.hotel.rates.domain.PriceSelection
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Preços por data e ocupação (M4 E02, RF-TAR-02 a 04, H1, H2, H9): a derivação por percentual e por valor (por UH e por
 * pessoa) pela regra única de `Money`, em cadeia e sem negativo; ciclo e profundidade da árvore; as faixas de criança
 * atuais e as obsoletas; a seleção por período e dia da semana com o teto de 366 dias; e o que uma edição muda.
 */
class RatePriceDomainTest {
    private val base =
        PriceSet(
            money("333.33"),
            money("400.00"),
            money("120.00"),
            listOf(ChildPrice(0, 5, money("0.00")), ChildPrice(6, 12, money("60.00"))),
        )

    // --- Derivação ------------------------------------------------------------------------------------------------

    @Test
    fun `a percent derivation applies to every value and rounds by the Money rule (HALF_EVEN)`() {
        val derived = PriceAdjustment.ByPercent(Percent.of("-10")).apply(base)

        // 333,33 − 33,333 → o desconto arredonda para 33,33 (regra única de Money): 300,00.
        derived.oneAdult shouldBe money("300.00")
        derived.twoAdults shouldBe money("360.00")
        derived.extraAdult shouldBe money("108.00")
        derived.children shouldContainExactly
            listOf(ChildPrice(0, 5, money("0.00")), ChildPrice(6, 12, money("54.00")))

        // 0,25 × 10% = 0,025 → HALF_EVEN dá 0,02 (nunca 0,03): 0,27.
        PriceAdjustment.ByPercent(Percent.of("10")).apply(prices("0.25")).oneAdult shouldBe money("0.27")
        // 0,35 × 10% = 0,035 → HALF_EVEN dá 0,04: 0,39.
        PriceAdjustment.ByPercent(Percent.of("10")).apply(prices("0.35")).oneAdult shouldBe money("0.39")
    }

    @Test
    fun `an amount per person is added once per person each value covers`() {
        val derived = PriceAdjustment.ByAmount(money("40.00"), perPerson = true).apply(base)

        derived.oneAdult shouldBe money("373.33")
        derived.twoAdults shouldBe money("480.00")
        derived.extraAdult shouldBe money("160.00")
        derived.children shouldContainExactly
            listOf(ChildPrice(0, 5, money("40.00")), ChildPrice(6, 12, money("100.00")))
    }

    @Test
    fun `an amount per room changes only the room rates`() {
        val derived = PriceAdjustment.ByAmount(money("-50.00"), perPerson = false).apply(base)

        derived.oneAdult shouldBe money("283.33")
        derived.twoAdults shouldBe money("350.00")
        derived.extraAdult shouldBe money("120.00")
        derived.children shouldBe base.children
        derived.withoutPrice shouldBe DerivationGaps.NONE
        // Sem preço de 2 adultos no base, o derivado também não tem (e não é lacuna da derivação).
        val noTwo = PriceAdjustment.ByPercent(Percent.of("5")).apply(prices("100.00"))
        noTwo.twoAdults.shouldBeNull()
        noTwo.withoutPrice shouldBe DerivationGaps.NONE
    }

    @Test
    fun `a derived value of zero or less leaves the occupancy without price, never a free rate`() {
        // Revisão humana da E02: zero é preço vendável; o desconto que zera (ou passa) tira a ocupação de venda.
        val zeroed = PriceAdjustment.ByAmount(money("-333.33"), perPerson = false).apply(base)
        zeroed.oneAdult.shouldBeNull()
        zeroed.twoAdults shouldBe money("66.67")
        zeroed.withoutPrice shouldBe DerivationGaps(oneAdult = true)

        val discount = PriceAdjustment.ByAmount(money("-60.00"), perPerson = true).apply(base)
        discount.oneAdult shouldBe money("273.33")
        discount.twoAdults shouldBe money("280.00")
        discount.extraAdult shouldBe money("60.00")
        // A criança de 6 a 12 (60,00 − 60,00 = 0) e a de 0 a 5 (0,00 − 60,00) ficam sem preço.
        discount.children shouldBe emptyList()
        discount.withoutPrice shouldBe DerivationGaps(children = listOf(0 to 5, 6 to 12))

        val all = PriceAdjustment.ByAmount(money("-500.00"), perPerson = true).apply(base)
        listOf(all.oneAdult, all.twoAdults, all.extraAdult).forEach { it.shouldBeNull() }
        all.withoutPrice shouldBe DerivationGaps(true, true, true, listOf(0 to 5, 6 to 12))

        // O valor do base já em 0,00 (cortesia gravada) continua 0,00 num percentual: não é lacuna.
        val percent = PriceAdjustment.ByPercent(Percent.of("-10")).apply(base)
        percent.children.first() shouldBe ChildPrice(0, 5, Money.ZERO)
        percent.withoutPrice shouldBe DerivationGaps.NONE
        // Um percentual que arredonda a zero também tira de venda.
        PriceAdjustment
            .ByPercent(Percent.of("-99.9999"))
            .apply(prices("10.00"))
            .oneAdult
            .shouldBeNull()

        // Na cadeia, a lacuna segue: o nível seguinte não devolve o preço.
        val chain =
            listOf(
                DerivationRule(RatePlanId.new(), PriceAdjustment.ByAmount(money("-350.00"), false)),
                DerivationRule(RatePlanId.new(), PriceAdjustment.ByAmount(money("100.00"), true)),
            )
        val chained = DerivationRule.applyAll(chain, base)
        chained.oneAdult.shouldBeNull()
        chained.twoAdults shouldBe money("250.00")
        chained.withoutPrice shouldBe DerivationGaps(oneAdult = true)
    }

    @Test
    fun `a chain applies each rule in order, from the root, rounding at each level`() {
        val nonRefundable = DerivationRule(RatePlanId.new(), PriceAdjustment.ByPercent(Percent.of("-10")))
        val withBreakfast = DerivationRule(RatePlanId.new(), PriceAdjustment.ByAmount(money("40.00"), true))

        val prices = DerivationRule.applyAll(listOf(nonRefundable, withBreakfast), base)

        prices.oneAdult shouldBe money("340.00")
        prices.twoAdults shouldBe money("440.00")
        DerivationRule.applyAll(emptyList(), base) shouldBe base
    }

    @Test
    fun `the adjustment is validated by type`() {
        fun invalid(block: () -> Unit) =
            shouldThrow<InvalidInputException>(block).also {
                it.code shouldBe RatesErrorCodes.RATE_PLAN_DERIVATION_INVALID
            }

        invalid { PriceAdjustment.of(RateAdjustmentType.PERCENT, Percent.of("-100"), null, null) }
        invalid { PriceAdjustment.of(RateAdjustmentType.PERCENT, null, null, null) }
        invalid { PriceAdjustment.of(RateAdjustmentType.PERCENT, Percent.of("5"), money("1.00"), null) }
        invalid { PriceAdjustment.of(RateAdjustmentType.AMOUNT, null, money("10.00"), null) }
        invalid { PriceAdjustment.of(RateAdjustmentType.AMOUNT, Percent.of("5"), money("10.00"), true) }
        invalid { PriceAdjustment.of(RateAdjustmentType.AMOUNT, null, money("1000000.01"), true) }
        PriceAdjustment.of(RateAdjustmentType.PERCENT, Percent.of("-99.9999"), null, null) shouldBe
            PriceAdjustment.ByPercent(Percent.of("-99.9999"))
        PriceAdjustment.of(RateAdjustmentType.AMOUNT, null, money("-40.00"), false) shouldBe
            PriceAdjustment.ByAmount(money("-40.00"), false)
    }

    @Test
    fun `a derivation that would form a cycle or pass five levels is refused with a stable code`() {
        val plan = RatePlanId.new()
        val a = RatePlanId.new()
        val b = RatePlanId.new()

        fun cycle(block: () -> Unit) =
            shouldThrow<BusinessRuleException>(block).code shouldBe RatesErrorCodes.RATE_PLAN_DERIVATION_CYCLE

        // O próprio plano; um base que deriva dele (a cadeia subindo do base chega ao plano).
        cycle { Derivations.requireValid(plan, plan, listOf(plan), 0) }
        cycle { Derivations.requireValid(plan, a, listOf(a, b, plan), 0) }
        // Sem ciclo: a cadeia termina num plano com preços próprios.
        Derivations.requireValid(plan, a, listOf(a, b), 0)
        // Profundidade: 2 níveis acima + o plano + 2 abaixo = 5 passa; 6 não.
        Derivations.requireValid(plan, a, listOf(a, b), 3)
        shouldThrow<BusinessRuleException> { Derivations.requireValid(plan, a, listOf(a, b), 4) }
            .code shouldBe RatesErrorCodes.RATE_PLAN_DERIVATION_TOO_DEEP
    }

    // --- Faixas de criança ----------------------------------------------------------------------------------------

    @Test
    fun `child prices follow the current hotel bands, or a single band without them, and old bands become obsolete`() {
        val bands = ChildBands.of(12, listOf(AgeBand(0, 5, "Bebê"), AgeBand(6, 12, "Criança")))
        bands.requireCurrent(base.children)

        val single = ChildBands.of(12, emptyList())
        single.bands shouldContainExactly listOf(AgeBand(0, 12, ChildBands.SINGLE_BAND_LABEL))
        single.requireCurrent(listOf(ChildPrice(0, 12, money("50.00"))))
        shouldThrow<InvalidInputException> { single.requireCurrent(base.children) }.violations.single().field shouldBe
            "prices.children[0]"

        // O hotel passou a 0–3 e 4–12: os preços gravados com 0–5 e 6–12 ficam obsoletos (nunca viram outra faixa).
        val changed = ChildBands.of(12, listOf(AgeBand(0, 3, "Bebê"), AgeBand(4, 12, "Criança")))
        val (current, obsolete) = changed.classify(base.children)
        current shouldBe emptyList()
        obsolete shouldContainExactly base.children
        val mixed = base.children + ChildPrice(0, 3, money("10.00"))
        changed.classify(mixed).first shouldContainExactly listOf(AgeBand(0, 3, "Bebê") to money("10.00"))

        val repeated = listOf(ChildPrice(0, 5, money("1.00")), ChildPrice(0, 5, money("2.00")))
        shouldThrow<InvalidInputException> { bands.requireCurrent(repeated) }.violations.single().field shouldBe
            "prices.children[1]"
    }

    @Test
    fun `prices are kept between 0 and one million`() {
        shouldThrow<InvalidInputException> {
            PriceSet.of(money("-0.01"), null, null, emptyList())
        }.violations.single().field shouldBe "prices.oneAdult"
        shouldThrow<InvalidInputException> {
            PriceSet.of(money("10.00"), null, null, listOf(ChildPrice(0, 5, money("1000000.01"))))
        }.violations.single().field shouldBe "prices.children[0].amount"
        PriceSet
            .of(money("0.00"), null, null, listOf(ChildPrice(6, 12, money("5.00")), ChildPrice(0, 5, Money.ZERO)))
            .children
            .map { it.minAge } shouldContainExactly listOf(0, 6)
    }

    // --- Seleção --------------------------------------------------------------------------------------------------

    @Test
    fun `the selection keeps only the weekdays asked for, inside the period`() {
        // 2026-11-02 é uma segunda-feira.
        val selection =
            PriceSelection.of(
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 15),
                setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            )

        selection.dates() shouldContainExactly
            listOf("2026-11-06", "2026-11-07", "2026-11-13", "2026-11-14").map(LocalDate::parse)
        PriceSelection.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4)).dates().size shouldBe 3
        PriceSelection
            .of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4), setOf(DayOfWeek.SUNDAY))
            .dates() shouldBe emptyList()
    }

    @Test
    fun `a selection is up to 366 days, never ends before it starts, and is cut at the business date`() {
        val start = LocalDate.of(2027, 1, 1)
        PriceSelection.of(start, start.plusDays(365)).dates().size shouldBe 366
        shouldThrow<InvalidInputException> { PriceSelection.of(start, start.plusDays(366)) }.let {
            it.code shouldBe RatesErrorCodes.RATE_PRICE_PERIOD_TOO_LONG
            it.violations.single().field shouldBe "to"
        }
        shouldThrow<InvalidInputException> { PriceSelection.of(start, start.minusDays(1)) }.code shouldBe
            RatesErrorCodes.RATE_PRICE_PERIOD_INVALID

        val season = PriceSelection.of(start, start.plusDays(9), setOf(DayOfWeek.MONDAY))
        season.startingAt(start.plusDays(5))!!.from shouldBe start.plusDays(5)
        season.startingAt(start.plusDays(5))!!.weekdays shouldBe setOf(DayOfWeek.MONDAY)
        season.startingAt(start.minusDays(1)) shouldBe season
        season.startingAt(start.plusDays(10)).shouldBeNull()
    }

    // --- O que uma edição muda ------------------------------------------------------------------------------------

    @Test
    fun `an edit separates new prices, changed recorded prices and identical ones`() {
        val type = RoomTypeId(UuidV7.generate())
        val day = LocalDate.of(2026, 12, 1)
        val keys = (0L..2L).map { PriceKey(type, day.plusDays(it)) }
        val existing = mapOf(keys[0] to prices("100.00"), keys[1] to prices("120.00"))

        val set = PriceChanges.toSet(keys, existing, prices("120.00"))
        set.created shouldContainExactly listOf(keys[2])
        set.changed shouldContainExactly listOf(keys[0])
        set.touchesRecordedPrices shouldBe true
        PriceChanges.toSet(keys.drop(1), existing, prices("120.00")).touchesRecordedPrices shouldBe false

        val removal = PriceChanges.toRemove(keys, existing)
        removal.removed shouldContainExactly keys.take(2)
        removal.touchesRecordedPrices shouldBe true
        PriceChanges.toRemove(keys.drop(2), existing).isEmpty() shouldBe true
    }

    private fun money(value: String) = Money.of(value)

    private fun prices(oneAdult: String) = PriceSet(money(oneAdult), null, null, emptyList())
}

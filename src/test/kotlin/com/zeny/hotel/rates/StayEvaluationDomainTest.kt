package com.zeny.hotel.rates

import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.rates.domain.AgeBand
import com.zeny.hotel.rates.domain.ChildBands
import com.zeny.hotel.rates.domain.ChildPrice
import com.zeny.hotel.rates.domain.DerivationRule
import com.zeny.hotel.rates.domain.EffectiveRestriction
import com.zeny.hotel.rates.domain.PriceAdjustment
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rates.domain.RestrictionValues
import com.zeny.hotel.rates.domain.SaleConditions
import com.zeny.hotel.rates.domain.SaleRules
import com.zeny.hotel.rates.domain.StayPricing
import com.zeny.hotel.rates.domain.StayRules
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.sum
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate

/**
 * Preço e vendabilidade de uma estadia (M4 E04), em tabelas de casos: o preço por noite com adultos extras, crianças
 * pela faixa etária, planos derivados e pacotes inclusos e adicionais (por noite, por pessoa e noite e por estadia),
 * sempre com a soma das noites igual ao total; os motivos de "sem preço"; cada regra de venda isolada (com os casos em
 * que a mesma restrição numa data que não importa **não** recusa); e o formato da estadia.
 */
class StayEvaluationDomainTest {
    // --- Preço ------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("pricedCases")
    fun `the price of each case has the expected total, and the nights always add up to it`(case: PricedCase) {
        val result = StayPricing.price(case.query, case.prices, BANDS, case.packages)

        val price = result.shouldBeInstanceOf<StayPriceResult.Priced>().price
        withClue(case.name) {
            price.nights.map { it.date } shouldContainExactly nightsOf(case.query)
            price.total shouldBe money(case.total)
            price.nights.map { it.total }.sum() shouldBe price.total
            price.nights.map { it.roomAmount }.sum() shouldBe price.roomTotal
            price.nights.map { it.includedPackagesAmount }.sum() shouldBe price.includedPackagesTotal
            price.nights.map { it.additionalPackagesAmount }.sum() shouldBe price.additionalPackagesTotal
            price.nights.map { it.accommodationAmount }.sum() shouldBe price.accommodationTotal
            price.roomTotal + price.additionalPackagesTotal shouldBe price.total
            price.accommodationTotal + price.includedPackagesTotal shouldBe price.roomTotal
            price.includedPackagesTotal shouldBe money(case.included)
            price.additionalPackagesTotal shouldBe money(case.additional)
            price.nights.forEach { night ->
                night.baseRate + night.extraAdultsAmount + night.children.map { it.amount }.sum() shouldBe
                    night.roomAmount
                night.packages.map { it.amount }.sum() shouldBe
                    night.includedPackagesAmount + night.additionalPackagesAmount
                night.packages.forEach { it.unitPrice * it.quantity shouldBe it.amount }
            }
        }
    }

    @Test
    fun `adults beyond the second pay the extra adult, and each child pays the price of the current age band`() {
        val price = priced(query(adults = 4, childAges = listOf(0, 5, 6, 12), nights = 1), flat(BASE))
        val night = price.nights.single()

        night.baseRate shouldBe money("400.00")
        night.extraAdults shouldBe 2
        night.extraAdultsAmount shouldBe money("240.00")
        night.children.map { Triple(it.age, it.label, it.amount) } shouldContainExactly
            listOf(
                Triple(0, "Bebê", money("0.00")),
                Triple(5, "Bebê", money("0.00")),
                Triple(6, "Criança", money("60.00")),
                Triple(12, "Criança", money("60.00")),
            )
        night.roomAmount shouldBe money("760.00")
    }

    @Test
    fun `a per-stay package is charged once, on the arrival night, never per night, whatever its rhythm`() {
        val price = priced(query(nights = 4), flat(BASE), listOf(DECORATION_PER_STAY))

        price.nights.map { it.additionalPackagesAmount } shouldContainExactly
            listOf(money("150.00"), money("0.00"), money("0.00"), money("0.00"))
        price.nights
            .first()
            .packages
            .single()
            .quantity shouldBe 1
        price.nights.drop(1).forEach { it.packages.shouldBeEmpty() }
        price.total shouldBe money("1750.00")
    }

    @Test
    fun `an included package is part of the daily rate - it splits the accommodation and never adds to the total`() {
        val price = priced(query(adults = 2, childAges = listOf(8), nights = 2), flat(BASE), listOf(BREAKFAST_INCLUDED))
        val night = price.nights.first()

        night.roomAmount shouldBe money("460.00")
        night.packages.single().quantity shouldBe 3
        night.includedPackagesAmount shouldBe money("105.00")
        night.accommodationAmount shouldBe money("355.00")
        night.total shouldBe money("460.00")
        price.total shouldBe money("920.00")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notPricedCases")
    fun `a stay without price says why, with the dates`(case: NotPricedCase) {
        val result = StayPricing.price(case.query, case.prices, BANDS, case.packages)

        val refusals = result.shouldBeInstanceOf<StayPriceResult.NotPriced>().refusals
        withClue(case.name) {
            refusals.map { it.code to it.dates } shouldContainExactly case.expected
        }
    }

    // --- Vendabilidade ----------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("saleCases")
    fun `each sale rule refuses on its own, with its code, and only on the date that matters`(case: SaleCase) {
        val refusals = SaleRules.evaluate(case.query, case.channel, case.conditions)

        withClue(case.name) { refusals.map { it.code } shouldContainExactly case.expected }
    }

    @Test
    fun `all reasons come together, in a fixed order`() {
        val conditions =
            conditions(
                planActive = false,
                restrictions =
                    mapOf(
                        ARRIVAL to
                            EffectiveRestriction.of(RestrictionValues(closedToArrival = true, minStay = 5), null),
                        DEPARTURE to EffectiveRestriction.of(RestrictionValues(closedToDeparture = true), null),
                        ARRIVAL.plusDays(1) to EffectiveRestriction.of(RestrictionValues(closed = true), null),
                    ),
            )

        val refusals = SaleRules.evaluate(query(adults = 5), SalesChannel.OTA, conditions)

        refusals.map { it.code } shouldContainExactly
            listOf(
                RatesErrorCodes.RATE_PLAN_INACTIVE,
                RatesErrorCodes.SALES_CHANNEL_NOT_ALLOWED,
                RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE,
                RatesErrorCodes.CLOSED_TO_ARRIVAL,
                RatesErrorCodes.CLOSED_TO_DEPARTURE,
                RatesErrorCodes.MIN_STAY_NOT_MET,
                RatesErrorCodes.STAY_DATE_CLOSED,
            )
        refusals.single { it.code == RatesErrorCodes.STAY_DATE_CLOSED }.dates shouldContainExactly
            listOf(ARRIVAL.plusDays(1))
        refusals.single { it.code == RatesErrorCodes.CLOSED_TO_DEPARTURE }.dates shouldContainExactly listOf(DEPARTURE)
    }

    // --- Formato --------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStays")
    fun `a stay out of format is refused with STAY_INVALID and the field`(case: InvalidStay) {
        val error = shouldThrow<InvalidInputException> { StayRules.requireValid(case.query, BANDS) }

        error.code shouldBe RatesErrorCodes.STAY_INVALID
        error.violations.map { it.field } shouldContainExactly case.fields
    }

    @Test
    fun `a valid stay passes the format check, up to 365 nights and the child age limit`() {
        StayRules.requireValid(query(nights = 365, childAges = listOf(0, 12)), BANDS)
        StayRules.requireValid(query(childAges = listOf(10)), SINGLE_BAND)
    }

    // --- Casos ----------------------------------------------------------------------------------------------------

    data class PricedCase(
        val name: String,
        val query: StayQuery,
        val prices: Map<LocalDate, PriceSet>,
        val packages: List<PackageSummary>,
        val total: String,
        val included: String = "0.00",
        val additional: String = "0.00",
    ) {
        override fun toString() = name
    }

    data class NotPricedCase(
        val name: String,
        val query: StayQuery,
        val prices: Map<LocalDate, PriceSet>,
        val packages: List<PackageSummary> = emptyList(),
        val expected: List<Pair<ErrorCode, List<LocalDate>>>,
    ) {
        override fun toString() = name
    }

    data class SaleCase(
        val name: String,
        val query: StayQuery,
        val conditions: SaleConditions,
        val expected: List<ErrorCode>,
        val channel: SalesChannel = SalesChannel.DIRECT,
    ) {
        override fun toString() = name
    }

    data class InvalidStay(
        val name: String,
        val query: StayQuery,
        val fields: List<String>,
    ) {
        override fun toString() = name
    }

    companion object {
        private val ROOM_TYPE = RoomTypeId(UuidV7.generate())
        private val OTHER_ROOM_TYPE = RoomTypeId(UuidV7.generate())
        private val PLAN = RatePlanId(UuidV7.generate())

        /** A data operacional dos casos de venda; a chegada é 9 dias depois, e a estadia tem 3 noites. */
        private val BUSINESS_DATE: LocalDate = LocalDate.of(2026, 11, 1)
        private val ARRIVAL: LocalDate = BUSINESS_DATE.plusDays(9)
        private val DEPARTURE: LocalDate = ARRIVAL.plusDays(3)
        private val MIDDLE: LocalDate = ARRIVAL.plusDays(1)
        private val LAST_NIGHT: LocalDate = DEPARTURE.minusDays(1)

        private val BANDS =
            ChildBands.of(12, listOf(AgeBand(0, 5, "Bebê"), AgeBand(6, 12, "Criança")))
        private val SINGLE_BAND = ChildBands.of(10, emptyList())

        private val BASE =
            PriceSet(
                money("300.00"),
                money("400.00"),
                money("120.00"),
                listOf(ChildPrice(0, 5, money("0.00")), ChildPrice(6, 12, money("60.00"))),
            )

        /** O derivado "Não reembolsável" (−10%) e, dele, o "Com café" (+ R$ 40 por pessoa). */
        private val DERIVED =
            DerivationRule.applyAll(
                listOf(
                    DerivationRule(PLAN, PriceAdjustment.ByPercent(Percent.of("-10"))),
                    DerivationRule(PLAN, PriceAdjustment.ByAmount(money("40.00"), perPerson = true)),
                ),
                BASE,
            )

        private val BREAKFAST_INCLUDED =
            pkg("CAFE", "35.00", PackagePricing.PER_PERSON_PER_NIGHT, included = true)
        private val BREAKFAST_ADDITIONAL =
            pkg("CAFE-ADIC", "35.00", PackagePricing.PER_PERSON_PER_NIGHT, included = false)
        private val PARKING = pkg("ESTAC", "25.00", PackagePricing.PER_NIGHT, included = false)
        private val DECORATION_PER_STAY =
            pkg(
                "DECOR",
                "150.00",
                PackagePricing.PER_STAY,
                included = false,
                rhythm = PackagePostingRhythm.ON_ARRIVAL,
            )
        private val WELCOME_INCLUDED_PER_STAY =
            pkg("BOAS-VINDAS", "80.00", PackagePricing.PER_STAY, included = true, PackagePostingRhythm.ON_ARRIVAL)
        private val DAILY_PER_NIGHT_ON_ARRIVAL =
            pkg("TRANSF", "20.00", PackagePricing.PER_NIGHT, included = false, PackagePostingRhythm.ON_ARRIVAL)

        @JvmStatic
        @Suppress("LongMethod") // a tabela inteira dos casos de preço
        fun pricedCases(): List<PricedCase> =
            listOf(
                PricedCase("1 adulto, 2 noites", query(adults = 1, nights = 2), flat(BASE), emptyList(), "600.00"),
                PricedCase("2 adultos, 3 noites", query(nights = 3), flat(BASE), emptyList(), "1200.00"),
                PricedCase(
                    "3 adultos e 2 crianças de faixas diferentes",
                    query(adults = 3, childAges = listOf(8, 3), nights = 2),
                    flat(BASE),
                    emptyList(),
                    // 400 + 120 (extra) + 60 (6–12) + 0 (0–5) = 580 por noite.
                    "1160.00",
                ),
                PricedCase(
                    "derivado −10% e + R$ 40/pessoa, 2 adultos e criança de 8",
                    query(childAges = listOf(8), nights = 2),
                    flat(DERIVED),
                    emptyList(),
                    // 2 adultos: 400 − 40 + 80 = 440; criança: 60 − 6 + 40 = 94.
                    "1068.00",
                ),
                PricedCase(
                    "pacote adicional por pessoa e noite",
                    query(childAges = listOf(8), nights = 2),
                    flat(BASE),
                    listOf(BREAKFAST_ADDITIONAL),
                    // (460 + 3 × 35) × 2.
                    "1130.00",
                    additional = "210.00",
                ),
                PricedCase(
                    "pacote adicional por estadia",
                    query(nights = 3),
                    flat(BASE),
                    listOf(DECORATION_PER_STAY),
                    "1350.00",
                    additional = "150.00",
                ),
                PricedCase(
                    "pacote incluso por pessoa e noite",
                    query(nights = 2),
                    flat(BASE),
                    listOf(BREAKFAST_INCLUDED),
                    "800.00",
                    included = "140.00",
                ),
                PricedCase(
                    "pacote incluso por estadia",
                    query(nights = 3),
                    flat(BASE),
                    listOf(WELCOME_INCLUDED_PER_STAY),
                    "1200.00",
                    included = "80.00",
                ),
                PricedCase(
                    "pacote por noite lançado na chegada continua por noite",
                    query(nights = 3),
                    flat(BASE),
                    listOf(DAILY_PER_NIGHT_ON_ARRIVAL),
                    "1260.00",
                    additional = "60.00",
                ),
                PricedCase(
                    "derivado + pacote por pessoa e noite + criança por faixa + incluso + por estadia",
                    query(adults = 3, childAges = listOf(4, 9), nights = 3),
                    flat(DERIVED),
                    listOf(BREAKFAST_ADDITIONAL, BREAKFAST_INCLUDED, PARKING, DECORATION_PER_STAY),
                    // Diária: 440 + (108 + 40) + (0 + 40) + 94 = 722; adicionais: 5 × 35 + 25 = 200 por noite e 150 na
                    // chegada; inclusos: 5 × 35 = 175 por noite. 3 × 722 + 3 × 200 + 150.
                    "2916.00",
                    included = "525.00",
                    additional = "750.00",
                ),
                PricedCase(
                    "preços diferentes em cada noite",
                    query(adults = 1, nights = 3),
                    mapOf(
                        ARRIVAL to BASE,
                        MIDDLE to BASE.copy(oneAdult = money("333.33")),
                        LAST_NIGHT to BASE.copy(oneAdult = money("0.01")),
                    ),
                    emptyList(),
                    "633.34",
                ),
                PricedCase(
                    "criança de 2 anos na faixa de 0 a 5, de preço zero",
                    query(childAges = listOf(2), nights = 1),
                    flat(BASE),
                    emptyList(),
                    "400.00",
                ),
            )

        @JvmStatic
        @Suppress("LongMethod") // a tabela inteira dos casos sem preço
        fun notPricedCases(): List<NotPricedCase> =
            listOf(
                NotPricedCase(
                    "noite sem preço gravado",
                    query(nights = 3),
                    flat(BASE) - MIDDLE,
                    expected = listOf(RatesErrorCodes.RATE_NOT_PRICED to listOf(MIDDLE)),
                ),
                NotPricedCase(
                    "dois adultos sem preço",
                    query(nights = 1),
                    flat(BASE.copy(twoAdults = null)),
                    expected = listOf(RatesErrorCodes.OCCUPANCY_NOT_PRICED to listOf(ARRIVAL)),
                ),
                NotPricedCase(
                    "adulto extra sem preço",
                    query(adults = 3, nights = 1),
                    flat(BASE.copy(extraAdult = null)),
                    expected = listOf(RatesErrorCodes.OCCUPANCY_NOT_PRICED to listOf(ARRIVAL)),
                ),
                NotPricedCase(
                    "um adulto que a derivação deixou sem preço",
                    query(adults = 1, nights = 2),
                    mapOf(
                        ARRIVAL to PriceAdjustment.ByAmount(money("-300.00"), perPerson = false).apply(BASE),
                        MIDDLE to BASE,
                    ),
                    expected = listOf(RatesErrorCodes.OCCUPANCY_NOT_PRICED to listOf(ARRIVAL)),
                ),
                NotPricedCase(
                    "criança só com o preço de uma faixa obsoleta (nunca o adulto extra)",
                    query(childAges = listOf(8), nights = 1),
                    flat(BASE.copy(children = listOf(ChildPrice(0, 12, money("50.00"))))),
                    expected = listOf(RatesErrorCodes.CHILD_AGE_NOT_PRICED to listOf(ARRIVAL)),
                ),
                NotPricedCase(
                    "criança numa faixa sem preço",
                    query(childAges = listOf(3), nights = 1),
                    flat(BASE.copy(children = listOf(ChildPrice(6, 12, money("60.00"))))),
                    expected = listOf(RatesErrorCodes.CHILD_AGE_NOT_PRICED to listOf(ARRIVAL)),
                ),
                NotPricedCase(
                    "inclusos acima da diária",
                    query(adults = 1, nights = 2),
                    mapOf(ARRIVAL to BASE.copy(oneAdult = money("30.00")), MIDDLE to BASE),
                    listOf(BREAKFAST_INCLUDED),
                    listOf(RatesErrorCodes.INCLUDED_PACKAGES_EXCEED_RATE to listOf(ARRIVAL)),
                ),
                NotPricedCase(
                    "vários motivos, cada um com as suas datas",
                    query(adults = 3, childAges = listOf(8), nights = 3),
                    mapOf(
                        ARRIVAL to BASE.copy(extraAdult = null),
                        LAST_NIGHT to BASE.copy(children = emptyList()),
                    ),
                    expected =
                        listOf(
                            RatesErrorCodes.RATE_NOT_PRICED to listOf(MIDDLE),
                            RatesErrorCodes.OCCUPANCY_NOT_PRICED to listOf(ARRIVAL),
                            RatesErrorCodes.CHILD_AGE_NOT_PRICED to listOf(LAST_NIGHT),
                        ),
                ),
            )

        @JvmStatic
        @Suppress("LongMethod") // a tabela inteira dos casos de venda
        fun saleCases(): List<SaleCase> =
            listOf(
                SaleCase("sem nenhuma restrição", query(), conditions(), emptyList()),
                SaleCase(
                    "plano desativado",
                    query(),
                    conditions(planActive = false),
                    listOf(RatesErrorCodes.RATE_PLAN_INACTIVE),
                ),
                SaleCase(
                    "canal não permitido",
                    query(),
                    conditions(),
                    listOf(RatesErrorCodes.SALES_CHANNEL_NOT_ALLOWED),
                    SalesChannel.OTA,
                ),
                SaleCase(
                    "tipo fora do plano",
                    query(roomType = OTHER_ROOM_TYPE),
                    conditions(),
                    listOf(RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN),
                ),
                SaleCase(
                    "tipo desativado no rooms",
                    query(),
                    conditions(roomTypeActive = false),
                    listOf(RatesErrorCodes.ROOM_TYPE_INACTIVE),
                ),
                SaleCase(
                    "adultos acima da ocupação máxima",
                    query(adults = 4),
                    conditions(),
                    listOf(RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE),
                ),
                SaleCase(
                    "crianças acima da ocupação máxima",
                    query(childAges = listOf(3, 4)),
                    conditions(),
                    listOf(RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE),
                ),
                SaleCase("ocupação na máxima", query(adults = 3, childAges = listOf(4)), conditions(), emptyList()),
                SaleCase(
                    "noite do meio fechada",
                    query(),
                    restricted(MIDDLE, RestrictionValues(closed = true)),
                    listOf(RatesErrorCodes.STAY_DATE_CLOSED),
                ),
                SaleCase(
                    "noite da chegada fechada",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(closed = true)),
                    listOf(RatesErrorCodes.STAY_DATE_CLOSED),
                ),
                SaleCase(
                    "fechado na data de saída não recusa (não é noite)",
                    query(),
                    restricted(DEPARTURE, RestrictionValues(closed = true)),
                    emptyList(),
                ),
                SaleCase(
                    "CTA na chegada",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(closedToArrival = true)),
                    listOf(RatesErrorCodes.CLOSED_TO_ARRIVAL),
                ),
                SaleCase(
                    "CTA numa noite do meio não recusa",
                    query(),
                    restricted(MIDDLE, RestrictionValues(closedToArrival = true)),
                    emptyList(),
                ),
                SaleCase(
                    "CTA na saída não recusa",
                    query(),
                    restricted(DEPARTURE, RestrictionValues(closedToArrival = true)),
                    emptyList(),
                ),
                SaleCase(
                    "CTD na saída",
                    query(),
                    restricted(DEPARTURE, RestrictionValues(closedToDeparture = true)),
                    listOf(RatesErrorCodes.CLOSED_TO_DEPARTURE),
                ),
                SaleCase(
                    "CTD na última noite não recusa (sai no dia seguinte)",
                    query(),
                    restricted(LAST_NIGHT, RestrictionValues(closedToDeparture = true)),
                    emptyList(),
                ),
                SaleCase(
                    "CTD na chegada não recusa",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(closedToDeparture = true)),
                    emptyList(),
                ),
                SaleCase(
                    "estadia mínima da chegada acima das noites",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(minStay = 4)),
                    listOf(RatesErrorCodes.MIN_STAY_NOT_MET),
                ),
                SaleCase(
                    "estadia mínima igual às noites",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(minStay = 3)),
                    emptyList(),
                ),
                SaleCase(
                    "estadia mínima de uma noite do meio não vale (on arrival)",
                    query(),
                    restricted(MIDDLE, RestrictionValues(minStay = 7)),
                    emptyList(),
                ),
                SaleCase(
                    "estadia máxima da chegada abaixo das noites",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(maxStay = 2)),
                    listOf(RatesErrorCodes.MAX_STAY_EXCEEDED),
                ),
                SaleCase(
                    "estadia máxima igual às noites",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(maxStay = 3)),
                    emptyList(),
                ),
                SaleCase(
                    "estadia máxima de uma noite do meio não vale",
                    query(),
                    restricted(MIDDLE, RestrictionValues(maxStay = 1)),
                    emptyList(),
                ),
                SaleCase(
                    "antecedência mínima acima dos 9 dias",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(minAdvanceDays = 10)),
                    listOf(RatesErrorCodes.MIN_ADVANCE_NOT_MET),
                ),
                SaleCase(
                    "antecedência mínima igual aos 9 dias",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(minAdvanceDays = 9)),
                    emptyList(),
                ),
                SaleCase(
                    "antecedência máxima abaixo dos 9 dias",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(maxAdvanceDays = 8)),
                    listOf(RatesErrorCodes.MAX_ADVANCE_EXCEEDED),
                ),
                SaleCase(
                    "antecedência máxima igual aos 9 dias",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(maxAdvanceDays = 9)),
                    emptyList(),
                ),
                SaleCase(
                    "antecedência de uma noite do meio não vale",
                    query(),
                    restricted(MIDDLE, RestrictionValues(minAdvanceDays = 30)),
                    emptyList(),
                ),
                SaleCase(
                    "antecedência contada da data operacional, não de outra data",
                    query(),
                    conditions(businessDate = ARRIVAL.minusDays(2)).withRestriction(
                        ARRIVAL,
                        RestrictionValues(minAdvanceDays = 3),
                    ),
                    listOf(RatesErrorCodes.MIN_ADVANCE_NOT_MET),
                ),
                SaleCase(
                    "chegada antes da data operacional",
                    query(),
                    conditions(businessDate = ARRIVAL.plusDays(1)),
                    listOf(RatesErrorCodes.ARRIVAL_BEFORE_BUSINESS_DATE),
                ),
                SaleCase(
                    "chegada na data operacional",
                    query(),
                    conditions(businessDate = ARRIVAL).withRestriction(
                        ARRIVAL,
                        RestrictionValues(minAdvanceDays = 0),
                    ),
                    emptyList(),
                ),
                SaleCase(
                    "restrição incoerente (mínimo acima do máximo) nunca vende",
                    query(),
                    restricted(ARRIVAL, RestrictionValues(minStay = 4, maxStay = 2)),
                    listOf(RatesErrorCodes.MIN_STAY_NOT_MET, RatesErrorCodes.MAX_STAY_EXCEEDED),
                ),
            )

        @JvmStatic
        fun invalidStays(): List<InvalidStay> =
            listOf(
                InvalidStay("saída igual à chegada", query(nights = 0), listOf("departure")),
                InvalidStay("saída antes da chegada", query(nights = -1), listOf("departure")),
                InvalidStay("mais de 365 noites", query(nights = 366), listOf("departure")),
                InvalidStay("nenhum adulto", query(adults = 0), listOf("adults")),
                InvalidStay(
                    "idade acima da idade limite e negativa",
                    query(childAges = listOf(5, 13, -1)),
                    listOf("childAges[1]", "childAges[2]"),
                ),
            )

        private fun query(
            adults: Int = 2,
            childAges: List<Int> = emptyList(),
            nights: Long = 3,
            roomType: RoomTypeId = ROOM_TYPE,
        ) = StayQuery(PLAN, roomType, ARRIVAL, ARRIVAL.plusDays(nights), adults, childAges)

        private fun nightsOf(query: StayQuery): List<LocalDate> =
            generateSequence(query.arrival) { it.plusDays(1) }.takeWhile { it.isBefore(query.departure) }.toList()

        /** O mesmo preço em todas as noites possíveis dos casos. */
        private fun flat(prices: PriceSet): Map<LocalDate, PriceSet> =
            (0L until 10L).associate { ARRIVAL.plusDays(it) to prices }

        private fun conditions(
            planActive: Boolean = true,
            roomTypeActive: Boolean = true,
            businessDate: LocalDate = BUSINESS_DATE,
            restrictions: Map<LocalDate, EffectiveRestriction> = emptyMap(),
        ) = SaleConditions(
            planActive = planActive,
            channels = setOf(SalesChannel.DIRECT, SalesChannel.WEBSITE),
            planRoomTypeIds = setOf(ROOM_TYPE),
            roomTypeActive = roomTypeActive,
            maxAdults = 3,
            maxChildren = 1,
            businessDate = businessDate,
            restrictions = restrictions,
        )

        private fun restricted(
            date: LocalDate,
            values: RestrictionValues,
        ) = conditions().withRestriction(date, values)

        private fun SaleConditions.withRestriction(
            date: LocalDate,
            values: RestrictionValues,
        ) = copy(restrictions = restrictions + (date to EffectiveRestriction.of(values, null)))

        private fun pkg(
            code: String,
            price: String,
            pricing: PackagePricing,
            included: Boolean,
            rhythm: PackagePostingRhythm = PackagePostingRhythm.DAILY,
        ) = PackageSummary(
            PackageId(UuidV7.generate()),
            code,
            "Pacote $code",
            money(price),
            pricing,
            rhythm,
            included,
            TransactionCodeId(UuidV7.generate()),
            active = true,
            version = 0,
        )

        private fun money(value: String) = Money.of(value)

        private fun priced(
            query: StayQuery,
            prices: Map<LocalDate, PriceSet>,
            packages: List<PackageSummary> = emptyList(),
        ): StayPrice =
            StayPricing
                .price(query, prices, BANDS, packages)
                .shouldBeInstanceOf<StayPriceResult.Priced>()
                .price
    }
}

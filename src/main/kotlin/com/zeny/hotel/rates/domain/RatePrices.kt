package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.RateAdjustmentType
import com.zeny.hotel.rates.RatePlanDerivation
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O preço de criança de uma faixa etária (em anos completos, inclusive), como foi gravado. */
data class ChildPrice(
    val minAge: Int,
    val maxAge: Int,
    val amount: Money,
)

/**
 * As ocupações que um plano derivado deixou **sem preço** (decisão do usuário na revisão humana da E02, 2026-10-20):
 * o ajuste da derivação deu valor menor ou igual a zero num valor que o base tinha (salvo o base já em 0,00, que fica
 * 0,00). A ocupação fica não vendável naquela data, como uma sem preço gravado, e a leitura sinaliza.
 */
data class DerivationGaps(
    val oneAdult: Boolean = false,
    val twoAdults: Boolean = false,
    val extraAdult: Boolean = false,
    /** As faixas de criança (idades, inclusive) sem preço. */
    val children: List<Pair<Int, Int>> = emptyList(),
) {
    fun isEmpty(): Boolean = this == NONE

    operator fun plus(other: DerivationGaps) =
        DerivationGaps(
            oneAdult || other.oneAdult,
            twoAdults || other.twoAdults,
            extraAdult || other.extraAdult,
            (children + other.children).distinct().sortedBy { it.first },
        )

    companion object {
        val NONE = DerivationGaps()
    }
}

/**
 * Os preços de um tipo de UH numa data (RF-TAR-02, SPEC §8.2.5): [oneAdult] (sempre gravado; nulo só num derivado
 * cujo ajuste o zerou, [withoutPrice]), [twoAdults] e [extraAdult] (por adulto além do segundo; nulos = a ocupação não
 * tem preço nesta data) e um preço por faixa etária de criança ([children], em ordem de idade; uma faixa sem preço não
 * é vendável para criança daquela idade). Os valores são diárias da UH (um e dois adultos) ou por pessoa (adulto extra
 * e criança), de 0,00 a 1.000.000,00.
 */
data class PriceSet(
    val oneAdult: Money?,
    val twoAdults: Money?,
    val extraAdult: Money?,
    val children: List<ChildPrice>,
    /** O que a derivação deixou sem preço (vazio nos preços gravados). */
    val withoutPrice: DerivationGaps = DerivationGaps.NONE,
) {
    /**
     * Os preços derivados: [transform] aplicado a cada valor com o número de pessoas que ele cobre (com
     * [roomRatesOnly], só às diárias de um e de dois adultos). Um resultado menor ou igual a zero deixa a ocupação sem
     * preço (em [withoutPrice]), salvo quando o valor do base já era 0,00 e o ajuste o mantém em 0,00 (a cortesia
     * gravada, sob percentual).
     */
    fun derive(
        roomRatesOnly: Boolean = false,
        transform: (Money, Int) -> Money,
    ): PriceSet {
        fun derived(
            value: Money?,
            persons: Int,
        ): Money? =
            value?.let { base ->
                transform(base, persons).takeIf { it.isPositive() || (base.isZero() && it.isZero()) }
            }

        val one = derived(oneAdult, 1)
        val two = derived(twoAdults, 2)
        val extra = if (roomRatesOnly) extraAdult else derived(extraAdult, 1)
        val kids =
            if (roomRatesOnly) {
                children.map { it to it }
            } else {
                children.map { it to derived(it.amount, 1)?.let { amount -> it.copy(amount = amount) } }
            }
        val gaps =
            DerivationGaps(
                oneAdult != null && one == null,
                twoAdults != null && two == null,
                extraAdult != null && extra == null,
                kids.filter { it.second == null }.map { it.first.minAge to it.first.maxAge },
            )
        return PriceSet(one, two, extra, kids.mapNotNull { it.second }, withoutPrice + gaps)
    }

    companion object {
        val MAX: Money = Money.of("1000000.00")

        /** Os preços conferidos (faixa de valores e faixas de criança sem repetição), com as crianças em ordem. */
        fun of(
            oneAdult: Money,
            twoAdults: Money?,
            extraAdult: Money?,
            children: List<ChildPrice>,
        ): PriceSet {
            amount("prices.oneAdult", oneAdult)
            twoAdults?.let { amount("prices.twoAdults", it) }
            extraAdult?.let { amount("prices.extraAdult", it) }
            children.forEachIndexed { index, child -> amount("prices.children[$index].amount", child.amount) }
            return PriceSet(oneAdult, twoAdults, extraAdult, children.sortedBy { it.minAge })
        }

        private fun amount(
            field: String,
            value: Money,
        ) {
            if (value.isNegative() || value > MAX) {
                throw RatesRules.invalid(
                    RatesErrorCodes.RATE_PRICE_INVALID,
                    field,
                    "Informe preços de 0,00 a 1.000.000,00.",
                )
            }
        }
    }
}

/** Uma faixa etária de criança do hotel (em anos completos, inclusive), com o nome. */
data class AgeBand(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
) {
    fun matches(price: ChildPrice): Boolean = price.minAge == minAge && price.maxAge == maxAge
}

/**
 * As faixas de criança **atuais** do hotel (H2): as `childAgeBands` da política ou, sem faixas, uma só, de 0 à idade
 * limite de criança (o preço único de criança). Decisão do usuário na E02 (2026-10-20): o preço guarda a faixa (as
 * idades) com que foi gravado; um preço numa faixa que não existe mais é **obsoleto**: ignorado (a data fica sem preço
 * de criança naquela faixa, não vendável para criança dessa idade, regra que a E04 aplica) até o gestor regravar, e a
 * leitura o sinaliza. Nada é recalculado em silêncio e nunca cai no preço de adulto extra.
 */
class ChildBands private constructor(
    val bands: List<AgeBand>,
) {
    /** Os preços de criança divididos em atuais (com a faixa, em ordem) e obsoletos. */
    fun classify(children: List<ChildPrice>): Pair<List<Pair<AgeBand, Money>>, List<ChildPrice>> {
        val current = bands.mapNotNull { band -> children.firstOrNull(band::matches)?.let { band to it.amount } }
        val obsolete = children.filter { child -> bands.none { it.matches(child) } }
        return current to obsolete
    }

    /** A idade limite de criança (a maior idade da última faixa; as faixas vão de 0 até ela, contíguas). */
    val maxAge: Int get() = bands.maxOf { it.maxAge }

    /** A faixa atual em que cai a idade [age] (anos completos); `null` fora de 0 a [maxAge]. */
    fun bandOf(age: Int): AgeBand? = bands.firstOrNull { age in it.minAge..it.maxAge }

    /** Cada preço de criança numa faixa atual, sem repetir a faixa; senão `400` com o campo do item. */
    fun requireCurrent(children: List<ChildPrice>) {
        children.forEachIndexed { index, child ->
            val repeated = children.take(index).any { it.minAge == child.minAge && it.maxAge == child.maxAge }
            if (bands.none { it.matches(child) } || repeated) {
                throw RatesRules.invalid(
                    RatesErrorCodes.RATE_PRICE_CHILD_BANDS_INVALID,
                    "prices.children[$index]",
                    "Informe cada preço de criança uma vez, numa das faixas etárias atuais do hotel.",
                )
            }
        }
    }

    companion object {
        /** O nome da faixa única, quando o hotel não diferencia idades. */
        const val SINGLE_BAND_LABEL = "Criança"

        fun of(
            childMaxAge: Int,
            hotelBands: List<AgeBand>,
        ): ChildBands = ChildBands(hotelBands.ifEmpty { listOf(AgeBand(0, childMaxAge, SINGLE_BAND_LABEL)) })
    }
}

/**
 * Como um plano derivado ajusta os preços do base (RF-TAR-03). Todo valor calculado passa pela regra única de [Money]
 * (RN-02). Um desconto que leva o valor a zero ou abaixo deixa a ocupação **sem preço** naquela data (não vendável,
 * sinalizada na leitura; decisão do usuário na revisão humana da E02), nunca uma diária grátis: só o valor que o base
 * já tinha em 0,00 continua 0,00 sob percentual (um valor negativo somado a ele o deixa sem preço).
 */
sealed interface PriceAdjustment {
    fun apply(prices: PriceSet): PriceSet

    /** Cada valor mais [percent] dele (negativo = desconto), arredondado por `Money` (`Percent.of`). */
    data class ByPercent(
        val percent: Percent,
    ) : PriceAdjustment {
        override fun apply(prices: PriceSet): PriceSet = prices.derive { value, _ -> value + percent.of(value) }
    }

    /**
     * [amount] somado (negativo = desconto). Por UH ([perPerson] falso): só nas diárias de um e de dois adultos (o
     * adulto extra e a criança ficam iguais). Por pessoa: uma vez por pessoa que o valor cobre (uma no de um adulto,
     * duas no de dois, uma no adulto extra e uma em cada faixa de criança).
     */
    data class ByAmount(
        val amount: Money,
        val perPerson: Boolean,
    ) : PriceAdjustment {
        override fun apply(prices: PriceSet): PriceSet =
            if (perPerson) {
                prices.derive { value, persons -> value + amount * persons }
            } else {
                prices.derive(roomRatesOnly = true) { value, _ -> value + amount }
            }
    }

    companion object {
        private val MIN_PERCENT = Percent.of("-100")
        private val MAX_AMOUNT = Money.of("1000000.00")

        /** A regra conferida: percentual acima de -100% e valor de -1.000.000,00 a 1.000.000,00; senão `400`. */
        fun of(
            type: RateAdjustmentType,
            percent: Percent?,
            amount: Money?,
            perPerson: Boolean?,
        ): PriceAdjustment =
            when (type) {
                RateAdjustmentType.PERCENT -> {
                    if (amount != null || perPerson != null) {
                        throw invalid("amount", "O percentual não leva valor nem \"por pessoa\".")
                    }
                    if (percent == null || percent <= MIN_PERCENT) {
                        throw invalid("percent", "Informe um percentual acima de -100.")
                    }
                    ByPercent(percent)
                }

                RateAdjustmentType.AMOUNT -> {
                    if (percent != null) throw invalid("percent", "O valor não leva percentual.")
                    if (amount == null || amount.abs() > MAX_AMOUNT) {
                        throw invalid("amount", "Informe um valor de -1.000.000,00 a 1.000.000,00.")
                    }
                    if (perPerson == null) throw invalid("perPerson", "Informe se o valor é por pessoa ou por UH.")
                    ByAmount(amount, perPerson)
                }
            }

        private fun invalid(
            field: String,
            message: String,
        ) = RatesRules.invalid(RatesErrorCodes.RATE_PLAN_DERIVATION_INVALID, field, message)
    }
}

/** A regra de um plano derivado: o plano [basePlanId] e o [adjustment] (H1: calculada na leitura). */
data class DerivationRule(
    val basePlanId: RatePlanId,
    val adjustment: PriceAdjustment,
) {
    fun toPublic(): RatePlanDerivation =
        when (adjustment) {
            is PriceAdjustment.ByPercent -> {
                RatePlanDerivation(basePlanId, RateAdjustmentType.PERCENT, adjustment.percent, null, null)
            }

            is PriceAdjustment.ByAmount -> {
                RatePlanDerivation(
                    basePlanId,
                    RateAdjustmentType.AMOUNT,
                    null,
                    adjustment.amount,
                    adjustment.perPerson,
                )
            }
        }

    companion object {
        /** Aplica as regras de [chain] (do mais próximo do base ao plano pedido) aos preços do plano raiz. */
        fun applyAll(
            chain: List<DerivationRule>,
            rootPrices: PriceSet,
        ): PriceSet = chain.fold(rootPrices) { prices, rule -> rule.adjustment.apply(prices) }
    }
}

/** As regras da árvore de derivação: sem ciclo e com no máximo [MAX_DEPTH] níveis. */
object Derivations {
    /** Níveis de derivação abaixo de um plano com preços próprios (o derivado de um derivado é o nível 2). */
    const val MAX_DEPTH = 5

    /**
     * Confere a derivação do plano [planId] do base [newBase]: [baseChain] é a cadeia a partir do base, subindo (o
     * próprio base, o base dele…, até um plano com preços próprios, ou parando ao chegar a [planId]);
     * [descendantsHeight] é o número de níveis dos planos que derivam de [planId] (zero se nenhum). Ciclo (o base é o
     * próprio plano ou deriva dele): `422 RATE_PLAN_DERIVATION_CYCLE`; mais de [MAX_DEPTH] níveis: `422
     * RATE_PLAN_DERIVATION_TOO_DEEP`.
     */
    fun requireValid(
        planId: RatePlanId,
        newBase: RatePlanId,
        baseChain: List<RatePlanId>,
        descendantsHeight: Int,
    ) {
        if (newBase == planId || planId in baseChain) {
            throw BusinessRuleException(
                RatesErrorCodes.RATE_PLAN_DERIVATION_CYCLE,
                "O plano não pode derivar de si mesmo, nem de um plano que deriva dele.",
            )
        }
        if (baseChain.size + descendantsHeight > MAX_DEPTH) {
            throw BusinessRuleException(
                RatesErrorCodes.RATE_PLAN_DERIVATION_TOO_DEEP,
                "A derivação passaria de $MAX_DEPTH níveis de planos.",
            )
        }
    }
}

/**
 * Os códigos de erro de uma seleção de datas: os dos preços (E02, [PRICES]) ou os das restrições (E03,
 * [RESTRICTIONS]); a regra é a mesma, só o código e o assunto da mensagem mudam.
 */
data class SelectionErrors(
    /** 400: fim antes do início, ou nenhuma data nos dias da semana. */
    val periodInvalid: ErrorCode,
    /** 400: mais de [PriceSelection.MAX_DAYS] dias. */
    val periodTooLong: ErrorCode,
    /** 400: período e temporada juntos, ou nenhum dos dois. */
    val selectionInvalid: ErrorCode,
    /** 422: começa antes da data operacional (ou a temporada já terminou). */
    val periodInPast: ErrorCode,
    /** O que se edita, na mensagem ("Os preços", "As restrições"). */
    val subject: String,
) {
    companion object {
        val PRICES =
            SelectionErrors(
                RatesErrorCodes.RATE_PRICE_PERIOD_INVALID,
                RatesErrorCodes.RATE_PRICE_PERIOD_TOO_LONG,
                RatesErrorCodes.RATE_PRICE_SELECTION_INVALID,
                RatesErrorCodes.RATE_PRICE_PERIOD_IN_PAST,
                "Os preços",
            )
        val RESTRICTIONS =
            SelectionErrors(
                RatesErrorCodes.RESTRICTION_PERIOD_INVALID,
                RatesErrorCodes.RESTRICTION_PERIOD_TOO_LONG,
                RatesErrorCodes.RESTRICTION_SELECTION_INVALID,
                RatesErrorCodes.RESTRICTION_PERIOD_IN_PAST,
                "As restrições",
            )
    }
}

/**
 * As datas de uma edição ou leitura de preços (RF-TAR-04) ou de restrições (RF-TAR-05): de [from] a [to], inclusive,
 * até [MAX_DAYS] dias (decisão do usuário na E02: períodos maiores em duas requisições), só nos [weekdays] (vazio =
 * todos os dias da semana).
 */
class PriceSelection private constructor(
    val from: LocalDate,
    val to: LocalDate,
    val weekdays: Set<DayOfWeek>,
) {
    /** As datas do período nos dias da semana pedidos, em ordem. */
    fun dates(): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filter { weekdays.isEmpty() || it.dayOfWeek in weekdays }
            .toList()

    /** O mesmo filtro a partir de [date] (sem datas antes dela); `null` se o período termina antes. */
    fun startingAt(date: LocalDate): PriceSelection? =
        when {
            to.isBefore(date) -> null
            from.isBefore(date) -> PriceSelection(date, to, weekdays)
            else -> this
        }

    companion object {
        const val MAX_DAYS = 366L

        /**
         * Confere o período: fim antes do início `400` [SelectionErrors.periodInvalid]; mais de [MAX_DAYS] dias, `400`
         * [SelectionErrors.periodTooLong] (os códigos dos preços, salvo [errors]).
         */
        fun of(
            from: LocalDate,
            to: LocalDate,
            weekdays: Set<DayOfWeek> = emptySet(),
            errors: SelectionErrors = SelectionErrors.PRICES,
        ): PriceSelection {
            if (to.isBefore(from)) {
                throw RatesRules.invalid(
                    errors.periodInvalid,
                    "to",
                    "O fim do período não pode ser antes do início.",
                )
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw RatesRules.invalid(
                    errors.periodTooLong,
                    "to",
                    "Edite até $MAX_DAYS dias por vez; divida períodos maiores.",
                )
            }
            return PriceSelection(from, to, weekdays)
        }
    }
}

/** Uma linha de preço: o tipo de UH e a data. */
data class PriceKey(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
)

/**
 * O que uma edição em massa muda (H9): as linhas novas ([created]), as já gravadas com outros valores ([changed]) e as
 * retiradas ([removed]); as já gravadas com os mesmos valores ficam de fora (não mudam, nem pedem aprovação).
 */
data class PriceChanges(
    val created: List<PriceKey>,
    val changed: List<PriceKey>,
    val removed: List<PriceKey>,
) {
    /** Se a edição altera ou retira algum preço já gravado (a tarifa publicada da H9, num plano publicado). */
    val touchesRecordedPrices: Boolean get() = changed.isNotEmpty() || removed.isNotEmpty()

    val all: List<PriceKey> get() = created + changed + removed

    fun isEmpty(): Boolean = all.isEmpty()

    companion object {
        /** Gravar [target] em [keys], dado o que já existe ([existing]). */
        fun toSet(
            keys: List<PriceKey>,
            existing: Map<PriceKey, PriceSet>,
            target: PriceSet,
        ): PriceChanges =
            PriceChanges(
                created = keys.filter { existing[it] == null },
                changed = keys.filter { existing[it] != null && existing[it] != target },
                removed = emptyList(),
            )

        /** Retirar os preços de [keys], dado o que já existe ([existing]). */
        fun toRemove(
            keys: List<PriceKey>,
            existing: Map<PriceKey, PriceSet>,
        ): PriceChanges = PriceChanges(emptyList(), emptyList(), keys.filter { existing[it] != null })
    }
}

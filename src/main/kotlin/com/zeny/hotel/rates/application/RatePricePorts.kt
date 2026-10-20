package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.RateAdjustmentType
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.SeasonId
import com.zeny.hotel.rates.domain.PriceKey
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rates.domain.Season
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * Os preços gravados do `rates` (tabelas `rates.rate_price` e `rate_price_child`) e a árvore de derivação, por
 * `JdbcClient`, em bloco (uma edição em massa grava todas as linhas num só comando), só no schema `rates`.
 *
 * Travas: as escritas de derivação tomam a trava consultiva da árvore em modo exclusivo ([lockDerivations]) e as de
 * preços em modo compartilhado ([lockDerivationsShared]), sempre **antes** da linha do plano: duas derivações opostas
 * simultâneas nunca formam ciclo, e o evento de uma edição de preços lista os derivados como eles estão.
 */
interface RatePriceStore {
    fun lockDerivations()

    fun lockDerivationsShared()

    /** Os preços gravados do plano nos tipos [roomTypeIds], de [from] a [to] (inclusive). */
    fun prices(
        planId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<PriceKey, PriceSet>

    /** Grava (insere ou troca, com todas as faixas de criança) [prices] nas linhas [keys]. */
    fun upsert(
        planId: RatePlanId,
        keys: List<PriceKey>,
        prices: PriceSet,
        now: Instant,
    )

    /** Retira as linhas [keys] (as de criança junto). */
    fun remove(
        planId: RatePlanId,
        keys: List<PriceKey>,
    )

    /** Se o plano tem algum preço gravado a partir de [date]. */
    fun hasPricesFrom(
        planId: RatePlanId,
        date: LocalDate,
    ): Boolean

    /** Os planos que derivam de [planId], direta ou indiretamente, com o nível (1 = derivado direto). */
    fun descendants(planId: RatePlanId): List<DerivedPlan>
}

/** Um plano que deriva de outro, [depth] níveis abaixo dele. */
data class DerivedPlan(
    val id: RatePlanId,
    val depth: Int,
    val active: Boolean,
)

/**
 * As linhas de uma edição em massa: os tipos de UH (do plano) e o período, explícito ([from] e [to]) **ou** a
 * temporada ([seasonId]), filtrado pelos [weekdays] (vazio = todos).
 */
data class PriceTarget(
    val roomTypeIds: Set<RoomTypeId>,
    val from: LocalDate?,
    val to: LocalDate?,
    val seasonId: SeasonId?,
    val weekdays: Set<DayOfWeek>,
) {
    val dates: DateTarget get() = DateTarget(from, to, seasonId, weekdays)
}

/** As datas de uma edição em massa: o período explícito ([from] e [to]) **ou** a temporada, nos [weekdays]. */
data class DateTarget(
    val from: LocalDate?,
    val to: LocalDate?,
    val seasonId: SeasonId?,
    val weekdays: Set<DayOfWeek>,
)

/** Um preço de criança numa faixa (idades em anos completos, inclusive). */
data class ChildPriceValue(
    val minAge: Int,
    val maxAge: Int,
    val amount: Money,
)

/** Os preços pedidos numa edição em massa, como chegam da API. */
data class PriceInput(
    val oneAdult: Money,
    val twoAdults: Money?,
    val extraAdult: Money?,
    val children: List<ChildPriceValue>,
)

/** A regra de derivação pedida, como chega da API. */
data class DerivationCommand(
    val basePlanId: RatePlanId,
    val type: RateAdjustmentType,
    val percent: Percent?,
    val amount: Money?,
    val perPerson: Boolean?,
)

/** O resultado de uma edição em massa: quantas linhas foram criadas, alteradas e retiradas, e onde. */
data class PriceChangeView(
    val ratePlanId: RatePlanId,
    val matchedDates: Int,
    val created: Int,
    val changed: Int,
    val removed: Int,
    val from: LocalDate?,
    val to: LocalDate?,
    val derivedRatePlanIds: List<RatePlanId>,
)

/** O preço de criança de uma faixa atual do hotel, com o nome dela. */
data class ChildPriceView(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
    val amount: Money,
)

/** Uma faixa etária de criança (idades em anos completos, inclusive). */
data class AgeRange(
    val minAge: Int,
    val maxAge: Int,
)

/** As ocupações que a derivação deixou sem preço (o ajuste deu zero ou menos): não vendáveis na data. */
data class UnpricedByDerivation(
    val oneAdult: Boolean,
    val twoAdults: Boolean,
    val extraAdult: Boolean,
    val children: List<AgeRange>,
)

/**
 * Os preços de um tipo de UH numa data (calculados, no derivado), com as faixas de criança obsoletas à parte e, num
 * derivado, o que o ajuste deixou sem preço ([unpricedByDerivation], nulo se nada).
 */
data class DailyPriceView(
    val date: LocalDate,
    val roomTypeId: RoomTypeId,
    val oneAdult: Money?,
    val twoAdults: Money?,
    val extraAdult: Money?,
    val children: List<ChildPriceView>,
    val obsoleteChildren: List<ChildPriceValue>,
    val unpricedByDerivation: UnpricedByDerivation? = null,
)

/** Os preços de um plano num período. */
data class RatePricesView(
    val ratePlanId: RatePlanId,
    val derived: Boolean,
    val pricedBy: RatePlanId,
    val from: LocalDate,
    val to: LocalDate,
    val items: List<DailyPriceView>,
)

/** Os dados de uma temporada, como chegam da API. */
data class SeasonCommand(
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

data class SeasonView(
    val id: SeasonId,
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

internal fun Season.view() = SeasonView(id, name, startsOn, endsOn, active, createdAt, updatedAt, checkNotNull(version))

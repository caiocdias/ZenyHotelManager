package com.zeny.hotel.rates

import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.Money
import java.time.LocalDate

/**
 * Os preços e as restrições **efetivos** de um plano por tipo de UH e data (M4 E06, RF-TAR-08;
 * `RatesApi.rateCalendar`), para o calendário de disponibilidade. [childAgeBands] são as faixas de criança **atuais**
 * do hotel (H2), na ordem em que [DailyRates.children] as traz; [days] tem uma linha por tipo de UH **do plano** (entre
 * os pedidos) e por data do período, em ordem de tipo (a do pedido) e data.
 */
data class RateCalendar(
    val ratePlan: RatePlanSummary,
    val childAgeBands: List<ChildAgeBandSummary>,
    val days: List<RateCalendarDay>,
)

/** Uma faixa etária de criança atual do hotel (anos completos, inclusive). */
data class ChildAgeBandSummary(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
)

/**
 * Um tipo de UH numa data do calendário: os preços efetivos ([prices]; nulo sem preço na data) e a restrição efetiva
 * ([restriction]: a do tipo sobre a geral do plano, campo a campo; sem nenhuma gravada, nada restringe).
 */
data class RateCalendarDay(
    val roomTypeId: RoomTypeId,
    val date: LocalDate,
    val prices: DailyRates?,
    val restriction: DailyRestriction,
)

/**
 * Os preços efetivos de um tipo de UH numa data (num derivado, os do base ajustados, H1): a diária de um e de dois
 * adultos, o adulto extra (por adulto além do segundo) e um preço por faixa atual de criança; nulo = aquela ocupação
 * não tem preço na data (sem valor gravado, a lacuna da derivação ou a faixa obsoleta, que nunca aparece).
 */
data class DailyRates(
    val oneAdult: Money?,
    val twoAdults: Money?,
    val extraAdult: Money?,
    val children: List<DailyChildRate>,
)

/** O preço de uma faixa atual de criança numa data; [amount] nulo = sem preço para criança dessa idade. */
data class DailyChildRate(
    val minAge: Int,
    val maxAge: Int,
    val label: String,
    val amount: Money?,
)

/** A restrição efetiva de um tipo de UH numa data, com a semântica de [RestrictionField]. */
data class DailyRestriction(
    val closed: Boolean,
    val closedToArrival: Boolean,
    val closedToDeparture: Boolean,
    val minStay: Int?,
    val maxStay: Int?,
    val minAdvanceDays: Int?,
    val maxAdvanceDays: Int?,
)

package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RestrictionField
import com.zeny.hotel.rates.domain.EffectiveRestriction
import com.zeny.hotel.rates.domain.RestrictionKey
import com.zeny.hotel.rates.domain.RestrictionValues
import com.zeny.hotel.rooms.RoomTypeId
import java.time.Instant
import java.time.LocalDate

/**
 * As restrições de venda gravadas do `rates` (tabela `rates.rate_restriction`), por `JdbcClient`, em bloco (uma edição
 * em massa grava todas as linhas num só comando), só no schema `rates`.
 */
interface RestrictionStore {
    /**
     * As linhas gravadas do plano de [from] a [to] (inclusive): as gerais ([RestrictionKey.roomTypeId] nulo) e as dos
     * tipos [roomTypeIds].
     */
    fun restrictions(
        planId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<RestrictionKey, RestrictionValues>

    /** Grava (insere ou troca, com todos os campos) as linhas [rows]. */
    fun upsert(
        planId: RatePlanId,
        rows: Map<RestrictionKey, RestrictionValues>,
        now: Instant,
    )

    /** Retira as linhas [keys]. */
    fun remove(
        planId: RatePlanId,
        keys: List<RestrictionKey>,
    )
}

/**
 * Os campos de uma restrição como chegam da API e saem na leitura (nulo = não informado; na edição, não mexe no campo).
 */
data class RestrictionData(
    val closed: Boolean? = null,
    val closedToArrival: Boolean? = null,
    val closedToDeparture: Boolean? = null,
    val minStay: Int? = null,
    val maxStay: Int? = null,
    val minAdvanceDays: Int? = null,
    val maxAdvanceDays: Int? = null,
) {
    internal fun toValues() =
        RestrictionValues(
            closed,
            closedToArrival,
            closedToDeparture,
            minStay,
            maxStay,
            minAdvanceDays,
            maxAdvanceDays,
        )

    internal companion object {
        fun of(values: RestrictionValues) =
            RestrictionData(
                values.closed,
                values.closedToArrival,
                values.closedToDeparture,
                values.minStay,
                values.maxStay,
                values.minAdvanceDays,
                values.maxAdvanceDays,
            )
    }
}

/**
 * Uma edição em massa de restrições: os tipos de UH ([roomTypeIds] vazio = a restrição **geral** do plano; senão, as
 * dos tipos, que precisam estar no plano), as datas e os campos a gravar ([set]) e a retirar ([clear]).
 */
data class RestrictionCommand(
    val roomTypeIds: Set<RoomTypeId>,
    val dates: DateTarget,
    val set: RestrictionData,
    val clear: Set<RestrictionField>,
)

/** O resultado de uma edição de restrições: quantas linhas foram criadas, alteradas e retiradas, e onde. */
data class RestrictionChangeView(
    val ratePlanId: RatePlanId,
    val general: Boolean,
    val roomTypeIds: List<RoomTypeId>,
    val matchedDates: Int,
    val created: Int,
    val changed: Int,
    val removed: Int,
    val fields: List<RestrictionField>,
    val from: LocalDate?,
    val to: LocalDate?,
)

/** A restrição efetiva de um tipo numa data, com os campos que vieram da restrição do tipo ([fromRoomType]). */
data class EffectiveRestrictionView(
    val closed: Boolean,
    val closedToArrival: Boolean,
    val closedToDeparture: Boolean,
    val minStay: Int?,
    val maxStay: Int?,
    val minAdvanceDays: Int?,
    val maxAdvanceDays: Int?,
    val fromRoomType: List<RestrictionField>,
) {
    internal companion object {
        fun of(effective: EffectiveRestriction) =
            EffectiveRestrictionView(
                effective.closed,
                effective.closedToArrival,
                effective.closedToDeparture,
                effective.minStay,
                effective.maxStay,
                effective.minAdvanceDays,
                effective.maxAdvanceDays,
                effective.fromRoomType.sorted(),
            )
    }
}

/** Um tipo de UH numa data: a restrição gravada do tipo ([specific], nula se não há) e a efetiva. */
data class RoomTypeRestrictionView(
    val roomTypeId: RoomTypeId,
    val specific: RestrictionData?,
    val effective: EffectiveRestrictionView,
)

/** Uma data com alguma restrição gravada: a geral do plano ([general], nula se não há) e cada tipo de UH. */
data class RestrictionDayView(
    val date: LocalDate,
    val general: RestrictionData?,
    val roomTypes: List<RoomTypeRestrictionView>,
)

/** As restrições de um plano num período. */
data class RestrictionsView(
    val ratePlanId: RatePlanId,
    val from: LocalDate,
    val to: LocalDate,
    val items: List<RestrictionDayView>,
)

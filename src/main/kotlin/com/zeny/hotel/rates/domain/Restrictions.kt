package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RestrictionField
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.BusinessRuleException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Uma linha de restrição: o tipo de UH ([roomTypeId] nulo = a restrição **geral** do plano) e a data. */
data class RestrictionKey(
    val roomTypeId: RoomTypeId?,
    val date: LocalDate,
) {
    val general: Boolean get() = roomTypeId == null
}

/**
 * Os valores de uma restrição de venda gravada (RF-TAR-05, SPEC §8.2.5; M4 E03). Todo campo é **anulável**: nulo = "não
 * informado" (a restrição do tipo herda o da geral; a geral, a ausência de restrição). Os booleanos são, por isso,
 * tri-estado: numa restrição do tipo, `false` abre uma exceção sobre a geral `true` (decisão do usuário em 2026-10-20).
 *
 * Faixas: [minStay] e [maxStay] de [MIN_STAY_FLOOR] a [MAX_NIGHTS] noites; [minAdvanceDays] e [maxAdvanceDays] de 0 a
 * [MAX_ADVANCE_DAYS] dias (0 na mínima = sem antecedência, útil para a exceção do tipo).
 */
data class RestrictionValues(
    val closed: Boolean? = null,
    val closedToArrival: Boolean? = null,
    val closedToDeparture: Boolean? = null,
    val minStay: Int? = null,
    val maxStay: Int? = null,
    val minAdvanceDays: Int? = null,
    val maxAdvanceDays: Int? = null,
) {
    /** Nenhum campo informado (a linha não existe). */
    fun isEmpty(): Boolean = this == NONE

    operator fun get(field: RestrictionField): Any? =
        when (field) {
            RestrictionField.CLOSED -> closed
            RestrictionField.CLOSED_TO_ARRIVAL -> closedToArrival
            RestrictionField.CLOSED_TO_DEPARTURE -> closedToDeparture
            RestrictionField.MIN_STAY -> minStay
            RestrictionField.MAX_STAY -> maxStay
            RestrictionField.MIN_ADVANCE_DAYS -> minAdvanceDays
            RestrictionField.MAX_ADVANCE_DAYS -> maxAdvanceDays
        }

    /** Os campos informados, na ordem de [RestrictionField]. */
    fun informed(): Set<RestrictionField> = RestrictionField.entries.filterTo(linkedSetOf()) { get(it) != null }

    /** Os campos em que este valor difere de [other]. */
    fun differingFrom(other: RestrictionValues): Set<RestrictionField> =
        RestrictionField.entries.filterTo(linkedSetOf()) { get(it) != other[it] }

    /** Sem os campos [fields] (voltam a "não informado"). */
    fun without(fields: Set<RestrictionField>): RestrictionValues =
        RestrictionValues(
            closed.unless(RestrictionField.CLOSED in fields),
            closedToArrival.unless(RestrictionField.CLOSED_TO_ARRIVAL in fields),
            closedToDeparture.unless(RestrictionField.CLOSED_TO_DEPARTURE in fields),
            minStay.unless(RestrictionField.MIN_STAY in fields),
            maxStay.unless(RestrictionField.MAX_STAY in fields),
            minAdvanceDays.unless(RestrictionField.MIN_ADVANCE_DAYS in fields),
            maxAdvanceDays.unless(RestrictionField.MAX_ADVANCE_DAYS in fields),
        )

    /**
     * A **precedência** (decisão do usuário em 2026-10-20): estes valores sobre [base], **campo a campo**: o campo
     * informado aqui prevalece; o não informado herda o de [base]. Vale para a restrição do tipo de UH sobre a geral
     * do plano e para os campos de uma edição sobre a linha gravada.
     */
    fun over(base: RestrictionValues?): RestrictionValues =
        if (base == null) {
            this
        } else {
            RestrictionValues(
                closed ?: base.closed,
                closedToArrival ?: base.closedToArrival,
                closedToDeparture ?: base.closedToDeparture,
                minStay ?: base.minStay,
                maxStay ?: base.maxStay,
                minAdvanceDays ?: base.minAdvanceDays,
                maxAdvanceDays ?: base.maxAdvanceDays,
            )
        }

    /** Se a estadia mínima não passa da máxima (quando as duas estão informadas). */
    val stayRangeValid: Boolean get() = minStay == null || maxStay == null || minStay <= maxStay

    /** Se a antecedência mínima não passa da máxima (quando as duas estão informadas). */
    val advanceRangeValid: Boolean
        get() = minAdvanceDays == null || maxAdvanceDays == null || minAdvanceDays <= maxAdvanceDays

    /**
     * Confere os valores pedidos ([prefix] é o caminho do objeto no corpo): cada um na faixa (`400
     * RESTRICTION_INVALID`, inclusive negativo) e, com o mínimo e o máximo informados juntos, o mínimo até o máximo
     * (`400 RESTRICTION_RANGE_INVALID`, no campo do máximo).
     */
    fun requireValidInput(prefix: String) {
        inRange(prefix, "minStay", minStay, MIN_STAY_FLOOR..MAX_NIGHTS, "noites")
        inRange(prefix, "maxStay", maxStay, MIN_STAY_FLOOR..MAX_NIGHTS, "noites")
        inRange(prefix, "minAdvanceDays", minAdvanceDays, 0..MAX_ADVANCE_DAYS, "dias")
        inRange(prefix, "maxAdvanceDays", maxAdvanceDays, 0..MAX_ADVANCE_DAYS, "dias")
        if (!stayRangeValid) {
            throw RatesRules.invalid(
                RatesErrorCodes.RESTRICTION_RANGE_INVALID,
                "$prefix.maxStay",
                "A estadia mínima não pode ser maior que a máxima.",
            )
        }
        if (!advanceRangeValid) {
            throw RatesRules.invalid(
                RatesErrorCodes.RESTRICTION_RANGE_INVALID,
                "$prefix.maxAdvanceDays",
                "A antecedência mínima não pode ser maior que a máxima.",
            )
        }
    }

    private fun inRange(
        prefix: String,
        field: String,
        value: Int?,
        range: IntRange,
        unit: String,
    ) {
        if (value != null && value !in range) {
            throw RatesRules.invalid(
                RatesErrorCodes.RESTRICTION_INVALID,
                "$prefix.$field",
                "Informe $field de ${range.first} a ${range.last} $unit.",
            )
        }
    }

    companion object {
        val NONE = RestrictionValues()

        const val MIN_STAY_FLOOR = 1
        const val MAX_NIGHTS = 365
        const val MAX_ADVANCE_DAYS = 730

        private fun <T> T?.unless(cleared: Boolean): T? = if (cleared) null else this
    }
}

/**
 * Uma edição de restrições (RF-TAR-05): os campos de [set] (os informados) passam a valer, os de [clear] voltam a "não
 * informado" e os demais ficam como estão (decisão do implementador: a edição é **parcial**, para fechar uma data sem
 * apagar a estadia mínima dela). A linha que fica sem nenhum campo sai.
 */
class RestrictionPatch private constructor(
    val set: RestrictionValues,
    val clear: Set<RestrictionField>,
) {
    /** Os campos que a edição mexe. */
    val fields: Set<RestrictionField> get() = set.informed() + clear

    /** A linha depois da edição, dada a gravada ([current], nula se não existe). */
    fun applyTo(current: RestrictionValues?): RestrictionValues =
        set.over((current ?: RestrictionValues.NONE).without(clear))

    companion object {
        /**
         * Confere a edição: os valores de [set] (`400 RESTRICTION_INVALID`/`RESTRICTION_RANGE_INVALID`, campo
         * `set.<campo>`), nenhum campo em [set] e em [clear] ao mesmo tempo, e algo a mudar (`400
         * RESTRICTION_INVALID`).
         */
        fun of(
            set: RestrictionValues,
            clear: Set<RestrictionField>,
        ): RestrictionPatch {
            set.requireValidInput("set")
            if (set.informed().any { it in clear }) {
                throw RatesRules.invalid(
                    RatesErrorCodes.RESTRICTION_INVALID,
                    "clear",
                    "Um campo não pode ser informado e retirado na mesma edição.",
                )
            }
            if (set.isEmpty() && clear.isEmpty()) {
                throw RatesRules.invalid(
                    RatesErrorCodes.RESTRICTION_INVALID,
                    "set",
                    "Informe ao menos um campo a gravar (set) ou a retirar (clear).",
                )
            }
            return RestrictionPatch(set, clear)
        }
    }
}

/**
 * A restrição **efetiva** de um tipo de UH numa data: a do tipo sobre a geral do plano, campo a campo
 * ([RestrictionValues.over]); o que nenhuma informa não restringe (fechado, CTA e CTD falsos; mínimos e máximos
 * nulos). [fromRoomType] lista os campos que vieram da restrição do tipo (a precedência, visível na leitura).
 */
data class EffectiveRestriction(
    val closed: Boolean,
    val closedToArrival: Boolean,
    val closedToDeparture: Boolean,
    val minStay: Int?,
    val maxStay: Int?,
    val minAdvanceDays: Int?,
    val maxAdvanceDays: Int?,
    val fromRoomType: Set<RestrictionField>,
) {
    companion object {
        /** Sem restrição gravada: vende sem restrição. */
        val NONE = of(null, null)

        fun of(
            general: RestrictionValues?,
            specific: RestrictionValues?,
        ): EffectiveRestriction {
            val merged = (specific ?: RestrictionValues.NONE).over(general)
            return EffectiveRestriction(
                merged.closed ?: false,
                merged.closedToArrival ?: false,
                merged.closedToDeparture ?: false,
                merged.minStay,
                merged.maxStay,
                merged.minAdvanceDays,
                merged.maxAdvanceDays,
                specific?.informed().orEmpty(),
            )
        }
    }
}

/**
 * O que uma edição de restrições muda: as linhas novas ([created]), as gravadas com outros valores ([changed]) e as que
 * ficam vazias e saem ([removed]); [after] traz os valores novos das criadas e alteradas, e [fields] os campos que
 * mudaram em alguma linha. As linhas que a edição não muda ficam de fora (sem trilha nem evento).
 */
data class RestrictionChanges(
    val created: List<RestrictionKey>,
    val changed: List<RestrictionKey>,
    val removed: List<RestrictionKey>,
    val after: Map<RestrictionKey, RestrictionValues>,
    val fields: Set<RestrictionField>,
) {
    val all: List<RestrictionKey> get() = created + changed + removed

    fun isEmpty(): Boolean = all.isEmpty()

    companion object {
        /** Aplicar [patch] em [keys], dado o que já existe ([existing]). */
        fun of(
            keys: List<RestrictionKey>,
            existing: Map<RestrictionKey, RestrictionValues>,
            patch: RestrictionPatch,
        ): RestrictionChanges {
            val created = mutableListOf<RestrictionKey>()
            val changed = mutableListOf<RestrictionKey>()
            val removed = mutableListOf<RestrictionKey>()
            val after = linkedMapOf<RestrictionKey, RestrictionValues>()
            val fields = sortedSetOf<RestrictionField>()
            keys.forEach { key ->
                val before = existing[key]
                val next = patch.applyTo(before)
                val previous = before ?: RestrictionValues.NONE
                if (next == previous) return@forEach
                fields += next.differingFrom(previous)
                when {
                    before == null -> created += key
                    next.isEmpty() -> removed += key
                    else -> changed += key
                }
                if (!next.isEmpty()) after[key] = next
            }
            return RestrictionChanges(created, changed, removed, after, fields)
        }
    }
}

/** Regras sobre o conjunto das restrições de um plano. */
object Restrictions {
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /**
     * Confere, nas [dates], que nenhuma restrição fica com o mínimo maior que o máximo, nem a geral sozinha nem a
     * efetiva de cada tipo de UH do plano ([roomTypeIds]; a do tipo sobre a geral, campo a campo), dadas as linhas
     * [rows] como ficariam depois da edição: senão, `422 RESTRICTION_RANGE_CONFLICT` (decisão do implementador: a
     * combinação incoerente nunca é gravada, em vez de deixar a data não vendável em silêncio).
     */
    fun requireCoherent(
        rows: Map<RestrictionKey, RestrictionValues>,
        roomTypeIds: Collection<RoomTypeId>,
        dates: Collection<LocalDate>,
    ) {
        dates.distinct().sorted().forEach { date ->
            val general = rows[RestrictionKey(null, date)]
            val effective = roomTypeIds.map { (rows[RestrictionKey(it, date)] ?: RestrictionValues.NONE).over(general) }
            val candidates = listOfNotNull(general) + effective
            candidates.forEach { values ->
                if (!values.stayRangeValid) throw conflict("estadia", date)
                if (!values.advanceRangeValid) throw conflict("antecedência", date)
            }
        }
    }

    private fun conflict(
        subject: String,
        date: LocalDate,
    ) = BusinessRuleException(
        RatesErrorCodes.RESTRICTION_RANGE_CONFLICT,
        "Com as restrições já gravadas, a $subject mínima ficaria maior que a máxima em ${date.format(DATE)}.",
    )
}

package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.SeasonId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Os dados editáveis de uma temporada. */
data class SeasonDetails(
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

/**
 * Temporada nomeada (RF-TAR-04, SPEC §8.2.5): um período de datas de negócio, inclusive, de até
 * [PriceSelection.MAX_DAYS] dias, usado como **atalho** na edição em massa de preços (a temporada não guarda preços: a
 * edição grava nas datas dela). Nome único sem diferenciar maiúsculas. Nunca é apagada, só desativada.
 */
@Entity
@Table(name = "season", schema = "rates")
class Season private constructor(
    @Id
    val id: SeasonId,
    details: SeasonDetails,
    createdAt: Instant,
) : RatesRecord(createdAt) {
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate = details.startsOn
        protected set

    @Column(name = "ends_on", nullable = false)
    var endsOn: LocalDate = details.endsOn
        protected set

    init {
        apply(details)
    }

    val details: SeasonDetails get() = SeasonDetails(name, startsOn, endsOn)

    /** O período da temporada como seleção de preços (todos os dias da semana, salvo [weekdays]). */
    fun selection(weekdays: Set<DayOfWeek>): PriceSelection = PriceSelection.of(startsOn, endsOn, weekdays)

    /** Troca os dados; devolve se algo mudou. */
    fun update(
        details: SeasonDetails,
        now: Instant,
    ): Boolean = touched(apply(details), now)

    private fun apply(details: SeasonDetails): Boolean {
        val valid = validate(details)
        val changed = valid != this.details
        name = valid.name
        startsOn = valid.startsOn
        endsOn = valid.endsOn
        return changed
    }

    override fun toString(): String = "Season(id=$id)"

    companion object {
        const val MAX_NAME_LENGTH = 80

        fun create(
            details: SeasonDetails,
            now: Instant,
        ): Season = Season(SeasonId.new(), details, now)

        /** O nome sem espaços nas pontas e o período de 1 a [PriceSelection.MAX_DAYS] dias; senão `400`. */
        fun validate(details: SeasonDetails): SeasonDetails {
            val name = RatesRules.required("name", details.name, MAX_NAME_LENGTH)
            val days = ChronoUnit.DAYS.between(details.startsOn, details.endsOn) + 1
            if (days !in 1..PriceSelection.MAX_DAYS) {
                throw RatesRules.invalid(
                    RatesErrorCodes.SEASON_INVALID,
                    "endsOn",
                    "A temporada termina no dia do início ou depois, com até ${PriceSelection.MAX_DAYS} dias.",
                )
            }
            return details.copy(name = name)
        }
    }
}

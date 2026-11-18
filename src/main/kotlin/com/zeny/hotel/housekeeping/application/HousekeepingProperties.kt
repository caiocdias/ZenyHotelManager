package com.zeny.hotel.housekeeping.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Parâmetros da governança (`zeny.housekeeping`, M6 E02), conferidos no boot: um valor inválido impede a partida.
 *
 * - [preArrivalAfterDays]: a UH vaga recebe a pré-chegada (D3-E02) quando a última tarefa dela, fora as `CANCELLED`,
 *   tem [preArrivalAfterDays] dias ou mais (a UH "vaga há N dias", RF-GOV-01); de [PRE_ARRIVAL_AFTER_DAYS].
 */
@ConfigurationProperties("zeny.housekeeping")
data class HousekeepingProperties(
    val preArrivalAfterDays: Int = DEFAULT_PRE_ARRIVAL_AFTER_DAYS,
) {
    init {
        require(preArrivalAfterDays in PRE_ARRIVAL_AFTER_DAYS) {
            "zeny.housekeeping.pre-arrival-after-days precisa ficar entre ${PRE_ARRIVAL_AFTER_DAYS.first} e " +
                "${PRE_ARRIVAL_AFTER_DAYS.last}"
        }
    }

    companion object {
        const val DEFAULT_PRE_ARRIVAL_AFTER_DAYS = 3
        val PRE_ARRIVAL_AFTER_DAYS = 1..30
    }
}

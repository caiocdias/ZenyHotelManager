package com.zeny.hotel.endofday.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Quando o fechamento automático de uma data operacional vence (M6 E10): no horário do fechamento (`endOfDayTime`),
 * no fuso do hotel, nunca no da JVM. Horário antes do meio-dia é da madrugada seguinte (`businessDate + 1 dia`, como o
 * padrão 03:00); do meio-dia em diante, do próprio `businessDate` (um fechamento às 23:30 não espera quase um dia).
 *
 * A conversão é a do `ZonedDateTime.of`: um horário que não existe no dia (pulado na entrada do horário de verão)
 * avança pelo tamanho do pulo; um que existe duas vezes (na saída) é o primeiro.
 */
object EndOfDaySchedule {
    fun dueAt(
        businessDate: LocalDate,
        endOfDayTime: LocalTime,
        zone: ZoneId,
    ): Instant {
        val day = if (endOfDayTime < LocalTime.NOON) businessDate.plusDays(1) else businessDate
        return ZonedDateTime.of(day, endOfDayTime, zone).toInstant()
    }
}

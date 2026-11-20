package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.DailyStatisticsSnapshot
import java.time.LocalDate

/** A porta de gravação do snapshot diário (`reporting.daily_statistics`, só de inserção). */
interface DailyStatisticsStore {
    /** O snapshot gravado de [date], ou `null`. */
    fun find(date: LocalDate): DailyStatisticsSnapshot?

    /** Grava [snapshot] se a data ainda não tem linha (`ON CONFLICT DO NOTHING`); não altera a existente. */
    fun insertIfAbsent(snapshot: DailyStatisticsSnapshot)
}

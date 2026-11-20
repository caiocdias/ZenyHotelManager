package com.zeny.hotel.reporting.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.DailyStatisticsSnapshot
import com.zeny.hotel.reporting.ReportingApi
import com.zeny.hotel.reporting.domain.DailyStatisticsCalculator
import com.zeny.hotel.shared.StorableRange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * A API pública do `reporting` ([ReportingApi]): sem checar permissão, na transação de quem chama. O snapshot é
 * calculado só das views `report_*` e gravado uma vez por data; existindo, é devolvido sem recalcular.
 */
@Service
class DailyStatisticsService(
    private val queries: ReportingQueries,
    private val store: DailyStatisticsStore,
    private val hotel: HotelApi,
) : ReportingApi {
    @Transactional
    override fun snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot {
        store.find(date)?.let { return it }
        val zone = hotel.timezone()
        val input =
            queries.dailyStatisticsInput(
                date,
                StorableRange.startOfDay(date, zone),
                StorableRange.startOfDay(date.plusDays(1), zone),
            )
        store.insertIfAbsent(DailyStatisticsCalculator.calculate(date, input))
        // Releitura: numa corrida, quem perdeu devolve a linha de quem ganhou, não a sua.
        return checkNotNull(store.find(date)) { "snapshot de $date não gravado" }
    }
}

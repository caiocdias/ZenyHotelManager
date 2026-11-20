package com.zeny.hotel.reporting

import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.time.LocalDate

/**
 * O snapshot diário das estatísticas do hotel numa data operacional (M6 E23, D1). As contagens são **disjuntas** e
 * vêm das noites vendidas que hospedam (`sold` e não day use): [roomsSold] (plano `NORMAL`), [roomsComplimentary]
 * (`COURTESY`) e [roomsHouseUse] (`HOUSE_USE`). [roomsAvailable] é o total menos as UHs fora de ordem do inventário.
 * As receitas são dos lançamentos da data, sem repasse (a taxa de serviço) e sem impostos; [eventsRevenue] é zero até
 * o módulo de eventos. As derivadas: [occupancy] = ([roomsSold] + [roomsComplimentary]) ÷ [roomsAvailable] (2 casas),
 * [adr] = [roomRevenue] ÷ [roomsSold] e [revpar] = [roomRevenue] ÷ [roomsAvailable] (2 casas, `HALF_EVEN`); todas
 * `null` com denominador zero, e a ocupação também acima de 999,9999%.
 */
data class DailyStatisticsSnapshot(
    val businessDate: LocalDate,
    val roomsSold: Long,
    val roomsComplimentary: Long,
    val roomsHouseUse: Long,
    val roomsAvailable: Long,
    val roomsOutOfOrder: Long,
    val arrivals: Long,
    val departures: Long,
    val noShows: Long,
    val cancellations: Long,
    val roomRevenue: Money,
    val foodBeverageRevenue: Money,
    val eventsRevenue: Money,
    val otherRevenue: Money,
    val occupancy: Percent?,
    val adr: Money?,
    val revpar: Money?,
)

/**
 * A API pública do `reporting` (M6 E23). As operações não conferem permissão: quem chama (o fechamento do dia) já se
 * autorizou, como nas demais APIs entre módulos.
 */
interface ReportingApi {
    /**
     * O snapshot de [date]: grava `reporting.daily_statistics` na primeira chamada e devolve sempre o gravado, sem
     * recalcular. Roda na transação de quem chama (`REQUIRED`); chamadas simultâneas da mesma data gravam uma linha só
     * (`ON CONFLICT DO NOTHING` e releitura). Sem guarda de data no código: **só para a data que fecha ou anteriores**,
     * porque o snapshot de uma data cujo movimento ainda não terminou ficaria gravado incompleto; quem garante isso é
     * o fechamento do dia, que chama com a data que fecha.
     */
    fun snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot
}

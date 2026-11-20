package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.DailyStatisticsSnapshot

/**
 * O modelo do PDF da data (`reporting.day-report`, M6 E23, D3): o snapshot, o painel do dia e as pendências do
 * fechamento, só com `Map`, `List` e folhas que o `printing` aceita. **Sem nome de hóspede**: as views não o publicam;
 * o PDF traz códigos de reserva, números de conta e de turno, saldos e ids de operador (por isso o anexo é sensível).
 */
object DayReportModel {
    fun of(
        snapshot: DailyStatisticsSnapshot,
        panel: DayPanel,
        pending: EndOfDayPending,
    ): Map<String, Any?> =
        mapOf(
            "date" to snapshot.businessDate,
            "snapshot" to snapshot(snapshot),
            "panel" to panel(panel),
            "pending" to pending(pending),
        )

    private fun snapshot(snapshot: DailyStatisticsSnapshot): Map<String, Any?> =
        mapOf(
            "roomsSold" to snapshot.roomsSold,
            "roomsComplimentary" to snapshot.roomsComplimentary,
            "roomsHouseUse" to snapshot.roomsHouseUse,
            "roomsAvailable" to snapshot.roomsAvailable,
            "roomsOutOfOrder" to snapshot.roomsOutOfOrder,
            "arrivals" to snapshot.arrivals,
            "departures" to snapshot.departures,
            "noShows" to snapshot.noShows,
            "cancellations" to snapshot.cancellations,
            "roomRevenue" to snapshot.roomRevenue,
            "foodBeverageRevenue" to snapshot.foodBeverageRevenue,
            "eventsRevenue" to snapshot.eventsRevenue,
            "otherRevenue" to snapshot.otherRevenue,
            "occupancy" to snapshot.occupancy,
            "adr" to snapshot.adr,
            "revpar" to snapshot.revpar,
        )

    private fun panel(panel: DayPanel): Map<String, Any?> =
        mapOf(
            "businessDate" to panel.businessDate,
            "rooms" to
                mapOf(
                    "active" to panel.rooms.active,
                    "occupied" to panel.rooms.occupied,
                    "vacant" to panel.rooms.vacant,
                    "dirty" to panel.rooms.dirty,
                    "clean" to panel.rooms.clean,
                    "inspected" to panel.rooms.inspected,
                    "inspectionPending" to panel.rooms.inspectionPending,
                    "outOfOrder" to panel.rooms.outOfOrder,
                    "outOfService" to panel.rooms.outOfService,
                ),
        )

    private fun pending(pending: EndOfDayPending): Map<String, Any?> =
        mapOf(
            "folios" to
                pending.folios.map {
                    mapOf(
                        "number" to it.folioNumber,
                        "reservationCode" to it.reservationCode,
                        "departure" to it.departure,
                        "balance" to it.balance,
                    )
                },
            "cashierShifts" to
                pending.cashierShifts.map {
                    mapOf(
                        "number" to it.shiftNumber,
                        "cashPoint" to it.cashPointName,
                        "operatorId" to it.operatorId.toString(),
                        "openedOn" to it.openedOn,
                    )
                },
            "fnrhSubmissions" to
                pending.fnrhSubmissions.map {
                    mapOf(
                        "operation" to it.operation,
                        "status" to it.status,
                        "reservationCode" to it.reservationCode,
                        "attempts" to it.attempts,
                        "lastFailure" to it.lastFailure,
                    )
                },
        )
}

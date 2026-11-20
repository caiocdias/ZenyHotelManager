package com.zeny.hotel.reporting.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.reporting.domain.Occupancy
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Percent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** O padrão e o limite de dias do painel. */
const val DEFAULT_PANEL_DAYS = 7
const val MAX_PANEL_DAYS = 30

/** Uma data do painel: o movimento e a ocupação (`null` se a data não tem inventário ou capacidade). */
data class DayPanelDay(
    val date: LocalDate,
    val arrivals: Long,
    val departures: Long,
    val hosted: Long,
    val occupancy: Percent?,
)

/** As UHs ativas de hoje por ocupação, status de governança e inspeção pendente. */
data class DayPanelRooms(
    val active: Long,
    val occupied: Long,
    val vacant: Long,
    val dirty: Long,
    val clean: Long,
    val inspected: Long,
    val inspectionPending: Long,
    val outOfOrder: Long,
    val outOfService: Long,
)

/** O painel do dia: a data operacional, uma linha por data e a situação das UHs de hoje. */
data class DayPanel(
    val businessDate: LocalDate,
    val days: List<DayPanelDay>,
    val rooms: DayPanelRooms,
)

/**
 * O painel do dia (RF-REL, M6 E09, D3): para a data operacional e as seguintes, as chegadas, as saídas, os hospedados
 * e a ocupação; para hoje, as UHs por status e as fora de ordem. Só contagens, sem dado de hóspede (RN-13). Data sem
 * movimento ou sem inventário vem com zeros e ocupação `null`, nunca erro. Exige `reporting.dashboard.read`.
 */
@Service
class DayPanelService(
    private val queries: ReportingQueries,
    private val authorization: Authorization,
    private val hotel: HotelApi,
) {
    @Transactional(readOnly = true)
    fun panel(days: Int): DayPanel {
        authorization.require(ReportingPermissions.DASHBOARD_READ)
        return loadWithoutAuthorization(days)
    }

    /**
     * O mesmo painel **sem conferir permissão**, para o arquivo por data (M6 E23), que quem chama (o fechamento) já se
     * autorizou: só o arquivo do fechamento o chama. Roda na transação de quem chama.
     */
    @Transactional(readOnly = true)
    fun loadWithoutAuthorization(days: Int): DayPanel {
        require(days in 1..MAX_PANEL_DAYS) { "days fora de 1..$MAX_PANEL_DAYS: $days" }
        val today = hotel.currentBusinessDate()
        val last = today.plusDays(days - 1L)
        val movements = queries.dayMovements(today, last).associateBy { it.date }
        val capacity = queries.dayCapacity(today, last).associate { it.date to it.rooms }
        val counts = queries.roomCounts()
        val blocked = queries.outOfOrderOn(today)
        return DayPanel(
            businessDate = today,
            days =
                (0 until days).map { offset ->
                    val date = today.plusDays(offset.toLong())
                    val row = movements[date]
                    DayPanelDay(
                        date = date,
                        arrivals = row?.arrivals ?: 0,
                        departures = row?.departures ?: 0,
                        hosted = row?.hosted ?: 0,
                        occupancy = Occupancy.of(row?.occupiedNights ?: 0, capacity[date] ?: 0),
                    )
                },
            rooms =
                DayPanelRooms(
                    active = counts.active,
                    occupied = counts.occupied,
                    vacant = counts.vacant,
                    dirty = counts.dirty,
                    clean = counts.clean,
                    inspected = counts.inspected,
                    inspectionPending = counts.inspectionPending,
                    outOfOrder = blocked.outOfOrder,
                    outOfService = blocked.outOfService,
                ),
        )
    }
}

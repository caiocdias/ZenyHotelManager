package com.zeny.hotel.reporting.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reporting.ReportingPermissions
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.StorableRange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private const val SECONDS_PER_HOUR = 3600L
private const val HOURS_SCALE = 1

/** Os status de uma ordem de serviço (contrato de `maintenance.report_work_orders`); a lista pode crescer. */
enum class MaintenanceStatus { OPEN, IN_PROGRESS, ON_HOLD, DONE, CANCELLED }

/** As categorias de uma ordem de serviço (contrato de `maintenance.report_work_orders`); a lista pode crescer. */
enum class MaintenanceCategory { ELECTRICAL, PLUMBING, AIR_CONDITIONING, FURNITURE, OTHER }

/** As ordens abertas no período num status. */
data class StatusCountRow(
    val status: MaintenanceStatus,
    val orders: Long,
)

/** As ordens abertas no período numa categoria. */
data class CategoryCountRow(
    val category: MaintenanceCategory,
    val orders: Long,
)

/** As ordens concluídas: o tempo médio de atendimento (horas, 1 casa; `null` sem ordem) e os custos somados. */
data class CompletedOrdersReport(
    val orders: Long,
    val averageHours: BigDecimal?,
    val laborCost: Money,
    val thirdPartyCost: Money,
    val partsCost: Money,
    val totalCost: Money,
)

/** O relatório de manutenção do período. */
data class MaintenanceReport(
    val from: LocalDate,
    val to: LocalDate,
    val byStatus: List<StatusCountRow>,
    val byCategory: List<CategoryCountRow>,
    val completed: CompletedOrdersReport,
)

/**
 * O relatório de manutenção (RF-REL, M6 E09, D5), pela abertura da ordem (`opened_at`, data local no fuso do hotel): as
 * ordens por status e por categoria, o tempo médio de atendimento (da abertura à conclusão, em horas com 1 casa) e a
 * soma dos custos das concluídas (`DONE`; as canceladas não entram). Sem texto livre (RN-13). Período sem ordem vem
 * com listas vazias, custos zero e tempo médio `null`. Exige `reporting.maintenance.read`.
 */
@Service
class MaintenanceReportService(
    private val queries: ReportingQueries,
    private val authorization: Authorization,
    private val hotel: HotelApi,
) {
    @Transactional(readOnly = true)
    fun report(
        from: LocalDate,
        to: LocalDate,
    ): MaintenanceReport {
        authorization.require(ReportingPermissions.MAINTENANCE_READ)
        requireValidReportPeriod(from, to)
        val zone = hotel.timezone()
        val start = StorableRange.startOfDay(from, zone)
        val end = StorableRange.startOfDay(to.plusDays(1), zone)
        val completed = queries.completedWorkOrders(start, end)
        val labor = Money.of(completed.laborCost)
        val thirdParty = Money.of(completed.thirdPartyCost)
        val parts = Money.of(completed.partsCost)
        return MaintenanceReport(
            from = from,
            to = to,
            byStatus =
                queries
                    .workOrdersByStatus(
                        start,
                        end,
                    ).map { StatusCountRow(MaintenanceStatus.valueOf(it.key), it.orders) },
            byCategory =
                queries
                    .workOrdersByCategory(start, end)
                    .map { CategoryCountRow(MaintenanceCategory.valueOf(it.key), it.orders) },
            completed =
                CompletedOrdersReport(
                    orders = completed.orders,
                    averageHours =
                        completed.orders.takeIf { it > 0 }?.let {
                            completed.totalSeconds.divide(
                                BigDecimal(it * SECONDS_PER_HOUR),
                                HOURS_SCALE,
                                RoundingMode.HALF_UP,
                            )
                        },
                    laborCost = labor,
                    thirdPartyCost = thirdParty,
                    partsCost = parts,
                    totalCost = labor + thirdParty + parts,
                ),
        )
    }
}

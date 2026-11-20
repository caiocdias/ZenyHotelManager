package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ArchivedReport
import com.zeny.hotel.reporting.DailyStatisticsSnapshot
import com.zeny.hotel.reporting.EndOfDayPendingItem
import com.zeny.hotel.reporting.EndOfDayPendingKind
import com.zeny.hotel.reporting.ReportingApi
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

/**
 * A fachada da [ReportingApi] (M6 E23): um único bean para a API pública, que delega ao snapshot (na transação de quem
 * chama, `REQUIRED`), ao arquivo por data (uma transação própria por tipo) e às pendências da RF-FEC-04. Sem checar
 * permissão. O snapshot abre a transação por `TransactionTemplate` e não por `@Transactional`, porque é uma escrita de
 * sistema, sem trilha própria (a regra de arquitetura exige `@Audited` em toda escrita anotada).
 */
@Service
class ReportingApiService(
    private val snapshots: DailyStatisticsService,
    private val archive: DayReportsArchive,
    private val pending: EndOfDayPendingService,
    transactionManager: PlatformTransactionManager,
) : ReportingApi {
    private val required = TransactionTemplate(transactionManager)

    override fun snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot =
        checkNotNull(required.execute { snapshots.snapshotDailyStatistics(date) })

    override fun archiveDayReports(date: LocalDate): List<ArchivedReport> = archive.archiveAll(date)

    override fun endOfDayPendingItems(): List<EndOfDayPendingItem> {
        val current = pending.loadWithoutAuthorization()
        return current.folios.map {
            EndOfDayPendingItem(
                EndOfDayPendingKind.FOLIO_OPEN_BALANCE,
                it.folioId,
                "conta ${it.folioNumber} da reserva ${it.reservationCode}",
            )
        } +
            current.cashierShifts.map {
                EndOfDayPendingItem(
                    EndOfDayPendingKind.CASHIER_SHIFT_OPEN,
                    it.shiftId,
                    "caixa ${it.shiftNumber} aberto em ${it.openedOn}",
                )
            } +
            current.fnrhSubmissions.map {
                EndOfDayPendingItem(
                    EndOfDayPendingKind.FNRH_SUBMISSION_PENDING,
                    it.reservationId,
                    "FNRH ${it.operation} ${it.status}",
                )
            }
    }
}

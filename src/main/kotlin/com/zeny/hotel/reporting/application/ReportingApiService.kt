package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ArchivedReport
import com.zeny.hotel.reporting.DailyStatisticsSnapshot
import com.zeny.hotel.reporting.ReportingApi
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

/**
 * A fachada da [ReportingApi] (M6 E23): um único bean para a API pública, que delega ao snapshot (na transação de
 * quem chama, `REQUIRED`) e ao arquivo por data (uma transação própria por tipo). Sem checar permissão. O snapshot
 * abre a transação por `TransactionTemplate` e não por `@Transactional`, porque é uma escrita de sistema, sem trilha
 * própria (a regra de arquitetura exige `@Audited` em toda escrita anotada).
 */
@Service
class ReportingApiService(
    private val snapshots: DailyStatisticsService,
    private val archive: DayReportsArchive,
    transactionManager: PlatformTransactionManager,
) : ReportingApi {
    private val required = TransactionTemplate(transactionManager)

    override fun snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot =
        checkNotNull(required.execute { snapshots.snapshotDailyStatistics(date) })

    override fun archiveDayReports(date: LocalDate): List<ArchivedReport> = archive.archiveAll(date)
}

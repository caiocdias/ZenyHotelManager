package com.zeny.hotel.reporting.application

import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.printing.DocumentFormat
import com.zeny.hotel.printing.DocumentRequest
import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.ModuleDocumentTemplates
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.reporting.ArchivedReport
import com.zeny.hotel.reporting.ArchivedReportKind
import com.zeny.hotel.reporting.ReportingFilePurposes
import com.zeny.hotel.shared.UuidV7
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Os templates de documento do `reporting` (M6 E23, D3): o PDF da data. */
object ReportingDocumentTemplates : ModuleDocumentTemplates {
    override val module = "reporting"

    const val DAY_REPORT = "reporting.day-report"

    override val templates =
        listOf(
            DocumentTemplate(
                code = DAY_REPORT,
                title = "Fechamento do dia",
                xhtmlTemplate = "templates/reporting/day-report.xhtml",
                textTemplate = "templates/reporting/day-report.txt",
            ),
        )
}

/** Registra os templates do `reporting` no catálogo do `printing`. */
@Configuration(proxyBeanMethods = false)
class ReportingDocumentsConfiguration {
    @Bean
    fun reportingDocumentTemplates(): ModuleDocumentTemplates = ReportingDocumentTemplates
}

/**
 * O arquivo por data ([com.zeny.hotel.reporting.ReportingApi.archiveDayReports], M6 E23, D4): por tipo de documento,
 * numa transação própria (`REQUIRES_NEW`), trava por (data, tipo) antes de conferir o índice, obtém o snapshot na
 * mesma transação, renderiza o PDF pelo `printing`, grava o anexo sensível pelo `files` com o id da linha como dono e
 * insere a linha. Qualquer falha desfaz o tipo inteiro, inclusive o anexo e o objeto, e propaga. Não confere
 * permissão: o fechamento já se autorizou.
 */
@Service
@Suppress("LongParameterList") // o snapshot, o painel, as pendências, o índice, o PDF, o anexo e a transação
class DayReportsArchive(
    private val snapshots: DailyStatisticsService,
    private val panel: DayPanelService,
    private val pending: EndOfDayPendingService,
    private val archive: ArchivedReportStore,
    private val printing: PrintingApi,
    private val files: FilesApi,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior =
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun archiveAll(date: LocalDate): List<ArchivedReport> =
        ArchivedReportKind.entries.map { kind -> checkNotNull(ownTransaction.execute { archiveOne(date, kind) }) }

    private fun archiveOne(
        date: LocalDate,
        kind: ArchivedReportKind,
    ): ArchivedReport {
        archive.lock(date, kind)
        archive.find(date, kind)?.let { return it }
        return when (kind) {
            ArchivedReportKind.DAY_REPORT -> archiveDayReport(date)
        }
    }

    private fun archiveDayReport(date: LocalDate): ArchivedReport {
        val model = DayReportModel.of(snapshots.snapshotDailyStatistics(date), panel.load(1), pending.load())
        val pdf = printing.render(DocumentRequest(ReportingDocumentTemplates.DAY_REPORT, model), DocumentFormat.PDF)
        val id = UuidV7.generate()
        val file = files.store(ReportingFilePurposes.DAY_REPORT, id.toString(), "fechamento-$date.pdf", pdf.content)
        val report =
            ArchivedReport(
                id = id,
                businessDate = date,
                kind = ArchivedReportKind.DAY_REPORT,
                fileId = file.id,
                createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        archive.insert(report)
        return report
    }
}

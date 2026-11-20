package com.zeny.hotel.reporting.application

import com.zeny.hotel.hotel.HotelApi
import org.springframework.stereotype.Service
import java.time.LocalDate

/** O formato de um arquivo exportado e o tipo de mídia dele. */
enum class ExportFormat(
    val extension: String,
    val contentType: String,
) {
    CSV("csv", "text/csv;charset=UTF-8"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/** Um relatório exportado: o nome do arquivo (ASCII), o tipo de mídia e os bytes. */
class ExportedReport(
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
)

/**
 * A exportação dos relatórios (RF-REL, M6 E09, D6): chama o mesmo caso de uso que a resposta JSON (que confere a
 * permissão e o período), converte o resultado no modelo tabular e o serializa. Nada é gravado nem guardado.
 */
@Service
class ReportExportService(
    private val housekeeping: HousekeepingReportService,
    private val maintenance: MaintenanceReportService,
    private val dayPanel: DayPanelService,
    private val pending: EndOfDayPendingService,
    private val hotel: HotelApi,
) {
    fun housekeeping(
        from: LocalDate,
        to: LocalDate,
        format: ExportFormat,
    ): ExportedReport = export(format, "governanca-${period(from, to)}", housekeeping.report(from, to)::toDocument)

    fun maintenance(
        from: LocalDate,
        to: LocalDate,
        format: ExportFormat,
    ): ExportedReport = export(format, "manutencao-${period(from, to)}", maintenance.report(from, to)::toDocument)

    fun dayPanel(
        days: Int,
        format: ExportFormat,
    ): ExportedReport {
        val panel = dayPanel.panel(days)
        return export(format, "painel-${panel.businessDate}", panel::toDocument)
    }

    fun pending(format: ExportFormat): ExportedReport {
        val result = pending.pending()
        return export(format, "pendencias-${hotel.currentBusinessDate()}", result::toDocument)
    }

    private fun period(
        from: LocalDate,
        to: LocalDate,
    ) = if (from == to) "$from" else "${from}_$to"

    private fun export(
        format: ExportFormat,
        baseName: String,
        document: (String) -> ReportDocument,
    ): ExportedReport {
        val built = document(baseName)
        val bytes =
            when (format) {
                ExportFormat.CSV -> ReportExport.csv(built)
                ExportFormat.XLSX -> ReportExport.xlsx(built)
            }
        return ExportedReport("$baseName.${format.extension}", format.contentType, bytes)
    }
}

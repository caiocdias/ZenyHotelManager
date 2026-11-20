package com.zeny.hotel.reporting.web

import com.zeny.hotel.reporting.application.DEFAULT_PANEL_DAYS
import com.zeny.hotel.reporting.application.ExportFormat
import com.zeny.hotel.reporting.application.ExportedReport
import com.zeny.hotel.reporting.application.MAX_PANEL_DAYS
import com.zeny.hotel.reporting.application.ReportExportService
import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

private const val CSV = "text/csv"
private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

private const val EXPORT_RULE =
    "Os mesmos parâmetros, a mesma permissão e o mesmo resultado da operação JSON. Qualquer erro sai em " +
        "`application/problem+json`, mesmo com outro `Accept`. O arquivo não é guardado em cache."
private const val CSV_RULE =
    "CSV em UTF-8 com BOM, separado por `;`, decimal com vírgula e datas ISO; um relatório de várias tabelas escreve " +
        "cada uma sob uma linha com o título dela, separadas por uma linha vazia."
private const val XLSX_RULE = "XLSX com uma aba por tabela e os números em células numéricas."

/**
 * A exportação dos relatórios em arquivo (M6 E09, D6; ARCHITECTURE §7 "Documentos impressos"): uma rota por formato
 * (`…/csv` e `…/xlsx`), sem `?format=` nem negociação por `Accept`, com `Cache-Control: no-store`. Cada rota chama o
 * mesmo caso de uso da resposta JSON.
 */
@ApiV1Controller
@RequestMapping("/reports")
@Tag(name = "reporting", description = "Relatórios operacionais, painel do dia e pendências do fechamento.")
@Suppress("TooManyFunctions") // duas rotas (CSV e XLSX) para cada um dos quatro relatórios
class ReportExportsController(
    private val exports: ReportExportService,
) {
    @GetMapping("/housekeeping/csv")
    @Operation(
        operationId = "getHousekeepingReportCsv",
        summary = "Relatório de governança em CSV",
        description = "O relatório de governança (`GET /reports/housekeeping`) como CSV. $CSV_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "O arquivo CSV.",
                content = [Content(mediaType = CSV, schema = Schema(type = "string"))],
            ),
        ],
    )
    fun housekeepingCsv(
        @Parameter(description = "Primeira data (YYYY-MM-DD).") @RequestParam from: LocalDate,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive.") @RequestParam to: LocalDate,
    ) = file(exports.housekeeping(from, to, ExportFormat.CSV))

    @GetMapping("/housekeeping/xlsx")
    @Operation(
        operationId = "getHousekeepingReportXlsx",
        summary = "Relatório de governança em XLSX",
        description = "O relatório de governança (`GET /reports/housekeeping`) como planilha. $XLSX_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A planilha XLSX.",
                content = [Content(mediaType = XLSX, schema = Schema(type = "string", format = "binary"))],
            ),
        ],
    )
    fun housekeepingXlsx(
        @Parameter(description = "Primeira data (YYYY-MM-DD).") @RequestParam from: LocalDate,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive.") @RequestParam to: LocalDate,
    ) = file(exports.housekeeping(from, to, ExportFormat.XLSX))

    @GetMapping("/maintenance/csv")
    @Operation(
        operationId = "getMaintenanceReportCsv",
        summary = "Relatório de manutenção em CSV",
        description = "O relatório de manutenção (`GET /reports/maintenance`) como CSV. $CSV_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "O arquivo CSV.",
                content = [Content(mediaType = CSV, schema = Schema(type = "string"))],
            ),
        ],
    )
    fun maintenanceCsv(
        @Parameter(description = "Primeira data (YYYY-MM-DD).") @RequestParam from: LocalDate,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive.") @RequestParam to: LocalDate,
    ) = file(exports.maintenance(from, to, ExportFormat.CSV))

    @GetMapping("/maintenance/xlsx")
    @Operation(
        operationId = "getMaintenanceReportXlsx",
        summary = "Relatório de manutenção em XLSX",
        description = "O relatório de manutenção (`GET /reports/maintenance`) como planilha. $XLSX_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A planilha XLSX.",
                content = [Content(mediaType = XLSX, schema = Schema(type = "string", format = "binary"))],
            ),
        ],
    )
    fun maintenanceXlsx(
        @Parameter(description = "Primeira data (YYYY-MM-DD).") @RequestParam from: LocalDate,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive.") @RequestParam to: LocalDate,
    ) = file(exports.maintenance(from, to, ExportFormat.XLSX))

    @GetMapping("/day-panel/csv")
    @Operation(
        operationId = "getDayPanelCsv",
        summary = "Painel do dia em CSV",
        description = "O painel do dia (`GET /reports/day-panel`) como CSV. $CSV_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "O arquivo CSV.",
                content = [Content(mediaType = CSV, schema = Schema(type = "string"))],
            ),
        ],
    )
    fun dayPanelCsv(
        @Parameter(description = "Quantos dias, a partir da data operacional (de 1 a $MAX_PANEL_DAYS).")
        @RequestParam(required = false, defaultValue = "$DEFAULT_PANEL_DAYS")
        @Min(1)
        @Max(MAX_PANEL_DAYS.toLong()) days: Int,
    ) = file(exports.dayPanel(days, ExportFormat.CSV))

    @GetMapping("/day-panel/xlsx")
    @Operation(
        operationId = "getDayPanelXlsx",
        summary = "Painel do dia em XLSX",
        description = "O painel do dia (`GET /reports/day-panel`) como planilha. $XLSX_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A planilha XLSX.",
                content = [Content(mediaType = XLSX, schema = Schema(type = "string", format = "binary"))],
            ),
        ],
    )
    fun dayPanelXlsx(
        @Parameter(description = "Quantos dias, a partir da data operacional (de 1 a $MAX_PANEL_DAYS).")
        @RequestParam(required = false, defaultValue = "$DEFAULT_PANEL_DAYS")
        @Min(1)
        @Max(MAX_PANEL_DAYS.toLong()) days: Int,
    ) = file(exports.dayPanel(days, ExportFormat.XLSX))

    @GetMapping("/end-of-day-pending/csv")
    @Operation(
        operationId = "getEndOfDayPendingCsv",
        summary = "Pendências do fechamento do dia em CSV",
        description = "As pendências (`GET /reports/end-of-day-pending`) como CSV. $CSV_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "O arquivo CSV.",
                content = [Content(mediaType = CSV, schema = Schema(type = "string"))],
            ),
        ],
    )
    fun endOfDayPendingCsv() = file(exports.pending(ExportFormat.CSV))

    @GetMapping("/end-of-day-pending/xlsx")
    @Operation(
        operationId = "getEndOfDayPendingXlsx",
        summary = "Pendências do fechamento do dia em XLSX",
        description = "As pendências (`GET /reports/end-of-day-pending`) como planilha. $XLSX_RULE $EXPORT_RULE",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "A planilha XLSX.",
                content = [Content(mediaType = XLSX, schema = Schema(type = "string", format = "binary"))],
            ),
        ],
    )
    fun endOfDayPendingXlsx() = file(exports.pending(ExportFormat.XLSX))

    private fun file(report: ExportedReport): ResponseEntity<ByteArray> =
        ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.parseMediaType(report.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(report.fileName)
                    .build()
                    .toString(),
            ).cacheControl(CacheControl.noStore())
            .body(report.content)
}

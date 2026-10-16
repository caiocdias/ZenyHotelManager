package com.zeny.hotel.checklists.web

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.application.ChecklistReportFilter
import com.zeny.hotel.checklists.application.ChecklistReportsService
import com.zeny.hotel.checklists.application.FailedItemRow
import com.zeny.hotel.checklists.application.LocationHistoryRow
import com.zeny.hotel.checklists.application.TemplateComplianceRow
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val PERIOD_RULE =
    "Período em datas no fuso do hotel, inclusive nas duas pontas (`from` e `to`, opcionais); fim antes do início: " +
        "400 `CHECKLIST_REPORT_PERIOD_INVALID`."
private const val COMPLIANCE_RULE =
    "Só os itens de marcação têm resultado: OK conta como conforme, NÃO OK como não conforme, e \"não se aplica\" " +
        "fica fora do denominador (`evaluatedCount` = OK + NÃO OK)."
private const val PERMISSION_RULE = "Exige `checklists.reports.read`."
private const val MAX_SUBJECT_TYPE = 60
private const val DEFAULT_RANKING = "20"
private const val MAX_RANKING = 200L

/**
 * Relatórios de checklists (RF-CHK-09, M2 E08): conformidade por modelo, itens mais reprovados e histórico por local.
 * Leem só as views `checklists.report_*` (ARCHITECTURE §11). Exigem `checklists.reports.read`.
 */
@ApiV1Controller
@RequestMapping("/checklist-reports")
@Tag(name = "checklists", description = "Modelos de checklist, rascunhos e versões publicadas.")
class ChecklistReportsController(
    private val reports: ChecklistReportsService,
) {
    @GetMapping("/compliance")
    @Operation(
        operationId = "getChecklistComplianceReport",
        summary = "Conformidade por modelo",
        description =
            "Por modelo, as execuções concluídas no período (pela conclusão) e as respostas de marcação por " +
                "resultado, com a taxa de conformidade (OK sobre os avaliados; nula sem nada avaliado). Em ordem de " +
                "nome do modelo. $COMPLIANCE_RULE $PERIOD_RULE $PERMISSION_RULE",
    )
    @Suppress("LongParameterList") // os filtros do relatório
    fun compliance(
        @Parameter(description = "Primeira data (YYYY-MM-DD), no fuso do hotel.")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive, no fuso do hotel.")
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) templateId: UUID?,
        @Parameter(description = "Só os sujeitos deste tipo (o local: `rooms.room`, `rooms.area`…).")
        @RequestParam(required = false)
        @Size(max = MAX_SUBJECT_TYPE) subjectType: String?,
        @Parameter(description = "Só este sujeito (o local).")
        @RequestParam(required = false) subjectId: UUID?,
    ): ChecklistComplianceReportResponse {
        val filter = ChecklistReportFilter(from, to, templateId?.let(::ChecklistTemplateId), subjectType, subjectId)
        return ChecklistComplianceReportResponse(reports.compliance(filter).map(TemplateComplianceResponse::of))
    }

    @GetMapping("/failed-items")
    @Operation(
        operationId = "getChecklistFailedItemsReport",
        summary = "Itens mais reprovados",
        description =
            "Os itens de marcação com ao menos uma reprovação (NÃO OK) em execuções concluídas no período (pela " +
                "conclusão), do mais reprovado ao menos (desempate pela taxa de reprovação), até `limit`. O rótulo é " +
                "o da versão mais nova entre as execuções do filtro (a chave do item é estável entre as versões). " +
                "$COMPLIANCE_RULE $PERIOD_RULE $PERMISSION_RULE",
    )
    @Suppress("LongParameterList") // os filtros do relatório
    fun failedItems(
        @Parameter(description = "Primeira data (YYYY-MM-DD), no fuso do hotel.")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive, no fuso do hotel.")
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) templateId: UUID?,
        @Parameter(description = "Só os sujeitos deste tipo (o local: `rooms.room`, `rooms.area`…).")
        @RequestParam(required = false)
        @Size(max = MAX_SUBJECT_TYPE) subjectType: String?,
        @Parameter(description = "Só este sujeito (o local).")
        @RequestParam(required = false) subjectId: UUID?,
        @Parameter(description = "Quantos itens, no máximo (de 1 a 200).")
        @RequestParam(required = false, defaultValue = DEFAULT_RANKING)
        @Min(1)
        @Max(MAX_RANKING) limit: Int,
    ): ChecklistFailedItemsReportResponse {
        val filter = ChecklistReportFilter(from, to, templateId?.let(::ChecklistTemplateId), subjectType, subjectId)
        return ChecklistFailedItemsReportResponse(reports.failedItems(filter, limit).map(FailedItemResponse::of))
    }

    @GetMapping("/location-history")
    @Operation(
        operationId = "getChecklistLocationHistory",
        summary = "Histórico por local",
        description =
            "As execuções de um local (sujeito), em qualquer situação — inclusive a pendente de rotina que ninguém " +
                "fez —, da mais recente para a mais antiga pela referência (`referenceAt`: a conclusão, senão o " +
                "início, senão o prazo da rotina), com as respostas de marcação por resultado (parciais enquanto em " +
                "andamento). O período filtra pela referência. $COMPLIANCE_RULE $PERIOD_RULE $PERMISSION_RULE",
    )
    @Suppress("LongParameterList") // os filtros do relatório
    fun locationHistory(
        @Parameter(description = "O tipo do sujeito (o local: `rooms.room`, `rooms.area`…).")
        @RequestParam
        @Size(min = 1, max = MAX_SUBJECT_TYPE) subjectType: String,
        @Parameter(description = "O sujeito (o local).")
        @RequestParam subjectId: UUID,
        @Parameter(description = "Primeira data (YYYY-MM-DD), no fuso do hotel.")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "Última data (YYYY-MM-DD), inclusive, no fuso do hotel.")
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) templateId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<LocationHistoryEntryResponse> {
        val filter = ChecklistReportFilter(from, to, templateId?.let(::ChecklistTemplateId), subjectType, subjectId)
        return reports.locationHistory(filter, page).map(LocationHistoryEntryResponse::of)
    }
}

@Schema(description = "Conformidade por modelo (RF-CHK-09).")
data class ChecklistComplianceReportResponse(
    val items: List<TemplateComplianceResponse>,
)

@Schema(description = "A conformidade de um modelo no filtro.")
data class TemplateComplianceResponse(
    val templateId: UUID,
    @field:Schema(description = "O nome atual do modelo.")
    val templateName: String,
    val context: ChecklistContext,
    @field:Schema(description = "Execuções concluídas no filtro.")
    val executions: Long,
    val okCount: Long,
    val notOkCount: Long,
    val notApplicableCount: Long,
    @field:Schema(description = "As respostas que contam na taxa: OK + NÃO OK (\"não se aplica\" fica de fora).")
    val evaluatedCount: Long,
    @field:Schema(
        description = "OK sobre os avaliados, em pontos percentuais com 4 casas; nula sem nada avaliado.",
        nullable = true,
    )
    val complianceRate: Percent?,
) {
    companion object {
        fun of(row: TemplateComplianceRow) =
            TemplateComplianceResponse(
                row.templateId.value,
                row.templateName,
                row.context,
                row.executions,
                row.outcomes.ok,
                row.outcomes.notOk,
                row.outcomes.notApplicable,
                row.outcomes.evaluated,
                row.outcomes.complianceRate,
            )
    }
}

@Schema(description = "Itens mais reprovados (RF-CHK-09).")
data class ChecklistFailedItemsReportResponse(
    val items: List<FailedItemResponse>,
)

@Schema(description = "Um item de marcação com reprovações no filtro.")
data class FailedItemResponse(
    val templateId: UUID,
    @field:Schema(description = "O nome atual do modelo.")
    val templateName: String,
    val itemKey: String,
    @field:Schema(description = "O rótulo do item na versão mais nova entre as execuções do filtro.")
    val itemLabel: String,
    val okCount: Long,
    val notOkCount: Long,
    val notApplicableCount: Long,
    @field:Schema(description = "As respostas que contam na taxa: OK + NÃO OK (\"não se aplica\" fica de fora).")
    val evaluatedCount: Long,
    @field:Schema(description = "NÃO OK sobre os avaliados, em pontos percentuais com 4 casas.")
    val failureRate: Percent,
) {
    companion object {
        fun of(row: FailedItemRow) =
            FailedItemResponse(
                row.templateId.value,
                row.templateName,
                row.itemKey,
                row.itemLabel,
                row.outcomes.ok,
                row.outcomes.notOk,
                row.outcomes.notApplicable,
                row.outcomes.evaluated,
                checkNotNull(row.outcomes.failureRate) { "Item reprovado sem resposta avaliada: ${row.itemKey}" },
            )
    }
}

@Schema(description = "Uma execução no histórico de um local.")
data class LocationHistoryEntryResponse(
    val executionId: UUID,
    val templateId: UUID,
    @field:Schema(description = "O nome atual do modelo.")
    val templateName: String,
    val context: ChecklistContext,
    @field:Schema(description = "O número da versão usada; nulo na pendente.", nullable = true)
    val versionNumber: Int?,
    val status: ChecklistExecutionStatus,
    @field:Schema(description = "A rotina que a gerou, se houver.", nullable = true)
    val routineId: UUID?,
    @field:Schema(description = "A data operacional da rotina, se houver.", nullable = true)
    val businessDate: LocalDate?,
    @field:Schema(description = "O prazo da rotina, se houver.", nullable = true)
    val dueAt: Instant?,
    @field:Schema(description = "A execução original, se esta foi registrada à parte (RN-22).", nullable = true)
    val duplicateOf: UUID?,
    @field:Schema(description = "O usuário executor; nulo na pendente.", nullable = true)
    val executorId: UUID?,
    @field:Schema(nullable = true)
    val startedAt: Instant?,
    @field:Schema(nullable = true)
    val completedAt: Instant?,
    @field:Schema(description = "O que posiciona a execução no histórico: a conclusão, senão o início, senão o prazo.")
    val referenceAt: Instant,
    val okCount: Long,
    val notOkCount: Long,
    val notApplicableCount: Long,
    @field:Schema(description = "As respostas que contam na taxa: OK + NÃO OK (\"não se aplica\" fica de fora).")
    val evaluatedCount: Long,
    @field:Schema(
        description = "OK sobre os avaliados, em pontos percentuais com 4 casas; nula sem nada avaliado.",
        nullable = true,
    )
    val complianceRate: Percent?,
) {
    companion object {
        fun of(row: LocationHistoryRow) =
            LocationHistoryEntryResponse(
                executionId = row.executionId.value,
                templateId = row.templateId.value,
                templateName = row.templateName,
                context = row.context,
                versionNumber = row.versionNumber,
                status = row.status,
                routineId = row.routineId?.value,
                businessDate = row.businessDate,
                dueAt = row.dueAt,
                duplicateOf = row.duplicateOf?.value,
                executorId = row.executorId?.value,
                startedAt = row.startedAt,
                completedAt = row.completedAt,
                referenceAt = row.referenceAt,
                okCount = row.outcomes.ok,
                notOkCount = row.outcomes.notOk,
                notApplicableCount = row.outcomes.notApplicable,
                evaluatedCount = row.outcomes.evaluated,
                complianceRate = row.outcomes.complianceRate,
            )
    }
}

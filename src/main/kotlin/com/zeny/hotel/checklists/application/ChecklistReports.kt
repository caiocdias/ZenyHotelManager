package com.zeny.hotel.checklists.application

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionId
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistRoutineId
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistsErrorCodes
import com.zeny.hotel.checklists.ChecklistsPermissions
import com.zeny.hotel.checklists.domain.CheckOutcomes
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Os filtros comuns dos relatórios: o período em datas (inclusive nas duas pontas, no fuso do hotel), o modelo e o
 * local (tipo e id opacos do sujeito, H3; cada um filtra sozinho).
 */
data class ChecklistReportFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val templateId: ChecklistTemplateId? = null,
    val subjectType: String? = null,
    val subjectId: UUID? = null,
)

/** Os filtros como o adaptador os recebe: o período já em instantes (`from` inclusive, `to` exclusive). */
data class ReportQuery(
    val from: Instant?,
    val to: Instant?,
    val templateId: ChecklistTemplateId?,
    val subjectType: String?,
    val subjectId: UUID?,
)

/**
 * As respostas de marcação contadas por resultado, com as taxas da regra única do domínio ([CheckOutcomes]: "não se
 * aplica" fora do denominador; sem nada avaliado, sem taxa). É o que a camada web lê, sem conhecer o domínio.
 */
data class CheckCounts(
    val ok: Long,
    val notOk: Long,
    val notApplicable: Long,
) {
    private val outcomes = CheckOutcomes(ok, notOk, notApplicable)

    /** OK + NÃO OK. */
    val evaluated: Long get() = outcomes.evaluated

    val complianceRate: Percent? get() = outcomes.complianceRate

    val failureRate: Percent? get() = outcomes.failureRate
}

/** A conformidade de um modelo no filtro: as execuções concluídas e as respostas de marcação por resultado. */
data class TemplateComplianceRow(
    val templateId: ChecklistTemplateId,
    val templateName: String,
    val context: ChecklistContext,
    val executions: Long,
    val outcomes: CheckCounts,
)

/** Um item de marcação de um modelo com as reprovações no filtro, com o rótulo da versão mais nova entre elas. */
data class FailedItemRow(
    val templateId: ChecklistTemplateId,
    val templateName: String,
    val itemKey: String,
    val itemLabel: String,
    val outcomes: CheckCounts,
)

/** Uma execução no histórico de um local (qualquer situação), posicionada por [referenceAt]. */
data class LocationHistoryRow(
    val executionId: ChecklistExecutionId,
    val templateId: ChecklistTemplateId,
    val templateName: String,
    val context: ChecklistContext,
    val versionNumber: Int?,
    val status: ChecklistExecutionStatus,
    val routineId: ChecklistRoutineId?,
    val businessDate: LocalDate?,
    val dueAt: Instant?,
    val duplicateOf: ChecklistExecutionId?,
    val executorId: UserId?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val referenceAt: Instant,
    val outcomes: CheckCounts,
)

/**
 * As consultas dos relatórios (RF-CHK-09). A implementação lê **só** as views `checklists.report_*` (ARCHITECTURE
 * §11), nunca as tabelas internas: o `ChecklistReportsTest` roda estas consultas com um papel que só lê as views.
 */
interface ChecklistReportsStore {
    /** A conformidade por modelo, em ordem de nome do modelo. */
    fun compliance(query: ReportQuery): List<TemplateComplianceRow>

    /** Os itens com ao menos uma reprovação, do mais reprovado ao menos (até [limit]). */
    fun failedItems(
        query: ReportQuery,
        limit: Int,
    ): List<FailedItemRow>

    /** O histórico de um local, do mais recente ao mais antigo, depois de [before] (`referenceAt`, id). */
    fun locationHistory(
        query: ReportQuery,
        before: Pair<Instant, UUID>?,
        limit: Int,
    ): List<LocationHistoryRow>
}

/**
 * Relatórios de checklists (RF-CHK-09): conformidade por modelo, itens mais reprovados e histórico por local, sobre
 * as views `checklists.report_*`. Exigem `checklists.reports.read` (só `ALL`): ler as execuções não basta. Só
 * leitura: nada é gravado nem auditado. O período é de datas no fuso do hotel ([HotelApi.timezone]): a conformidade e
 * os itens contam a conclusão; o histórico, a referência (conclusão, início ou prazo da rotina).
 */
@Service
class ChecklistReportsService(
    private val store: ChecklistReportsStore,
    private val authorization: Authorization,
    private val hotel: HotelApi,
) {
    @Transactional(readOnly = true)
    fun compliance(filter: ChecklistReportFilter): List<TemplateComplianceRow> {
        authorization.require(ChecklistsPermissions.REPORTS_READ)
        return store.compliance(query(filter))
    }

    @Transactional(readOnly = true)
    fun failedItems(
        filter: ChecklistReportFilter,
        limit: Int,
    ): List<FailedItemRow> {
        authorization.require(ChecklistsPermissions.REPORTS_READ)
        return store.failedItems(query(filter), limit)
    }

    /** O histórico de um local: o filtro precisa do tipo e do id do sujeito. */
    @Transactional(readOnly = true)
    fun locationHistory(
        filter: ChecklistReportFilter,
        page: PageParams,
    ): CursorPage<LocationHistoryRow> {
        requireNotNull(filter.subjectType) { "O histórico é de um local: falta o tipo do sujeito." }
        requireNotNull(filter.subjectId) { "O histórico é de um local: falta o id do sujeito." }
        authorization.require(ChecklistsPermissions.REPORTS_READ)
        val before = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        val rows = store.locationHistory(query(filter), before, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) {
            PageCursor.of(it.referenceAt.toString(), it.executionId.toString())
        }
    }

    private fun query(filter: ChecklistReportFilter): ReportQuery {
        if (filter.from != null && filter.to != null && filter.to.isBefore(filter.from)) {
            val message = "O fim do período não pode ser antes do início."
            val code = ChecklistsErrorCodes.CHECKLIST_REPORT_PERIOD_INVALID
            throw InvalidInputException(code, message, listOf(FieldViolation("to", code.value, message)))
        }
        val zone = hotel.timezone()
        return ReportQuery(
            from = filter.from?.atStartOfDay(zone)?.toInstant(),
            to =
                filter.to
                    ?.plusDays(1)
                    ?.atStartOfDay(zone)
                    ?.toInstant(),
            templateId = filter.templateId,
            subjectType = filter.subjectType,
            subjectId = filter.subjectId,
        )
    }
}

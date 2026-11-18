package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.EndOfDayRunNotFoundException
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** As consultas do fechamento do dia (M6 E10): execuções, etapas e pendências, todas com `endofday.runs.read`. */
@Service
class EndOfDayQueries(
    private val runs: EndOfDayRunRepository,
    private val issues: EndOfDayIssueRepository,
    private val authorization: Authorization,
) {
    /** Uma página das execuções, data mais nova primeiro (`endofday.runs.read`); o cursor é `(data, id)`. */
    @Transactional(readOnly = true)
    fun listRuns(page: PageParams): CursorPage<EndOfDayRunView> {
        authorization.require(EndOfDayPermissions.RUNS_READ)
        val after = page.decodedCursor()?.let { it.localDate(0) to it.uuid(1) }
        val pageable = PageRequest.ofSize(page.limit + 1)
        val rows = after?.let { (date, id) -> runs.pageAfter(date, id, pageable) } ?: runs.firstPage(pageable)
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.businessDate.toString(), it.id.value.toString()) }
            .map { it.toView() }
    }

    /** A execução [runId] com as etapas em ordem (`endofday.runs.read`); inexistente é 404. */
    @Transactional(readOnly = true)
    fun getRun(runId: UUID): EndOfDayRunView {
        authorization.require(EndOfDayPermissions.RUNS_READ)
        return runs.findById(runId).orElse(null)?.toView() ?: throw EndOfDayRunNotFoundException()
    }

    /** Uma página das pendências da execução [runId], da mais antiga; o cursor é `(createdAt, id)`. */
    @Transactional(readOnly = true)
    fun listIssues(
        runId: UUID,
        page: PageParams,
    ): CursorPage<EndOfDayIssueView> {
        authorization.require(EndOfDayPermissions.RUNS_READ)
        val after = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        if (!runs.existsById(runId)) throw EndOfDayRunNotFoundException()
        val pageable = PageRequest.ofSize(page.limit + 1)
        val rows =
            after?.let { (createdAt, id) -> issues.pageAfter(runId, createdAt, id, pageable) }
                ?: issues.firstPage(runId, pageable)
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.createdAt.toString(), it.id.toString()) }
            .map { it.toView() }
    }
}

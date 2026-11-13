package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.EndOfDayRunNotFoundException
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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
        val after = page.decodedCursor()?.let { it.instant(0).also(::requireStorable) to it.uuid(1) }
        if (!runs.existsById(runId)) throw EndOfDayRunNotFoundException()
        val pageable = PageRequest.ofSize(page.limit + 1)
        val rows =
            after?.let { (createdAt, id) -> issues.pageAfter(runId, createdAt, id, pageable) }
                ?: issues.firstPage(runId, pageable)
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.createdAt.toString(), it.id.toString()) }
            .map { it.toView() }
    }

    private fun requireStorable(instant: Instant) {
        if (instant < MIN_STORABLE_INSTANT || instant >= MAX_STORABLE_INSTANT_EXCLUSIVE) {
            throw InvalidInputException(
                ErrorCodes.INVALID_CURSOR,
                "Cursor de paginação inválido. Recomece da primeira página.",
            )
        }
    }

    private companion object {
        /**
         * O primeiro instante que o `timestamptz` do PostgreSQL guarda (`4713-11-24 BC`, no calendário ISO o ano
         * -4713). Um cursor bem formado antes dele faria a consulta falhar com `timestamp out of range` (500).
         */
        val MIN_STORABLE_INSTANT: Instant = LocalDate.of(-4713, 11, 24).atStartOfDay().toInstant(ZoneOffset.UTC)

        /** O primeiro instante depois do alcance do `timestamptz` (que acaba em `294276-12-31`); mesmo motivo. */
        val MAX_STORABLE_INSTANT_EXCLUSIVE: Instant =
            LocalDate
                .of(294_276, 12, 31)
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
    }
}

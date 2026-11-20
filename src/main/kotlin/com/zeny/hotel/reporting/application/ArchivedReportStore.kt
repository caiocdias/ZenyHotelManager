package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.ArchivedReport
import com.zeny.hotel.reporting.ArchivedReportKind
import java.time.LocalDate

/** A porta do índice dos documentos arquivados por data (`reporting.archived_report`, só de inserção). */
interface ArchivedReportStore {
    /**
     * Trava consultiva da transação (`pg_advisory_xact_lock`) por ([date], [kind]): quem chega depois espera o commit
     * de quem já está arquivando o mesmo tipo da mesma data e então o encontra em [find]. Tomar **antes** de conferir.
     */
    fun lock(
        date: LocalDate,
        kind: ArchivedReportKind,
    )

    /** O documento arquivado de [date] e [kind], ou `null`. */
    fun find(
        date: LocalDate,
        kind: ArchivedReportKind,
    ): ArchivedReport?

    /** Grava a linha do índice (único por data e tipo). */
    fun insert(report: ArchivedReport)

    /** Os documentos arquivados de [date], por tipo. */
    fun list(date: LocalDate): List<ArchivedReport>
}

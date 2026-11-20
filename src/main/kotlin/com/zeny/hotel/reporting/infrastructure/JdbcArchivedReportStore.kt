package com.zeny.hotel.reporting.infrastructure

import com.zeny.hotel.files.FileId
import com.zeny.hotel.reporting.ArchivedReport
import com.zeny.hotel.reporting.ArchivedReportKind
import com.zeny.hotel.reporting.application.ArchivedReportStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** `reporting.archived_report` pelo `JdbcClient`: uma linha por data e tipo, só de inserção. */
@Component
class JdbcArchivedReportStore(
    private val jdbc: JdbcClient,
) : ArchivedReportStore {
    override fun lock(
        date: LocalDate,
        kind: ArchivedReportKind,
    ) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtext('reporting.archived_report'), hashtext(:key))")
            .param("key", "$date:${kind.name}")
            .query { _, _ -> }
            .list()
    }

    override fun find(
        date: LocalDate,
        kind: ArchivedReportKind,
    ): ArchivedReport? =
        jdbc
            .sql("SELECT * FROM reporting.archived_report WHERE business_date = :date AND kind = :kind")
            .param("date", date)
            .param("kind", kind.name)
            .query { rs, _ -> rs.report() }
            .optional()
            .orElse(null)

    override fun insert(report: ArchivedReport) {
        jdbc
            .sql(
                """
                INSERT INTO reporting.archived_report (id, business_date, kind, file_id, created_at)
                VALUES (:id, :date, :kind, :fileId, :createdAt)
                """.trimIndent(),
            ).param("id", report.id)
            .param("date", report.businessDate)
            .param("kind", report.kind.name)
            .param("fileId", report.fileId.value)
            .param("createdAt", report.createdAt.atOffset(ZoneOffset.UTC))
            .update()
    }

    override fun list(date: LocalDate): List<ArchivedReport> =
        jdbc
            .sql("SELECT * FROM reporting.archived_report WHERE business_date = :date ORDER BY kind")
            .param("date", date)
            .query { rs, _ -> rs.report() }
            .list()

    private fun ResultSet.report() =
        ArchivedReport(
            id = getObject("id", UUID::class.java),
            businessDate = getObject("business_date", LocalDate::class.java),
            kind = ArchivedReportKind.valueOf(getString("kind")),
            fileId = FileId(getObject("file_id", UUID::class.java)),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
}

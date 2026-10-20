package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.application.FolioReopenings
import com.zeny.hotel.billing.domain.FolioReopening
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * As reaberturas de contas por SQL (M3 E10): só `INSERT` e `SELECT` em `billing.folio_reopening` (*append-only*, bean
 * [BillingTablesConfiguration]); um trigger recusa `UPDATE`/`DELETE` até do dono.
 */
@Component
class JdbcFolioReopenings(
    private val jdbc: JdbcClient,
) : FolioReopenings {
    override fun add(reopening: FolioReopening) {
        jdbc
            .sql(
                """
                INSERT INTO billing.folio_reopening (id, folio_id, reason, closed_on, closed_at, business_date,
                    reopened_at, reopened_by, approval_id, approved_by)
                VALUES (:id, :folioId, :reason, :closedOn, :closedAt, :businessDate, :reopenedAt, :reopenedBy,
                    :approvalId, :approvedBy)
                """.trimIndent(),
            ).param("id", reopening.id)
            .param("folioId", reopening.folioId.value)
            .param("reason", reopening.reason)
            .param("closedOn", reopening.closedOn)
            .param("closedAt", reopening.closedAt.atOffset(ZoneOffset.UTC))
            .param("businessDate", reopening.businessDate)
            .param("reopenedAt", reopening.reopenedAt.atOffset(ZoneOffset.UTC))
            .param("reopenedBy", reopening.reopenedBy.value)
            .param("approvalId", reopening.approval?.approvalId)
            .param("approvedBy", reopening.approval?.approvedBy?.value)
            .update()
    }

    override fun of(folioId: FolioId): List<FolioReopening> =
        jdbc
            .sql("SELECT * FROM billing.folio_reopening WHERE folio_id = :folioId ORDER BY reopened_at, id")
            .param("folioId", folioId.value)
            .query { rs, _ ->
                FolioReopening(
                    rs.getObject("id", UUID::class.java),
                    FolioId(rs.getObject("folio_id", UUID::class.java)),
                    rs.getString("reason"),
                    rs.getObject("closed_on", LocalDate::class.java),
                    rs.getObject("closed_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getObject("reopened_at", OffsetDateTime::class.java).toInstant(),
                    UserId(rs.getObject("reopened_by", UUID::class.java)),
                    rs.getObject("approval_id", UUID::class.java)?.let {
                        PostingApproval(it, UserId(rs.getObject("approved_by", UUID::class.java)))
                    },
                )
            }.list()
}

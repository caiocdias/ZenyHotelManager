package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.application.PostingLedger
import com.zeny.hotel.billing.application.PostingRow
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os lançamentos por SQL (RN-03): só `INSERT` e `SELECT`, que é tudo o que o papel da aplicação pode em
 * `billing.posting` (bean [billingAppendOnlyTables]); um trigger recusa `UPDATE`/`DELETE` até do dono.
 */
@Component
class JdbcPostingLedger(
    private val jdbc: JdbcClient,
) : PostingLedger {
    override fun tryAdd(posting: Posting): Boolean =
        unlessViolated(BillingConstraints.POSTING_REVERSAL) {
            jdbc
                .sql(
                    """
                    INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                        amount, description, source, business_date, posted_at, posted_by, corrects_id, reason,
                        approval_id, approved_by)
                    VALUES (:id, :folioId, :codeId, :type, :quantity, :unitPrice, :amount, :description, :source,
                        :businessDate, :postedAt, :postedBy, :correctsId, :reason, :approvalId, :approvedBy)
                    """.trimIndent(),
                ).param("id", posting.id.value)
                .param("folioId", posting.folioId.value)
                .param("codeId", posting.transactionCodeId.value)
                .param("type", posting.type.name)
                .param("quantity", posting.quantity)
                .param("unitPrice", posting.unitPrice.amount)
                .param("amount", posting.amount.amount)
                .param("description", posting.description)
                .param("source", posting.source.name)
                .param("businessDate", posting.businessDate)
                .param("postedAt", posting.postedAt.atOffset(ZoneOffset.UTC))
                .param("postedBy", posting.postedBy?.value)
                .param("correctsId", posting.correctsId?.value)
                .param("reason", posting.reason)
                .param("approvalId", posting.approval?.approvalId)
                .param("approvedBy", posting.approval?.approvedBy?.value)
                .update()
        }

    override fun posting(id: PostingId): Posting? =
        jdbc
            .sql("SELECT * FROM billing.posting WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> posting(rs) }
            .optional()
            .orElse(null)

    override fun correctionsOf(id: PostingId): List<Posting> =
        jdbc
            .sql("SELECT * FROM billing.posting WHERE corrects_id = :id ORDER BY posted_at, id")
            .param("id", id.value)
            .query { rs, _ -> posting(rs) }
            .list()

    override fun balances(folioIds: Collection<FolioId>): Map<FolioId, Money> {
        if (folioIds.isEmpty()) return emptyMap()
        val sums =
            jdbc
                .sql(
                    """
                    SELECT folio_id, sum(amount) AS total FROM billing.posting
                    WHERE folio_id IN (:ids) GROUP BY folio_id
                    """.trimIndent(),
                ).param("ids", folioIds.map { it.value })
                .query { rs, _ ->
                    FolioId(rs.getObject("folio_id", UUID::class.java)) to
                        Money.of(rs.getBigDecimal("total"))
                }.list()
                .toMap()
        return folioIds.associateWith { sums[it] ?: Money.ZERO }
    }

    override fun page(
        folioId: FolioId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<PostingRow> =
        jdbc
            .sql(
                """
                $ROW_SELECT
                WHERE p.folio_id = :folioId
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (p.posted_at, p.id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY p.posted_at, p.id
                LIMIT :limit
                """.trimIndent(),
            ).param("folioId", folioId.value)
            .param("afterAt", after?.first?.atOffset(ZoneOffset.UTC))
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> row(rs) }
            .list()

    override fun row(id: PostingId): PostingRow? =
        jdbc
            .sql("$ROW_SELECT WHERE p.id = :id")
            .param("id", id.value)
            .query { rs, _ -> row(rs) }
            .optional()
            .orElse(null)

    private fun row(rs: ResultSet) =
        PostingRow(
            posting(rs),
            rs.getObject("reversed_by", UUID::class.java)?.let(::PostingId),
            rs.getBigDecimal("remaining")?.let(Money::of),
        )

    private fun posting(rs: ResultSet): Posting =
        Posting(
            id = PostingId(rs.getObject("id", UUID::class.java)),
            folioId = FolioId(rs.getObject("folio_id", UUID::class.java)),
            transactionCodeId = TransactionCodeId(rs.getObject("transaction_code_id", UUID::class.java)),
            type = PostingType.valueOf(rs.getString("posting_type")),
            quantity = rs.getBigDecimal("quantity"),
            unitPrice = Money.of(rs.getBigDecimal("unit_price")),
            amount = Money.of(rs.getBigDecimal("amount")),
            description = rs.getString("description"),
            source = PostingSource.valueOf(rs.getString("source")),
            businessDate = rs.getObject("business_date", LocalDate::class.java),
            postedAt = rs.getObject("posted_at", OffsetDateTime::class.java).toInstant(),
            postedBy = rs.getObject("posted_by", UUID::class.java)?.let(::UserId),
            correctsId = rs.getObject("corrects_id", UUID::class.java)?.let(::PostingId),
            reason = rs.getString("reason"),
            approval =
                rs.getObject("approval_id", UUID::class.java)?.let {
                    PostingApproval(it, UserId(rs.getObject("approved_by", UUID::class.java)))
                },
        )

    private companion object {
        /** O lançamento, o estorno dele (se houve) e, no normal, o que ainda resta (ele mais as correções). */
        val ROW_SELECT =
            """
            SELECT p.*, r.id AS reversed_by,
                   CASE WHEN p.posting_type = 'REGULAR'
                        THEN p.amount + COALESCE((SELECT sum(c.amount) FROM billing.posting c WHERE c.corrects_id = p.id), 0)
                   END AS remaining
            FROM billing.posting p
            LEFT JOIN billing.posting r ON r.corrects_id = p.id AND r.posting_type = 'REVERSAL'
            """.trimIndent()
    }
}

/** A tabela *append-only* do módulo (RN-03), que a partida confere ([AppendOnlyTables]). */
@Configuration(proxyBeanMethods = false)
class BillingTablesConfiguration {
    @Bean
    fun billingAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(POSTING_TABLE) }

    companion object {
        const val POSTING_TABLE = "billing.posting"
    }
}

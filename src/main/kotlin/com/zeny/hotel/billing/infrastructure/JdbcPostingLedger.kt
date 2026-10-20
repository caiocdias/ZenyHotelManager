package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.application.PostingLedger
import com.zeny.hotel.billing.application.PostingRow
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.billing.domain.PostingRouting
import com.zeny.hotel.billing.domain.ServiceChargeLink
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
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
@Suppress("TooManyFunctions") // as leituras do livro de lançamentos (PostingLedger) e o mapeamento das linhas
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
                        approval_id, approved_by, routed_from_folio_id, routing_rule_id, transferred_from_id,
                        service_charge_for_id, service_charge_rate, triggered_by_id)
                    VALUES (:id, :folioId, :codeId, :type, :quantity, :unitPrice, :amount, :description, :source,
                        :businessDate, :postedAt, :postedBy, :correctsId, :reason, :approvalId, :approvedBy,
                        :routedFrom, :routingRule, :transferredFrom, :serviceChargeFor, :serviceChargeRate,
                        :triggeredBy)
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
                .param("routedFrom", posting.routing?.fromFolioId?.value)
                .param("routingRule", posting.routing?.ruleId?.value)
                .param("transferredFrom", posting.transferredFromId?.value)
                .param("serviceChargeFor", posting.serviceCharge?.itemId?.value)
                .param("serviceChargeRate", posting.serviceCharge?.rate?.value)
                .param("triggeredBy", posting.triggeredById?.value)
                .update()
        }

    override fun posting(id: PostingId): Posting? =
        jdbc
            .sql("SELECT * FROM billing.posting WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> posting(rs) }
            .optional()
            .orElse(null)

    override fun serviceChargeOf(itemId: PostingId): Posting? =
        jdbc
            .sql("SELECT * FROM billing.posting WHERE service_charge_for_id = :id")
            .param("id", itemId.value)
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
                    SELECT f.id AS folio_id,
                        COALESCE((SELECT sum(amount) FROM billing.posting p WHERE p.folio_id = f.id), 0)
                        - COALESCE((SELECT sum(CASE WHEN y.payment_type = 'PAYMENT' THEN y.amount ELSE -y.amount END)
                                    FROM billing.payment y WHERE y.folio_id = f.id), 0) AS total
                    FROM billing.folio f
                    WHERE f.id IN (:ids)
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

    override fun lineageRoot(id: PostingId): PostingId =
        jdbc
            .sql(
                """
                WITH RECURSIVE up (id, posting_type, transferred_from_id) AS (
                    SELECT id, posting_type, transferred_from_id FROM billing.posting WHERE id = :id
                    UNION ALL
                    SELECT o.id, o.posting_type, o.transferred_from_id
                    FROM up
                    JOIN billing.posting t ON t.id = up.transferred_from_id
                    JOIN billing.posting o ON o.id = t.corrects_id
                )
                SELECT id FROM up WHERE posting_type IN ('REGULAR', 'SERVICE_CHARGE')
                """.trimIndent(),
            ).param("id", id.value)
            .query(UUID::class.java)
            .optional()
            .map(::PostingId)
            .orElse(id)

    override fun lineageCorrections(root: PostingId): List<Posting> =
        jdbc
            .sql(
                """
                WITH RECURSIVE down (id) AS (
                    SELECT CAST(:root AS uuid)
                    UNION ALL
                    SELECT i.id
                    FROM down
                    JOIN billing.posting t ON t.corrects_id = down.id AND t.posting_type = 'TRANSFER_OUT'
                    JOIN billing.posting i ON i.transferred_from_id = t.id
                ),
                charges (id) AS (
                    SELECT c.id FROM billing.posting c WHERE c.service_charge_for_id IN (SELECT id FROM down)
                )
                SELECT * FROM billing.posting
                WHERE (corrects_id IN (SELECT id FROM down) OR corrects_id IN (SELECT id FROM charges))
                  AND posting_type IN ('REVERSAL', 'ADJUSTMENT')
                ORDER BY posted_at, id
                """.trimIndent(),
            ).param("root", root.value)
            .query { rs, _ -> posting(rs) }
            .list()

    override fun lockLineage(root: PostingId) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(hashtext('billing.posting-lineage'), hashtext(:root))")
            .param("root", root.value.toString())
            .query()
            .singleRow()
    }

    private fun row(rs: ResultSet) =
        PostingRow(
            posting(rs),
            rs.getObject("reversed_by", UUID::class.java)?.let(::PostingId),
            rs.getBigDecimal("remaining")?.let(Money::of),
            rs.getObject("transferred_to", UUID::class.java)?.let(::PostingId),
            rs.getObject("transferred_to_folio", UUID::class.java)?.let(::FolioId),
            rs.getObject("transferred_from_folio", UUID::class.java)?.let(::FolioId),
            rs.getObject("service_charge_id", UUID::class.java)?.let(::PostingId),
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
            routing =
                rs.getObject("routed_from_folio_id", UUID::class.java)?.let {
                    PostingRouting(FolioId(it), RoutingRuleId(rs.getObject("routing_rule_id", UUID::class.java)))
                },
            transferredFromId = rs.getObject("transferred_from_id", UUID::class.java)?.let(::PostingId),
            serviceCharge =
                rs.getObject("service_charge_for_id", UUID::class.java)?.let {
                    ServiceChargeLink(PostingId(it), Percent.of(rs.getBigDecimal("service_charge_rate")))
                },
            triggeredById = rs.getObject("triggered_by_id", UUID::class.java)?.let(::PostingId),
        )

    private companion object {
        /**
         * O lançamento, o estorno dele (se houve), no normal e na entrada de transferência o que ainda resta (ele mais
         * as correções e saídas), na saída a entrada e a conta dela, na entrada a conta da saída, e no item a taxa de
         * serviço dele nesta conta (M3 E06; a taxa também tem o que resta).
         */
        val ROW_SELECT =
            """
            SELECT p.*, r.id AS reversed_by, sc.id AS service_charge_id,
                   CASE WHEN p.posting_type IN ('REGULAR', 'TRANSFER_IN', 'SERVICE_CHARGE')
                        THEN p.amount + COALESCE((SELECT sum(c.amount) FROM billing.posting c WHERE c.corrects_id = p.id), 0)
                   END AS remaining,
                   t.id AS transferred_to, t.folio_id AS transferred_to_folio, s.folio_id AS transferred_from_folio
            FROM billing.posting p
            LEFT JOIN billing.posting r ON r.corrects_id = p.id AND r.posting_type = 'REVERSAL'
            LEFT JOIN billing.posting t ON t.transferred_from_id = p.id
            LEFT JOIN billing.posting s ON s.id = p.transferred_from_id
            LEFT JOIN billing.posting sc ON sc.service_charge_for_id = p.id
            """.trimIndent()
    }
}

/**
 * As tabelas *append-only* do módulo (RN-03), que a partida confere ([AppendOnlyTables]): os lançamentos e, desde a
 * E07, os movimentos de caixa e as contagens do fechamento, desde a E08, os pagamentos e reembolsos, desde a E09,
 * as liberações de caução e, desde a E10, as reaberturas de contas.
 */
@Configuration(proxyBeanMethods = false)
class BillingTablesConfiguration {
    @Bean
    fun billingAppendOnlyTables(): AppendOnlyTables =
        AppendOnlyTables {
            setOf(
                POSTING_TABLE,
                CASH_MOVEMENT_TABLE,
                CASHIER_SHIFT_COUNT_TABLE,
                PAYMENT_TABLE,
                PRE_AUTHORIZATION_RELEASE_TABLE,
                FOLIO_REOPENING_TABLE,
            )
        }

    companion object {
        const val POSTING_TABLE = "billing.posting"
        const val CASH_MOVEMENT_TABLE = "billing.cash_movement"
        const val CASHIER_SHIFT_COUNT_TABLE = "billing.cashier_shift_count"
        const val PAYMENT_TABLE = "billing.payment"
        const val PRE_AUTHORIZATION_RELEASE_TABLE = "billing.card_pre_authorization_release"
        const val FOLIO_REOPENING_TABLE = "billing.folio_reopening"
    }
}

package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.PaymentType
import com.zeny.hotel.billing.application.PaymentLedger
import com.zeny.hotel.billing.application.PaymentRow
import com.zeny.hotel.billing.domain.Payment
import com.zeny.hotel.billing.domain.PaymentReferences
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Os pagamentos e reembolsos por SQL (RN-03, M3 E08): só `INSERT` e `SELECT`, que é tudo o que o papel da aplicação
 * pode em `billing.payment` (bean `BillingTablesConfiguration.billingAppendOnlyTables`); um trigger recusa
 * `UPDATE`/`DELETE` até do dono.
 */
@Component
class JdbcPaymentLedger(
    private val jdbc: JdbcClient,
) : PaymentLedger {
    override fun add(payment: Payment) {
        jdbc
            .sql(
                """
                INSERT INTO billing.payment (id, folio_id, payment_type, method, amount, tendered, change_given, deposit,
                    nsu, authorization_code, card_brand, card_last4, payment_intent_id, refund_of_id, reason,
                    cashier_shift_id, business_date, received_at, received_by, approval_id, approved_by,
                    card_pre_authorization_id)
                VALUES (:id, :folioId, :type, :method, :amount, :tendered, :change, :deposit, :nsu, :authorization,
                    :brand, :last4, :intentId, :refundOf, :reason, :shiftId, :businessDate, :receivedAt, :receivedBy,
                    :approvalId, :approvedBy, :preAuthorizationId)
                """.trimIndent(),
            ).param("id", payment.id.value)
            .param("folioId", payment.folioId.value)
            .param("type", payment.type.name)
            .param("method", payment.method.name)
            .param("amount", payment.amount.amount)
            .param("tendered", payment.tendered?.amount)
            .param("change", payment.change?.amount)
            .param("deposit", payment.deposit)
            .param("nsu", payment.references.nsu)
            .param("authorization", payment.references.authorizationCode)
            .param("brand", payment.references.cardBrand)
            .param("last4", payment.references.cardLast4)
            .param("intentId", payment.paymentIntentId)
            .param("refundOf", payment.refundOfId?.value)
            .param("reason", payment.reason)
            .param("shiftId", payment.shiftId.value)
            .param("businessDate", payment.businessDate)
            .param("receivedAt", payment.receivedAt.atOffset(ZoneOffset.UTC))
            .param("receivedBy", payment.receivedBy.value)
            .param("approvalId", payment.approval?.approvalId)
            .param("approvedBy", payment.approval?.approvedBy?.value)
            .param("preAuthorizationId", payment.preAuthorizationId?.value)
            .update()
    }

    override fun payment(id: PaymentId): Payment? =
        jdbc
            .sql("SELECT * FROM billing.payment WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.payment() }
            .optional()
            .orElse(null)

    override fun refundsOf(id: PaymentId): List<Payment> =
        jdbc
            .sql("SELECT * FROM billing.payment WHERE refund_of_id = :id ORDER BY received_at, id")
            .param("id", id.value)
            .query { rs, _ -> rs.payment() }
            .list()

    override fun existsForIntent(intentId: UUID): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM billing.payment WHERE payment_intent_id = :id)")
            .param("id", intentId)
            .query(Boolean::class.java)
            .single()

    override fun page(
        folioId: FolioId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<PaymentRow> =
        jdbc
            .sql(
                """
                SELECT p.*, $REFUNDED FROM billing.payment p
                WHERE p.folio_id = :folioId
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (p.received_at, p.id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY p.received_at, p.id
                LIMIT :limit
                """.trimIndent(),
            ).param("folioId", folioId.value)
            .param("afterAt", after?.first?.atOffset(ZoneOffset.UTC))
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.row() }
            .list()

    override fun row(id: PaymentId): PaymentRow? =
        jdbc
            .sql("SELECT p.*, $REFUNDED FROM billing.payment p WHERE p.id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.row() }
            .optional()
            .orElse(null)

    override fun shiftTotals(shiftId: CashierShiftId): Map<PaymentMethod, Money> =
        jdbc
            .sql(
                """
                SELECT method, sum(CASE WHEN payment_type = 'PAYMENT' THEN amount ELSE -amount END) AS total
                FROM billing.payment WHERE cashier_shift_id = :shiftId GROUP BY method
                """.trimIndent(),
            ).param("shiftId", shiftId.value)
            .query { rs, _ -> PaymentMethod.valueOf(rs.getString("method")) to Money.of(rs.getBigDecimal("total")) }
            .list()
            .toMap()

    override fun netDeposits(folioIds: Collection<FolioId>): Map<FolioId, Money> {
        if (folioIds.isEmpty()) return emptyMap()
        // O reembolso conta pelo recebimento que devolve (o depósito é dele), não pela marca copiada no reembolso.
        return jdbc
            .sql(
                """
                SELECT p.folio_id, sum(CASE WHEN p.payment_type = 'PAYMENT' THEN p.amount ELSE -p.amount END) AS total
                FROM billing.payment p
                LEFT JOIN billing.payment o ON o.id = p.refund_of_id
                WHERE p.folio_id IN (:ids)
                  AND ((p.payment_type = 'PAYMENT' AND p.deposit) OR (p.payment_type = 'REFUND' AND o.deposit))
                GROUP BY p.folio_id
                """.trimIndent(),
            ).param("ids", folioIds.map { it.value })
            .query { rs, _ ->
                FolioId(rs.getObject("folio_id", UUID::class.java)) to Money.of(rs.getBigDecimal("total"))
            }.list()
            .toMap()
    }

    private fun ResultSet.row(): PaymentRow {
        val payment = payment()
        val refunded = Money.of(getBigDecimal("refunded")).takeIf { payment.type == PaymentType.PAYMENT }
        return PaymentRow(payment, refunded)
    }

    private fun ResultSet.payment(): Payment =
        Payment(
            PaymentId(getObject("id", UUID::class.java)),
            FolioId(getObject("folio_id", UUID::class.java)),
            PaymentType.valueOf(getString("payment_type")),
            PaymentMethod.valueOf(getString("method")),
            Money.of(getBigDecimal("amount")),
            getBigDecimal("tendered")?.let(Money::of),
            getBoolean("deposit"),
            PaymentReferences(
                getString("nsu"),
                getString("authorization_code"),
                getString("card_brand"),
                getString("card_last4"),
            ),
            getObject("payment_intent_id", UUID::class.java),
            getObject("refund_of_id", UUID::class.java)?.let(::PaymentId),
            getString("reason"),
            CashierShiftId(getObject("cashier_shift_id", UUID::class.java)),
            getObject("business_date", LocalDate::class.java),
            getObject("received_at", OffsetDateTime::class.java).toInstant(),
            UserId(getObject("received_by", UUID::class.java)),
            getObject("approval_id", UUID::class.java)?.let {
                PostingApproval(it, UserId(getObject("approved_by", UUID::class.java)))
            },
            getObject("card_pre_authorization_id", UUID::class.java)?.let(::CardPreAuthorizationId),
        )

    private companion object {
        /** O total reembolsado de um recebimento (zero sem reembolso). */
        const val REFUNDED =
            "COALESCE((SELECT sum(r.amount) FROM billing.payment r WHERE r.refund_of_id = p.id), 0) AS refunded"
    }
}

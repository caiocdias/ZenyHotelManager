package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.application.CardPreAuthorizationRow
import com.zeny.hotel.billing.application.CardPreAuthorizationStore
import com.zeny.hotel.billing.domain.CardPreAuthorization
import com.zeny.hotel.billing.domain.PaymentReferences
import com.zeny.hotel.billing.domain.PreAuthorizationRelease
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
 * As cauções por SQL (M3 E09): a linha de `billing.card_pre_authorization` (só a situação, o capturado e a versão
 * mudam; um trigger recusa o resto) e as liberações em `billing.card_pre_authorization_release`, só inseridas
 * (*append-only*). Triggers adiados conferem, no commit, que o capturado é a soma das capturas e que só a caução com a
 * liberação está `RELEASED`.
 */
@Component
class JdbcCardPreAuthorizations(
    private val jdbc: JdbcClient,
) : CardPreAuthorizationStore {
    override fun add(preAuthorization: CardPreAuthorization) {
        jdbc
            .sql(
                """
                INSERT INTO billing.card_pre_authorization (id, folio_id, amount, captured_amount, status, expires_at,
                    nsu, authorization_code, card_brand, card_last4, business_date, registered_at, registered_by,
                    updated_at, version)
                VALUES (:id, :folioId, :amount, :captured, :status, :expiresAt, :nsu, :authorization, :brand, :last4,
                    :businessDate, :registeredAt, :registeredBy, :updatedAt, :version)
                """.trimIndent(),
            ).param("id", preAuthorization.id.value)
            .param("folioId", preAuthorization.folioId.value)
            .param("amount", preAuthorization.amount.amount)
            .param("captured", preAuthorization.capturedAmount.amount)
            .param("status", preAuthorization.status.name)
            .param("expiresAt", preAuthorization.expiresAt.atOffset(ZoneOffset.UTC))
            .param("nsu", preAuthorization.references.nsu)
            .param("authorization", preAuthorization.references.authorizationCode)
            .param("brand", preAuthorization.references.cardBrand)
            .param("last4", preAuthorization.references.cardLast4)
            .param("businessDate", preAuthorization.businessDate)
            .param("registeredAt", preAuthorization.registeredAt.atOffset(ZoneOffset.UTC))
            .param("registeredBy", preAuthorization.registeredBy.value)
            .param("updatedAt", preAuthorization.updatedAt.atOffset(ZoneOffset.UTC))
            .param("version", preAuthorization.version)
            .update()
    }

    override fun forUpdate(id: CardPreAuthorizationId): CardPreAuthorization? =
        jdbc
            .sql("SELECT * FROM billing.card_pre_authorization WHERE id = :id FOR UPDATE")
            .param("id", id.value)
            .query { rs, _ -> rs.preAuthorization() }
            .optional()
            .orElse(null)

    override fun update(preAuthorization: CardPreAuthorization) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE billing.card_pre_authorization
                    SET status = :status, captured_amount = :captured, updated_at = :updatedAt, version = :version
                    WHERE id = :id AND version = :previous
                    """.trimIndent(),
                ).param("id", preAuthorization.id.value)
                .param("status", preAuthorization.status.name)
                .param("captured", preAuthorization.capturedAmount.amount)
                .param("updatedAt", preAuthorization.updatedAt.atOffset(ZoneOffset.UTC))
                .param("version", preAuthorization.version)
                .param("previous", preAuthorization.version - 1)
                .update()
        check(updated == 1) { "Caução ${preAuthorization.id} gravada fora da trava (versão inesperada)" }
    }

    override fun addRelease(release: PreAuthorizationRelease) {
        jdbc
            .sql(
                """
                INSERT INTO billing.card_pre_authorization_release (id, card_pre_authorization_id, folio_id, amount,
                    business_date, released_at, released_by)
                VALUES (:id, :preAuthorizationId, :folioId, :amount, :businessDate, :releasedAt, :releasedBy)
                """.trimIndent(),
            ).param("id", release.id)
            .param("preAuthorizationId", release.preAuthorizationId.value)
            .param("folioId", release.folioId.value)
            .param("amount", release.amount.amount)
            .param("businessDate", release.businessDate)
            .param("releasedAt", release.releasedAt.atOffset(ZoneOffset.UTC))
            .param("releasedBy", release.releasedBy.value)
            .update()
    }

    override fun unsettledOf(folioId: FolioId): List<CardPreAuthorization> =
        jdbc
            .sql(
                "SELECT * FROM billing.card_pre_authorization " +
                    "WHERE folio_id = :folioId AND status IN ('ACTIVE', 'PARTIALLY_CAPTURED') ORDER BY id",
            ).param("folioId", folioId.value)
            .query { rs, _ -> rs.preAuthorization() }
            .list()

    override fun row(id: CardPreAuthorizationId): CardPreAuthorizationRow? =
        jdbc
            .sql("$SELECT_ROWS WHERE a.id = :id")
            .param("id", id.value)
            .query { rs, _ -> rs.row() }
            .optional()
            .orElse(null)

    override fun page(
        folioId: FolioId,
        afterId: UUID?,
        limit: Int,
    ): List<CardPreAuthorizationRow> =
        jdbc
            .sql(
                """
                $SELECT_ROWS
                WHERE a.folio_id = :folioId AND (CAST(:afterId AS uuid) IS NULL OR a.id > CAST(:afterId AS uuid))
                ORDER BY a.id
                LIMIT :limit
                """.trimIndent(),
            ).param("folioId", folioId.value)
            .param("afterId", afterId)
            .param("limit", limit)
            .query { rs, _ -> rs.row() }
            .list()

    override fun expiring(
        from: Instant,
        to: Instant,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<CardPreAuthorizationRow> =
        jdbc
            .sql(
                """
                $SELECT_ROWS
                WHERE a.status IN ('ACTIVE', 'PARTIALLY_CAPTURED')
                  AND a.expires_at >= :from AND a.expires_at < :to
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                       OR (a.expires_at, a.id) > (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY a.expires_at, a.id
                LIMIT :limit
                """.trimIndent(),
            ).param("from", from.atOffset(ZoneOffset.UTC))
            .param("to", to.atOffset(ZoneOffset.UTC))
            .param("afterAt", after?.first?.atOffset(ZoneOffset.UTC))
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.row() }
            .list()

    private fun ResultSet.row(): CardPreAuthorizationRow {
        val preAuthorization = preAuthorization()
        val release =
            getObject("release_id", UUID::class.java)?.let {
                PreAuthorizationRelease(
                    it,
                    preAuthorization.id,
                    preAuthorization.folioId,
                    Money.of(getBigDecimal("released_amount")),
                    getObject("released_business_date", LocalDate::class.java),
                    getObject("released_at", OffsetDateTime::class.java).toInstant(),
                    UserId(getObject("released_by", UUID::class.java)),
                )
            }

        @Suppress("UNCHECKED_CAST")
        val captures = (getArray("capture_ids").array as Array<UUID>).map(::PaymentId)
        return CardPreAuthorizationRow(preAuthorization, release, captures)
    }

    private fun ResultSet.preAuthorization(): CardPreAuthorization =
        CardPreAuthorization(
            CardPreAuthorizationId(getObject("id", UUID::class.java)),
            FolioId(getObject("folio_id", UUID::class.java)),
            Money.of(getBigDecimal("amount")),
            Money.of(getBigDecimal("captured_amount")),
            CardPreAuthorizationStatus.valueOf(getString("status")),
            getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            PaymentReferences(
                getString("nsu"),
                getString("authorization_code"),
                getString("card_brand"),
                getString("card_last4"),
            ),
            getObject("business_date", LocalDate::class.java),
            getObject("registered_at", OffsetDateTime::class.java).toInstant(),
            UserId(getObject("registered_by", UUID::class.java)),
            getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            getLong("version"),
        )

    private companion object {
        /** A caução com a liberação e os recebimentos das capturas, em ordem de registro. */
        val SELECT_ROWS =
            """
            SELECT a.*, r.id AS release_id, r.amount AS released_amount, r.business_date AS released_business_date,
                   r.released_at, r.released_by,
                   ARRAY(SELECT p.id FROM billing.payment p WHERE p.card_pre_authorization_id = a.id
                         ORDER BY p.received_at, p.id) AS capture_ids
            FROM billing.card_pre_authorization a
            LEFT JOIN billing.card_pre_authorization_release r ON r.card_pre_authorization_id = a.id
            """.trimIndent()
    }
}

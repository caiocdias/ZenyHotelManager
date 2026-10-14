package com.zeny.hotel.audit.infrastructure

import com.zeny.hotel.audit.application.AuditEntries
import com.zeny.hotel.audit.application.AuditEntryFilter
import com.zeny.hotel.audit.application.AuditEntryPosition
import com.zeny.hotel.audit.application.AuditEntryView
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.AuditOutcome
import com.zeny.hotel.shared.AuditRecord
import com.zeny.hotel.shared.AuditTrail
import com.zeny.hotel.shared.UuidV7
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Grava a trilha (porta [AuditTrail] do `shared`) em `audit.audit_entry`, na transação corrente
 * do caso de uso: o `JdbcClient` usa a mesma conexão da transação JPA. Sem transação, recusa
 * (a entrada sobreviveria a um rollback). Só `INSERT`: o papel da aplicação não tem `UPDATE`,
 * `DELETE` nem `TRUNCATE` na tabela (H4).
 */
@Component
class JdbcAuditTrail(
    private val jdbc: JdbcClient,
    jsonMapper: JsonMapper,
) : AuditTrail,
    AuditEntries {
    private val values = AuditValues(jsonMapper)

    override fun append(records: List<AuditRecord>) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "A trilha de auditoria só grava dentro da transação do caso de uso"
        }
        records.forEach { record ->
            jdbc
                .sql(
                    """
                    INSERT INTO audit.audit_entry (id, occurred_at, user_id, device_session_id, action, outcome,
                        error_code, entity_type, entity_id, before_state, after_state, approval_id)
                    VALUES (:id, :occurredAt, :userId, :deviceSessionId, :action, :outcome, :errorCode, :entityType,
                        :entityId, CAST(:before AS jsonb), CAST(:after AS jsonb), :approvalId)
                    """.trimIndent(),
                ).param("id", UuidV7.generate())
                .param("occurredAt", utc(record.occurredAt))
                .param("userId", record.userId?.value)
                .param("deviceSessionId", record.deviceSessionId?.value)
                .param("action", record.action)
                .param("outcome", record.outcome.name)
                .param("errorCode", record.errorCode)
                .param("entityType", record.entityType)
                .param("entityId", record.entityId)
                .param("before", values.toJson(record.before))
                .param("after", values.toJson(record.after))
                .param("approvalId", record.approvalId)
                .update()
        }
    }

    override fun page(
        filter: AuditEntryFilter,
        after: AuditEntryPosition?,
        limit: Int,
    ): List<AuditEntryView> =
        jdbc
            .sql(
                """
                SELECT id, occurred_at, user_id, device_session_id, action, outcome, error_code, entity_type,
                    entity_id, CAST(before_state AS text) AS before_state, CAST(after_state AS text) AS after_state,
                    approval_id
                FROM audit.audit_entry
                WHERE (CAST(:userId AS uuid) IS NULL OR user_id = CAST(:userId AS uuid))
                  AND (CAST(:entityType AS varchar) IS NULL OR entity_type = CAST(:entityType AS varchar))
                  AND (CAST(:entityId AS varchar) IS NULL OR entity_id = CAST(:entityId AS varchar))
                  AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= CAST(:from AS timestamptz))
                  AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < CAST(:to AS timestamptz))
                  AND (CAST(:afterAt AS timestamptz) IS NULL
                      OR (occurred_at, id) < (CAST(:afterAt AS timestamptz), CAST(:afterId AS uuid)))
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("userId", filter.userId)
            .param("entityType", filter.entityType)
            .param("entityId", filter.entityId)
            .param("from", filter.from?.let(::utc))
            .param("to", filter.to?.let(::utc))
            .param("afterAt", after?.occurredAt?.let(::utc))
            .param("afterId", after?.id)
            .param("limit", limit)
            .query { rs, _ -> toView(rs) }
            .list()

    private fun toView(rs: ResultSet) =
        AuditEntryView(
            id = rs.getObject("id", UUID::class.java),
            occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            userId = rs.getObject("user_id", UUID::class.java),
            deviceSessionId = rs.getObject("device_session_id", UUID::class.java),
            action = rs.getString("action"),
            outcome = AuditOutcome.valueOf(rs.getString("outcome")),
            errorCode = rs.getString("error_code"),
            entityType = rs.getString("entity_type"),
            entityId = rs.getString("entity_id"),
            before = values.fromJson(rs.getString("before_state")),
            after = values.fromJson(rs.getString("after_state")),
            approvalId = rs.getObject("approval_id", UUID::class.java),
        )
}

/** A trilha é *append-only* para o papel da aplicação; a partida confere (DatabaseRoleGuard, `shared`). */
@Configuration(proxyBeanMethods = false)
class AuditTablesConfiguration {
    @Bean
    fun auditAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(AUDIT_ENTRY_TABLE) }

    companion object {
        const val AUDIT_ENTRY_TABLE = "audit.audit_entry"
    }
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

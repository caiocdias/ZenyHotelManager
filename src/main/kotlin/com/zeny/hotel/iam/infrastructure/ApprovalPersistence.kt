package com.zeny.hotel.iam.infrastructure

import com.zeny.hotel.iam.application.ApprovalStore
import com.zeny.hotel.iam.domain.Approval
import com.zeny.hotel.iam.domain.ApprovalBinding
import com.zeny.hotel.iam.domain.ApprovalId
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UserId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `iam.approval` e `iam.approval_use` pelo `JdbcClient`, na transação corrente: só inserção e
 * leitura (as duas tabelas são *append-only* para o papel da aplicação). O uso único é a chave
 * primária de `approval_use`, com `ON CONFLICT DO NOTHING`: a segunda transação a usar a mesma
 * aprovação espera a primeira e, se ela confirmar, não insere nada.
 */
@Component
class JdbcApprovalStore(
    private val jdbc: JdbcClient,
) : ApprovalStore {
    override fun add(approval: Approval) {
        jdbc
            .sql(
                """
                INSERT INTO iam.approval (id, action, permission, resource_ref, limit_key, limit_amount, reason,
                    requester_id, requester_session_id, approver_id, approved_at, expires_at, token_hash)
                VALUES (:id, :action, :permission, :resourceRef, :limitKey, :limitAmount, :reason,
                    :requesterId, :requesterSessionId, :approverId, :approvedAt, :expiresAt, :tokenHash)
                """.trimIndent(),
            ).param("id", approval.id.value)
            .param("action", approval.binding.action)
            .param("permission", approval.binding.permission.value)
            .param("resourceRef", approval.binding.resourceRef)
            .param("limitKey", approval.binding.limitKey)
            .param("limitAmount", approval.binding.limitAmount)
            .param("reason", approval.reason)
            .param("requesterId", approval.requesterId.value)
            .param("requesterSessionId", approval.requesterSessionId.value)
            .param("approverId", approval.approverId.value)
            .param("approvedAt", utc(approval.approvedAt))
            .param("expiresAt", utc(approval.expiresAt))
            .param("tokenHash", approval.tokenHash)
            .update()
    }

    override fun findByTokenHash(tokenHash: String): Approval? =
        jdbc
            .sql(
                """
                SELECT id, action, permission, resource_ref, limit_key, limit_amount, reason, requester_id,
                    requester_session_id, approver_id, approved_at, expires_at, token_hash
                FROM iam.approval WHERE token_hash = :tokenHash
                """.trimIndent(),
            ).param("tokenHash", tokenHash)
            .query { rs, _ -> approvalOf(rs) }
            .optional()
            .orElse(null)

    override fun isUsed(id: ApprovalId): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM iam.approval_use WHERE approval_id = :id)")
            .param("id", id.value)
            .query(Boolean::class.java)
            .single()

    override fun markUsed(
        id: ApprovalId,
        userId: UserId,
        sessionId: DeviceSessionId,
        at: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO iam.approval_use (approval_id, used_at, user_id, device_session_id)
                VALUES (:id, :usedAt, :userId, :sessionId)
                ON CONFLICT (approval_id) DO NOTHING
                """.trimIndent(),
            ).param("id", id.value)
            .param("usedAt", utc(at))
            .param("userId", userId.value)
            .param("sessionId", sessionId.value)
            .update() == 1

    private fun approvalOf(rs: ResultSet) =
        Approval(
            id = ApprovalId(rs.getObject("id", UUID::class.java)),
            binding =
                ApprovalBinding(
                    action = rs.getString("action"),
                    permission = PermissionCode(rs.getString("permission")),
                    resourceRef = rs.getString("resource_ref"),
                    limitKey = rs.getString("limit_key"),
                    limitAmount = rs.getObject("limit_amount", BigDecimal::class.java),
                ),
            reason = rs.getString("reason"),
            requesterId = UserId(rs.getObject("requester_id", UUID::class.java)),
            requesterSessionId = DeviceSessionId(rs.getObject("requester_session_id", UUID::class.java)),
            approverId = UserId(rs.getObject("approver_id", UUID::class.java)),
            approvedAt = rs.getObject("approved_at", OffsetDateTime::class.java).toInstant(),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            tokenHash = rs.getString("token_hash"),
        )
}

/** As aprovações e os usos são *append-only* para o papel da aplicação; a partida confere (DatabaseRoleGuard). */
@Configuration(proxyBeanMethods = false)
class ApprovalTablesConfiguration {
    @Bean
    fun iamAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(APPROVAL_TABLE, APPROVAL_USE_TABLE) }

    companion object {
        const val APPROVAL_TABLE = "iam.approval"
        const val APPROVAL_USE_TABLE = "iam.approval_use"
    }
}

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

package com.zeny.hotel.shared.infrastructure.idempotency

import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.shared.application.idempotency.IdempotencyRecord
import com.zeny.hotel.shared.application.idempotency.IdempotencyRecords
import com.zeny.hotel.shared.application.idempotency.NewIdempotencyRecord
import com.zeny.hotel.shared.application.idempotency.StoredResponse
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * Chaves de idempotência na tabela `shared.idempotency_key`. Cada operação roda numa transação
 * própria (`REQUIRES_NEW`) e confirma na hora, mesmo que alguém a chame dentro de outra
 * transação: a reserva tem de ficar visível às requisições concorrentes antes de o handler
 * executar, e a resposta tem de ficar gravada mesmo que a transação de fora seja desfeita.
 *
 * A unicidade de `(scope, idempotency_key)` faz a reserva atômica: com
 * `INSERT … ON CONFLICT DO NOTHING`, de duas inserções simultâneas só uma grava a linha.
 *
 * A resposta guardada pode trazer dado pessoal (nome de hóspede, documento, nome de arquivo
 * em `Content-Disposition`), então o corpo (em base64) e os cabeçalhos (em JSON) são gravados
 * cifrados pelo [FieldCipher] (ARCHITECTURE §8.3). Como a chave vive no máximo 24h, uma
 * rotação de chave de cifragem não exige re-cifrar nada: basta manter a antiga por um dia.
 */
@Component
class JdbcIdempotencyRecords(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
    private val cipher: FieldCipher,
    transactionManager: PlatformTransactionManager,
) : IdempotencyRecords {
    private val newTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun insertIfAbsent(record: NewIdempotencyRecord): Boolean =
        inNewTransaction {
            jdbc
                .sql(
                    """
                    INSERT INTO shared.idempotency_key
                        (id, scope, idempotency_key, request_hash, status, created_at, expires_at)
                    VALUES (:id, :scope, :key, :hash, 'IN_PROGRESS', :createdAt, :expiresAt)
                    ON CONFLICT (scope, idempotency_key) DO NOTHING
                    """.trimIndent(),
                ).param("id", record.id)
                .param("scope", record.request.scope)
                .param("key", record.request.key)
                .param("hash", record.request.requestHash)
                .param("createdAt", utc(record.createdAt))
                .param("expiresAt", utc(record.expiresAt))
                .update() == 1
        }

    override fun find(
        scope: String,
        key: String,
    ): IdempotencyRecord? =
        inNewTransaction {
            jdbc
                .sql(
                    """
                    SELECT id, request_hash, expires_at, response_status, response_headers, response_body
                    FROM shared.idempotency_key
                    WHERE scope = :scope AND idempotency_key = :key
                    """.trimIndent(),
                ).param("scope", scope)
                .param("key", key)
                .query { rs, _ -> toRecord(rs) }
                .optional()
                .orElse(null)
        }

    override fun replaceExpired(
        expiredId: UUID,
        record: NewIdempotencyRecord,
        now: Instant,
    ): Boolean =
        inNewTransaction {
            jdbc
                .sql(
                    """
                    UPDATE shared.idempotency_key
                    SET id = :id, request_hash = :hash, status = 'IN_PROGRESS', response_status = NULL,
                        response_headers = NULL, response_body = NULL, created_at = :createdAt,
                        completed_at = NULL, expires_at = :expiresAt
                    WHERE id = :expiredId AND expires_at <= :now
                    """.trimIndent(),
                ).param("id", record.id)
                .param("hash", record.request.requestHash)
                .param("createdAt", utc(record.createdAt))
                .param("expiresAt", utc(record.expiresAt))
                .param("expiredId", expiredId)
                .param("now", utc(now))
                .update() == 1
        }

    override fun complete(
        id: UUID,
        response: StoredResponse,
        completedAt: Instant,
    ): Boolean =
        inNewTransaction {
            jdbc
                .sql(
                    """
                    UPDATE shared.idempotency_key
                    SET status = 'COMPLETED', response_status = :status, response_headers = :headers,
                        response_body = :body, completed_at = :completedAt
                    WHERE id = :id AND status = 'IN_PROGRESS'
                    """.trimIndent(),
                ).param("status", response.status)
                .param("headers", cipher.encrypt(jsonMapper.writeValueAsString(response.headers)))
                .param("body", cipher.encrypt(BASE64.encodeToString(response.body)))
                .param("completedAt", utc(completedAt))
                .param("id", id)
                .update() == 1
        }

    override fun deleteInProgress(id: UUID): Boolean =
        inNewTransaction {
            jdbc
                .sql("DELETE FROM shared.idempotency_key WHERE id = :id AND status = 'IN_PROGRESS'")
                .param("id", id)
                .update() == 1
        }

    override fun deleteExpired(now: Instant): Int =
        inNewTransaction {
            jdbc
                .sql("DELETE FROM shared.idempotency_key WHERE expires_at <= :now")
                .param("now", utc(now))
                .update()
        }

    private fun toRecord(rs: ResultSet): IdempotencyRecord {
        val status = rs.getInt("response_status").takeUnless { rs.wasNull() }
        val response =
            status?.let {
                StoredResponse(
                    status = it,
                    headers = jsonMapper.readValue(cipher.decrypt(rs.getString("response_headers")), HEADERS_TYPE),
                    body = Base64.getDecoder().decode(cipher.decrypt(rs.getString("response_body"))),
                )
            }
        return IdempotencyRecord(
            id = rs.getObject("id", UUID::class.java),
            requestHash = rs.getString("request_hash"),
            expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            response = response,
        )
    }

    private fun <T> inNewTransaction(action: () -> T): T = newTransaction.execute { action() }

    private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private companion object {
        val HEADERS_TYPE = object : TypeReference<Map<String, List<String>>>() {}
        val BASE64: Base64.Encoder = Base64.getEncoder()
    }
}

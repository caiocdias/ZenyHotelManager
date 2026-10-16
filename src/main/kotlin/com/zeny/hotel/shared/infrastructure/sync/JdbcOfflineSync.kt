package com.zeny.hotel.shared.infrastructure.sync

import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.OfflinePosition
import com.zeny.hotel.shared.OfflineSource
import com.zeny.hotel.shared.OfflineTombstones
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.application.sync.CommandRetention
import com.zeny.hotel.shared.application.sync.OfflineChangesService
import com.zeny.hotel.shared.application.sync.OfflineCommandRecords
import com.zeny.hotel.shared.application.sync.OfflineCommandService
import com.zeny.hotel.shared.application.sync.OfflineCommandStatus
import com.zeny.hotel.shared.application.sync.OfflineSyncStore
import com.zeny.hotel.shared.application.sync.StoredOfflineCommand
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val TOMBSTONE_TABLE = "shared.sync_tombstone"

/** O horizonte e os *tombstones* do download offline (`JdbcClient`, consultas parametrizadas). */
@Component
class JdbcOfflineSyncStore(
    private val jdbc: JdbcClient,
) : OfflineSyncStore {
    override fun horizon(): Long =
        jdbc
            .sql("SELECT pg_snapshot_xmin(pg_current_snapshot())::text")
            .query(String::class.java)
            .single()
            .toLong()

    override fun tombstones(
        source: OfflineSource,
        request: OfflineChangeRequest,
    ): List<OfflineChange> =
        jdbc
            .sql(
                """
                SELECT t.entity_id, ${OfflineChangeRequest.positionColumns("t")}
                FROM $TOMBSTONE_TABLE t
                WHERE t.source = :source
                  AND ${OfflineChangeRequest.window("t")}
                  AND (
                      (cardinality(t.left_scope_of) = 0)
                      OR (CAST(:owner AS uuid) IS NOT NULL AND CAST(:owner AS uuid) = ANY (t.left_scope_of))
                  )
                ORDER BY t.change_xid, t.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams())
            .param("source", source.name)
            .query { rs, _ -> tombstoneOf(rs) }
            .list()

    private fun tombstoneOf(rs: ResultSet): OfflineChange =
        OfflineChange.tombstone(OfflinePosition.from(rs), rs.getObject("entity_id", UUID::class.java))
}

/**
 * Os *tombstones* gravados pela aplicação, na transação de escrita de quem apagou o registro (a posição
 * vem do trigger de `shared.sync_tombstone`). A tabela é *append-only* para o papel da aplicação.
 */
@Component
class JdbcOfflineTombstones(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val changes: OfflineChangesService,
) : OfflineTombstones {
    override fun record(
        source: OfflineSource,
        id: UUID,
        leftScopeOf: Set<UserId>,
    ) {
        require(changes.source(source.name) == source) { "Fonte offline não registrada: $source" }
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "Grave o tombstone dentro da transação de escrita que apagou o registro" }
        jdbc
            .sql(
                """
                INSERT INTO $TOMBSTONE_TABLE (id, source, entity_id, left_scope_of, recorded_at)
                VALUES (:id, :source, :entityId, CAST(:leftScopeOf AS uuid[]), :recordedAt)
                """.trimIndent(),
            ).param("id", UuidV7.generate())
            .param("source", source.name)
            .param("entityId", id)
            .param("leftScopeOf", leftScopeOf.map { it.value }.sorted().joinToString(",", "{", "}"))
            .param("recordedAt", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }
}

/** Os resultados dos comandos offline em `shared.offline_command`, na transação corrente. */
@Component
class JdbcOfflineCommandRecords(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : OfflineCommandRecords {
    override fun find(commandId: UUID): StoredOfflineCommand? =
        jdbc
            .sql(
                """
                SELECT command_id, user_id, type, status, error_code, message, warnings::text AS warnings, occurred_at
                FROM shared.offline_command
                WHERE command_id = :id AND status <> 'PROCESSING'
                """.trimIndent(),
            ).param("id", commandId)
            .query { rs, _ -> toRecord(rs) }
            .optional()
            .orElse(null)

    override fun reserve(
        commandId: UUID,
        userId: UserId,
        type: String,
        occurredAt: Instant,
        retention: CommandRetention,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO shared.offline_command
                    (command_id, user_id, type, status, warnings, occurred_at, processed_at, retain_from)
                VALUES (:id, :userId, :type, 'PROCESSING', '[]'::jsonb, :occurredAt, :processedAt, :retainFrom)
                ON CONFLICT (command_id) DO NOTHING
                """.trimIndent(),
            ).param("id", commandId)
            .param("userId", userId.value)
            .param("type", type)
            .param("occurredAt", utc(occurredAt))
            .param("processedAt", utc(retention.processedAt))
            .param("retainFrom", utc(retention.retainFrom))
            .update() == 1

    override fun complete(
        commandId: UUID,
        status: OfflineCommandStatus,
        warnings: List<OfflineCommandWarning>,
    ) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE shared.offline_command
                    SET status = :status, warnings = CAST(:warnings AS jsonb)
                    WHERE command_id = :id AND status = 'PROCESSING'
                    """.trimIndent(),
                ).param("status", status.name)
                .param("warnings", warningsJson(warnings))
                .param("id", commandId)
                .update()
        check(updated == 1) { "Reserva do comando $commandId não encontrada" }
    }

    override fun insertRejected(
        record: StoredOfflineCommand,
        retention: CommandRetention,
    ): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO shared.offline_command
                    (command_id, user_id, type, status, error_code, message, warnings, occurred_at, processed_at,
                     retain_from)
                VALUES (:id, :userId, :type, 'REJECTED', :code, :message, CAST(:warnings AS jsonb), :occurredAt,
                        :processedAt, :retainFrom)
                ON CONFLICT (command_id) DO NOTHING
                """.trimIndent(),
            ).param("id", record.commandId)
            .param("userId", record.userId.value)
            .param("type", record.type)
            .param("code", record.code?.value)
            .param("message", record.message)
            .param("warnings", warningsJson(record.warnings))
            .param("occurredAt", record.occurredAt?.let(::utc))
            .param("processedAt", utc(retention.processedAt))
            .param("retainFrom", utc(retention.retainFrom))
            .update() == 1

    override fun expireResultsRetainedBefore(instant: Instant): Int =
        jdbc
            .sql(
                """
                UPDATE shared.offline_command
                SET status = 'EXPIRED', error_code = NULL, message = NULL, warnings = '[]'::jsonb, occurred_at = NULL
                WHERE retain_from < :instant AND status NOT IN ('EXPIRED', 'PROCESSING')
                """.trimIndent(),
            ).param("instant", utc(instant))
            .update()

    override fun deleteIdsRetainedBefore(instant: Instant): Int =
        jdbc
            .sql("DELETE FROM shared.offline_command WHERE retain_from < :instant AND status = 'EXPIRED'")
            .param("instant", utc(instant))
            .update()

    private fun toRecord(rs: ResultSet): StoredOfflineCommand {
        val purged = rs.getString("status") == EXPIRED
        return StoredOfflineCommand(
            commandId = rs.getObject("command_id", UUID::class.java),
            userId = UserId(rs.getObject("user_id", UUID::class.java)),
            type = rs.getString("type"),
            status =
                if (purged) {
                    OfflineCommandStatus.REJECTED
                } else {
                    OfflineCommandStatus.valueOf(
                        rs.getString("status"),
                    )
                },
            code = rs.getString("error_code")?.let(::ErrorCode),
            message = rs.getString("message"),
            warnings =
                jsonMapper.readValue(rs.getString("warnings"), WARNINGS_TYPE).map {
                    OfflineCommandWarning(ErrorCode(it.code), it.message)
                },
            occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java)?.toInstant(),
            purged = purged,
        )
    }

    private fun warningsJson(warnings: List<OfflineCommandWarning>): String =
        jsonMapper.writeValueAsString(warnings.map { StoredWarning(it.code.value, it.message) })

    private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    /** Um alerta como fica no `jsonb`. */
    private data class StoredWarning(
        val code: String,
        val message: String,
    )

    private companion object {
        val WARNINGS_TYPE = object : TypeReference<List<StoredWarning>>() {}

        /** O resultado saiu pela retenção; a linha ficou só com o id, o usuário e o tipo. */
        const val EXPIRED = "EXPIRED"
    }
}

/**
 * Expurgo dos comandos offline, sob trava do ShedLock (ARCHITECTURE §13): esvazia o resultado dos que
 * passaram de `zeny.sync.command-retention` (30 dias, contados de `retain_from`), deixando só o id, o usuário e
 * o tipo, e apaga os ids que passaram de `zeny.sync.command-id-retention` (365 dias). O reenvio de um id sem
 * resultado é `COMMAND_EXPIRED`, então o expurgo não deixa um comando ser aplicado de novo.
 */
@Component
class OfflineCommandPurgeJob(
    private val commands: OfflineCommandService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.sync.purge.interval}",
        initialDelayString = "\${zeny.sync.purge.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    fun purgeExpiredCommands() {
        val purged = commands.purgeExpired()
        if (purged.expiredResults > 0 || purged.removedIds > 0) {
            log.info(
                "Expurgo de comandos offline: {} resultados esvaziados, {} ids apagados",
                purged.expiredResults,
                purged.removedIds,
            )
        }
    }

    companion object {
        const val LOCK_NAME = "offline-commands-purge"
    }
}

@Configuration(proxyBeanMethods = false)
class OfflineSyncTablesConfiguration {
    /** Os *tombstones* só recebem `INSERT` do papel da aplicação. */
    @Bean
    fun sharedOfflineAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(TOMBSTONE_TABLE) }
}

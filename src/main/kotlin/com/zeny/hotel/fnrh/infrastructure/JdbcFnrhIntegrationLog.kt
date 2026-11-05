package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhCall
import com.zeny.hotel.fnrh.application.FnrhCallHistory
import com.zeny.hotel.fnrh.application.FnrhIntegrationLog
import com.zeny.hotel.fnrh.application.FnrhLastFailure
import com.zeny.hotel.shared.AppendOnlyTables
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * O registro das chamadas à FNRH (`fnrh.integration_call`, *append-only*, D-E07-8), gravado numa transação própria
 * (`REQUIRES_NEW`), no modelo do `JdbcPushDeliveryLog`: o chamador pode desfazer a dele, e a chamada ao Serpro já
 * aconteceu. Não guarda texto livre. Conta cada chamada em `zeny.fnrh.calls`.
 *
 * [record] nunca lança: um registro que falha não pode trocar o resultado do Serpro (a E08 repetiria um `POST`), então
 * só a classe da falha vai ao log. A linha de log da chamada em si é escrita pelo adaptador.
 */
@Component
class JdbcFnrhIntegrationLog(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val meters: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) : FnrhIntegrationLog {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun record(call: FnrhCall) {
        try {
            meters
                .counter(
                    FnrhMetrics.CALLS,
                    "operation",
                    call.operation,
                    "outcome",
                    call.outcome,
                    "environment",
                    call.environment.name,
                ).increment()
            ownTransaction.executeWithoutResult { insert(call) }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn(
                "FNRH {}: o registro da chamada falhou ({})",
                call.operation.take(MAX_LOGGED),
                e.javaClass.simpleName,
            )
        }
    }

    private fun insert(call: FnrhCall) {
        jdbc
            .sql(
                """
                INSERT INTO fnrh.integration_call
                    (operation, environment, outcome, http_status, duration_ms, correlation_ref, remote_id, occurred_at)
                VALUES (:operation, :environment, :outcome, :status, :duration, :correlation, :remote, :now)
                """.trimIndent(),
            ).param("operation", call.operation)
            .param("environment", call.environment.name)
            .param("outcome", call.outcome)
            .param("status", call.httpStatus)
            .param("duration", call.durationMs)
            .param("correlation", call.correlationRef)
            .param("remote", call.remoteId?.takeIf(REMOTE_ID::matches))
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(JdbcFnrhIntegrationLog::class.java)
        const val MAX_LOGGED = 40

        /** O id remoto vem do Serpro: o que foge deste formato vai como `NULL` (a tabela não aceita outro). */
        val REMOTE_ID = Regex("^[A-Za-z0-9._-]{1,100}$")
    }
}

/** A leitura do registro para o status e as métricas. */
@Component
class JdbcFnrhCallHistory(
    private val jdbc: JdbcClient,
) : FnrhCallHistory {
    override fun lastSuccessAt(environment: FnrhEnvironment): Instant? =
        jdbc
            .sql(
                "SELECT max(occurred_at) FROM fnrh.integration_call " +
                    "WHERE environment = :environment AND outcome = 'SUCCESS'",
            ).param("environment", environment.name)
            .query { rs, _ -> rs.instant(1) }
            .single()

    override fun lastFailure(environment: FnrhEnvironment): FnrhLastFailure? =
        jdbc
            .sql(
                "SELECT occurred_at, outcome FROM fnrh.integration_call " +
                    "WHERE environment = :environment AND outcome <> 'SUCCESS' " +
                    "ORDER BY occurred_at DESC, id DESC LIMIT 1",
            ).param("environment", environment.name)
            .query { rs, _ -> FnrhLastFailure(checkNotNull(rs.instant(1)), rs.getString(2)) }
            .optional()
            .orElse(null)

    private fun ResultSet.instant(column: Int): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}

/** As tabelas *append-only* do módulo, que a partida confere ([AppendOnlyTables]): o registro das chamadas. */
@Configuration(proxyBeanMethods = false)
class FnrhAppendOnlyTablesConfiguration {
    @Bean
    fun fnrhAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf("fnrh.integration_call") }
}

package com.zeny.hotel.printing.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Parâmetros da fila de impressão (`zeny.printing`, D-E10-4 a D-E10-6 e D-E10-10). Todos positivos; o teto da rota
 * manual não passa do teto geral. Um valor inválido impede a partida.
 */
@ConfigurationProperties("zeny.printing")
data class PrintingProperties(
    /** Teto do conteúdo pela `PrintingApi` (5 MiB). */
    val maxContentBytes: Int = DEFAULT_MAX_CONTENT_BYTES,
    /** Teto da rota manual `POST /print-jobs` (700 KiB): cabe no limite do corpo da idempotência com o base64. */
    val maxManualContentBytes: Int = DEFAULT_MAX_MANUAL_CONTENT_BYTES,
    /** Por quanto tempo o conteúdo fica depois do fim do job. */
    val contentRetention: Duration = ONE_DAY,
    /** Validade de um job `PENDING`. */
    val pendingTtl: Duration = ONE_DAY,
    /** Espera pela confirmação de um job `SENT`. */
    val ackTimeout: Duration = Duration.ofMinutes(ACK_TIMEOUT_MINUTES),
    /** Reivindicações de um job sem confirmação antes de `ACK_TIMEOUT`. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val longPollMaxWait: Duration = Duration.ofSeconds(LONG_POLL_SECONDS),
    val deliveryPollInterval: Duration = Duration.ofSeconds(1),
    val streamHeartbeat: Duration = Duration.ofSeconds(HEARTBEAT_SECONDS),
    val streamMaxDuration: Duration = Duration.ofMinutes(STREAM_MAX_MINUTES),
    val maxConnectionsPerAgent: Int = 2,
    val maintenanceInterval: Duration = Duration.ofMinutes(1),
    val maintenanceInitialDelay: Duration = Duration.ofMinutes(1),
) {
    init {
        positive("max-content-bytes", maxContentBytes > 0)
        positive("max-manual-content-bytes", maxManualContentBytes > 0)
        positive("max-attempts", maxAttempts > 0)
        positive("max-connections-per-agent", maxConnectionsPerAgent > 0)
        positive("content-retention", contentRetention.isPositive)
        positive("pending-ttl", pendingTtl.isPositive)
        positive("ack-timeout", ackTimeout.isPositive)
        positive("long-poll-max-wait", longPollMaxWait.isPositive)
        positive("delivery-poll-interval", deliveryPollInterval.isPositive)
        positive("stream-heartbeat", streamHeartbeat.isPositive)
        positive("stream-max-duration", streamMaxDuration.isPositive)
        positive("maintenance-interval", maintenanceInterval.isPositive)
        positive("maintenance-initial-delay", maintenanceInitialDelay.isPositive)
        require(maxManualContentBytes <= maxContentBytes) {
            "zeny.printing.max-manual-content-bytes não pode passar de max-content-bytes"
        }
    }

    private fun positive(
        name: String,
        ok: Boolean,
    ) = require(ok) { "zeny.printing.$name precisa ser positivo" }

    private companion object {
        const val DEFAULT_MAX_CONTENT_BYTES = 5_242_880
        const val DEFAULT_MAX_MANUAL_CONTENT_BYTES = 716_800
        const val ACK_TIMEOUT_MINUTES = 2L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val LONG_POLL_SECONDS = 30L
        const val HEARTBEAT_SECONDS = 15L
        const val STREAM_MAX_MINUTES = 30L
        val ONE_DAY: Duration = Duration.ofHours(24)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrintingProperties::class)
class PrintingConfiguration

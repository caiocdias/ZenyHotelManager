package com.zeny.hotel.shared.infrastructure.events

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Ciclo de vida do outbox (`shared.event_publication`, ARCHITECTURE §5 e §13, H13 do roadmap do M2).
 *
 * @property resubmission o reenvio das publicações com falha ([OutboxRetryPolicy]).
 * @property completedRetention por quanto tempo uma publicação concluída fica na tabela, contado da
 *   conclusão; depois, o [CompletedEventPublicationPurgeJob] a apaga. As incompletas nunca são apagadas.
 *
 * Os intervalos dos jobs (`resubmission.interval`, `resubmission.initial-delay`, `purge.*`) são lidos
 * pelo `@Scheduled`, não por esta classe.
 */
@ConfigurationProperties("zeny.events")
class OutboxProperties(
    val resubmission: Resubmission,
    val completedRetention: Duration,
) {
    init {
        require(completedRetention >= MIN_COMPLETED_RETENTION) {
            "zeny.events.completed-retention menor que $MIN_COMPLETED_RETENTION"
        }
    }

    /**
     * @property initialBackoff espera antes da 1ª nova tentativa, contada da publicação.
     * @property maxBackoff teto da espera, que dobra a cada tentativa.
     * @property giveUpAfter prazo, contado da publicação, depois do qual uma tentativa que falha deixa a
     *   publicação abandonada (continua `FAILED`, fora do reenvio automático).
     * @property batchSize quantas publicações vencidas, no máximo, cada execução do job reenvia.
     */
    class Resubmission(
        val initialBackoff: Duration,
        val maxBackoff: Duration,
        val giveUpAfter: Duration,
        val batchSize: Int,
    ) {
        init {
            require(initialBackoff.isPositive) { "zeny.events.resubmission.initial-backoff precisa ser positivo" }
            require(maxBackoff >= initialBackoff) {
                "zeny.events.resubmission.max-backoff menor que o initial-backoff"
            }
            require(giveUpAfter >= maxBackoff) { "zeny.events.resubmission.give-up-after menor que o max-backoff" }
            require(batchSize in 1..MAX_BATCH_SIZE) {
                "zeny.events.resubmission.batch-size fora de 1..$MAX_BATCH_SIZE"
            }
        }

        fun policy(): OutboxRetryPolicy = OutboxRetryPolicy(initialBackoff, maxBackoff, giveUpAfter)
    }

    private companion object {
        const val MAX_BATCH_SIZE = 10_000
        val MIN_COMPLETED_RETENTION: Duration = Duration.ofHours(1)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxConfiguration

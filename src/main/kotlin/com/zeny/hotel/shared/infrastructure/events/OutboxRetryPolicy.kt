package com.zeny.hotel.shared.infrastructure.events

import org.springframework.modulith.events.EventPublication
import java.time.Duration
import java.time.Instant

/**
 * Regra de reenvio das publicações `FAILED` do outbox (H13 do roadmap do M2): backoff exponencial e
 * abandono.
 *
 * - **Tentativas:** `completion_attempts` conta as entregas (a original grava 1 e cada reenvio soma 1) e
 *   `last_resubmission_date` guarda a do último reenvio (ao criar a linha, o Modulith grava nela a data da
 *   publicação; nula, de uma linha antiga, vale a publicação); os dois são gravados pelo Modulith, com o
 *   `Clock` da aplicação. O Modulith não grava a hora da falha, mas o listener roda logo depois da
 *   entrega, então essa data é a referência da espera.
 * - **Backoff:** depois de `n` reenvios (`n = completion_attempts - 1`), o próximo vence em
 *   `referência + min(initialBackoff × 2ⁿ, maxBackoff)` (padrão: 1, 2, 4, 8, 16, 32 min e depois 1 h).
 * - **Abandono:** a publicação `FAILED` cujo último reenvio aconteceu `giveUpAfter` ou mais depois da
 *   publicação está abandonada: houve ao menos uma tentativa depois do prazo e ela falhou. Só depende
 *   das colunas da linha, não do relógio, então nenhum caminho automático a desfaz (o Modulith só
 *   grava `last_resubmission_date` ao reenviar, e o job não reenvia abandonadas). Com os padrões, isso
 *   acontece no 29º reenvio (`completion_attempts` = 30), 24 h 3 min depois da publicação. O prazo conta
 *   da publicação: com a aplicação fora do ar, o tempo parado também conta (mas sempre há ao menos uma
 *   tentativa depois do prazo).
 *
 * A mesma condição de abandono está em SQL em [ABANDONED_CONDITION_SQL] (métricas e reenvio manual).
 */
class OutboxRetryPolicy(
    private val initialBackoff: Duration,
    private val maxBackoff: Duration,
    private val giveUpAfter: Duration,
) {
    /** Espera antes do próximo reenvio de quem já teve [resubmissions] reenvios. */
    fun backoff(resubmissions: Int): Duration {
        val exponent = resubmissions.coerceIn(0, MAX_EXPONENT)
        val delay = initialBackoff.multipliedBy(1L shl exponent)
        return if (delay > maxBackoff) maxBackoff else delay
    }

    fun isAbandoned(publication: EventPublication): Boolean =
        publication.status == EventPublication.Status.FAILED &&
            isAbandoned(publication.publicationDate, publication.lastResubmissionDate)

    fun isAbandoned(
        publishedAt: Instant,
        lastResubmittedAt: Instant?,
    ): Boolean = lastResubmittedAt != null && !lastResubmittedAt.isBefore(publishedAt.plus(giveUpAfter))

    /** Quando vence a próxima tentativa (a publicação pode estar abandonada: veja [isAbandoned]). */
    fun nextAttemptAt(publication: EventPublication): Instant =
        (publication.lastResubmissionDate ?: publication.publicationDate)
            .plus(backoff(publication.completionAttempts - 1))

    /** `FAILED`, não abandonada e com a próxima tentativa vencida em [now]. */
    fun isDue(
        publication: EventPublication,
        now: Instant,
    ): Boolean =
        publication.status == EventPublication.Status.FAILED &&
            !isAbandoned(publication) &&
            !now.isBefore(nextAttemptAt(publication))

    /** O prazo de abandono, para o parâmetro `:giveUpAfter` de [ABANDONED_CONDITION_SQL]. */
    fun giveUpAfterSqlInterval(): String = giveUpAfter.toString()

    companion object {
        /**
         * Condição de abandono sobre uma linha de `shared.event_publication`, com o parâmetro
         * `:giveUpAfter` (texto ISO 8601, como `PT24H`, de [giveUpAfterSqlInterval]).
         */
        const val ABANDONED_CONDITION_SQL =
            "status = 'FAILED' AND last_resubmission_date >= publication_date + CAST(:giveUpAfter AS interval)"

        private const val MAX_EXPONENT = 30
    }
}

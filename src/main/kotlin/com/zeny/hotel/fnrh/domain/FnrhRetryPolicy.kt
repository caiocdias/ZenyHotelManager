package com.zeny.hotel.fnrh.domain

import java.time.Duration

/**
 * A política de retry do envio à FNRH (D-E08-4): espera `initialBackoff × 2^(n−1)` até [maxBackoff], onde n é o
 * número de tentativas já feitas, e a [maxAttempts]-ésima tentativa com falha esgota a submissão.
 */
data class FnrhRetryPolicy(
    val maxAttempts: Int = 8,
    val initialBackoff: Duration = Duration.ofMinutes(1),
    val maxBackoff: Duration = Duration.ofHours(1),
) {
    /** A espera até a próxima tentativa, depois de [attempts] tentativas feitas (a partir de 1). */
    fun delayAfter(attempts: Int): Duration {
        val doublings = (attempts - 1).coerceIn(0, MAX_DOUBLINGS)
        val delay = initialBackoff.multipliedBy(1L shl doublings)
        return if (delay > maxBackoff) maxBackoff else delay
    }

    private companion object {
        /** Evita estouro do deslocamento: 2^20 minutos já passa de qualquer teto razoável. */
        const val MAX_DOUBLINGS = 20
    }
}

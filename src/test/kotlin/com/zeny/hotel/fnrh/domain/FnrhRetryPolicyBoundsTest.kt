package com.zeny.hotel.fnrh.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class FnrhRetryPolicyBoundsTest {
    private val policy = FnrhRetryPolicy()

    @Test
    fun `a espera nunca diminui nem passa do teto`() {
        var previous = Duration.ZERO
        for (n in 1..200) {
            val d = policy.delayAfter(n)
            assertTrue(d >= previous, "n=$n")
            assertTrue(d <= policy.maxBackoff, "n=$n")
            previous = d
        }
    }

    @Test
    fun `a primeira espera e o backoff inicial e a de zero tentativas nao e menor que ele`() {
        assertEquals(Duration.ofMinutes(1), policy.delayAfter(1))
        assertEquals(Duration.ofMinutes(1), policy.delayAfter(0))
        assertEquals(Duration.ofMinutes(1), policy.delayAfter(-5))
    }

    @Test
    fun `o teto que nao e potencia de dois corta a curva exatamente nele`() {
        val custom =
            FnrhRetryPolicy(maxAttempts = 8, initialBackoff = Duration.ofMinutes(1), maxBackoff = Duration.ofMinutes(5))

        assertEquals(listOf(1L, 2L, 4L, 5L, 5L), (1..5).map { custom.delayAfter(it).toMinutes() })
    }

    @Test
    fun `backoff inicial acima do teto devolve o teto`() {
        val custom = FnrhRetryPolicy(initialBackoff = Duration.ofHours(2), maxBackoff = Duration.ofHours(1))

        assertEquals(Duration.ofHours(1), custom.delayAfter(1))
    }

    @Test
    fun `a curva do contrato soma cerca de duas horas ate a oitava tentativa`() {
        val total = (1..7).fold(Duration.ZERO) { acc, n -> acc + policy.delayAfter(n) }

        assertEquals(Duration.ofMinutes(123), total)
    }
}

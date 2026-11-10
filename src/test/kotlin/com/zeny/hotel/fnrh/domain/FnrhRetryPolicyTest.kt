package com.zeny.hotel.fnrh.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class FnrhRetryPolicyTest {
    private val policy = FnrhRetryPolicy()

    @Test
    fun `a espera dobra a cada tentativa a partir de um minuto ate uma hora`() {
        val minutes = (1..7).map { policy.delayAfter(it).toMinutes() }

        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L), minutes)
    }

    @Test
    fun `muitas tentativas nao passam do teto nem estouram`() {
        assertEquals(Duration.ofHours(1), policy.delayAfter(20))
        assertEquals(Duration.ofHours(1), policy.delayAfter(10_000))
    }

    @Test
    fun `os padroes sao 8 tentativas, 1 minuto e 1 hora`() {
        assertEquals(8, policy.maxAttempts)
        assertEquals(Duration.ofMinutes(1), policy.initialBackoff)
        assertEquals(Duration.ofHours(1), policy.maxBackoff)
    }
}

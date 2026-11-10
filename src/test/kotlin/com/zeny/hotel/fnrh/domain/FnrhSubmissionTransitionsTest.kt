package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.shared.ConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/** Ataques às transições de [FnrhSubmission]: terminais, fronteira temporária x permanente e limites do retry. */
class FnrhSubmissionTransitionsTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val policy = FnrhRetryPolicy()
    private val guestId = UUID.randomUUID()

    private fun submission() =
        FnrhSubmission.create(
            reservationId = UUID.randomUUID(),
            reservationCode = "RES-1",
            operation = FnrhOperation.CHECK_IN,
            eventKey = "k",
            causalVersion = 3,
            performedBy = null,
            occurredAt = now,
            now = now,
        )

    private fun error(code: String = "REQUIRED") = FnrhSubmissionError(guestId, "birthDate", code)

    private fun terminals(): Map<String, FnrhSubmission> =
        mapOf(
            "SENT" to submission().also { it.markSent(now) },
            "CANCELLED" to submission().also { it.cancel(now) },
            "DISMISSED" to
                submission().also {
                    it.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, now)
                    it.dismiss(now)
                },
        )

    @Test
    fun `cada falha temporaria so esgota na oitava tentativa`() {
        for (failure in FnrhSubmissionFailure.entries.filter { it.temporary }) {
            val s = submission()
            repeat(7) { assertFalse(s.recordFailure(failure, now, policy), "$failure tentativa ${it + 1}") }
            assertEquals(FnrhSubmissionStatus.PENDING, s.status)
            assertTrue(s.recordFailure(failure, now, policy), "$failure oitava")
            assertEquals(FnrhSubmissionStatus.FAILED, s.status)
        }
    }

    @Test
    fun `cada falha permanente esgota na primeira tentativa, inclusive as locais`() {
        for (failure in FnrhSubmissionFailure.entries.filter { !it.temporary }) {
            val s = submission()
            assertTrue(s.recordFailure(failure, now, policy), "$failure")
            assertEquals(FnrhSubmissionStatus.FAILED, s.status)
            assertEquals(1, s.attempts)
            assertEquals(failure, s.lastFailure)
        }
    }

    @Test
    fun `o limite acompanha a politica recebida`() {
        val strict = FnrhRetryPolicy(maxAttempts = 1)
        val s = submission()

        assertTrue(s.recordFailure(FnrhSubmissionFailure.UNAVAILABLE, now, strict))
        assertEquals(FnrhSubmissionStatus.FAILED, s.status)

        val three = FnrhRetryPolicy(maxAttempts = 3)
        val t = submission()
        assertFalse(t.recordFailure(FnrhSubmissionFailure.UNAVAILABLE, now, three))
        assertFalse(t.recordFailure(FnrhSubmissionFailure.UNAVAILABLE, now, three))
        assertTrue(t.recordFailure(FnrhSubmissionFailure.UNAVAILABLE, now, three))
    }

    @Test
    fun `o reagendamento usa a espera da tentativa recem contada`() {
        val s = submission()
        val expected = listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L)

        expected.forEach { minutes ->
            s.recordFailure(FnrhSubmissionFailure.UNKNOWN_OUTCOME, now, policy)
            assertEquals(now.plusSeconds(minutes * 60), s.nextAttemptAt)
        }
    }

    @Test
    fun `falha temporaria nao muda nextAttemptAt quando esgota`() {
        val s = submission()
        repeat(7) { s.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, policy) }
        val before = s.nextAttemptAt

        s.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now.plusSeconds(999), policy)

        assertEquals(before, s.nextAttemptAt)
    }

    @Test
    fun `depois do retry a contagem recomeca e a oitava falha esgota de novo`() {
        val s = submission()
        repeat(8) { s.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, policy) }
        assertEquals(FnrhSubmissionStatus.FAILED, s.status)

        s.retry(now)
        repeat(7) { assertFalse(s.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, policy)) }
        assertEquals(FnrhSubmissionStatus.PENDING, s.status)
        assertTrue(s.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, policy))
    }

    @Test
    fun `fail nao conta tentativa e deixa a submissao vencida`() {
        val s = submission()

        s.fail(FnrhSubmissionFailure.REMOTE_CANCELLED, now.plusSeconds(10))

        assertEquals(0, s.attempts)
        assertEquals(now.plusSeconds(10), s.nextAttemptAt)
    }

    @Test
    fun `a marca de escrita em curso sobrevive a fail, cancel e dismiss`() {
        val a = submission().also { it.markInFlight() }
        a.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, now)
        assertTrue(a.inFlight)
        a.dismiss(now)
        assertTrue(a.inFlight)

        val b = submission().also { it.markInFlight() }
        b.cancel(now)
        assertTrue(b.inFlight)
    }

    @Test
    fun `retry de FAILED com escrita em curso e tentativas esgotadas preserva a marca e zera o resto`() {
        val s = submission().also { it.markInFlight() }
        repeat(8) { s.recordFailure(FnrhSubmissionFailure.UNAVAILABLE, now, policy) }

        s.retry(now.plusSeconds(5))

        assertTrue(s.inFlight)
        assertEquals(0, s.attempts)
        assertEquals(now.plusSeconds(5), s.nextAttemptAt)
        assertEquals(FnrhSubmissionStatus.PENDING, s.status)
    }

    @Test
    fun `markInvalid duas vezes substitui os erros e nao acumula`() {
        val s = submission()
        s.markInvalid(listOf(error("A"), error("B")), now)

        s.markInvalid(listOf(error("C")), now)

        assertEquals(listOf(error("C")), s.errors)
    }

    @Test
    fun `os erros guardados nao mudam quando a lista de origem muda`() {
        val source = mutableListOf(error("A"))
        val s = submission()
        s.markInvalid(source, now)

        source.add(error("B"))

        assertEquals(listOf(error("A")), s.errors)
    }

    @Test
    fun `markInvalid segura a fila com o instante informado e nao conta tentativa`() {
        val s = submission()

        s.markInvalid(listOf(error()), now.plusSeconds(7))

        assertEquals(FnrhSubmissionStatus.INVALID, s.status)
        assertEquals(0, s.attempts)
        assertEquals(now.plusSeconds(7), s.nextAttemptAt)
        assertFalse(s.isTerminal)
    }

    @Test
    fun `dismiss e cancel nao zeram a ultima falha nem os erros`() {
        val f = submission()
        f.recordFailure(FnrhSubmissionFailure.FORBIDDEN, now, policy)
        f.dismiss(now)
        assertEquals(FnrhSubmissionFailure.FORBIDDEN, f.lastFailure)

        val i = submission()
        i.markInvalid(listOf(error()), now)
        i.dismiss(now)
        assertEquals(listOf(error()), i.errors)
    }

    @Test
    fun `de cada estado terminal retry e dismiss respondem 409 e nada muda`() {
        for ((name, s) in terminals()) {
            val retry = assertThrows<ConflictException>(name) { s.retry(now.plusSeconds(1)) }
            assertEquals(FnrhErrorCodes.SUBMISSION_NOT_RETRYABLE, retry.code, name)
            val dismiss = assertThrows<ConflictException>(name) { s.dismiss(now.plusSeconds(1)) }
            assertEquals(FnrhErrorCodes.SUBMISSION_NOT_DISMISSABLE, dismiss.code, name)
            assertEquals(name, s.status.name)
            assertTrue(s.isTerminal, name)
        }
    }

    @Test
    fun `de cada estado terminal as demais transicoes falham sem alterar nenhum campo`() {
        val actions: Map<String, (FnrhSubmission) -> Unit> =
            mapOf(
                "markInFlight" to { it.markInFlight() },
                "clearInFlight" to { it.clearInFlight() },
                "markSent" to { it.markSent(now.plusSeconds(9)) },
                "recordFailure temporaria" to
                    { it.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now.plusSeconds(9), policy) },
                "recordFailure permanente" to
                    { it.recordFailure(FnrhSubmissionFailure.VALIDATION, now.plusSeconds(9), policy) },
                "fail" to { it.fail(FnrhSubmissionFailure.REMOTE_CANCELLED, now.plusSeconds(9)) },
                "markInvalid" to { it.markInvalid(listOf(error()), now.plusSeconds(9)) },
                "cancel" to { it.cancel(now.plusSeconds(9)) },
            )
        for ((terminal, _) in terminals()) {
            for ((actionName, action) in actions) {
                val s = terminals().getValue(terminal)
                val before =
                    listOf(s.status, s.attempts, s.nextAttemptAt, s.lastFailure, s.inFlight, s.sentAt, s.errors)

                assertThrows<IllegalStateException>("$terminal/$actionName") { action(s) }

                val after = listOf(s.status, s.attempts, s.nextAttemptAt, s.lastFailure, s.inFlight, s.sentAt, s.errors)
                assertEquals(before, after, "$terminal/$actionName")
            }
        }
    }

    @Test
    fun `markSent e cancel valem de INVALID e de FAILED, que nao sao terminais`() {
        val f = submission().also { it.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, now) }
        f.markInFlight()
        f.clearInFlight()
        assertFalse(f.isTerminal)

        val i = submission().also { it.markInvalid(listOf(error()), now) }
        i.cancel(now)
        assertEquals(FnrhSubmissionStatus.CANCELLED, i.status)
    }

    @Test
    fun `retry de PENDING deixa a submissao intacta`() {
        val s = submission()
        s.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, policy)
        val attempts = s.attempts
        val next = s.nextAttemptAt

        assertThrows<ConflictException> { s.retry(now.plusSeconds(99)) }

        assertEquals(attempts, s.attempts)
        assertEquals(next, s.nextAttemptAt)
        assertEquals(FnrhSubmissionFailure.SERVER_ERROR, s.lastFailure)
    }

    @Test
    fun `dismiss de PENDING deixa a submissao intacta`() {
        val s = submission()

        assertThrows<ConflictException> { s.dismiss(now) }

        assertEquals(FnrhSubmissionStatus.PENDING, s.status)
    }

    @Test
    fun `toString nao traz dado em nenhum estado`() {
        val s = submission()
        s.markInvalid(listOf(FnrhSubmissionError(guestId, "cpf", "INVALID_FORMAT")), now)
        for (text in listOf(s.toString(), submission().also { it.cancel(now) }.toString())) {
            assertFalse(text.contains("RES-1"))
            assertFalse(text.contains("cpf"))
            assertFalse(text.contains("INVALID_FORMAT"))
            assertFalse(text.contains(guestId.toString()))
        }
    }
}

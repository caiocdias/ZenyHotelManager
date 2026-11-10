package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.fnrh.FnrhErrorCodes
import com.zeny.hotel.shared.ConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class FnrhSubmissionTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val policy = FnrhRetryPolicy()
    private val guestId = UUID.randomUUID()

    private fun submission(operation: FnrhOperation = FnrhOperation.REGISTER_RESERVATION) =
        FnrhSubmission.create(
            reservationId = UUID.randomUUID(),
            reservationCode = "RES-SECRETO-123",
            operation = operation,
            eventKey = "created",
            causalVersion = 0,
            performedBy = null,
            occurredAt = now.minusSeconds(5),
            now = now,
        )

    private fun invalid(s: FnrhSubmission) =
        s.markInvalid(listOf(FnrhSubmissionError(guestId, "birthDate", "REQUIRED")), now)

    private fun failed(): FnrhSubmission = submission().also { it.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, now) }

    @Test
    fun `create deixa pendente, sem tentativas, vencida e sem escrita em curso`() {
        val s = submission(FnrhOperation.CHECK_IN)

        assertEquals(FnrhSubmissionStatus.PENDING, s.status)
        assertEquals(0, s.attempts)
        assertEquals(now, s.nextAttemptAt)
        assertEquals(now, s.createdAt)
        assertEquals(now.minusSeconds(5), s.occurredAt)
        assertFalse(s.inFlight)
        assertNull(s.sequence)
        assertNull(s.sentAt)
        assertNull(s.lastFailure)
        assertTrue(s.errors.isEmpty())
        assertFalse(s.isTerminal)
    }

    @Test
    fun `o posto da operacao segue a ordem causal`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), FnrhOperation.entries.map { it.rank })
        assertEquals(
            listOf(
                FnrhOperation.REGISTER_RESERVATION,
                FnrhOperation.UPDATE_RESERVATION,
                FnrhOperation.CANCEL_RESERVATION,
                FnrhOperation.CHECK_IN,
                FnrhOperation.UNDO_CHECK_IN,
                FnrhOperation.CHECK_OUT,
            ),
            FnrhOperation.entries,
        )
    }

    @Test
    fun `falha temporaria reagenda ate a oitava tentativa e entao falha`() {
        for (failure in listOf(FnrhSubmissionFailure.SERVER_ERROR, FnrhSubmissionFailure.INTERNAL_ERROR)) {
            val s = submission()
            repeat(7) {
                val later = now.plusSeconds(it.toLong())
                assertFalse(s.recordFailure(failure, later, policy))
                assertEquals(FnrhSubmissionStatus.PENDING, s.status)
                assertEquals(later.plus(policy.delayAfter(it + 1)), s.nextAttemptAt)
            }
            assertEquals(7, s.attempts)
            assertEquals(now.plusSeconds(6).plus(policy.delayAfter(7)), s.nextAttemptAt)

            assertTrue(s.recordFailure(failure, now, policy))
            assertEquals(FnrhSubmissionStatus.FAILED, s.status)
            assertEquals(failure, s.lastFailure)
            assertEquals(8, s.attempts)
        }
    }

    @Test
    fun `falha permanente vira FAILED na primeira tentativa`() {
        for (failure in listOf(
            FnrhSubmissionFailure.VALIDATION,
            FnrhSubmissionFailure.UNAUTHORIZED,
            FnrhSubmissionFailure.FORBIDDEN,
            FnrhSubmissionFailure.NOT_FOUND,
        )) {
            val s = submission()

            assertTrue(s.recordFailure(failure, now, policy))
            assertEquals(FnrhSubmissionStatus.FAILED, s.status)
            assertEquals(failure, s.lastFailure)
            assertEquals(1, s.attempts)
        }
    }

    @Test
    fun `so as quatro falhas citadas sao temporarias`() {
        val temporary = FnrhSubmissionFailure.entries.filter { it.temporary }.toSet()

        assertEquals(
            setOf(
                FnrhSubmissionFailure.SERVER_ERROR,
                FnrhSubmissionFailure.UNAVAILABLE,
                FnrhSubmissionFailure.UNKNOWN_OUTCOME,
                FnrhSubmissionFailure.INTERNAL_ERROR,
            ),
            temporary,
        )
    }

    @Test
    fun `fail e permanente e local`() {
        val s = submission()

        s.fail(FnrhSubmissionFailure.REMOTE_CANCELLED, now.plusSeconds(1))

        assertEquals(FnrhSubmissionStatus.FAILED, s.status)
        assertEquals(FnrhSubmissionFailure.REMOTE_CANCELLED, s.lastFailure)
    }

    @Test
    fun `a marca de escrita em curso sobrevive a falha, invalidacao e retry`() {
        val a = submission().also { it.markInFlight() }
        assertTrue(a.inFlight)
        a.recordFailure(FnrhSubmissionFailure.UNKNOWN_OUTCOME, now, policy)
        assertTrue(a.inFlight)
        a.recordFailure(FnrhSubmissionFailure.VALIDATION, now, policy)
        assertEquals(FnrhSubmissionStatus.FAILED, a.status)
        assertTrue(a.inFlight)
        a.retry(now)
        assertTrue(a.inFlight)

        val b = submission().also { it.markInFlight() }
        invalid(b)
        assertTrue(b.inFlight)
        b.retry(now)
        assertTrue(b.inFlight)

        val c = submission().also { it.markInFlight() }
        c.clearInFlight()
        assertFalse(c.inFlight)

        val d = submission().also { it.markInFlight() }
        d.markSent(now)
        assertFalse(d.inFlight)
    }

    @Test
    fun `markSent grava o instante e termina`() {
        val s = submission()

        s.markSent(now.plusSeconds(3))

        assertEquals(FnrhSubmissionStatus.SENT, s.status)
        assertEquals(now.plusSeconds(3), s.sentAt)
        assertTrue(s.isTerminal)
    }

    @Test
    fun `markInvalid guarda os erros e retry volta de FAILED e de INVALID zerando tudo`() {
        val s = submission(FnrhOperation.CHECK_IN)
        invalid(s)
        assertEquals(FnrhSubmissionStatus.INVALID, s.status)
        assertEquals(listOf(FnrhSubmissionError(guestId, "birthDate", "REQUIRED")), s.errors)
        assertFalse(s.isTerminal)

        val later = now.plusSeconds(60)
        s.retry(later)
        assertEquals(FnrhSubmissionStatus.PENDING, s.status)
        assertEquals(0, s.attempts)
        assertTrue(s.errors.isEmpty())
        assertNull(s.lastFailure)
        assertEquals(later, s.nextAttemptAt)

        val f = submission()
        f.recordFailure(FnrhSubmissionFailure.VALIDATION, now, policy)
        f.retry(later)
        assertEquals(FnrhSubmissionStatus.PENDING, f.status)
        assertEquals(0, f.attempts)
        assertNull(f.lastFailure)
        assertEquals(later, f.nextAttemptAt)
    }

    @Test
    fun `retry so vale para FAILED e INVALID`() {
        val pending = submission()
        val sent = submission().also { it.markSent(now) }
        val cancelled = submission().also { it.cancel(now) }
        val dismissed = failed().also { it.dismiss(now) }

        for (s in listOf(pending, sent, cancelled, dismissed)) {
            val ex = assertThrows<ConflictException> { s.retry(now) }
            assertEquals(FnrhErrorCodes.SUBMISSION_NOT_RETRYABLE, ex.code)
        }
    }

    @Test
    fun `dismiss so vale para FAILED e INVALID e termina`() {
        val f = failed().also { it.dismiss(now) }
        assertEquals(FnrhSubmissionStatus.DISMISSED, f.status)
        assertTrue(f.isTerminal)

        val i = submission().also { invalid(it) }.also { it.dismiss(now) }
        assertEquals(FnrhSubmissionStatus.DISMISSED, i.status)

        val pending = submission()
        val sent = submission().also { it.markSent(now) }
        val cancelled = submission().also { it.cancel(now) }
        val dismissed = failed().also { it.dismiss(now) }
        for (s in listOf(pending, sent, cancelled, dismissed)) {
            val ex = assertThrows<ConflictException> { s.dismiss(now) }
            assertEquals(FnrhErrorCodes.SUBMISSION_NOT_DISMISSABLE, ex.code)
        }
    }

    @Test
    fun `cancel termina e pode vir de qualquer estado nao terminal`() {
        val p = submission().also { it.cancel(now) }
        assertEquals(FnrhSubmissionStatus.CANCELLED, p.status)
        assertTrue(p.isTerminal)
        assertNotNull(failed().also { it.cancel(now) })
    }

    @Test
    fun `estado terminal recusa as outras transicoes`() {
        val terminals =
            listOf(
                { submission().also { it.markSent(now) } },
                { submission().also { it.cancel(now) } },
                { failed().also { it.dismiss(now) } },
            )
        for (make in terminals) {
            val actions: List<(FnrhSubmission) -> Unit> =
                listOf(
                    { it.markInFlight() },
                    { it.clearInFlight() },
                    { it.markSent(now) },
                    { it.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, policy) },
                    { it.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, now) },
                    { invalid(it) },
                    { it.cancel(now) },
                )
            for (action in actions) {
                assertThrows<IllegalStateException> { action(make()) }
            }
        }
    }

    @Test
    fun `toString nao traz o codigo da reserva nem os erros`() {
        val s = submission(FnrhOperation.CHECK_IN)
        invalid(s)

        val text = s.toString()

        assertFalse(text.contains("RES-SECRETO-123"))
        assertFalse(text.contains("birthDate"))
        assertFalse(text.contains(guestId.toString()))
        assertTrue(text.contains(s.id.toString()))
    }
}

package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/** O cancelamento condicional da submissão sem escrita em curso (M5 E08, Task 3, D-E08-8 e I-1 da revisão). */
@IntegrationTest
class FnrhSubmissionCancelIfIdleTest(
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val tx: TransactionTemplate,
) {
    private val now: Instant = Instant.parse("2026-11-10T12:00:00Z")

    private fun record(prepare: (FnrhSubmission) -> Unit = {}): UUID {
        val s =
            FnrhSubmission.create(
                UUID.randomUUID(),
                "R-TEST",
                FnrhOperation.CHECK_IN,
                "checked-in:v1",
                1,
                null,
                now,
                now,
            )
        store.recordIfAbsent(s) shouldBe true
        tx.executeWithoutResult {
            val loaded = checkNotNull(store.find(s.id))
            prepare(loaded)
            store.save(loaded)
        }
        return s.id
    }

    private fun status(id: UUID) = checkNotNull(store.find(id)).status

    @Test
    fun `it cancels a pending, a failed and an invalid submission without a write in flight`() {
        val pending = record()
        val failed = record { it.fail(FnrhSubmissionFailure.VALIDATION, now) }
        val invalid = record { it.markInvalid(emptyList(), now) }

        listOf(pending, failed, invalid).map { store.cancelIfIdle(it, now) } shouldBe listOf(true, true, true)

        listOf(pending, failed, invalid).map(::status) shouldBe List(3) { FnrhSubmissionStatus.CANCELLED }
    }

    @Test
    fun `it does not cancel a submission whose write became in flight after it was read`() {
        val id = record()
        val read = checkNotNull(store.find(id))
        read.inFlight shouldBe false

        store.markInFlight(id)

        store.cancelIfIdle(id, now) shouldBe false
        status(id) shouldBe FnrhSubmissionStatus.PENDING
    }

    @Test
    fun `it does not cancel a sent submission`() {
        val id = record { it.markSent(now) }

        store.cancelIfIdle(id, now) shouldBe false

        status(id) shouldBe FnrhSubmissionStatus.SENT
    }
}

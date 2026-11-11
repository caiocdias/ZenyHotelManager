package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteGuest
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhRemoteReservation
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhRetryPolicy
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Persistência da submissão à FNRH e dos ids remotos (M5 E08, Task 2). */
@IntegrationTest
class FnrhSubmissionPersistenceTest(
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val tx: TransactionTemplate,
) {
    private val now: Instant = Instant.parse("2026-11-10T12:00:00Z")

    private fun submission(
        reservationId: UUID,
        operation: FnrhOperation,
        version: Long,
        key: String = "e$version",
        createdAt: Instant = now,
    ) = FnrhSubmission.create(
        reservationId = reservationId,
        reservationCode = "R-TEST",
        operation = operation,
        eventKey = key,
        causalVersion = version,
        performedBy = null,
        occurredAt = now,
        now = createdAt,
    )

    private fun record(
        reservationId: UUID,
        operation: FnrhOperation,
        version: Long,
        key: String = "e$version",
        createdAt: Instant = now,
    ): UUID {
        val s = submission(reservationId, operation, version, key, createdAt)
        store.recordIfAbsent(s) shouldBe true
        return s.id
    }

    private fun update(
        id: UUID,
        change: (FnrhSubmission) -> Unit,
    ) = tx.executeWithoutResult {
        val loaded = checkNotNull(store.find(id))
        change(loaded)
        store.save(loaded)
    }

    private fun heads(reservationId: UUID) = store.dueHeads(now, 100, reservationId)

    @Test
    fun `recordIfAbsent stores once per reservation, operation and event key`() {
        val reservation = UuidV7.generate()
        val first = submission(reservation, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val again = submission(reservation, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        store.recordIfAbsent(first) shouldBe true
        store.recordIfAbsent(again) shouldBe false

        jdbc.queryForObject(
            "SELECT count(*) FROM fnrh.submission WHERE reservation_id = ?",
            Long::class.java,
            reservation,
        ) shouldBe
            1L
    }

    @Test
    fun `the mapping reads back the generated sequence, the rank and the lazy errors inside a transaction`() {
        val reservation = UuidV7.generate()
        val id = record(reservation, FnrhOperation.CHECK_IN, 3)
        val guest = UuidV7.generate()
        val errors =
            listOf(
                FnrhSubmissionError(guest, "birthDate", "REQUIRED"),
                FnrhSubmissionError(guest, "birthDate", "INVALID"),
                FnrhSubmissionError(guest, "nationality", "REQUIRED"),
            )

        // lida numa transação, alterada fora dela e salva noutra (entidade destacada)
        val detached = tx.execute { checkNotNull(store.find(id)).also { s -> s.errors.size } }
        detached.markInvalid(errors, now)
        tx.executeWithoutResult { store.save(detached) }

        tx.executeWithoutResult {
            val read = checkNotNull(store.find(id))
            read.sequence.shouldNotBeNull() shouldBeGreaterThan 0L
            read.status shouldBe FnrhSubmissionStatus.INVALID
            read.errors shouldContainExactlyInAnyOrder errors
            read.reservationCode shouldBe "R-TEST"
        }
        jdbc.queryForObject("SELECT operation_rank FROM fnrh.submission WHERE id = ?", Int::class.java, id) shouldBe 3
        jdbc.queryForObject(
            "SELECT count(*) FROM fnrh.submission_error WHERE submission_id = ?",
            Long::class.java,
            id,
        ) shouldBe
            3L
    }

    @Test
    fun `retry removes the stored errors`() {
        val id = record(UuidV7.generate(), FnrhOperation.CHECK_IN, 3)
        tx.executeWithoutResult {
            val s = checkNotNull(store.find(id))
            s.markInvalid(listOf(FnrhSubmissionError(UuidV7.generate(), "x", "REQUIRED")), now)
            store.save(s)
        }

        tx.executeWithoutResult {
            val s = checkNotNull(store.find(id))
            s.retry(now)
            store.save(s)
        }

        jdbc.queryForObject(
            "SELECT count(*) FROM fnrh.submission_error WHERE submission_id = ?",
            Long::class.java,
            id,
        ) shouldBe
            0L
        tx.executeWithoutResult { checkNotNull(store.find(id)).errors.shouldBeEmpty() }
    }

    @Test
    fun `dueHeads follows the causal order whatever the insertion order`() {
        val reservation = UuidV7.generate()
        val checkOut = record(reservation, FnrhOperation.CHECK_OUT, 5)
        val checkIn = record(reservation, FnrhOperation.CHECK_IN, 3)
        val register = record(reservation, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        heads(reservation) shouldContainExactly listOf(register)

        update(register) { it.markSent(now) }
        heads(reservation) shouldContainExactly listOf(checkIn)

        update(checkIn) { it.recordFailure(FnrhSubmissionFailure.VALIDATION, now, FnrhRetryPolicy()) }
        heads(reservation).shouldBeEmpty()

        update(checkIn) { it.dismiss() }
        heads(reservation) shouldContainExactly listOf(checkOut)
    }

    @Test
    fun `on the same version the registration comes before the check-in`() {
        val reservation = UuidV7.generate()
        val checkIn = record(reservation, FnrhOperation.CHECK_IN, 0, "walkin-in")
        val register = record(reservation, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        heads(reservation) shouldContainExactly listOf(register)
        checkIn shouldNotBe register
    }

    @Test
    fun `dueHeads skips a future attempt, filters by reservation and respects the limit`() {
        val future = UuidV7.generate()
        val futureId = record(future, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        update(futureId) { it.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, FnrhRetryPolicy()) }
        heads(future).shouldBeEmpty()

        val a = UuidV7.generate()
        val b = UuidV7.generate()
        val idA = record(a, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val idB = record(b, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        heads(a) shouldContainExactly listOf(idA)
        val all = store.dueHeads(now.plus(Duration.ofDays(1)), 1000)
        all.containsAll(listOf(idA, idB, futureId)) shouldBe true
        store.dueHeads(now.plus(Duration.ofDays(1)), 1) shouldHaveSize 1
    }

    @Test
    fun `unresolvedOf, page and countByStatus read the queue`() {
        val reservation = UuidV7.generate()
        val open = record(reservation, FnrhOperation.CHECK_IN, 3, "k1")
        val sent = record(reservation, FnrhOperation.CHECK_IN, 4, "k2")
        update(sent) { it.markSent(now) }
        record(reservation, FnrhOperation.CHECK_OUT, 5)

        store.unresolvedOf(reservation, FnrhOperation.CHECK_IN).map { it.id } shouldContainExactly listOf(open)

        val pending = store.page(FnrhSubmissionStatus.PENDING, null, 1000)
        pending.map { it.id }.contains(open) shouldBe true
        val first = store.page(null, null, 1)
        first shouldHaveSize 1
        val next = store.page(null, first.single().sequence, 1)
        next.single().sequence!! shouldBeGreaterThan first.single().sequence!!
        (store.countByStatus()[FnrhSubmissionStatus.SENT] ?: 0L) shouldBeGreaterThan 0L
    }

    @Test
    fun `markInFlight is written even when the caller's transaction is rolled back`() {
        val id = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, 0, "created")

        shouldThrow<IllegalStateException> {
            tx.executeWithoutResult {
                store.markInFlight(id)
                error("o chamador desfaz")
            }
        }

        inFlight(id) shouldBe true
    }

    @Test
    fun `a status change saved afterwards keeps the in-flight mark`() {
        val id = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val loaded = tx.execute { checkNotNull(store.find(id)) }
        store.markInFlight(id)

        loaded.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, FnrhRetryPolicy())
        tx.executeWithoutResult { store.save(loaded) }

        inFlight(id) shouldBe true
    }

    @Test
    fun `marking the submission as sent clears the in-flight mark`() {
        val id = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, 0, "created")
        store.markInFlight(id)

        tx.executeWithoutResult {
            val s = checkNotNull(store.find(id))
            s.markSent(now)
            store.save(s)
        }

        inFlight(id) shouldBe false
    }

    @Test
    fun `saving a remote id clears the in-flight mark in the same transaction`() {
        val reservation = UuidV7.generate()
        val guest = UuidV7.generate()
        val id = record(reservation, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        store.markInFlight(id)
        remoteIds.saveReservation(remoteReservation(reservation), id)
        inFlight(id) shouldBe false

        store.markInFlight(id)
        remoteIds.saveGuest(remoteGuest(reservation, guest), id)
        inFlight(id) shouldBe false
    }

    @Test
    fun `reservation and guest ids are stored per environment and the link is encrypted`() {
        val reservation = UuidV7.generate()
        val guest = UuidV7.generate()
        val id = record(reservation, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        remoteIds.saveReservation(remoteReservation(reservation, link = "https://pre.example/abc"), id)
        remoteIds.saveGuest(remoteGuest(reservation, guest), id)

        val read = remoteIds.reservation(reservation, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull()
        read.remoteId shouldBe FnrhRemoteId("res-1")
        read.precheckinLink shouldBe "https://pre.example/abc"
        read.toString() shouldNotContain "pre.example"
        remoteIds.reservation(reservation, FnrhEnvironment.PRODUCTION).shouldBeNull()
        val stored =
            jdbc.queryForObject(
                "SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = ?",
                String::class.java,
                reservation,
            )!!
        stored shouldStartWith "v1:"
        stored shouldNotContain "pre.example"

        remoteIds.guests(reservation, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("g-1")
        remoteIds.guests(reservation, FnrhEnvironment.PRODUCTION).shouldBeEmpty()

        remoteIds.saveReservation(remoteReservation(reservation, link = null, cancelled = true), id)
        remoteIds.reservation(reservation, FnrhEnvironment.HOMOLOGATION)!!.let {
            it.cancelled shouldBe true
            it.precheckinLink.shouldBeNull()
        }
    }

    @Test
    fun `knownPerson finds the person of the same guest in another reservation of the same environment only`() {
        val guest = UuidV7.generate()
        val other = UuidV7.generate()
        val id = record(other, FnrhOperation.CHECK_IN, 3)
        remoteIds.saveGuest(remoteGuest(other, guest, person = "p-77"), id)

        remoteIds.knownPerson(guest, FnrhEnvironment.HOMOLOGATION) shouldBe FnrhRemoteId("p-77")
        remoteIds.knownPerson(guest, FnrhEnvironment.PRODUCTION).shouldBeNull()
        remoteIds.knownPerson(UuidV7.generate(), FnrhEnvironment.HOMOLOGATION).shouldBeNull()
    }

    @Test
    fun `the schema has no column for documents, race, disability, names or messages`() {
        val columns =
            jdbc
                .queryForList(
                    "SELECT table_name || '.' || column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'fnrh' AND table_name IN " +
                        "('submission', 'submission_error', 'remote_reservation', 'remote_guest')",
                    String::class.java,
                ).filterNotNull()

        columns.shouldNotBeEmpty()
        columns
            .filter { c ->
                listOf("document", "race", "disability", "name", "message").any { c.substringAfter('.').contains(it) }
            }.shouldBeEmpty()
    }

    private fun inFlight(id: UUID) =
        jdbc.queryForObject("SELECT in_flight FROM fnrh.submission WHERE id = ?", Boolean::class.java, id)

    private fun remoteReservation(
        reservation: UUID,
        link: String? = null,
        cancelled: Boolean = false,
    ) = FnrhRemoteReservation(reservation, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId("res-1"), link, cancelled)

    private fun remoteGuest(
        reservation: UUID,
        guest: UUID,
        person: String = "p-1",
    ) = FnrhRemoteGuest(
        reservation,
        guest,
        FnrhEnvironment.HOMOLOGATION,
        FnrhRemoteId(person),
        FnrhRemoteId("g-1"),
        false,
    )

    private fun <T> Collection<T>.shouldNotBeEmpty() = (isNotEmpty()) shouldBe true
}

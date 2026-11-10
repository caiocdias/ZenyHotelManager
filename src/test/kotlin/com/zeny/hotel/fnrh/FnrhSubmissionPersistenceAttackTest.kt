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
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ataques à persistência da submissão à FNRH e dos ids remotos (M5 E08, Task 2). Ids próprios: banco compartilhado. */
@IntegrationTest
class FnrhSubmissionPersistenceAttackTest(
    @Autowired private val store: FnrhSubmissionStore,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val tx: TransactionTemplate,
) {
    private val now: Instant = Instant.parse("2026-11-10T12:00:00Z")
    private val later: Instant = now.plus(Duration.ofDays(30))

    private fun build(
        reservation: UUID,
        operation: FnrhOperation,
        version: Long,
        key: String = "e$version",
    ) = FnrhSubmission.create(reservation, "R-ATK", operation, key, version, null, now, now)

    private fun record(
        reservation: UUID,
        operation: FnrhOperation,
        version: Long,
        key: String = "e$version",
    ): UUID {
        val s = build(reservation, operation, version, key)
        store.recordIfAbsent(s) shouldBe true
        return s.id
    }

    private fun change(
        id: UUID,
        action: (FnrhSubmission) -> Unit,
    ) = tx.executeWithoutResult {
        val s = checkNotNull(store.find(id))
        action(s)
        store.save(s)
    }

    private fun heads(reservation: UUID) = store.dueHeads(later, 100, reservation)

    private fun inFlight(id: UUID) =
        jdbc.queryForObject("SELECT in_flight FROM fnrh.submission WHERE id = ?", Boolean::class.java, id)

    private fun reservationIds(
        reservation: UUID,
        link: String? = null,
        env: FnrhEnvironment = FnrhEnvironment.HOMOLOGATION,
    ) = FnrhRemoteReservation(reservation, env, FnrhRemoteId("res"), link, false)

    private fun guestIds(
        reservation: UUID,
        guest: UUID,
        env: FnrhEnvironment = FnrhEnvironment.HOMOLOGATION,
        checkedOut: Boolean = false,
        person: String = "p",
    ) = FnrhRemoteGuest(reservation, guest, env, FnrhRemoteId(person), FnrhRemoteId("g"), checkedOut)

    @Test
    fun `a pending head is the only submission of its reservation returned even with many pending behind it`() {
        val r = UuidV7.generate()
        val first = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        record(r, FnrhOperation.UPDATE_RESERVATION, 1)
        record(r, FnrhOperation.CHECK_IN, 2)
        record(r, FnrhOperation.CHECK_OUT, 3)

        heads(r) shouldContainExactly listOf(first)
    }

    @Test
    fun `a lower version wins over a higher rank regardless of insertion order`() {
        val r = UuidV7.generate()
        record(r, FnrhOperation.CHECK_OUT, 9)
        val update = record(r, FnrhOperation.UPDATE_RESERVATION, 4)
        record(r, FnrhOperation.CANCEL_RESERVATION, 6)

        heads(r) shouldContainExactly listOf(update)
    }

    @Test
    fun `on equal versions the rank orders the walk-in chain register then check-in then check-out`() {
        val r = UuidV7.generate()
        val out = record(r, FnrhOperation.CHECK_OUT, 0, "k-out")
        val inn = record(r, FnrhOperation.CHECK_IN, 0, "k-in")
        val reg = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        heads(r) shouldContainExactly listOf(reg)
        change(reg) { it.markSent(now) }
        heads(r) shouldContainExactly listOf(inn)
        change(inn) { it.markSent(now) }
        heads(r) shouldContainExactly listOf(out)
    }

    @Test
    fun `on equal version and rank the sequence breaks the tie in insertion order`() {
        val r = UuidV7.generate()
        val a = record(r, FnrhOperation.UPDATE_RESERVATION, 2, "a")
        record(r, FnrhOperation.UPDATE_RESERVATION, 2, "b")

        heads(r) shouldContainExactly listOf(a)
    }

    @Test
    fun `an invalid head holds the queue until it is dismissed`() {
        val r = UuidV7.generate()
        val inn = record(r, FnrhOperation.CHECK_IN, 1)
        val out = record(r, FnrhOperation.CHECK_OUT, 2)
        change(inn) { it.markInvalid(listOf(FnrhSubmissionError(UuidV7.generate(), "f", "REQUIRED")), now) }

        heads(r).shouldBeEmpty()
        change(inn) { it.dismiss(now) }
        heads(r) shouldContainExactly listOf(out)
    }

    @Test
    fun `a failed head holds the queue and a retry releases it back as the head`() {
        val r = UuidV7.generate()
        val reg = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        record(r, FnrhOperation.CHECK_IN, 1)
        change(reg) { it.recordFailure(FnrhSubmissionFailure.VALIDATION, now, FnrhRetryPolicy()) }
        heads(r).shouldBeEmpty()

        change(reg) { it.retry(now) }
        heads(r) shouldContainExactly listOf(reg)
    }

    @Test
    fun `a cancelled submission does not hold the queue`() {
        val r = UuidV7.generate()
        val reg = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val inn = record(r, FnrhOperation.CHECK_IN, 1)
        change(reg) { it.cancel(now) }

        heads(r) shouldContainExactly listOf(inn)
    }

    @Test
    fun `a pending head not yet due blocks the later ones that are due`() {
        val r = UuidV7.generate()
        val reg = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        record(r, FnrhOperation.CHECK_IN, 1)
        change(reg) { it.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, FnrhRetryPolicy()) }

        store.dueHeads(now, 100, r).shouldBeEmpty()
        heads(r) shouldContainExactly listOf(reg)
    }

    @Test
    fun `a head due exactly at now is returned`() {
        val r = UuidV7.generate()
        val reg = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        store.dueHeads(now, 100, r) shouldContainExactly listOf(reg)
        store.dueHeads(now.minusMillis(1), 100, r).shouldBeEmpty()
    }

    @Test
    fun `a reservation with only terminal submissions has no head`() {
        val r = UuidV7.generate()
        val reg = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        change(reg) { it.markSent(now) }

        heads(r).shouldBeEmpty()
        store.dueHeads(later, 100, UuidV7.generate()).shouldBeEmpty()
    }

    @Test
    fun `heads of several reservations come ordered by the sequence of each head and respect the limit`() {
        val a = UuidV7.generate()
        val b = UuidV7.generate()
        val c = UuidV7.generate()
        record(a, FnrhOperation.CHECK_OUT, 5) // a chega primeiro, mas a cabeça de a entra depois
        val headB = record(b, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val headA = record(a, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val headC = record(c, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val mine = setOf(headA, headB, headC)

        val all = store.dueHeads(later, 100_000).filter { it in mine }
        all shouldContainExactly listOf(headB, headA, headC)
        store.dueHeads(later, 100_000).count { it == headA } shouldBe 1
        store.dueHeads(later, 2).size shouldBe 2
        store.dueHeads(later, 0).shouldBeEmpty()
        store.dueHeads(later, 100_000, b) shouldContainExactly listOf(headB)
    }

    @Test
    fun `markInFlight on one submission does not touch the others of the reservation`() {
        val r = UuidV7.generate()
        val a = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val b = record(r, FnrhOperation.CHECK_IN, 1)

        store.markInFlight(a)

        inFlight(a) shouldBe true
        inFlight(b) shouldBe false
    }

    @Test
    fun `saving a remote id clears only the in-flight mark of the given submission`() {
        val r = UuidV7.generate()
        val a = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val b = record(r, FnrhOperation.CHECK_IN, 1)
        store.markInFlight(a)
        store.markInFlight(b)

        remoteIds.saveReservation(reservationIds(r), a)
        inFlight(a) shouldBe false
        inFlight(b) shouldBe true

        remoteIds.saveGuest(guestIds(r, UuidV7.generate()), b)
        inFlight(b) shouldBe false
    }

    @Test
    fun `saving a remote id survives and the in-flight mark is cleared even if the caller transaction rolls back`() {
        val r = UuidV7.generate()
        val a = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        store.markInFlight(a)

        shouldThrow<IllegalStateException> {
            tx.executeWithoutResult {
                remoteIds.saveReservation(reservationIds(r), a)
                error("o chamador desfaz")
            }
        }

        inFlight(a) shouldBe false
        remoteIds.reservation(r, FnrhEnvironment.HOMOLOGATION).shouldNotBeNull()
    }

    @Test
    fun `a remote id saved for an unrelated submission leaves the in-flight mark of this one`() {
        val r = UuidV7.generate()
        val mine = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        store.markInFlight(mine)

        remoteIds.saveReservation(reservationIds(r), UuidV7.generate())

        inFlight(mine) shouldBe true
    }

    @Test
    fun `retry and a failure save preserve the in-flight mark and never turn it on`() {
        val r = UuidV7.generate()
        val marked = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val plain = record(r, FnrhOperation.CHECK_IN, 1)
        store.markInFlight(marked)

        change(marked) { it.recordFailure(FnrhSubmissionFailure.VALIDATION, now, FnrhRetryPolicy()) }
        inFlight(marked) shouldBe true
        change(marked) { it.retry(now) }
        inFlight(marked) shouldBe true

        change(plain) { it.recordFailure(FnrhSubmissionFailure.VALIDATION, now, FnrhRetryPolicy()) }
        change(plain) { it.retry(now) }
        inFlight(plain) shouldBe false
    }

    @Test
    fun `an in-memory in-flight change on the entity is not persisted by save`() {
        val id = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, 0, "created")

        change(id) { it.markInFlight() }
        inFlight(id) shouldBe false

        store.markInFlight(id)
        change(id) { it.clearInFlight() }
        inFlight(id) shouldBe true
    }

    @Test
    fun `the in-flight mark is read back by find`() {
        val id = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, 0, "created")
        store.markInFlight(id)

        tx.execute { checkNotNull(store.find(id)).inFlight } shouldBe true
    }

    @Test
    fun `saving a submission loaded before another writer changed it fails on the version`() {
        val id = record(UuidV7.generate(), FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val stale = tx.execute { checkNotNull(store.find(id)) }
        change(id) { it.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, FnrhRetryPolicy()) }

        stale.recordFailure(FnrhSubmissionFailure.SERVER_ERROR, now, FnrhRetryPolicy())
        shouldThrow<OptimisticLockingFailureException> { tx.executeWithoutResult { store.save(stale) } }
    }

    @Test
    fun `recordIfAbsent concurrently with the same key stores one row and reports one winner`() {
        val r = UuidV7.generate()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val results =
                (1..threads).map {
                    pool.submit<Boolean> {
                        val s = build(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
                        start.await()
                        store.recordIfAbsent(s)
                    }
                }
            start.countDown()
            val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }

            outcomes.count { it } shouldBe 1
            jdbc.queryForObject(
                "SELECT count(*) FROM fnrh.submission WHERE reservation_id = ?",
                Long::class.java,
                r,
            ) shouldBe 1L
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `recordIfAbsent keeps the first row untouched when the same key comes again with other data`() {
        val r = UuidV7.generate()
        val first = build(r, FnrhOperation.CHECK_IN, 3, "k")
        store.recordIfAbsent(first) shouldBe true
        val other = FnrhSubmission.create(r, "R-OTHER", FnrhOperation.CHECK_IN, "k", 99, null, now, now)

        store.recordIfAbsent(other) shouldBe false

        tx.execute { checkNotNull(store.find(first.id)) }.let {
            it.reservationCode shouldBe "R-ATK"
            it.causalVersion shouldBe 3L
        }
        store.find(other.id).shouldBeNull()
    }

    @Test
    fun `the same event key is accepted for another operation or another reservation`() {
        val r = UuidV7.generate()
        store.recordIfAbsent(build(r, FnrhOperation.CHECK_IN, 3, "k")) shouldBe true
        store.recordIfAbsent(build(r, FnrhOperation.CHECK_OUT, 3, "k")) shouldBe true
        store.recordIfAbsent(build(UuidV7.generate(), FnrhOperation.CHECK_IN, 3, "k")) shouldBe true
    }

    @Test
    fun `an event recorded after its original was sent is still deduplicated`() {
        val r = UuidV7.generate()
        val id = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        change(id) { it.markSent(now) }

        store.recordIfAbsent(build(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")) shouldBe false
    }

    @Test
    fun `the precheckin link round-trips with unicode and a long query string, encrypted in the column`() {
        val r = UuidV7.generate()
        val id = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        val link = "https://pre.example/ação?token=" + "é".repeat(600) + "&x=1"

        remoteIds.saveReservation(reservationIds(r, link), id)

        remoteIds.reservation(r, FnrhEnvironment.HOMOLOGATION)!!.precheckinLink shouldBe link
        val stored =
            jdbc.queryForObject(
                "SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = ?",
                String::class.java,
                r,
            )!!
        stored shouldStartWith "v1:"
        stored shouldNotContain "pre.example"
        stored shouldNotContain "token"
    }

    @Test
    fun `an absent precheckin link is null in the column and null when read`() {
        val r = UuidV7.generate()
        val id = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")

        remoteIds.saveReservation(reservationIds(r, null), id)

        jdbc
            .queryForObject(
                "SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = ?",
                String::class.java,
                r,
            ).shouldBeNull()
        remoteIds.reservation(r, FnrhEnvironment.HOMOLOGATION)!!.precheckinLink.shouldBeNull()
    }

    @Test
    fun `the same link stored for two reservations yields different ciphertexts`() {
        val a = UuidV7.generate()
        val b = UuidV7.generate()
        val id = record(a, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        remoteIds.saveReservation(reservationIds(a, "https://pre.example/same"), id)
        remoteIds.saveReservation(reservationIds(b, "https://pre.example/same"), id)

        val stored =
            listOf(a, b).map {
                jdbc.queryForObject(
                    "SELECT precheckin_link FROM fnrh.remote_reservation WHERE reservation_id = ?",
                    String::class.java,
                    it,
                )
            }
        (stored[0] == stored[1]) shouldBe false
    }

    @Test
    fun `toString of the remote reservation hides the link and of the submission hides the code`() {
        val r = UuidV7.generate()
        val text = reservationIds(r, "https://secret.example/x").toString()
        text shouldNotContain "secret.example"
        text shouldNotContain "precheckinLink"

        build(r, FnrhOperation.CHECK_IN, 1).toString() shouldNotContain "R-ATK"
    }

    @Test
    fun `saving a reservation again updates it in place and the environments stay independent`() {
        val r = UuidV7.generate()
        val id = record(r, FnrhOperation.REGISTER_RESERVATION, 0, "created")
        remoteIds.saveReservation(reservationIds(r, "https://a.example/1"), id)
        remoteIds.saveReservation(reservationIds(r, "https://b.example/2", FnrhEnvironment.PRODUCTION), id)
        remoteIds.saveReservation(reservationIds(r, null).copy(cancelled = true), id)

        remoteIds.reservation(r, FnrhEnvironment.HOMOLOGATION)!!.let {
            it.cancelled shouldBe true
            it.precheckinLink.shouldBeNull()
        }
        remoteIds.reservation(r, FnrhEnvironment.PRODUCTION)!!.precheckinLink shouldBe "https://b.example/2"
    }

    @Test
    fun `saving a guest again updates the check-out progress without duplicating and per guest`() {
        val r = UuidV7.generate()
        val g1 = UuidV7.generate()
        val g2 = UuidV7.generate()
        val id = record(r, FnrhOperation.CHECK_IN, 1)
        remoteIds.saveGuest(guestIds(r, g1), id)
        remoteIds.saveGuest(guestIds(r, g2), id)
        remoteIds.saveGuest(guestIds(r, g1, checkedOut = true), id)

        val guests = remoteIds.guests(r, FnrhEnvironment.HOMOLOGATION).associateBy { it.guestId }
        guests.size shouldBe 2
        guests.getValue(g1).checkedOut shouldBe true
        guests.getValue(g2).checkedOut shouldBe false
    }

    @Test
    fun `knownPerson is deterministic with two reservations and ignores the other guest`() {
        val guest = UuidV7.generate()
        val other = UuidV7.generate()
        val r1 = UuidV7.generate()
        val r2 = UuidV7.generate()
        val id = record(r1, FnrhOperation.CHECK_IN, 1)
        remoteIds.saveGuest(guestIds(r1, guest, person = "p-same"), id)
        remoteIds.saveGuest(guestIds(r2, guest, person = "p-same"), id)
        remoteIds.saveGuest(guestIds(r1, other, person = "p-other"), id)

        remoteIds.knownPerson(guest, FnrhEnvironment.HOMOLOGATION) shouldBe FnrhRemoteId("p-same")
        remoteIds.knownPerson(other, FnrhEnvironment.HOMOLOGATION) shouldBe FnrhRemoteId("p-other")
    }

    @Test
    fun `the database rejects values outside the checked domains`() {
        val r = UuidV7.generate()
        val id = record(r, FnrhOperation.CHECK_IN, 1)

        fun rejected(sql: String) = shouldThrow<DataIntegrityViolationException> { jdbc.update(sql, id) }
        rejected("UPDATE fnrh.submission SET status = 'BOGUS' WHERE id = ?")
        rejected("UPDATE fnrh.submission SET operation = 'BOGUS' WHERE id = ?")
        rejected("UPDATE fnrh.submission SET causal_version = -1 WHERE id = ?")
        rejected("UPDATE fnrh.submission SET attempts = -1 WHERE id = ?")
        rejected("UPDATE fnrh.submission SET last_failure = 'free text from the remote' WHERE id = ?")
        shouldThrow<DataAccessException> { jdbc.update("UPDATE fnrh.submission SET sequence = 1 WHERE id = ?", id) }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO fnrh.remote_reservation (reservation_id, environment, remote_id, cancelled) " +
                    "VALUES (?, 'STAGING', 'x', false)",
                r,
            )
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO fnrh.submission_error (submission_id, guest_id, field, code) VALUES (?, ?, 'f', 'c')",
                UuidV7.generate(),
                UuidV7.generate(),
            )
        }
    }

    @Test
    fun `no column of the four tables has a text type that could hold a document or free text beyond the known ones`() {
        val texts =
            jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns " +
                    "WHERE table_schema = 'fnrh' AND table_name IN " +
                    "('submission', 'submission_error', 'remote_reservation', 'remote_guest') " +
                    "AND data_type IN ('text', 'character varying')",
                String::class.java,
            )

        texts.filterNotNull().sorted() shouldContainExactly
            listOf(
                "remote_guest.environment",
                "remote_guest.guest_remote_id",
                "remote_guest.person_id",
                "remote_reservation.environment",
                "remote_reservation.precheckin_link",
                "remote_reservation.remote_id",
                "submission.event_key",
                "submission.last_failure",
                "submission.operation",
                "submission.reservation_code",
                "submission.status",
                "submission_error.code",
                "submission_error.field",
            )
    }
}

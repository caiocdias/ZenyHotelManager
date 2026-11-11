package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionError
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Pendências, reenvio e encerramento das submissões à FNRH pela API (M5 E08, Task 11; D-E08-12 e 16): permissões, o
 * filtro por situação, a privacidade do corpo, o efeito do `retry` e do `dismiss` na fila e a trilha.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhSubmissionsApiTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired reservationsApi: ReservationsApi,
    @Autowired sender: FnrhSubmissionSender,
    @Autowired store: FnrhSubmissionStore,
    @Autowired remoteIds: FnrhRemoteIds,
    @Autowired clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired tx: TransactionTemplate,
    @Autowired roomsApi: RoomsApi,
) : FnrhAlertTestBase(
        iam,
        jdbc,
        hotelApi,
        reservationsApi,
        sender,
        store,
        remoteIds,
        clock,
        mockMvc,
        jsonMapper,
        tx,
        roomsApi,
    ) {
    private val base = "/api/v1/fnrh/submissions"

    @BeforeEach
    fun enable() {
        iam.enableFnrh()
    }

    private fun fail(id: UUID) =
        tx.executeWithoutResult {
            val submission = checkNotNull(store.find(id))
            submission.fail(FnrhSubmissionFailure.NO_REMOTE_OPERATION, clock.instant())
            store.save(submission)
        }

    private fun invalidate(
        id: UUID,
        guest: UUID,
    ) = tx.executeWithoutResult {
        val submission = checkNotNull(store.find(id))
        submission.markInvalid(listOf(FnrhSubmissionError(guest, "document", "REQUIRED")), clock.instant())
        store.save(submission)
    }

    private fun send(id: UUID) =
        tx.executeWithoutResult {
            val submission = checkNotNull(store.find(id))
            submission.markSent(clock.instant())
            store.save(submission)
        }

    private fun JsonNode.ids(): List<String> = this["items"].values().map { it["id"].asString() }

    private fun attempted(reservation: UUID): Int =
        sender.sendDueFor(reservation).let { it.sent + it.postponed + it.failed + it.invalid }

    @Test
    fun `without the permission all three endpoints answer 403`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            fail(id)
            val nobody = createUser()
            val reader = user(FnrhPermissions.SUBMISSIONS_READ)

            call(GET, base, asTestUser(nobody)).expectStatus(403)
            call(POST, "$base/$id/retry", asTestUser(nobody)).expectStatus(403)
            call(POST, "$base/$id/dismiss", asTestUser(nobody)).expectStatus(403)
            call(GET, base, asTestUser(reader)).expectStatus(200)
            call(POST, "$base/$id/retry", asTestUser(reader)).expectStatus(403)
            call(POST, "$base/$id/dismiss", asTestUser(reader)).expectStatus(403)

            row(id).status shouldBe FnrhSubmissionStatus.FAILED
        }

    @Test
    fun `the list filters by status, shows the fields and leaks no guest data nor the pre-check-in link`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val checkIn = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            send(register)
            invalidate(checkIn, guest.id)
            val reader = user(FnrhPermissions.SUBMISSIONS_READ)

            val response = call(GET, "$base?status=INVALID&limit=200", asTestUser(reader))
            val body = response.ok()

            body.ids() shouldContain checkIn.toString()
            body.ids() shouldNotContain register.toString()
            body["items"].values().map { it["status"].asString() }.toSet() shouldContainExactly setOf("INVALID")
            val item = body["items"].values().single { it["id"].asString() == checkIn.toString() }
            item["reservationId"].asString() shouldBe reservation.toString()
            item["reservationCode"].asString() shouldBe codeOf(reservation)
            item["operation"].asString() shouldBe "CHECK_IN"
            item["attempts"].asInt() shouldBe 0
            item["lastFailure"].isNull shouldBe true
            item["errors"]
                .values()
                .map {
                    Triple(
                        it["guestId"].asString(),
                        it["field"].asString(),
                        it["code"].asString(),
                    )
                }.shouldContainExactly(Triple(guest.id.toString(), "document", "REQUIRED"))
            val text = response.getContentAsString(Charsets.UTF_8)
            guest.leaks.forEach { text shouldNotContain it }
            text.lowercase() shouldNotContain "precheckin"
            text.lowercase() shouldNotContain "link"
        }

    @Test
    fun `the list is paginated by cursor`(): Unit =
        with(iam) {
            val reservation = reservation()
            val first = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val second = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            fail(first)
            fail(second)

            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val url = "$base?status=FAILED&limit=1" + cursor?.let { "&cursor=$it" }.orEmpty()
                val page = call(GET, url, asAdmin()).ok()
                seen += page.ids()
                cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            } while (cursor != null)

            seen shouldContain first.toString()
            seen shouldContain second.toString()
            seen.indexOf(first.toString()) shouldBe seen.indexOf(second.toString()) - 1
        }

    @Test
    fun `retry of a FAILED submission puts it back in the queue with no attempts`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            fail(id)
            attempted(reservation) shouldBe 0
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

            val item = call(POST, "$base/$id/retry", asTestUser(manager)).ok()

            item["id"].asString() shouldBe id.toString()
            item["status"].asString() shouldBe "PENDING"
            item["attempts"].asInt() shouldBe 0
            item["lastFailure"].isNull shouldBe true
            row(id).status shouldBe FnrhSubmissionStatus.PENDING
            attempted(reservation) shouldBe 1
        }

    @Test
    fun `retry of an INVALID submission clears the errors`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val id = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            invalidate(id, guest.id)
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

            val item = call(POST, "$base/$id/retry", asTestUser(manager)).ok()

            item["status"].asString() shouldBe "PENDING"
            item["errors"].values().toList().shouldBeEmpty()
        }

    @Test
    fun `retry of a PENDING or SENT submission is a 409 and an unknown id a 404`(): Unit =
        with(iam) {
            val reservation = reservation()
            val pending = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val sent = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            send(sent)

            call(POST, "$base/$pending/retry", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_RETRYABLE")
            call(POST, "$base/$sent/retry", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_RETRYABLE")
            call(POST, "$base/${UUID.randomUUID()}/retry", asAdmin()).problem(404, "FNRH_SUBMISSION_NOT_FOUND")
            row(pending).status shouldBe FnrhSubmissionStatus.PENDING
        }

    @Test
    fun `dismiss of a FAILED submission releases the next one of the reservation`(): Unit =
        with(iam) {
            val reservation = reservation()
            val head = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val next = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            fail(head)
            attempted(reservation) shouldBe 0
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

            val item = call(POST, "$base/$head/dismiss", asTestUser(manager)).ok()

            item["status"].asString() shouldBe "DISMISSED"
            attempted(reservation) shouldBe 1
            row(next).attempts shouldBe 1
        }

    @Test
    fun `dismiss of a SENT or PENDING submission is a 409 and an unknown id a 404`(): Unit =
        with(iam) {
            val reservation = reservation()
            val pending = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val sent = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            send(sent)

            call(POST, "$base/$sent/dismiss", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_DISMISSABLE")
            call(POST, "$base/$pending/dismiss", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_DISMISSABLE")
            call(POST, "$base/${UUID.randomUUID()}/dismiss", asAdmin()).problem(404, "FNRH_SUBMISSION_NOT_FOUND")
            row(sent).status shouldBe FnrhSubmissionStatus.SENT
        }

    @Test
    fun `the trail records retry and dismiss with the user and no guest data`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val retried = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            val dismissed = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            invalidate(retried, guest.id)
            fail(dismissed)
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

            call(POST, "$base/$retried/retry", asTestUser(manager)).ok()
            call(POST, "$base/$dismissed/dismiss", asTestUser(manager)).ok()

            for (
            (action, id, after) in
            listOf(
                Triple("fnrh.submission.retry", retried, "PENDING"),
                Triple("fnrh.submission.dismiss", dismissed, "DISMISSED"),
            )
            ) {
                val entry = auditOf(action, id)
                entry.user shouldBe manager.id.value
                entry.before shouldContain "\"status\""
                entry.after shouldContain after
                guest.leaks.forEach {
                    entry.before shouldNotContain it
                    entry.after shouldNotContain it
                }
                entry.after shouldNotContain codeOf(reservation)
            }
        }

    @Test
    fun `a rejected retry is not recorded as a change`(): Unit =
        with(iam) {
            val reservation = reservation()
            val pending = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id

            call(POST, "$base/$pending/retry", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_RETRYABLE")

            val changed =
                jdbc
                    .sql(
                        "SELECT count(*) FROM audit.audit_entry WHERE action = 'fnrh.submission.retry' " +
                            "AND entity_id = :id AND outcome = 'SUCCEEDED'",
                    ).param("id", pending.toString())
                    .query(Long::class.java)
                    .single()
            changed shouldBe 0L
        }

    private data class AuditRow(
        val user: UUID?,
        val before: String,
        val after: String,
    )

    private fun auditOf(
        action: String,
        id: UUID,
    ): AuditRow =
        jdbc
            .sql(
                "SELECT user_id, CAST(before_state AS text) AS b, CAST(after_state AS text) AS a " +
                    "FROM audit.audit_entry " +
                    "WHERE action = :action AND entity_id = :id AND outcome = 'SUCCEEDED'",
            ).param("action", action)
            .param("id", id.toString())
            .query {
                rs,
                _,
                ->
                AuditRow(rs.getObject("user_id", UUID::class.java), rs.getString("b"), rs.getString("a"))
            }.single()
}

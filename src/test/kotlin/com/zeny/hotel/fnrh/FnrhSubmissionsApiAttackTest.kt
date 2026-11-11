package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhRemoteReservation
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Ataques do testador ao contrato público da fila da FNRH (M5 E08, Task 11; D-E08-12 e 16): 401 e papéis reais,
 * privacidade do corpo (inclusive do `retry` e do `dismiss`), paginação e filtro, todas as transições de estado,
 * a marca da escrita em curso, a repetição sem efeito duplo, a trilha e o contrato.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList", "LargeClass") // as portas do fluxo real; um teste por comportamento
class FnrhSubmissionsApiAttackTest(
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
    private val web = mockMvc
    private val ids = remoteIds

    @BeforeEach
    fun enable() {
        iam.enableFnrh()
    }

    /** Força o estado no banco, como um envio anterior o deixaria (o teste não depende do Serpro). */
    private fun force(
        id: UUID,
        status: FnrhSubmissionStatus,
        attempts: Int = 0,
        failure: String? = null,
        inFlight: Boolean = false,
    ) {
        jdbc
            .sql(
                "UPDATE fnrh.submission SET status = :status, attempts = :attempts, last_failure = :failure, " +
                    "in_flight = :inFlight, next_attempt_at = :now WHERE id = :id",
            ).param("status", status.name)
            .param("attempts", attempts)
            .param("failure", failure)
            .param("inFlight", inFlight)
            .param("now", java.sql.Timestamp.from(clock.instant()))
            .param("id", id)
            .update()
    }

    private fun addError(
        id: UUID,
        guest: UUID,
        field: String = "document",
    ) {
        jdbc
            .sql("INSERT INTO fnrh.submission_error (submission_id, guest_id, field, code) VALUES (:s, :g, :f, :c)")
            .param("s", id)
            .param("g", guest)
            .param("f", field)
            .param("c", "REQUIRED")
            .update()
    }

    private fun errorCount(id: UUID): Long =
        jdbc
            .sql("SELECT count(*) FROM fnrh.submission_error WHERE submission_id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private fun sequenceOf(id: UUID): Long =
        jdbc
            .sql("SELECT sequence FROM fnrh.submission WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private fun auditCount(
        action: String,
        id: UUID,
        outcome: String = "SUCCEEDED",
    ): Long =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE action = :action AND entity_id = :id " +
                    "AND outcome = :outcome",
            ).param("action", action)
            .param("id", id.toString())
            .param("outcome", outcome)
            .query(Long::class.java)
            .single()

    private fun withTemplateRole(roleName: String): TestUser {
        val roleId =
            jdbc
                .sql("SELECT id FROM iam.role WHERE name = :name AND active")
                .param("name", roleName)
                .query(UUID::class.java)
                .single()
        return iam.createUser().also { iam.assignRole(it, RoleId(roleId)) }
    }

    /** O cursor logo antes da submissão: o banco compartilhado tem linhas de outros testes antes dela. */
    private fun from(id: UUID): String = PageCursor.of((sequenceOf(id) - 1).toString()).encode()

    private fun JsonNode.itemIds(): List<String> = this["items"].values().map { it["id"].asString() }

    private fun status(
        method: HttpMethod,
        path: String,
    ): Int =
        when (method) {
            GET -> web.get(path)
            else -> web.post(path)
        }.andReturn().response.status

    // --- Autorização -----------------------------------------------------------------------------------------------

    @Test
    fun `anonymous callers get 401 on the list, the retry and the dismiss`() {
        val id = UUID.randomUUID()

        status(GET, base) shouldBe 401
        status(POST, "$base/$id/retry") shouldBe 401
        status(POST, "$base/$id/dismiss") shouldBe 401
    }

    @Test
    fun `the real Receptionist reads but cannot retry or dismiss, the real Manager can`(): Unit =
        with(iam) {
            val reservation = reservation()
            val first = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val second = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            force(first, FnrhSubmissionStatus.FAILED, attempts = 3, failure = "SERVER_ERROR")
            force(second, FnrhSubmissionStatus.FAILED, attempts = 2, failure = "UNAVAILABLE")
            val receptionist = asTestUser(withTemplateRole("Recepcionista"))
            val manager = asTestUser(withTemplateRole("Gerente"))
            val attendant = asTestUser(withTemplateRole("Camareira"))

            call(GET, base, receptionist).expectStatus(200)
            call(POST, "$base/$first/retry", receptionist).problem(403, "PERMISSION_DENIED")
            call(POST, "$base/$first/dismiss", receptionist).problem(403, "PERMISSION_DENIED")
            call(GET, base, attendant).problem(403, "PERMISSION_DENIED")
            call(POST, "$base/$first/retry", attendant).problem(403, "PERMISSION_DENIED")
            row(first).status shouldBe FnrhSubmissionStatus.FAILED
            row(first).attempts shouldBe 3
            auditCount("fnrh.submission.retry", first) shouldBe 0L
            auditCount("fnrh.submission.dismiss", first) shouldBe 0L

            call(GET, base, manager).expectStatus(200)
            call(POST, "$base/$first/retry", manager).ok()["status"].asString() shouldBe "PENDING"
            call(POST, "$base/$second/dismiss", manager).ok()["status"].asString() shouldBe "DISMISSED"
        }

    @Test
    fun `a caller without the permission gets 403 even for an unknown id, never a 404`(): Unit =
        with(iam) {
            val nobody = asTestUser(createUser())
            val unknown = UUID.randomUUID()

            call(POST, "$base/$unknown/retry", nobody).problem(403, "PERMISSION_DENIED")
            call(POST, "$base/$unknown/dismiss", nobody).problem(403, "PERMISSION_DENIED")
            call(POST, "$base/$unknown/retry", asTestUser(user(FnrhPermissions.SUBMISSIONS_READ)))
                .problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `the manage permission alone does not read the list`(): Unit =
        with(iam) {
            call(GET, base, asTestUser(user(FnrhPermissions.SUBMISSIONS_MANAGE))).problem(403, "PERMISSION_DENIED")
        }

    // --- Privacidade ------------------------------------------------------------------------------------------------

    @Test
    fun `no response of the three endpoints carries guest data or the pre-check-in link`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val checkIn = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            val link = "https://precheckin.example.test/acesso/${UUID.randomUUID()}"
            ids.saveReservation(
                FnrhRemoteReservation(reservation, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId("r-1"), link, false),
                register,
            )
            force(register, FnrhSubmissionStatus.SENT)
            force(checkIn, FnrhSubmissionStatus.INVALID)
            addError(checkIn, guest.id)
            addError(checkIn, guest.id, "birthDate")
            val forbidden = guest.leaks + link + "precheckin" + "pre-check-in"

            val list = call(GET, "$base?limit=200&cursor=${from(register)}", asAdmin())
            val listBody = list.ok()
            val mine = listBody["items"].values().filter { it["reservationId"].asString() == reservation.toString() }
            mine.map { it["id"].asString() } shouldContain checkIn.toString()
            val allowedItem =
                setOf(
                    "id",
                    "reservationId",
                    "reservationCode",
                    "operation",
                    "status",
                    "attempts",
                    "nextAttemptAt",
                    "lastFailure",
                    "errors",
                    "createdAt",
                    "sentAt",
                )
            assertSoftly {
                mine.forEach { item -> item.propertyNames().toSet() shouldBe allowedItem }
                listBody["items"].values().forEach { item ->
                    item["errors"].values().forEach {
                        it.propertyNames().toSet() shouldBe
                            setOf("guestId", "field", "code")
                    }
                }
            }
            val invalid = mine.single { it["id"].asString() == checkIn.toString() }
            invalid["errors"].size() shouldBe 2

            val retried = call(POST, "$base/$checkIn/retry", asAdmin())
            val dismissed = call(POST, "$base/$register/dismiss", asAdmin())
            force(register, FnrhSubmissionStatus.FAILED, attempts = 1, failure = "SERVER_ERROR")
            val dismissedOk = call(POST, "$base/$register/dismiss", asAdmin())

            retried.status shouldBe 200
            dismissed.status shouldBe 409
            dismissedOk.status shouldBe 200
            retried.ok().propertyNames().toSet() shouldBe allowedItem
            listOf(list, retried, dismissed, dismissedOk).forEach { response ->
                val text = response.getContentAsString(Charsets.UTF_8).lowercase()
                forbidden.forEach { withClue(it) { text shouldNotContain it.lowercase() } }
            }
        }

    @Test
    fun `a FAILED item shows the failure code and a PENDING one hides nothing else`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            force(id, FnrhSubmissionStatus.FAILED, attempts = 8, failure = "SERVER_ERROR")

            val item =
                call(GET, "$base?status=FAILED&limit=200&cursor=${from(id)}", asAdmin())
                    .ok()["items"]
                    .values()
                    .single { it["id"].asString() == id.toString() }

            item["lastFailure"].asString() shouldBe "SERVER_ERROR"
            item["attempts"].asInt() shouldBe 8
            item["status"].asString() shouldBe "FAILED"
            item["sentAt"].isNull shouldBe true
            item["errors"].size() shouldBe 0
        }

    // --- Paginação --------------------------------------------------------------------------------------------------

    private fun pages(
        query: String,
        from: Long,
        limit: Int,
    ): List<JsonNode> =
        with(iam) {
            val collected = mutableListOf<JsonNode>()
            var cursor: String? = PageCursor.of((from - 1).toString()).encode()
            var guard = 0
            while (cursor != null) {
                check(++guard < MAX_PAGES) { "paginação sem fim" }
                val page = call(GET, "$base?${query}limit=$limit&cursor=$cursor", asAdmin()).ok()
                collected.add(page)
                cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            }
            collected
        }

    @Test
    fun `several pages with a small limit neither repeat nor skip and keep the order`(): Unit =
        with(iam) {
            val reservation = reservation()
            val first = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val mine = listOf(first) + List(6) { submit(reservation, FnrhOperation.UPDATE_RESERVATION) }

            val pages = pages("", sequenceOf(first), 2)

            val all = pages.flatMap { it.itemIds() }
            all.toSet().size shouldBe all.size
            pages.dropLast(1).forEach { it["items"].size() shouldBe 2 }
            pages.forEach { (it["items"].size() <= 2) shouldBe true }
            val sequences = all.map { sequenceOf(UUID.fromString(it)) }
            sequences shouldBe sequences.sorted()
            all.filter { it in mine.map(UUID::toString) } shouldContainExactly mine.map(UUID::toString)
            pages.last()["nextCursor"].isNull shouldBe true
        }

    @Test
    fun `the status filter holds on every page and never returns another status`(): Unit =
        with(iam) {
            val reservation = reservation()
            val first = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val others = List(7) { submit(reservation, FnrhOperation.UPDATE_RESERVATION) }
            val all = listOf(first) + others
            val failed = all.filterIndexed { index, _ -> index % 2 == 0 }
            val sent = all.filterIndexed { index, _ -> index % 2 == 1 }
            failed.forEach { force(it, FnrhSubmissionStatus.FAILED, failure = "SERVER_ERROR") }
            sent.forEach { force(it, FnrhSubmissionStatus.SENT) }

            val pages = pages("status=FAILED&", sequenceOf(first), 1)

            pages.flatMap { it["items"].values() }.map { it["status"].asString() }.toSet().let {
                it.shouldContainExactly("FAILED")
            }
            val seen = pages.flatMap { it.itemIds() }
            seen.toSet().size shouldBe seen.size
            seen.filter { it in all.map(UUID::toString) } shouldContainExactly failed.map(UUID::toString)
            seen.shouldNotContain(sent.first().toString())
        }

    @Test
    fun `a filter with no match gives an empty last page`(): Unit =
        with(iam) {
            val cursor = PageCursor.of(Long.MAX_VALUE.toString()).encode()

            val page = call(GET, "$base?status=FAILED&cursor=$cursor", asAdmin()).ok()

            page["items"].values().toList().shouldBeEmpty()
            page["nextCursor"].isNull shouldBe true
        }

    @Test
    fun `limit out of bounds is 400 and the bounds themselves are accepted`(): Unit =
        with(iam) {
            for (bad in listOf("0", "-1", "201", "abc", "")) {
                withClue("limit=$bad") { call(GET, "$base?limit=$bad", asAdmin()).expectStatus(400) }
            }
            call(GET, "$base?limit=1", asAdmin()).ok()["items"].size() shouldBe 1
            call(GET, "$base?limit=200", asAdmin()).ok()
        }

    @Test
    fun `an invalid status is 400 and the value is case sensitive`(): Unit =
        with(iam) {
            for (bad in listOf("BOGUS", "failed", "Failed", "FAILED,SENT")) {
                withClue("status=$bad") { call(GET, "$base?status=$bad", asAdmin()).expectStatus(400) }
            }
            FnrhSubmissionStatus.entries.forEach {
                withClue(it.name) { call(GET, "$base?status=${it.name}&limit=1", asAdmin()).ok() }
            }
        }

    @Test
    fun `a tampered cursor or one from another endpoint is 400 INVALID_CURSOR`(): Unit =
        with(iam) {
            val cursors =
                listOf(
                    "***",
                    "bm90LWEtY3Vyc29y",
                    PageCursor.of(UUID.randomUUID().toString()).encode(),
                    PageCursor.of("2026-02-30", UUID.randomUUID().toString()).encode(),
                    PageCursor.of("ontem").encode(),
                    PageCursor.of("99999999999999999999").encode(),
                    PageCursor.of("1.5").encode(),
                    PageCursor.of("").encode(),
                )
            assertSoftly {
                cursors.forEach { cursor ->
                    withClue(cursor) { call(GET, "$base?cursor=$cursor", asAdmin()).problem(400, "INVALID_CURSOR") }
                }
            }
        }

    // --- Transições -------------------------------------------------------------------------------------------------

    @Test
    fun `retry resets attempts and errors, keeps the write in flight and the head is due again`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val checkIn = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            force(register, FnrhSubmissionStatus.SENT)
            force(checkIn, FnrhSubmissionStatus.FAILED, attempts = 8, failure = "UNKNOWN_OUTCOME", inFlight = true)
            store.dueHeads(clock.instant(), 100, reservation).shouldBeEmpty()

            val item = call(POST, "$base/$checkIn/retry", asAdmin()).ok()

            item["status"].asString() shouldBe "PENDING"
            item["attempts"].asInt() shouldBe 0
            item["lastFailure"].isNull shouldBe true
            item["errors"].size() shouldBe 0
            val after = row(checkIn)
            after.status shouldBe FnrhSubmissionStatus.PENDING
            after.attempts shouldBe 0
            after.lastFailure shouldBe null
            after.inFlight shouldBe true
            store.dueHeads(clock.instant(), 100, reservation) shouldContainExactly listOf(checkIn)
            row(register).status shouldBe FnrhSubmissionStatus.SENT
        }

    @Test
    fun `retry of an INVALID submission removes the errors from the database too`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val checkIn = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            force(checkIn, FnrhSubmissionStatus.INVALID)
            addError(checkIn, guest.id)
            errorCount(checkIn) shouldBe 1L

            call(POST, "$base/$checkIn/retry", asAdmin()).ok()["errors"].size() shouldBe 0

            errorCount(checkIn) shouldBe 0L
            call(GET, "$base?status=PENDING&limit=200&cursor=${from(checkIn)}", asAdmin())
                .ok()["items"]
                .values()
                .single { it["id"].asString() == checkIn.toString() }["errors"]
                .size() shouldBe 0
        }

    @Test
    fun `retry refuses every state but FAILED and INVALID with 409 and changes nothing`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val refused =
                listOf(
                    FnrhSubmissionStatus.PENDING,
                    FnrhSubmissionStatus.SENT,
                    FnrhSubmissionStatus.CANCELLED,
                    FnrhSubmissionStatus.DISMISSED,
                )
            assertSoftly {
                for (state in refused) {
                    withClue(state.name) {
                        force(id, state, attempts = 3, failure = "SERVER_ERROR", inFlight = true)
                        call(POST, "$base/$id/retry", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_RETRYABLE")
                        val after = row(id)
                        after.status shouldBe state
                        after.attempts shouldBe 3
                        after.inFlight shouldBe true
                    }
                }
            }
            auditCount("fnrh.submission.retry", id) shouldBe 0L
        }

    @Test
    fun `dismiss refuses every state but FAILED and INVALID with 409 and changes nothing`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val refused =
                listOf(
                    FnrhSubmissionStatus.PENDING,
                    FnrhSubmissionStatus.SENT,
                    FnrhSubmissionStatus.CANCELLED,
                    FnrhSubmissionStatus.DISMISSED,
                )
            assertSoftly {
                for (state in refused) {
                    withClue(state.name) {
                        force(id, state, attempts = 3, failure = "SERVER_ERROR")
                        call(POST, "$base/$id/dismiss", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_DISMISSABLE")
                        row(id).status shouldBe state
                        row(id).attempts shouldBe 3
                    }
                }
            }
            auditCount("fnrh.submission.dismiss", id) shouldBe 0L
        }

    @Test
    fun `dismiss of an INVALID head frees the next submission of the reservation`(): Unit =
        with(iam) {
            val reservation = reservation()
            val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            force(register, FnrhSubmissionStatus.SENT)
            val head = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            val next = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            force(head, FnrhSubmissionStatus.INVALID)
            addError(head, UUID.randomUUID())
            store.dueHeads(clock.instant(), 100, reservation).shouldBeEmpty()

            val item = call(POST, "$base/$head/dismiss", asAdmin()).ok()

            item["status"].asString() shouldBe "DISMISSED"
            row(head).status shouldBe FnrhSubmissionStatus.DISMISSED
            store.dueHeads(clock.instant(), 100, reservation) shouldContainExactly listOf(next)
        }

    @Test
    fun `a FAILED head holds the next one until retry, and retry makes the head the one to leave`(): Unit =
        with(iam) {
            val reservation = reservation()
            val head = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            val next = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
            force(head, FnrhSubmissionStatus.FAILED, attempts = 8, failure = "SERVER_ERROR")
            store.dueHeads(clock.instant(), 100, reservation).shouldBeEmpty()

            call(POST, "$base/$head/retry", asAdmin()).ok()

            store.dueHeads(clock.instant(), 100, reservation) shouldContainExactly listOf(head)
            row(next).status shouldBe FnrhSubmissionStatus.PENDING
        }

    @Test
    fun `unknown ids are 404 and malformed ones are 400 on both actions`(): Unit =
        with(iam) {
            val unknown = UUID.randomUUID()
            for (action in listOf("retry", "dismiss")) {
                call(POST, "$base/$unknown/$action", asAdmin()).problem(404, "FNRH_SUBMISSION_NOT_FOUND")
                for (bad in listOf("nao-e-uuid", "123", "00000000-0000-0000-0000-00000000000g")) {
                    withClue("$action $bad") { call(POST, "$base/$bad/$action", asAdmin()).expectStatus(400) }
                }
            }
            auditCount("fnrh.submission.retry", unknown) shouldBe 0L
            auditCount("fnrh.submission.dismiss", unknown) shouldBe 0L
        }

    @Test
    fun `repeating retry has no double effect`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            force(id, FnrhSubmissionStatus.FAILED, attempts = 4, failure = "SERVER_ERROR")

            call(POST, "$base/$id/retry", asAdmin()).ok()
            val afterFirst = row(id)
            call(POST, "$base/$id/retry", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_RETRYABLE")

            row(id) shouldBe afterFirst
            auditCount("fnrh.submission.retry", id) shouldBe 1L
        }

    @Test
    fun `repeating dismiss has no double effect and a dismissed submission cannot be retried`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            force(id, FnrhSubmissionStatus.FAILED, attempts = 4, failure = "SERVER_ERROR")

            call(POST, "$base/$id/dismiss", asAdmin()).ok()
            call(POST, "$base/$id/dismiss", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_DISMISSABLE")
            call(POST, "$base/$id/retry", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_RETRYABLE")

            row(id).status shouldBe FnrhSubmissionStatus.DISMISSED
            auditCount("fnrh.submission.dismiss", id) shouldBe 1L
            auditCount("fnrh.submission.retry", id) shouldBe 0L
        }

    @Test
    fun `retry then a failed send then retry again goes back to PENDING with zero attempts`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            force(id, FnrhSubmissionStatus.FAILED, attempts = 8, failure = "SERVER_ERROR")
            call(POST, "$base/$id/retry", asAdmin()).ok()
            force(id, FnrhSubmissionStatus.FAILED, attempts = 8, failure = "VALIDATION")

            val item = call(POST, "$base/$id/retry", asAdmin()).ok()

            item["status"].asString() shouldBe "PENDING"
            item["attempts"].asInt() shouldBe 0
            item["lastFailure"].isNull shouldBe true
            auditCount("fnrh.submission.retry", id) shouldBe 2L
        }

    // --- Trilha ------------------------------------------------------------------------------------------------------

    @Test
    fun `the trail of retry and dismiss has the user and neither guest data nor the reservation code`(): Unit =
        with(iam) {
            val guest = fullGuest()
            val reservation = checkedIn(guest.id)
            val checkIn = awaitSubmission(reservation, FnrhOperation.CHECK_IN).id
            val register = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id
            force(checkIn, FnrhSubmissionStatus.INVALID)
            addError(checkIn, guest.id)
            force(register, FnrhSubmissionStatus.FAILED, attempts = 5, failure = "FORBIDDEN")
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

            call(POST, "$base/$checkIn/retry", asTestUser(manager)).ok()
            call(POST, "$base/$register/dismiss", asTestUser(manager)).ok()

            val rows =
                jdbc
                    .sql(
                        "SELECT action, user_id, CAST(before_state AS text) AS b, CAST(after_state AS text) AS a, " +
                            "CAST(entity_id AS text) AS e FROM audit.audit_entry " +
                            "WHERE entity_id IN (:a, :b) AND action LIKE 'fnrh.submission.%' AND outcome = 'SUCCEEDED'",
                    ).param("a", checkIn.toString())
                    .param("b", register.toString())
                    .query { rs, _ ->
                        listOf(
                            rs.getString("action"),
                            rs.getObject("user_id", UUID::class.java),
                            rs.getString("b"),
                            rs.getString("a"),
                        )
                    }.list()
            rows.size shouldBe 2
            rows.forEach { row ->
                row[1] shouldBe manager.id.value
                val text = "${row[2]} ${row[3]}"
                guest.leaks.forEach { text shouldNotContain it }
                text shouldNotContain codeOf(reservation)
                text shouldNotContain "REQUIRED"
            }
            val dismiss = rows.single { it[0] == "fnrh.submission.dismiss" }
            (dismiss[2] as String).contains("FAILED") shouldBe true
            (dismiss[2] as String).contains("FORBIDDEN") shouldBe true
            (dismiss[3] as String).contains("DISMISSED") shouldBe true
            val retry = rows.single { it[0] == "fnrh.submission.retry" }
            (retry[2] as String).contains("INVALID") shouldBe true
            (retry[3] as String).contains("PENDING") shouldBe true
        }

    @Test
    fun `a 409 or a 403 leaves no successful entry in the trail`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = awaitSubmission(reservation, FnrhOperation.REGISTER_RESERVATION).id

            call(POST, "$base/$id/dismiss", asAdmin()).problem(409, "FNRH_SUBMISSION_NOT_DISMISSABLE")
            call(POST, "$base/$id/dismiss", asTestUser(createUser())).problem(403, "PERMISSION_DENIED")

            auditCount("fnrh.submission.dismiss", id) shouldBe 0L
            auditCount("fnrh.submission.retry", id) shouldBe 0L
        }

    // --- Contrato ----------------------------------------------------------------------------------------------------

    @Test
    fun `the contract marks operation, status and lastFailure as extensible enums and hides the link`(): Unit =
        with(iam) {
            val doc =
                jsonMapper.readTree(
                    web
                        .get("/v3/api-docs")
                        .andReturn()
                        .response.contentAsString,
                )
            val schema = doc["components"]["schemas"]["FnrhSubmissionResponse"]
            val properties = schema["properties"]

            for (field in listOf("operation", "status", "lastFailure")) {
                withClue(field) {
                    properties[field]["x-extensible-enum"].values().toList().isEmpty() shouldBe false
                    properties[field].has("enum") shouldBe false
                    properties[field]["type"].asString() shouldBe "string"
                }
            }
            properties["status"]["x-extensible-enum"].values().map { it.asString() } shouldBe
                FnrhSubmissionStatus.entries.map { it.name }
            properties["operation"]["x-extensible-enum"].values().map { it.asString() } shouldBe
                FnrhOperation.entries.map { it.name }
            val names = properties.propertyNames().toList().map { it.lowercase() }
            names.none { "link" in it || "precheck" in it || "url" in it } shouldBe true
            schema["required"].values().map { it.asString() } shouldNotContain "sentAt"
            val errors = doc["components"]["schemas"]["FnrhSubmissionErrorResponse"]["properties"]
            errors.propertyNames().toSet() shouldBe setOf("guestId", "field", "code")
            doc["paths"]["/api/v1/fnrh/submissions"]["get"]["operationId"].asString() shouldBe "listFnrhSubmissions"
            doc["paths"]["/api/v1/fnrh/submissions/{id}/retry"]["post"]["operationId"].asString() shouldBe
                "retryFnrhSubmission"
            doc["paths"]["/api/v1/fnrh/submissions/{id}/dismiss"]["post"]["operationId"].asString() shouldBe
                "dismissFnrhSubmission"
        }

    private companion object {
        const val MAX_PAGES = 2_000
    }
}

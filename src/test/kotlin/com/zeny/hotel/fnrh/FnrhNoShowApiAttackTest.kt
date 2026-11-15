package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionRecorder
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Ataques do testador à API de pendências com submissões `NO_SHOW` (M6 E14, Task 2): filtro, paginação, `retry`,
 * `dismiss` e o alerta de `REMOTE_NO_SHOW`, sem dado pessoal. As linhas `NO_SHOW` gravadas são apagadas no fim.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhNoShowApiAttackTest(
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
    @Autowired private val recorder: FnrhSubmissionRecorder,
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
    private val noShowIds = mutableListOf<UUID>()

    @BeforeEach
    fun enable() {
        iam.enableFnrh()
    }

    @AfterEach
    fun cleanUpNoShow() {
        noShowIds.forEach { jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", it).update() }
    }

    private fun failedNoShow(reservation: UUID): UUID {
        val id = submit(reservation, FnrhOperation.NO_SHOW).also { noShowIds += it }
        force(id, "FAILED", "REMOTE_NO_SHOW")
        return id
    }

    private fun force(
        id: UUID,
        status: String,
        failure: String?,
    ) {
        jdbc
            .sql("UPDATE fnrh.submission SET status = :s, attempts = 1, last_failure = :f WHERE id = :id")
            .param("s", status)
            .param("f", failure)
            .param("id", id)
            .update()
    }

    private fun sequenceOf(id: UUID): Long =
        jdbc
            .sql("SELECT sequence FROM fnrh.submission WHERE id = :id")
            .param("id", id)
            .query(Long::class.java)
            .single()

    private fun from(id: UUID): String = PageCursor.of((sequenceOf(id) - 1).toString()).encode()

    @Test
    fun `the status filter and the cursor page through NO_SHOW submissions without losing or repeating any`(): Unit =
        with(iam) {
            val reservation = reservation()
            val first = failedNoShow(reservation)
            val second = failedNoShow(reservation)
            val third = failedNoShow(reservation)
            val seen = mutableListOf<String>()
            var cursor: String? = from(first)
            while (cursor != null) {
                val page = call(GET, "$base?status=FAILED&limit=2&cursor=$cursor", asAdmin()).ok()
                page["items"].values().forEach {
                    it["operation"].asString() shouldBe "NO_SHOW"
                    it["lastFailure"].asString() shouldBe "REMOTE_NO_SHOW"
                    seen += it["id"].asString()
                }
                cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            }
            seen.filter { it in setOf(first, second, third).map(UUID::toString) } shouldContainExactly
                listOf(first, second, third).map(UUID::toString)

            val pending =
                call(GET, "$base?status=PENDING&limit=200&cursor=${from(first)}", asAdmin())
                    .ok()["items"]
                    .values()
                    .map { it["id"].asString() }
            pending
                .filter { it == first.toString() || it == second.toString() || it == third.toString() }
                .shouldBeEmpty()
        }

    @Test
    fun `retry puts a failed NO_SHOW back as PENDING and keeps its operation`(): Unit =
        with(iam) {
            val id = failedNoShow(reservation())

            val retried = call(POST, "$base/$id/retry", asAdmin()).ok()

            retried["status"].asString() shouldBe "PENDING"
            retried["operation"].asString() shouldBe "NO_SHOW"
            jdbc
                .sql("SELECT operation, operation_rank FROM fnrh.submission WHERE id = :id")
                .param("id", id)
                .query { rs, _ -> rs.getString(1) to rs.getInt(2) }
                .single() shouldBe ("NO_SHOW" to 6)
        }

    @Test
    fun `dismiss closes a failed NO_SHOW as DISMISSED and releases the next operation of the reservation`(): Unit =
        with(iam) {
            val reservation = reservation()
            val id = failedNoShow(reservation)
            val next = submit(reservation, FnrhOperation.UPDATE_RESERVATION).also { noShowIds += it }
            jdbc
                .sql("UPDATE fnrh.submission SET status = 'SENT' WHERE reservation_id = :r AND id NOT IN (:a, :b)")
                .param("r", reservation)
                .param("a", id)
                .param("b", next)
                .update()
            val due = clock.instant().plusSeconds(DAYS_AHEAD)
            store.dueHeads(due, 1000, reservation).shouldBeEmpty()

            val dismissed = call(POST, "$base/$id/dismiss", asAdmin()).ok()

            dismissed["status"].asString() shouldBe "DISMISSED"
            dismissed["operation"].asString() shouldBe "NO_SHOW"
            store.dueHeads(due, 1000, reservation) shouldContainExactly listOf(next)
        }

    @Test
    fun `the REMOTE_NO_SHOW alert holds only the code and the label and reaches only who manages submissions`(): Unit =
        with(iam) {
            val reservation = reservation()
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
            val reader = user(FnrhPermissions.SUBMISSIONS_READ)
            val id = submit(reservation, FnrhOperation.NO_SHOW).also { noShowIds += it }

            recorder.failedLocally(id, FnrhSubmissionFailure.REMOTE_NO_SHOW)

            val alerts = alertsOf(reservation).filter { it.body.contains("no-show") }
            alerts.map { it.body }.distinct() shouldContainExactly
                listOf("Reserva ${codeOf(reservation)}: no-show — falhou")
            alerts.map { it.title }.distinct() shouldContainExactly listOf("Falha no envio à FNRH")
            alerts.any { it.recipient == manager.id.value } shouldBe true
            alerts.any { it.recipient == reader.id.value } shouldBe false
        }

    private companion object {
        const val DAYS_AHEAD = 30L * 24 * 3600
    }
}

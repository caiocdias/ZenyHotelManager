package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionRecorder
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmission
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Os códigos novos do no-show (M6 E14, D-E14-5): a operação `NO_SHOW` (posto 6) e a falha `REMOTE_NO_SHOW` na fila, na
 * API de pendências e no alerta.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhNoShowCodesTest(
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
    private val noShowIds = mutableListOf<UUID>()

    @BeforeEach
    fun enable() {
        iam.enableFnrh()
    }

    // O banco é compartilhado: as linhas NO_SHOW que o teste gravou não podem sobrar para os outros testes.
    @AfterEach
    fun cleanUpNoShow() {
        noShowIds.forEach { jdbc.sql("DELETE FROM fnrh.submission WHERE id = :id").param("id", it).update() }
    }

    @Test
    fun `NO_SHOW comes after every other operation in the causal order`() {
        FnrhOperation.NO_SHOW.rank shouldBe 6
        FnrhOperation.entries
            .filter { it != FnrhOperation.NO_SHOW }
            .forEach { it.rank shouldBeLessThan FnrhOperation.NO_SHOW.rank }
    }

    @Test
    fun `REMOTE_NO_SHOW is a permanent failure`() {
        FnrhSubmissionFailure.REMOTE_NO_SHOW.temporary shouldBe false
    }

    @Test
    fun `the queue stores a NO_SHOW submission`() {
        val reservation = reservation()
        val submission =
            FnrhSubmission.create(
                reservationId = reservation,
                reservationCode = codeOf(reservation),
                operation = FnrhOperation.NO_SHOW,
                eventKey = "no-show:v2",
                causalVersion = 2,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        noShowIds += submission.id

        store.recordIfAbsent(submission) shouldBe true

        val stored =
            jdbc
                .sql("SELECT operation, operation_rank FROM fnrh.submission WHERE id = :id")
                .param("id", submission.id)
                .query { rs, _ -> rs.getString("operation") to rs.getInt("operation_rank") }
                .single()
        stored shouldBe ("NO_SHOW" to 6)
    }

    @Test
    fun `the submissions API shows NO_SHOW and REMOTE_NO_SHOW and the alert carries the no-show label`(): Unit =
        with(iam) {
            val reservation = reservation()
            val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
            val id = submit(reservation, FnrhOperation.NO_SHOW).also { noShowIds += it }

            recorder.failedLocally(id, FnrhSubmissionFailure.REMOTE_NO_SHOW) shouldBe FnrhSubmissionStatus.FAILED

            val reader = user(FnrhPermissions.SUBMISSIONS_READ)
            val items = call(GET, "/api/v1/fnrh/submissions?status=FAILED&limit=200", asTestUser(reader)).ok()["items"]
            val item = items.values().single { it["id"].asString() == id.toString() }
            item["operation"].asString() shouldBe "NO_SHOW"
            item["lastFailure"].asString() shouldBe "REMOTE_NO_SHOW"
            alertsOf(reservation).filter { it.recipient == manager.id.value }.map { it.body } shouldContain
                "Reserva ${codeOf(reservation)}: no-show — falhou"
        }
}

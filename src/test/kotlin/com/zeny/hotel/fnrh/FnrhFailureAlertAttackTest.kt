package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionRecorder
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
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Ataque ao alerta de falha no envio à FNRH (M5 E08, Task 9; D-E08-11 e 12): privacidade em todas as operações e
 * situações, quando notifica (e quando não), uma notificação por passagem e quem recebe.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhFailureAlertAttackTest(
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
    private val labels =
        mapOf(
            FnrhOperation.REGISTER_RESERVATION to "registro da reserva",
            FnrhOperation.UPDATE_RESERVATION to "atualização da reserva",
            FnrhOperation.CANCEL_RESERVATION to "cancelamento da reserva",
            FnrhOperation.CHECK_IN to "check-in",
            FnrhOperation.UNDO_CHECK_IN to "desfazer check-in",
            FnrhOperation.CHECK_OUT to "check-out",
        )

    @Test
    fun `every operation alerts with its fixed label on a permanent failure and leaks no guest data`() {
        val guest = fullGuest()
        val reservation = checkedIn(guest.id)
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

        FnrhOperation.entries.forEach { operation ->
            val id = submit(reservation, operation)
            recorder.failed(id, FnrhSubmissionFailure.VALIDATION) shouldBe FnrhSubmissionStatus.FAILED
            val body = "Reserva ${codeOf(reservation)}: ${labels.getValue(operation)} — falhou"
            val mine = alertsOf(reservation).filter { it.recipient == manager.id.value && it.body == body }
            withClue("$operation") { mine shouldHaveSize 1 }
            withClue("$operation") { mine.single().title shouldBe "Falha no envio à FNRH" }
        }

        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize FnrhOperation.entries.size
        assertNoLeak(reservation, guest.leaks)
    }

    @Test
    fun `every operation alerts with the invalid label and leaks no guest data, even with errors that name fields`() {
        val guest = fullGuest()
        val reservation = checkedIn(guest.id)
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)

        FnrhOperation.entries.forEach { operation ->
            val id = submit(reservation, operation)
            val errors = listOf(FnrhSubmissionError(guest.id, "document", "REQUIRED"))
            recorder.invalid(id, errors) shouldBe FnrhSubmissionStatus.INVALID
            val body = "Reserva ${codeOf(reservation)}: ${labels.getValue(operation)} — dados inválidos"
            withClue("$operation") {
                alertsOf(reservation).filter { it.recipient == manager.id.value && it.body == body } shouldHaveSize 1
            }
        }

        assertNoLeak(reservation, guest.leaks)
    }

    @Test
    fun `a local failure alerts with the failed label and leaks no guest data`() {
        val guest = fullGuest()
        val reservation = checkedIn(guest.id)
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val cancelled = submit(reservation, FnrhOperation.CHECK_IN)
        val noOperation = submit(reservation, FnrhOperation.UNDO_CHECK_IN)

        recorder.failedLocally(cancelled, FnrhSubmissionFailure.REMOTE_CANCELLED) shouldBe FnrhSubmissionStatus.FAILED
        recorder.failedLocally(noOperation, FnrhSubmissionFailure.NO_REMOTE_OPERATION) shouldBe
            FnrhSubmissionStatus.FAILED

        alertsOf(reservation).filter { it.recipient == manager.id.value }.map { it.body } shouldBe
            listOf(
                "Reserva ${codeOf(reservation)}: check-in — falhou",
                "Reserva ${codeOf(reservation)}: desfazer check-in — falhou",
            )
        assertNoLeak(reservation, guest.leaks)
    }

    @Test
    fun `the eighth temporary failure alerts once and the seven before it do not`() {
        val reservation = reservation()
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val id = submit(reservation, FnrhOperation.UPDATE_RESERVATION)

        repeat(MAX_ATTEMPTS - 1) {
            recorder.failed(id, FnrhSubmissionFailure.UNAVAILABLE) shouldBe FnrhSubmissionStatus.PENDING
            withClue("tentativa ${it + 1}") { alertsOf(reservation).shouldBeEmpty() }
        }
        recorder.failed(id, FnrhSubmissionFailure.UNAVAILABLE) shouldBe FnrhSubmissionStatus.FAILED

        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize 1
    }

    @Test
    fun `a result for a submission already sent or cancelled does not alert`() {
        val reservation = reservation()
        user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val sent = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
        val cancelled = submit(reservation, FnrhOperation.CANCEL_RESERVATION)
        recorder.sent(sent) shouldBe FnrhSubmissionStatus.SENT
        tx.executeWithoutResult {
            store.find(cancelled)!!.also { submission ->
                submission.cancel(clock.instant())
                store.save(submission)
            }
        }

        recorder.failed(sent, FnrhSubmissionFailure.VALIDATION) shouldBe FnrhSubmissionStatus.SENT
        recorder.failedLocally(sent, FnrhSubmissionFailure.REMOTE_CANCELLED) shouldBe FnrhSubmissionStatus.SENT
        recorder.invalid(sent, emptyList()) shouldBe FnrhSubmissionStatus.SENT
        recorder.failed(cancelled, FnrhSubmissionFailure.VALIDATION) shouldBe FnrhSubmissionStatus.CANCELLED
        recorder.invalid(cancelled, emptyList()) shouldBe FnrhSubmissionStatus.CANCELLED

        alertsOf(reservation).shouldBeEmpty()
    }

    @Test
    fun `a submission that is already failed does not alert again when another result arrives`() {
        val reservation = reservation()
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val id = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
        recorder.failed(id, FnrhSubmissionFailure.VALIDATION) shouldBe FnrhSubmissionStatus.FAILED

        recorder.failed(id, FnrhSubmissionFailure.VALIDATION)
        recorder.failedLocally(id, FnrhSubmissionFailure.REMOTE_CANCELLED)

        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize 1
    }

    @Test
    fun `a successful send does not alert`() {
        val reservation = reservation()
        user(FnrhPermissions.SUBMISSIONS_MANAGE)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(reservation).sent shouldBe 1

        alertsOf(reservation).shouldBeEmpty()
    }

    @Test
    fun `a retry followed by a new failure alerts again`() {
        val reservation = reservation()
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val id = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
        recorder.failed(id, FnrhSubmissionFailure.VALIDATION)
        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize 1

        tx.executeWithoutResult {
            store.find(id)!!.also { submission ->
                submission.retry(clock.instant())
                store.save(submission)
            }
        }
        row(id).status shouldBe FnrhSubmissionStatus.PENDING
        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize 1
        recorder.failed(id, FnrhSubmissionFailure.VALIDATION) shouldBe FnrhSubmissionStatus.FAILED

        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize 2
    }

    @Test
    fun `only users with the manage permission receive the alert`() {
        val reservation = reservation()
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val reader = user(FnrhPermissions.SUBMISSIONS_READ)
        val domains = user(FnrhPermissions.DOMAINS_READ)
        val id = submit(reservation, FnrhOperation.UPDATE_RESERVATION)

        recorder.failed(id, FnrhSubmissionFailure.VALIDATION)

        alertsOf(reservation).map { it.recipient }.filter {
            it in setOf(manager.id.value, reader.id.value, domains.id.value)
        } shouldBe listOf(manager.id.value)
    }

    @Test
    fun `an inactive user with the manage permission does not receive the alert`() {
        val reservation = reservation()
        val gone = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        iam.deactivate(gone)
        val id = submit(reservation, FnrhOperation.UPDATE_RESERVATION)

        recorder.failed(id, FnrhSubmissionFailure.VALIDATION)

        alertsOf(reservation).filter { it.recipient == gone.id.value }.shouldBeEmpty()
    }

    @Test
    fun `the whole send path alerts once for a permanent failure and not again on the next run`() {
        val guest = fullGuest()
        val reservation = checkedIn(guest.id)
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        iam.enableFnrh()
        FnrhTestServer.stubAny(400, """{"mensagem":"dado inválido"}""")

        sender.send(reservation).failed shouldBe 1
        advance(Duration.ofHours(1))
        sender.send(reservation)

        alertsOf(reservation).filter { it.recipient == manager.id.value } shouldHaveSize 1
        assertNoLeak(reservation, guest.leaks)
    }

    private fun assertNoLeak(
        reservation: java.util.UUID,
        leaks: List<String>,
    ) {
        alertsOf(reservation).forEach { alert ->
            leaks.forEach { leak ->
                withClue("o alerta vazou $leak") { (alert.title + alert.body) shouldNotContain leak }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

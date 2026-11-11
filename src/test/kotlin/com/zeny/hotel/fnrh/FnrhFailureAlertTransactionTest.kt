package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionRecorder
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * O que acontece com o resultado gravado quando a notificação do alerta falha (M5 E08, Task 9): o alerta vai na mesma
 * transação do resultado: o resultado fica gravado, o recorder registra um `WARN` e não relança.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhFailureAlertTransactionTest(
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
    @Suppress("VarCouldBeVal") // o Spring injeta o campo lateinit
    @MockitoSpyBean
    private lateinit var notifications: NotificationsApi

    @AfterEach
    fun restoreNotifications() {
        reset(notifications)
    }

    @Test
    fun `when the alert fails the failure result is still saved, nothing is notified and the log has the id only`(
        output: CapturedOutput,
    ) {
        val guest = fullGuest()
        val reservation = checkedIn(guest.id)
        user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val id = submit(reservation, FnrhOperation.UPDATE_RESERVATION)
        failAlert(reservation, "atualização da reserva")

        recorder.failed(id, FnrhSubmissionFailure.VALIDATION) shouldBe FnrhSubmissionStatus.FAILED

        val after = row(id)
        after.status shouldBe FnrhSubmissionStatus.FAILED
        after.attempts shouldBe 1
        after.lastFailure shouldBe FnrhSubmissionFailure.VALIDATION
        alertsOf(reservation).shouldBeEmpty()
        val warn = output.all.lines().filter { "alerta da submissão" in it }
        warn shouldHaveSize 1
        warn.single() shouldContain "WARN"
        warn.single() shouldContain id.toString()
        warn.single() shouldContain "UPDATE_RESERVATION"
        warn.single() shouldContain "IllegalStateException"
        guest.leaks.forEach { leak -> withClue("o log vazou $leak") { warn.single() shouldNotContain leak } }
    }

    @Test
    fun `when the alert fails the send run still counts the failure and does not call the Serpro again`() {
        val reservation = reservation()
        user(FnrhPermissions.SUBMISSIONS_MANAGE)
        iam.enableFnrh()
        FnrhTestServer.stubAny(400, """{"mensagem":"dado inválido"}""")
        failAlert(reservation, "registro da reserva")

        sender.send(reservation).failed shouldBe 1

        val first = rows(reservation).first()
        first.status shouldBe FnrhSubmissionStatus.FAILED
        first.attempts shouldBe 1
        alertsOf(reservation).shouldBeEmpty()
        val calls = FnrhTestServer.requests().size

        advance(Duration.ofHours(1))
        sender.send(reservation).failed shouldBe 0

        FnrhTestServer.requests().size shouldBe calls
        rows(reservation).first().attempts shouldBe 1
        alertsOf(reservation).shouldBeEmpty()
    }

    /** Faz o `notify` do alerta daquela reserva e operação falhar; os outros alertas seguem normais. */
    private fun failAlert(
        reservation: java.util.UUID,
        label: String,
    ) {
        doThrow(IllegalStateException("notificação indisponível"))
            .`when`(notifications)
            .notify(
                NotificationsTypes.INTEGRATION_FAILURE,
                "Falha no envio à FNRH",
                "Reserva ${codeOf(reservation)}: $label — falhou",
                NotificationRecipients.withPermission(FnrhPermissions.SUBMISSIONS_MANAGE),
            )
    }
}

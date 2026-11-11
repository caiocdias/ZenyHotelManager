package com.zeny.hotel.fnrh

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * O envio à FNRH depois de limpar a credencial (M5 E16, Task 3): sem chamada ao Serpro e sem gastar tentativa, também
 * quando a limpeza acontece no meio de uma rodada. Mantém o `read-timeout` padrão (20 s): a primeira chamada termina
 * normalmente. O banco e a trilha são compartilhados: só se conta o que acontece depois da limpeza.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhTestConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhSettingsClearSendTest(
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
) : FnrhReservationSendTestBase(
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
    ) {
    private val clearPath = "/api/v1/fnrh/settings/clear-credentials"

    @Test
    fun `after clearing, the send run makes no call and the pending submission keeps zero attempts`() {
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        clear()
        FnrhTestServer.server.resetRequests()

        val run = sender.send(id)

        run shouldBe FnrhSendRun(0, 0, 0, 0)
        row(submission.id).status shouldBe FnrhSubmissionStatus.PENDING
        row(submission.id).attempts shouldBe 0
        FnrhTestServer.requests() shouldHaveSize 0
    }

    @Test
    fun `after clearing, refreshing the domains is 422 FNRH_NOT_CONFIGURED without calling the Serpro`(): Unit =
        with(iam) {
            enableFnrh()
            clear()
            FnrhTestServer.server.resetRequests()

            call(POST, "/api/v1/fnrh/domains/refresh", asAdmin()).problem(422, "FNRH_NOT_CONFIGURED")

            FnrhTestServer.requests() shouldHaveSize 0
        }

    @Test
    fun `a clear in the middle of a send run stops the run before the next call`() {
        val first = reservation()
        val second = reservation()
        val firstSubmission = awaitSubmission(first, FnrhOperation.REGISTER_RESERVATION)
        val secondSubmission = awaitSubmission(second, FnrhOperation.REGISTER_RESERVATION)
        iam.enableFnrh()
        // O primeiro POST /reservas fica preso por 3 s (bem abaixo do read-timeout de 20 s) e responde normalmente.
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"reserva":{"reserva_id":"${remoteId()}","situacao_reserva_id":"CRIADA"}}""")
                    .withFixedDelay(HOLD_MS),
            ),
        )
        FnrhTestServer.server.resetRequests()

        val received = CountDownLatch(1)
        // O WireMock não remove o ouvinte; depois do teste ele só conta no latch descartado.
        FnrhTestServer.server.addMockServiceRequestListener { request, _ ->
            if (request.url.endsWith("/reservas")) received.countDown()
        }
        val run: CompletableFuture<FnrhSendRun> =
            CompletableFuture.supplyAsync { sender.sendDueFor(first, second) }
        // A limpeza só começa com o primeiro POST /reservas já recebido pelo WireMock, ainda preso no atraso.
        received.await(10, TimeUnit.SECONDS) shouldBe true
        clear()
        run.get(30, TimeUnit.SECONDS)

        FnrhTestServer.requests().filter { it.method.value() == "POST" && it.url.endsWith("/reservas") } shouldHaveSize
            1
        row(firstSubmission.id).status shouldBe FnrhSubmissionStatus.SENT
        row(secondSubmission.id).status shouldBe FnrhSubmissionStatus.PENDING
        row(secondSubmission.id).attempts shouldBe 0
    }

    private fun clear() {
        with(iam) {
            val etag = call(GET, "/api/v1/fnrh/settings", asAdmin()).getHeader(HttpHeaders.ETAG)
            call(POST, clearPath, asAdmin(), ifMatch = etag).ok()
        }
    }

    private companion object {
        const val HOLD_MS = 3_000
    }
}

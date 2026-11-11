package com.zeny.hotel.fnrh

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * O fluxo da etapa inteira pela API da recepção (M5 E08, Review Focus): reserva criada, check-in e check-out com o
 * Serpro (WireMock) respondendo, as chamadas em ordem, as submissões `SENT` e os ids remotos; e o mesmo com o Serpro
 * fora do ar no meio, sem duplicar nada quando ele volta. Cada teste cria a própria reserva e envia só as dela.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList")
class FnrhStageEndToEndTest(
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
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)

    private class Stay(
        val id: UUID,
        val folio: UUID,
        val guests: List<UUID>,
        val cpf: String,
    )

    @Test
    fun `the real flow reservation, check-in and check-out reaches Serpro in order and every submission is sent`() {
        val stay = stay()
        val remote = remoteId()
        iam.enableFnrh()
        stubHappy(stay, remote)

        sender.send(stay.id)
        checkIn(stay)
        sender.send(stay.id)
        checkOut(stay)
        sender.send(stay.id)

        statuses(stay.id) shouldContainExactly
            listOf(
                FnrhOperation.REGISTER_RESERVATION to FnrhSubmissionStatus.SENT,
                FnrhOperation.CHECK_IN to FnrhSubmissionStatus.SENT,
                FnrhOperation.CHECK_OUT to FnrhSubmissionStatus.SENT,
            )
        calls() shouldContainExactly
            listOf(
                "POST /reservas",
                "GET /pessoas/documento/CPF/${stay.cpf}",
                "POST /pessoas",
                "POST /reservas/$remote/hospedes",
                "PATCH /hospedes/h-$remote/checkout",
            )
        remoteIds
            .reservation(stay.id, FnrhEnvironment.HOMOLOGATION)
            .shouldNotBeNull()
            .remoteId.value shouldBe remote
        val guest = remoteIds.guests(stay.id, FnrhEnvironment.HOMOLOGATION).single()
        guest.personId.value shouldBe "p-$remote"
        guest.guestRemoteId?.value shouldBe "h-$remote"
        guest.checkedOut shouldBe true
    }

    @Test
    fun `with Serpro down after the registration the check-in answers 200 and the queue resumes without duplicating`() {
        val stay = stay()
        val remote = remoteId()
        iam.enableFnrh()
        stubHappy(stay, remote)
        sender.send(stay.id)
        FnrhTestServer.stubAny(503)

        checkIn(stay)
        sender.send(stay.id)
        checkOut(stay)
        sender.send(stay.id)

        rows(stay.id).map { it.operation to it.status } shouldContainExactly
            listOf(
                FnrhOperation.REGISTER_RESERVATION to FnrhSubmissionStatus.SENT,
                FnrhOperation.CHECK_IN to FnrhSubmissionStatus.PENDING,
                FnrhOperation.CHECK_OUT to FnrhSubmissionStatus.PENDING,
            )
        FnrhTestServer.reset()
        stubHappy(stay, remote)
        advance(Duration.ofMinutes(2))

        sender.send(stay.id)

        statuses(stay.id).map { it.second }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        calls() shouldContainExactly
            listOf(
                "GET /pessoas/documento/CPF/${stay.cpf}",
                "POST /pessoas",
                "POST /reservas/$remote/hospedes",
                "PATCH /hospedes/h-$remote/checkout",
            )
        requests("POST", "/reservas").size shouldBe 0
        remoteIds.guests(stay.id, FnrhEnvironment.HOMOLOGATION).single().checkedOut shouldBe true
    }

    @Test
    fun `with Serpro down from the first call nothing is lost nor duplicated when it comes back`() {
        val stay = stay()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)

        sender.send(stay.id)
        checkIn(stay)
        sender.send(stay.id)
        checkOut(stay)
        sender.send(stay.id)

        rows(stay.id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.PENDING)
        FnrhTestServer.reset()
        stubHappy(stay, remote)
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(stay.id))
        advance(Duration.ofMinutes(2))

        sender.send(stay.id)

        statuses(stay.id).map { it.second }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        requests("POST", "/reservas").size shouldBe 1
        requests("POST", "/pessoas").size shouldBe 1
        requests("POST", "/reservas/$remote/hospedes").size shouldBe 1
        requests("PATCH", "/hospedes/h-$remote/checkout").size shouldBe 1
        remoteIds
            .reservation(stay.id, FnrhEnvironment.HOMOLOGATION)
            .shouldNotBeNull()
            .remoteId.value shouldBe remote
    }

    @Test
    fun `after the whole flow no table of the fnrh schema holds the document number of the guest`() {
        val stay = stay()
        val remote = remoteId()
        iam.enableFnrh()
        stubHappy(stay, remote)
        sender.send(stay.id)
        checkIn(stay)
        sender.send(stay.id)
        checkOut(stay)
        sender.send(stay.id)
        statuses(stay.id).map { it.second }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)

        val tables =
            jdbc
                .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'fnrh'")
                .query(String::class.java)
                .list()
        tables.isNotEmpty() shouldBe true
        for (table in tables) {
            val dump =
                jdbc
                    .sql("SELECT t::text FROM fnrh.\"$table\" t")
                    .query(String::class.java)
                    .list()
                    .joinToString("\n")
            withClue("tabela fnrh.$table") { dump.contains(stay.cpf) shouldBe false }
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun stay(): Stay {
        val cpf = newCpf()
        val guest =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile =
                                support.guests.profileBody(
                                    extra = mapOf("birthDate" to LocalDate.of(1988, 3, 14), "nationality" to "BR"),
                                ),
                            documents = listOf(support.guests.document("CPF", cpf)),
                            sensitiveData = mapOf("raceColor" to "BROWN"),
                        ),
                    )["id"]
                    .asString(),
            )
        val front = frontdesk.stay()
        val booked = front.book(guest, departure = today)
        front.ready()
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        return Stay(booked.id, booked.folio, listOf(guest), cpf)
    }

    private fun checkIn(stay: Stay) {
        val body =
            listOf(
                mapOf(
                    "guestId" to stay.guests.single(),
                    "primary" to true,
                    "travelReason" to "LAZER_FERIAS",
                    "transportMode" to "AUTOMOVEL",
                    "minorAuthorization" to null,
                ),
            )
        with(support.billing) {
            call(POST, "/api/v1/reservations/${stay.id}/check-in", receptionist(), mapOf("guests" to body))
                .expect(200)
        }
        awaitSubmission(stay.id, FnrhOperation.CHECK_IN)
    }

    private fun checkOut(stay: Stay) {
        frontdesk.pay(stay.folio, frontdesk.cashier(), "400.00")
        with(support.billing) {
            call(POST, "/api/v1/reservations/${stay.id}/check-out", iam.asAdmin(), null).expect(200)
        }
        awaitSubmission(stay.id, FnrhOperation.CHECK_OUT)
    }

    private fun receptionist(): RequestPostProcessor =
        ReservationsTestSupport
            .Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))
            .auth

    private fun stubHappy(
        stay: Stay,
        remote: String,
    ) {
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", stay.cpf, DEFAULT_CPF)
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withRequestBody(matchingJsonPath("$.pessoa.documento_id.numero_documento", equalTo(stay.cpf)))
                .willReturn(okJson("""{"pessoa_id":"p-$remote"}""")),
        )
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo("p-$remote")))
                .willReturn(okJson("""{"hospede_id":"h-$remote"}""")),
        )
        FnrhTestServer.stubCheckOut_v2_4("h-$remote")
    }

    private fun statuses(id: UUID) = rows(id).map { it.operation to it.status }

    /** As chamadas ao Serpro como "MÉTODO caminho", na ordem em que chegaram. */
    private fun calls() =
        FnrhTestServer
            .requests()
            .map { "${it.method.value()} ${it.url.removePrefix(FnrhTestServer.BASE_PATH)}" }

    private companion object {
        const val DEFAULT_CPF = "52998224725"
    }
}

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
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * As duas lacunas de critério de aceite da E08, ponta a ponta: a falha que esgota as 8 tentativas, aparece na lista,
 * alerta e volta pelo `retry`; e a varredura de sigilo da trilha de auditoria e das notificações depois do fluxo
 * completo de um hóspede de dados completos.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList")
class FnrhStageFailureFlowTest(
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
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)

    @Test
    fun `eight 503s make the submission FAILED, listed and alerted, and the retry sends it once Serpro is back`() {
        // Mais que as 8 esperas (8 h 15 min): as chamadas gravadas ficam no passado (o status de outros testes).
        advance(Duration.ofHours(REWIND_HOURS).negated())
        val id = reservation()
        val submission = awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION).id
        val manager =
            iam.createUserWith(
                TestGrant(FnrhPermissions.permissions.single { it.code == FnrhPermissions.SUBMISSIONS_MANAGE }, ALL),
                TestGrant(FnrhPermissions.permissions.single { it.code == FnrhPermissions.SUBMISSIONS_READ }, ALL),
            )
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)

        repeat(MAX_ATTEMPTS) {
            sender.send(id)
            advance(Duration.ofHours(1).plusMinutes(1))
        }

        row(submission).status shouldBe FnrhSubmissionStatus.FAILED
        row(submission).attempts shouldBe MAX_ATTEMPTS
        val listed =
            with(iam) { call(GET, "/api/v1/fnrh/submissions?status=FAILED&limit=200", asTestUser(manager)) }
        listed.status shouldBe 200
        jsonMapper
            .readTree(listed.getContentAsString(Charsets.UTF_8))["items"]
            .values()
            .map { it["id"].asString() } shouldContain submission.toString()
        alertsOf(id).count { it.recipient == manager.id.value } shouldBe 1

        val retried = with(iam) { call(POST, "/api/v1/fnrh/submissions/$submission/retry", asTestUser(manager)) }
        retried.status shouldBe 200
        row(submission).status shouldBe FnrhSubmissionStatus.PENDING
        row(submission).attempts shouldBe 0
        FnrhTestServer.reset()
        val remote = remoteId()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id))
        advance(Duration.ofMinutes(2))

        sender.send(id)

        row(submission).status shouldBe FnrhSubmissionStatus.SENT
        requests("POST", "/reservas").size shouldBe 1
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION)!!.remoteId.value shouldBe remote
        rows(id).count { it.operation == FnrhOperation.REGISTER_RESERVATION } shouldBe 1
    }

    @Test
    fun `after the full flow and a failure alert no audit entry nor notification holds data of the guest`() {
        val cpf = newCpf()
        val passport = support.guests.passport("PX")
        val name = support.guests.uniqueName("Zuleica")
        val social = "Zu ${support.guests.suffix()}"
        val email = support.guests.uniqueEmail()
        val phone = uniquePhone()
        val description = "Descrição não binária ${support.guests.suffix()}"
        val guest =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile =
                                support.guests.profileBody(
                                    fullName = name,
                                    email = email,
                                    extra =
                                        mapOf(
                                            "phone" to "+55 11 $phone",
                                            "socialName" to social,
                                            "gender" to "OTHER",
                                            "genderDescription" to description,
                                        ),
                                ),
                            documents =
                                listOf(
                                    support.guests.document("CPF", cpf),
                                    support.guests.document("PASSPORT", passport, "AR"),
                                ),
                            sensitiveData = mapOf("raceColor" to "INDIGENOUS", "disability" to "VISUAL"),
                        ),
                    )["id"]
                    .asString(),
            )
        val booked = bookedToday(guest)
        checkInAndOut(booked, guest)
        iam.enableFnrh()
        stubFullFlow(cpf, remoteId())

        sender.send(booked.id)

        rows(booked.id).map { it.status }.toSet() shouldBe setOf(FnrhSubmissionStatus.SENT)
        val failing = checkedInWithFailure(guest)
        rows(failing).first().status shouldBe FnrhSubmissionStatus.FAILED
        alertsOf(failing).isNotEmpty() shouldBe true

        val leaks =
            listOf(
                name,
                social,
                email,
                description,
                cpf,
                passport,
                phone,
                phone.replace("-", ""),
                "INDIGENOUS",
                "VISUAL",
            )
        assertNoLeaks(leaks)
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun checkInAndOut(
        booked: FrontdeskTestSupport.Booked,
        guest: UUID,
    ) {
        val receptionist =
            ReservationsTestSupport
                .Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))
                .auth
        val body =
            listOf(
                mapOf(
                    "guestId" to guest,
                    "primary" to true,
                    "travelReason" to "LAZER_FERIAS",
                    "transportMode" to "AUTOMOVEL",
                    "minorAuthorization" to null,
                ),
            )
        with(support.billing) {
            call(POST, "/api/v1/reservations/${booked.id}/check-in", receptionist, mapOf("guests" to body)).expect(200)
        }
        awaitSubmission(booked.id, FnrhOperation.CHECK_IN)
        frontdesk.pay(booked.folio, frontdesk.cashier(), "400.00")
        with(support.billing) {
            call(POST, "/api/v1/reservations/${booked.id}/check-out", iam.asAdmin(), null).expect(200)
        }
        awaitSubmission(booked.id, FnrhOperation.CHECK_OUT)
    }

    private fun assertNoLeaks(leaks: List<String>) {
        val audit = dump("audit.audit_entry")
        val notifications = dump("notifications.notification")
        for (leak in leaks) {
            withClue("trilha de auditoria com $leak") { audit shouldNotContain leak }
            withClue("notificação com $leak") { notifications shouldNotContain leak }
        }
    }

    private fun uniquePhone() = "9${Random.nextInt(1000, 10_000)}-${Random.nextInt(1000, 10_000)}"

    private fun bookedToday(guest: UUID): FrontdeskTestSupport.Booked {
        val front = frontdesk.stay()
        val booked = front.book(guest, departure = today)
        front.ready()
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        return booked
    }

    private fun dump(table: String): String =
        jdbc
            .sql("SELECT t::text FROM $table t")
            .query(String::class.java)
            .list()
            .joinToString("\n")

    /** Uma segunda reserva do mesmo hóspede cujo registro o Serpro recusa (`400`), com o alerta. */
    private fun checkedInWithFailure(guest: UUID): UUID {
        val front = frontdesk.stay()
        val booked = front.book(guest)
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        FnrhTestServer.reset()
        FnrhTestServer.stubAny(400, """{"mensagem":"dado inválido"}""")
        sender.send(booked.id)
        return booked.id
    }

    private fun stubFullFlow(
        cpf: String,
        remote: String,
    ) {
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withRequestBody(matchingJsonPath("$.pessoa.documento_id.numero_documento", equalTo(cpf)))
                .willReturn(okJson("""{"pessoa_id":"p-$remote"}""")),
        )
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes"))
                .willReturn(okJson("""{"hospede_id":"h-$remote"}""")),
        )
        FnrhTestServer.stubCheckOut_v2_4("h-$remote")
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
        const val REWIND_HOURS = 9L
        const val DEFAULT_CPF = "52998224725"
    }
}

package com.zeny.hotel.fnrh

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
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * O alerta de falha no envio à FNRH (M5 E08, Task 9; D-E08-11 e 12): uma submissão que passa a `FAILED` ou `INVALID`
 * notifica quem tem `fnrh.submissions.manage` (e só ele), com o código da reserva e rótulos fixos, e nunca com dado do
 * hóspede. A falha temporária que reagenda não notifica. Cada teste cria a própria reserva.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhFailureAlertTest(
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

    private data class Alert(
        val recipient: UUID,
        val title: String,
        val body: String,
    )

    @Test
    fun `a permanent failure notifies who manages submissions and not the receptionist, with no guest data`() {
        val guest = fullGuest()
        val id = checkedIn(guest.id)
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        val receptionist = user(FnrhPermissions.SUBMISSIONS_READ)
        iam.enableFnrh()
        FnrhTestServer.stubAny(400, """{"mensagem":"dado inválido"}""")

        sender.send(id).failed shouldBe 1

        rows(id).first().status shouldBe FnrhSubmissionStatus.FAILED
        val alerts = alertsOf(id)
        alerts.count { it.recipient == manager.id.value } shouldBe 1
        alerts.filter { it.recipient == receptionist.id.value }.shouldBeEmpty()
        val alert = alerts.first { it.recipient == manager.id.value }
        alert.title shouldBe "Falha no envio à FNRH"
        alert.body shouldBe "Reserva ${codeOf(id)}: registro da reserva — falhou"
        guest.leaks.forEach { leak ->
            withClue("o alerta vazou $leak") { (alert.title + alert.body) shouldNotContain leak }
        }
    }

    @Test
    fun `an invalid check-in notifies with the invalid label and no guest data`() {
        val guest = fullGuest(gender = null)
        val id = checkedIn(guest.id)
        val manager = user(FnrhPermissions.SUBMISSIONS_MANAGE)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1

        val alert = alertsOf(id).first { it.recipient == manager.id.value }
        alert.body shouldBe "Reserva ${codeOf(id)}: check-in — dados inválidos"
        guest.leaks.forEach { leak ->
            withClue("o alerta vazou $leak") { (alert.title + alert.body) shouldNotContain leak }
        }
    }

    @Test
    fun `a temporary failure that reschedules does not notify`() {
        val id = reservation()
        user(FnrhPermissions.SUBMISSIONS_MANAGE)
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)

        sender.send(id).postponed shouldBe 1

        alertsOf(id).shouldBeEmpty()
    }

    @Test
    fun `the permission is only for the manager template and is not sensitive`() {
        val definition = FnrhPermissions.permissions.single { it.code == FnrhPermissions.SUBMISSIONS_MANAGE }
        definition.templates.keys.map { it.name } shouldBe listOf("MANAGER")
        definition.code.value shouldContain "fnrh.submissions.manage"
        definition.sensitive shouldBe false
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun user(permission: PermissionCode): TestUser =
        iam.createUserWith(TestGrant(FnrhPermissions.permissions.single { it.code == permission }, ALL))

    private fun alertsOf(reservation: UUID): List<Alert> =
        jdbc
            .sql(
                "SELECT recipient_id, title, body FROM notifications.notification " +
                    "WHERE type = 'notifications.integration-failure' AND body LIKE :code",
            ).param("code", "%${codeOf(reservation)}%")
            .query { rs, _ ->
                Alert(rs.getObject("recipient_id", UUID::class.java), rs.getString("title"), rs.getString("body"))
            }.list()

    private class FullGuest(
        val id: UUID,
        val leaks: List<String>,
    )

    /** Um hóspede com todos os campos preenchidos, inclusive nome social e descrição de gênero. */
    private fun fullGuest(gender: String? = "OTHER"): FullGuest {
        val name = support.guests.uniqueName("Zuleica")
        val social = "Zu ${support.guests.suffix()}"
        val email = support.guests.uniqueEmail()
        val description = "Descrição não binária ${support.guests.suffix()}"
        val cpf = newCpf()
        val id =
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
                                            "socialName" to social,
                                            "gender" to gender,
                                            "genderDescription" to description.takeIf { gender != null },
                                        ),
                                ),
                            documents = listOf(support.guests.document("CPF", cpf)),
                            sensitiveData = mapOf("raceColor" to "BROWN"),
                        ),
                    )["id"]
                    .asString(),
            )
        return FullGuest(id, listOf(name, social, email, description, cpf, "98765-4321"))
    }

    private fun checkedIn(guest: UUID): UUID {
        val stay = frontdesk.stay()
        val booked = stay.book(guest)
        stay.ready()
        val receptionist = iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN))
        val guests =
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
            call(
                POST,
                "/api/v1/reservations/${booked.id}/check-in",
                iam.asTestUser(receptionist),
                mapOf("guests" to guests),
            ).expect(200)
        }
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(booked.id, FnrhOperation.CHECK_IN)
        return booked.id
    }
}

package com.zeny.hotel.fnrh

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.zeny.hotel.fnrh.application.FnrhRemoteGuest
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * O envio do check-in à FNRH (M5 E08, Task 7; D-E08-6, 7, 8, 10 e 15): a validação local de todos os hóspedes antes
 * de qualquer chamada, a reserva garantida no Serpro, a pessoa reaproveitada (conhecida ou consultada por documento) ou
 * criada, o hóspede posto na reserva com o responsável antes do menor, e a conciliação da escrita em curso. Cada teste
 * cria a própria reserva e envia só as submissões dela.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhCheckInSendTest(
    @Autowired iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired private val reservationsApi: ReservationsApi,
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

    @Test
    fun `two adults get one lookup, one person and one guest each, with the CPF of the user who checked in`(
        output: CapturedOutput,
    ) {
        val already = output.all.length
        val (firstCpf, secondCpf) = newCpf() to newCpf()
        val first = fnrhGuest(firstCpf)
        val second = fnrhGuest(secondCpf)
        val receptionist = receptionist()
        val userCpf = "11144477735"
        with(iam) {
            val path = "/api/v1/fnrh/requesters/${receptionist.user.id.value}"
            call(PUT, path, asAdmin(), mapOf("cpf" to userCpf)).ok()
        }
        val id = checkedIn(listOf(stayGuest(first, primary = true), stayGuest(second)), receptionist.auth)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        for ((cpf, person) in listOf(firstCpf to "p-1-$remote", secondCpf to "p-2-$remote")) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, userCpf)
            stubRegisterPerson(cpf, person, userCpf)
            stubAddGuest(remote, person, "h-$person")
        }

        sender.send(id).sent shouldBe 2

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        checkInRow(id).inFlight shouldBe false
        requests("GET", "/pessoas/documento/CPF/$firstCpf").size shouldBe 1
        requests("GET", "/pessoas/documento/CPF/$secondCpf").size shouldBe 1
        requests("POST", "/pessoas").size shouldBe 2
        requests("POST", "/reservas/$remote/hospedes").size shouldBe 2
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).map {
            Triple(it.guestId, it.personId.value, it.guestRemoteId?.value)
        } shouldContainExactlyInAnyOrder
            listOf(
                Triple(first, "p-1-$remote", "h-p-1-$remote"),
                Triple(second, "p-2-$remote", "h-p-2-$remote"),
            )
        val log = output.all.substring(already)
        for (leak in listOf(firstCpf, secondCpf, userCpf, DEFAULT_CPF, "PARDA", "numero_documento")) {
            withClue("log leaked $leak") { log shouldNotContain leak }
        }
    }

    @Test
    fun `without a CPF of the user the default requester CPF goes, and a person already in the hotel is reused`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF, personId = "p-$remote")
        stubAddGuest(remote, "p-$remote", "h-$remote")

        sender.send(id).sent shouldBe 2

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        requests("POST", "/pessoas").shouldBeEmpty()
        val body = jsonMapper.readTree(requests("POST", "/reservas/$remote/hospedes").single().bodyAsString)
        body["pessoa_id"].asString() shouldBe "p-$remote"
        body["is_principal"].asBoolean() shouldBe true
        body["situacao_hospede_id"].asString() shouldBe "CHECKIN_REALIZADO"
    }

    @Test
    fun `a minor listed before the responsible goes after him, with the responsible guest id`() {
        val (adultCpf, minorCpf) = newCpf() to newCpf()
        val adult = fnrhGuest(adultCpf)
        val minor = fnrhGuest(minorCpf, birthDate = today.minusYears(MINOR_AGE))
        val proof = mapOf("proofType" to "PARENT_PRESENT", "responsibleGuestId" to adult, "relationship" to "PAI")
        val id = checkedIn(listOf(stayGuest(minor, minorAuthorization = proof), stayGuest(adult, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        for ((cpf, person) in listOf(adultCpf to "p-a-$remote", minorCpf to "p-m-$remote")) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
            stubRegisterPerson(cpf, person)
            stubAddGuest(remote, person, "h-$person")
        }

        sender.send(id).sent shouldBe 2

        val added = requests("POST", "/reservas/$remote/hospedes").map { jsonMapper.readTree(it.bodyAsString) }
        added.map { it["pessoa_id"].asString() } shouldContainExactly listOf("p-a-$remote", "p-m-$remote")
        added[0].has("responsavel_id") shouldBe false
        added[1]["responsavel_id"].asString() shouldBe "h-p-a-$remote"
    }

    @Test
    fun `a guest without gender makes the check-in invalid with no call to Serpro`() {
        val guest = fnrhGuest(newCpf(), profile = mapOf("gender" to null))
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.INVALID
        FnrhTestServer.requests().map { "${it.method.value()} ${it.url}" } shouldContainExactly
            listOf("POST ${FnrhTestServer.BASE_PATH}/reservas")
        errorsOf(checkInRow(id).id) shouldContainExactly listOf(Triple(guest, "person.gender", "REQUIRED"))
    }

    @Test
    fun `a disability out of the domain is invalid with a neutral error that neither the table nor the API names`() {
        val withDisability =
            fnrhGuest(newCpf(), sensitive = mapOf("raceColor" to "BROWN", "disability" to OUT_OF_DOMAIN))
        val withoutRace = fnrhGuest(newCpf(), sensitive = mapOf("disability" to OUT_OF_DOMAIN))
        val id = checkedIn(listOf(stayGuest(withDisability, primary = true), stayGuest(withoutRace)))
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1

        val submission = checkInRow(id)
        submission.status shouldBe FnrhSubmissionStatus.INVALID
        errorsOf(submission.id) shouldContainExactlyInAnyOrder
            listOf(
                Triple(withDisability, "person.sensitiveData", "INVALID_VALUE"),
                Triple(withoutRace, "person.sensitiveData", "INVALID_VALUE"),
            )
        jdbc
            .sql("SELECT count(*) FROM fnrh.submission_error WHERE field IN (:fields)")
            .param("fields", SENSITIVE_FIELDS)
            .query(Long::class.java)
            .single() shouldBe 0L
        val item =
            invalidSubmissionsPages().flatMap { it["items"].values() }.single {
                it["id"].asString() == submission.id.toString()
            }
        item["errors"].values().map { it["field"].asString() }.toSet() shouldBe setOf("person.sensitiveData")
        for (page in invalidSubmissionsPages().map { it.toString() }) {
            for (field in SENSITIVE_FIELDS + OUT_OF_DOMAIN) {
                withClue("API leaked $field") { page shouldNotContain field }
            }
        }
    }

    @Test
    fun `a 503 adding the second guest is retried without sending the first one again`() {
        val (firstCpf, secondCpf) = newCpf() to newCpf()
        val first = fnrhGuest(firstCpf)
        val second = fnrhGuest(secondCpf)
        val id = checkedIn(listOf(stayGuest(first, primary = true), stayGuest(second)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        for ((cpf, person) in listOf(firstCpf to "p-1-$remote", secondCpf to "p-2-$remote")) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
            stubRegisterPerson(cpf, person)
        }
        stubAddGuest(remote, "p-1-$remote", "h-1-$remote")
        stubAddGuestUnavailable(remote, "p-2-$remote")

        sender.send(id).postponed shouldBe 1
        checkInRow(id).status shouldBe FnrhSubmissionStatus.PENDING
        stubAddGuest(remote, "p-2-$remote", "h-2-$remote")
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-1-$remote" to "p-1-$remote")
        FnrhTestServer.server.resetRequests()
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        requests("POST", "/pessoas").shouldBeEmpty()
        requests("POST", "/reservas/$remote/hospedes").map {
            jsonMapper.readTree(it.bodyAsString)["pessoa_id"].asString()
        } shouldContainExactly
            listOf("p-2-$remote")
        remoteIds
            .guests(
                id,
                FnrhEnvironment.HOMOLOGATION,
            ).map { it.guestRemoteId?.value } shouldContainExactlyInAnyOrder
            listOf("h-1-$remote", "h-2-$remote")
    }

    @Test
    fun `a timeout adding the guest is reconciled by the guest list, with no second POST`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        stubRegisterPerson(cpf, "p-$remote")
        FnrhTestServer.stub("/reservas/$remote/hospedes", 200, """{"hospede_id":"h-$remote"}""", TIMEOUT_DELAY_MS)

        sender.send(id).postponed shouldBe 1
        checkInRow(id).inFlight shouldBe true
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-$remote" to "p-$remote")
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        requests("POST", "/reservas/$remote/hospedes").size shouldBe 1
        requests("GET", "/reservas/$remote/hospedes").size shouldBe 1
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("h-$remote")
    }

    @Test
    fun `a walk-in sends the registration first and the check-in uses its id`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val stay = frontdesk.stay()
        stay.ready()
        val created =
            with(support.billing) {
                call(
                    POST,
                    "/api/v1/walk-ins",
                    walkInOperator().auth,
                    mapOf(
                        "roomId" to stay.room,
                        "ratePlanId" to stay.plan,
                        "departure" to today.plusDays(2),
                        "adults" to 1,
                        "childAges" to emptyList<Int>(),
                        "segment" to "LEISURE",
                        "guests" to listOf(stayGuest(guest, primary = true)),
                    ),
                ).expect(201)
            }
        val id = UUID.fromString(created["reservation"]["id"].asString())
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(id, FnrhOperation.CHECK_IN)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        stubRegisterPerson(cpf, "p-$remote")
        stubAddGuest(remote, "p-$remote", "h-$remote")

        sender.send(id).sent shouldBe 2

        val writes = FnrhTestServer.requests().filter { it.method.value() == "POST" }.map { it.url }
        writes shouldContainExactly
            listOf("/reservas", "/pessoas", "/reservas/$remote/hospedes").map { FnrhTestServer.BASE_PATH + it }
    }

    @Test
    fun `a reservation without its registration submission is registered by the check-in`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        jdbc
            .sql("DELETE FROM fnrh.submission WHERE reservation_id = :id AND operation = 'REGISTER_RESERVATION'")
            .param("id", id)
            .update()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        stubRegisterPerson(cpf, "p-$remote")
        stubAddGuest(remote, "p-$remote", "h-$remote")

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas").size shouldBe 1
        remoteIds.reservation(id, FnrhEnvironment.HOMOLOGATION)?.remoteId shouldBe FnrhRemoteId(remote)
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("h-$remote")
    }

    @Test
    fun `a returning guest reuses the person of an earlier reservation without lookup nor POST`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val earlier = UuidV7.generate()
        remoteIds.saveGuest(
            FnrhRemoteGuest(earlier, guest, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId("p-old-$earlier"), null, true),
            UuidV7.generate(),
        )
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubAddGuest(remote, "p-old-$earlier", "h-$remote")

        sender.send(id).sent shouldBe 2

        FnrhTestServer.requests().filter { it.url.contains("/pessoas") }.shouldBeEmpty()
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().personId shouldBe FnrhRemoteId("p-old-$earlier")
    }

    @Test
    fun `a guest merged after being sent is not added again to the reservation`() {
        val (firstCpf, secondCpf) = newCpf() to newCpf()
        val first = fnrhGuest(firstCpf)
        val second = fnrhGuest(secondCpf)
        val id = checkedIn(listOf(stayGuest(first, primary = true), stayGuest(second)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", firstCpf, DEFAULT_CPF)
        stubRegisterPerson(firstCpf, "p-1-$remote")
        stubAddGuest(remote, "p-1-$remote", "h-1-$remote")
        // A consulta do segundo falha antes de qualquer escrita: a tentativa seguinte não tem escrita em curso.
        FnrhTestServer.stub("/pessoas/documento/CPF/$secondCpf", 503)
        sender.send(id).postponed shouldBe 1
        checkInRow(id).inFlight shouldBe false
        // O primeiro hóspede é absorvido por um perfil novo com o mesmo CPF; a reserva passa a apontar para ele.
        val survivor = fnrhGuest(firstCpf)
        mergeInto(survivor, first)
        await().atMost(Duration.ofSeconds(30)).until {
            reservationsApi.reservation(ReservationId(id))!!.guests.any { it.guestId.value == survivor }
        }
        FnrhTestServer.reset()
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", firstCpf, DEFAULT_CPF, personId = "p-1-$remote")
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", secondCpf, DEFAULT_CPF)
        stubRegisterPerson(secondCpf, "p-2-$remote")
        stubAddGuest(remote, "p-1-$remote", "h-dup-$remote")
        stubAddGuest(remote, "p-2-$remote", "h-2-$remote")
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        requests("GET", "/reservas/$remote/hospedes").shouldBeEmpty()
        requests("POST", "/reservas/$remote/hospedes").map {
            jsonMapper.readTree(it.bodyAsString)["pessoa_id"].asString()
        } shouldContainExactly
            listOf("p-2-$remote")
    }

    @Test
    fun `a guest with a gender description and every field filled leaves nothing of his in the log`(
        output: CapturedOutput,
    ) {
        val already = output.all.length
        val cpf = newCpf()
        val name = support.guests.uniqueName("Zuleica")
        val description = "Descrição não binária ${support.guests.suffix()}"
        val guest =
            fnrhGuest(
                cpf,
                profile = mapOf("fullName" to name, "gender" to "OTHER", "genderDescription" to description),
                sensitive = mapOf("raceColor" to "INDIGENOUS", "disability" to "VISUAL"),
            )
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        stubRegisterPerson(cpf, "p-$remote")
        stubAddGuest(remote, "p-$remote", "h-$remote")

        sender.send(id).sent shouldBe 2

        val person = jsonMapper.readTree(requests("POST", "/pessoas").single().bodyAsString)["pessoa"]
        person["GeneroDescricao"].asString() shouldBe description
        person["raca_id"].asString() shouldBe "INDIGENA"
        val log = output.all.substring(already)
        for (leak in listOf(cpf, name, description, "INDIGENA", "INDIGENOUS", "GeneroDescricao", "raca_id")) {
            withClue("log leaked $leak") { log shouldNotContain leak }
        }
    }

    @Test
    fun `with Serpro answering 503 to everything the check-in succeeds and the submission stays pending`() {
        val guest = fnrhGuest(newCpf())
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        jdbc
            .sql("DELETE FROM fnrh.submission WHERE reservation_id = :id AND operation = 'REGISTER_RESERVATION'")
            .param("id", id)
            .update()

        sender.send(id).postponed shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.PENDING
        checkInRow(id).attempts shouldBe 1
    }

    @Test
    fun `a check-in that survives the undo only reconciles its write in flight and creates nothing`() {
        val guest = fnrhGuest(newCpf())
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        sender.send(id).postponed shouldBe 1
        val checkIn = checkInRow(id)
        remoteIds.saveGuest(
            FnrhRemoteGuest(id, guest, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId("p-$remote"), null, false),
            checkIn.id,
        )
        store.markInFlight(checkIn.id)
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/undo-check-in", iam.asAdmin(), idempotencyKey = null).expect(200)
        }
        awaitSubmission(id, FnrhOperation.UNDO_CHECK_IN)
        FnrhTestServer.reset()
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-$remote" to "p-$remote")
        advance(Duration.ofMinutes(2))

        sender.send(id)

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        FnrhTestServer.requests().map { "${it.method.value()} ${it.url}" } shouldContainExactly
            listOf("GET ${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes")
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("h-$remote")
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Um hóspede com todos os dados que a FNRH pede: CPF, gênero, raça/cor e endereço no Brasil. */
    private fun fnrhGuest(
        cpf: String,
        birthDate: LocalDate = LocalDate.of(1988, 3, 14),
        profile: Map<String, Any?> = emptyMap(),
        sensitive: Map<String, Any?> = mapOf("raceColor" to "BROWN"),
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("birthDate" to birthDate, "nationality" to "BR") + profile,
                            ),
                        documents = listOf(support.guests.document("CPF", cpf)),
                        sensitiveData = sensitive,
                    ),
                )["id"]
                .asString(),
        )

    private fun stayGuest(
        guest: UUID,
        primary: Boolean = false,
        minorAuthorization: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to primary,
            "travelReason" to "LAZER_FERIAS",
            "transportMode" to "AUTOMOVEL",
            "minorAuthorization" to minorAuthorization,
        )

    /** Uma recepcionista sem CPF solicitante cadastrado. */
    private fun receptionist() =
        ReservationsTestSupport.Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))

    private fun walkInOperator() =
        ReservationsTestSupport.Operator(
            iam.createUserWith(
                frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN),
                frontdesk.grant(ReservationsPermissions.RESERVATIONS_MANAGE),
                frontdesk.grant(ReservationsPermissions.ASSIGNMENTS_MANAGE),
            ),
        )

    /** Uma reserva de hoje com o check-in feito por [auth], com o registro e o check-in já gravados pelo listener. */
    private fun checkedIn(
        guests: List<Map<String, Any?>>,
        auth: RequestPostProcessor = receptionist().auth,
    ): UUID {
        val stay = frontdesk.stay()
        val holder = guests.first { it["primary"] == true }["guestId"] as UUID
        val booked = stay.book(holder)
        stay.ready()
        with(support.billing) {
            call(POST, "/api/v1/reservations/${booked.id}/check-in", auth, mapOf("guests" to guests)).expect(200)
        }
        awaitSubmission(booked.id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(booked.id, FnrhOperation.CHECK_IN)
        return booked.id
    }

    /** Todas as páginas de `GET /api/v1/fnrh/submissions?status=INVALID`, lidas como o administrador. */
    private fun invalidSubmissionsPages(): List<JsonNode> =
        with(iam) {
            val pages = mutableListOf<JsonNode>()
            var cursor: String? = null
            do {
                val url = "/api/v1/fnrh/submissions?status=INVALID&limit=100" + cursor?.let { "&cursor=$it" }.orEmpty()
                val page = call(GET, url, asAdmin()).ok()
                pages.add(page)
                cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            } while (cursor != null)
            pages
        }

    private fun checkInRow(reservation: UUID): Row = rows(reservation).single { it.operation == FnrhOperation.CHECK_IN }

    private fun errorsOf(submission: UUID): List<Triple<UUID, String, String>> =
        jdbc
            .sql("SELECT guest_id, field, code FROM fnrh.submission_error WHERE submission_id = :id")
            .param("id", submission)
            .query {
                rs,
                _,
                ->
                Triple(rs.getObject("guest_id", UUID::class.java), rs.getString("field"), rs.getString("code"))
            }.list()

    private fun mergeInto(
        survivor: UUID,
        merged: UUID,
    ) {
        val path = "/api/v1/guests/$survivor"
        with(iam) {
            call(
                POST,
                "$path/merge",
                asAdmin(),
                mapOf("mergedGuestId" to merged),
                ifMatch = support.guests.etagOf(path),
            ).ok()
        }
    }

    /** `POST /pessoas` que casa pelo número do documento, para cada pessoa ter o próprio id. */
    private fun stubRegisterPerson(
        cpf: String,
        personId: String,
        requesterCpf: String = DEFAULT_CPF,
    ) {
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withHeader("cpf_solicitante", equalTo(requesterCpf))
                .withRequestBody(matchingJsonPath("$.pessoa.documento_id.numero_documento", equalTo(cpf)))
                .willReturn(okJson("""{"pessoa_id":"$personId"}""")),
        )
    }

    /** `POST /reservas/{id}/hospedes` que casa pela pessoa, para cada hóspede ter o próprio id. */
    private fun stubAddGuest(
        remote: String,
        personId: String,
        guestId: String,
    ) {
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo(personId)))
                .willReturn(okJson("""{"hospede_id":"$guestId"}""")),
        )
    }

    private fun stubAddGuestUnavailable(
        remote: String,
        personId: String,
    ) {
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes"))
                .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo(personId)))
                .willReturn(serviceUnavailable()),
        )
    }

    private companion object {
        /** O CPF solicitante padrão de [enableFnrh], só com os dígitos. */
        const val DEFAULT_CPF = "52998224725"

        /** Um código de deficiência que o perfil aceita e o domínio da FNRH não tem. */
        const val OUT_OF_DOMAIN = "ZZ_FORA_DO_DOMINIO"

        /** Os campos dos dados sensíveis (RN-19) que nunca aparecem nos erros gravados nem na API. */
        val SENSITIVE_FIELDS = listOf("person.race", "person.disabilityOption", "person.disabilityType")
    }
}

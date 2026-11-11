package com.zeny.hotel.fnrh

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
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
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationsApi
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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Ataque ao envio do check-in à FNRH (M5 E08, Task 7): privacidade (log, trilha, `integration_call`,
 * `submission_error`, `toString`), nunca duplicar pessoa nem hóspede (D-E08-6, 7, 15), a ordem responsável antes do
 * menor, a validação de todos os hóspedes antes de qualquer chamada (D-E08-10), a regra 0 e o desfazer (D-E08-8) e o
 * estado do Serpro. Cada teste cria a própria reserva e envia só as submissões dela.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass") // as portas do fluxo real; um teste por ataque
class FnrhCheckInAttackTest(
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
    @Autowired private val guestsApi: GuestsApi,
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

    // --- Privacidade ----------------------------------------------------------------------------------------------

    /** O que um hóspede com todos os campos tem de pessoal, para procurar nos registros. */
    private inner class Secrets {
        val cpf = newCpf()
        val passport = support.guests.passport("ZQ", 8)
        val name = support.guests.uniqueName("Zuleica")
        val social = support.guests.uniqueName("Zu")
        val email = support.guests.uniqueEmail()
        val street = "Rua ${support.guests.suffix()} dos Ipês"
        val description = "Descrição não binária ${support.guests.suffix()}"
        val userCpf = "11144477735"

        val all =
            listOf(
                cpf,
                passport,
                name,
                social,
                email,
                street,
                description,
                "975318642",
                "97531-8642",
                "01407-000",
                "Apto 12",
                "INDIGENOUS",
                "INDIGENA",
                "VISUAL",
                userCpf,
            )

        fun guest(): UUID =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile =
                                support.guests.profileBody(
                                    fullName = name,
                                    email = email,
                                    address = support.guests.address() + mapOf("street" to street),
                                    extra =
                                        mapOf(
                                            "socialName" to social,
                                            "gender" to "OTHER",
                                            "genderDescription" to description,
                                            "phone" to "+55 11 97531-8642",
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
    }

    private fun leaksIn(
        secrets: Secrets,
        text: String,
        where: String,
    ) {
        for (leak in secrets.all) withClue("$where leaked $leak") { text shouldNotContain leak }
    }

    private fun tableText(sql: String): String =
        jdbc
            .sql(sql)
            .query(String::class.java)
            .list()
            .joinToString("\n")

    @Test
    fun `a guest with every field leaves nothing of his in the log, the audit trail, the call log nor toString`(
        output: CapturedOutput,
    ) {
        val already = output.all.length
        val secrets = Secrets()
        val guest = secrets.guest()
        val receptionist = receptionist()
        with(iam) {
            val path = "/api/v1/fnrh/requesters/${receptionist.user.id.value}"
            call(PUT, path, asAdmin(), mapOf("cpf" to secrets.userCpf)).ok()
        }
        val id = checkedIn(listOf(stayGuest(guest, primary = true)), receptionist.auth)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", secrets.cpf, secrets.userCpf)
        stubRegisterPerson(secrets.cpf, "p-$remote", secrets.userCpf)
        stubAddGuest(remote, "p-$remote", "h-$remote")

        sender.send(id).sent shouldBe 2

        leaksIn(secrets, output.all.substring(already), "log")
        val calls = tableText("SELECT c::text FROM fnrh.integration_call c WHERE correlation_ref IN (${refs(id)})")
        withClue("integration_call is empty, the test proves nothing") { calls shouldNotBe "" }
        leaksIn(secrets, calls, "fnrh.integration_call")
        val ids = rows(id).map { it.id } + id
        for (entity in ids) {
            support.auditEntries(entity).forEach {
                leaksIn(secrets, (it["before"] as String?).orEmpty(), "audit before")
                leaksIn(secrets, (it["after"] as String?).orEmpty(), "audit after")
            }
        }
        leaksIn(secrets, tableText("SELECT t::text FROM fnrh.submission t WHERE reservation_id = '$id'"), "submission")
        leaksIn(
            secrets,
            tableText("SELECT t::text FROM fnrh.remote_guest t WHERE reservation_id = '$id'"),
            "remote_guest",
        )
        leaksIn(secrets, checkNotNullFnrhData(guest), "toString of FnrhGuestData")
        leaksIn(secrets, store.find(checkInRow(id).id).toString(), "toString of the submission")
    }

    private fun checkNotNullFnrhData(guest: UUID): String {
        val data = guestsApi.fnrhData(GuestId(guest)) ?: error("sem dados")
        val person =
            com.zeny.hotel.fnrh.domain.FnrhPersonMapper
                .toPerson(data)
        return "$data $person ${person.document}"
    }

    private fun refs(reservation: UUID): String = rows(reservation).joinToString(",") { "'${it.id}'" }

    @Test
    fun `a 400 that echoes the person and a timeout leave nothing of the guest in the log, the call log nor the errors`(
        output: CapturedOutput,
    ) {
        val already = output.all.length
        val secrets = Secrets()
        val guest = secrets.guest()
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", secrets.cpf, DEFAULT_CPF)
        val echo =
            """{"message":"pessoa invalida ${secrets.name} ${secrets.cpf} ${secrets.email} ${secrets.description}"}"""
        FnrhTestServer.stub("/pessoas", 400, echo)

        sender.send(id).failed shouldBe 1

        FnrhTestServer.stub("/pessoas", 200, """{"pessoa_id":"p-$remote"}""", TIMEOUT_DELAY_MS)
        val retried = checkInRow(id)
        retried.status shouldBe FnrhSubmissionStatus.FAILED
        leaksIn(secrets, output.all.substring(already), "log")
        leaksIn(
            secrets,
            tableText("SELECT c::text FROM fnrh.integration_call c WHERE correlation_ref IN (${refs(id)})"),
            "fnrh.integration_call",
        )
        leaksIn(
            secrets,
            tableText("SELECT t::text FROM fnrh.submission_error t WHERE submission_id = '${retried.id}'"),
            "fnrh.submission_error",
        )
        for (entity in rows(id).map { it.id }) {
            support.auditEntries(entity).forEach {
                leaksIn(secrets, (it["before"] as String?).orEmpty() + (it["after"] as String?).orEmpty(), "audit")
            }
        }
    }

    @Test
    fun `an invalid guest with every other field filled leaves nothing of his in the errors, the log nor the trail`(
        output: CapturedOutput,
    ) {
        val already = output.all.length
        val secrets = Secrets()
        val guest = secrets.guest()
        // Sem raça/cor: o hóspede é inválido e todos os outros campos continuam preenchidos.
        with(iam) {
            val path = "/api/v1/guests/$guest/sensitive-data"
            call(PUT, path, asAdmin(), mapOf("disability" to "VISUAL")).ok()
        }
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1

        val submission = checkInRow(id)
        submission.status shouldBe FnrhSubmissionStatus.INVALID
        // RN-19: o erro de raça/cor é gravado como um erro neutro, que não revela o campo sensível.
        errorsOf(submission.id).map { it.second } shouldContainExactly listOf("person.sensitiveData")
        val stored = tableText("SELECT t::text FROM fnrh.submission_error t WHERE submission_id = '${submission.id}'")
        listOf("race", "disability").forEach { stored.contains(it, ignoreCase = true) shouldBe false }
        leaksIn(secrets, output.all.substring(already), "log")
        leaksIn(
            secrets,
            tableText("SELECT t::text FROM fnrh.submission_error t WHERE submission_id = '${submission.id}'"),
            "fnrh.submission_error",
        )
        support.auditEntries(submission.id).forEach {
            leaksIn(secrets, (it["before"] as String?).orEmpty() + (it["after"] as String?).orEmpty(), "audit")
        }
        FnrhTestServer
            .requests()
            .filter { it.url.contains("/pessoas") || it.url.contains("/hospedes") }
            .shouldBeEmpty()
    }

    // --- Nunca duplicar -------------------------------------------------------------------------------------------

    @Test
    fun `a timeout on POST pessoas is reconciled by the lookup on the next attempt, with no second POST pessoas`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        FnrhTestServer.stub("/pessoas", 200, """{"pessoa_id":"p-$remote"}""", TIMEOUT_DELAY_MS)

        sender.send(id).postponed shouldBe 1
        checkInRow(id).inFlight shouldBe true
        // O Serpro gravou a pessoa apesar do timeout: a consulta por documento a acha.
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF, personId = "p-$remote")
        FnrhTestServer.stubListReservationGuests_v2_4(remote)
        stubAddGuest(remote, "p-$remote", "h-$remote")
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        requests("POST", "/pessoas").size shouldBe 1
        requests("POST", "/reservas/$remote/hospedes").size shouldBe 1
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().also {
            it.personId shouldBe FnrhRemoteId("p-$remote")
            it.guestRemoteId shouldBe FnrhRemoteId("h-$remote")
        }
    }

    @Test
    fun `a crash after the 2xx of POST pessoas, with the mark on and no id, never posts the person again`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stub("/pessoas/documento/CPF/$cpf", 503)
        sender.send(id).postponed shouldBe 1
        FnrhTestServer.reset()
        advance(Duration.ofMinutes(2))
        // Simula a queda depois do 2xx: a marca está ligada e nenhum id foi gravado.
        store.markInFlight(checkInRow(id).id)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF, personId = "p-$remote")
        FnrhTestServer.stubListReservationGuests_v2_4(remote)
        stubAddGuest(remote, "p-$remote", "h-$remote")

        sender.send(id)

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        requests("POST", "/pessoas").shouldBeEmpty()
        requests("POST", "/reservas/$remote/hospedes").size shouldBe 1
    }

    @Test
    fun `a crash after the 2xx of POST hospedes, with the mark on and no guest id, reconciles by the list`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stub("/pessoas/documento/CPF/$cpf", 503)
        sender.send(id).postponed shouldBe 1
        FnrhTestServer.reset()
        val checkIn = checkInRow(id)
        remoteIds.saveGuest(
            FnrhRemoteGuest(id, guest, FnrhEnvironment.HOMOLOGATION, FnrhRemoteId("p-$remote"), null, false),
            checkIn.id,
        )
        store.markInFlight(checkIn.id)
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-$remote" to "p-$remote")
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        FnrhTestServer.requests().map { "${it.method.value()} ${it.url}" } shouldContainExactly
            listOf("GET ${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes")
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("h-$remote")
    }

    @Test
    fun `the mark of another submission of the reservation makes the check-in list the guests before posting`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        // Uma alteração com o `PUT` sem resposta, marcada e depois encerrada à mão: a marca fica na reserva.
        val update = recordUpdate(id)
        store.markInFlight(update)
        jdbc.sql("UPDATE fnrh.submission SET status = 'DISMISSED' WHERE id = :id").param("id", update).update()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id), remote)
        FnrhTestServer.stubUpdateReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF, personId = "p-$remote")
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-$remote" to "p-$remote")
        stubAddGuest(remote, "p-$remote", "h-new-$remote")

        sender.send(id)

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        withClue(FnrhTestServer.requests().joinToString { "${it.method.value()} ${it.url}" }) {
            requests("POST", "/reservas/$remote/hospedes").shouldBeEmpty()
        }
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("h-$remote")
    }

    @Test
    fun `a guest merged after a timeout adding him is reconciled for the survivor, with no second POST hospedes`() {
        val cpf = newCpf()
        val first = fnrhGuest(cpf)
        val id = checkedIn(listOf(stayGuest(first, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        stubRegisterPerson(cpf, "p-$remote")
        FnrhTestServer.stub("/reservas/$remote/hospedes", 200, """{"hospede_id":"h-$remote"}""", TIMEOUT_DELAY_MS)
        sender.send(id).postponed shouldBe 1
        checkInRow(id).inFlight shouldBe true
        val survivor = fnrhGuest(cpf)
        mergeInto(survivor, first)
        await().atMost(Duration.ofSeconds(30)).until {
            reservationsApi.reservation(ReservationId(id))!!.guests.any { it.guestId.value == survivor }
        }
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF, personId = "p-$remote")
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-$remote" to "p-$remote")
        stubAddGuest(remote, "p-$remote", "h-dup-$remote")
        FnrhTestServer.server.resetRequests()
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        requests("POST", "/reservas/$remote/hospedes").shouldBeEmpty()
        requests("POST", "/pessoas").shouldBeEmpty()
        remoteIds
            .guests(id, FnrhEnvironment.HOMOLOGATION)
            .mapNotNull { it.guestRemoteId?.value }
            .toSet() shouldBe setOf("h-$remote")
    }

    @Test
    fun `a person known only in the other environment is not reused, it is looked up and created in this one`() {
        val cpf = newCpf()
        val guest = fnrhGuest(cpf)
        remoteIds.saveGuest(
            FnrhRemoteGuest(
                UuidV7.generate(),
                guest,
                FnrhEnvironment.PRODUCTION,
                FnrhRemoteId("p-prod-${UuidV7.generate()}"),
                FnrhRemoteId("h-prod"),
                true,
            ),
            UuidV7.generate(),
        )
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
        stubRegisterPerson(cpf, "p-hom-$remote")
        stubAddGuest(remote, "p-hom-$remote", "h-hom-$remote")

        sender.send(id).sent shouldBe 2

        requests("GET", "/pessoas/documento/CPF/$cpf").size shouldBe 1
        requests("POST", "/pessoas").size shouldBe 1
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().personId shouldBe FnrhRemoteId("p-hom-$remote")
    }

    // --- Ordem responsável antes do menor -------------------------------------------------------------------------

    @Test
    fun `two minors listed before their responsible both carry his guest id, and he goes first`() {
        val (adultCpf, firstCpf, secondCpf) = Triple(newCpf(), newCpf(), newCpf())
        val adult = fnrhGuest(adultCpf)
        val minors = listOf(firstCpf, secondCpf).map { fnrhGuest(it, birthDate = today.minusYears(MINOR_AGE)) }
        val proof = mapOf("proofType" to "PARENT_PRESENT", "responsibleGuestId" to adult, "relationship" to "PAI")
        val id =
            checkedIn(
                listOf(
                    stayGuest(minors[0], minorAuthorization = proof),
                    stayGuest(minors[1], minorAuthorization = proof),
                    stayGuest(adult, primary = true),
                ),
            )
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubEveryone(remote, listOf(adultCpf, firstCpf, secondCpf))

        sender.send(id).sent shouldBe 2

        val added = requests("POST", "/reservas/$remote/hospedes").map { jsonMapper.readTree(it.bodyAsString) }
        added.map { it["pessoa_id"].asString() }.first() shouldBe "p-$adultCpf"
        added.size shouldBe 3
        val responsibleOf = added.associate { it["pessoa_id"].asString() to it["responsavel_id"]?.asString() }
        responsibleOf["p-$adultCpf"] shouldBe null
        responsibleOf["p-$firstCpf"] shouldBe "h-$adultCpf"
        responsibleOf["p-$secondCpf"] shouldBe "h-$adultCpf"
    }

    @Test
    fun `a responsible who is not the primary guest and is listed last still goes before his minor`() {
        val (primaryCpf, responsibleCpf, minorCpf) = Triple(newCpf(), newCpf(), newCpf())
        val primary = fnrhGuest(primaryCpf)
        val responsible = fnrhGuest(responsibleCpf)
        val minor = fnrhGuest(minorCpf, birthDate = today.minusYears(MINOR_AGE))
        val proof =
            mapOf("proofType" to "PARENT_PRESENT", "responsibleGuestId" to responsible, "relationship" to "MAE")
        val id =
            checkedIn(
                listOf(
                    stayGuest(minor, minorAuthorization = proof),
                    stayGuest(primary, primary = true),
                    stayGuest(responsible),
                ),
            )
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubEveryone(remote, listOf(primaryCpf, responsibleCpf, minorCpf))

        sender.send(id).sent shouldBe 2

        val added = requests("POST", "/reservas/$remote/hospedes").map { jsonMapper.readTree(it.bodyAsString) }
        added.map { it["pessoa_id"].asString() }.last() shouldBe "p-$minorCpf"
        added.last()["responsavel_id"].asString() shouldBe "h-$responsibleCpf"
        added.first()["is_principal"].asBoolean() shouldBe true
    }

    private fun stubEveryone(
        remote: String,
        cpfs: List<String>,
    ) {
        for (cpf in cpfs) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
            stubRegisterPerson(cpf, "p-$cpf")
            stubAddGuest(remote, "p-$cpf", "h-$cpf")
        }
    }

    @Test
    fun `a minor cannot be the responsible of another minor, so the order by responsible never meets a cycle`() {
        val adult = fnrhGuest(newCpf())
        val older = fnrhGuest(newCpf(), birthDate = today.minusYears(OLDER_MINOR_AGE))
        val younger = fnrhGuest(newCpf(), birthDate = today.minusYears(MINOR_AGE))

        fun proof(responsible: UUID) =
            mapOf("proofType" to "PARENT_PRESENT", "responsibleGuestId" to responsible, "relationship" to "IRMAO")
        val stay = frontdesk.stay()
        val created =
            support.created(
                support.booking(
                    adult,
                    support.reservation(stay.type, stay.plan, today, today.plusDays(2), THREE_ADULTS),
                ),
            )["reservations"][0]
        val reservation = UUID.fromString(created["id"].asString())
        with(iam) {
            call(POST, "/api/v1/reservations/$reservation/assign-room", iam.asAdmin(), mapOf("roomId" to stay.room))
                .ok()
        }
        stay.ready()

        val body =
            mapOf(
                "guests" to
                    listOf(
                        stayGuest(younger, minorAuthorization = proof(older)),
                        stayGuest(older, minorAuthorization = proof(adult)),
                        stayGuest(adult, primary = true),
                    ),
            )
        with(support.billing) {
            call(POST, "/api/v1/reservations/$reservation/check-in", receptionist().auth, body).expect(422)
        }
    }

    // --- Validação ------------------------------------------------------------------------------------------------

    @Test
    fun `one invalid guest among two valid ones makes the check-in invalid with his error and no call`() {
        val valid = List(2) { fnrhGuest(newCpf()) }
        val broken = fnrhGuest(newCpf(), profile = mapOf("gender" to null))
        val id = checkedIn(listOf(stayGuest(valid[0], primary = true), stayGuest(broken), stayGuest(valid[1])))
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.INVALID
        errorsOf(checkInRow(id).id) shouldContainExactly listOf(Triple(broken, "person.gender", "REQUIRED"))
        FnrhTestServer
            .requests()
            .filter { it.url.contains("/pessoas") || it.url.contains("/hospedes") }
            .shouldBeEmpty()
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).shouldBeEmpty()
    }

    @Test
    fun `the same field wrong in several guests is one error per guest, and fixing the profiles then retry sends`() {
        val (firstCpf, secondCpf) = newCpf() to newCpf()
        val first = fnrhGuest(firstCpf, profile = mapOf("gender" to null), sensitive = emptyMap())
        val second = fnrhGuest(secondCpf, profile = mapOf("gender" to null))
        val id = checkedIn(listOf(stayGuest(first, primary = true), stayGuest(second)))
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)

        sender.send(id).invalid shouldBe 1

        val submission = checkInRow(id)
        errorsOf(submission.id) shouldContainExactlyInAnyOrder
            listOf(
                Triple(first, "person.gender", "REQUIRED"),
                Triple(first, "person.sensitiveData", "INVALID_VALUE"),
                Triple(second, "person.gender", "REQUIRED"),
            )
        for (guest in listOf(first, second)) fixProfile(guest, gender = "FEMALE")
        with(iam) {
            val path = "/api/v1/guests/$first/sensitive-data"
            call(PUT, path, asAdmin(), mapOf("raceColor" to "BROWN")).ok()
        }
        transactions().executeWithoutResult {
            val domain = checkNotNull(store.find(submission.id))
            domain.retry(clock.instant())
            store.save(domain)
        }
        for ((cpf, person) in listOf(firstCpf to "p-1-$remote", secondCpf to "p-2-$remote")) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
            stubRegisterPerson(cpf, person)
            stubAddGuest(remote, person, "h-$person")
        }

        sender.send(id).sent shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.SENT
        errorsOf(submission.id).shouldBeEmpty()
        requests("POST", "/reservas/$remote/hospedes").size shouldBe 2
    }

    // --- Regra 0 e desfazer ---------------------------------------------------------------------------------------

    @Test
    fun `an undone check-in with no mark and nothing in Serpro is cancelled and nothing is created`() {
        val guest = fnrhGuest(newCpf())
        val id = checkedIn(listOf(stayGuest(guest, primary = true)))
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/undo-check-in", iam.asAdmin(), idempotencyKey = null).expect(200)
        }
        awaitSubmission(id, FnrhOperation.UNDO_CHECK_IN)
        await().atMost(Duration.ofSeconds(30)).until { checkInRow(id).status == FnrhSubmissionStatus.CANCELLED }

        sender.send(id)

        FnrhTestServer
            .requests()
            .filter { it.url.contains("/pessoas") || it.url.contains("/hospedes") }
            .shouldBeEmpty()
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).shouldBeEmpty()
    }

    @Test
    fun `a check-in that survives the undo and finds the same person twice in the list fails closed, never sent`() {
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
        FnrhTestServer.stubListReservationGuests_v2_4(
            remote,
            "h-1-$remote" to "p-$remote",
            "h-2-$remote" to "p-$remote",
        )
        advance(Duration.ofMinutes(2))

        sender.send(id)

        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe null
        FnrhTestServer.requests().filter { it.method.value() == "POST" }.shouldBeEmpty()
    }

    // --- Estado do Serpro -----------------------------------------------------------------------------------------

    @Test
    fun `a permanent failure on the second guest fails the check-in and a retry never sends the first one again`() {
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
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes"))
                .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo("p-2-$remote")))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json").withBody("{}")),
        )

        sender.send(id).failed shouldBe 1

        checkInRow(id).status shouldBe FnrhSubmissionStatus.FAILED
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).mapNotNull { it.guestRemoteId?.value } shouldContainExactly
            listOf("h-1-$remote")
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-1-$remote" to "p-1-$remote")
        FnrhTestServer.server.resetRequests()
        transactions().executeWithoutResult {
            val domain = checkNotNull(store.find(checkInRow(id).id))
            domain.retry(clock.instant())
            store.save(domain)
        }

        sender.send(id)

        requests("POST", "/pessoas").shouldBeEmpty()
        requests("POST", "/reservas/$remote/hospedes").map {
            jsonMapper.readTree(it.bodyAsString)["pessoa_id"].asString()
        } shouldContainExactly
            listOf("p-2-$remote")
        requests("GET", "/pessoas/documento/CPF/$firstCpf").shouldBeEmpty()
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private fun transactions() = tx

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
                        sensitiveData = sensitive.ifEmpty { null },
                    ),
                )["id"]
                .asString(),
        )

    private fun fixProfile(
        guest: UUID,
        gender: String,
    ) {
        val path = "/api/v1/guests/$guest"
        val profile =
            support.guests.profileBody(
                extra =
                    mapOf(
                        "birthDate" to "1988-03-14",
                        "nationality" to "BR",
                        "gender" to gender,
                    ),
            )
        with(iam) {
            call(PUT, path, asAdmin(), profile, ifMatch = support.guests.etagOf(path)).ok()
        }
    }

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

    private fun receptionist() =
        ReservationsTestSupport.Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))

    private fun checkedIn(
        guests: List<Map<String, Any?>>,
        auth: RequestPostProcessor = receptionist().auth,
    ): UUID {
        val stay = frontdesk.stay()
        val holder = guests.first { it["primary"] == true }["guestId"] as UUID
        val created =
            support.created(
                support.booking(
                    holder,
                    support.reservation(stay.type, stay.plan, today, today.plusDays(2), guests.size.coerceAtLeast(2)),
                ),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        with(iam) {
            call(POST, "/api/v1/reservations/$id/assign-room", iam.asAdmin(), mapOf("roomId" to stay.room)).ok()
        }
        stay.ready()
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/check-in", auth, mapOf("guests" to guests)).expect(200)
        }
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(id, FnrhOperation.CHECK_IN)
        return id
    }

    private fun checkInRow(reservation: UUID): Row = rows(reservation).single { it.operation == FnrhOperation.CHECK_IN }

    private fun errorsOf(submission: UUID): List<Triple<UUID, String, String>> =
        jdbc
            .sql("SELECT guest_id, field, code FROM fnrh.submission_error WHERE submission_id = :id")
            .param("id", submission)
            .query { rs, _ ->
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

    private companion object {
        const val DEFAULT_CPF = "52998224725"
        const val OLDER_MINOR_AGE = 15L
        const val THREE_ADULTS = 3
    }
}

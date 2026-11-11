package com.zeny.hotel.fnrh

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSendRun
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
import com.zeny.hotel.testsupport.FnrhTestConfiguration
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ataques do testador ao envio depois de limpar a credencial da FNRH (M5 E16, Task 3): a limpeza que chega entre duas
 * chamadas do mesmo passo `CHECK_IN` de uma reserva com dois hóspedes. Esperado: nenhuma chamada depois da limpeza,
 * nada gravado como resultado (sem tentativa gasta, sem falha), a escrita em curso (`in_flight`) marcada, e, com a
 * integração religada, a conciliação sem pessoa nem hóspede duplicado. Mantém o `read-timeout` padrão (20 s). A
 * sincronização é por um latch num ouvinte do WireMock, nunca por `sleep`. O banco e a trilha são compartilhados:
 * só se conta o que o teste fez.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhTestConfiguration::class,
)
@Suppress("LongParameterList") // as portas e os apoios do fluxo real
class FnrhSettingsClearSendAttackTest(
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
    private var running: CompletableFuture<FnrhSendRun>? = null

    /** Nenhum envio fica solto no servidor compartilhado, nem se o teste falhar antes do `join`. */
    @AfterEach
    fun settle() {
        gate = null
        runCatching { running?.get(SETTLE_SECONDS, TimeUnit.SECONDS) }
        running = null
    }

    @Test
    fun `a clear between the person and the guest of the first guest stops the check-in with nothing recorded`() {
        val scenario = interrupted(Hold.PERSON)

        scenario.run shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)
        requests("POST", "/pessoas").size shouldBe 1
        requests("POST", "/reservas/${scenario.remote}/hospedes").shouldBeEmpty()
        requests("GET", "/pessoas/documento/CPF/${scenario.secondCpf}").shouldBeEmpty()
        checkIn(scenario.id) shouldBe scenario.before.copy(inFlight = true)
        guestIds(scenario).shouldBeEmpty()
    }

    @Test
    fun `a clear after the first guest was added stops before the lookup of the second and spends no attempt`() {
        val scenario = interrupted(Hold.GUEST)

        scenario.run shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)
        requests("POST", "/pessoas").size shouldBe 1
        requests("POST", "/reservas/${scenario.remote}/hospedes").size shouldBe 1
        requests("GET", "/pessoas/documento/CPF/${scenario.secondCpf}").shouldBeEmpty()
        // O hóspede foi gravado com o id remoto: a escrita em curso terminou, e a linha fica como estava.
        checkIn(scenario.id) shouldBe scenario.before
        guestIds(scenario) shouldBe listOf("h-${scenario.firstPerson}")
    }

    @Test
    fun `re-enabling after a clear between persons reconciles with no duplicated person nor guest`() {
        val scenario = interrupted(Hold.PERSON)
        stubListGuests(scenario)

        reenable()
        sender.send(scenario.id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        checkIn(scenario.id).status shouldBe FnrhSubmissionStatus.SENT
        checkIn(scenario.id).inFlight shouldBe false
        requests("POST", "/reservas").size shouldBe 1
        requests("POST", "/pessoas").map { cpfOf(it.bodyAsString) } shouldContainExactlyInAnyOrder
            listOf(scenario.firstCpf, scenario.secondCpf)
        postedGuests(scenario) shouldContainExactlyInAnyOrder listOf(scenario.firstPerson, scenario.secondPerson)
        requests("GET", "/reservas/${scenario.remote}/hospedes").size shouldBe 1
        guestIds(scenario) shouldContainExactlyInAnyOrder
            listOf("h-${scenario.firstPerson}", "h-${scenario.secondPerson}")
    }

    @Test
    fun `re-enabling after a clear between guests reconciles and adds only the second guest`() {
        val scenario = interrupted(Hold.GUEST)
        stubListGuests(scenario)

        reenable()
        sender.send(scenario.id) shouldBe FnrhSendRun(sent = 1, postponed = 0, failed = 0, invalid = 0)

        checkIn(scenario.id).status shouldBe FnrhSubmissionStatus.SENT
        checkIn(scenario.id).inFlight shouldBe false
        requests("POST", "/pessoas").map { cpfOf(it.bodyAsString) } shouldContainExactlyInAnyOrder
            listOf(scenario.firstCpf, scenario.secondCpf)
        postedGuests(scenario) shouldContainExactlyInAnyOrder listOf(scenario.firstPerson, scenario.secondPerson)
        guestIds(scenario) shouldContainExactlyInAnyOrder
            listOf("h-${scenario.firstPerson}", "h-${scenario.secondPerson}")
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private enum class Hold(
        val suffix: String,
    ) {
        PERSON("/pessoas"),
        GUEST("/hospedes"),
    }

    private class Interrupted(
        val id: UUID,
        val remote: String,
        val firstCpf: String,
        val secondCpf: String,
        val before: Row,
        val run: FnrhSendRun,
    ) {
        val firstPerson = "p-1-$remote"
        val secondPerson = "p-2-$remote"
    }

    /**
     * Cria o check-in de dois hóspedes, deixa o primeiro POST do tipo [hold] preso no WireMock (bem abaixo do
     * `read-timeout`), limpa a credencial com o pedido já recebido e espera a rodada terminar.
     */
    private fun interrupted(hold: Hold): Interrupted {
        val firstCpf = newCpf()
        val secondCpf = newCpf()
        val first = fnrhGuest(firstCpf)
        val second = fnrhGuest(secondCpf)
        val id = checkedIn(listOf(stayGuest(first, primary = true), stayGuest(second)))
        val remote = remoteId()
        val before = checkIn(id)
        val firstPerson = "p-1-$remote"
        val secondPerson = "p-2-$remote"
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        for ((cpf, person) in listOf(firstCpf to firstPerson, secondCpf to secondPerson)) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
            stubRegisterPerson(cpf, person, if (hold == Hold.PERSON && cpf == firstCpf) HOLD_MS else 0)
            stubAddGuest(remote, person, "h-$person", if (hold == Hold.GUEST && cpf == firstCpf) HOLD_MS else 0)
        }
        val received = CountDownLatch(1)
        listen()
        gate = Gate(hold.suffix, received)
        val future = CompletableFuture.supplyAsync { sender.send(id) }
        running = future
        received.await(AWAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        clear()
        val run = future.get(RUN_SECONDS, TimeUnit.SECONDS)
        return Interrupted(id, remote, firstCpf, secondCpf, before, run)
    }

    private fun stubListGuests(scenario: Interrupted) {
        val listed =
            remoteIds
                .guests(scenario.id, FnrhEnvironment.HOMOLOGATION)
                .mapNotNull { row -> row.guestRemoteId?.let { it.value to row.personId.value } }
        FnrhTestServer.stubListReservationGuests_v2_4(scenario.remote, *listed.toTypedArray())
    }

    private fun postedGuests(scenario: Interrupted): List<String> =
        requests("POST", "/reservas/${scenario.remote}/hospedes").map { personOf(it.bodyAsString) }

    private fun guestIds(scenario: Interrupted): List<String> =
        remoteIds.guests(scenario.id, FnrhEnvironment.HOMOLOGATION).mapNotNull { it.guestRemoteId?.value }

    private fun reenable() {
        iam.enableFnrh()
    }

    private fun clear() {
        with(iam) {
            val etag = call(GET, "/api/v1/fnrh/settings", asAdmin()).getHeader(HttpHeaders.ETAG)
            call(POST, "/api/v1/fnrh/settings/clear-credentials", asAdmin(), ifMatch = etag).ok()
        }
    }

    private fun cpfOf(body: String): String =
        jsonMapper.readTree(body)["pessoa"]["documento_id"]["numero_documento"].asString()

    private fun personOf(body: String): String = jsonMapper.readTree(body)["pessoa_id"].asString()

    private fun checkIn(reservation: UUID): Row = rows(reservation).single { it.operation == FnrhOperation.CHECK_IN }

    private fun fnrhGuest(cpf: String): UUID =
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

    private fun stayGuest(
        guest: UUID,
        primary: Boolean = false,
    ): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to primary,
            "travelReason" to "LAZER_FERIAS",
            "transportMode" to "AUTOMOVEL",
            "minorAuthorization" to null,
        )

    private fun checkedIn(guests: List<Map<String, Any?>>): UUID {
        val auth: RequestPostProcessor =
            ReservationsTestSupport
                .Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))
                .auth
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

    /** `POST /pessoas` que casa pelo número do documento; com [delayMs], só responde depois dele. */
    private fun stubRegisterPerson(
        cpf: String,
        personId: String,
        delayMs: Int,
    ) {
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withHeader("cpf_solicitante", equalTo(DEFAULT_CPF))
                .withRequestBody(matchingJsonPath("$.pessoa.documento_id.numero_documento", equalTo(cpf)))
                .willReturn(okJson("""{"pessoa_id":"$personId"}""").withFixedDelay(delayMs)),
        )
    }

    /** `POST /reservas/{id}/hospedes` que casa pela pessoa; com [delayMs], só responde depois dele. */
    private fun stubAddGuest(
        remote: String,
        personId: String,
        guestId: String,
        delayMs: Int,
    ) {
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/$remote/hospedes"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo(personId)))
                .willReturn(okJson("""{"hospede_id":"$guestId"}""").withFixedDelay(delayMs)),
        )
    }

    /** O pedido que a rodada espera ver chegar ao WireMock antes da limpeza. */
    private class Gate(
        val suffix: String,
        val received: CountDownLatch,
    )

    private companion object {
        const val DEFAULT_CPF = "52998224725"

        /** Bem abaixo do `read-timeout` padrão (20 s): a chamada presa termina normalmente. */
        const val HOLD_MS = 4_000
        const val AWAIT_SECONDS = 10L
        const val RUN_SECONDS = 30L
        const val SETTLE_SECONDS = 30L

        /** O pedido que o ouvinte espera; só um ouvinte é registrado por JVM e ele consulta este campo. */
        @Volatile
        var gate: Gate? = null

        private val listener: Unit by lazy {
            FnrhTestServer.server.addMockServiceRequestListener { request, _ ->
                gate
                    ?.takeIf { request.method.value() == "POST" && request.url.endsWith(it.suffix) }
                    ?.received
                    ?.countDown()
            }
        }

        /** Registra o ouvinte uma única vez no servidor compartilhado (o WireMock não o remove). */
        fun listen() = listener
    }
}

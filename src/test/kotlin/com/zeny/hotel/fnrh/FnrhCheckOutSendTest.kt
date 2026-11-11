package com.zeny.hotel.fnrh

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
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionFailure
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
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * O envio do check-out e do desfazer check-in à FNRH (M5 E08, Task 8; D-E08-3, 8 e 9): o check-out nunca sai antes do
 * check-in nem fica `SENT` vazio, repete só o que falta, e o desfazer não tem operação na API (`SENT` sem chamada ou
 * `FAILED` com `NO_REMOTE_OPERATION`). Cada teste cria a própria reserva e envia só as submissões dela.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList", "LargeClass") // as portas e os apoios do fluxo real
class FnrhCheckOutSendTest(
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
    fun `the check-out never leaves before the check-in, then goes with the check-out instant`() {
        val stay = twoGuestStay()
        val id = checkedInAndOut(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)
        FnrhTestServer.stubRegisterReservation_v2_4(remote)

        sender.send(id).postponed shouldBe 1

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.PENDING
        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.PENDING
        requests("PATCH", "/hospedes/h-1-$remote/checkout").shouldBeEmpty()
        stubGuests(stay, remote)
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 2

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.SENT
        val patches = FnrhTestServer.requests().filter { it.method.value() == "PATCH" }
        patches.map { it.url.substringAfterLast("/hospedes/").substringBefore("/checkout") } shouldContainExactly
            listOf("h-1-$remote", "h-2-$remote")
        val out = checkedOutAt(id)
        patches.map { Instant.parse(it.bodyAsString) }.toSet() shouldBe setOf(out.truncatedTo(ChronoUnit.MILLIS))
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).map { it.checkedOut } shouldContainExactly
            listOf(true, true)
    }

    @Test
    fun `a failure on the second guest check-out is retried without sending the first one again`() {
        val stay = twoGuestStay()
        val id = checkedInAndOut(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        FnrhTestServer.stub("/hospedes/h-2-$remote/checkout", 503)

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.PENDING
        requests("PATCH", "/hospedes/h-1-$remote/checkout").size shouldBe 1
        FnrhTestServer.stubCheckOut_v2_4("h-2-$remote")
        advance(Duration.ofMinutes(2))

        sender.send(id).sent shouldBe 1

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.SENT
        requests("PATCH", "/hospedes/h-1-$remote/checkout").size shouldBe 1
        requests("PATCH", "/hospedes/h-2-$remote/checkout").size shouldBe 2
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).map { it.checkedOut } shouldContainExactly
            listOf(true, true)
    }

    @Test
    fun `a check-out recorded without its check-in registers the reservation and the guests first`() {
        val stay = twoGuestStay()
        val id = checkedInAndOut(stay)
        deleteSubmissions(id, FnrhOperation.CHECK_IN, FnrhOperation.REGISTER_RESERVATION)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)

        sender.send(id).sent shouldBe 1

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.SENT
        FnrhTestServer
            .requests()
            .filter { it.method.value() != "GET" }
            .map { it.method.value() to it.url.removePrefix(FnrhTestServer.BASE_PATH) }
            .let { calls ->
                calls.first() shouldBe ("POST" to "/reservas")
                calls.count { it.first == "PATCH" } shouldBe 2
                calls.indexOfFirst { it.first == "PATCH" } shouldBe calls.size - 2
            }
        // O check-in que chega depois não chama o Serpro de novo.
        val late = recordLate(id, FnrhOperation.CHECK_IN, "checked-in:late")
        FnrhTestServer.server.resetRequests()

        sender.send(id).sent shouldBe 1

        row(late).status shouldBe FnrhSubmissionStatus.SENT
        FnrhTestServer.requests().shouldBeEmpty()
    }

    @Test
    fun `an undo before the send cancels the check-in and sends nothing about guests`() {
        val stay = oneGuestStay()
        val id = checkedIn(stay)
        undo(id)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.CANCELLED
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        guestCalls().shouldBeEmpty()
    }

    @Test
    fun `an undo after the send fails with no remote operation`() {
        val stay = oneGuestStay()
        val id = checkedIn(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        sender.send(id).sent shouldBe 2
        undo(id)
        FnrhTestServer.server.resetRequests()

        sender.send(id).failed shouldBe 1

        rowOf(id, FnrhOperation.UNDO_CHECK_IN).let {
            it.status shouldBe FnrhSubmissionStatus.FAILED
            it.lastFailure shouldBe FnrhSubmissionFailure.NO_REMOTE_OPERATION
        }
        FnrhTestServer.requests().shouldBeEmpty()
    }

    @Test
    fun `an undone check-in with a write in flight is reconciled and the undo fails when Serpro had the guest`() {
        val (id, remote) = undoneWithWriteInFlight(stay = oneGuestStay())
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-1-$remote" to "p-1-$remote")

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.FAILED
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).lastFailure shouldBe FnrhSubmissionFailure.NO_REMOTE_OPERATION
        guestCalls().filter { it.first == "POST" }.shouldBeEmpty()
    }

    @Test
    fun `an undone check-in with a write in flight is reconciled and the undo is sent when Serpro had no guest`() {
        val (id, _) = undoneWithWriteInFlight(stay = oneGuestStay())
        FnrhTestServer.stubListReservationGuests_v2_4(remote(id))

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        guestCalls().filter { it.first == "POST" }.shouldBeEmpty()
    }

    @Test
    fun `a returning guest whose guest POST timed out is reconciled after the undo, and the undo fails`() {
        val (id, remote) = undoneWithWriteInFlight(stay = oneGuestStay(), person = PersonSource.KNOWN)
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-1-$remote" to "p-1-$remote")

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.FAILED
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).lastFailure shouldBe FnrhSubmissionFailure.NO_REMOTE_OPERATION
        requests("GET", "/reservas/$remote/hospedes").size shouldBe 1
        guestCalls().filter { it.first == "POST" }.shouldBeEmpty()
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).single().guestRemoteId shouldBe FnrhRemoteId("h-1-$remote")
    }

    @Test
    fun `a guest found by document whose guest POST timed out is reconciled after the undo, and the undo fails`() {
        val (id, remote) = undoneWithWriteInFlight(stay = oneGuestStay(), person = PersonSource.FOUND_BY_DOCUMENT)
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-1-$remote" to "p-1-$remote")

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.FAILED
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).lastFailure shouldBe FnrhSubmissionFailure.NO_REMOTE_OPERATION
        guestCalls().filter { it.first == "POST" }.shouldBeEmpty()
    }

    @Test
    fun `a returning guest whose guest POST timed out and never reached Serpro lets the undo be sent`() {
        val (id, remote) = undoneWithWriteInFlight(stay = oneGuestStay(), person = PersonSource.KNOWN)
        FnrhTestServer.stubListReservationGuests_v2_4(remote)

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
        requests("GET", "/reservas/$remote/hospedes").size shouldBe 1
        guestCalls().filter { it.first == "POST" }.shouldBeEmpty()
    }

    @Test
    fun `an undo superseded by a new check-in before the send is sent with no call`() {
        val stay = oneGuestStay()
        val id = checkedIn(stay)
        undo(id)
        checkInCall(id, stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubAny(503)
        FnrhTestServer.stubRegisterReservation_v2_4(remote)

        sender.send(id)

        val checkIns = rows(id).filter { it.operation == FnrhOperation.CHECK_IN }.map { it.status }
        checkIns shouldContainExactly listOf(FnrhSubmissionStatus.CANCELLED, FnrhSubmissionStatus.PENDING)
        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private class Stay(
        val booked: FrontdeskTestSupport.Booked,
        val guests: List<UUID>,
        val cpfs: List<String>,
    )

    private val remotes = mutableMapOf<UUID, String>()

    private fun remote(reservation: UUID): String = remotes.getValue(reservation)

    private fun oneGuestStay() = stayOf(1)

    private fun twoGuestStay() = stayOf(2)

    /** Uma reserva de hoje (day use, com a UH pronta) com [count] hóspedes, o primeiro principal. */
    private fun stayOf(count: Int): Stay {
        val cpfs = List(count) { newCpf() }
        val guests = cpfs.map(::fnrhGuest)
        val frontStay = frontdesk.stay()
        val booked = frontStay.book(guests.first(), departure = today)
        frontStay.ready()
        return Stay(booked, guests, cpfs)
    }

    private fun checkInCall(
        id: UUID,
        stay: Stay,
    ) {
        val body = stay.guests.mapIndexed { index, guest -> stayGuest(guest, primary = index == 0) }
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/check-in", receptionist(), mapOf("guests" to body)).expect(200)
        }
    }

    /** O check-in feito, com o registro e o check-in já gravados pelo listener. */
    private fun checkedIn(stay: Stay): UUID {
        val id = stay.booked.id
        checkInCall(id, stay)
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(id, FnrhOperation.CHECK_IN)
        return id
    }

    /** O check-in e o check-out (conta quitada) feitos, com as submissões gravadas e ainda `PENDING`. */
    private fun checkedInAndOut(stay: Stay): UUID {
        val id = checkedIn(stay)
        frontdesk.pay(stay.booked.folio, frontdesk.cashier(), "400.00")
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/check-out", iam.asAdmin(), null).expect(200)
        }
        awaitSubmission(id, FnrhOperation.CHECK_OUT)
        return id
    }

    private fun undo(id: UUID) {
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/undo-check-in", iam.asAdmin(), idempotencyKey = null).expect(200)
        }
        awaitSubmission(id, FnrhOperation.UNDO_CHECK_IN)
    }

    /**
     * Stubs de todos os hóspedes da estadia: a consulta por documento (sem pessoa), o cadastro da pessoa
     * `p-n-<remote>`, o hóspede `h-n-<remote>` e o check-out dele. O [remote] é o id da reserva no Serpro.
     */
    private fun stubGuests(
        stay: Stay,
        remote: String,
    ) {
        stay.cpfs.forEachIndexed { index, cpf ->
            val person = "p-${index + 1}-$remote"
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", cpf, DEFAULT_CPF)
            stubRegisterPerson(cpf, person)
            stubAddGuest(remote, person, "h-${index + 1}-$remote")
            FnrhTestServer.stubCheckOut_v2_4("h-${index + 1}-$remote")
        }
    }

    /** De onde vem a pessoa do hóspede no envio: criada, já conhecida de outra reserva ou achada pelo documento. */
    private enum class PersonSource { NEW, KNOWN, FOUND_BY_DOCUMENT }

    /**
     * O check-in com um `POST` de hóspede sem resposta (a escrita em curso) e o check-in desfeito: o `CHECK_IN` não é
     * cancelado. A pessoa `p-1-<remote>` vem de [person]. Devolve a reserva e o id remoto; o Serpro já está sem os
     * stubs do envio.
     */
    private fun undoneWithWriteInFlight(
        stay: Stay,
        person: PersonSource = PersonSource.NEW,
    ): Pair<UUID, String> {
        val id = checkedIn(stay)
        val remote = remoteId()
        remotes[id] = remote
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        when (person) {
            PersonSource.NEW -> {
                FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", stay.cpfs.single(), DEFAULT_CPF)
                stubRegisterPerson(stay.cpfs.single(), "p-1-$remote")
            }

            PersonSource.KNOWN -> {
                val earlier = UuidV7.generate()
                remoteIds.saveGuest(
                    FnrhRemoteGuest(
                        earlier,
                        stay.guests.single(),
                        FnrhEnvironment.HOMOLOGATION,
                        FnrhRemoteId("p-1-$remote"),
                        FnrhRemoteId("h-old-$remote"),
                        checkedOut = true,
                    ),
                    UuidV7.generate(),
                )
            }

            PersonSource.FOUND_BY_DOCUMENT -> {
                FnrhTestServer.stubFindPersonByDocument_v2_4(
                    "CPF",
                    stay.cpfs.single(),
                    DEFAULT_CPF,
                    personId = "p-1-$remote",
                )
            }
        }
        FnrhTestServer.stub("/reservas/$remote/hospedes", 200, """{"hospede_id":"h-1-$remote"}""", TIMEOUT_DELAY_MS)
        sender.send(id).postponed shouldBe 1
        rowOf(id, FnrhOperation.CHECK_IN).inFlight shouldBe true
        undo(id)
        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.PENDING
        FnrhTestServer.reset()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        advance(Duration.ofMinutes(2))
        return id to remote
    }

    private fun rowOf(
        reservation: UUID,
        operation: FnrhOperation,
    ): Row = rows(reservation).single { it.operation == operation }

    private fun deleteSubmissions(
        reservation: UUID,
        vararg operations: FnrhOperation,
    ) {
        for (operation in operations) {
            jdbc
                .sql("DELETE FROM fnrh.submission WHERE reservation_id = :id AND operation = :operation")
                .param("id", reservation)
                .param("operation", operation.name)
                .update()
        }
    }

    /** Uma submissão que o listener grava depois das demais (reentrega do outbox), de versão menor que o check-out. */
    private fun recordLate(
        reservation: UUID,
        operation: FnrhOperation,
        eventKey: String,
    ): UUID {
        val late =
            FnrhSubmission.create(
                reservationId = reservation,
                reservationCode = codeOf(reservation),
                operation = operation,
                eventKey = eventKey,
                causalVersion = 1,
                performedBy = null,
                occurredAt = clock.instant(),
                now = clock.instant(),
            )
        store.recordIfAbsent(late) shouldBe true
        return late.id
    }

    private fun checkedOutAt(reservation: UUID): Instant =
        checkNotNull(reservationsApi.reservation(ReservationId(reservation)))
            .guests
            .map { checkNotNull(it.checkedOutAt) }
            .distinct()
            .single()

    private fun guestCalls() =
        FnrhTestServer
            .requests()
            .map { it.method.value() to it.url.removePrefix(FnrhTestServer.BASE_PATH) }
            .filter { (_, url) -> url.startsWith("/pessoas") || url.contains("/hospedes") }

    private fun fnrhGuest(cpf: String): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra =
                                    mapOf(
                                        "birthDate" to java.time.LocalDate.of(1988, 3, 14),
                                        "nationality" to "BR",
                                    ),
                            ),
                        documents = listOf(support.guests.document("CPF", cpf)),
                        sensitiveData = mapOf("raceColor" to "BROWN"),
                    ),
                )["id"]
                .asString(),
        )

    private fun stayGuest(
        guest: UUID,
        primary: Boolean,
    ): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to primary,
            "travelReason" to "LAZER_FERIAS",
            "transportMode" to "AUTOMOVEL",
            "minorAuthorization" to null,
        )

    private fun receptionist(): RequestPostProcessor =
        ReservationsTestSupport
            .Operator(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.STAYS_CHECK_IN)))
            .auth

    private fun stubRegisterPerson(
        cpf: String,
        personId: String,
    ) {
        FnrhTestServer.server.stubFor(
            post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
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
        /** O CPF solicitante padrão de [enableFnrh], só com os dígitos. */
        const val DEFAULT_CPF = "52998224725"
    }
}

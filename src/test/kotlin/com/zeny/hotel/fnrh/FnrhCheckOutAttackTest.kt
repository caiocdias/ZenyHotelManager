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
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
 * Ataque ao envio do check-out e do desfazer check-in à FNRH (M5 E08, Task 8; D-E08-3, 8 e 9): o check-out nunca é
 * `SENT` vazio (check-in não saído, nunca gravado, inválido, parcial, antecipado), repete só o que falta sem regredir
 * `checkedOut` nem perder ids, a ordem causal vale com o Serpro alternando falhas, o desfazer só decide pelo estado
 * e nada de dado de hóspede vai a log, trilha ou `integration_call`. Cada teste cria a própria reserva.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions") // as portas do fluxo real; um teste por ataque
class FnrhCheckOutAttackTest(
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

    // --- Nunca Sent vazio -----------------------------------------------------------------------------------------

    @Test
    fun `a check-out whose check-in is invalid is invalid too, with no guest call and no leak of the guest`(
        output: CapturedOutput,
    ) {
        val already = output.all.length
        val name = support.guests.uniqueName("Zuleica")
        val cpf = newCpf()
        val guest = fnrhGuest(cpf, sensitive = null, fullName = name)
        val stay = stayWith(listOf(guest), listOf(cpf))
        val id = checkedInAndOut(stay)
        deleteSubmissions(id, FnrhOperation.CHECK_IN)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.INVALID
        requests("PATCH", "").shouldBeEmpty()
        FnrhTestServer.requests().filter { it.url.contains("/pessoas") || it.url.contains("/hospedes") }.shouldBeEmpty()
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).filter { it.checkedOut }.shouldBeEmpty()
        val leaks =
            output.all.substring(already) +
                tableText("SELECT c::text FROM fnrh.integration_call c") +
                tableText("SELECT t::text FROM fnrh.submission_error t") +
                tableText("SELECT t::text FROM fnrh.submission t WHERE reservation_id = '$id'")
        for (secret in listOf(cpf, name)) withClue("leaked $secret") { leaks shouldNotContain secret }
    }

    @Test
    fun `an invalid check-in in the queue holds the check-out, which never leaves`() {
        val cpf = newCpf()
        val stay = stayWith(listOf(fnrhGuest(cpf, sensitive = null)), listOf(cpf))
        val id = checkedInAndOut(stay)
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remoteId())

        sender.send(id).invalid shouldBe 1
        advance(Duration.ofMinutes(30))
        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_IN).status shouldBe FnrhSubmissionStatus.INVALID
        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.PENDING
        requests("PATCH", "").shouldBeEmpty()
    }

    @Test
    fun `a check-out with the check-in half sent never patches the first guest before the second is registered`() {
        val stay = twoGuestStay()
        val id = checkedInAndOut(stay)
        deleteSubmissions(id, FnrhOperation.CHECK_IN)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        FnrhTestServer.stub("/reservas/$remote/hospedes", 503)
        stubAddGuest(remote, "p-1-$remote", "h-1-$remote")

        sender.send(id).postponed shouldBe 1

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.PENDING
        FnrhTestServer.requests().filter { it.method.value() == "PATCH" }.shouldBeEmpty()
        FnrhTestServer.reset()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        FnrhTestServer.stubListReservationGuests_v2_4(remote, "h-1-$remote" to "p-1-$remote")
        advance(Duration.ofMinutes(30))

        sender.send(id).sent shouldBe 1

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.SENT
        requests("PATCH", "/hospedes/h-1-$remote/checkout").size shouldBe 1
        requests("PATCH", "/hospedes/h-2-$remote/checkout").size shouldBe 1
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).map { it.checkedOut } shouldContainExactly
            listOf(true, true)
    }

    @Test
    fun `an early check-out leaves with the check-out instant, before the departure date`() {
        val stay = stayOf(1, departure = today.plusDays(2))
        val id = checkedInAndOut(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)

        sender.send(id).sent shouldBe 3

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.SENT
        val patch = requests("PATCH", "/hospedes/h-1-$remote/checkout").single()
        Instant.parse(patch.bodyAsString) shouldBe checkedOutAt(id).truncatedTo(ChronoUnit.MILLIS)
    }

    @Test
    fun `a guest without his own check-out instant gets the event instant`() {
        val stay = oneGuestStay()
        val id = checkedInAndOut(stay)
        jdbc
            .sql("UPDATE reservations.reservation_guest SET checked_out_at = NULL WHERE reservation_id = :id")
            .param("id", id)
            .update()
        val occurred =
            jdbc
                .sql("SELECT occurred_at FROM fnrh.submission WHERE reservation_id = :id AND operation = 'CHECK_OUT'")
                .param("id", id)
                .query { rs, _ -> rs.getTimestamp("occurred_at").toInstant() }
                .single()
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)

        sender.send(id).sent shouldBe 3

        val patch = requests("PATCH", "/hospedes/h-1-$remote/checkout").single()
        Instant.parse(patch.bodyAsString) shouldBe occurred.truncatedTo(ChronoUnit.MILLIS)
    }

    // --- Repetição sem regressão ----------------------------------------------------------------------------------

    @Test
    fun `a permanent failure on the second guest check-out keeps the first one done and the ids intact`() {
        val stay = twoGuestStay()
        val id = checkedInAndOut(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        FnrhTestServer.stub("/hospedes/h-2-$remote/checkout", 400, """{"message":"x"}""")

        sender.send(id)

        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.FAILED
        val remoteGuests = remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).sortedBy { it.guestRemoteId?.value }
        remoteGuests.map { it.guestRemoteId?.value } shouldContainExactly listOf("h-1-$remote", "h-2-$remote")
        remoteGuests.map { it.personId.value } shouldContainExactly listOf("p-1-$remote", "p-2-$remote")
        remoteGuests.map { it.checkedOut } shouldContainExactly listOf(true, false)
    }

    @Test
    fun `a check-in or a check-out delivered again after the send changes nothing and checkedOut stays true`() {
        val stay = twoGuestStay()
        val id = checkedInAndOut(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        sender.send(id).sent shouldBe 3
        val before = remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).sortedBy { it.guestRemoteId?.value }
        val lateIn = recordLate(id, FnrhOperation.CHECK_IN, "checked-in:late")
        val lateOut = recordLate(id, FnrhOperation.CHECK_OUT, "checked-out:late")
        val lateUndo = recordLate(id, FnrhOperation.UNDO_CHECK_IN, "undone:late")
        FnrhTestServer.server.resetRequests()

        sender.send(id).sent shouldBe 3

        listOf(lateIn, lateOut, lateUndo).forEach { row(it).status shouldBe FnrhSubmissionStatus.SENT }
        FnrhTestServer.requests().shouldBeEmpty()
        remoteIds.guests(id, FnrhEnvironment.HOMOLOGATION).sortedBy { it.guestRemoteId?.value } shouldBe before
        before.map { it.checkedOut } shouldContainExactly listOf(true, true)
    }

    // --- Desfazer -------------------------------------------------------------------------------------------------

    @Test
    fun `an undo sent after the guests reached Serpro fails and holds the later check-in and check-out`() {
        val stay = oneGuestStay()
        val id = checkedIn(stay)
        val remote = remoteId()
        iam.enableFnrh()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        sender.send(id).sent shouldBe 2
        undo(id)
        sender.send(id).failed shouldBe 1
        checkInCall(id, stay)
        frontdesk.pay(stay.booked.folio, frontdesk.cashier(), "400.00")
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/check-out", iam.asAdmin(), null).expect(200)
        }
        awaitSubmission(id, FnrhOperation.CHECK_OUT)
        FnrhTestServer.server.resetRequests()

        advance(Duration.ofMinutes(30))
        sender.send(id)

        rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.FAILED
        rows(id).filter { it.operation == FnrhOperation.CHECK_IN }.map { it.status } shouldContainExactly
            listOf(FnrhSubmissionStatus.SENT, FnrhSubmissionStatus.PENDING)
        rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.PENDING
        FnrhTestServer.requests().shouldBeEmpty()
    }

    // --- Ordem causal ---------------------------------------------------------------------------------------------

    @Test
    fun `check-in, undo, new check-in and check-out with Serpro failing in turns never send the check-out first`() {
        val stay = oneGuestStay()
        val id = checkedIn(stay)
        undo(id)
        checkInCall(id, stay)
        awaitSecondCheckIn(id)
        frontdesk.pay(stay.booked.folio, frontdesk.cashier(), "400.00")
        with(support.billing) {
            call(POST, "/api/v1/reservations/$id/check-out", iam.asAdmin(), null).expect(200)
        }
        awaitSubmission(id, FnrhOperation.CHECK_OUT)
        val remote = remoteId()
        iam.enableFnrh()
        // Rodada 1: o Serpro cai em tudo.
        FnrhTestServer.stubAny(503)
        sender.send(id).postponed shouldBe 1
        // Rodada 2: a reserva sai, o hóspede não.
        FnrhTestServer.reset()
        FnrhTestServer.stubFindReservationByCode_v2_4(codeOf(id))
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", stay.cpfs.single(), DEFAULT_CPF)
        stubRegisterPerson(stay.cpfs.single(), "p-1-$remote")
        FnrhTestServer.stub("/reservas/$remote/hospedes", 503)
        advance(Duration.ofMinutes(30))
        sender.send(id)
        // Rodada 3: tudo no ar.
        FnrhTestServer.reset()
        FnrhTestServer.stubRegisterReservation_v2_4(remote)
        stubGuests(stay, remote)
        FnrhTestServer.stubListReservationGuests_v2_4(remote)
        advance(Duration.ofHours(1))
        sender.send(id)
        advance(Duration.ofHours(1))
        sender.send(id)

        withClue("rows ${rows(id).map { it.operation to it.status }}") {
            rowOf(id, FnrhOperation.UNDO_CHECK_IN).status shouldBe FnrhSubmissionStatus.SENT
            rowOf(id, FnrhOperation.CHECK_OUT).status shouldBe FnrhSubmissionStatus.SENT
        }
        val writes =
            FnrhTestServer
                .requests()
                .filter { it.method.value() != "GET" }
                .map { it.method.value() to it.url.removePrefix(FnrhTestServer.BASE_PATH) }
        val firstPatch = writes.indexOfFirst { it.first == "PATCH" }
        val lastGuestPost = writes.indexOfLast { it.second.endsWith("/hospedes") }
        withClue("writes $writes") {
            (firstPatch > lastGuestPost) shouldBe true
            writes.count { it.second.endsWith("/hospedes") } shouldBe 1
            writes.count { it.first == "PATCH" } shouldBe 1
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private class Stay(
        val booked: FrontdeskTestSupport.Booked,
        val guests: List<UUID>,
        val cpfs: List<String>,
    )

    private fun oneGuestStay() = stayOf(1)

    private fun twoGuestStay() = stayOf(2)

    private fun stayOf(
        count: Int,
        departure: java.time.LocalDate = today,
    ): Stay {
        val cpfs = List(count) { newCpf() }
        return stayWith(cpfs.map { fnrhGuest(it) }, cpfs, departure)
    }

    private fun stayWith(
        guests: List<UUID>,
        cpfs: List<String>,
        departure: java.time.LocalDate = today,
    ): Stay {
        val frontStay = frontdesk.stay()
        val booked = frontStay.book(guests.first(), departure = departure)
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

    private fun checkedIn(stay: Stay): UUID {
        val id = stay.booked.id
        checkInCall(id, stay)
        awaitSubmission(id, FnrhOperation.REGISTER_RESERVATION)
        awaitSubmission(id, FnrhOperation.CHECK_IN)
        return id
    }

    private fun awaitSecondCheckIn(id: UUID) {
        org.awaitility.Awaitility
            .await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(50))
            .until { rows(id).count { it.operation == FnrhOperation.CHECK_IN } == 2 }
    }

    private fun checkedInAndOut(
        stay: Stay,
        amount: String = "400.00",
    ): UUID {
        val id = checkedIn(stay)
        frontdesk.pay(stay.booked.folio, frontdesk.cashier(), amount)
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

    private fun rowOf(
        reservation: UUID,
        operation: FnrhOperation,
    ): Row = rows(reservation).single { it.operation == operation }

    private fun tableText(sql: String): String =
        jdbc
            .sql(sql)
            .query(String::class.java)
            .list()
            .joinToString("\n")

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

    private fun fnrhGuest(
        cpf: String,
        sensitive: Map<String, Any?>? = mapOf("raceColor" to "BROWN"),
        fullName: String = support.guests.uniqueName(),
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                fullName = fullName,
                                extra =
                                    mapOf(
                                        "birthDate" to java.time.LocalDate.of(1988, 3, 14),
                                        "nationality" to "BR",
                                    ),
                            ),
                        documents = listOf(support.guests.document("CPF", cpf)),
                        sensitiveData = sensitive,
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
        const val DEFAULT_CPF = "52998224725"
    }
}

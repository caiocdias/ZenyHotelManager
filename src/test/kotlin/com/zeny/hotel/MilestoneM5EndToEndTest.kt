package com.zeny.hotel

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.FnrhShortReadTimeoutConfiguration
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhSendRun
import com.zeny.hotel.fnrh.application.FnrhSubmissionSender
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.rates.RatePricesTestSupport
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport.Operator
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Cenário ponta a ponta do marco M5 (gate, E12), pela API HTTP, com os módulos reais (só a FNRH no WireMock) e os
 * modelos de role da SPEC §3.1 que o sistema entrega (Gerente e Recepcionista); o que é só do Administrador (ligar a
 * FNRH, a política de early/late) fica com o administrador inicial. O Gerente cadastra o tipo de UH, as UHs, o plano e
 * os preços; a Recepcionista cria a reserva do titular com um menor, cota o early check-in, tem o check-in recusado
 * sem UH alocada, com a UH suja e com o menor sem comprovação (sem nada gravado em cada recusa) e faz o check-in com a
 * autorização do menor, a caução e o early cotado; as submissões da reserva chegam ao Serpro em ordem, com o titular e
 * o menor como pessoas distintas. Cada teste cria os próprios cadastros, envia só as submissões da própria reserva e
 * devolve a configuração global (FNRH, política, relógio) no fim.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList") // os beans que o cenário usa
class MilestoneM5EndToEndTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val sender: FnrhSubmissionSender,
    @Autowired private val remoteIds: FnrhRemoteIds,
    @Autowired private val clock: MutableClock,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing: BillingTestSupport = support.billing
    private val rooms: RoomsTestSupport = support.inventory.rooms
    private val rates: RatesTestSupport = support.rates
    private val prices: RatePricesTestSupport = support.prices
    private val guests: GuestsTestSupport = support.guests
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)

    /** A Recepcionista, pelo modelo de role do boot. */
    private val receptionist: Operator by lazy { Operator(userWithTemplate("RECEPTIONIST")) }

    /** O PIN de aprovação do Gerente, sorteado. */
    private val managerPin: String = (PIN_MIN..PIN_MAX).random().toString()

    /** O Gerente, pelo modelo de role do boot, com o PIN [managerPin]. */
    private val manager: Operator by lazy {
        Operator(userWithTemplate("MANAGER").also { iam.setPin(it, managerPin) })
    }

    @Test
    @Suppress("LongMethod") // o cenário do marco, passo a passo
    fun `a guest arrives, is checked in with early check-in and the stay reaches FNRH in order`() {
        FnrhTestServer.reset()
        jdbc.resetFnrhSettings()
        val originalPolicy = frontdesk.policy()
        clock.advance(REWIND.negated())
        try {
            // --- Preparo: a FNRH ligada, os cadastros do Gerente, o early com valor e os stubs do Serpro ---------
            iam.enableFnrh()
            val stay = newStay()
            val checkInTime = LocalTime.parse(originalPolicy["checkInTime"].toString())
            frontdesk.putPolicy(
                originalPolicy +
                    mapOf("earlyCheckIn" to mapOf("limitTime" to "${checkInTime.minusHours(3)}", "fee" to EARLY_FEE)),
            )
            stubFnrhFor(stay)

            // --- A Recepcionista reserva e cota o early (o preço é informado antes) -------------------------------
            val booked = book(stay)
            val quote = api(GET, "/api/v1/reservations/${booked.id}/early-late-quote", receptionist).ok()
            quote["earlyCheckIn"]["offered"].asBoolean() shouldBe true
            val fee = quote["earlyCheckIn"]["fee"].asString()
            fee shouldBe EARLY_FEE
            val proof = minorProof(stay.minor)
            val complete = checkInBody(stay, fee, proof)

            // --- Recusas com o pedido completo: nada gravado em nenhuma -------------------------------------------
            checkIn(booked.id, complete).problem(409, "ROOM_NOT_ASSIGNED")
            nothingWritten(booked, stay.room)

            api(POST, "/api/v1/reservations/${booked.id}/assign-room", receptionist, mapOf("roomId" to stay.room)).ok()
            housekeeping(stay.room, "MARK_DIRTY")
            checkIn(booked.id, complete).problem(409, "ROOM_NOT_READY").let {
                it["roomId"].asString() shouldBe stay.room.toString()
                it["housekeepingStatus"].asString() shouldBe "DIRTY"
            }
            nothingWritten(booked, stay.room)

            housekeeping(stay.room, "MARK_CLEAN")
            housekeeping(stay.room, "MARK_INSPECTED")
            checkIn(booked.id, checkInBody(stay, fee, minorProof = null)).problem(422, "MINOR_AUTHORIZATION_REQUIRED")
            nothingWritten(booked, stay.room)

            // --- Check-in com o menor autorizado, a caução e o early cotado ---------------------------------------
            val response = checkIn(booked.id, complete).expect(200)
            response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
            response["reservation"]["roomId"].asString() shouldBe stay.room.toString()
            response["reservation"]["checkIn"]["by"].asString() shouldBe
                receptionist.user.id.value
                    .toString()
            response["room"]["occupancy"].asString() shouldBe "OCCUPIED"
            response["room"]["guestCount"].asInt() shouldBe 2
            response["preAuthorization"]["status"].asString() shouldBe "ACTIVE"
            response["earlyCheckIn"]["amount"].asString() shouldBe fee
            support.status(booked.id) shouldBe "IN_HOUSE"
            roomsApi.status(RoomId(stay.room))!!.let {
                it.occupancy.name shouldBe "OCCUPIED"
                it.guestCount shouldBe 2
            }
            frontdesk.preAuthorizations(booked.folio).map { it["status"].asString() } shouldContainExactly
                listOf("ACTIVE")
            support.postings(booked.folio).single().let {
                it["key"] shouldBe "ROOM_CHARGE"
                it["amount"] shouldBe fee
                it["description"] shouldBe "Early check-in"
            }
            support.lifecyclePublications(GuestCheckedIn::class.java, booked.id, STAY_LISTENER) shouldHaveSize 1

            // --- A FNRH: as submissões da reserva enviadas, em ordem, com o titular e o menor distintos --------
            sendOwnFnrhSubmissions(booked.id, FnrhOperation.REGISTER_RESERVATION, FnrhOperation.CHECK_IN)
            fnrhStatuses(booked.id) shouldContainExactly
                listOf(
                    FnrhOperation.REGISTER_RESERVATION to FnrhSubmissionStatus.SENT,
                    FnrhOperation.CHECK_IN to FnrhSubmissionStatus.SENT,
                )
            val calls = fnrhCallsOf(stay, booked.code)
            calls.map { "${it.method.value()} ${it.url.removePrefix(FnrhTestServer.BASE_PATH)}" } shouldContainExactly
                listOf(
                    "POST /reservas",
                    "GET /pessoas/documento/CPF/${stay.holderFnrh.cpf}",
                    "POST /pessoas",
                    "POST /reservas/${stay.remote}/hospedes",
                    "GET /pessoas/documento/CPF/${stay.minorFnrh.cpf}",
                    "POST /pessoas",
                    "POST /reservas/${stay.remote}/hospedes",
                )
            val added = calls.filter { it.url.endsWith("/hospedes") }.map { jsonMapper.readTree(it.bodyAsString) }
            withClue("o titular e o menor são pessoas distintas, o menor com o titular como responsável") {
                added.map { it["pessoa_id"].asString() } shouldContainExactly
                    listOf(stay.holderFnrh.person, stay.minorFnrh.person)
                added[0]["is_principal"].asBoolean() shouldBe true
                added[0].has("responsavel_id") shouldBe false
                added[1]["responsavel_id"].asString() shouldBe stay.holderFnrh.guest
            }
            remoteIds
                .reservation(booked.id, FnrhEnvironment.HOMOLOGATION)
                .shouldNotBeNull()
                .remoteId.value shouldBe stay.remote
            remoteIds.guests(booked.id, FnrhEnvironment.HOMOLOGATION).associate {
                it.guestId to (it.personId.value to it.guestRemoteId?.value)
            } shouldBe
                mapOf(
                    stay.holder to (stay.holderFnrh.person to stay.holderFnrh.guest),
                    stay.minor to (stay.minorFnrh.person to stay.minorFnrh.guest),
                )
        } finally {
            jdbc.resetFnrhSettings()
            FnrhTestServer.reset()
            frontdesk.putPolicy(originalPolicy)
            clock.advance(REWIND)
        }
    }

    // --- Apoio: a estadia ---------------------------------------------------------------------------------------

    /** Os ids que o Serpro (WireMock) devolve para um hóspede: a pessoa e o hóspede na reserva. */
    private data class FnrhGuestIds(
        val cpf: String,
        val person: String,
        val guest: String,
    )

    /**
     * Os cadastros de uma estadia: o tipo de UH com duas UHs inspecionadas, o plano com preços, o titular adulto e o
     * menor, ambos brasileiros com CPF, e os ids remotos que os stubs do Serpro devolvem para eles.
     */
    private class Stay(
        val type: UUID,
        val rooms: List<UUID>,
        val plan: UUID,
        val holder: UUID,
        val minor: UUID,
        val remote: String,
        val holderFnrh: FnrhGuestIds,
        val minorFnrh: FnrhGuestIds,
    ) {
        val room: UUID get() = rooms.first()
    }

    private data class Booked(
        val id: UUID,
        val folio: UUID,
        val code: String,
    )

    /**
     * O Gerente cadastra o tipo de UH, duas UHs (deixadas inspecionadas), as políticas, o plano e os preços; a
     * Recepcionista cadastra o titular e o menor.
     */
    private fun newStay(): Stay {
        val today = hotelApi.currentBusinessDate()
        val type = api(POST, "/api/v1/room-types", manager, rooms.roomTypeBody()).expect(201).id()
        val created = (1..2).map { api(POST, "/api/v1/rooms", manager, rooms.roomBody(type)).expect(201).id() }
        support.inventory.awaitRow(type, today) { it.total == created.size }
        created.forEach { room ->
            housekeeping(room, "MARK_CLEAN")
            housekeeping(room, "MARK_INSPECTED")
        }
        val cancellation =
            api(POST, "/api/v1/cancellation-policies", manager, rates.cancellationBody()).expect(201).id()
        val guarantee =
            api(
                POST,
                "/api/v1/guarantee-policies",
                manager,
                rates.guaranteeBody(type = "NONE", depositPercent = null, dueDaysBefore = null),
            ).expect(201).id()
        val plan =
            api(POST, "/api/v1/rate-plans", manager, rates.planBody(listOf(type), cancellation, guarantee))
                .expect(201)
                .id()
        prices
            .update(
                plan,
                prices.selection(listOf(type), today, today.plusDays(PRICED_DAYS)),
                prices.prices("300.00", "400.00", "120.00"),
                auth = manager.auth,
            ).ok()
        val holderCpf = newCpf()
        val minorCpf = newCpf()
        val holder = guest(holderCpf, LocalDate.of(1988, 3, 14))
        val minor = guest(minorCpf, today.minusYears(MINOR_AGE))
        val remote = "r-${UuidV7.generate()}"
        return Stay(
            type,
            created,
            plan,
            holder,
            minor,
            remote,
            FnrhGuestIds(holderCpf, "p-titular-$remote", "h-titular-$remote"),
            FnrhGuestIds(minorCpf, "p-menor-$remote", "h-menor-$remote"),
        )
    }

    /** Um hóspede brasileiro com CPF, data de nascimento e os dados que a FNRH exige, cadastrado pela Recepcionista. */
    private fun guest(
        cpf: String,
        birthDate: LocalDate,
    ): UUID =
        api(
            POST,
            "/api/v1/guests",
            receptionist,
            guests.createBody(
                profile = guests.profileBody(extra = mapOf("birthDate" to birthDate, "nationality" to "BR")),
                documents = listOf(guests.document("CPF", cpf)),
                sensitiveData = mapOf("raceColor" to "BROWN"),
            ),
        ).expect(201).id()

    /** A Recepcionista reserva de hoje a depois de amanhã, com o titular e o menor na reserva, sem UH alocada. */
    private fun book(stay: Stay): Booked {
        val today = hotelApi.currentBusinessDate()
        val body =
            support.booking(
                stay.holder,
                support.reservation(
                    stay.type,
                    stay.plan,
                    today,
                    today.plusDays(2),
                    guests = listOf(stay.holder to true, stay.minor to false),
                ),
            )
        val created = support.create(body, receptionist.auth).expect(201)["reservations"][0]
        return Booked(
            created.id(),
            UUID.fromString(created["folioId"].asString()),
            created["code"].asString(),
        )
    }

    /** A foto da autorização do menor, enviada e vinculada ao perfil dele pela Recepcionista. */
    private fun minorProof(minor: UUID): UUID {
        val upload =
            mockMvc
                .multipart("/api/v1/files") {
                    file(MockMultipartFile("file", "autorizacao.png", "image/png", TestImages.png(64, 48)))
                    part(MockPart("purpose", MINOR_AUTHORIZATION_PHOTO.toByteArray(Charsets.UTF_8)))
                    with(receptionist.auth)
                }.andReturn()
                .response
        val file = upload.expect(201).id()
        api(POST, "/api/v1/guests/$minor/attachments", receptionist, mapOf("fileId" to file)).expect(201)
        return file
    }

    /** O pedido de check-in com o titular e o menor (com a comprovação [minorProof], se houver), caução e early. */
    private fun checkInBody(
        stay: Stay,
        fee: String,
        minorProof: UUID?,
    ): Map<String, Any?> {
        val authorization =
            minorProof?.let {
                mapOf(
                    "proofType" to "WRITTEN_AUTHORIZATION",
                    "attachmentId" to it,
                    "responsibleGuestId" to stay.holder,
                    "relationship" to "PAI",
                )
            }
        return mapOf(
            "guests" to
                listOf(stayGuest(stay.holder, primary = true), stayGuest(stay.minor, authorization = authorization)),
            "preAuthorization" to frontdesk.preAuthorization(),
            "earlyCheckInFee" to fee,
        )
    }

    private fun stayGuest(
        guest: UUID,
        primary: Boolean = false,
        authorization: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to primary,
            "travelReason" to "LAZER_FERIAS",
            "transportMode" to "AUTOMOVEL",
            "minorAuthorization" to authorization,
        )

    /** O check-in pela Recepcionista, cada um com a própria `Idempotency-Key`. */
    private fun checkIn(
        reservation: UUID,
        body: Map<String, Any?>,
    ): MockHttpServletResponse = api(POST, "/api/v1/reservations/$reservation/check-in", receptionist, body)

    /** Depois de uma recusa: a reserva confirmada, sem caução, sem lançamento e a UH vaga. */
    private fun nothingWritten(
        booked: Booked,
        room: UUID,
    ) {
        support.status(booked.id) shouldBe "CONFIRMED"
        frontdesk.preAuthorizations(booked.folio).shouldBeEmpty()
        support.postings(booked.folio).shouldBeEmpty()
        roomsApi.status(RoomId(room))!!.occupancy.name shouldBe "VACANT"
    }

    /** Uma mudança manual do status de governança da UH pelo Gerente. */
    private fun housekeeping(
        room: UUID,
        transition: String,
    ) {
        billing
            .call(
                POST,
                "/api/v1/rooms/$room/housekeeping-status",
                manager.auth,
                mapOf("transition" to transition),
                ifMatch = rooms.etagOf("/api/v1/rooms/$room/status"),
            ).ok()
    }

    // --- Apoio: a FNRH ------------------------------------------------------------------------------------------

    /**
     * Os stubs v2.4 do Serpro para a estadia: o registro da reserva com o id remoto dela e, para o titular e o menor,
     * a consulta por documento sem pessoa, o cadastro da pessoa e o hóspede na reserva com ids remotos distintos (cada
     * stub só casa com o CPF ou a pessoa dele) e o check-out.
     */
    private fun stubFnrhFor(stay: Stay) {
        FnrhTestServer.stubRegisterReservation_v2_4(stay.remote)
        for (ids in listOf(stay.holderFnrh, stay.minorFnrh)) {
            FnrhTestServer.stubFindPersonByDocument_v2_4("CPF", ids.cpf, REQUESTER_CPF)
            FnrhTestServer.server.stubFor(
                post(urlEqualTo("${FnrhTestServer.BASE_PATH}/pessoas"))
                    .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                    .withHeader("cpf_solicitante", equalTo(REQUESTER_CPF))
                    .withRequestBody(matchingJsonPath("$.pessoa.documento_id.numero_documento", equalTo(ids.cpf)))
                    .willReturn(okJson("""{"pessoa_id":"${ids.person}"}""")),
            )
            FnrhTestServer.server.stubFor(
                post(urlEqualTo("${FnrhTestServer.BASE_PATH}/reservas/${stay.remote}/hospedes"))
                    .withHeader("Authorization", equalTo(FnrhTestServer.basicAuthorization))
                    .withRequestBody(matchingJsonPath("$.pessoa_id", equalTo(ids.person)))
                    .willReturn(okJson("""{"hospede_id":"${ids.guest}"}""")),
            )
            FnrhTestServer.stubCheckOut_v2_4(ids.guest)
        }
    }

    /**
     * Espera o listener do `fnrh` gravar as submissões [expected] da reserva e envia só as dela (o job fica adiado
     * nos testes), fora de transação.
     */
    private fun sendOwnFnrhSubmissions(
        reservationId: UUID,
        vararg expected: FnrhOperation,
    ): FnrhSendRun {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).pollInterval(Duration.ofMillis(POLL_MILLIS)).until {
            fnrhStatuses(reservationId).map { it.first }.containsAll(expected.toList())
        }
        return sender.sendDueFor(reservationId)
    }

    /** As submissões da reserva na fila, em ordem: a operação e a situação. */
    private fun fnrhStatuses(reservationId: UUID): List<Pair<FnrhOperation, FnrhSubmissionStatus>> =
        jdbc
            .sql("SELECT operation, status FROM fnrh.submission WHERE reservation_id = :id ORDER BY sequence")
            .param("id", reservationId)
            .query { rs, _ ->
                FnrhOperation.valueOf(rs.getString("operation")) to
                    FnrhSubmissionStatus.valueOf(rs.getString("status"))
            }.list()

    /**
     * As chamadas ao Serpro (WireMock, um por JVM) desta estadia, na ordem em que chegaram: as que levam o id remoto
     * da reserva ou o CPF de um dos hóspedes no caminho, o cadastro de pessoa com um desses CPFs e o registro com o
     * código da reserva.
     */
    private fun fnrhCallsOf(
        stay: Stay,
        code: String,
    ): List<LoggedRequest> {
        val cpfs = listOf(stay.holderFnrh.cpf, stay.minorFnrh.cpf)
        return FnrhTestServer.requests().filter { request ->
            val path = request.url.removePrefix(FnrhTestServer.BASE_PATH)
            val body = request.bodyAsString
            stay.remote in path ||
                cpfs.any { it in path } ||
                (path == "/pessoas" && cpfs.any { it in body }) ||
                (path == "/reservas" && "\"numero_reserva\":\"$code\"" in body)
        }
    }

    // --- Apoio: operadores e chamadas ---------------------------------------------------------------------------

    /** Usuário novo com a role do modelo da SPEC §3.1 que o sistema cria no boot. */
    private fun userWithTemplate(template: String): TestUser {
        val role =
            jdbc
                .sql("SELECT id FROM iam.role WHERE template_key = :template")
                .param("template", template)
                .query(UUID::class.java)
                .single()
        return iam.createUser().also { iam.assignRole(it, RoleId(role)) }
    }

    private fun api(
        method: HttpMethod,
        path: String,
        operator: Operator,
        body: Any? = null,
    ): MockHttpServletResponse = billing.call(method, path, operator.auth, body)

    private fun MockHttpServletResponse.expect(status: Int): JsonNode = with(billing) { expect(status) }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(billing) { problem(status, code) }

    private fun MockHttpServletResponse.ok(): JsonNode = with(iam) { ok() }

    private fun JsonNode.id(): UUID = UUID.fromString(this["id"].asString())

    private companion object {
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val PRICED_DAYS = 30L
        const val MINOR_AGE = 10L
        const val EARLY_FEE = "85.00"
        const val MINOR_AUTHORIZATION_PHOTO = "guests.minor-authorization-photo"
        const val STAY_LISTENER = "StayTransitionsProbeListener"

        /** O CPF solicitante padrão que o `enableFnrh` configura, só dígitos. */
        const val REQUESTER_CPF = "52998224725"
        const val TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 50L

        /**
         * O relógio volta antes do cenário, como em `FnrhReservationSendTestBase`: as chamadas gravadas em
         * `fnrh.integration_call` (append-only, banco compartilhado) ficam no passado para os outros testes.
         */
        val REWIND: Duration = Duration.ofHours(3)
    }
}

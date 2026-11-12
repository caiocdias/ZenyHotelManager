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
import com.zeny.hotel.guests.GuestsTestSupport.Companion.formattedCpf
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.rates.RatePricesTestSupport
import com.zeny.hotel.rates.RatesTestSupport
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.reservations.ReservationsTestSupport.Operator
import com.zeny.hotel.reservations.RoomMoved
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.FnrhTestServer
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.enableFnrh
import com.zeny.hotel.testsupport.pdfText
import com.zeny.hotel.testsupport.resetFnrhSettings
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Cenário ponta a ponta do marco M5 (gate, E12), pela API HTTP, com os módulos reais (só a FNRH no WireMock) e os
 * modelos de role da SPEC §3.1 que o sistema entrega (Gerente e Recepcionista); o que é só do Administrador (ligar a
 * FNRH, a política de early/late, o agente e a impressora) fica com o administrador inicial. O Gerente cadastra o tipo
 * de UH, as UHs, o plano, os preços e o ponto de caixa; a Recepcionista cria a reserva do titular com um menor, cota o
 * early check-in, tem o check-in recusado sem UH alocada, com a UH suja e com o menor sem comprovação (sem nada gravado
 * em cada recusa) e faz o check-in com a autorização do menor, a caução e o early cotado; as submissões da reserva
 * chegam ao Serpro em ordem, com o titular e o menor como pessoas distintas. Depois, a ficha sai pelo agente com o
 * documento mascarado, a estadia troca de UH, é estendida e reduzida, recebe um lançamento e o pagamento do saldo, o
 * extrato mostra o saldo zero e o check-out com o late cotado libera a caução e encerra tudo; nada de nome ou CPF do
 * hóspede em trilha, outbox, notificação ou view da FNRH. Cada teste cria os próprios cadastros, envia só as submissões
 * da própria reserva e devolve a configuração global (FNRH, política, relógio) no fim.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    InventoryTestConfiguration::class,
    MutableClockConfiguration::class,
    FnrhShortReadTimeoutConfiguration::class,
)
@Suppress("LongParameterList", "LargeClass") // os beans que o cenário usa; o cenário do marco inteiro
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
    fun `a guest arrives, is registered with FNRH, has the registration card printed and leaves with a zero balance`() {
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
            withClue("o job está adiado: nada desta reserva chegou ao Serpro antes do envio") {
                fnrhCallsOf(stay, booked.code).shouldBeEmpty()
            }
            support.historyKinds(booked.id) shouldContainExactly listOf("CHECK_IN")
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

            // --- A ficha impressa pelo agente numa A4_PDF, com o documento mascarado -----------------------------
            printedRegistrationCard(stay, booked)

            // --- Troca para a UH 2 com a tarifa mantida ----------------------------------------------------------
            movedToSecondRoom(stay, booked)

            // --- Extensão da saída em uma noite e redução de volta, com a tarifa do tipo já mais cara ------------
            extendedAndShortened(stay, booked)

            // --- Lançamento manual e o saldo (o early e o lançamento) pago no turno de caixa da Recepcionista ----
            openShift(receptionist)
            val posting =
                api(
                    POST,
                    "/api/v1/folios/${booked.folio}/postings",
                    receptionist,
                    mapOf("transactionCodeId" to billing.codeId("LAUNDRY"), "quantity" to 1, "unitPrice" to LAUNDRY),
                ).expect(201)
            val due = billing.balance(booked.folio)
            due shouldBe (BigDecimal(fee) + BigDecimal(LAUNDRY)).toPlainString()
            pay(booked.folio, due)
            billing.balance(booked.folio) shouldBe "0.00"

            // --- O extrato em PDF: o lançamento, o pagamento e o saldo zero --------------------------------------
            val statement = api(GET, "/api/v1/folios/${booked.folio}/statement/pdf", receptionist)
            statement.status shouldBe 200
            statement.contentType shouldBe "application/pdf"
            pdfText(statement.contentAsByteArray).let { text ->
                text shouldContain posting["description"].asString()
                text shouldContain money(LAUNDRY)
                text shouldContain money(due)
                text shouldContain Regex("""Saldo\s+R\$ 0,00(\s|$)""")
            }

            // --- Check-out com o late cotado: o saldo das cobranças primeiro, pago, e o check-out repetido -------
            checkedOutWithLate(stay, booked)

            // --- A FNRH: as alterações e o check-out enviados, o check-out como a última chamada da reserva -------
            fnrhAfterCheckOut(stay, booked, calls.size)

            // --- Privacidade: nem o nome do titular nem o CPF em trilha, outbox, notificação ou view da FNRH -----
            nothingLeaked(stay, booked)
        } finally {
            jdbc.resetFnrhSettings()
            FnrhTestServer.reset()
            frontdesk.putPolicy(originalPolicy)
            clock.advance(REWIND)
        }
    }

    @Test
    fun `a stale early check-in fee is refused with nothing written and no fee means no early charge`() {
        FnrhTestServer.reset()
        jdbc.resetFnrhSettings()
        val originalPolicy = frontdesk.policy()
        try {
            val stay = newStay()
            val checkInTime = LocalTime.parse(originalPolicy["checkInTime"].toString())
            val limit = "${checkInTime.minusHours(3)}"
            frontdesk.putPolicy(
                originalPolicy + mapOf("earlyCheckIn" to mapOf("limitTime" to limit, "fee" to EARLY_FEE)),
            )
            val booked = book(stay)
            api(POST, "/api/v1/reservations/${booked.id}/assign-room", receptionist, mapOf("roomId" to stay.room)).ok()
            val quoted =
                api(GET, "/api/v1/reservations/${booked.id}/early-late-quote", receptionist)
                    .ok()["earlyCheckIn"]["fee"]
                    .asString()
            quoted shouldBe EARLY_FEE
            val proof = minorProof(stay.minor)

            // O valor mudou depois da cotação: 409 com o valor vigente e nada gravado.
            frontdesk.putPolicy(originalPolicy + mapOf("earlyCheckIn" to mapOf("limitTime" to limit, "fee" to "95.00")))
            checkIn(booked.id, checkInBody(stay, quoted, proof))
                .problem(409, "EARLY_LATE_FEE_CHANGED")["currentFee"]
                .asString() shouldBe "95.00"
            nothingWritten(booked, stay.room)

            // Sem o valor no pedido o early não é cobrado, mesmo oferecido na política.
            val body = checkInBody(stay, quoted, proof) - "earlyCheckInFee"
            val response = checkIn(booked.id, body).expect(200)
            response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
            response["earlyCheckIn"].isNull shouldBe true
            support.postings(booked.folio).shouldBeEmpty()
            frontdesk.preAuthorizations(booked.folio).map { it["status"].asString() } shouldContainExactly
                listOf("ACTIVE")
        } finally {
            jdbc.resetFnrhSettings()
            FnrhTestServer.reset()
            frontdesk.putPolicy(originalPolicy)
        }
    }

    // --- Apoio: da ficha ao check-out ---------------------------------------------------------------------------

    /**
     * O administrador cadastra um agente (o token fica só em memória) e uma impressora `A4_PDF` dele; a Recepcionista
     * imprime a ficha, o agente a recebe pelo long-poll, baixa o PDF e confirma a impressão. O PDF tem o titular, a UH
     * e as datas da reserva lida aqui e o CPF de cada hóspede mascarado, nunca inteiro.
     */
    private fun printedRegistrationCard(
        stay: Stay,
        booked: Booked,
    ) {
        val suffix = guests.suffix()
        val agent = iam.call(POST, "/api/v1/print-agents", iam.asAdmin(), mapOf("name" to "Agente $suffix")).expect(201)
        val token = agent["token"].asString()
        val printer =
            iam
                .call(
                    POST,
                    "/api/v1/printers",
                    iam.asAdmin(),
                    mapOf(
                        "name" to "A4 $suffix",
                        "kind" to "A4_PDF",
                        "address" to "a4-$suffix",
                        "agentId" to agent.id(),
                    ),
                ).expect(201)
                .id()

        val job =
            api(
                POST,
                "/api/v1/reservations/${booked.id}/registration-card/print",
                receptionist,
                mapOf("printerId" to printer),
            ).expect(202)["printJobId"]
                .asString()
        agentLongPoll(token).single().let {
            it["id"].asString() shouldBe job
            it["printerId"].asString() shouldBe printer.toString()
            it["mediaType"].asString() shouldBe "application/pdf"
        }
        val content = agentRequest(GET, "/api/v1/print-agents/me/jobs/$job/content", token)
        content.status shouldBe 200
        content.contentType shouldBe "application/pdf"
        agentRequest(POST, "/api/v1/print-agents/me/jobs/$job/ack", token, mapOf("result" to "PRINTED")).status shouldBe
            204
        api(GET, "/api/v1/print-jobs?printerId=$printer", receptionist).ok()["items"].values().map {
            it["id"].asString() to it["status"].asString()
        } shouldContainExactly listOf(job to "PRINTED")

        val reservation = support.reservation(booked.id)["reservation"]
        val room = UUID.fromString(reservation["roomAssignment"]["roomId"].asString())
        room shouldBe stay.room
        val text = pdfText(content.contentAsByteArray)
        text shouldContain stay.holderName
        text shouldContain "UH ${roomsApi.room(RoomId(room))!!.number}"
        text shouldContain "Chegada ${date(reservation["arrival"])}"
        text shouldContain "Saída ${date(reservation["departure"])}"
        for (cpf in listOf(stay.holderFnrh.cpf, stay.minorFnrh.cpf)) {
            text shouldContain "***${cpf.takeLast(MASK_VISIBLE)}"
            text shouldNotContain cpf
            text shouldNotContain formattedCpf(cpf)
        }
    }

    /** A Recepcionista troca a reserva para a UH 2 mantendo a tarifa: UH 1 Vaga e Suja, UH 2 Ocupada, `RoomMoved`. */
    private fun movedToSecondRoom(
        stay: Stay,
        booked: Booked,
    ) {
        val (origin, destination) = stay.rooms
        val nights = nightlyAmounts(booked.id)
        val response =
            billing
                .call(
                    POST,
                    "/api/v1/reservations/${booked.id}/room-moves",
                    receptionist.auth,
                    mapOf("roomId" to destination, "reason" to "GUEST_REQUEST", "tariff" to "KEEP"),
                    ifMatch = support.etag(booked.id),
                ).expect(200)
        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        response["reservation"]["roomId"].asString() shouldBe destination.toString()
        response["tariff"].asString() shouldBe "KEEP"
        response["difference"].asString() shouldBe "0.00"
        roomsApi.status(RoomId(origin))!!.let {
            it.occupancy.name shouldBe "VACANT"
            it.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        }
        roomsApi.status(RoomId(destination))!!.let {
            it.occupancy.name shouldBe "OCCUPIED"
            it.guestCount shouldBe 2
        }
        assignments(booked.id) shouldContainExactly
            listOf(Assignment(origin, "ROOM_MOVED", true), Assignment(destination, null, false))
        nightlyAmounts(booked.id) shouldBe nights
        support.lifecyclePublications(RoomMoved::class.java, booked.id, STAY_LISTENER) shouldHaveSize 1
    }

    /**
     * O Gerente sobe a tarifa do tipo; a Recepcionista estende a saída em uma noite e a reduz de volta. A UH 2
     * acompanha e as noites que já existiam não mudam de preço (RN-14).
     */
    private fun extendedAndShortened(
        stay: Stay,
        booked: Booked,
    ) {
        val destination = stay.rooms[1]
        val departure = LocalDate.parse(support.reservation(booked.id)["reservation"]["departure"].asString())
        val nights = nightlyAmounts(booked.id)
        val today = hotelApi.currentBusinessDate()
        prices
            .update(
                stay.plan,
                prices.selection(listOf(stay.type), today, today.plusDays(PRICED_DAYS)),
                prices.prices("450.00", RAISED_TWO_ADULTS, "150.00"),
                auth = manager.auth,
            ).ok()

        changeDeparture(booked.id, departure.plusDays(1)).let {
            it["status"].asString() shouldBe "IN_HOUSE"
            it["departure"].asString() shouldBe departure.plusDays(1).toString()
            it["roomAssignment"]["roomId"].asString() shouldBe destination.toString()
            it["roomAssignment"]["endsOn"].asString() shouldBe departure.plusDays(1).toString()
        }
        nightlyAmounts(booked.id).let { extended ->
            extended.map { it.first } shouldContainExactly nights.map { it.first } + departure
            extended.take(nights.size) shouldContainExactly nights
        }
        roomsApi.status(RoomId(destination))!!.occupancy.name shouldBe "OCCUPIED"

        changeDeparture(booked.id, departure).let {
            it["departure"].asString() shouldBe departure.toString()
            it["roomAssignment"]["roomId"].asString() shouldBe destination.toString()
            it["roomAssignment"]["endsOn"].asString() shouldBe departure.toString()
        }
        nightlyAmounts(booked.id) shouldContainExactly nights
        roomsApi.status(RoomId(destination))!!.let {
            it.occupancy.name shouldBe "OCCUPIED"
            it.guestCount shouldBe 2
        }
    }

    /** O `PUT` da reserva pela Recepcionista, com o `If-Match`, mudando só a saída; devolve a reserva. */
    private fun changeDeparture(
        reservation: UUID,
        departure: LocalDate,
    ): JsonNode {
        val current = support.reservation(reservation)["reservation"]
        return support
            .change(reservation, support.changeBody(current, departure = departure), auth = receptionist.auth)
            .expect(200)["reservation"]
    }

    /**
     * O check-out pela Recepcionista com o late cotado: a política (do administrador) oferece o late; o primeiro pedido
     * dá `409 FOLIO_BALANCE_NOT_ZERO` com a noite da chegada e o late somados, sem nada mudado; pago o total, o pedido
     * repetido com outra chave libera a caução, encerra a conta, a reserva e a alocação e deixa a UH 2 Vaga e Suja.
     */
    @Suppress("LongMethod") // a recusa, o pagamento e tudo o que o check-out muda
    private fun checkedOutWithLate(
        stay: Stay,
        booked: Booked,
    ) {
        val (origin, destination) = stay.rooms
        val hold = frontdesk.preAuthorizations(booked.folio).single()["id"].asString()
        frontdesk.putPolicy(
            frontdesk.policy() + mapOf("lateCheckOut" to mapOf("limitTime" to LATE_LIMIT, "fee" to LATE_FEE)),
        )
        val quote = api(GET, "/api/v1/reservations/${booked.id}/early-late-quote", receptionist).ok()["lateCheckOut"]
        quote["offered"].asBoolean() shouldBe true
        val late = quote["fee"].asString()
        late shouldBe LATE_FEE
        val body = mapOf("lateCheckOutFee" to late)
        val arrivalNight = nightlyAmounts(booked.id).first().second

        val total =
            checkOut(booked.id, body).problem(409, "FOLIO_BALANCE_NOT_ZERO").let {
                it["folios"].values().map { folio -> folio["folioId"].asString() } shouldContainExactly
                    listOf(booked.folio.toString())
                it["total"].asString()
            }
        total shouldBe (BigDecimal(arrivalNight) + BigDecimal(late)).toPlainString()
        support.status(booked.id) shouldBe "IN_HOUSE"
        frontdesk.preAuthorizations(booked.folio).single()["status"].asString() shouldBe "ACTIVE"
        billing.folio(booked.folio)["status"].asString() shouldBe "OPEN"
        roomsApi.status(RoomId(destination))!!.occupancy.name shouldBe "OCCUPIED"

        pay(booked.folio, total)
        val response = checkOut(booked.id, body).expect(200)

        response["reservation"]["status"].asString() shouldBe "CHECKED_OUT"
        response["lateCheckOut"]["amount"].asString() shouldBe late
        response["room"].let {
            it["roomId"].asString() shouldBe destination.toString()
            it["occupancy"].asString() shouldBe "VACANT"
            it["housekeepingStatus"].asString() shouldBe "DIRTY"
        }
        response["releasedPreAuthorizations"].values().map { it["id"].asString() to it["status"].asString() } shouldBe
            listOf(hold to "RELEASED")
        response["closedFolios"].values().map { it["folioId"].asString() to it["status"].asString() } shouldBe
            listOf(booked.folio.toString() to "CLOSED")
        support.status(booked.id) shouldBe "CHECKED_OUT"
        frontdesk.preAuthorizations(booked.folio).single()["status"].asString() shouldBe "RELEASED"
        billing.folio(booked.folio)["status"].asString() shouldBe "CLOSED"
        billing.balance(booked.folio) shouldBe "0.00"
        roomsApi.status(RoomId(destination))!!.let {
            it.occupancy.name shouldBe "VACANT"
            it.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        }
        assignments(booked.id) shouldContainExactly
            listOf(Assignment(origin, "ROOM_MOVED", true), Assignment(destination, "CHECKED_OUT", true))
        support.lifecyclePublications(GuestCheckedOut::class.java, booked.id, STAY_LISTENER) shouldHaveSize 1
    }

    /** O check-out pela Recepcionista, cada um com a própria `Idempotency-Key`. */
    private fun checkOut(
        reservation: UUID,
        body: Map<String, Any?>,
    ): MockHttpServletResponse = api(POST, "/api/v1/reservations/$reservation/check-out", receptionist, body)

    /**
     * Espera as duas alterações (a extensão e a redução) e o check-out na fila e envia só as da reserva: tudo `SENT`, e
     * as chamadas desta reserva depois das [before] do check-in são as duas alterações e, por último, o check-out de
     * cada hóspede.
     */
    private fun fnrhAfterCheckOut(
        stay: Stay,
        booked: Booked,
        before: Int,
    ) {
        val operations =
            listOf(
                FnrhOperation.REGISTER_RESERVATION,
                FnrhOperation.CHECK_IN,
                FnrhOperation.UPDATE_RESERVATION,
                FnrhOperation.UPDATE_RESERVATION,
                FnrhOperation.CHECK_OUT,
            )
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).pollInterval(Duration.ofMillis(POLL_MILLIS)).until {
            fnrhStatuses(booked.id).size == operations.size
        }
        sendOwnFnrhSubmissions(booked.id, FnrhOperation.UPDATE_RESERVATION, FnrhOperation.CHECK_OUT)
        fnrhStatuses(booked.id) shouldContainExactly operations.map { it to FnrhSubmissionStatus.SENT }
        val calls =
            fnrhCallsOf(stay, booked.code).drop(before).map {
                "${it.method.value()} ${it.url.removePrefix(FnrhTestServer.BASE_PATH)}"
            }
        calls shouldHaveSize 4
        calls.take(2) shouldContainExactly listOf("PUT /reservas/${stay.remote}", "PUT /reservas/${stay.remote}")
        // O check-out de cada hóspede fecha as chamadas da reserva (a ordem entre os dois não é contrato).
        calls.takeLast(2) shouldContainExactlyInAnyOrder
            listOf(
                "PATCH /hospedes/${stay.holderFnrh.guest}/checkout",
                "PATCH /hospedes/${stay.minorFnrh.guest}/checkout",
            )
    }

    /**
     * Nem o nome do titular nem o CPF (só dígitos ou formatado) de um dos hóspedes nas linhas de auditoria da reserva e
     * da conta, nas publicações do outbox, nas notificações e nas linhas de `fnrh.report_submissions` da reserva.
     */
    private fun nothingLeaked(
        stay: Stay,
        booked: Booked,
    ) {
        val cpfs = listOf(stay.holderFnrh.cpf, stay.minorFnrh.cpf)
        val leaks = listOf(stay.holderName, stay.holderName.substringAfterLast(' ')) + cpfs + cpfs.map(::formattedCpf)
        val reservation = "%${booked.id}%"
        val sources =
            mapOf(
                "trilha da reserva" to
                    rows(
                        "SELECT a::text FROM audit.audit_entry a WHERE a.entity_id IN (:ids) " +
                            "OR a.before_state::text LIKE :reservation OR a.after_state::text LIKE :reservation",
                        "ids" to listOf(booked.id.toString(), booked.folio.toString()),
                        "reservation" to reservation,
                    ),
                "outbox da reserva" to
                    rows(
                        "SELECT serialized_event FROM shared.event_publication " +
                            "WHERE serialized_event LIKE :reservation",
                        "reservation" to reservation,
                    ),
                "fnrh.report_submissions" to
                    rows(
                        "SELECT r::text FROM fnrh.report_submissions r WHERE r.reservation_id = :id",
                        "id" to booked.id,
                    ),
            )
        for ((source, found) in sources) {
            withClue(source) {
                found.isNotEmpty() shouldBe true
                for (leak in leaks) found.forEach { it shouldNotContain leak }
            }
        }
        for (leak in leaks) {
            withClue("outbox inteiro e notificações") {
                rows(
                    "SELECT serialized_event FROM shared.event_publication WHERE serialized_event LIKE :v " +
                        "UNION ALL SELECT n::text FROM notifications.notification n WHERE n.title LIKE :v " +
                        "OR n.body LIKE :v",
                    "v" to "%$leak%",
                ).shouldBeEmpty()
            }
        }
    }

    private fun rows(
        sql: String,
        vararg params: Pair<String, Any>,
    ): List<String> =
        params
            .fold(jdbc.sql(sql)) { spec, (name, value) -> spec.param(name, value) }
            .query(String::class.java)
            .list()
            .filterNotNull()

    /** Abre, como [operator], um turno de caixa num ponto de caixa novo cadastrado pelo Gerente. */
    private fun openShift(operator: Operator) {
        val cashPoint =
            api(POST, "/api/v1/cash-points", manager, mapOf("name" to "Caixa ${guests.suffix()}")).expect(201).id()
        api(POST, "/api/v1/cashier-shifts", operator, mapOf("cashPointId" to cashPoint, "openingFloat" to "0.00"))
            .expect(201)
    }

    /** Recebe [amount] em dinheiro na conta, no turno da Recepcionista. */
    private fun pay(
        folio: UUID,
        amount: String,
    ) {
        api(POST, "/api/v1/folios/$folio/payments", receptionist, mapOf("method" to "CASH", "amount" to amount))
            .expect(201)
    }

    private data class Assignment(
        val roomId: UUID,
        val releaseReason: String?,
        val released: Boolean,
    )

    /** As alocações da reserva, da mais antiga à mais recente: a UH, o motivo da soltura e se foi solta. */
    private fun assignments(reservation: UUID): List<Assignment> =
        jdbc
            .sql(
                "SELECT room_id, release_reason, released_at IS NOT NULL AS released " +
                    "FROM reservations.room_assignment WHERE reservation_id = :id ORDER BY assigned_at, id",
            ).param("id", reservation)
            .query { rs, _ ->
                Assignment(
                    rs.getObject("room_id", UUID::class.java),
                    rs.getString("release_reason"),
                    rs.getBoolean("released"),
                )
            }.list()

    /** As noites da reserva: a data e o valor. */
    private fun nightlyAmounts(reservation: UUID): List<Pair<LocalDate, String>> =
        support.reservation(reservation)["reservation"]["nightlyRates"].values().map {
            LocalDate.parse(it["date"].asString()) to it["amount"].asString()
        }

    // --- Apoio: o agente de impressão (protocolo da E10) ---------------------------------------------------------

    /** Os jobs que o agente do [token] recebe por um long-poll (que volta na hora quando há job pendente). */
    private fun agentLongPoll(token: String): List<JsonNode> {
        val response = agentRequest(GET, "/api/v1/print-agents/me/jobs?wait=$LONG_POLL_SECONDS", token)
        response.status shouldBe 200
        return jsonMapper.readTree(response.contentAsString).values().toList()
    }

    /** Uma chamada do agente com o token dele (o long-poll e o conteúdo podem responder de forma assíncrona). */
    private fun agentRequest(
        method: HttpMethod,
        path: String,
        token: String,
        body: Any? = null,
    ): MockHttpServletResponse {
        val result =
            mockMvc
                .request(method, path) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    if (body != null) {
                        contentType = MediaType.APPLICATION_JSON
                        content = jsonMapper.writeValueAsString(body)
                    }
                }.andReturn()
        if (!result.request.isAsyncStarted) return result.response
        result.asyncResult
        return mockMvc.perform(asyncDispatch(result)).andReturn().response
    }

    /** A data como os documentos a mostram (`dd/MM/yyyy`). */
    private fun date(value: JsonNode): String = LocalDate.parse(value.asString()).format(DOCUMENT_DATE)

    /** O valor como os documentos o mostram (`R$ 1.234,56`). */
    private fun money(value: String): String =
        "R$ " + DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.of("pt", "BR"))).format(BigDecimal(value))

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
        val holderName: String,
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
        val holderName = guests.uniqueName("Titular")
        val holder = guest(holderCpf, LocalDate.of(1988, 3, 14), holderName)
        val minor = guest(minorCpf, today.minusYears(MINOR_AGE), guests.uniqueName("Menor"))
        val remote = "r-${UuidV7.generate()}"
        return Stay(
            type,
            created,
            plan,
            holder,
            holderName,
            minor,
            remote,
            FnrhGuestIds(holderCpf, "p-titular-$remote", "h-titular-$remote"),
            FnrhGuestIds(minorCpf, "p-menor-$remote", "h-menor-$remote"),
        )
    }

    /**
     * Um hóspede brasileiro com o nome [fullName], CPF, data de nascimento e os dados que a FNRH exige, cadastrado pela
     * Recepcionista.
     */
    private fun guest(
        cpf: String,
        birthDate: LocalDate,
        fullName: String,
    ): UUID =
        api(
            POST,
            "/api/v1/guests",
            receptionist,
            guests.createBody(
                profile =
                    guests.profileBody(
                        fullName = fullName,
                        extra = mapOf("birthDate" to birthDate, "nationality" to "BR"),
                    ),
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
        roomsApi.status(RoomId(room))!!.let {
            it.occupancy.name shouldBe "VACANT"
            (it.guestCount ?: 0) shouldBe 0
        }
        withClue("a recusa não deixa transição, carimbo de chegada, evento nem submissão de check-in") {
            support.historyKinds(booked.id) shouldNotContain "CHECK_IN"
            jdbc
                .sql(
                    "SELECT checked_in_at IS NULL AND checked_in_by IS NULL " +
                        "FROM reservations.reservation WHERE id = :id",
                ).param("id", booked.id)
                .query(Boolean::class.java)
                .single() shouldBe true
            support.lifecyclePublications(GuestCheckedIn::class.java, booked.id, STAY_LISTENER).shouldBeEmpty()
            fnrhStatuses(booked.id).map { it.first } shouldNotContain FnrhOperation.CHECK_IN
        }
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
     * Os stubs v2.4 do Serpro para a estadia: o registro e a alteração da reserva com o id remoto dela e, para o
     * titular e o menor, a consulta por documento sem pessoa, o cadastro da pessoa e o hóspede na reserva com ids
     * remotos distintos (cada stub só casa com o CPF ou a pessoa dele) e o check-out.
     */
    private fun stubFnrhFor(stay: Stay) {
        FnrhTestServer.stubRegisterReservation_v2_4(stay.remote)
        FnrhTestServer.stubUpdateReservation_v2_4(stay.remote)
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
        const val LATE_FEE = "70.00"
        const val LATE_LIMIT = "18:00"
        const val LAUNDRY = "30.00"
        const val RAISED_TWO_ADULTS = "550.00"
        const val MASK_VISIBLE = 4
        const val LONG_POLL_SECONDS = 5
        val DOCUMENT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
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

package com.zeny.hotel.frontdesk

import com.zeny.hotel.files.TestImages
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderRequest
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.CheckInRendezvousProbe
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.RoomStatusFailureProbe
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.modulith.test.EnableScenarios
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * O check-in orquestrado da recepção (M5 E02; ARCHITECTURE §6.1, RF-REC-01, RF-REC-08, RN-05, RN-11, RN-24, H11 a H14):
 * a transação única (sucesso com a caução, a reserva `IN_HOUSE`, a UH Ocupada e `GuestCheckedIn`; a falha forçada
 * depois da caução, que não deixa nada), as recusas de cada passo (UH não pronta ou não alocada, hóspede sem os dados
 * mínimos, menor sem comprovação ou com o anexo errado), a leitura dos documentos que nunca decifra, o alerta de
 * restrição, a idempotência, o desfazer sensível com a aprovação de superior e a cotação de early/late pela política do
 * hotel. O mesmo contexto dos testes das reservas (com o listener de falha forçada, inerte para as UHs não armadas).
 */
@IntegrationTest
@EnableScenarios
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LargeClass") // os cenários do check-in de ponta a ponta
class FrontdeskCheckInApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val inventory = support.inventory
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Sucesso --------------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o check-in inteiro: resposta, reserva, UH, caução, saldo, trilha, evento e idempotência
    fun `a ready room checks in with the guests, the minor proof and a deposit hold, in one transaction`(
        output: CapturedOutput,
    ) {
        val stay = stay(adults = 2)
        val adultCpf = newCpf()
        val adult = guest(cpf = adultCpf, sensitive = true)
        val minor = guest(birthDate = today.minusYears(9))
        val proof = minorPhoto(minor)
        val booking = stay.book(adult)
        stay.ready()
        val operator = ReservationsTestSupport.Operator(iam.createUserWith(checkInGrant()))
        val folioBefore = folio(booking.folio)
        val folioEtagBefore = folioEtag(booking.folio)
        val key = UuidV7.generate().toString()
        val body =
            checkInBody(
                listOf(primary(adult), written(minor, responsible = adult, attachment = proof)),
                preAuthorization = preAuthorization(),
            )

        val response = checkIn(booking.id, body, operator.auth, key).expect(200)

        response["reservation"]["status"].asString() shouldBe "IN_HOUSE"
        response["reservation"]["roomId"].asString() shouldBe stay.room.toString()
        response["reservation"]["checkIn"]["by"].asString() shouldBe
            operator.user.id.value
                .toString()
        val minorGuest = response["reservation"]["guests"].values().single { it["guestId"].asString() == "$minor" }
        minorGuest["minorAuthorization"]["proofType"].asString() shouldBe "WRITTEN_AUTHORIZATION"
        minorGuest["minorAuthorization"]["responsibleGuestId"].asString() shouldBe adult.toString()
        minorGuest["minorAuthorization"]["relationship"].asString() shouldBe "PAI"
        response["room"]["occupancy"].asString() shouldBe "OCCUPIED"
        response["room"]["guestCount"].asInt() shouldBe 2
        response["preAuthorization"]["status"].asString() shouldBe "ACTIVE"
        response["preAuthorization"]["amount"].asString() shouldBe "500.00"
        response["restrictionAlerts"].values().toList().shouldBeEmpty()

        // A reserva hospedada, a UH ocupada e a caução na conta, que não mudou o saldo nem a versão.
        support.status(booking.id) shouldBe "IN_HOUSE"
        roomsApi.status(RoomId(stay.room))!!.guestCount shouldBe 2
        preAuthorizations(booking.folio) shouldHaveSize 1
        folio(booking.folio)["balance"].asString() shouldBe folioBefore["balance"].asString()
        folioEtag(booking.folio) shouldBe folioEtagBefore
        // A trilha: a ação da recepção e as dos módulos de baixo, sem número de documento nem dado sensível.
        val trail = support.auditEntries(booking.id)
        trail.map { it["action"] }.filter { it in CHECK_IN_ACTIONS } shouldContainExactly CHECK_IN_ACTIONS
        trail.single { it["action"] == "frontdesk.stay.check-in" }["userId"] shouldBe operator.user.id.value
        val everything = trail.joinToString { "${it["before"]} ${it["after"]}" } + response.toString()
        everything shouldNotContain adultCpf
        everything shouldNotContain "BLACK"
        everything shouldNotContain DISABILITY
        // Nem no log (B6): o CPF, a raça/cor e a deficiência (RN-19).
        output.out shouldNotContain adultCpf
        output.out shouldNotContain DISABILITY
        output.out shouldNotContain "raceColor"
        // `GuestCheckedIn` pelo outbox, uma vez.
        support.lifecyclePublications(GuestCheckedIn::class.java, booking.id, LISTENER) shouldHaveSize 1

        // A repetição com a mesma chave devolve a guardada, sem nova caução.
        val replayed = checkInRaw(booking.id, body, operator.auth, key)
        replayed.getHeader("Idempotent-Replayed") shouldBe "true"
        with(iam) { replayed.ok() }["preAuthorization"]["id"] shouldBe response["preAuthorization"]["id"]
        preAuthorizations(booking.folio) shouldHaveSize 1
    }

    @Test
    fun `a forced failure after the deposit hold leaves no hold, no transition, no occupancy and no trail`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult)
        stay.ready()
        RoomStatusFailureProbe.armed += stay.room
        try {
            checkIn(booking.id, checkInBody(listOf(primary(adult)), preAuthorization = preAuthorization()))
                .problem(409, "TEST_FORCED_FAILURE")
        } finally {
            RoomStatusFailureProbe.armed -= stay.room
        }

        support.status(booking.id) shouldBe "CONFIRMED"
        support.historyKinds(booking.id).shouldBeEmpty()
        preAuthorizations(booking.folio).shouldBeEmpty()
        roomsApi.status(RoomId(stay.room))!!.occupancy.name shouldBe "VACANT"
        support
            .auditEntries(booking.id)
            .map { it["action"] }
            .filter { it in CHECK_IN_ACTIONS }
            .shouldBeEmpty()
        preAuthorizationAudit(booking.folio).shouldBeEmpty()
        support.lifecyclePublications(GuestCheckedIn::class.java, booking.id, LISTENER).shouldBeEmpty()

        // Sem a falha, o mesmo check-in passa.
        checkIn(booking.id, checkInBody(listOf(primary(adult)), preAuthorization = preAuthorization())).expect(200)
        preAuthorizations(booking.folio) shouldHaveSize 1
    }

    @Test
    @Suppress("LongMethod") // as rodadas da corrida e o que a perdedora não pode deixar
    fun `two simultaneous check-ins in the same room never both pass, and the loser leaves nothing (B1)`() {
        repeat(RACE_ROUNDS) { round ->
            withClue("rodada $round") {
                // Um day use e um pernoite na mesma UH hoje: os meios-dias não conflitam (H5), a ocupação sim.
                val stay = stay()
                val first = guest()
                val second = guest()
                val dayUse = stay.book(first, departure = today)
                val overnight = stay.book(second)
                stay.ready()
                val barrier = CyclicBarrier(2)
                CheckInRendezvousProbe.armed[stay.room] = barrier
                val pool = Executors.newFixedThreadPool(2)
                val results =
                    try {
                        listOf(dayUse to first, overnight to second)
                            .map { (booking, guest) ->
                                pool.submit<MockHttpServletResponse> {
                                    checkInRaw(
                                        booking.id,
                                        checkInBody(listOf(primary(guest)), preAuthorization = preAuthorization()),
                                    )
                                }
                            }.map { it.get(CheckInRendezvousProbe.WAIT_SECONDS * 2, TimeUnit.SECONDS) }
                    } finally {
                        CheckInRendezvousProbe.armed.remove(stay.room)
                        pool.shutdownNow()
                    }

                results.map { it.status }.sorted() shouldContainExactly listOf(200, 409)
                val (winner, loser) =
                    listOf(dayUse, overnight).zip(results).partition { (_, response) -> response.status == 200 }
                val lost = loser.single()
                with(billing) { lost.second.problem(409, "ROOM_NOT_READY") }["occupancy"].asString() shouldBe
                    "OCCUPIED"
                support.status(winner.single().first.id) shouldBe "IN_HOUSE"
                support.status(lost.first.id) shouldBe "CONFIRMED"
                support.historyKinds(lost.first.id).shouldBeEmpty()
                preAuthorizations(lost.first.folio).shouldBeEmpty()
                support
                    .auditEntries(
                        lost.first.id,
                    ).map { it["action"] }
                    .filter { it in CHECK_IN_ACTIONS }
                    .shouldBeEmpty()
                support.lifecyclePublications(GuestCheckedIn::class.java, lost.first.id, LISTENER).shouldBeEmpty()
                roomsApi.status(RoomId(stay.room))!!.guestCount shouldBe 1
            }
        }
    }

    @Test
    fun `the atomic occupation of rooms refuses a room that is not ready, changing nothing`() {
        val stay = stay()
        val before = roomsApi.status(RoomId(stay.room))!!
        val attempt = roomsApi.markOccupiedIfReady(RoomId(stay.room), 2, RoomStatusSource.CHECK_IN)
        attempt.ready shouldBe false
        attempt.status.housekeepingStatus shouldBe HousekeepingStatus.DIRTY
        roomsApi.status(RoomId(stay.room))!!.let {
            it.occupancy shouldBe before.occupancy
            it.version shouldBe before.version
        }
        stay.ready()
        roomsApi.markOccupiedIfReady(RoomId(stay.room), 2, RoomStatusSource.CHECK_IN).let {
            it.ready shouldBe true
            it.status.guestCount shouldBe 2
        }
        roomsApi.markOccupiedIfReady(RoomId(stay.room), 3, RoomStatusSource.CHECK_IN).let {
            it.ready shouldBe false
            it.status.guestCount shouldBe 2
        }
    }

    @Test
    fun `a closed reservation folio refuses the check-in with nothing written (B6)`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult)
        stay.ready()
        jdbc
            .sql(
                "UPDATE billing.folio SET status = 'CLOSED', closed_on = opened_on, closed_at = now() WHERE id = :id",
            ).param("id", booking.folio)
            .update()

        checkIn(booking.id, checkInBody(listOf(primary(adult)), preAuthorization = preAuthorization()))
            .problem(409, "FOLIO_CLOSED")
        support.status(booking.id) shouldBe "CONFIRMED"
        roomsApi.status(RoomId(stay.room))!!.occupancy.name shouldBe "VACANT"
    }

    @Test
    fun `a Brazilian minor with only an RG checks in, an adult with only an RG does not (B2)`() {
        val stay = stay()
        val adult = guest()
        val minor = guest(cpf = rg(), document = "RG", birthDate = today.minusYears(7))
        val adultRg = guest(cpf = rg(), document = "RG")
        val booking = stay.book(adult)
        stay.ready()
        val proof = mapOf("proofType" to "PARENT_PRESENT") + responsible(adult)

        checkIn(booking.id, checkInBody(listOf(primary(adultRg), checkInGuest(minor, minorAuthorization = proof))))
            .problem(422, "GUEST_DATA_INCOMPLETE")["incompleteGuests"]
            .values()
            .map { it["guestId"].asString() } shouldContainExactly listOf(adultRg.toString())
        checkIn(booking.id, checkInBody(listOf(primary(adult), checkInGuest(minor, minorAuthorization = proof))))
            .expect(200)
    }

    // --- Recusas --------------------------------------------------------------------------------------------------

    @Test
    fun `a dirty, out of order or inspection pending room is not ready, and an unassigned one is refused`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult, assign = false)
        val body = checkInBody(listOf(primary(adult)), preAuthorization = preAuthorization())

        checkIn(booking.id, body).problem(409, "ROOM_NOT_ASSIGNED")
        stay.assign(booking.id)
        checkIn(booking.id, body).problem(409, "ROOM_NOT_READY").let {
            it["housekeepingStatus"].asString() shouldBe "DIRTY"
            it["roomId"].asString() shouldBe stay.room.toString()
            // Recusada na conferência, antes de ocupar (a UH continua vaga no problema).
            it["occupancy"].asString() shouldBe "VACANT"
        }
        roomsApi.markClean(RoomId(stay.room), true, RoomStatusSource.MANUAL)
        checkIn(booking.id, body).problem(409, "ROOM_NOT_READY")["inspectionPending"].asBoolean() shouldBe true
        roomsApi.markInspected(RoomId(stay.room), RoomStatusSource.MANUAL)
        val ooo =
            roomsApi.openOutOfOrder(
                RoomId(stay.room),
                OutOfOrderKind.OUT_OF_ORDER,
                OutOfOrderRequest(today, today, "Vazamento", "Manutenção", HousekeepingStatus.INSPECTED),
            )
        checkIn(booking.id, body).problem(409, "ROOM_NOT_READY")["outOfOrder"].asString() shouldBe "OUT_OF_ORDER"
        roomsApi.releaseOutOfOrder(ooo.id, RoomStatusSource.MANUAL)

        // Nada ficou de nenhuma tentativa; pronta, entra.
        support.status(booking.id) shouldBe "CONFIRMED"
        preAuthorizations(booking.folio).shouldBeEmpty()
        checkIn(booking.id, body).expect(200)
    }

    @Test
    fun `the room in the body is assigned first, with the assignment permission`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult, assign = false)
        stay.ready()
        val body = checkInBody(listOf(primary(adult)), roomId = stay.room)
        val clerk = ReservationsTestSupport.Operator(iam.createUserWith(checkInGrant()))

        checkIn(booking.id, body, clerk.auth).problem(403, "PERMISSION_DENIED")
        val withAssign =
            ReservationsTestSupport.Operator(
                iam.createUserWith(checkInGrant(), grant(ReservationsPermissions.ASSIGNMENTS_MANAGE)),
            )
        checkIn(booking.id, body, withAssign.auth).expect(200)["reservation"]["roomId"].asString() shouldBe
            stay.room.toString()
    }

    @Test
    fun `a guest without the minimal data is refused with what is missing and no document number`() {
        val stay = stay(adults = 3)
        val adult = guest()
        val noDocument = guest(cpf = null)
        val foreignCpf = newCpf()
        val foreigner = guest(cpf = foreignCpf, nationality = "AR")
        val booking = stay.book(adult)
        stay.ready()

        val problem =
            checkIn(booking.id, checkInBody(listOf(primary(adult), plain(noDocument), plain(foreigner))))
                .problem(422, "GUEST_DATA_INCOMPLETE")
        val incomplete = problem["incompleteGuests"].values().associateBy { it["guestId"].asString() }
        incomplete.keys shouldBe setOf(noDocument.toString(), foreigner.toString())
        incomplete.getValue("$noDocument")["missing"].values().map { it.asString() } shouldContainExactly
            listOf("DOCUMENT")
        incomplete.getValue("$noDocument")["acceptedDocumentTypes"].values().map { it.asString() } shouldContainExactly
            listOf("CPF")
        incomplete.getValue("$foreigner")["acceptedDocumentTypes"].values().map { it.asString() } shouldContainExactly
            listOf("PASSPORT", "RNM", "FOREIGN_ID")
        problem.toString() shouldNotContain foreignCpf
        support.status(booking.id) shouldBe "CONFIRMED"

        // Inexistente: 400 com o campo.
        checkIn(booking.id, checkInBody(listOf(primary(adult), plain(UuidV7.generate()))))
            .problem(400, "UNKNOWN_GUEST")["errors"][0]["field"]
            .asString() shouldBe "guests[1].guestId"
    }

    @Test
    fun `the document check never decrypts a document, even with an illegible number`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult)
        stay.ready()
        val original = documentNumber(adult)
        // Número ilegível (chave que não existe): se a leitura do check-in decifrasse, falharia com 500.
        setDocumentNumber(adult, "v1:missing:AAAA")
        try {
            checkIn(booking.id, checkInBody(listOf(primary(adult)))).expect(200)
        } finally {
            setDocumentNumber(adult, original)
        }
    }

    @Test
    fun `a minor without proof, or with an attachment of another purpose or profile, is refused`() {
        val stay = stay()
        val adult = guest()
        val minor = guest(birthDate = today.minusYears(12))
        val booking = stay.book(adult)
        stay.ready()
        val otherPurpose = upload(ID_DOCUMENT_PHOTO).also { attach(minor, it) }
        val otherProfile = minorPhoto(adult)

        checkIn(booking.id, checkInBody(listOf(primary(adult), plain(minor)), preAuthorization = preAuthorization()))
            .problem(422, "MINOR_AUTHORIZATION_REQUIRED")
        listOf(otherPurpose, otherProfile, UuidV7.generate()).forEach { attachment ->
            withClue(attachment) {
                checkIn(booking.id, checkInBody(listOf(primary(adult), written(minor, adult, attachment))))
                    .problem(422, "MINOR_AUTHORIZATION_ATTACHMENT_INVALID")
                    .let {
                        it["guestId"].asString() shouldBe minor.toString()
                        it["field"].asString() shouldBe "guests[1].minorAuthorization.attachmentId"
                    }
            }
        }
        support.status(booking.id) shouldBe "CONFIRMED"
        preAuthorizations(booking.folio).shouldBeEmpty()

        // A presença do responsável vale sem anexo.
        val parent =
            checkInGuest(minor, minorAuthorization = mapOf("proofType" to "PARENT_PRESENT") + responsible(adult))
        checkIn(booking.id, checkInBody(listOf(primary(adult), parent))).expect(200)
    }

    @Test
    fun `a guest on the restriction list comes as an alert without blocking, and other refusals keep it confirmed`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult)
        stay.ready()
        with(iam) { call(POST, "/api/v1/guests/$adult/restrict", asAdmin(), mapOf("reason" to "Danos ao quarto")).ok() }
        val noPermission = ReservationsTestSupport.Operator(iam.createUser())

        checkIn(booking.id, checkInBody(listOf(primary(adult))), noPermission.auth).problem(403, "PERMISSION_DENIED")
        checkIn(booking.id, checkInBody(listOf(primary(adult)), guestCount = 0)).problem(400, "VALIDATION_FAILED")
        checkIn(UuidV7.generate(), checkInBody(listOf(primary(adult)))).problem(404, "RESERVATION_NOT_FOUND")
        checkIn(
            booking.id,
            checkInBody(
                listOf(primary(adult)),
                preAuthorization = mapOf("amount" to "500.00", "expiresAt" to Instant.now().plusSeconds(3600)),
            ),
        ).problem(400, "PRE_AUTHORIZATION_INVALID")["errors"][0]["field"]
            .asString() shouldBe "preAuthorization.authorizationCode"
        support.status(booking.id) shouldBe "CONFIRMED"

        val alerts = checkIn(booking.id, checkInBody(listOf(primary(adult)))).expect(200)["restrictionAlerts"]
        alerts.values().map { it["guestId"].asString() } shouldContainExactly listOf(adult.toString())
        // Já hospedada: a segunda vez é 409.
        checkIn(booking.id, checkInBody(listOf(primary(adult)))).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED")
    }

    // --- Desfazer -------------------------------------------------------------------------------------------------

    @Test
    fun `undoing the check-in asks for approval, then confirms the reservation and vacates the room without dirt`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult)
        stay.ready()
        val receptionist = ReservationsTestSupport.Operator(iam.createUserWith(checkInGrant()))
        checkIn(
            booking.id,
            checkInBody(listOf(primary(adult)), preAuthorization = preAuthorization()),
            receptionist.auth,
        ).expect(200)

        val required = undo(booking.id, receptionist.auth).problem(403, "APPROVAL_REQUIRED")
        required["action"].asString() shouldBe "frontdesk.stay.undo-check-in"
        required["permission"].asString() shouldBe "frontdesk.stays.undocheckin"
        required["resourceRef"].asString() shouldBe "reservations.reservation:${booking.id}"
        support.status(booking.id) shouldBe "IN_HOUSE"

        val approver = approver()
        val approval = approve(required, receptionist, approver)
        val undone = undo(booking.id, receptionist.auth, approval["approvalToken"].asString()).expect(200)
        undone["reservation"]["status"].asString() shouldBe "CONFIRMED"
        undone["reservation"]["roomId"].asString() shouldBe stay.room.toString()
        undone["room"]["occupancy"].asString() shouldBe "VACANT"
        undone["room"]["housekeepingStatus"].asString() shouldBe "INSPECTED"
        // A caução ficou (liberada à parte, pelo billing).
        preAuthorizations(booking.folio) shouldHaveSize 1
        val entry = support.auditEntries(booking.id).single { it["action"] == "frontdesk.stay.undo-check-in" }
        entry["userId"] shouldBe receptionist.user.id.value
        entry["approvalId"].toString() shouldBe approval["approvalId"].asString()
        approverOf(approval["approvalId"].asString()) shouldBe approver.id.value

        // De novo hospedada, e em outra data operacional não se desfaz mais (antes de pedir a aprovação).
        checkIn(booking.id, checkInBody(listOf(primary(adult)))).expect(200)
        jdbc
            .sql("UPDATE reservations.reservation SET checked_in_on = checked_in_on - 1 WHERE id = :id")
            .param("id", booking.id)
            .update()
        undo(booking.id, receptionist.auth).problem(422, "UNDO_CHECK_IN_EXPIRED")
        undo(booking.id, iam.asAdmin()).problem(422, "UNDO_CHECK_IN_EXPIRED")
        support.status(booking.id) shouldBe "IN_HOUSE"
    }

    @Test
    fun `undoing a confirmed reservation is refused before asking for approval`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult)
        val receptionist = ReservationsTestSupport.Operator(iam.createUserWith(checkInGrant()))

        undo(booking.id, receptionist.auth).problem(409, "RESERVATION_TRANSITION_NOT_ALLOWED").let {
            it["reservationStatus"].asString() shouldBe "CONFIRMED"
            it["action"].asString() shouldBe "UNDO_CHECK_IN"
        }
        undo(booking.id, ReservationsTestSupport.Operator(iam.createUser()).auth).problem(403, "PERMISSION_DENIED")
    }

    // --- Cotação de early/late ------------------------------------------------------------------------------------

    @Test
    fun `the early and late quote follows the current hotel policy in the hotel time zone`() {
        val stay = stay()
        val adult = guest()
        val booking = stay.book(adult, assign = false)
        val path = "/api/v1/hotel/policies"
        val original = with(iam) { call(GET, path, asAdmin()).ok() }
        val originalBody = jsonMap(original)
        try {
            putPolicy(originalBody + mapOf("earlyCheckIn" to null, "lateCheckOut" to null))
            quote(booking.id).let {
                it["earlyCheckIn"]["offered"].asBoolean() shouldBe false
                it["earlyCheckIn"]["fee"].isNull shouldBe true
                it["lateCheckOut"]["offered"].asBoolean() shouldBe false
            }

            val checkInTime = LocalTime.parse(original["checkInTime"].asString())
            val checkOutTime = LocalTime.parse(original["checkOutTime"].asString())
            val earlyLimit = checkInTime.minusHours(3)
            val lateLimit = checkOutTime.plusHours(4)
            putPolicy(
                originalBody +
                    mapOf(
                        "earlyCheckIn" to mapOf("limitTime" to "$earlyLimit", "fee" to "80.00"),
                        "lateCheckOut" to mapOf("limitTime" to "$lateLimit", "fee" to "95.50"),
                    ),
            )
            val zone = hotelApi.timezone()
            val reservation = support.reservation(booking.id)["reservation"]
            val arrival = LocalDate.parse(reservation["arrival"].asString())
            val departure = LocalDate.parse(reservation["departure"].asString())
            quote(booking.id).let {
                it["timezone"].asString() shouldBe zone.id
                it["earlyCheckIn"]["offered"].asBoolean() shouldBe true
                LocalTime.parse(it["earlyCheckIn"]["limitTime"].asString()) shouldBe earlyLimit
                it["earlyCheckIn"]["fee"].asString() shouldBe "80.00"
                Instant.parse(it["earlyCheckIn"]["from"].asString()) shouldBe
                    arrival.atTime(earlyLimit).atZone(zone).toInstant()
                Instant.parse(it["earlyCheckIn"]["until"].asString()) shouldBe
                    arrival.atTime(checkInTime).atZone(zone).toInstant()
                it["lateCheckOut"]["fee"].asString() shouldBe "95.50"
                Instant.parse(it["lateCheckOut"]["until"].asString()) shouldBe
                    departure.atTime(lateLimit).atZone(zone).toInstant()
            }
            with(iam) {
                call(GET, "/api/v1/reservations/${booking.id}/early-late-quote", iam.createUser().let(::asTestUser))
                    .problem(403, "PERMISSION_DENIED")
            }
        } finally {
            putPolicy(originalBody)
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    /** Uma estadia de hoje a depois de amanhã num tipo de UH novo, com uma UH e um plano com preço. */
    private inner class Stay(
        val type: UUID,
        val room: UUID,
        val plan: UUID,
        val adults: Int,
    ) {
        fun book(
            holder: UUID,
            assign: Boolean = true,
            departure: LocalDate = today.plusDays(2),
        ): Booked {
            val created =
                support.created(
                    support.booking(
                        holder,
                        support.reservation(type, plan, today, departure, adults = adults),
                    ),
                )["reservations"][0]
            val id = UUID.fromString(created["id"].asString())
            if (assign) assign(id)
            return Booked(id, UUID.fromString(created["folioId"].asString()))
        }

        fun assign(id: UUID) {
            with(iam) {
                call(POST, "/api/v1/reservations/$id/assign-room", asAdmin(), mapOf("roomId" to room)).ok()
            }
        }

        /** A UH limpa e inspecionada (nasce suja). */
        fun ready() {
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            roomsApi.markInspected(RoomId(room), RoomStatusSource.MANUAL)
        }
    }

    private data class Booked(
        val id: UUID,
        val folio: UUID,
    )

    private fun stay(adults: Int = 2): Stay {
        val (type, rooms) = inventory.roomTypeWithRooms(1, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        return Stay(type, rooms.single(), plan.id, adults)
    }

    private fun guest(
        cpf: String? = newCpf(),
        birthDate: LocalDate = LocalDate.of(1988, 3, 14),
        nationality: String = "BR",
        sensitive: Boolean = false,
        document: String = "CPF",
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("birthDate" to birthDate, "nationality" to nationality),
                            ),
                        documents = listOfNotNull(cpf?.let { support.guests.document(document, it) }),
                        sensitiveData =
                            if (sensitive) mapOf("raceColor" to "BLACK", "disability" to DISABILITY) else null,
                    ),
                )["id"]
                .asString(),
        )

    /** Um RG único (letras e dígitos, de 4 a 20). */
    private fun rg(): String =
        "RG" +
            UuidV7
                .generate()
                .toString()
                .takeLast(10)
                .uppercase()
                .replace("-", "") + "7"

    private fun checkInGuest(
        guest: UUID,
        primary: Boolean = false,
        minorAuthorization: Map<String, Any?>? = null,
    ): Map<String, Any?> =
        mapOf(
            "guestId" to guest,
            "primary" to primary,
            "travelReason" to "LAZER",
            "transportMode" to "AUTOMOVEL",
            "minorAuthorization" to minorAuthorization,
        )

    private fun primary(guest: UUID) = checkInGuest(guest, primary = true)

    private fun plain(guest: UUID) = checkInGuest(guest)

    private fun responsible(adult: UUID) = mapOf("responsibleGuestId" to adult, "relationship" to "PAI")

    private fun written(
        minor: UUID,
        responsible: UUID,
        attachment: UUID,
    ) = checkInGuest(
        minor,
        minorAuthorization =
            mapOf("proofType" to "WRITTEN_AUTHORIZATION", "attachmentId" to attachment) + responsible(responsible),
    )

    private fun preAuthorization(): Map<String, Any?> =
        mapOf(
            "amount" to "500.00",
            "expiresAt" to Instant.now().plus(5, ChronoUnit.DAYS),
            "authorizationCode" to "A1B2C3",
            "nsu" to "004512",
            "cardBrand" to "VISA",
            "cardLast4" to "1234",
        )

    private fun checkInBody(
        guests: List<Map<String, Any?>>,
        preAuthorization: Map<String, Any?>? = null,
        guestCount: Int? = null,
        roomId: UUID? = null,
    ): Map<String, Any?> =
        mapOf(
            "roomId" to roomId,
            "guests" to guests,
            "guestCount" to guestCount,
            "preAuthorization" to preAuthorization,
        )

    private fun checkInRaw(
        id: UUID,
        body: Map<String, Any?>,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String = UuidV7.generate().toString(),
    ) = billing.call(POST, "/api/v1/reservations/$id/check-in", auth, body, idempotencyKey = key)

    private fun checkIn(
        id: UUID,
        body: Map<String, Any?>,
        auth: RequestPostProcessor = iam.asAdmin(),
        key: String = UuidV7.generate().toString(),
    ) = Response(checkInRaw(id, body, auth, key))

    private fun undo(
        id: UUID,
        auth: RequestPostProcessor,
        approvalToken: String? = null,
    ) = Response(
        billing.call(
            POST,
            "/api/v1/reservations/$id/undo-check-in",
            auth,
            idempotencyKey = null,
            approvalToken = approvalToken,
        ),
    )

    private fun quote(id: UUID): JsonNode =
        with(iam) { call(GET, "/api/v1/reservations/$id/early-late-quote", asAdmin()).ok() }

    /** A resposta com os atalhos do `BillingTestSupport`. */
    private inner class Response(
        val raw: MockHttpServletResponse,
    ) {
        fun expect(status: Int): JsonNode = with(billing) { raw.expect(status) }

        fun problem(
            status: Int,
            code: String,
        ): JsonNode = with(billing) { raw.problem(status, code) }
    }

    private fun folio(id: UUID): JsonNode = with(iam) { call(GET, "/api/v1/folios/$id", asAdmin()).ok() }

    private fun folioEtag(id: UUID): String? = iam.call(GET, "/api/v1/folios/$id", iam.asAdmin()).getHeader("ETag")

    private fun preAuthorizations(folio: UUID): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/folios/$folio/card-pre-authorizations", asAdmin()).ok() }["items"]
            .values()
            .toList()

    private fun preAuthorizationAudit(folio: UUID): List<String> =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE action = 'billing.card-pre-authorization.register' " +
                    "AND after_state->>'folioId' = :folio",
            ).param("folio", folio.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun upload(purpose: String): UUID {
        val response =
            mockMvc
                .multipart("/api/v1/files") {
                    file(MockMultipartFile("file", "autorizacao.png", "image/png", TestImages.png(64, 48)))
                    part(MockPart("purpose", purpose.toByteArray(Charsets.UTF_8)))
                    with(iam.asAdmin())
                }.andReturn()
                .response
        return UUID.fromString(with(iam) { response.expectStatus(201)!! }["id"].asString())
    }

    private fun attach(
        guest: UUID,
        file: UUID,
    ) {
        with(iam) {
            call(POST, "/api/v1/guests/$guest/attachments", asAdmin(), mapOf("fileId" to file)).expectStatus(201)
        }
    }

    /** A foto da autorização de menor enviada e vinculada ao perfil [owner]. */
    private fun minorPhoto(owner: UUID): UUID = upload(MINOR_AUTHORIZATION_PHOTO).also { attach(owner, it) }

    private fun documentNumber(guest: UUID): String =
        jdbc
            .sql("SELECT number FROM guests.guest_document WHERE guest_id = :id")
            .param("id", guest)
            .query(String::class.java)
            .single()

    private fun setDocumentNumber(
        guest: UUID,
        number: String,
    ) {
        jdbc
            .sql("UPDATE guests.guest_document SET number = :number WHERE guest_id = :id")
            .param("number", number)
            .param("id", guest)
            .update()
    }

    private fun approverOf(approvalId: String): UUID =
        jdbc
            .sql("SELECT approver_id FROM iam.approval WHERE id = CAST(:id AS uuid)")
            .param("id", approvalId)
            .query(UUID::class.java)
            .single()

    private fun approver(): TestUser =
        iam.createUserWith(grant(FrontdeskPermissions.STAYS_UNDO_CHECK_IN)).also {
            iam.setPin(it, APPROVER_PIN)
        }

    private fun approve(
        problem: JsonNode,
        requester: ReservationsTestSupport.Operator,
        approver: TestUser,
    ): JsonNode =
        with(iam) {
            val body =
                mapOf(
                    "action" to problem["action"].asString(),
                    "permission" to problem["permission"].asString(),
                    "resourceRef" to problem["resourceRef"].asString(),
                    "approverLogin" to approver.login,
                    "approverPin" to APPROVER_PIN,
                    "reason" to "Check-in feito na reserva errada",
                )
            call(POST, "/api/v1/auth/approvals", requester.auth, body).expectStatus(201)!!
        }

    private fun putPolicy(body: Map<String, Any?>) {
        with(iam) {
            val etag = support.guests.etagOf("/api/v1/hotel/policies")
            call(PUT, "/api/v1/hotel/policies", asAdmin(), body, ifMatch = etag).ok()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsonMap(node: JsonNode): Map<String, Any?> =
        jsonMapper.convertValue(node, Map::class.java) as Map<String, Any?>

    private fun checkInGrant() = grant(FrontdeskPermissions.STAYS_CHECK_IN)

    private fun grant(code: PermissionCode): TestGrant {
        val definition =
            (FrontdeskPermissions.permissions + ReservationsPermissions.permissions).single { it.code == code }
        return TestGrant(definition, ALL)
    }

    private companion object {
        const val LISTENER = "StayTransitionsProbeListener"
        const val PRICED_DAYS = 10L
        const val APPROVER_PIN = "4826"
        const val MINOR_AUTHORIZATION_PHOTO = "guests.minor-authorization-photo"
        const val ID_DOCUMENT_PHOTO = "guests.id-document-photo"
        val CHECK_IN_ACTIONS = listOf("reservations.reservation.check-in", "frontdesk.stay.check-in")
        val DISABILITY =
            "DEFICIENCIA_TESTE_" +
                UuidV7
                    .generate()
                    .toString()
                    .takeLast(8)
                    .uppercase()
        const val RACE_ROUNDS = 5
    }
}

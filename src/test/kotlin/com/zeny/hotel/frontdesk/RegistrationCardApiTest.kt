package com.zeny.hotel.frontdesk

import com.zeny.hotel.files.TestImages
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * A ficha de registro em PDF e na impressão (M5 E11, Task 11; D-E11-9 a D-E11-11 e D-E11-14): o conteúdo (código, UH,
 * datas, nome social, nacionalidade, nascimento, endereço, documento mascarado e os códigos FNRH), a ausência do
 * documento inteiro e dos dados de RN-19 no PDF e no ESC/POS, as situações sem ficha, a permissão, o `202` idempotente,
 * a trilha só com ids e o texto hostil no endereço.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions")
class RegistrationCardApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val ibm850 = charset("IBM850")

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- conteúdo ---

    @Test
    fun `the PDF has the stay and the guests data with the masked document and the FNRH codes, and nothing else`() {
        val scenario = scenario()
        val response = pdf(scenario.reservation, iam.asAdmin())

        response.status shouldBe 200
        response.contentType shouldBe "application/pdf"
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain
            "ficha-${scenario.code}.pdf"
        response.getHeader(HttpHeaders.CACHE_CONTROL).shouldNotBeNull() shouldContain "no-store"
        String(response.contentAsByteArray, Charsets.ISO_8859_1) shouldStartWith "%PDF-"

        val text = pdfText(response.contentAsByteArray)
        text shouldContain scenario.code
        text shouldContain scenario.roomNumber
        text shouldContain "Nome Social ${scenario.suffix}"
        text shouldNotContain scenario.civilName
        text shouldContain "BR"
        text shouldContain "14/03/1988"
        text shouldContain "Rua das Acácias"
        text shouldContain "***${scenario.cpf.takeLast(4)}"
        text shouldContain "LAZER (código FNRH)"
        text shouldContain "AUTOMOVEL (código FNRH)"
        text shouldContain "Assinatura"
        forbidden(scenario).forEach { text shouldNotContain it }
    }

    @Test
    fun `the thermal print has the same data and none of the forbidden ones`() {
        val scenario = scenario()
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)

        val accepted = print(scenario.reservation, iam.asAdmin(), printer, UuidV7.generate().toString())
        accepted.status shouldBe 202
        val text = escPosText(UUID.fromString(billing.run { accepted.expect(202) }["printJobId"].asString()))

        text shouldContain scenario.code
        text shouldContain "Nome Social ${scenario.suffix}"
        text shouldContain "LAZER (código FNRH)"
        forbidden(scenario).forEach { text shouldNotContain it }
    }

    @Test
    fun `an address with a line break and a bidi mark comes out clean`() {
        val scenario = scenario(complement = "Apto\n12‮")
        val text = pdfText(pdf(scenario.reservation, iam.asAdmin()).contentAsByteArray)

        text shouldContain "Apto 12"
        text shouldNotContain "‮"
    }

    // --- situações ---

    @Test
    fun `a provisional or a cancelled reservation has no card`() {
        val provisional = scenario(checkIn = false)
        jdbc
            .sql(
                "UPDATE reservations.reservation SET status = 'PROVISIONAL', " +
                    "provisional_until = now() + interval '1 day' WHERE id = :id",
            ).param("id", provisional.reservation)
            .update()
        val cancelled = scenario(checkIn = false)
        support.cancel(cancelled.reservation).status shouldBe 200

        billing.run {
            pdf(provisional.reservation, admin).problem(409, "REGISTRATION_CARD_UNAVAILABLE")
            pdf(cancelled.reservation, admin).problem(409, "REGISTRATION_CARD_UNAVAILABLE")
        }
    }

    @Test
    fun `a confirmed reservation still has the card and an unknown one is 404`() {
        val confirmed = scenario(checkIn = false)

        pdf(confirmed.reservation, iam.asAdmin()).status shouldBe 200
        billing.run { pdf(UuidV7.generate(), admin).problem(404, "RESERVATION_NOT_FOUND") }
    }

    // --- permissão, impressão e trilha ---

    @Test
    fun `without frontdesk stays checkin both routes are 403`() {
        val scenario = scenario()
        val stranger = iam.asTestUser(iam.createUser())

        billing.run {
            pdf(scenario.reservation, stranger).problem(403, "PERMISSION_DENIED")
            print(scenario.reservation, stranger, newPrinter(), UuidV7.generate().toString())
                .problem(403, "PERMISSION_DENIED")
        }
    }

    @Test
    fun `a user with only frontdesk stays checkin gets the card`() {
        val scenario = scenario()
        val definition = FrontdeskPermissions.permissions.single { it.code == FrontdeskPermissions.STAYS_CHECK_IN }
        val clerk = iam.asTestUser(iam.createUserWith(TestGrant(definition, ALL)))

        pdf(scenario.reservation, clerk).status shouldBe 200
    }

    @Test
    fun `printing answers 202, repeats the job on the same key and audits only ids`() {
        val scenario = scenario()
        val printer = newPrinter()
        val key = UuidV7.generate().toString()

        val first = print(scenario.reservation, iam.asAdmin(), printer, key)
        val second = print(scenario.reservation, iam.asAdmin(), printer, key)

        val job = billing.run { first.expect(202) }["printJobId"].asString()
        billing.run { second.expect(202) }["printJobId"].asString() shouldBe job
        val trail =
            jdbc
                .sql(
                    "SELECT after_state::text FROM audit.audit_entry " +
                        "WHERE action = 'frontdesk.registration-card.print' AND entity_id = CAST(:job AS text)",
                ).param("job", job)
                .query(String::class.java)
                .list()
        trail.size shouldBe 1
        trail.single() shouldContain scenario.reservation.toString()
        trail.single() shouldContain printer.toString()
        forbidden(scenario).forEach { trail.single() shouldNotContain it }
        trail.single() shouldNotContain scenario.code
    }

    @Test
    fun `an unknown printer is 400 and the contract describes the binary response`() {
        val scenario = scenario()

        billing.run {
            print(scenario.reservation, admin, UuidV7.generate(), UuidV7.generate().toString())
                .problem(400, "UNKNOWN_PRINTER")
        }
        val block =
            java.io
                .File("api/openapi.yaml")
                .readText()
                .substringAfter("operationId: getRegistrationCardPdf")
                .substringBefore("operationId:")
        block shouldContain "application/pdf"
        block shouldContain "format: binary"
    }

    // --- apoio ---

    private class Scenario(
        val reservation: UUID,
        val code: String,
        val roomNumber: String,
        val suffix: String,
        val civilName: String,
        val cpf: String,
    )

    /** O que não pode sair: o CPF inteiro (com e sem pontuação), a raça/cor e a deficiência (RN-19). */
    private fun forbidden(scenario: Scenario): List<String> =
        listOf(
            scenario.cpf,
            "${scenario.cpf.substring(0, 3)}.${scenario.cpf.substring(3, 6)}.${scenario.cpf.substring(6, 9)}-" +
                scenario.cpf.substring(9),
            "BLACK",
            "Preta",
            "Preto",
            DISABILITY,
        )

    /**
     * Uma reserva em casa (ou só confirmada, sem [checkIn]) com um adulto de nome social, CPF completo, endereço,
     * raça/cor e deficiência gravadas e um menor com a autorização escrita.
     */
    @Suppress("LongMethod")
    private fun scenario(
        checkIn: Boolean = true,
        complement: String = "Apto 12",
    ): Scenario {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
        val (type, rooms) = support.inventory.roomTypeWithRooms(1, today)
        val room = rooms.single()
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val civilName = "Civil Fulano $suffix"
        val cpf = newCpf()
        val adult =
            guest(
                profileExtra =
                    mapOf(
                        "fullName" to civilName,
                        "socialName" to "Nome Social $suffix",
                        "birthDate" to LocalDate.of(1988, 3, 14),
                        "nationality" to "BR",
                        "address" to
                            support.guests.address() + mapOf("street" to "Rua das Acácias", "complement" to complement),
                    ),
                cpf = cpf,
                sensitive = true,
            )
        val minor = guest(profileExtra = mapOf("birthDate" to today.minusYears(9), "nationality" to "BR"))
        val created =
            support.created(
                support.booking(adult, support.reservation(type, plan.id, today, today.plusDays(2), adults = 2)),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        with(iam) {
            call(POST, "/api/v1/reservations/$id/assign-room", asAdmin(), mapOf("roomId" to room)).ok()
        }
        if (checkIn) {
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            roomsApi.markInspected(RoomId(room), RoomStatusSource.MANUAL)
            val proof = upload().also { attach(minor, it) }
            val body =
                mapOf(
                    "guests" to
                        listOf(
                            checkInGuest(adult, primary = true),
                            checkInGuest(
                                minor,
                                minorAuthorization =
                                    mapOf(
                                        "proofType" to "WRITTEN_AUTHORIZATION",
                                        "attachmentId" to proof,
                                        "responsibleGuestId" to adult,
                                        "relationship" to "PAI",
                                    ),
                            ),
                        ),
                )
            billing.run {
                call(
                    POST,
                    "/api/v1/reservations/$id/check-in",
                    admin,
                    body,
                    idempotencyKey = UuidV7.generate().toString(),
                ).expect(200)
            }
        }
        return Scenario(
            reservation = id,
            code = created["code"].asString(),
            roomNumber = roomsApi.room(RoomId(room))!!.number,
            suffix = suffix,
            civilName = civilName,
            cpf = cpf,
        )
    }

    private fun guest(
        profileExtra: Map<String, Any?>,
        cpf: String = newCpf(),
        sensitive: Boolean = false,
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile = support.guests.profileBody(extra = profileExtra),
                        documents = listOf(support.guests.document("CPF", cpf)),
                        sensitiveData =
                            if (sensitive) mapOf("raceColor" to "BLACK", "disability" to DISABILITY) else null,
                    ),
                )["id"]
                .asString(),
        )

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

    private fun upload(): UUID {
        val response =
            mockMvc
                .multipart("/api/v1/files") {
                    file(MockMultipartFile("file", "autorizacao.png", "image/png", TestImages.png(64, 48)))
                    part(MockPart("purpose", "guests.minor-authorization-photo".toByteArray(Charsets.UTF_8)))
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

    private fun pdf(
        reservation: UUID,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/reservations/$reservation/registration-card/pdf") { with(auth) }
            .andReturn()
            .response

    private fun print(
        reservation: UUID,
        auth: RequestPostProcessor,
        printerId: UUID,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/reservations/$reservation/registration-card/print") {
                with(auth)
                header(IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"printerId":"$printerId"}"""
            }.andReturn()
            .response

    private fun newPrinter(kind: PrinterKind = PrinterKind.A4_PDF): UUID {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(iam.adminId().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
            val agent = agents.createAgent("Agente $suffix").view.id
            return agents.createPrinter(NewPrinter("Imp $suffix", kind, "addr-$suffix", agent)).id
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun escPosText(jobId: UUID): String {
        val stored =
            jdbc
                .sql("SELECT content FROM printing.print_job WHERE id = :id")
                .param("id", jobId)
                .query { rs, _ -> rs.getBytes(1) }
                .single()
        val bytes = cipher.decrypt(stored, PrintJobsService.associatedData(jobId))
        return String(bytes, ibm850)
            .replace(Regex("\u001B@|\u001B[ta]|\u001DVB"), "")
            .filter { it == '\n' || it >= ' ' }
    }

    private companion object {
        const val SUFFIX_LENGTH = 12
        const val PRICED_DAYS = 10L
        val DISABILITY =
            "DEFICIENCIA_TESTE_" +
                UuidV7
                    .generate()
                    .toString()
                    .takeLast(8)
                    .uppercase()
    }
}

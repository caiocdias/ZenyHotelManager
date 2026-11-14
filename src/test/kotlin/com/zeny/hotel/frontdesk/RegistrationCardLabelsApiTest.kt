package com.zeny.hotel.frontdesk

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * A ficha de registro mostra o motivo de viagem e o meio de transporte pelo rótulo do catálogo da FNRH (M6 E15,
 * D-E15-3): no PDF e no ESC/POS, o código cru com a nota quando fora do catálogo, o rótulo trocado na ficha seguinte e
 * o rótulo com caractere de controle limpo.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList")
class RegistrationCardLabelsApiTest(
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
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val ibm850 = charset("IBM850")

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `the PDF and the thermal print show the catalog labels`() {
        val reservation = checkedIn("LAZER_FERIAS", "AUTOMOVEL")

        val text = pdfText(pdf(reservation))
        text shouldContain "Lazer/Férias"
        text shouldContain "Automóvel"
        text shouldNotContain "código FNRH"

        val thermal = escPosText(printJob(reservation))
        thermal shouldContain "Lazer/Férias"
        thermal shouldContain "Automóvel"
        thermal shouldNotContain "código FNRH"
    }

    @Test
    fun `a code outside the catalog comes out raw with the FNRH note`() {
        val reservation = checkedIn("LAZER", "AUTOMOVEL")

        val text = pdfText(pdf(reservation))
        text shouldContain "LAZER (código FNRH)"
        text shouldContain "Automóvel"
        escPosText(printJob(reservation)) shouldContain "LAZER (código FNRH)"
    }

    @Test
    fun `a label changed in the catalog shows on the next card`() {
        val reservation = checkedIn("NEGOCIOS", "AUTOMOVEL")

        withLabel("NEGOCIOS", "Negócios e eventos") {
            pdfText(pdf(reservation)) shouldContain "Negócios e eventos"
        }
        pdfText(pdf(reservation)) shouldNotContain "Negócios e eventos"
    }

    @Test
    fun `a label with control characters comes out clean`() {
        val reservation = checkedIn("SAUDE", "AUTOMOVEL")

        withLabel("SAUDE", "Saúde‮\u0007") {
            val text = pdfText(pdf(reservation))
            text shouldContain "Saúde"
            text shouldNotContain "‮"
            text shouldNotContain "\u0007"
            val thermal = escPosText(printJob(reservation))
            thermal shouldContain "Saúde"
            thermal shouldNotContain "‮"
            thermal shouldNotContain "\u0007"
        }
    }

    // --- apoio ---

    private fun checkedIn(
        travelReason: String,
        transportMode: String,
    ): UUID {
        val stay = frontdesk.stay()
        val holder = frontdesk.guest()
        val booked = stay.book(holder)
        stay.ready()
        val body =
            mapOf(
                "guests" to
                    listOf(
                        mapOf(
                            "guestId" to holder,
                            "primary" to true,
                            "travelReason" to travelReason,
                            "transportMode" to transportMode,
                        ),
                    ),
            )
        support.billing.run {
            call(
                POST,
                "/api/v1/reservations/${booked.id}/check-in",
                admin,
                body,
                idempotencyKey = UuidV7.generate().toString(),
            ).expect(200)
        }
        return booked.id
    }

    /** Troca o rótulo de [code] em `TRAVEL_REASONS` por [label] durante [block] e devolve o original (banco compartilhado). */
    private fun withLabel(
        code: String,
        label: String,
        block: () -> Unit,
    ) {
        val where = "WHERE domain = 'TRAVEL_REASONS' AND code = '$code'"
        val original = jdbc.sql("SELECT label FROM fnrh.domain_value $where").query(String::class.java).single()
        try {
            jdbc.sql("UPDATE fnrh.domain_value SET label = :label $where").param("label", label).update()
            block()
        } finally {
            jdbc.sql("UPDATE fnrh.domain_value SET label = :label $where").param("label", original).update()
        }
    }

    private fun pdf(reservation: UUID): ByteArray {
        val response =
            mockMvc
                .get("/api/v1/reservations/$reservation/registration-card/pdf") { with(iam.asAdmin()) }
                .andReturn()
                .response
        response.status shouldBe 200
        return response.contentAsByteArray
    }

    private fun printJob(reservation: UUID): UUID {
        val response =
            mockMvc
                .post("/api/v1/reservations/$reservation/registration-card/print") {
                    with(iam.asAdmin())
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.generate().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"printerId":"${thermalPrinter()}"}"""
                }.andReturn()
                .response
        return UUID.fromString(support.billing.run { response.expect(202) }["printJobId"].asString())
    }

    private fun thermalPrinter(): UUID {
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
            return agents.createPrinter(NewPrinter("Imp $suffix", PrinterKind.THERMAL_ESCPOS, "addr-$suffix", agent)).id
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
    }
}

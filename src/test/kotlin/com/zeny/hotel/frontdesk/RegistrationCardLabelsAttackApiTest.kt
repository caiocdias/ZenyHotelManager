package com.zeny.hotel.frontdesk

import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
import java.time.LocalDate
import java.util.UUID

/**
 * Ataques à ficha com os rótulos da FNRH (M6 E15, Task 2, D-E15-3): campo ausente, vários hóspedes, rótulo longo,
 * caracteres especiais, caractere fora do IBM850 e a ficha que continua sem dado de RN-19 nem documento inteiro.
 * Os rótulos trocados aqui usam códigos que os outros testes não tocam e voltam ao original no `finally`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList")
class RegistrationCardLabelsAttackApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired hotelApi: HotelApi,
    @Autowired roomsApi: RoomsApi,
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
    fun `a guest without travel reason and transport mode has no such line and no null`() {
        val reservation = checkedIn(listOf(null to null))

        val text = pdfText(pdf(reservation))
        text shouldNotContain "Motivo da viagem"
        text shouldNotContain "Meio de transporte"
        text shouldNotContain "null"
        text shouldNotContain "código FNRH"
        val thermal = escPosText(printJob(reservation))
        thermal shouldNotContain "Motivo:"
        thermal shouldNotContain "Transporte:"
        thermal shouldNotContain "null"
        thermal shouldNotContain "código FNRH"
    }

    @Test
    fun `a guest with only the transport mode shows just that line`() {
        val reservation = checkedIn(listOf(null to "TREM"))

        val text = pdfText(pdf(reservation))
        text shouldContain "Trem"
        text shouldNotContain "Motivo da viagem"
        text shouldNotContain "null"
        val thermal = escPosText(printJob(reservation))
        thermal shouldContain "Transporte: Trem"
        thermal shouldNotContain "Motivo:"
    }

    @Test
    fun `two guests with different codes each show their own label`() {
        val reservation = checkedIn(listOf("COMPRAS" to "MOTO", "RELIGIAO" to "BICICLETA"))

        val text = pdfText(pdf(reservation))
        listOf("Compras", "Moto", "Religião", "Bicicleta").forEach { text shouldContain it }
        val thermal = escPosText(printJob(reservation))
        listOf("Motivo: Compras", "Transporte: Moto", "Motivo: Religião", "Transporte: Bicicleta").forEach {
            thermal shouldContain it
        }
        thermal.indexOf("Motivo: Compras") shouldBeLessThan thermal.indexOf("Motivo: Religião")
    }

    @Test
    fun `the same code on two guests shows the label twice and a mix of catalog and raw codes stays apart`() {
        val reservation = checkedIn(listOf("COMPRAS" to "MOTO", "COMPRAS" to "FOGUETE"))

        val thermal = escPosText(printJob(reservation))
        Regex("Motivo: Compras").findAll(thermal).count() shouldBe 2
        Regex("Transporte: Moto\\b").findAll(thermal).count() shouldBe 1
        thermal shouldContain "Transporte: FOGUETE (código FNRH)"
        Regex("Compras").findAll(pdfText(pdf(reservation))).count() shouldBe 2
    }

    @Test
    fun `a long label wraps within 48 columns on the thermal print without losing words`() {
        val label = (1..14).joinToString(" ") { "Palavra$it" }
        val reservation = checkedIn(listOf("ESTUDOS_CURSOS" to "AVIAO"))

        withLabel("TRAVEL_REASONS", "ESTUDOS_CURSOS", label) {
            val lines = escPosText(printJob(reservation)).lines()
            lines.forEach { it.length shouldBeLessThanOrEqual COLUMNS }
            val joined = lines.joinToString(" ")
            (1..14).forEach { joined shouldContain "Palavra$it" }
            pdfText(pdf(reservation)) shouldContain "Palavra14"
        }
    }

    @Test
    fun `a single word longer than the line is cut in the thermal print and not lost`() {
        val word = "X".repeat(110)
        val reservation = checkedIn(listOf("PARENTES_AMIGOS" to "AVIAO"))

        withLabel("TRAVEL_REASONS", "PARENTES_AMIGOS", word) {
            val lines = escPosText(printJob(reservation)).lines()
            lines.forEach { it.length shouldBeLessThanOrEqual COLUMNS }
            lines.joinToString("").count { it == 'X' } shouldBe word.length
        }
    }

    @Test
    fun `a label with angle brackets, ampersand and quotes comes out literal in the PDF and the thermal print`() {
        val label = "A<b>B & \"C\" 'D' &amp; </td>"
        val reservation = checkedIn(listOf("CONGRESSO_FEIRA" to "AVIAO"))

        withLabel("TRAVEL_REASONS", "CONGRESSO_FEIRA", label) {
            val text = pdfText(pdf(reservation))
            text shouldContain "A<b>B & \"C\" 'D' &amp; </td>"
            text shouldContain "Avião"
            val thermal = escPosText(printJob(reservation))
            thermal shouldContain "A<b>B & \"C\" 'D' &amp; </td>"
            thermal shouldNotContain "&lt;"
            thermal shouldNotContain "&quot;"
        }
    }

    @Test
    fun `a raw code with dots and dashes outside the catalog comes out literal and the PDF is still valid`() {
        val reservation = checkedIn(listOf("a.b-9_Z" to "AVIAO"))

        val bytes = pdf(reservation)
        String(bytes, Charsets.ISO_8859_1) shouldStartWith "%PDF-"
        pdfText(bytes) shouldContain "a.b-9_Z (código FNRH)"
        escPosText(printJob(reservation)) shouldContain "a.b-9_Z (código FNRH)"
    }

    @Test
    fun `a label with characters outside IBM850 and WinAnsi comes out with a question mark`() {
        val reservation = checkedIn(listOf("COMPRAS" to "AVIAO"))

        withLabel("TRAVEL_REASONS", "COMPRAS", "Compras 日本 ok") {
            escPosText(printJob(reservation)) shouldContain "Motivo: Compras ?? ok"
            pdfText(pdf(reservation)) shouldContain "Compras ?? ok"
        }
    }

    @Test
    fun `with the labels the card still has no RN-19 data and no whole document number, on both routes`() {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
        val cpf = newCpf()
        val disability = "DEFICIENCIA_ATAQUE_${suffix.uppercase()}"
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile =
                                support.guests.profileBody(
                                    extra = mapOf("birthDate" to LocalDate.of(1988, 3, 14), "nationality" to "BR"),
                                ),
                            documents = listOf(support.guests.document("CPF", cpf)),
                            sensitiveData = mapOf("raceColor" to "BLACK", "disability" to disability),
                        ),
                    )["id"]
                    .asString(),
            )
        val reservation = checkedIn(listOf("LAZER_FERIAS" to "AUTOMOVEL"), holder)

        val formatted = "${cpf.substring(0, 3)}.${cpf.substring(3, 6)}.${cpf.substring(6, 9)}-${cpf.substring(9)}"
        val forbidden = listOf(cpf, formatted, "BLACK", "Preta", "Preto", disability)
        val text = pdfText(pdf(reservation))
        val thermal = escPosText(printJob(reservation))
        text shouldContain "Lazer/Férias"
        thermal shouldContain "Lazer/Férias"
        text shouldContain "***${cpf.takeLast(4)}"
        forbidden.forEach {
            text shouldNotContain it
            thermal shouldNotContain it
        }
    }

    // --- apoio ---

    /** Check-in com um hóspede por par (motivo, meio); o primeiro é o titular (ou [holder]). */
    private fun checkedIn(
        pairs: List<Pair<String?, String?>>,
        holder: UUID = frontdesk.guest(),
    ): UUID {
        val stay = frontdesk.stay()
        val booked = stay.book(holder)
        stay.ready()
        val guests =
            pairs.mapIndexed { index, (reason, mode) ->
                val id = if (index == 0) holder else frontdesk.guest()
                buildMap<String, Any?> {
                    put("guestId", id)
                    put("primary", index == 0)
                    if (reason != null) put("travelReason", reason)
                    if (mode != null) put("transportMode", mode)
                }
            }
        support.billing.run {
            call(
                POST,
                "/api/v1/reservations/${booked.id}/check-in",
                admin,
                mapOf("guests" to guests),
                idempotencyKey = UuidV7.generate().toString(),
            ).expect(200)
        }
        return booked.id
    }

    /** Troca o rótulo de [code] em [domain] por [label] durante [block] e devolve o original (banco compartilhado). */
    private fun withLabel(
        domain: String,
        code: String,
        label: String,
        block: () -> Unit,
    ) {
        val where = "WHERE domain = '$domain' AND code = '$code'"
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
        const val COLUMNS = 48
    }
}

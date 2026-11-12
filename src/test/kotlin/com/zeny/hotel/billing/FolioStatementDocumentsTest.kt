package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.billing.application.FolioStatementDocuments
import com.zeny.hotel.billing.application.FolioStatementService
import com.zeny.hotel.billing.application.StatementAudience
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelFilePurposes
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.pdfImageCount
import com.zeny.hotel.testsupport.pdfPageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.imageio.ImageIO

/**
 * O extrato da conta em PDF e ESC/POS (M5 E11, Task 9; D-E11-2, D-E11-12 e D-E11-14), pelo serviço do `billing`: a
 * prévia do hóspede sem usuário nem motivo, o extrato interno com eles, o dono descrito pela porta
 * [FolioOwnerDescriber] (só de teste, do tipo `GROUP`) ou só pelo tipo, o extrato longo com logotipo, o ESC/POS em 48
 * colunas, a permissão e a trilha do `print` só com ids.
 */
@IntegrationTest
@Import(
    IamTestConfiguration::class,
    MutableClockConfiguration::class,
    FilesTestConfiguration::class,
    FolioStatementDocumentsTest.GroupDescriberConfiguration::class,
)
@Suppress("TooManyFunctions", "LongParameterList")
class FolioStatementDocumentsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val documents: FolioStatementDocuments,
    @Autowired private val statements: FolioStatementService,
    @Autowired private val printing: PrintingApi,
    @Autowired private val guests: GuestsApi,
    @Autowired private val authorization: Authorization,
    @Autowired private val audit: AuditContext,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val jdbc: JdbcClient,
    @Autowired flyway: Flyway,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)
    private val owner = JdbcClient.create(flyway.configuration.dataSource)
    private val ibm850 = charset("IBM850")

    /** O descritor de teste do tipo `GROUP`: sem descritor de produção, para não colidir com o da Task 12. */
    @TestConfiguration(proxyBeanMethods = false)
    class GroupDescriberConfiguration {
        @Bean
        fun groupDescriber(): FolioOwnerDescriber =
            object : FolioOwnerDescriber {
                override val ownerType = FolioOwnerType.GROUP

                override fun describe(ownerId: UUID) =
                    FolioOwnerDescription(
                        label = "Grupo Congresso Azul",
                        guestName = "Maria Responsável",
                        roomNumber = "UH 204",
                        arrival = LocalDate.of(2026, 11, 20),
                        departure = LocalDate.of(2026, 11, 23),
                    )
            }
    }

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- o conteúdo ---

    @Test
    fun `the guest PDF has the folio number, the lines, the totals and the balance but no user nor reason`() {
        val scenario = scenario()
        val text = actingAsAdmin { pdfText(documents.pdf(scenario.folio, StatementAudience.GUEST).content) }

        text shouldContain scenario.number
        text shouldContain "Pagamento — Dinheiro"
        text shouldContain "Lavanderia"
        listOf("R$ 400,00", "R$ 100,00", "R$ 300,00").forEach { text shouldContain it }
        text shouldContain "Saldo"
        text shouldNotContain iam.adminId().toString()
        text shouldNotContain REVERSAL_REASON
    }

    @Test
    fun `the internal PDF has the user and the reason`() {
        val scenario = scenario()
        val text = actingAsAdmin { pdfText(documents.pdf(scenario.folio, StatementAudience.INTERNAL).content) }

        text shouldContain iam.adminId().toString()
        text shouldContain REVERSAL_REASON
    }

    @Test
    fun `without a describer the owner shows only its type`() {
        val scenario = scenario()
        val text = actingAsAdmin { pdfText(documents.pdf(scenario.folio, StatementAudience.GUEST).content) }

        text shouldContain "Reserva"
        text shouldNotContain "Maria Responsável"
    }

    @Test
    fun `with a describer the owner shows the name and the room`() {
        val scenario = scenario(ownerType = "GROUP")
        val text = actingAsAdmin { pdfText(documents.pdf(scenario.folio, StatementAudience.GUEST).content) }

        text shouldContain "Grupo Congresso Azul"
        text shouldContain "Maria Responsável"
        text shouldContain "UH 204"
        text shouldContain "20/11/2026"
        text shouldContain "23/11/2026"
    }

    @Test
    fun `a released and an active pre-authorization show the pt-BR label, not the enum name`() {
        val scenario = scenario()
        with(billing) {
            val folio = scenario.folio.value

            fun hold() =
                call(
                    POST,
                    "/api/v1/folios/$folio/card-pre-authorizations",
                    admin,
                    mapOf(
                        "amount" to "300.00",
                        "expiresAt" to
                            java.time.Instant
                                .now()
                                .plusSeconds(3 * 86_400L)
                                .toString()
                                .substringBefore('.') + "Z",
                        "authorizationCode" to "AUT778",
                        "cardBrand" to "VISA",
                        "cardLast4" to "4242",
                    ),
                ).expect(201)
            val released = hold()
            call(POST, "/api/v1/folios/$folio/card-pre-authorizations/${released["id"].asString()}/release", admin)
                .expect(200)
            hold()
        }
        val text = actingAsAdmin { pdfText(documents.pdf(scenario.folio, StatementAudience.GUEST).content) }

        text shouldContain "liberada"
        text shouldContain "ativa"
        listOf("RELEASED", "ACTIVE", "CAPTURED").forEach { text shouldNotContain it }
    }

    @Test
    fun `two describers of the same type refuse to build the service`() {
        val twice =
            List(2) {
                object : FolioOwnerDescriber {
                    override val ownerType = FolioOwnerType.EVENT

                    override fun describe(ownerId: UUID): FolioOwnerDescription? = null
                }
            }

        shouldThrow<IllegalArgumentException> {
            FolioStatementDocuments(statements, printing, guests, twice, authorization, audit)
        }
    }

    // --- extrato longo ---

    @Test
    fun `a long statement with a logo has several pages, the pagination and less than 5 MiB`() {
        val scenario = scenario(postings = 80)
        val logo = uploadLogo(logoPng())
        val rendered =
            withHotelLogo(logo) { actingAsAdmin { documents.pdf(scenario.folio, StatementAudience.GUEST) } }

        pdfPageCount(rendered.content) shouldBeGreaterThan 1
        pdfText(rendered.content) shouldContain "Página 2 de"
        rendered.content.size shouldBeLessThan FIVE_MIB
        pdfImageCount(rendered.content) shouldBeGreaterThan 0
    }

    // --- ESC/POS ---

    @Test
    fun `the thermal print has every line in at most 48 columns`() {
        val scenario = scenario(postings = 12)
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val job = actingAsAdmin { documents.print(scenario.folio, StatementAudience.GUEST, printer) }

        val text = escPosText(job)
        text shouldContain scenario.number
        text shouldContain "Pagamento"
        text.lines().forEach { it.length shouldBeLessThanOrEqual COLUMNS }
        text.lines().any { it.length > COLUMNS / 2 } shouldBe true
    }

    // --- permissão e trilha ---

    @Test
    fun `without billing folios read the statement is denied`() {
        val scenario = scenario()
        val stranger = iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL))
        val printer = newPrinter(PrinterKind.A4_PDF)

        shouldThrow<PermissionDeniedException> {
            acting(stranger.id.value) { documents.pdf(scenario.folio, StatementAudience.GUEST) }
        }
        shouldThrow<PermissionDeniedException> {
            acting(stranger.id.value) { documents.print(scenario.folio, StatementAudience.GUEST, printer) }
        }
    }

    @Test
    fun `the print audit trail has only ids`() {
        val scenario = scenario()
        val printer = newPrinter(PrinterKind.A4_PDF)
        val job = actingAsAdmin { documents.print(scenario.folio, StatementAudience.INTERNAL, printer) }

        val states =
            jdbc
                .sql(
                    "SELECT CAST(after_state AS text) FROM audit.audit_entry " +
                        "WHERE action = 'billing.statement.print' AND CAST(after_state AS text) LIKE :like",
                ).param("like", "%$job%")
                .query(String::class.java)
                .list()

        states shouldHaveSize 1
        val state = states.single()
        state shouldContain scenario.folio.toString()
        state shouldContain printer.toString()
        listOf("Lavanderia", REVERSAL_REASON, "R$", "Saldo", scenario.number).forEach { state shouldNotContain it }
    }

    // --- apoio ---

    private data class Scenario(
        val folio: FolioId,
        val number: String,
    )

    /**
     * Uma conta com uma diária de 100 de ontem, uma de 300 de hoje, uma lavanderia de 40 estornada (com motivo) e um
     * recebimento de 100 em dinheiro: lançado 400, recebido 100, saldo 300; mais [postings] consumos de 1,00.
     */
    private fun scenario(
        ownerType: String = "RESERVATION",
        postings: Int = 0,
    ): Scenario =
        with(billing) {
            val folio = openFolio(ownerType)
            owner
                .sql(
                    """
                    INSERT INTO billing.posting (id, folio_id, transaction_code_id, posting_type, quantity, unit_price,
                        amount, description, source, business_date, posted_at)
                    SELECT :id, :folio, c.id, 'REGULAR', 1, 100, 100, 'Diária de ontem', 'END_OF_DAY',
                        CURRENT_DATE - 1, now() - interval '1 day'
                    FROM billing.transaction_code c WHERE c.system_key = 'ROOM_CHARGE'
                    """.trimIndent(),
                ).param("id", UuidV7.generate())
                .param("folio", folio)
                .update()
            post(folio, codeId("ROOM_CHARGE"), "300.00")
            val laundry = post(folio, codeId("LAUNDRY"), "40.00")
            call(
                POST,
                "/api/v1/folios/$folio/postings/${laundry["id"].asString()}/reverse",
                admin,
                mapOf("reason" to REVERSAL_REASON),
            ).expect(201)
            repeat(postings) { post(folio, codeId("MISCELLANEOUS"), "1.00") }
            val cashier =
                iam.asTestUser(
                    iam.createUserWith(
                        TestGrant(definition(BillingPermissions.PAYMENTS_RECEIVE), ALL),
                        TestGrant(definition(BillingPermissions.CASHIER_OPERATE), ALL),
                    ),
                )
            openShift(cashier)
            pay(folio, cashier, mapOf("method" to "CASH", "amount" to "100.00")).expect(201)
            Scenario(FolioId(folio), folio(folio)["number"].asString())
        }

    private fun <T> acting(
        userId: UUID,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun <T> actingAsAdmin(block: () -> T): T = acting(iam.adminId(), block)

    private fun newPrinter(kind: PrinterKind): UUID =
        actingAsAdmin {
            val agent = agents.createAgent("Agente ${suffix()}").view.id
            agents.createPrinter(NewPrinter("Imp ${suffix()}", kind, "addr-${suffix()}", agent)).id
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

    private fun suffix() = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private fun logoPng(): ByteArray {
        val image = BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(20, 90, 160)
        graphics.fillRect(0, 0, 120, 60)
        graphics.dispose()
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun uploadLogo(content: ByteArray): UUID =
        with(iam) {
            val response = files.upload(HotelFilePurposes.LOGO.value, asAdmin(), content, "logo.png", "image/png")
            response.status shouldBe 201
            UUID.fromString(json(response)["id"].asString())
        }

    /** Grava o hotel com o [logo], roda [block] e devolve o original no `finally` (banco compartilhado). */
    private fun <T> withHotelLogo(
        logo: UUID,
        block: () -> T,
    ): T =
        with(iam) {
            val original = call(GET, "/api/v1/hotel", asAdmin())
            val originalBody = json(original)
            val configured = originalBody.path("legalName").let { !it.isNull && !it.isMissingNode }
            try {
                call(
                    PUT,
                    "/api/v1/hotel",
                    asAdmin(),
                    hotelBody + ("logoFileId" to logo),
                    ifMatch = original.getHeader(HttpHeaders.ETAG).shouldNotBeNull(),
                ).ok()
                block()
            } finally {
                val etag = call(GET, "/api/v1/hotel", asAdmin()).getHeader(HttpHeaders.ETAG)
                call(PUT, "/api/v1/hotel", asAdmin(), if (configured) originalBody else hotelBody, ifMatch = etag).ok()
            }
        }

    private val hotelBody =
        mapOf(
            "legalName" to "Pousada Extrato Ltda",
            "tradeName" to "Pousada Extrato",
            "cnpj" to "11222333000181",
            "municipalRegistration" to "IM-4455",
            "cadastur" to "CAD-998877",
            "address" to
                mapOf(
                    "street" to "Rua das Flores",
                    "number" to "100",
                    "complement" to "Sala 2",
                    "district" to "Centro",
                    "city" to "São Paulo",
                    "state" to "SP",
                    "postalCode" to "01310-100",
                    "ibgeCityCode" to "3550308",
                ),
            "timezone" to "America/Sao_Paulo",
            "taxRegime" to "SIMPLES_NACIONAL",
            "logoFileId" to null,
        )

    private companion object {
        const val REVERSAL_REASON = "Hóspede não usou a lavanderia"
        const val FIVE_MIB = 5 * 1024 * 1024
        const val COLUMNS = 48
        const val SUFFIX_LENGTH = 12
    }
}

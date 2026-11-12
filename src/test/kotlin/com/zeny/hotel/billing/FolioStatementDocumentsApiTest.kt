package com.zeny.hotel.billing

import com.zeny.hotel.billing.BillingTestSupport.Companion.definition
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.infrastructure.StorageProperties
import com.zeny.hotel.hotel.HotelFilePurposes
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.pdfText
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/**
 * As rotas do extrato em PDF e da sua impressão (M5 E11, Task 10; D-E11-9 e D-E11-10): o `200 application/pdf` com o
 * nome do arquivo e sem cache, o padrão `GUEST`, o `202` idempotente, as recusas, o erro em `problem+json` mesmo com
 * `Accept: application/pdf` e o logotipo ausente do bucket sem derrubar o documento.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, MutableClockConfiguration::class, FilesTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions")
class FolioStatementDocumentsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val s3: S3Client,
    @Autowired private val storage: StorageProperties,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val billing = BillingTestSupport(iam, mockMvc, jsonMapper)

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `the PDF route answers application pdf with the file name and no cache`() {
        val folio = folioWithCharge()
        val response = pdf(folio, billing.admin)

        response.status shouldBe 200
        response.contentType shouldBe "application/pdf"
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain
            "extrato-${folioNumber(folio)}.pdf"
        response.getHeader(HttpHeaders.CACHE_CONTROL).shouldNotBeNull() shouldContain "no-store"
        String(response.contentAsByteArray, Charsets.ISO_8859_1) shouldStartWith "%PDF-"
    }

    @Test
    fun `the audience defaults to GUEST and INTERNAL shows the user`() {
        val folio = folioWithCharge()
        val guest = pdfText(pdf(folio, billing.admin).contentAsByteArray)
        val internal = pdfText(pdf(folio, billing.admin, "INTERNAL").contentAsByteArray)

        guest.contains(iam.adminId().toString()) shouldBe false
        internal shouldContain iam.adminId().toString()
    }

    @Test
    fun `printing answers 202 with the job id and the same key repeats the same job`() {
        val folio = folioWithCharge()
        val printer = newPrinter()
        val key = UuidV7.generate().toString()

        val first = print(folio, billing.admin, printer, key)
        val second = print(folio, billing.admin, printer, key)

        first.status shouldBe 202
        val jobId = UUID.fromString(billing.run { first.expect(202) }["printJobId"].asString())
        UUID.fromString(billing.run { second.expect(202) }["printJobId"].asString()) shouldBe jobId
    }

    @Test
    fun `printing without the Idempotency-Key is 400`() {
        val folio = folioWithCharge()
        val response = print(folio, billing.admin, newPrinter(), key = null)

        response.status shouldBe 400
    }

    @Test
    fun `an unknown printer is 400 UNKNOWN_PRINTER`() {
        val folio = folioWithCharge()

        billing.run {
            print(folio, admin, UuidV7.generate(), UuidV7.generate().toString()).problem(400, "UNKNOWN_PRINTER")
        }
    }

    @Test
    fun `without billing folios read both routes are 403`() {
        val folio = folioWithCharge()
        val printer = newPrinter()
        val stranger = iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL)))

        billing.run {
            pdf(folio, stranger).problem(403, "PERMISSION_DENIED")
            print(folio, stranger, printer, UuidV7.generate().toString()).problem(403, "PERMISSION_DENIED")
        }
    }

    @Test
    fun `an error on the PDF route with Accept application pdf is still problem json`() {
        val folio = folioWithCharge()
        val stranger = iam.asTestUser(iam.createUserWith(TestGrant(definition(BillingPermissions.CODES_READ), ALL)))

        billing.run {
            pdf(UuidV7.generate(), admin, accept = "application/pdf").problem(404, "FOLIO_NOT_FOUND")
            pdf(folio, stranger, accept = "application/pdf").problem(403, "PERMISSION_DENIED")
        }
    }

    @Test
    fun `with the logo object gone from the bucket the PDF is 200 and the print is 202`() {
        val folio = folioWithCharge()
        val printer = newPrinter()
        val logo = uploadLogo()
        withHotelLogo(logo) {
            files.objectKeys("").single { it.endsWith(logo.toString()) }.let { key ->
                s3.deleteObject(
                    DeleteObjectRequest
                        .builder()
                        .bucket(storage.bucket)
                        .key(key)
                        .build(),
                )
            }

            pdf(folio, billing.admin).status shouldBe 200
            print(folio, billing.admin, printer, UuidV7.generate().toString()).status shouldBe 202
        }
    }

    @Test
    fun `the contract describes the binary PDF response`() {
        val spec = java.io.File("api/openapi.yaml").readText()
        val block = spec.substringAfter("operationId: getFolioStatementPdf").substringBefore("operationId:")

        block shouldContain "application/pdf"
        block shouldContain "format: binary"
    }

    // --- apoio ---

    private fun pdf(
        folio: UUID,
        auth: RequestPostProcessor,
        audience: String? = null,
        accept: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/folios/$folio/statement/pdf" + audience?.let { "?audience=$it" }.orEmpty()) {
                with(auth)
                accept?.let { header(HttpHeaders.ACCEPT, it) }
            }.andReturn()
            .response

    private fun print(
        folio: UUID,
        auth: RequestPostProcessor,
        printerId: UUID,
        key: String?,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/folios/$folio/statement/print?audience=GUEST") {
                with(auth)
                if (key != null) header(IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"printerId":"$printerId"}"""
            }.andReturn()
            .response

    private fun folioWithCharge(): UUID =
        with(billing) {
            val folio = openFolio()
            post(folio, codeId("ROOM_CHARGE"), "300.00")
            folio
        }

    private fun folioNumber(folio: UUID): String = billing.folio(folio)["number"].asString()

    private fun newPrinter(): UUID {
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
            return agents.createPrinter(NewPrinter("Imp $suffix", PrinterKind.A4_PDF, "addr-$suffix", agent)).id
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun uploadLogo(): UUID =
        with(iam) {
            val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val response = files.upload(HotelFilePurposes.LOGO.value, asAdmin(), bytes, "logo.png", "image/png")
            withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
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
        const val SUFFIX_LENGTH = 12
    }
}

package com.zeny.hotel.printing

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.infrastructure.StorageProperties
import com.zeny.hotel.hotel.CurrentHotelProfile
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.hotel.HotelFilePurposes
import com.zeny.hotel.hotel.HotelProfile
import com.zeny.hotel.hotel.TaxRegime
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.application.DocumentTemplateCatalog
import com.zeny.hotel.printing.application.HotelLogoReader
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.printing.application.PrintingApiService
import com.zeny.hotel.printing.infrastructure.HtmlPdfRenderer
import com.zeny.hotel.shared.Address
import com.zeny.hotel.shared.BrazilianState
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfImageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import printingexample.PrintingExampleTemplates
import printingexample.PrintingExamplesConfiguration
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID
import javax.imageio.ImageIO

/**
 * A renderização e a impressão de documentos pela [PrintingApi] (M5 E11, Task 8; D-E11-2 a D-E11-7), com o template de
 * exemplo (`printingexample.card`): o cabeçalho do hotel gravado pela API, o rodapé, o logotipo reduzido ou ausente
 * sem erro (inclusive dentro da transação de escrita de quem chama), o texto hostil limpo e literal e a fila da E10.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class, PrintingExamplesConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class DocumentRenderingTest(
    @Autowired private val printing: PrintingApi,
    @Autowired private val context: ApplicationContext,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val filesApi: FilesApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val catalog: DocumentTemplateCatalog,
    @Autowired private val renderer: HtmlPdfRenderer,
    @Autowired private val logos: HotelLogoReader,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val s3: S3Client,
    @Autowired private val storage: StorageProperties,
    @Autowired private val jdbc: JdbcClient,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val hostile = "<script>alert(1)</script> & ‮João\u001B"
    private val hostileClean = "<script>alert(1)</script> & João"
    private val ibm850 = charset("IBM850")

    private val hotelBody =
        mapOf(
            "legalName" to "Pousada Documentos Ltda",
            "tradeName" to "Pousada Documentos",
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

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- apoio ---

    private fun card(
        name: String = "Ana Hóspede",
        extra: Map<String, Any?> = emptyMap(),
    ) = DocumentRequest(
        PrintingExampleTemplates.CARD,
        mapOf("guest" to mapOf("name" to name), "items" to listOf("Item um", "Item dois")) + extra,
    )

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun <T> asAdmin(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(iam.adminId().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    private fun newPrinter(kind: PrinterKind): UUID =
        asAdmin {
            val agent = agents.createAgent("Agente ${suffix()}").view.id
            agents.createPrinter(NewPrinter("Imp ${suffix()}", kind, "addr-${suffix()}", agent)).id
        }

    private fun storedJob(id: UUID): Triple<String, String, ByteArray> =
        jdbc
            .sql("SELECT status, document_type, content FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getBytes(3)) }
            .single()

    private fun contentOf(id: UUID): ByteArray =
        cipher.decrypt(storedJob(id).third, PrintJobsService.associatedData(id))

    private fun png(
        width: Int,
        height: Int,
        noisyRows: Int = 0,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(20, 90, 160)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        val random = Random(7)
        for (y in 0 until noisyRows) for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, "png", bytes)
        return bytes.toByteArray()
    }

    private fun uploadLogo(
        content: ByteArray,
        name: String = "logo.png",
        contentType: String = "image/png",
    ): UUID =
        with(iam) {
            val response = files.upload(HotelFilePurposes.LOGO.value, asAdmin(), content, name, contentType)
            response.status shouldBe 201
            UUID.fromString(json(response)["id"].asString())
        }

    private fun hotelId(): UUID = jdbc.sql("SELECT id FROM hotel.hotel").query(UUID::class.java).single()

    /**
     * Grava o hotel de [hotelBody] com o [logo] pela API, roda [block] e devolve o original no `finally`. Um hotel não
     * configurado não volta a sê-lo pela API: nesse caso fica o [hotelBody] sem logotipo (nenhum logotipo apagado
     * sobra para os outros testes).
     */
    private fun <T> withHotel(
        logo: UUID?,
        block: () -> T,
    ): T =
        with(iam) {
            val original = call(GET, "/api/v1/hotel", asAdmin())
            val originalBody = json(original)
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
                val configured = originalBody.path("legalName").let { !it.isNull && !it.isMissingNode }
                val etag = call(GET, "/api/v1/hotel", asAdmin()).getHeader(HttpHeaders.ETAG)
                call(PUT, "/api/v1/hotel", asAdmin(), if (configured) originalBody else hotelBody, ifMatch = etag).ok()
            }
        }

    private fun profile(
        legalName: String = "Pousada Fixa Ltda",
        tradeName: String = "Pousada Fixa",
    ) = CurrentHotelProfile(
        UUID.randomUUID(),
        HotelProfile(
            legalName = legalName,
            tradeName = tradeName,
            cnpj = Cnpj.of("11222333000181"),
            municipalRegistration = null,
            cadastur = null,
            address = Address("Rua A", "1", null, "Centro", "São Paulo", BrazilianState.SP, "01310100", "3550308"),
            timezone = ZoneId.of("America/Sao_Paulo"),
            taxRegime = TaxRegime.SIMPLES_NACIONAL,
            logoFileId = null,
        ),
    )

    /** A fachada com o perfil do hotel fixado (ou nulo: hotel não configurado) e o relógio parado. */
    private fun serviceWith(current: CurrentHotelProfile?): PrintingApi {
        val hotel =
            object : HotelApi by hotelApi {
                override fun profile(): CurrentHotelProfile? = current

                override fun timezone(): ZoneId = ZoneId.of("America/Sao_Paulo")
            }
        val clock = Clock.fixed(Instant.parse("2026-11-11T13:30:00Z"), ZoneOffset.UTC)
        return PrintingApiService(jobs, catalog, renderer, hotel, logos, clock)
    }

    private fun imageWidths(pdf: ByteArray): List<Int> =
        Loader.loadPDF(pdf).use { document -> document.pages.flatMap { widths(it.resources) } }

    private fun widths(resources: PDResources?): List<Int> =
        resources
            ?.xObjectNames
            ?.flatMap { name ->
                when (val xObject = resources.getXObject(name)) {
                    is PDImageXObject -> listOf(xObject.width)
                    is PDFormXObject -> widths(xObject.resources)
                    else -> emptyList()
                }
            }.orEmpty()

    private fun ByteArray.count(byte: Int) = count { it == byte.toByte() }

    // --- bean ---

    @Test
    fun `a fachada e o unico bean da PrintingApi`() {
        context
            .getBeansOfType(PrintingApi::class.java)
            .values
            .single()
            .shouldBeInstanceOf<PrintingApiService>()
    }

    // --- PDF com o hotel gravado pela API ---

    @Test
    fun `o PDF traz o cabecalho do hotel, o titulo, a emissao, a paginacao e o logotipo`() {
        val logo = uploadLogo(png(120, 60))
        withHotel(logo) {
            val rendered = printing.render(card(), DocumentFormat.PDF)

            rendered.format shouldBe DocumentFormat.PDF
            rendered.mediaType shouldBe "application/pdf"
            String(rendered.content.copyOfRange(0, 5), Charsets.US_ASCII) shouldBe "%PDF-"
            val text = pdfText(rendered.content)
            text shouldContain "Pousada Documentos Ltda"
            text shouldContain "Pousada Documentos"
            text shouldContain "11.222.333/0001-81"
            text shouldContain "CAD-998877"
            text shouldContain "IM-4455"
            text shouldContain "Rua das Flores, 100"
            text shouldContain "São Paulo"
            text shouldContain "01310-100"
            text shouldContain "Ficha de exemplo"
            text shouldMatch Regex("(?s).*Emitido em \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}.*")
            text shouldContain "Página 1 de 1"
            text shouldContain "Ana Hóspede"
            text shouldContain "Item dois"
            pdfImageCount(rendered.content) shouldBe 1
        }
    }

    @Test
    fun `o PNG de 2 MB e 4000 px entra reduzido a 400 px e o PDF fica abaixo de 1 MB`() {
        val big = png(4000, 400, noisyRows = 160)
        (big.size in 1_500_000..HotelFilePurposes.LOGO_MAX_SIZE_BYTES.toInt()) shouldBe true
        val logo = uploadLogo(big)
        withHotel(logo) {
            val rendered = printing.render(card(), DocumentFormat.PDF)

            imageWidths(rendered.content) shouldHaveSize 1
            imageWidths(rendered.content).single() shouldBeLessThanOrEqual 400
            rendered.content.size shouldBeLessThan 1_048_576
        }
    }

    // --- logotipo indisponível, dentro da transação de escrita de quem chama ---

    private fun printsWithoutLogoInsideAWriteTransaction(logo: UUID) {
        val printer = newPrinter(PrinterKind.A4_PDF)
        val jobId =
            tx
                .execute { _ ->
                    printing.render(card(), DocumentFormat.PDF).let { pdfImageCount(it.content) shouldBe 0 }
                    printing.print(card(), printer)
                }.shouldNotBeNull()

        // a transação de quem chama commitou: o job existe, sem logotipo
        storedJob(jobId).first shouldBe "PENDING"
        pdfImageCount(contentOf(jobId)) shouldBe 0
        pdfText(contentOf(jobId)) shouldContain "Pousada Documentos Ltda"
        hotelApi
            .profile()
            .shouldNotBeNull()
            .profile.logoFileId shouldBe logo
    }

    @Test
    fun `logotipo WEBP sai sem imagem e sem erro, dentro de uma transacao de escrita`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(64)
        val logo = uploadLogo(webp, "logo.webp", "image/webp")
        withHotel(logo) { printsWithoutLogoInsideAWriteTransaction(logo) }
    }

    @Test
    fun `logotipo apagado sai sem imagem e sem erro, dentro de uma transacao de escrita`() {
        val logo = uploadLogo(png(80, 40))
        withHotel(logo) {
            filesApi.delete(FileId(logo), HotelFilePurposes.LOGO, hotelId().toString())
            printsWithoutLogoInsideAWriteTransaction(logo)
        }
    }

    @Test
    fun `logotipo sem o objeto no bucket sai sem imagem e sem erro, dentro de uma transacao de escrita`() {
        val logo = uploadLogo(png(80, 40))
        val key = files.objectKeys("").single { it.endsWith(logo.toString()) }
        withHotel(logo) {
            s3.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(storage.bucket)
                    .key(key)
                    .build(),
            )
            printsWithoutLogoInsideAWriteTransaction(logo)
        }
    }

    @Test
    fun `o aviso do logotipo indisponivel nao traz dado do hospede`(output: CapturedOutput) {
        val logo = uploadLogo(png(80, 40))
        withHotel(logo) {
            filesApi.delete(FileId(logo), HotelFilePurposes.LOGO, hotelId().toString())
            printing.render(card(name = "NomeSecretoDoHospede"), DocumentFormat.PDF)
            printing.render(card(name = "NomeSecretoDoHospede"), DocumentFormat.ESCPOS)
        }

        output.all shouldContain "sem logotipo"
        output.all shouldNotContain "NomeSecretoDoHospede"
    }

    // --- hotel não configurado, texto hostil e contexto ---

    @Test
    fun `hotel nao configurado gera o documento so com Hotel no cabecalho e sem logotipo`() {
        val service = serviceWith(null)

        val pdf = service.render(card(), DocumentFormat.PDF).content
        val escpos = String(service.render(card(), DocumentFormat.ESCPOS).content, ibm850)

        pdfText(pdf) shouldContain "Hotel"
        pdfText(pdf) shouldContain "Ana Hóspede"
        pdfText(pdf) shouldContain "Emitido em 11/11/2026 10:30"
        pdfImageCount(pdf) shouldBe 0
        escpos shouldContain "Hotel"
        escpos shouldContain "Hospede: Ana Hóspede"
    }

    @Test
    fun `razao social e nome do modelo hostis aparecem literais e limpos no PDF`() {
        val service = serviceWith(profile(legalName = hostile))

        val text = pdfText(service.render(card(name = hostile), DocumentFormat.PDF).content)

        text.split(hostileClean).size shouldBe 4 // no cabeçalho e duas vezes no corpo do exemplo
        text shouldNotContain "‮"
        text shouldNotContain "\u001B"
    }

    @Test
    fun `o ESC POS traz o texto hostil literal, sem comando injetado e sem entidade escapada`() {
        val service = serviceWith(profile(legalName = hostile))

        val bytes = service.render(card(name = "Ana <b> &#27; & X\u001B\u001DY"), DocumentFormat.ESCPOS).content

        bytes.count(0x1B) shouldBe 4 // ESC @, ESC t 2, ESC a 1 e ESC a 0
        bytes.count(0x1D) shouldBe 1 // o corte
        val text = String(bytes, ibm850)
        text shouldContain "Hospede: Ana <b> &#27; & X Y"
        text shouldContain hostileClean
        text shouldNotContain "&lt;"
        text shouldNotContain "&amp;"
    }

    @Test
    fun `fora do WinAnsi vira interrogacao no PDF e os acentos ficam`() {
        val text =
            pdfText(serviceWith(profile()).render(card(name = "Łukasz Ñandú 李 Ação"), DocumentFormat.PDF).content)

        text shouldContain "?ukasz Ñandú ? Ação"
    }

    @Test
    fun `um modelo com chave hotel nao sombreia o cabecalho`() {
        val service = serviceWith(profile(legalName = "Razao Verdadeira Ltda", tradeName = "Fantasia Verdadeira"))
        val request =
            card(extra = mapOf("hotel" to mapOf("legalName" to "Hotel Sombra", "tradeName" to "Hotel Sombra")))

        val pdf = pdfText(service.render(request, DocumentFormat.PDF).content)
        val escpos = String(service.render(request, DocumentFormat.ESCPOS).content, ibm850)

        pdf shouldContain "Razao Verdadeira Ltda"
        pdf shouldContain "Fantasia Verdadeira"
        pdf shouldNotContain "Hotel Sombra"
        escpos shouldContain "Fantasia Verdadeira"
        escpos shouldNotContain "Hotel Sombra"
    }

    @Test
    fun `o modelo aceita as folhas permitidas e recusa outro tipo com IllegalArgumentException`() {
        val service = serviceWith(profile())
        val leaves =
            mapOf(
                "s" to "texto",
                "n" to 1,
                "d" to BigDecimal("2.5"),
                "b" to true,
                "date" to LocalDate.of(2026, 11, 11),
                "time" to LocalTime.of(14, 0),
                "at" to Instant.parse("2026-11-11T13:30:00Z"),
                "money" to Money.of("10.00"),
                "percent" to Percent.of("10"),
                "none" to null,
                "list" to listOf(mapOf("x" to 1), listOf("y")),
            )
        service.render(card(extra = mapOf("leaves" to leaves)), DocumentFormat.PDF)

        listOf<Any>(
            UUID.randomUUID(),
            mapOf("deep" to listOf(UUID.randomUUID())),
            mapOf(1 to "chave que não é texto"),
            setOf("a"),
            arrayOf("a"),
            StringBuilder("a"),
        ).forEach { bad ->
            shouldThrow<IllegalArgumentException> {
                service.render(
                    card(extra = mapOf("bad" to bad)),
                    DocumentFormat.PDF,
                )
            }
            shouldThrow<IllegalArgumentException> {
                service.render(card(extra = mapOf("bad" to bad)), DocumentFormat.ESCPOS)
            }
        }
        shouldThrow<IllegalArgumentException> {
            service.render(DocumentRequest("printingexample.none", mapOf()), DocumentFormat.PDF)
        }
    }

    // --- impressão pela fila da E10 ---

    @Test
    fun `print numa A4 cria o job PENDING com o codigo do template e um PDF`() {
        val printer = newPrinter(PrinterKind.A4_PDF)

        val id = printing.print(card(), printer)

        val (status, type, _) = storedJob(id)
        status shouldBe "PENDING"
        type shouldBe PrintingExampleTemplates.CARD
        String(contentOf(id).copyOfRange(0, 5), Charsets.US_ASCII) shouldBe "%PDF-"
        pdfText(contentOf(id)) shouldContain "Ana Hóspede"
    }

    @Test
    fun `print numa termica cria o job com ESC POS e o nome do hotel no cabecalho`() {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val expectedName = hotelApi.profile()?.profile?.tradeName ?: "Hotel"

        val id = printing.print(card(), printer)

        val content = contentOf(id)
        storedJob(id).second shouldBe PrintingExampleTemplates.CARD
        content[0] shouldBe 0x1B.toByte()
        content[1] shouldBe 0x40.toByte()
        val text = String(content, ibm850)
        text shouldContain expectedName
        text.indexOf(expectedName) shouldBeLessThan text.indexOf("Hospede: Ana")
        text shouldContain "Ficha de exemplo"
        text shouldContain "Emitido em"
    }

    @Test
    fun `print numa impressora desativada ou desconhecida herda o erro do enqueue`() {
        val printer = newPrinter(PrinterKind.A4_PDF)
        asAdmin { agents.deactivatePrinter(printer) }

        shouldThrow<BusinessRuleException> { printing.print(card(), printer) }.code shouldBe
            PrintingErrorCodes.PRINTER_INACTIVE
        shouldThrow<InvalidInputException> { printing.print(card(), UUID.randomUUID()) }.code shouldBe
            PrintingErrorCodes.UNKNOWN_PRINTER
    }

    // --- log ---

    @Test
    fun `data URI de imagem invalida ou enorme nao aparece no log`(output: CapturedOutput) {
        val encoder = java.util.Base64.getEncoder()
        val invalid = encoder.encodeToString("nao e imagem ZZMARCADORDOLOGOZZ".toByteArray())
        val huge = encoder.encodeToString(ByteArray(1_500_000) { (it % 251).toByte() })
        val broken = "!!!ZZMARCADORQUEBRADOZZ!!!"
        val pngLike = encoder.encodeToString(png(4, 4).copyOfRange(0, 40) + "ZZTRUNCADOZZ".toByteArray())
        listOf(invalid, huge, broken, pngLike).forEach { payload ->
            val error =
                runCatching {
                    renderer.render(
                        """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>x</p>""" +
                            """<img src="data:image/png;base64,$payload"/></body></html>""",
                    )
                }.exceptionOrNull()
            error?.message.orEmpty() shouldNotContain payload.take(24)
        }

        listOf(invalid, huge, broken, pngLike).forEach { output.all shouldNotContain it.take(24) }
        output.all shouldNotContain "ZZMARCADOR"
    }

    @Test
    fun `render nao loga o modelo`(output: CapturedOutput) {
        val pdf = serviceWith(profile()).render(card(name = "OutroNomeSecreto"), DocumentFormat.PDF).content
        serviceWith(profile()).render(card(name = "OutroNomeSecreto"), DocumentFormat.ESCPOS)

        String(pdf, Charsets.ISO_8859_1) shouldStartWith "%PDF-"
        output.all shouldNotContain "OutroNomeSecreto"
    }
}

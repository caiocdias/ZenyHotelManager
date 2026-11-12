package com.zeny.hotel.printing

import com.sun.net.httpserver.HttpServer
import com.zeny.hotel.files.FileContent
import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilePurposeCode
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.files.FilesErrorCodes
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
import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfImageCount
import com.zeny.hotel.testsupport.pdfPageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Ataques à fachada da [PrintingApi] (M5 E11, Task 8): falha do logotipo dentro da transação de escrita de quem
 * chama,
 * sombreamento do contexto, texto hostil literal, tipos e formas do modelo, auditoria do `print` e PDF longo.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class, PrintingExamplesConfiguration::class)
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class DocumentRenderingAttackTest(
    @Autowired private val printing: PrintingApi,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val filesApi: FilesApi,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val catalog: DocumentTemplateCatalog,
    @Autowired private val renderer: HtmlPdfRenderer,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val s3: S3Client,
    @Autowired private val storage: StorageProperties,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)
    private val ibm850 = charset("IBM850")

    private val hotelBody =
        mapOf(
            "legalName" to "Pousada Ataque Ltda",
            "tradeName" to "Pousada Ataque",
            "cnpj" to "11222333000181",
            "municipalRegistration" to "IM-1",
            "cadastur" to "CAD-1",
            "address" to
                mapOf(
                    "street" to "Rua A",
                    "number" to "1",
                    "complement" to null,
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
        items: List<Any?> = listOf("Item um"),
    ) = DocumentRequest(
        PrintingExampleTemplates.CARD,
        mapOf("guest" to mapOf("name" to name), "items" to items) + extra,
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

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(20, 90, 160)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
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

    private fun address(street: String = "Rua A") =
        Address(street, "1", null, "Centro", "São Paulo", BrazilianState.SP, "01310100", "3550308")

    private fun profile(
        legalName: String = "Pousada Fixa Ltda",
        tradeName: String = "Pousada Fixa",
        address: Address = address(),
        logo: UUID? = null,
    ) = CurrentHotelProfile(
        UUID.randomUUID(),
        HotelProfile(
            legalName = legalName,
            tradeName = tradeName,
            cnpj = Cnpj.of("11222333000181"),
            municipalRegistration = null,
            cadastur = null,
            address = address,
            timezone = ZoneId.of("America/Sao_Paulo"),
            taxRegime = TaxRegime.SIMPLES_NACIONAL,
            logoFileId = logo,
        ),
    )

    private fun serviceWith(
        current: CurrentHotelProfile?,
        filesForLogo: FilesApi = filesApi,
    ): PrintingApi {
        val hotel =
            object : HotelApi by hotelApi {
                override fun profile(): CurrentHotelProfile? = current

                override fun timezone(): ZoneId = ZoneId.of("America/Sao_Paulo")
            }
        val clock = Clock.fixed(Instant.parse("2026-11-11T13:30:00Z"), ZoneOffset.UTC)
        return PrintingApiService(
            jobs,
            catalog,
            renderer,
            hotel,
            HotelLogoReader(filesForLogo, transactionManager),
            clock,
        )
    }

    private fun ByteArray.count(byte: Int) = count { it == byte.toByte() }

    private fun storedContent(id: UUID): ByteArray =
        cipher.decrypt(
            jdbc
                .sql("SELECT content FROM printing.print_job WHERE id = :id")
                .param("id", id)
                .query { rs, _ -> rs.getBytes(1) }
                .single(),
            PrintJobsService.associatedData(id),
        )

    private fun jobStatus(id: UUID): String =
        jdbc
            .sql("SELECT status FROM printing.print_job WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .single()

    // --- logotipo com falha dentro da transação de escrita (Review Focus 4) ---

    /** Renderiza e imprime dentro de uma transação de escrita; o commit externo não pode falhar. */
    private fun printInsideWriteTransaction(service: PrintingApi) {
        val printer = newPrinter(PrinterKind.A4_PDF)
        val jobId =
            tx
                .execute { _ ->
                    pdfImageCount(service.render(card(), DocumentFormat.PDF).content) shouldBe 0
                    service.print(card(), printer)
                }.shouldNotBeNull()
        jobStatus(jobId) shouldBe "PENDING"
        pdfImageCount(storedContent(jobId)) shouldBe 0
    }

    private fun failingFiles(failure: () -> Throwable): FilesApi =
        object : FilesApi by filesApi {
            override fun readContent(
                id: FileId,
                purpose: FilePurposeCode,
                ownerEntityId: String,
            ): FileContent = throw failure()
        }

    @Test
    fun `excecao do armazenamento sai sem logotipo e sem erro dentro da transacao de escrita`() {
        val service =
            serviceWith(profile(logo = UUID.randomUUID()), failingFiles { IllegalStateException("bucket fora do ar") })

        printInsideWriteTransaction(service)
    }

    @Test
    fun `anexo sensivel (IllegalArgumentException) sai sem logotipo e sem erro`() {
        val service =
            serviceWith(profile(logo = UUID.randomUUID()), failingFiles { IllegalArgumentException("sensível") })

        printInsideWriteTransaction(service)
    }

    @Test
    fun `erro de negocio do anexo (outro dono) sai sem logotipo e sem erro`() {
        val service =
            serviceWith(
                profile(logo = UUID.randomUUID()),
                failingFiles { ConflictException(FilesErrorCodes.FILE_ALREADY_ATTACHED, "de outro") },
            )

        printInsideWriteTransaction(service)
    }

    @Test
    fun `objeto trocado no bucket (SHA divergente pela fachada real) sai sem logotipo e o commit externo passa`() {
        val logo = uploadLogo(png(80, 40))
        val key = files.objectKeys("").single { it.endsWith(logo.toString()) }
        withHotel(logo) {
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(storage.bucket)
                    .key(key)
                    .build(),
                RequestBody.fromBytes("outro conteudo".toByteArray()),
            )
            printInsideWriteTransaction(printing)
        }
    }

    @Test
    fun `PNG corrompido (so o inicio) sai sem logotipo e sem erro dentro da transacao de escrita`() {
        val truncated = png(300, 200).let { it.copyOfRange(0, it.size / 2) }
        val logo = uploadLogo(truncated)
        withHotel(logo) { printInsideWriteTransaction(printing) }
    }

    @Test
    fun `lixo declarado como PNG sai sem logotipo e sem erro dentro da transacao de escrita`() {
        val junk = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(500) { 7 }
        val logo = uploadLogo(junk)
        withHotel(logo) { printInsideWriteTransaction(printing) }
    }

    @Test
    fun `logotipo desvinculado do anexo (hotel aponta para um id inexistente) sai sem logotipo e sem erro`() {
        val service = serviceWith(profile(logo = UUID.randomUUID()))

        printInsideWriteTransaction(service)
    }

    // --- contexto do template ---

    @Test
    fun `o modelo nao sombreia hotel, logo, fmt, issuedAt, title nem bodyTemplate, na raiz e aninhados`() {
        val names = listOf("hotel", "logo", "fmt", "issuedAt", "title", "bodyTemplate", "document")
        val shadow = names.associateWith { "SOMBRA-$it" }
        val nested =
            names.associateWith {
                mapOf(
                    it to "SOMBRA-$it",
                    "tradeName" to "SOMBRA-t",
                    "legalName" to "SOMBRA-l",
                )
            }
        val service = serviceWith(profile(legalName = "Razao Verdadeira Ltda", tradeName = "Fantasia Verdadeira"))
        val request = card(extra = shadow + mapOf("nested" to nested))

        val pdf = pdfText(service.render(request, DocumentFormat.PDF).content)
        val escpos = String(service.render(request, DocumentFormat.ESCPOS).content, ibm850)

        pdf shouldContain "Razao Verdadeira Ltda"
        pdf shouldContain "Fantasia Verdadeira"
        pdf shouldContain "Ficha de exemplo"
        pdf shouldContain "Emitido em 11/11/2026 10:30"
        pdf shouldContain "Ana Hóspede"
        pdfImageCount(service.render(request, DocumentFormat.PDF).content) shouldBe 0
        pdf shouldNotContain "SOMBRA"
        escpos shouldContain "Fantasia Verdadeira"
        escpos shouldContain "Ficha de exemplo"
        escpos shouldNotContain "SOMBRA"
    }

    @Test
    fun `valores com expressoes de template, marcacao e entidades saem literais e limpos no PDF e no ESC POS`() {
        val values =
            listOf(
                "\${7*7}",
                "__\${7*7}__",
                "[[\${7*7}]]",
                "[(\${7*7})]",
                "*{x}",
                "#{msg}",
                "~{layout}",
                "<script>alert(1)</script>",
                "&#27; &#x1B; &amp; &lt;",
                "ESC GS",
                "A‮B​C D",
            )
        val service = serviceWith(profile())
        values.forEach { value ->
            val pdf = pdfText(service.render(card(name = value), DocumentFormat.PDF).content)
            val escposBytes = service.render(card(name = value), DocumentFormat.ESCPOS).content
            val escpos = String(escposBytes, ibm850)
            val expected = value.replace("‮", "").replace("​", "").replace(" ", " ")

            pdf shouldContain expected
            pdf shouldNotContain "49"
            escpos shouldContain "Hospede: $expected"
            escpos shouldNotContain "49"
            escposBytes.count(0x1B) shouldBe 4
            escposBytes.count(0x1D) shouldBe 1
        }
    }

    @Test
    fun `nenhum byte de comando no ESC POS vem do perfil do hotel`() {
        val hostile = "X\u001BpY\u001DVZ\u0010\u0004"
        val service =
            serviceWith(profile(legalName = hostile, tradeName = hostile, address = address(street = hostile)))

        val bytes = service.render(card(name = hostile), DocumentFormat.ESCPOS).content

        bytes.count(0x1B) shouldBe 4 // ESC @, ESC t 2, ESC a 1 e ESC a 0
        bytes.count(0x1D) shouldBe 1 // o corte
        bytes.count(0x10) shouldBe 0
        bytes.count(0x04) shouldBe 0
    }

    // --- tipos e formas do modelo ---

    @Test
    fun `Sequence, objeto, datas de outro tipo, Char e Pair sao recusados com IllegalArgumentException`() {
        val service = serviceWith(profile())
        listOf<Any>(
            sequenceOf(1, 2),
            Any(),
            Pair(1, 2),
            java.util.Date(),
            java.time.LocalDateTime.now(),
            java.time.ZonedDateTime.now(),
            java.time.Duration.ZERO,
            'c',
            byteArrayOf(1),
            listOf(listOf(mapOf("fundo" to Instant.now(), "ruim" to UUID.randomUUID()))),
            mapOf("a" to mapOf(UUID.randomUUID() to "chave uuid")),
        ).forEach { bad ->
            DocumentFormat.entries.forEach { format ->
                shouldThrow<IllegalArgumentException> {
                    service.render(card(extra = mapOf("bad" to bad)), format)
                }
            }
        }
    }

    @Test
    fun `o erro de tipo cita o caminho e nunca o valor`() {
        val service = serviceWith(profile())

        val error =
            shouldThrow<IllegalArgumentException> {
                service.render(
                    card(extra = mapOf("bad" to listOf(mapOf("k" to StringBuilder("SEGREDO-DO-HOSPEDE"))))),
                    DocumentFormat.PDF,
                )
            }

        error.message.orEmpty() shouldNotContain "SEGREDO-DO-HOSPEDE"
        error.message.orEmpty() shouldContain "bad"
    }

    @Test
    fun `modelo ciclico falha com IllegalArgumentException e nao com estouro de pilha`() {
        val cyclicList = mutableListOf<Any?>()
        cyclicList.add(cyclicList)
        val cyclicMap = mutableMapOf<String, Any?>()
        cyclicMap["self"] = cyclicMap
        val service = serviceWith(profile())

        listOf<Any>(cyclicList, cyclicMap).forEach { cyclic ->
            val failure = runCatching { service.render(card(extra = mapOf("loop" to cyclic)), DocumentFormat.PDF) }
            failure.exceptionOrNull()?.javaClass shouldBe IllegalArgumentException::class.java
        }
    }

    @Test
    fun `modelo muito profundo falha com IllegalArgumentException e nao com estouro de pilha`() {
        var deep: Any? = "folha"
        repeat(200_000) { deep = listOf(deep) }
        val service = serviceWith(profile())

        val failure = runCatching { service.render(card(extra = mapOf("deep" to deep)), DocumentFormat.ESCPOS) }

        failure.exceptionOrNull()?.javaClass shouldBe IllegalArgumentException::class.java
    }

    // --- print ---

    @Test
    fun `print audita uma unica vez, pelo PrintJobsService, so com o id`() {
        val printer = newPrinter(PrinterKind.A4_PDF)

        val id = printing.print(card(name = "NomeAuditavelZZ"), printer)

        val actions =
            jdbc
                .sql("SELECT action FROM audit.audit_entry WHERE CAST(after_state AS text) LIKE :like")
                .param("like", "%$id%")
                .query(String::class.java)
                .list()
        actions shouldBe listOf("printing.job.create")
        jdbc
            .sql("SELECT count(*) FROM audit.audit_entry WHERE CAST(after_state AS text) LIKE '%NomeAuditavelZZ%'")
            .query(Int::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `print com modelo invalido nao deixa job na fila`() {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)

        shouldThrow<IllegalArgumentException> { printing.print(card(extra = mapOf("x" to UUID.randomUUID())), printer) }

        jdbc
            .sql("SELECT count(*) FROM printing.print_job WHERE printer_id = :p")
            .param("p", printer)
            .query(Int::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `print numa impressora desconhecida e recusado antes de renderizar`() {
        val error =
            shouldThrow<InvalidInputException> {
                printing.print(card(extra = mapOf("x" to UUID.randomUUID())), UUID.randomUUID())
            }

        error.code shouldBe PrintingErrorCodes.UNKNOWN_PRINTER
    }

    // --- PDF longo e sem recurso externo ---

    @Test
    fun `documento de varias paginas traz Pagina X de Y em todas e o cabecalho longo nao quebra`() {
        val long = "Razao Social Muito Longa ".repeat(20).trim()
        val service =
            serviceWith(
                profile(
                    legalName = long,
                    tradeName = "Fantasia ".repeat(15).trim(),
                    address =
                        address("Rua " + "Comprida ".repeat(30)),
                ),
            )
        val items = (1..300).map { "Linha numero $it do documento" }

        val pdf = service.render(card(items = items), DocumentFormat.PDF).content

        val pages = pdfPageCount(pdf)
        pages shouldBeGreaterThan 1
        val text = pdfText(pdf)
        text shouldContain "Página 1 de $pages"
        text shouldContain "Página $pages de $pages"
        text shouldContain "Linha numero 300"
        text shouldContain "Razao Social Muito Longa"
    }

    @Test
    fun `tags de recurso externo no modelo e no perfil do hotel nao geram acesso nem imagem`() {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/x.png"
            val tag = """<img src="$url"/> <link rel="stylesheet" href="$url"/> <style>@import url($url);</style>"""
            val service = serviceWith(profile(legalName = tag, tradeName = tag, address = address(street = tag)))

            val pdf = service.render(card(name = tag, items = listOf(tag)), DocumentFormat.PDF).content

            hits.get() shouldBe 0
            pdfImageCount(pdf) shouldBe 0
            pdfText(pdf) shouldContain "<img src=\"$url\"/>"
        } finally {
            server.stop(0)
        }
    }
}

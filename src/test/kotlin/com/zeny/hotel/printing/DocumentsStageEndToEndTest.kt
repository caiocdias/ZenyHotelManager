package com.zeny.hotel.printing

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.BillingTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.frontdesk.FrontdeskPermissions
import com.zeny.hotel.frontdesk.FrontdeskTestSupport
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.hotel.HotelFilePurposes
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfImageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
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
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockPart
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Passada de etapa da M5 E11, ponta a ponta com os módulos reais: o hotel e o logotipo gravados pela API (original
 * devolvido no `finally`), um check-in com hóspede de CPF e dados de RN-19, um lançamento e um pagamento na conta, os
 * três PDFs baixados pelas rotas dos donos e os três documentos impressos numa impressora `A4_PDF` e numa
 * `THERMAL_ESCPOS` de um agente de teste, que recebe os jobs pelo protocolo da E10 (long-poll com o token do agente).
 * Também as costuras: o mesmo `Idempotency-Key` em rotas de documentos diferentes, as permissões cruzadas e a ausência
 * de listener de eventos no `printing`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions")
class DocumentsStageEndToEndTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing = support.billing
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val ibm850 = charset("IBM850")

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    private class Agent(
        val id: UUID,
        val token: String,
    )

    private class Stay(
        val reservation: UUID,
        val folio: UUID,
        val code: String,
        val roomNumber: String,
        val suffix: String,
        val civilName: String,
        val cpf: String,
    )

    // --- o fluxo completo ---

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
    fun `the three documents come out as PDF and as ESC POS through the agent protocol with the hotel header`() {
        val logo = uploadLogo()
        withHotel(logo, "Pousada E2E Ltda", "Pousada E2E") {
            val stay = stay()
            val room = stay.roomNumber
            billing.post(stay.folio, billing.codeId("ROOM_CHARGE"), "250.00")
            frontdesk.pay(stay.folio, frontdesk.cashier(), "100.00")
            val statementNumber = billing.folio(stay.folio)["number"].asString()

            // 1. Os três PDFs pelas rotas dos módulos donos.
            val card = pdf("/api/v1/reservations/${stay.reservation}/registration-card/pdf")
            val statement = pdf("/api/v1/folios/${stay.folio}/statement/pdf")
            val confirmation = pdf("/api/v1/reservations/${stay.reservation}/confirmation/pdf")
            val cardText = pdfText(card)
            val statementText = pdfText(statement)
            val confirmationText = pdfText(confirmation)

            cardText shouldContain stay.code
            cardText shouldContain room
            cardText shouldContain "Nome Social ${stay.suffix}"
            cardText shouldContain "***${stay.cpf.takeLast(LAST4)}"
            statementText shouldContain statementNumber
            statementText shouldContain "250,00"
            statementText shouldContain "100,00"
            statementText shouldContain "150,00"
            statementText shouldContain "Nome Social ${stay.suffix}"
            confirmationText shouldContain stay.code
            confirmationText shouldContain "Nome Social ${stay.suffix}"
            val policy = hotelApi.policy()
            confirmationText shouldContain "Check-in a partir das ${policy.checkInTime.format(HOUR)}"
            confirmationText shouldContain "Check-out até as ${policy.checkOutTime.format(HOUR)}"

            listOf(cardText, statementText, confirmationText).forEach { text ->
                text shouldContain "Pousada E2E Ltda"
                text shouldContain "11.222.333/0001-81"
                text shouldContain "CAD-998877"
                text shouldContain "Rua das Flores, 100"
                forbidden(stay).forEach { text shouldNotContain it }
            }
            listOf(card, statement, confirmation).forEach { pdfImageCount(it) shouldBeGreaterThan 0 }

            // 2. Mudar o hotel pela API muda o PDF seguinte (nada de texto fixo).
            putHotel("Pousada Renomeada Ltda", "Pousada Renomeada", logo)
            val renamed = pdfText(pdf("/api/v1/reservations/${stay.reservation}/confirmation/pdf"))
            renamed shouldContain "Pousada Renomeada Ltda"
            renamed shouldNotContain "Pousada E2E Ltda"
            putHotel("Pousada E2E Ltda", "Pousada E2E", logo)

            // 3. Impressão dos três numa A4_PDF e numa térmica do mesmo agente.
            val agent = newAgent()
            val a4 = newPrinter(agent, "A4_PDF")
            val thermal = newPrinter(agent, "THERMAL_ESCPOS")
            val routes =
                mapOf(
                    "registration-card" to "/api/v1/reservations/${stay.reservation}/registration-card/print",
                    "statement" to "/api/v1/folios/${stay.folio}/statement/print",
                    "confirmation" to "/api/v1/reservations/${stay.reservation}/confirmation/print",
                )
            val created = mutableMapOf<UUID, Pair<String, UUID>>() // job -> (documento, impressora)
            for ((name, path) in routes) {
                for (printer in listOf(a4, thermal)) {
                    val accepted = print(path, printer)
                    accepted.status shouldBe 202
                    created[UUID.fromString(json(accepted)["printJobId"].asString())] = name to printer
                }
            }

            // 4. O agente recebe os seis jobs pelo protocolo da E10 e lê o conteúdo com o token.
            val delivered = pollUntil(agent, created.size)
            delivered.map { UUID.fromString(it["id"].asString()) } shouldContainExactlyInAnyOrder created.keys
            for (job in delivered) {
                val id = UUID.fromString(job["id"].asString())
                val (name, printer) = created.getValue(id)
                job["printerId"].asString() shouldBe printer.toString()
                job["documentType"].asString() shouldContain name
                val content = agentGet("/api/v1/print-agents/me/jobs/$id/content", agent.token)
                content.status shouldBe 200
                val expectedText =
                    when (name) {
                        "registration-card" -> stay.code
                        "statement" -> statementNumber
                        else -> stay.code
                    }
                if (printer == a4) {
                    job["mediaType"].asString() shouldBe "application/pdf"
                    content.contentType shouldBe "application/pdf"
                    String(content.contentAsByteArray, Charsets.ISO_8859_1) shouldStartWith "%PDF-"
                    val text = pdfText(content.contentAsByteArray)
                    text shouldContain expectedText
                    text shouldContain "Pousada E2E Ltda"
                    pdfImageCount(content.contentAsByteArray) shouldBeGreaterThan 0
                    forbidden(stay).forEach { text shouldNotContain it }
                } else {
                    val bytes = content.contentAsByteArray
                    job["mediaType"].asString() shouldBe "application/octet-stream"
                    String(bytes, Charsets.ISO_8859_1) shouldNotContain "%PDF"
                    bytes[0] shouldBe 0x1B.toByte()
                    bytes[1] shouldBe '@'.code.toByte()
                    bytes.takeLast(GS_V_CUT.size) shouldBe GS_V_CUT.toList()
                    val text = escPosText(bytes)
                    text shouldContain expectedText
                    text shouldContain "Pousada E2E Ltda"
                    text shouldContain "11.222.333/0001-81"
                    forbidden(stay).forEach { text shouldNotContain it }
                    if (name == "registration-card") text shouldContain "***${stay.cpf.takeLast(LAST4)}"
                    if (name == "statement") text shouldContain "SALDO: R\$ 150,00"
                }
                agentPost("/api/v1/print-agents/me/jobs/$id/ack", agent.token, mapOf("result" to "PRINTED"))
                    .status shouldBe 204
            }
            created.keys.forEach { statusOf(it) shouldBe "PRINTED" }
        }
    }

    // --- costuras ---

    @Test
    fun `the same Idempotency-Key on a different document route is refused and never replays another document`() {
        val stay = stay()
        val printer = newPrinter(newAgent(), "A4_PDF")
        val key = UuidV7.generate().toString()
        val cardPath = "/api/v1/reservations/${stay.reservation}/registration-card/print"

        val first = print(cardPath, printer, key)
        first.status shouldBe 202
        val job = json(first)["printJobId"].asString()

        listOf(
            "/api/v1/reservations/${stay.reservation}/confirmation/print",
            "/api/v1/folios/${stay.folio}/statement/print",
        ).forEach { path ->
            val second = print(path, printer, key)
            with(iam) { second.problem(422, "IDEMPOTENCY_KEY_REUSED") }
            second.contentAsString shouldNotContain job
        }
        // O que a mesma rota repete é o mesmo job; os outros documentos não geraram job algum.
        json(print(cardPath, printer, key))["printJobId"].asString() shouldBe job
        jobsOf(printer) shouldBe 1
    }

    @Test
    fun `whoever has only one of the three permissions opens only its own document, on both routes`() {
        val stay = stay()
        val printer = newPrinter(newAgent(), "A4_PDF")
        val users =
            mapOf(
                "card" to
                    grantUser(
                        FrontdeskPermissions.permissions.single { it.code == FrontdeskPermissions.STAYS_CHECK_IN },
                    ),
                "statement" to grantUser(BillingTestSupport.definition(BillingPermissions.FOLIOS_READ)),
                "confirmation" to
                    grantUser(
                        ReservationsPermissions.permissions.single {
                            it.code == ReservationsPermissions.RESERVATIONS_READ
                        },
                    ),
            )
        val pdfPaths =
            mapOf(
                "card" to "/api/v1/reservations/${stay.reservation}/registration-card/pdf",
                "statement" to "/api/v1/folios/${stay.folio}/statement/pdf",
                "confirmation" to "/api/v1/reservations/${stay.reservation}/confirmation/pdf",
            )
        val printPaths =
            mapOf(
                "card" to "/api/v1/reservations/${stay.reservation}/registration-card/print",
                "statement" to "/api/v1/folios/${stay.folio}/statement/print",
                "confirmation" to "/api/v1/reservations/${stay.reservation}/confirmation/print",
            )

        for ((owner, auth) in users) {
            for ((document, path) in pdfPaths) {
                val response = pdfResponse(path, auth)
                if (owner == document) {
                    response.status shouldBe 200
                } else {
                    with(iam) { response.problem(403, "PERMISSION_DENIED") }
                }
            }
            for ((document, path) in printPaths) {
                val response = print(path, printer, auth = auth)
                if (owner == document) {
                    response.status shouldBe 202
                } else {
                    with(iam) { response.problem(403, "PERMISSION_DENIED") }
                }
            }
        }
        // Só os três 202 da diagonal criaram job.
        jobsOf(printer) shouldBe users.size
    }

    @Test
    fun `printing has no event listener of any kind, while a module that listens is detected by the same rule`() {
        val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.zeny.hotel")

        fun listeners(pkg: String): List<String> =
            classes
                .filter { it.packageName.startsWith(pkg) }
                .flatMap { type: JavaClass -> type.methods.filter(::isListener).map { "${type.name}.${it.name}" } } +
                classes
                    .filter { it.packageName.startsWith(pkg) }
                    .filter { type ->
                        type.allRawInterfaces.any {
                            it.name ==
                                "org.springframework.context.ApplicationListener"
                        }
                    }.map { it.name }

        listeners("com.zeny.hotel.fnrh").isNotEmpty() shouldBe true
        listeners("com.zeny.hotel.printing") shouldBe emptyList()
        classes.count { it.packageName.startsWith("com.zeny.hotel.printing") } shouldBeGreaterThan 0
    }

    // --- apoio ---

    private fun isListener(method: JavaMethod): Boolean = LISTENER_ANNOTATIONS.any { method.isAnnotatedWith(it) }

    private fun forbidden(stay: Stay): List<String> =
        listOf(
            stay.cpf,
            "${stay.cpf.substring(
                0,
                3,
            )}.${stay.cpf.substring(3, 6)}.${stay.cpf.substring(6, 9)}-${stay.cpf.substring(9)}",
            "BLACK",
            "Preta",
            "Preto",
            DISABILITY,
        )

    private fun grantUser(definition: com.zeny.hotel.shared.PermissionDefinition): RequestPostProcessor =
        iam.asTestUser(iam.createUserWith(TestGrant(definition, ALL)))

    private fun json(response: MockHttpServletResponse): JsonNode = jsonMapper.readTree(response.contentAsString)

    private fun pdfResponse(
        path: String,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = mockMvc.request(GET, path) { with(auth) }.andReturn().response

    private fun pdf(path: String): ByteArray {
        val response = pdfResponse(path)
        response.status shouldBe 200
        response.contentType shouldBe "application/pdf"
        return response.contentAsByteArray
    }

    private fun print(
        path: String,
        printer: UUID,
        key: String = UuidV7.generate().toString(),
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse =
        mockMvc
            .request(POST, path) {
                with(auth)
                header(IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"printerId":"$printer"}"""
            }.andReturn()
            .response

    private fun jobsOf(printer: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM printing.print_job WHERE printer_id = :id")
            .param("id", printer)
            .query(Int::class.java)
            .single()

    private fun statusOf(job: UUID): String =
        jdbc
            .sql(
                "SELECT status FROM printing.print_job WHERE id = :id",
            ).param("id", job)
            .query(String::class.java)
            .single()

    private fun escPosText(bytes: ByteArray): String =
        String(bytes, ibm850)
            .replace(Regex("\u001B@|\u001B[ta]."), "")
            .replace(Regex("\u001BV.|\u001DV.."), "")
            .filter { it == '\n' || it >= ' ' }

    // agente (protocolo da E10)

    private fun newAgent(): Agent {
        val response =
            iam.call(
                POST,
                "/api/v1/print-agents",
                iam.asAdmin(),
                mapOf("name" to "Agente ${UuidV7.generate().toString().takeLast(SUFFIX)}"),
            )
        response.status shouldBe 201
        val body = json(response)
        return Agent(UUID.fromString(body["id"].asString()), body["token"].asString())
    }

    private fun newPrinter(
        agent: Agent,
        kind: String,
    ): UUID {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX)
        val response =
            iam.call(
                POST,
                "/api/v1/printers",
                iam.asAdmin(),
                mapOf("name" to "Imp $suffix", "kind" to kind, "address" to "addr-$suffix", "agentId" to agent.id),
            )
        response.status shouldBe 201
        return UUID.fromString(json(response)["id"].asString())
    }

    private fun agentGet(
        path: String,
        token: String,
    ): MockHttpServletResponse {
        val result = mockMvc.request(GET, path) { header(HttpHeaders.AUTHORIZATION, "Bearer $token") }.andReturn()
        if (!result.request.isAsyncStarted) return result.response
        result.asyncResult
        return mockMvc.perform(asyncDispatch(result)).andReturn().response
    }

    private fun agentPost(
        path: String,
        token: String,
        body: Any,
    ): MockHttpServletResponse =
        mockMvc
            .request(POST, path) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
            }.andReturn()
            .response

    private fun pollUntil(
        agent: Agent,
        expected: Int,
    ): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        repeat(POLL_ROUNDS) {
            if (all.size >= expected) return all
            val response = agentGet("/api/v1/print-agents/me/jobs?wait=0", agent.token)
            response.status shouldBe 200
            all += json(response).values().toList()
        }
        return all
    }

    // hotel e logotipo pela API

    private fun uploadLogo(): UUID {
        val response =
            mockMvc
                .multipart("/api/v1/files") {
                    file(MockMultipartFile("file", "logo.png", "image/png", TestImages.png(LOGO_W, LOGO_H)))
                    part(MockPart("purpose", HotelFilePurposes.LOGO.value.toByteArray(Charsets.UTF_8)))
                    with(iam.asAdmin())
                }.andReturn()
                .response
        response.status shouldBe 201
        return UUID.fromString(json(response)["id"].asString())
    }

    private fun hotelBody(
        legalName: String,
        tradeName: String,
        logo: UUID?,
    ) = mapOf(
        "legalName" to legalName,
        "tradeName" to tradeName,
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
        "logoFileId" to logo,
    )

    private fun putHotel(
        legalName: String,
        tradeName: String,
        logo: UUID?,
    ) {
        val etag = iam.call(GET, "/api/v1/hotel", iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val response =
            iam.call(PUT, "/api/v1/hotel", iam.asAdmin(), hotelBody(legalName, tradeName, logo), ifMatch = etag)
        response.status shouldBe 200
    }

    /** Grava o hotel com o [logo] pela API, roda [block] e devolve o original no `finally` (banco compartilhado). */
    private fun <T> withHotel(
        logo: UUID,
        legalName: String,
        tradeName: String,
        block: () -> T,
    ): T {
        val original = iam.call(GET, "/api/v1/hotel", iam.asAdmin())
        val originalBody = json(original)
        val configured = originalBody.path("legalName").let { !it.isNull && !it.isMissingNode }
        putHotel(legalName, tradeName, logo)
        try {
            return block()
        } finally {
            val etag = iam.call(GET, "/api/v1/hotel", iam.asAdmin()).getHeader(HttpHeaders.ETAG)
            val body = if (configured) originalBody else hotelBody("Pousada Padrão Ltda", "Pousada Padrão", null)
            iam.call(PUT, "/api/v1/hotel", iam.asAdmin(), body, ifMatch = etag).status shouldBe 200
        }
    }

    // estadia em casa com CPF e RN-19

    @Suppress("LongMethod")
    private fun stay(): Stay {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX)
        val (type, rooms) = support.inventory.roomTypeWithRooms(1, today)
        val room = rooms.single()
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val civilName = "Civil Fulano $suffix"
        val cpf = newCpf()
        val adult =
            guest(
                mapOf(
                    "fullName" to civilName,
                    "socialName" to "Nome Social $suffix",
                    "birthDate" to LocalDate.of(1988, 3, 14),
                    "nationality" to "BR",
                    "address" to support.guests.address() + mapOf("street" to "Rua das Acácias"),
                ),
                cpf,
                sensitive = true,
            )
        val created =
            support.created(
                support.booking(adult, support.reservation(type, plan.id, today, today.plusDays(2), adults = 1)),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        with(iam) {
            call(POST, "/api/v1/reservations/$id/assign-room", asAdmin(), mapOf("roomId" to room)).ok()
        }
        roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
        roomsApi.markInspected(RoomId(room), RoomStatusSource.MANUAL)
        val body =
            mapOf(
                "guests" to
                    listOf(
                        mapOf(
                            "guestId" to adult,
                            "primary" to true,
                            "travelReason" to "LAZER",
                            "transportMode" to "AUTOMOVEL",
                            "minorAuthorization" to null,
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
        return Stay(
            reservation = id,
            folio = UUID.fromString(created["folioId"].asString()),
            code = created["code"].asString(),
            roomNumber = roomsApi.room(RoomId(room))!!.number,
            suffix = suffix,
            civilName = civilName,
            cpf = cpf,
        )
    }

    private fun guest(
        profileExtra: Map<String, Any?>,
        cpf: String,
        sensitive: Boolean,
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

    private companion object {
        const val SUFFIX = 12
        const val LAST4 = 4
        const val PRICED_DAYS = 10L
        const val LOGO_W = 120
        const val LOGO_H = 60
        const val POLL_ROUNDS = 5
        val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val GS_V_CUT = byteArrayOf(0x1D, 'V'.code.toByte(), 66, 0)
        val LISTENER_ANNOTATIONS =
            listOf(
                "org.springframework.context.event.EventListener",
                "org.springframework.transaction.event.TransactionalEventListener",
                "org.springframework.modulith.events.ApplicationModuleListener",
            )
        val DISABILITY =
            "DEFICIENCIA_E2E_" +
                UuidV7
                    .generate()
                    .toString()
                    .takeLast(8)
                    .uppercase()
    }
}

package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.shared.ContentCipher
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
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
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Ataque à confirmação de reserva (M5 E11, Task 13): crianças, o ESC/POS da térmica, texto hostil e longo nos dois
 * textos da política (RN-11), as políticas e as diárias copiadas na reserva (RN-15), day use e várias noites, cada
 * situação, titular mesclado e anonimizado, permissão, idempotência, impressora inativa e trilha só com ids.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class ReservationConfirmationAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val ibm850 = charset("IBM850")

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- crianças ---

    @Test
    fun `a reservation with children shows their ages in the PDF and in the ESC-POS`() {
        val booked = book()
        jdbc
            .sql("UPDATE reservations.reservation SET child_ages = :ages WHERE id = :id")
            .param("ages", listOf(4, 9).toTypedArray())
            .param("id", booked.id)
            .update()

        val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
        text shouldContain "Adultos 2"
        text shouldContain "Crianças 4, 9"

        val thermal = escPosText(printedJob(booked.id))
        thermal shouldContain "adultos 2, criancas 4, 9"
    }

    @Test
    fun `a reservation without children has no children line in either format`() {
        val booked = book()
        pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray) shouldNotContain "Crianças"
        escPosText(printedJob(booked.id)) shouldNotContain "criancas"
    }

    // --- ESC/POS ---

    @Test
    fun `the thermal print starts with the ESC-POS reset and has the code, dates, total, policies and texts`() {
        val booked = book()
        val bytes = escPosBytes(printedJob(booked.id))

        (bytes[0].toInt() and 0xFF) shouldBe 0x1B
        (bytes[1].toInt() and 0xFF) shouldBe 0x40
        val text = commandFree(bytes)
        text shouldContain "CONFIRMACAO DE RESERVA ${booked.code}"
        text shouldContain "Chegada ${today.format(dateFormat)} Saida ${today.plusDays(2).format(dateFormat)}"
        text shouldContain "2 noite(s), adultos 2"
        text shouldContain "${today.format(dateFormat)} R$ 400,00"
        text shouldContain "TOTAL: R$ 800,00"
        text shouldContain "Cancelamento ${booked.cancellationName}"
        text.replace('\n', ' ') shouldContain "48 horas antes da chegada"
        text shouldContain "Garantia"
        text shouldContain "Check-in a partir das"
        text shouldContain "Janela de arrumacao"
        text shouldContain "Early check-in"
        text shouldContain "Late check-out"
    }

    @Test
    fun `no ESC-POS line of the confirmation is wider than 48 columns, even with a long policy text`() {
        val booked = book()
        val longText = (1..300).joinToString(" ") { "palavra$it" } + " FIMTEXTO"
        withPolicy(mapOf("stayPolicyText" to longText, "additionalPolicyText" to longText)) {
            val text = commandFree(escPosBytes(printedJob(booked.id)))
            text shouldContain "FIMTEXTO"
            text.lines().maxOf { it.length } shouldBeLessThanOrEqualTo THERMAL_COLUMNS
        }
    }

    @Test
    fun `hostile policy text is literal in the PDF and gives no ESC-POS command nor bidi`() {
        val booked = book()
        val baselineBytes = escPosBytes(printedJob(booked.id))
        val hostile = "<script>alert(1)</script> \${7*191} [[\${1+1}]] th:text ESC\u001B@\u001DVB\u0007x ‮oculto⁦"
        withPolicy(mapOf("stayPolicyText" to hostile, "additionalPolicyText" to "<b>$hostile</b>")) {
            val response = pdf(booked.id, iam.asAdmin())
            response.status shouldBe 200
            val text = pdfText(response.contentAsByteArray)
            text shouldContain "<script>alert(1)</script>"
            text shouldContain "\${7*191}"
            text shouldContain "[[\${1+1}]]"
            text shouldNotContain "1337"
            text shouldNotContain "\u001B"
            text shouldNotContain "‮"
            text shouldNotContain "⁦"

            val bytes = escPosBytes(printedJob(booked.id))
            count(bytes, 0x1B) shouldBe count(baselineBytes, 0x1B)
            count(bytes, 0x1D) shouldBe count(baselineBytes, 0x1D)
            count(bytes, 0x07) shouldBe count(baselineBytes, 0x07)
            commandFree(bytes) shouldContain "<script>alert(1)</script>"
            commandFree(bytes) shouldNotContain "1337"
        }
    }

    @Test
    fun `hostile holder name and a 4000 character text keep the PDF literal and complete`() {
        val hostile = "Ana <b>X</b> \${7*191} [[\${1+1}]] ‮civil"
        val holder = guest(mapOf("fullName" to hostile))
        val booked = book(holder = holder.first)
        val body = (1..430).joinToString(" ") { "texto$it" } + " FIMDOTEXTO"
        withPolicy(mapOf("stayPolicyText" to body)) {
            val response = pdf(booked.id, iam.asAdmin())
            response.status shouldBe 200
            val text = pdfText(response.contentAsByteArray)
            text shouldContain "<b>X</b>"
            text shouldContain "\${7*191}"
            text shouldNotContain "1337"
            text shouldNotContain "‮"
            text shouldContain "FIMDOTEXTO"
            text shouldContain "Total R$ 800,00"
        }
    }

    // --- RN-11 ---

    @Test
    fun `an offered early check-in shows time and price and a missing late check-out says not offered`() {
        val booked = book()
        withPolicy(
            mapOf(
                "earlyCheckIn" to mapOf("limitTime" to "10:00", "fee" to "50.00"),
                "lateCheckOut" to null,
                "additionalPolicyText" to null,
            ),
        ) {
            val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
            text shouldContain "Early check-in a partir das 10:00, R$ 50,00"
            text shouldContain "Late check-out não oferecido"
            text shouldNotContain "null"
            val thermal = escPosText(printedJob(booked.id))
            thermal shouldContain "Early check-in a partir das 10:00: R$ 50,00"
            thermal shouldContain "Late check-out nao oferecido"
            thermal shouldNotContain "null"
        }
    }

    @Test
    fun `neither early nor late offered says so for both, in PDF and ESC-POS`() {
        val booked = book()
        withPolicy(mapOf("earlyCheckIn" to null, "lateCheckOut" to null)) {
            val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
            text shouldContain "Early check-in não oferecido"
            text shouldContain "Late check-out não oferecido"
            val thermal = escPosText(printedJob(booked.id))
            thermal shouldContain "Early check-in nao oferecido"
            thermal shouldContain "Late check-out nao oferecido"
        }
    }

    // --- RN-15 ---

    @Test
    fun `a guarantee policy edited after the booking does not change the PDF`() {
        val booked = book()
        val path = "/api/v1/guarantee-policies/${booked.guaranteePolicy}"
        val edited = "Editada ${booked.suffix}"
        with(iam) {
            call(
                PUT,
                path,
                asAdmin(),
                support.rates.guaranteeBody(name = edited, type = "CARD", depositPercent = null, dueDaysBefore = null),
                ifMatch = support.guests.etagOf(path),
            ).ok()
        }

        val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
        text shouldContain "depósito de 30%"
        text shouldNotContain edited
        text shouldNotContain "cartão de crédito"
        escPosText(printedJob(booked.id)) shouldContain "deposito de 30%"
    }

    @Test
    fun `edited nightly rates and a later price change show the stored rates and the right total`() {
        val booked =
            book(nightlyRates = listOf(today to "350.00", today.plusDays(1) to "360.00"))
        support.setPrices(booked.plan, listOf(booked.type), today, today.plusDays(PRICED_DAYS), twoAdults = "999.00")

        val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
        text shouldContain "${today.format(dateFormat)} R$ 350,00"
        text shouldContain "${today.plusDays(1).format(dateFormat)} R$ 360,00"
        text shouldContain "Total R$ 710,00"
        text shouldNotContain "999,00"
        escPosText(printedJob(booked.id)) shouldContain "TOTAL: R$ 710,00"
    }

    @Test
    fun `a day use shows its single date and the total of that date`() {
        val booked = book(nights = 0)
        val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
        text shouldContain "Noites 0"
        text shouldContain "${today.format(dateFormat)} R$ 400,00"
        text shouldContain "Total R$ 400,00"
        text shouldNotContain "null"
    }

    @Test
    fun `a five night stay lists every night and sums them`() {
        val booked = book(nights = 5)
        val text = pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray)
        text shouldContain "Noites 5"
        (0L..4L).forEach { text shouldContain "${today.plusDays(it).format(dateFormat)} R$ 400,00" }
        text shouldContain "Total R$ 2.000,00"
        escPosText(printedJob(booked.id)) shouldContain "TOTAL: R$ 2.000,00"
    }

    // --- situações ---

    @Test
    fun `provisional, confirmed, in house and checked out reservations get the confirmation with their label`() {
        val provisional = book(arrivalOffset = PROVISIONAL_OFFSET, guarantee = "DEPOSIT")
        support.status(provisional.id) shouldBe "PROVISIONAL"
        pdfText(pdf(provisional.id, iam.asAdmin()).contentAsByteArray) shouldContain "Situação Pré-reserva"

        val confirmed = book()
        force(confirmed.id, "CONFIRMED")
        pdfText(pdf(confirmed.id, iam.asAdmin()).contentAsByteArray) shouldContain "Situação Confirmada"

        val inHouse = book()
        support.forceInHouse(inHouse.id, inHouse.rooms.first())
        support.status(inHouse.id) shouldBe "IN_HOUSE"
        pdfText(pdf(inHouse.id, iam.asAdmin()).contentAsByteArray) shouldContain "Situação Hospedada"
        escPosText(printedJob(inHouse.id)) shouldContain "Situacao: Hospedada"

        val gone = book()
        support.forceCheckedOut(gone.id)
        pdfText(pdf(gone.id, iam.asAdmin()).contentAsByteArray) shouldContain "Situação Encerrada"
    }

    @Test
    fun `a no-show reservation is 409 on both routes and creates no job`() {
        val booked = book()
        force(booked.id, "NO_SHOW")
        val printer = newPrinter()
        val before = jobCount()

        billing.run {
            pdf(booked.id, admin).problem(409, "CONFIRMATION_UNAVAILABLE")
            print(booked.id, admin, printer, UuidV7.generate().toString()).problem(409, "CONFIRMATION_UNAVAILABLE")
        }
        jobCount() shouldBe before
    }

    @Test
    fun `a cancelled reservation creates no job and an unknown one is a problem json even asking for pdf`() {
        val booked = book()
        support.cancel(booked.id).status shouldBe 200
        val before = jobCount()
        billing.run {
            print(booked.id, admin, newPrinter(), UuidV7.generate().toString())
                .problem(409, "CONFIRMATION_UNAVAILABLE")
        }
        jobCount() shouldBe before

        val unknown =
            mockMvc
                .get("/api/v1/reservations/${UuidV7.generate()}/confirmation/pdf") {
                    with(iam.asAdmin())
                    accept = MediaType.APPLICATION_PDF
                }.andReturn()
                .response
        unknown.status shouldBe 404
        MediaType.parseMediaType(unknown.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe
            true
        unknown.contentAsString shouldContain "RESERVATION_NOT_FOUND"
    }

    // --- titular ---

    @Test
    fun `a holder without social name shows the full name`() {
        val holder = guest(mapOf("fullName" to "Civil Sem Social ${support.guests.suffix(8)}"))
        val booked = book(holder = holder.first)
        pdfText(pdf(booked.id, iam.asAdmin()).contentAsByteArray) shouldContain holder.second
    }

    @Test
    fun `a merged holder shows the survivor and nothing of the merged profile`() {
        val survivor = guest(mapOf("fullName" to "Sobrevivente ${support.guests.suffix(8)}"))
        val merged = guest(mapOf("fullName" to "Duplicado Mesclado ${support.guests.suffix(8)}"))
        val booked = book(holder = merged.first)
        with(iam) {
            call(
                POST,
                "/api/v1/guests/${survivor.first}/merge",
                asAdmin(),
                mapOf("mergedGuestId" to merged.first, "takeFromMerged" to emptyList<String>()),
                ifMatch = support.guests.etagOf("/api/v1/guests/${survivor.first}"),
            ).ok()
        }

        val response = pdf(booked.id, iam.asAdmin())
        response.status shouldBe 200
        val text = pdfText(response.contentAsByteArray)
        text shouldContain survivor.second
        text shouldNotContain "Duplicado Mesclado"
        escPosText(printedJob(booked.id)) shouldNotContain "Duplicado Mesclado"
    }

    @Test
    fun `an anonymized holder gives a confirmation without the erased name`() {
        val holder =
            guest(
                mapOf(
                    "fullName" to "Anonimizar Fulano ${support.guests.suffix(8)}",
                    "socialName" to "Social Anon ${support.guests.suffix(8)}",
                ),
            )
        val booked = book(holder = holder.first)
        support.forceCheckedOut(booked.id)
        with(iam) { call(POST, "/api/v1/guests/${holder.first}/anonymize", asAdmin()).expectStatus(200) }

        val response = pdf(booked.id, iam.asAdmin())
        response.status shouldBe 200
        val text = pdfText(response.contentAsByteArray)
        text shouldNotContain "Anonimizar Fulano"
        text shouldNotContain "Social Anon"
        text shouldNotContain "null"
        val thermal = escPosText(printedJob(booked.id))
        thermal shouldNotContain "Anonimizar Fulano"
        thermal shouldNotContain "null"
    }

    // --- permissão, idempotência, impressora e trilha ---

    @Test
    fun `reservations reservations read alone opens the PDF and the print, another permission opens none`() {
        val booked = book()
        val reader =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReservationsPermissions.permissions.single {
                            it.code ==
                                ReservationsPermissions.RESERVATIONS_READ
                        },
                        ALL,
                    ),
                ),
            )
        val other =
            iam.asTestUser(
                iam.createUserWith(
                    TestGrant(
                        ReservationsPermissions.permissions.first {
                            it.code != ReservationsPermissions.RESERVATIONS_READ
                        },
                        ALL,
                    ),
                ),
            )
        val printer = newPrinter()

        pdf(booked.id, reader).status shouldBe 200
        billing.run { print(booked.id, reader, printer, UuidV7.generate().toString()).expect(202) }

        val before = jobCount()
        billing.run {
            pdf(booked.id, other).problem(403, "PERMISSION_DENIED")
            print(booked.id, other, printer, UuidV7.generate().toString()).problem(403, "PERMISSION_DENIED")
        }
        jobCount() shouldBe before
    }

    @Test
    fun `printing without an idempotency key is 400, on an inactive printer 422, and neither creates a job`() {
        val booked = book()
        val printer = newPrinter()
        val before = jobCount()

        val noKey =
            mockMvc
                .post("/api/v1/reservations/${booked.id}/confirmation/print") {
                    with(iam.asAdmin())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"printerId":"$printer"}"""
                }.andReturn()
                .response
        noKey.status shouldBe 400

        asAdmin { agents.deactivatePrinter(printer) }
        billing.run {
            print(booked.id, admin, printer, UuidV7.generate().toString()).problem(422, "PRINTER_INACTIVE")
        }
        jobCount() shouldBe before
    }

    @Test
    fun `the same key and printer repeat the job and the trail holds only ids, for ESC-POS too`() {
        val booked = book()
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val key = UuidV7.generate().toString()

        val first = billing.run { print(booked.id, admin, printer, key).expect(202) }["printJobId"].asString()
        val second = billing.run { print(booked.id, admin, printer, key).expect(202) }["printJobId"].asString()
        second shouldBe first
        jdbc
            .sql("SELECT count(*) FROM printing.print_job WHERE id = CAST(:job AS uuid)")
            .param("job", first)
            .query(Long::class.java)
            .single() shouldBe 1
        (escPosBytes(UUID.fromString(first))[0].toInt() and 0xFF) shouldBe 0x1B
        val trail =
            jdbc
                .sql(
                    "SELECT a::text FROM audit.audit_entry a WHERE entity_id = CAST(:job AS text) " +
                        "AND action = 'reservations.confirmation.print'",
                ).param("job", first)
                .query(String::class.java)
                .list()
        trail.size shouldBe 1
        trail.single() shouldContain booked.id.toString()
        trail.single() shouldNotContain booked.code
        trail.single() shouldNotContain "R$"
        trail.single() shouldNotContain "Titular"
    }

    // --- apoio ---

    private class Booked(
        val id: UUID,
        val code: String,
        val suffix: String,
        val type: UUID,
        val plan: UUID,
        val rooms: List<UUID>,
        val cancellationName: String,
        val guaranteePolicy: UUID,
    )

    /** Cria um perfil e devolve o id e o nome que a confirmação mostra (o social, se houver, ou o civil). */
    private fun guest(profile: Map<String, Any?>): Pair<UUID, String> {
        val created =
            support.guests.createGuest(
                support.guests.createBody(
                    profile = support.guests.profileBody(extra = profile),
                    documents = emptyList(),
                ),
            )
        val shown = (profile["socialName"] ?: profile["fullName"]) as String
        return UUID.fromString(created["id"].asString()) to shown
    }

    private fun book(
        nights: Int = 2,
        guarantee: String = "DEPOSIT",
        arrivalOffset: Long = 0,
        nightlyRates: List<Pair<LocalDate, String>> = emptyList(),
        holder: UUID? = null,
    ): Booked {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
        val (type, rooms) = support.inventory.roomTypeWithRooms(1, today)
        val cancellationName = "Flex $suffix"
        val plan =
            support.plan(
                listOf(type),
                guarantee = guarantee,
                cancellationBody = support.rates.cancellationBody(name = cancellationName),
            )
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS * 2))
        val arrival = today.plusDays(arrivalOffset)
        val created =
            support.created(
                support.booking(
                    holder ?: support.guest(),
                    support.reservation(
                        type,
                        plan.id,
                        arrival,
                        arrival.plusDays(nights.toLong()),
                        adults = 2,
                        nightlyRates = nightlyRates,
                    ),
                ),
            )["reservations"][0]
        return Booked(
            UUID.fromString(created["id"].asString()),
            created["code"].asString(),
            suffix,
            type,
            plan.id,
            rooms,
            cancellationName,
            plan.guaranteePolicy,
        )
    }

    private fun force(
        id: UUID,
        status: String,
    ) = jdbc
        .sql("UPDATE reservations.reservation SET status = :status, provisional_until = NULL WHERE id = :id")
        .param("status", status)
        .param("id", id)
        .update()

    /** Aplica os campos à política do hotel, roda o bloco e devolve a política original, aconteça o que acontecer. */
    private fun withPolicy(
        changes: Map<String, Any?>,
        block: () -> Unit,
    ) {
        val path = "/api/v1/hotel/policies"
        val original = jsonMapper.convertValue(with(iam) { call(GET, path, asAdmin()).ok() }, Map::class.java)
        putPolicies(path, original + changes)
        try {
            block()
        } finally {
            putPolicies(path, original)
        }
    }

    private fun putPolicies(
        path: String,
        body: Map<*, *>,
    ) {
        with(iam) {
            val etag = call(GET, path, asAdmin()).getHeader(HttpHeaders.ETAG)
            call(PUT, path, asAdmin(), body, ifMatch = etag).ok()
        }
    }

    private fun pdf(
        reservation: UUID,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/reservations/$reservation/confirmation/pdf") { with(auth) }
            .andReturn()
            .response

    private fun print(
        reservation: UUID,
        auth: RequestPostProcessor,
        printerId: UUID,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/reservations/$reservation/confirmation/print") {
                with(auth)
                header(IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"printerId":"$printerId"}"""
            }.andReturn()
            .response

    private fun printedJob(reservation: UUID): UUID {
        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val accepted = print(reservation, iam.asAdmin(), printer, UuidV7.generate().toString())
        return UUID.fromString(billing.run { accepted.expect(202) }["printJobId"].asString())
    }

    private fun jobCount(): Long = jdbc.sql("SELECT count(*) FROM printing.print_job").query(Long::class.java).single()

    private fun <T> asAdmin(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(iam.adminId().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun newPrinter(kind: PrinterKind = PrinterKind.A4_PDF): UUID =
        asAdmin {
            val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
            val agent = agents.createAgent("Agente $suffix").view.id
            agents.createPrinter(NewPrinter("Imp $suffix", kind, "addr-$suffix", agent)).id
        }

    private fun escPosBytes(jobId: UUID): ByteArray {
        val stored =
            jdbc
                .sql("SELECT content FROM printing.print_job WHERE id = :id")
                .param("id", jobId)
                .query { rs, _ -> rs.getBytes(1) }
                .single()
        return cipher.decrypt(stored, PrintJobsService.associatedData(jobId))
    }

    private fun escPosText(jobId: UUID): String = commandFree(escPosBytes(jobId))

    /** O texto da impressão sem os comandos ESC/POS (`ESC @`, `ESC t n`, `ESC a n`, `GS V B`) e sem controles. */
    private fun commandFree(bytes: ByteArray): String =
        String(bytes, ibm850).replace(Regex("\u001B@|\u001B[ta]|\u001DVB"), "").filter { it == '\n' || it >= ' ' }

    private fun count(
        bytes: ByteArray,
        value: Int,
    ): Int = bytes.count { it.toInt() == value }

    private companion object {
        const val SUFFIX_LENGTH = 12
        const val PRICED_DAYS = 10L
        const val PROVISIONAL_OFFSET = 10L
        const val THERMAL_COLUMNS = 48
    }
}

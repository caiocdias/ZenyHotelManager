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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
 * Ataque à ficha de registro (M5 E11, Task 11): documentos de todos os tipos, número inteiro e sufixos além do
 * mascarado, perfil mesclado e anonimizado, campos ausentes, situações, texto hostil, permissão, idempotência,
 * impressora inativa, log e trilha.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class RegistrationCardAttackTest(
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

    // --- RN-19 e documento inteiro ---

    @Test
    fun `passport, RNM, foreign id and several documents never print the whole number, in PDF, ESC-POS, log and trail`(
        output: CapturedOutput,
    ) {
        val tag = support.guests.suffix(10)
        val passport = Doc("PASSPORT", "US", "zq-7${tag.substring(0, 5)}.${tag.substring(5)}", "ZQ7${tag.uppercase()}")
        val rnm = Doc("RNM", null, "r${(10000 + (tag.hashCode().toLong() and 0xFFFF) % 80000)}", null)
        val foreign = Doc("FOREIGN_ID", "AR", "dni 9${tag.substring(2)}/x", "DNI9${tag.substring(2).uppercase()}X")
        val cpf = newCpf()
        val rg = Doc("RG", null, "mg-${support.guests.suffix(8)}", null)
        val stay =
            stay(
                Spec(
                    profile = mapOf("nationality" to "US", "fullName" to "Hospede Passaporte $tag"),
                    documents = listOf(passport, rnm, Doc("CPF", null, cpf, cpf)),
                    sensitive = true,
                ),
                Spec(minor = true, documents = listOf(rg)),
            )
        val foreignStay =
            stay(
                Spec(
                    profile = mapOf("nationality" to "AR", "fullName" to "Hospede Estrangeiro $tag"),
                    documents = listOf(foreign),
                ),
            )
        val all = listOf(passport, rnm, foreign, rg)
        val forbidden = forbidden(all) + cpfForms(cpf) + stay.rn19

        val text = pdfText(pdf(stay.reservation, iam.asAdmin()).contentAsByteArray)
        forbidden.forEach { text shouldNotContain it }
        text shouldContain "***${passport.normalized().takeLast(4)}"
        text shouldContain "***${rnm.normalized().takeLast(2)}"
        text shouldContain "***${rg.normalized().takeLast(4)}"
        text shouldContain "***${cpf.takeLast(4)}"
        val foreignText = pdfText(pdf(foreignStay.reservation, iam.asAdmin()).contentAsByteArray)
        forbidden.forEach { foreignText shouldNotContain it }
        foreignText shouldContain "***${foreign.normalized().takeLast(4)}"
        val foreignThermal = escPosText(printedJob(foreignStay.reservation))
        forbidden.forEach { foreignThermal shouldNotContain it }

        val printer = newPrinter(PrinterKind.THERMAL_ESCPOS)
        val accepted = print(stay.reservation, iam.asAdmin(), printer, UuidV7.generate().toString())
        val job = UUID.fromString(billing.run { accepted.expect(202) }["printJobId"].asString())
        val thermal = escPosText(job)
        forbidden.forEach { thermal shouldNotContain it }
        thermal shouldContain "***${passport.normalized().takeLast(4)}"

        val trail = auditText(job)
        forbidden.forEach { trail shouldNotContain it }
        val log = output.all
        forbidden.forEach { log shouldNotContain it }
    }

    @Test
    fun `error responses do not carry the document or the name`() {
        val rawNumber = "ZX9${support.guests.suffix(9).uppercase()}"
        val us = mapOf("nationality" to "US")
        val stay = stay(Spec(profile = us, documents = listOf(Doc("PASSPORT", "US", rawNumber, rawNumber))))
        val cancelledStay =
            stay(
                Spec(profile = us, documents = listOf(Doc("PASSPORT", "US", rawNumber + "9", rawNumber + "9"))),
                checkIn = false,
            )
        support.cancel(cancelledStay.reservation).status shouldBe 200

        val response = pdf(cancelledStay.reservation, iam.asAdmin())
        response.status shouldBe 409
        val body = response.getContentAsString(Charsets.UTF_8)
        body shouldContain "REGISTRATION_CARD_UNAVAILABLE"
        body shouldNotContain rawNumber
        body shouldNotContain stay.names.first()
        body shouldNotContain cancelledStay.names.first()
        pdf(stay.reservation, iam.asAdmin()).status shouldBe 200
    }

    // --- nome social, mesclagem, anonimização, campos ausentes ---

    @Test
    fun `a guest with only a civil name shows the civil name and a social name hides it in PDF and ESC-POS`() {
        val plain = stay(Spec(profile = mapOf("socialName" to null)))
        val social = stay(Spec(profile = mapOf("socialName" to "Social Sem Civil ${support.guests.suffix(6)}")))
        val plainText = pdfText(pdf(plain.reservation, iam.asAdmin()).contentAsByteArray)
        plainText shouldContain plain.names.first()

        val socialText = pdfText(pdf(social.reservation, iam.asAdmin()).contentAsByteArray)
        socialText shouldContain "Social Sem Civil"
        socialText shouldNotContain social.names.first()
        val job = printedJob(social.reservation)
        escPosText(job) shouldNotContain social.names.first()
    }

    @Test
    fun `a merged guest shows the survivor and none of the merged profile data`() {
        val survivorCpf = newCpf()
        val mergedPassport = "pp${support.guests.suffix(8)}7"
        val survivor =
            guest(Spec(profile = mapOf("fullName" to "Sobrevivente ${support.guests.suffix(8)}"), cpf = survivorCpf))
        val merged =
            guest(
                Spec(
                    profile =
                        mapOf(
                            "fullName" to "Duplicado Mesclado ${support.guests.suffix(8)}",
                            "nationality" to "PT",
                        ),
                    documents = listOf(Doc("PASSPORT", "PT", mergedPassport, null)),
                    noCpf = true,
                ),
            )
        val stay = stayWithHolder(merged, checkIn = true)
        with(iam) {
            call(
                POST,
                "/api/v1/guests/${survivor.id}/merge",
                asAdmin(),
                mapOf("mergedGuestId" to merged.id, "takeFromMerged" to emptyList<String>()),
                ifMatch = support.guests.etagOf("/api/v1/guests/${survivor.id}"),
            ).ok()
        }

        val response = pdf(stay.reservation, iam.asAdmin())
        response.status shouldBe 200
        val text = pdfText(response.contentAsByteArray)
        text shouldContain "Sobrevivente"
        text shouldNotContain "Duplicado Mesclado"
        cpfForms(survivorCpf).plus(listOf(mergedPassport, mergedPassport.uppercase())).forEach {
            text shouldNotContain
                it
        }
    }

    @Test
    fun `an anonymized guest on the reservation gives a card without the erased personal data`() {
        val cpf = newCpf()
        val guest =
            guest(
                Spec(
                    profile =
                        mapOf(
                            "fullName" to "Anonimizar Fulano ${support.guests.suffix(8)}",
                            "socialName" to "Social Anon ${support.guests.suffix(8)}",
                        ),
                    cpf = cpf,
                ),
            )
        val stay = stayWithHolder(guest, checkIn = true)
        forceCheckedOut(stay.reservation)
        with(iam) { call(POST, "/api/v1/guests/${guest.id}/anonymize", asAdmin()).expectStatus(200) }

        val response = pdf(stay.reservation, iam.asAdmin())
        response.status shouldBe 200
        val text = pdfText(response.contentAsByteArray)
        text shouldNotContain "Anonimizar Fulano"
        text shouldNotContain "Social Anon"
        text shouldNotContain "Rua das Acácias"
        text shouldNotContain "14/03"
        cpfForms(cpf).forEach { text shouldNotContain it }
        text shouldNotContain "null"
    }

    @Test
    fun `a guest without nationality, birth date, address and documents still gets a clean card`() {
        val guest =
            guest(
                Spec(
                    profile = emptyMap(),
                ),
            )
        val stay = stayWithHolder(guest, checkIn = true)
        jdbc.sql("DELETE FROM guests.guest_document WHERE guest_id = :id").param("id", guest.id).update()
        jdbc
            .sql(
                "UPDATE guests.guest SET nationality = NULL, birth_date = NULL, street = NULL, number = NULL, " +
                    "district = NULL, city = NULL, state = NULL, postal_code = NULL, ibge_city_code = NULL, " +
                    "complement = NULL WHERE id = :id",
            ).param("id", guest.id)
            .update()

        val response = pdf(stay.reservation, iam.asAdmin())
        response.status shouldBe 200
        val text = pdfText(response.contentAsByteArray)
        text shouldContain stay.code
        text shouldNotContain "null"
        text shouldNotContain "Endereço"
        val thermal = escPosText(printedJob(stay.reservation))
        thermal shouldContain stay.code
        thermal shouldNotContain "null"
    }

    // --- situações ---

    @Test
    fun `in house, checked out pass and no show is refused and a refusal creates no job`() {
        val inHouse = stay(Spec())
        pdf(inHouse.reservation, iam.asAdmin()).status shouldBe 200

        val gone = stay(Spec())
        forceCheckedOut(gone.reservation)
        pdf(gone.reservation, iam.asAdmin()).status shouldBe 200

        val noShow = stay(Spec(), checkIn = false)
        releaseAssignment(noShow.reservation)
        jdbc
            .sql("UPDATE reservations.reservation SET status = 'NO_SHOW' WHERE id = :id")
            .param("id", noShow.reservation)
            .update()
        val before = jobCount()
        billing.run {
            pdf(noShow.reservation, admin).problem(409, "REGISTRATION_CARD_UNAVAILABLE")
            print(noShow.reservation, admin, newPrinter(), UuidV7.generate().toString())
                .problem(409, "REGISTRATION_CARD_UNAVAILABLE")
        }
        jobCount() shouldBe before
    }

    @Test
    fun `an unknown reservation is a problem json even when the client accepts only PDF`() {
        val response =
            mockMvc
                .get("/api/v1/reservations/${UuidV7.generate()}/registration-card/pdf") {
                    with(iam.asAdmin())
                    accept = MediaType.APPLICATION_PDF
                }.andReturn()
                .response
        response.status shouldBe 404
        response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
        response.getContentAsString(Charsets.UTF_8) shouldContain "RESERVATION_NOT_FOUND"
    }

    @Test
    fun `a forbidden user gets a problem json on the PDF with Accept PDF and no print job is created`() {
        val stay = stay(Spec())
        val stranger = iam.asTestUser(iam.createUser())
        val before = jobCount()
        val response =
            mockMvc
                .get("/api/v1/reservations/${stay.reservation}/registration-card/pdf") {
                    with(stranger)
                    accept = MediaType.APPLICATION_PDF
                }.andReturn()
                .response
        response.status shouldBe 403
        response.contentType.shouldNotBeNull() shouldContain "problem+json"
        response.getContentAsString(Charsets.UTF_8) shouldNotContain stay.names.first()

        print(stay.reservation, stranger, newPrinter(), UuidV7.generate().toString()).status shouldBe 403
        jobCount() shouldBe before
    }

    @Test
    fun `a permission other than frontdesk stays checkin does not open the card`() {
        val stay = stay(Spec())
        val other = FrontdeskPermissions.permissions.first { it.code != FrontdeskPermissions.STAYS_CHECK_IN }
        val user = iam.asTestUser(iam.createUserWith(TestGrant(other, ALL)))
        pdf(stay.reservation, user).status shouldBe 403
    }

    @Test
    fun `a checkin permission scoped only to itself is enough and cannot print on an inactive printer`() {
        val stay = stay(Spec())
        val definition = FrontdeskPermissions.permissions.single { it.code == FrontdeskPermissions.STAYS_CHECK_IN }
        val clerk = iam.asTestUser(iam.createUserWith(TestGrant(definition, ALL)))
        val printer = newPrinter()
        asAdmin { agents.deactivatePrinter(printer) }
        val before = jobCount()
        billing.run {
            print(
                stay.reservation,
                clerk,
                printer,
                UuidV7.generate().toString(),
            ).problem(422, "PRINTER_INACTIVE")
        }
        jobCount() shouldBe before
    }

    @Test
    fun `printing without an idempotency key is 400 and creates no job`() {
        val stay = stay(Spec())
        val printer = newPrinter()
        val before = jobCount()
        val response =
            mockMvc
                .post("/api/v1/reservations/${stay.reservation}/registration-card/print") {
                    with(iam.asAdmin())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"printerId":"$printer"}"""
                }.andReturn()
                .response
        response.status shouldBe 400
        jobCount() shouldBe before
    }

    @Test
    fun `a replayed print with the same key creates one job only`() {
        val stay = stay(Spec())
        val printer = newPrinter()
        val key = UuidV7.generate().toString()
        val before = jobCount()
        repeat(3) { print(stay.reservation, iam.asAdmin(), printer, key).status shouldBe 202 }
        jobCount() shouldBe before + 1
    }

    // --- texto hostil ---

    @Test
    fun `hostile text in name and address is literal in the PDF and issues no ESC-POS command`() {
        val hostile = "Ana <b>X</b> \${7*7} [[\${1+1}]] ${'$'}{T(java.lang.Runtime)} th:text"
        val baseline = stay(Spec(profile = mapOf("fullName" to "Ana Normal ${support.guests.suffix(6)}")))
        val baselineBytes = escPosBytes(printedJob(baseline.reservation, PrinterKind.THERMAL_ESCPOS))

        val stay =
            stay(
                Spec(
                    profile =
                        mapOf(
                            "fullName" to hostile,
                            "address" to
                                support.guests.address() +
                                mapOf(
                                    "street" to "Rua <i>Z</i> [[\${2+2}]]",
                                    "complement" to "ESC\u001B@\u001DVB\u0007x",
                                ),
                        ),
                ),
            )
        val response = pdf(stay.reservation, iam.asAdmin())
        response.status shouldBe 200
        val text = pdfText(response.contentAsByteArray)
        text shouldContain "<b>X</b>"
        text shouldContain "[[\${1+1}]]"
        text shouldNotContain "49"
        text shouldNotContain "\u001B"

        val bytes = escPosBytes(printedJob(stay.reservation, PrinterKind.THERMAL_ESCPOS))
        count(bytes, 0x1B) shouldBe count(baselineBytes, 0x1B)
        count(bytes, 0x1D) shouldBe count(baselineBytes, 0x1D)
        count(bytes, 0x07) shouldBe count(baselineBytes, 0x07)
    }

    // --- apoio ---

    private class Doc(
        val type: String,
        val country: String?,
        val raw: String,
        private val normalizedValue: String?,
    ) {
        fun normalized(): String = normalizedValue ?: raw.uppercase().filter { it.isLetterOrDigit() }
    }

    private class Spec(
        val profile: Map<String, Any?> = emptyMap(),
        val documents: List<Doc> = emptyList(),
        val cpf: String? = null,
        val sensitive: Boolean = false,
        val minor: Boolean = false,
        val noDocuments: Boolean = false,
        val noCpf: Boolean = false,
        val checkIn: Boolean = true,
    )

    private class Guest(
        val id: UUID,
        val name: String,
    )

    private class Stay(
        val reservation: UUID,
        val code: String,
        val names: List<String>,
        val rn19: List<String>,
    )

    private fun forbidden(docs: List<Doc>): List<String> =
        docs
            .flatMap { doc ->
                val n = doc.normalized()
                listOf(
                    doc.raw,
                    doc.raw.uppercase(),
                    doc.raw.lowercase(),
                    n,
                    n.lowercase(),
                    n.dropLast(4),
                    n.lowercase().dropLast(4),
                ) +
                    listOf(
                        n.chunked(3).joinToString("."),
                        n.chunked(3).joinToString("-"),
                        n.chunked(4).joinToString(" "),
                    )
            }.filter { it.length >= 4 }

    private fun cpfForms(cpf: String): List<String> =
        listOf(
            cpf,
            "${cpf.substring(0, 3)}.${cpf.substring(3, 6)}.${cpf.substring(6, 9)}-${cpf.substring(9)}",
            cpf.dropLast(4),
            cpf.substring(0, 9),
        )

    private fun guest(spec: Spec): Guest {
        val g = support.guests
        val extra =
            mapOf(
                "birthDate" to (if (spec.minor) today.minusYears(9) else LocalDate.of(1988, 3, 14)),
                "nationality" to "BR",
            ) +
                spec.profile
        val profile = g.profileBody(extra = extra)
        val docs =
            when {
                spec.noDocuments -> {
                    emptyList()
                }

                (spec.noCpf || (spec.documents.isNotEmpty() && spec.cpf == null)) -> {
                    spec.documents.map { g.document(it.type, it.raw, it.country) }
                }

                else -> {
                    listOf(g.document("CPF", spec.cpf ?: newCpf())) +
                        spec.documents.map { g.document(it.type, it.raw, it.country) }
                }
            }
        val body =
            g.createBody(
                profile,
                docs,
                if (spec.sensitive) {
                    mapOf(
                        "raceColor" to "BLACK",
                        "disability" to DISABILITY,
                    )
                } else {
                    null
                },
            )
        val created = g.createGuest(body)
        return Guest(UUID.fromString(created["id"].asString()), profile["fullName"] as String)
    }

    private fun stay(
        vararg specs: Spec,
        checkIn: Boolean = true,
    ): Stay {
        val guests = specs.map { guest(it) }
        return build(guests, specs.toList(), checkIn && specs.all { it.checkIn })
    }

    private fun stayWithHolder(
        holder: Guest,
        checkIn: Boolean,
    ): Stay = build(listOf(holder), listOf(Spec()), checkIn)

    @Suppress("LongMethod")
    private fun build(
        guests: List<Guest>,
        specs: List<Spec>,
        checkIn: Boolean,
    ): Stay {
        val (type, rooms) = support.inventory.roomTypeWithRooms(1, today)
        val room = rooms.single()
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val created =
            support.created(
                support.booking(
                    guests.first().id,
                    support.reservation(
                        type,
                        plan.id,
                        today,
                        today.plusDays(2),
                        adults = specs.count { !it.minor }.coerceAtLeast(2),
                    ),
                ),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        with(iam) { call(POST, "/api/v1/reservations/$id/assign-room", asAdmin(), mapOf("roomId" to room)).ok() }
        if (checkIn) {
            roomsApi.markClean(RoomId(room), false, RoomStatusSource.MANUAL)
            roomsApi.markInspected(RoomId(room), RoomStatusSource.MANUAL)
            val adultId = guests.first().id
            val body =
                mapOf(
                    "guests" to
                        guests.mapIndexed { index, guest ->
                            if (specs[index].minor) {
                                val proof = upload().also { attach(guest.id, it) }
                                checkInGuest(
                                    guest.id,
                                    minorAuthorization =
                                        mapOf(
                                            "proofType" to "WRITTEN_AUTHORIZATION",
                                            "attachmentId" to proof,
                                            "responsibleGuestId" to adultId,
                                            "relationship" to "PAI",
                                        ),
                                )
                            } else {
                                checkInGuest(guest.id, primary = index == 0)
                            }
                        },
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
        return Stay(
            id,
            created["code"].asString(),
            guests.map { it.name },
            listOf("BLACK", "Preta", "Preto", DISABILITY),
        )
    }

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
        with(
            iam,
        ) { call(POST, "/api/v1/guests/$guest/attachments", asAdmin(), mapOf("fileId" to file)).expectStatus(201) }
    }

    private fun pdf(
        reservation: UUID,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/reservations/$reservation/registration-card/pdf") { with(auth) }.andReturn().response

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

    private fun printedJob(
        reservation: UUID,
        kind: PrinterKind = PrinterKind.THERMAL_ESCPOS,
    ): UUID {
        val accepted = print(reservation, iam.asAdmin(), newPrinter(kind), UuidV7.generate().toString())
        return UUID.fromString(billing.run { accepted.expect(202) }["printJobId"].asString())
    }

    private fun releaseAssignment(reservation: UUID) {
        jdbc
            .sql(
                "UPDATE reservations.room_assignment SET released_at = now(), release_reason = 'CHECKED_OUT' " +
                    "WHERE reservation_id = :id AND released_at IS NULL",
            ).param("id", reservation)
            .update()
    }

    private fun forceCheckedOut(reservation: UUID) {
        jdbc
            .sql(
                "WITH released AS (UPDATE reservations.room_assignment SET released_at = now(), " +
                    "release_reason = 'CHECKED_OUT' WHERE reservation_id = :id AND released_at IS NULL) " +
                    "UPDATE reservations.reservation SET status = 'CHECKED_OUT', checked_out_at = now(), " +
                    "checked_out_on = checked_in_on WHERE id = :id",
            ).param("id", reservation)
            .update()
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

    private fun escPosText(jobId: UUID): String =
        String(escPosBytes(jobId), ibm850).replace(Regex("\u001B@|\u001B[ta]|\u001DVB"), "").filter {
            it == '\n' ||
                it >= ' '
        }

    private fun count(
        bytes: ByteArray,
        value: Int,
    ): Int = bytes.count { it.toInt() == value }

    private fun auditText(job: UUID): String =
        jdbc
            .sql("SELECT a::text FROM audit.audit_entry a WHERE entity_id = CAST(:job AS text)")
            .param("job", job)
            .query(String::class.java)
            .list()
            .joinToString("\n")

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

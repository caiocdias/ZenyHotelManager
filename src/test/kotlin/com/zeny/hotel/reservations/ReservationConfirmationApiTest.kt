package com.zeny.hotel.reservations

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
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
import org.springframework.http.HttpMethod.GET
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
 * A confirmação de reserva em PDF e na impressão (M5 E11, Task 13; D-E11-13 e D-E11-14): o conteúdo (código, titular,
 * tipo de UH, plano, datas, noites, diária e total), as políticas copiadas na reserva (RN-15) que a edição posterior
 * não muda, os horários e os textos da política do hotel (RN-11), as situações sem confirmação, a permissão, o `202`
 * idempotente e a trilha só com ids.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions")
class ReservationConfirmationApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val billing = support.billing
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `the PDF has the stay, the holder social name, the nights, the rate and the total`() {
        val scenario = scenario()
        val response = pdf(scenario.reservation, iam.asAdmin())

        response.status shouldBe 200
        response.contentType shouldBe "application/pdf"
        response.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldNotBeNull() shouldContain
            "confirmacao-${scenario.code}.pdf"
        response.getHeader(HttpHeaders.CACHE_CONTROL).shouldNotBeNull() shouldContain "no-store"
        String(response.contentAsByteArray, Charsets.ISO_8859_1) shouldStartWith "%PDF-"

        val text = pdfText(response.contentAsByteArray)
        text shouldContain "Confirmação de reserva ${scenario.code}"
        text shouldContain "Titular Nome Social ${scenario.suffix}"
        text shouldNotContain scenario.civilName
        text shouldContain "Tipo de UH ${scenario.roomTypeName}"
        text shouldContain "Plano ${scenario.planName}"
        text shouldContain "Chegada ${today.format(dateFormat)}"
        text shouldContain "Saída ${today.plusDays(2).format(dateFormat)}"
        text shouldContain "Noites 2"
        text shouldContain "Adultos 2"
        text shouldNotContain "Crianças"
        text shouldContain "${today.format(dateFormat)} R$ 400,00"
        text shouldContain "Total R$ 800,00"
    }

    @Test
    fun `the PDF has the copied cancellation and guarantee terms`() {
        val scenario = scenario()
        val text = pdfText(pdf(scenario.reservation, iam.asAdmin()).contentAsByteArray)

        text shouldContain "Cancelamento ${scenario.cancellationName}"
        text shouldContain "48 horas antes da chegada"
        text shouldContain "multa de 1 diária"
        text shouldContain "Garantia"
        text shouldContain "depósito de 30%"
    }

    @Test
    fun `a cancellation policy edited after the booking does not change the PDF (RN-15)`() {
        val scenario = scenario()
        val path = "/api/v1/cancellation-policies/${scenario.cancellationPolicy}"
        val edited = "Editada ${scenario.suffix}"
        with(iam) {
            call(
                PUT,
                path,
                asAdmin(),
                support.rates.cancellationBody(name = edited, deadlineHours = 6, penaltyNights = 3),
                ifMatch = support.guests.etagOf(path),
            ).ok()
        }

        val text = pdfText(pdf(scenario.reservation, iam.asAdmin()).contentAsByteArray)

        text shouldContain "Cancelamento ${scenario.cancellationName}"
        text shouldContain "48 horas antes da chegada"
        text shouldNotContain edited
        text shouldNotContain "6 horas antes"
    }

    @Test
    fun `the PDF has the hotel times, the cleaning window, the early and late prices and both policy texts`() {
        val scenario = scenario()
        val path = "/api/v1/hotel/policies"
        val original = with(iam) { call(GET, path, asAdmin()).ok() }
        val originalBody = jsonMapper.convertValue(original, Map::class.java)
        val stay = "Texto da diária ${scenario.suffix}"
        val additional = "Texto adicional ${scenario.suffix}"
        putPolicies(
            path,
            originalBody +
                mapOf(
                    "checkInTime" to "14:00",
                    "checkOutTime" to "12:00",
                    "cleaningWindowMinutes" to 90,
                    "earlyCheckIn" to null,
                    "lateCheckOut" to mapOf("limitTime" to "16:00", "fee" to "100.00"),
                    "stayPolicyText" to stay,
                    "additionalPolicyText" to additional,
                ),
        )
        try {
            val text = pdfText(pdf(scenario.reservation, iam.asAdmin()).contentAsByteArray)

            text shouldContain "Check-in a partir das 14:00"
            text shouldContain "Check-out até as 12:00"
            text shouldContain "Janela de arrumação 90 minutos"
            text shouldContain "Early check-in não oferecido"
            text shouldContain "Late check-out até as 16:00, R$ 100,00"
            text shouldContain stay
            text shouldContain additional
        } finally {
            putPolicies(path, originalBody)
        }
    }

    @Test
    fun `a cancelled reservation has no confirmation and an unknown one is 404`() {
        val scenario = scenario()
        support.cancel(scenario.reservation).status shouldBe 200

        billing.run {
            pdf(scenario.reservation, admin).problem(409, "CONFIRMATION_UNAVAILABLE")
            print(scenario.reservation, admin, newPrinter(), UuidV7.generate().toString())
                .problem(409, "CONFIRMATION_UNAVAILABLE")
            pdf(UuidV7.generate(), admin).problem(404, "RESERVATION_NOT_FOUND")
        }
    }

    @Test
    fun `without reservations reservations read both routes are 403`() {
        val scenario = scenario()
        val stranger = iam.asTestUser(iam.createUser())

        billing.run {
            pdf(scenario.reservation, stranger).problem(403, "PERMISSION_DENIED")
            print(scenario.reservation, stranger, newPrinter(), UuidV7.generate().toString())
                .problem(403, "PERMISSION_DENIED")
        }
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
                        "WHERE action = 'reservations.confirmation.print' AND entity_id = CAST(:job AS text)",
                ).param("job", job)
                .query(String::class.java)
                .list()
        trail.size shouldBe 1
        trail.single() shouldContain scenario.reservation.toString()
        trail.single() shouldContain printer.toString()
        trail.single() shouldNotContain scenario.code
        trail.single() shouldNotContain scenario.suffix
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
                .substringAfter("operationId: getReservationConfirmationPdf")
                .substringBefore("operationId:")
        block shouldContain "application/pdf"
        block shouldContain "format: binary"
    }

    // --- apoio ---

    private class Scenario(
        val reservation: UUID,
        val code: String,
        val suffix: String,
        val civilName: String,
        val roomTypeName: String,
        val planName: String,
        val cancellationName: String,
        val cancellationPolicy: UUID,
    )

    /** Uma reserva confirmada (duas noites e dois adultos) cujo titular tem nome social. */
    private fun scenario(): Scenario {
        val suffix = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)
        val (type, _) = support.inventory.roomTypeWithRooms(1, today)
        val cancellationName = "Flex $suffix"
        val plan =
            support.plan(
                listOf(type),
                guarantee = "DEPOSIT",
                cancellationBody = support.rates.cancellationBody(name = cancellationName),
            )
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val civilName = "Civil Titular $suffix"
        val holder =
            UUID.fromString(
                support.guests
                    .createGuest(
                        support.guests.createBody(
                            profile =
                                support.guests.profileBody(
                                    extra = mapOf("fullName" to civilName, "socialName" to "Nome Social $suffix"),
                                ),
                            documents = emptyList(),
                        ),
                    )["id"]
                    .asString(),
            )
        val created =
            support.created(
                support.booking(
                    holder,
                    support.reservation(type, plan.id, today, today.plusDays(2), adults = 2),
                ),
            )["reservations"][0]
        val id = UUID.fromString(created["id"].asString())
        val row =
            jdbc
                .sql(
                    "SELECT (SELECT name FROM rooms.room_type WHERE id = :type) AS type_name, " +
                        "(SELECT name FROM rates.rate_plan WHERE id = :plan) AS plan_name",
                ).param("type", type)
                .param("plan", plan.id)
                .query { rs, _ -> rs.getString("type_name") to rs.getString("plan_name") }
                .single()
        return Scenario(
            reservation = id,
            code = created["code"].asString(),
            suffix = suffix,
            civilName = civilName,
            roomTypeName = row.first,
            planName = row.second,
            cancellationName = cancellationName,
            cancellationPolicy = plan.cancellationPolicy,
        )
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

    private companion object {
        const val SUFFIX_LENGTH = 12
        const val PRICED_DAYS = 10L
    }
}

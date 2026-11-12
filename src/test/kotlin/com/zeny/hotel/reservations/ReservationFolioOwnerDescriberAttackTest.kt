package com.zeny.hotel.reservations

import com.zeny.hotel.billing.FolioOwnerDescriber
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.rooms.RoomId
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
import org.springframework.mock.web.MockHttpServletResponse
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
 * Ataque ao descritor da reserva como dona da conta (M5 E11, Task 12): titular anonimizado, mesclado em cadeia,
 * situações da reserva, UH trocada, day use, booking com várias reservas, dono que não é reserva, texto hostil no
 * PDF e no ESC/POS e ausência de trilha.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions")
class ReservationFolioOwnerDescriberAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val describers: List<FolioOwnerDescriber>,
    @Autowired private val mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val cipher: ContentCipher,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()
    private val describer get() = describers.single { it.ownerType == FolioOwnerType.RESERVATION }
    private val ibm850 = charset("IBM850")

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `an anonymized holder leaves no name in the description, in the PDF or in the thermal print`() {
        val suffix = suffix()
        val holder = guest("Civil Anonimo $suffix", "Social Anonimo $suffix")
        val made = reservation(holder, assignRoom = false)
        support.forceCheckedOut(made.id)
        with(iam) { call(POST, "/api/v1/guests/$holder/anonymize", asAdmin()).ok() }

        val description = describer.describe(made.id)!!
        description.label shouldBe "Reserva ${made.code}"
        description.guestName.orEmpty() shouldNotContain "Anonimo"
        description.guestName.orEmpty() shouldNotContain suffix

        val text = pdfText(pdf(made.folio).contentAsByteArray)
        text shouldNotContain "Anonimo"
        text shouldNotContain suffix
        text shouldNotContain "null"
        val thermal = escPosText(printedJob(made.folio))
        thermal shouldNotContain "Anonimo"
        thermal shouldNotContain suffix
    }

    @Test
    fun `a holder merged twice in a chain is described by the last survivor`() {
        val suffix = suffix()
        val last = guest("Final $suffix", null)
        val middle = guest("Meio $suffix", null)
        val first = guest("Primeiro $suffix", null)
        val made = reservation(first, assignRoom = false)
        merge(survivor = middle, merged = first)
        merge(survivor = last, merged = middle)

        describer.describe(made.id)!!.guestName shouldBe "Final $suffix"
        val text = pdfText(pdf(made.folio).contentAsByteArray)
        text shouldContain "Final $suffix"
        text shouldNotContain "Primeiro $suffix"
        text shouldNotContain "Meio $suffix"
    }

    @Test
    fun `cancelled, in house and checked out reservations are all described`() {
        val suffix = suffix()
        val holder = guest("Civil Situacao $suffix", null)

        val cancelled = reservation(holder, assignRoom = false)
        support.cancel(cancelled.id).status shouldBe 200
        support.status(cancelled.id) shouldBe "CANCELLED"
        val inHouse = reservation(holder, assignRoom = false, rooms = 1)
        support.forceInHouse(inHouse.id, inHouse.roomsOfType.first())
        val out = reservation(holder, assignRoom = false)
        support.forceCheckedOut(out.id)

        listOf(cancelled, inHouse, out).forEach { made ->
            val description = describer.describe(made.id)!!
            description.label shouldBe "Reserva ${made.code}"
            description.guestName shouldBe "Civil Situacao $suffix"
            description.arrival shouldBe today
            description.departure shouldBe today.plusDays(2)
        }
        describer.describe(inHouse.id)!!.roomNumber shouldBe roomsApi.room(RoomId(inHouse.roomsOfType.first()))!!.number
    }

    @Test
    fun `reassigning the room shows the current room and not the old one`() {
        val holder = guest("Civil Troca ${suffix()}", null)
        val made = reservation(holder, assignRoom = false, rooms = 2)
        val (first, second) = made.roomsOfType
        assign(made.id, first)
        describer.describe(made.id)!!.roomNumber shouldBe roomsApi.room(RoomId(first))!!.number
        assign(made.id, second)

        describer.describe(made.id)!!.roomNumber shouldBe roomsApi.room(RoomId(second))!!.number
    }

    @Test
    fun `a day use has arrival equal to departure in the description`() {
        val holder = guest("Civil DayUse ${suffix()}", null)
        val made = reservation(holder, assignRoom = false, nights = 0)

        val description = describer.describe(made.id)!!
        description.arrival shouldBe today
        description.departure shouldBe today
    }

    @Test
    fun `each reservation of one booking describes its own code and room`() {
        val holder = guest("Civil Grupo ${suffix()}", null)
        val (type, rooms) = support.inventory.roomTypeWithRooms(2, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val body =
            support.booking(
                holder,
                support.reservation(type, plan.id, today, today.plusDays(1), adults = 1),
                support.reservation(type, plan.id, today, today.plusDays(3), adults = 1),
            )
        val created = support.created(body)["reservations"]
        val ids = created.values().map { UUID.fromString(it["id"].asString()) }
        assign(ids[0], rooms[0])
        assign(ids[1], rooms[1])

        val first = describer.describe(ids[0])!!
        val second = describer.describe(ids[1])!!
        first.label shouldBe "Reserva ${created[0]["code"].asString()}"
        second.label shouldBe "Reserva ${created[1]["code"].asString()}"
        first.roomNumber shouldBe roomsApi.room(RoomId(rooms[0]))!!.number
        second.roomNumber shouldBe roomsApi.room(RoomId(rooms[1]))!!.number
        first.departure shouldBe today.plusDays(1)
        second.departure shouldBe today.plusDays(3)
    }

    @Test
    fun `an id that belongs to a guest, a room or a folio is not a reservation and gives null`() {
        val holder = guest("Civil Outro ${suffix()}", null)
        val made = reservation(holder, assignRoom = false)

        describer.describe(holder) shouldBe null
        describer.describe(made.folio) shouldBe null
        describer.describe(made.roomsOfType.first()) shouldBe null
    }

    @Test
    fun `hostile text in the holder name is literal in the PDF and adds no ESC-POS command`() {
        val baseline = reservation(guest("Ana Normal ${suffix()}", null), assignRoom = false)
        val baselineBytes = escPosBytes(printedJob(baseline.folio))
        val hostile = "Ana <b>X</b> \${7*191} [[\${1+1}]] th:text \u001B@\u001DVB\u0007x"
        val made = reservation(guest(hostile, null), assignRoom = false)

        val text = pdfText(pdf(made.folio).contentAsByteArray)
        text shouldContain "<b>X</b>"
        text shouldContain "\${7*191}"
        text shouldContain "[[\${1+1}]]"
        text shouldNotContain "1337"
        text shouldNotContain "\u001B"

        val bytes = escPosBytes(printedJob(made.folio))
        count(bytes, 0x1B) shouldBe count(baselineBytes, 0x1B)
        count(bytes, 0x1D) shouldBe count(baselineBytes, 0x1D)
        count(bytes, 0x07) shouldBe count(baselineBytes, 0x07)
    }

    @Test
    fun `the description never prints the name and describing and rendering the PDF write no audit trail`() {
        val suffix = suffix()
        val made = reservation(guest("Civil Trilha $suffix", null), assignRoom = false)
        val before = auditCount()

        val description = describer.describe(made.id)!!
        pdf(made.folio).status shouldBe 200

        description.toString() shouldNotContain suffix
        description.toString() shouldNotContain "guestName"
        auditCount() shouldBe before
    }

    // --- apoio ---

    private class Reserved(
        val id: UUID,
        val code: String,
        val folio: UUID,
        val roomsOfType: List<UUID>,
    )

    private fun suffix(): String = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private fun guest(
        fullName: String,
        socialName: String?,
    ): UUID =
        UUID.fromString(
            support.guests
                .createGuest(
                    support.guests.createBody(
                        profile =
                            support.guests.profileBody(
                                extra = mapOf("fullName" to fullName, "socialName" to socialName),
                            ),
                        documents = emptyList(),
                    ),
                )["id"]
                .asString(),
        )

    private fun merge(
        survivor: UUID,
        merged: UUID,
    ) = with(iam) {
        call(
            POST,
            "/api/v1/guests/$survivor/merge",
            asAdmin(),
            mapOf("mergedGuestId" to merged, "takeFromMerged" to emptyList<String>()),
            ifMatch = support.guests.etagOf("/api/v1/guests/$survivor"),
        ).ok()
    }

    private fun assign(
        reservation: UUID,
        room: UUID,
    ) = with(
        iam,
    ) { call(POST, "/api/v1/reservations/$reservation/assign-room", asAdmin(), mapOf("roomId" to room)).ok() }

    @Suppress("LongParameterList")
    private fun reservation(
        holder: UUID,
        assignRoom: Boolean,
        rooms: Int = 1,
        nights: Long = 2,
    ): Reserved {
        val (type, created) = support.inventory.roomTypeWithRooms(rooms, today)
        val plan = support.plan(listOf(type))
        support.setPrices(plan.id, listOf(type), today, today.plusDays(PRICED_DAYS))
        val json =
            support.created(
                support.booking(holder, support.reservation(type, plan.id, today, today.plusDays(nights), adults = 1)),
            )["reservations"][0]
        val id = UUID.fromString(json["id"].asString())
        if (assignRoom) assign(id, created.first())
        return Reserved(id, json["code"].asString(), UUID.fromString(json["folioId"].asString()), created)
    }

    private fun pdf(folio: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/folios/$folio/statement/pdf") { with(iam.asAdmin()) }.andReturn().response

    private fun printedJob(folio: UUID): UUID {
        val response =
            mockMvc
                .post("/api/v1/folios/$folio/statement/print?audience=GUEST") {
                    with(iam.asAdmin())
                    header(IDEMPOTENCY_KEY_HEADER, UuidV7.generate().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"printerId":"${newPrinter()}"}"""
                }.andReturn()
                .response
        return UUID.fromString(support.billing.run { response.expect(202) }["printJobId"].asString())
    }

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
            val suffix = suffix()
            val agent = agents.createAgent("Agente $suffix").view.id
            return agents.createPrinter(NewPrinter("Imp $suffix", PrinterKind.THERMAL_ESCPOS, "addr-$suffix", agent)).id
        } finally {
            SecurityContextHolder.clearContext()
        }
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
            it == '\n' || it >= ' '
        }

    private fun count(
        bytes: ByteArray,
        value: Int,
    ): Int = bytes.count { it.toInt() == value }

    private fun auditCount(): Long = jdbc.sql("SELECT count(*) FROM audit.audit_entry").query(Long::class.java).single()

    private companion object {
        const val SUFFIX_LENGTH = 12
        const val PRICED_DAYS = 10L
    }
}

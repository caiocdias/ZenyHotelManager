package com.zeny.hotel.frontdesk

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.InventoryTestConfiguration
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.staff.DepartmentId
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.staff.StaffTestSupport
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * Os recados da recepção de ponta a ponta (M5 E06; RF-REC-07, RN-01, RN-13 e H10 do M3, D1 a D6): a criação para um
 * setor com o vínculo (reserva ou UH) e o responsável, as recusas que não gravam nada, a lista filtrada e paginada por
 * `(dueOn, id)`, a alteração com `If-Match` que nunca toca um resolvido, a resolução única (inclusive em corrida), as
 * permissões (o setor resolve sem editar), o texto que nunca vai à trilha, aos problemas nem aos logs, e a ausência de
 * `DELETE`. Cada teste cria o próprio setor (o banco é compartilhado).
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(IamTestConfiguration::class, InventoryTestConfiguration::class, MutableClockConfiguration::class)
@Suppress("LongParameterList") // os beans que os cenários usam
class FrontdeskTracesApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val staffApi: StaffApi,
    @Autowired private val clock: MutableClock,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val support = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val frontdesk = FrontdeskTestSupport(iam, support, hotelApi, roomsApi, jsonMapper)
    private val staff = StaffTestSupport(iam)
    private val today: LocalDate get() = hotelApi.currentBusinessDate()

    // --- Criação e leitura ----------------------------------------------------------------------------------------

    @Test
    fun `a trace is created for a department, linked to a reservation or a room, and read back with its ETag`() {
        val department = department()
        val assigneeName = staff.unique("Governanta")
        val assignee = iam.createUser()
        staff.createEmployee(department, assigneeName, assignee.id.value)
        val stay = frontdesk.stay()
        val reservation = reservation(stay)
        val roomNumber =
            roomsApi
                .room(
                    com.zeny.hotel.rooms
                        .RoomId(stay.room),
                )!!
                .number

        val byReservation =
            create(
                body(department, reservation = reservation, assignee = assignee.id.value, text = "  Berço na UH  "),
            )
        byReservation.raw.status shouldBe 201
        val reservationTrace = byReservation.json
        val id = UUID.fromString(reservationTrace["id"].asString())
        byReservation.raw.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/traces/$id"
        byReservation.raw.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        reservationTrace["departmentId"].asString() shouldBe department.toString()
        reservationTrace["departmentName"].asString() shouldBe departmentName(department)
        reservationTrace["dueOn"].asString() shouldBe today.toString()
        reservationTrace["text"].asString() shouldBe "Berço na UH"
        reservationTrace["reservationId"].asString() shouldBe reservation.toString()
        reservationTrace["roomId"].isNull shouldBe true
        reservationTrace["roomNumber"].isNull shouldBe true
        reservationTrace["assigneeUserId"].asString() shouldBe assignee.id.value.toString()
        reservationTrace["assigneeName"].asString() shouldBe assigneeName
        reservationTrace["status"].asString() shouldBe "OPEN"
        reservationTrace["createdBy"].asString() shouldBe iam.adminId().toString()
        reservationTrace["resolvedAt"].isNull shouldBe true
        reservationTrace["resolvedBy"].isNull shouldBe true
        reservationTrace["version"].asLong() shouldBe 0

        val read = call(GET, "/api/v1/traces/$id")
        read.status shouldBe 200
        read.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
        iam.json(read) shouldBe reservationTrace

        val byRoom = create(body(department, room = stay.room, dueOn = today.plusDays(1)))
        byRoom.raw.status shouldBe 201
        byRoom.json["roomId"].asString() shouldBe stay.room.toString()
        byRoom.json["roomNumber"].asString() shouldBe roomNumber
        byRoom.json["reservationId"].isNull shouldBe true
        byRoom.json["assigneeUserId"].isNull shouldBe true
        byRoom.json["assigneeName"].isNull shouldBe true

        call(GET, "/api/v1/traces/${UUID.randomUUID()}").problem(404, "TRACE_NOT_FOUND")
    }

    @Test
    @Suppress("LongMethod") // cada recusa com o código e o campo
    fun `a trace without a link, with both links, an unknown department, reservation, room or assignee is refused`() {
        val department = department()
        val stay = frontdesk.stay(rooms = 2)
        val reservation = reservation(stay)
        val inactiveRoom = stay.rooms[1]
        with(iam) { call(POST, "/api/v1/rooms/$inactiveRoom/deactivate", asAdmin()).ok() }
        val unlinkedUser = iam.createUser()

        create(body(department)).fieldProblem(400, "TRACE_INVALID", "reservationId")
        create(body(department, reservation = reservation, room = stay.room))
            .fieldProblem(400, "TRACE_INVALID", "reservationId")
        create(body(department, room = stay.room, text = "   ")).fieldProblem(400, "TRACE_INVALID", "text")
        create(body(department, room = stay.room, text = "x".repeat(501))).fieldProblem(400, "TRACE_INVALID", "text")
        create(body(department, room = stay.room, dueOn = today.minusDays(1)))
            .fieldProblem(400, "TRACE_INVALID", "dueOn")
        create(body(department, room = stay.room).plus("departmentId" to UUID.randomUUID()))
            .fieldProblem(400, "UNKNOWN_DEPARTMENT", "departmentId")
        create(body(department, reservation = UUID.randomUUID()))
            .fieldProblem(400, "UNKNOWN_RESERVATION", "reservationId")
        create(body(department, room = UUID.randomUUID())).fieldProblem(400, "UNKNOWN_ROOM", "roomId")
        create(body(department, room = stay.room, assignee = unlinkedUser.id.value))
            .fieldProblem(400, "UNKNOWN_ASSIGNEE", "assigneeUserId")
        create(body(department, room = stay.room, assignee = UUID.randomUUID()))
            .fieldProblem(400, "UNKNOWN_ASSIGNEE", "assigneeUserId")
        val inactive = create(body(department, room = inactiveRoom))
        with(iam) { inactive.raw.problem(422, "ROOM_INACTIVE") }["field"].asString() shouldBe "roomId"
        create(body(department, room = stay.room).minus("text")).fieldProblem(400, "VALIDATION_FAILED", "text")

        traceCount(department) shouldBe 0
    }

    // --- Lista ----------------------------------------------------------------------------------------------------

    @Test
    fun `the list filters by department, due date, status, reservation and room, page by page`() {
        val department = department()
        val other = department()
        val stay = frontdesk.stay(rooms = 2)
        val reservation = reservation(stay)
        val day = today.plusDays(3)
        val byReservation = created(body(department, reservation = reservation, dueOn = day))
        val byRoom = created(body(department, room = stay.room, dueOn = day))
        val byOtherRoom = created(body(department, room = stay.rooms[1], dueOn = day))
        val sameDay = listOf(byReservation, byRoom, byOtherRoom).sorted()
        val later = created(body(department, room = stay.room, dueOn = day.plusDays(1)))
        val resolved = created(body(department, room = stay.rooms[1], dueOn = day.plusDays(2)))
        call(POST, "/api/v1/traces/$resolved/resolve").status shouldBe 200
        val elsewhere = created(body(other, reservation = reservation, dueOn = day))
        val all = sameDay + later + resolved

        list("departmentId=$department") shouldContainExactly all
        list("departmentId=$department&dueOn=$day") shouldContainExactly sameDay
        list("departmentId=$department&status=RESOLVED") shouldContainExactly listOf(resolved)
        list("departmentId=$department&status=OPEN") shouldContainExactly sameDay + later
        list("reservationId=$reservation") shouldContainExactly listOf(byReservation, elsewhere).sorted()
        list("reservationId=$reservation&departmentId=$other") shouldContainExactly listOf(elsewhere)
        list("roomId=${stay.room}") shouldContainExactly listOf(byRoom, later)
        list("dueOn=$day&roomId=${stay.rooms[1]}") shouldContainExactly listOf(byOtherRoom)
        call(GET, "/api/v1/traces?status=LATE").problem(400, "VALIDATION_FAILED")

        // Página a página (limit=2): cada recado uma vez, na ordem (dueOn, id), mesmo com o vencimento repetido.
        val pages = mutableListOf<List<UUID>>()
        var cursor: String? = null
        do {
            val page =
                with(iam) {
                    call(
                        GET,
                        "/api/v1/traces?departmentId=$department&limit=2" + cursor?.let { "&cursor=$it" }.orEmpty(),
                    ).ok()
                }
            pages += page["items"].values().map { UUID.fromString(it["id"].asString()) }
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        pages.map { it.size } shouldContainExactly listOf(2, 2, 1)
        pages.flatten() shouldContainExactly all
    }

    // --- Alteração ------------------------------------------------------------------------------------------------

    @Test
    fun `updating needs If-Match and never touches a resolved trace`() {
        val department = department()
        val other = department()
        val stay = frontdesk.stay()
        val id = created(body(department, room = stay.room, text = "Trocar o chuveiro"))
        val change = change(other, today.plusDays(2), "Trocar o chuveiro e a toalha")

        update(id, change, ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
        update(id, change, ifMatch = "\"7\"").problem(412, "PRECONDITION_FAILED")
        update(id, change(department, today.minusDays(1), "x"), "\"0\"")
            .fieldProblem(400, "TRACE_INVALID", "dueOn")
        update(id, change(UUID.randomUUID(), today, "x"), "\"0\"")
            .fieldProblem(400, "UNKNOWN_DEPARTMENT", "departmentId")

        val updated = update(id, change, "\"0\"")
        updated.status shouldBe 200
        updated.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
        val json = iam.json(updated)
        json["departmentId"].asString() shouldBe other.toString()
        json["departmentName"].asString() shouldBe departmentName(other)
        json["dueOn"].asString() shouldBe today.plusDays(2).toString()
        json["text"].asString() shouldBe "Trocar o chuveiro e a toalha"
        json["roomId"].asString() shouldBe stay.room.toString()
        json["version"].asLong() shouldBe 1

        val resolved = call(POST, "/api/v1/traces/$id/resolve")
        resolved.status shouldBe 200
        val etag = resolved.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val before = iam.json(call(GET, "/api/v1/traces/$id"))

        update(id, change(department, today.plusDays(4), "Outra coisa"), etag)
            .problem(409, "TRACE_ALREADY_RESOLVED")

        iam.json(call(GET, "/api/v1/traces/$id")) shouldBe before
    }

    // --- Resolução ------------------------------------------------------------------------------------------------

    @Test
    fun `resolving records who and when, once`() {
        val department = department()
        val stay = frontdesk.stay()
        val operator = ReservationsTestSupport.Operator(iam.createUserWith(*departmentGrants()))
        val id = created(body(department, room = stay.room))
        clock.advance(Duration.ofMinutes(7))
        val now = clock.instant()

        val response = call(POST, "/api/v1/traces/$id/resolve", operator.auth)
        response.status shouldBe 200
        response.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
        val json = iam.json(response)
        json["status"].asString() shouldBe "RESOLVED"
        json["resolvedBy"].asString() shouldBe
            operator.user.id.value
                .toString()
        Duration.between(now, Instant.parse(json["resolvedAt"].asString())).abs().toMillis() shouldBeLessThan 1
        iam.json(call(GET, "/api/v1/traces/$id")) shouldBe json

        call(POST, "/api/v1/traces/$id/resolve", operator.auth).problem(409, "TRACE_ALREADY_RESOLVED")
        call(POST, "/api/v1/traces/${UUID.randomUUID()}/resolve").problem(404, "TRACE_NOT_FOUND")

        // Dois resolvendo ao mesmo tempo: a linha travada deixa só um resolver.
        repeat(ROUNDS) { _ ->
            val raced = created(body(department, room = stay.room))
            val barrier = CyclicBarrier(2)
            val statuses = mutableListOf<Int>()
            listOf(operator.auth, iam.asAdmin())
                .map { auth ->
                    thread {
                        barrier.await()
                        val status = call(POST, "/api/v1/traces/$raced/resolve", auth).status
                        synchronized(statuses) { statuses += status }
                    }
                }.forEach { it.join() }
            statuses shouldContainExactlyInAnyOrder listOf(200, 409)
            iam.json(call(GET, "/api/v1/traces/$raced"))["version"].asLong() shouldBe 1
        }
    }

    // --- Permissões -----------------------------------------------------------------------------------------------

    @Test
    fun `writes need their permission, and the department staff resolve without editing`() {
        val department = department()
        val stay = frontdesk.stay()
        val id = created(body(department, room = stay.room))
        val nobody = iam.asTestUser(iam.createUser())
        val reader = iam.asTestUser(iam.createUserWith(frontdesk.grant(FrontdeskPermissions.TRACES_READ)))
        val departmentStaff = iam.asTestUser(iam.createUserWith(*departmentGrants()))

        call(POST, "/api/v1/traces", nobody, body(department, room = stay.room)).problem(403, "PERMISSION_DENIED")
        call(GET, "/api/v1/traces?departmentId=$department", nobody).problem(403, "PERMISSION_DENIED")
        call(GET, "/api/v1/traces/$id", nobody).problem(403, "PERMISSION_DENIED")
        update(id, change(department, today, "x"), "\"0\"", nobody).problem(403, "PERMISSION_DENIED")
        call(POST, "/api/v1/traces/$id/resolve", nobody).problem(403, "PERMISSION_DENIED")
        call(POST, "/api/v1/traces", reader, body(department, room = stay.room)).problem(403, "PERMISSION_DENIED")
        call(POST, "/api/v1/traces/$id/resolve", reader).problem(403, "PERMISSION_DENIED")
        call(GET, "/api/v1/traces/$id", reader).status shouldBe 200

        update(id, change(department, today, "x"), "\"0\"", departmentStaff).problem(403, "PERMISSION_DENIED")
        call(POST, "/api/v1/traces", departmentStaff, body(department, room = stay.room))
            .problem(403, "PERMISSION_DENIED")
        call(POST, "/api/v1/traces/$id/resolve", departmentStaff).status shouldBe 200
        traceCount(department) shouldBe 1
    }

    // --- Sigilo do texto (RN-13) ----------------------------------------------------------------------------------

    @Test
    fun `the trace text never reaches the audit trail, the problems or the logs`(output: CapturedOutput) {
        val department = department()
        val stay = frontdesk.stay()
        val created = create(body(department, room = stay.room, text = "Berço $CREATED_MARKER"))
        created.raw.status shouldBe 201
        val id = UUID.fromString(created.json["id"].asString())
        val updated = update(id, change(department, today, "Toalhas $UPDATED_MARKER"), "\"0\"")
        updated.status shouldBe 200
        val longText = "$REFUSED_MARKER ".repeat(40)
        val refusals =
            listOf(
                create(body(department, room = stay.room, text = longText)).raw,
                update(id, change(department, today, longText), "\"1\""),
                create(body(department, room = UUID.randomUUID(), text = "Cortina $REFUSED_MARKER")).raw,
            )
        call(POST, "/api/v1/traces/$id/resolve").status shouldBe 200
        update(id, change(department, today, "Depois $REFUSED_MARKER"), "\"2\"").problem(409, "TRACE_ALREADY_RESOLVED")

        refusals.map { it.status } shouldContainExactly listOf(400, 400, 400)
        refusals.forEach { body -> MARKERS.forEach { body.getContentAsString(Charsets.UTF_8) shouldNotContain it } }
        val trail = support.auditEntries(id)
        trail.map { it["action"] } shouldContainExactly
            listOf("frontdesk.trace.create", "frontdesk.trace.update", "frontdesk.trace.resolve")
        trail.forEach { entry ->
            MARKERS.forEach { marker ->
                withClue(entry) {
                    entry["before"].toString() shouldNotContain marker
                    entry["after"].toString() shouldNotContain marker
                }
            }
        }
        trail[0]["after"].toString() shouldContain
            "\"textLength\": ${"Berço $CREATED_MARKER".let { it.codePointCount(0, it.length) }}"
        trail[1]["after"].toString() shouldContain
            "\"textLength\": ${"Toalhas $UPDATED_MARKER".let { it.codePointCount(0, it.length) }}"
        trail[1]["after"].toString() shouldContain "\"textChanged\": true"
        MARKERS.forEach { output.all shouldNotContain it }
    }

    @Test
    fun `a same-length text edit is told apart in the trail, and a no-op update writes nothing`() {
        val department = department()
        val stay = frontdesk.stay()
        val id = created(body(department, room = stay.room, text = "Chuveiro A"))
        val first = update(id, change(department, today, "Chuveiro B"), "\"0\"")
        first.status shouldBe 200
        val noOp = update(id, change(department, today, "Chuveiro B"), "\"1\"")
        noOp.status shouldBe 200
        noOp.getHeader(HttpHeaders.ETAG) shouldBe first.getHeader(HttpHeaders.ETAG)
        iam.json(noOp)["version"].asLong() shouldBe 1

        val trail = support.auditEntries(id)
        trail.map { it["action"] } shouldContainExactly listOf("frontdesk.trace.create", "frontdesk.trace.update")
        trail[1]["after"].toString() shouldContain "\"textChanged\": true"
        trail[1]["after"].toString() shouldNotContain "Chuveiro"
    }

    @Test
    fun `updating with an unknown assignee is refused and changes nothing`() {
        val department = department()
        val stay = frontdesk.stay()
        val id = created(body(department, room = stay.room))
        val before = iam.json(call(GET, "/api/v1/traces/$id"))

        update(id, change(department, today, "Outro", assignee = UUID.randomUUID()), "\"0\"")
            .fieldProblem(400, "UNKNOWN_ASSIGNEE", "assigneeUserId")

        iam.json(call(GET, "/api/v1/traces/$id")) shouldBe before
        support.auditEntries(id).map { it["action"] } shouldContainExactly listOf("frontdesk.trace.create")
    }

    // --- Sem exclusão ---------------------------------------------------------------------------------------------

    @Test
    fun `there is no DELETE for traces`() {
        val department = department()
        val stay = frontdesk.stay()
        val id = created(body(department, room = stay.room))

        call(DELETE, "/api/v1/traces/$id").problem(405, "METHOD_NOT_ALLOWED")

        call(GET, "/api/v1/traces/$id").status shouldBe 200
        traceCount(department) shouldBe 1
    }

    // --- Apoio ----------------------------------------------------------------------------------------------------

    private class Created(
        val raw: MockHttpServletResponse,
        val json: JsonNode,
    )

    private fun department(): UUID = staff.createDepartment()

    private fun departmentName(id: UUID): String = staffApi.departments().single { it.id == DepartmentId(id) }.name

    /** Uma reserva confirmada na estadia, sem UH. */
    private fun reservation(stay: FrontdeskTestSupport.Stay): UUID =
        UUID.fromString(
            support
                .created(
                    support.booking(
                        support.guest(),
                        support.reservation(stay.type, stay.plan, today, today.plusDays(2)),
                    ),
                )["reservations"][0]["id"]
                .asString(),
        )

    @Suppress("LongParameterList") // o corpo inteiro, com padrões
    private fun body(
        department: UUID,
        reservation: UUID? = null,
        room: UUID? = null,
        assignee: UUID? = null,
        dueOn: LocalDate = today,
        text: String = "Recado de teste",
    ): Map<String, Any?> =
        mapOf(
            "departmentId" to department,
            "dueOn" to dueOn,
            "text" to text,
            "reservationId" to reservation,
            "roomId" to room,
            "assigneeUserId" to assignee,
        )

    private fun change(
        department: UUID,
        dueOn: LocalDate,
        text: String,
        assignee: UUID? = null,
    ): Map<String, Any?> =
        mapOf("departmentId" to department, "dueOn" to dueOn, "text" to text, "assigneeUserId" to assignee)

    private fun call(
        method: HttpMethod,
        path: String,
        auth: RequestPostProcessor = iam.asAdmin(),
        body: Any? = null,
    ): MockHttpServletResponse = iam.call(method, path, auth, body)

    private fun create(body: Map<String, Any?>): Created {
        val raw = call(POST, "/api/v1/traces", body = body)
        return Created(raw, iam.json(raw))
    }

    private fun created(body: Map<String, Any?>): UUID {
        val response = create(body)
        withClue(response.raw.getContentAsString(Charsets.UTF_8)) { response.raw.status shouldBe 201 }
        return UUID.fromString(response.json["id"].asString())
    }

    private fun update(
        id: UUID,
        body: Map<String, Any?>,
        ifMatch: String?,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(PUT, "/api/v1/traces/$id", auth, body, ifMatch = ifMatch)

    private fun list(query: String): List<UUID> =
        with(iam) { call(GET, "/api/v1/traces?$query&limit=200").ok() }["items"]
            .values()
            .map { UUID.fromString(it["id"].asString()) }

    private fun traceCount(department: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM frontdesk.trace WHERE department_id = :id")
            .param("id", department)
            .query(Int::class.java)
            .single()

    private fun departmentGrants() =
        arrayOf(frontdesk.grant(FrontdeskPermissions.TRACES_READ), frontdesk.grant(FrontdeskPermissions.TRACES_RESOLVE))

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(iam) { problem(status, code) }

    private fun MockHttpServletResponse.fieldProblem(
        status: Int,
        code: String,
        field: String,
    ): JsonNode {
        val problem = problem(status, code)
        problem["errors"].values().map { it["field"].asString() } shouldContain field
        return problem
    }

    private fun Created.fieldProblem(
        status: Int,
        code: String,
        field: String,
    ): JsonNode = raw.fieldProblem(status, code, field)

    private infix fun List<String>.shouldContain(field: String) = withClue(this) { this.contains(field) shouldBe true }

    private companion object {
        const val ROUNDS = 3
        const val CREATED_MARKER = "BERCO-UH-SEGREDO"
        const val UPDATED_MARKER = "TOALHA-UH-SEGREDO"
        const val REFUSED_MARKER = "CORTINA-UH-SEGREDO"
        val MARKERS = listOf(CREATED_MARKER, UPDATED_MARKER, REFUSED_MARKER)
    }
}

package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.rooms.RoomsTestSupport
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A publicação do plano do dia (M6 E03, Task 3; RF-GOV-03, D7, D8 e D12-E03): grava nas tarefas a camareira e a posição
 * (`ASSIGNED`) ou as volta a `PENDING` (sem camareira ou retiradas), não toca a tarefa que já andou (Review Focus 4),
 * reconfere a escala (Review Focus 1), avisa só quem teve tarefa incluída ou removida (Review Focus 3), sem dado de
 * hóspede, e registra a trilha só por ids.
 *
 * O banco é compartilhado e o plano nunca é apagado (Global Constraints): numa data futura da janela, cada teste monta
 * o rascunho exato com as próprias tarefas e camareiras ([AssignmentPlanTestSupport.draft]), manda o `If-Match` do
 * plano existente e afirma só sobre as próprias tarefas, camareiras e painéis.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // um cenário por critério; os apoios do contexto
class AssignmentPlanPublishTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)
    private val rooms = RoomsTestSupport(iam)
    private val reservations = ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc)
    private val stays = HousekeepingStays(reservations, reservationsApi, hotel)

    private val date: LocalDate get() = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)

    @Test
    fun `the first publication assigns the tasks with attendant and position and notifies each attendant`() {
        val (ana, anaUser) = attendantWithUser()
        val (bia, biaUser) = attendantWithUser()
        val first = roomTask()
        val second = roomTask()
        val third = areaTask()
        plans.draft(date, listOf(ana, bia), listOf(first to ana, second to ana, third to bia))

        val response = plans.publish(date)
        val body = with(iam) { response.ok() }

        body["status"].asString() shouldBe "PUBLISHED"
        response.getHeader(HttpHeaders.ETAG) shouldBe "\"${body["version"].asLong()}\""
        assigned(first) shouldBe (ana to 1)
        assigned(second) shouldBe (ana to 2)
        assigned(third) shouldBe (bia to 1)
        listOf(first, second, third).forEach { plans.task(it)["status"].asString() shouldBe "ASSIGNED" }
        val listed =
            housekeeping
                .listAll(
                    "${HousekeepingTestSupport.TASKS}?date=$date",
                ).associateBy { it["id"].asString() }
        listed.getValue("$first")["assignedEmployeeId"].asString() shouldBe "$ana"
        listed.getValue("$first")["assignmentPosition"].asInt() shouldBe 1
        notNotified(body).keys.filter { it in setOf(ana, bia) }.shouldBeEmpty()
        listOf(anaUser, biaUser).forEach { user ->
            withClue(user.id) {
                val notices = panel(user)
                notices shouldHaveSize 1
                notices.single()["type"].asString() shouldBe "notifications.task-assigned"
            }
        }
    }

    @Test
    fun `a republication without change notifies nobody`() {
        val (ana, anaUser) = attendantWithUser()
        val task = roomTask()
        plans.draft(date, listOf(ana), listOf(task to ana))
        plans.published(date)

        val (body, _) = plans.published(date)

        body["notified"].asInt() shouldBe 0
        body["notNotified"].values().shouldBeEmpty()
        panel(anaUser) shouldHaveSize 1
        assigned(task) shouldBe (ana to 1)
    }

    @Test
    fun `a task moved from A to B notifies only A and B, not C (Review Focus 3)`() {
        val (ana, anaUser) = attendantWithUser()
        val (bia, biaUser) = attendantWithUser()
        val (cia, ciaUser) = attendantWithUser()
        val moved = roomTask()
        val kept = roomTask()
        val other = roomTask()
        plans.draft(date, listOf(ana, bia, cia), listOf(moved to ana, kept to bia, other to cia))
        plans.published(date)

        val etag = plans.etagOrNull(date)
        with(iam) {
            plans.adjust(date, mapOf("items" to listOf(mapOf("taskId" to moved, "employeeId" to bia))), etag).ok()
        }
        val (body, _) = plans.published(date)

        assigned(moved) shouldBe (bia to 2)
        panel(anaUser) shouldHaveSize 2
        panel(biaUser) shouldHaveSize 2
        panel(ciaUser) shouldHaveSize 1
        panel(anaUser).first()["body"].asString() shouldContain "Removidas"
        panel(biaUser).first()["body"].asString() shouldContain "Incluídas"
        body["notified"].asInt() shouldBe 2
    }

    @Test
    fun `a withdrawn task and a task left without attendant go back to PENDING without attendant`() {
        val (ana, anaUser) = attendantWithUser()
        val kept = roomTask()
        val withdrawn = roomTask()
        val unassigned = areaTask()
        plans.draft(date, listOf(ana), listOf(kept to ana, withdrawn to ana, unassigned to ana))
        plans.published(date)

        val etag = plans.etagOrNull(date)
        val change =
            mapOf(
                "items" to listOf(mapOf("taskId" to unassigned, "employeeId" to null)),
                "remove" to listOf(withdrawn),
            )
        with(iam) { plans.adjust(date, change, etag).ok() }
        plans.published(date)

        listOf(withdrawn, unassigned).forEach { id ->
            withClue(id) {
                val task = plans.task(id)
                task["status"].asString() shouldBe "PENDING"
                task["assignedEmployeeId"].isNull shouldBe true
                task["assignmentPosition"].isNull shouldBe true
            }
        }
        assigned(kept) shouldBe (ana to 1)
        panel(anaUser) shouldHaveSize 2
    }

    @Test
    fun `who has no user, or whose notify delivered nothing, is listed as not notified with the reason`() {
        val noUser = plans.attendant(date)
        val (panelOff, panelOffUser) = attendantWithUser()
        val (inactive, inactiveUser) = attendantWithUser()
        val (notified, _) = attendantWithUser()
        with(iam) {
            call(
                PUT,
                "/api/v1/notification-preferences",
                asTestUser(panelOffUser),
                mapOf(
                    "preferences" to
                        listOf(mapOf("type" to "notifications.task-assigned", "inApp" to false, "push" to false)),
                ),
            ).ok()
        }
        iam.deactivate(inactiveUser)
        val employees = listOf(noUser, panelOff, inactive, notified)
        plans.draft(date, employees, employees.map { roomTask() to it })

        val (body, _) = plans.published(date)

        val reasons = notNotified(body).filterKeys { it in employees }
        reasons shouldBe mapOf(noUser to "NO_USER", panelOff to "NOT_DELIVERED", inactive to "NOT_DELIVERED")
        panel(panelOffUser).shouldBeEmpty()
    }

    @Test
    fun `an attendant who lost a task and left the schedule is still notified of the removal`() {
        val (ana, anaUser) = attendantWithUser()
        val (bia, biaUser) = attendantWithUser()
        val task = roomTask()
        plans.draft(date, listOf(ana, bia), listOf(task to ana))
        plans.published(date)
        plans.staff.record(ana, ScheduleEntryKind.DAY_OFF, date)

        plans.draft(date, listOf(bia), listOf(task to bia))
        val (body, _) = plans.published(date)

        assigned(task) shouldBe (bia to 1)
        notNotified(body).keys.filter { it in setOf(ana, bia) }.shouldBeEmpty()
        panel(anaUser) shouldHaveSize 2
        panel(anaUser).first()["body"].asString() shouldContain "Removidas"
        panel(biaUser) shouldHaveSize 1
    }

    @Test
    fun `a republication leaves untouched an IN_PROGRESS task that is no longer in the draft (R2)`() {
        val (ana, _) = attendantWithUser()
        val started = roomTask()
        val other = roomTask()
        plans.draft(date, listOf(ana), listOf(started to ana, other to ana))
        plans.published(date)
        plans.setStatus(started, "IN_PROGRESS")
        val before = plans.task(started)

        // A sugestão nova não leva a tarefa que já andou; a publicação não a conta como retirada (D7-E03).
        plans.draft(date, listOf(ana), listOf(other to ana))
        plans.published(date)

        plans.task(started) shouldBe before
        assigned(other) shouldBe (ana to 1)
    }

    @Test
    fun `an IN_PROGRESS task whose item only changes position is not rewritten and does not block`() {
        val (ana, _) = attendantWithUser()
        val started = roomTask()
        val other = roomTask()
        plans.draft(date, listOf(ana), listOf(started to ana, other to ana))
        plans.published(date)
        val etag = plans.etagOrNull(date)
        with(iam) {
            plans
                .adjust(
                    date,
                    mapOf("items" to listOf(mapOf("taskId" to started, "employeeId" to ana, "position" to 2))),
                    etag,
                ).ok()
        }
        plans.setStatus(started, "IN_PROGRESS")
        val before = plans.task(started)

        plans.published(date)

        plans.task(started) shouldBe before
        assigned(started) shouldBe (ana to 1)
        assigned(other) shouldBe (ana to 1)
    }

    @Test
    fun `an IN_PROGRESS task with the same published item does not block and is not changed`() {
        val (ana, _) = attendantWithUser()
        val started = roomTask()
        plans.draft(date, listOf(ana), listOf(started to ana))
        plans.published(date)
        plans.setStatus(started, "IN_PROGRESS")
        val before = plans.task(started)

        plans.published(date)

        plans.task(started) shouldBe before
    }

    @Test
    fun `an IN_PROGRESS task that the draft would move is 422 with its id in errors and nothing is written`() {
        val (ana, _) = attendantWithUser()
        val (bia, _) = attendantWithUser()
        val started = roomTask()
        val other = roomTask()
        plans.draft(date, listOf(ana, bia), listOf(started to ana, other to ana))
        plans.published(date)
        plans.draft(date, listOf(ana, bia), listOf(started to bia, other to bia))
        plans.setStatus(started, "IN_PROGRESS")
        val etag = plans.etagOrNull(date)

        val problem = with(iam) { plans.publish(date, etag).problem(422, "ASSIGNMENT_TASK_NOT_ASSIGNABLE") }

        problem["errors"].values().map { it["field"].asString() } shouldContain "$started"
        assigned(other) shouldBe (ana to 2)
        plans.etagOrNull(date) shouldBe etag
        plans.plan(date).let { with(iam) { it.ok() } }["status"].asString() shouldBe "DRAFT"
    }

    @Test
    fun `an attendant who left the schedule before the publication makes it 422 and nothing is written`() {
        val (ana, anaUser) = attendantWithUser()
        val task = roomTask()
        plans.draft(date, listOf(ana), listOf(task to ana))
        plans.staff.record(ana, ScheduleEntryKind.DAY_OFF, date)
        val etag = plans.etagOrNull(date)

        with(iam) { plans.publish(date, etag).problem(422, "ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED") }

        plans.task(task)["status"].asString() shouldBe "PENDING"
        plans.etagOrNull(date) shouldBe etag
        panel(anaUser).shouldBeEmpty()
    }

    @Test
    fun `an ASSIGNED task of another date, outside the plan content, is not withdrawn`() {
        val (ana, _) = attendantWithUser()
        val outside =
            plans.insertTask(
                date.plusDays(1),
                area = UUID.randomUUID(),
                status = "ASSIGNED",
                employee = ana,
                position = 1,
            )
        plans.draft(date, listOf(ana), listOf(roomTask() to ana))

        plans.published(date)

        plans.task(outside)["status"].asString() shouldBe "ASSIGNED"
        assigned(outside) shouldBe (ana to 1)
    }

    /**
     * O plano de uma data futura leva só as tarefas da própria data (D2-E03): a `ASSIGNED` de uma data **anterior** a
     * ele, ainda dentro da janela, não é retirada. Pega o mutante `includePrevious = true` na publicação do plano
     * futuro. A data anterior é +3 ([DAYS_AHEAD] - 2), que nenhum teste publica no Postgres compartilhado: o
     * `AssignmentPlanRealtimeTest` publica +4, este teste +5, e o `AssignmentPlansApiTest` só sugere em +3.
     */
    @Test
    fun `an ASSIGNED task of an earlier date in the window is not withdrawn by the future plan`() {
        val (ana, _) = attendantWithUser()
        val earlier = date.minusDays(2)
        val before =
            plans.insertTask(
                earlier,
                area = UUID.randomUUID(),
                status = "ASSIGNED",
                employee = ana,
                position = 2,
            )
        plans.draft(date, listOf(ana), listOf(roomTask() to ana))

        plans.published(date)

        plans.task(before)["status"].asString() shouldBe "ASSIGNED"
        plans.task(before)["date"].asString() shouldBe "$earlier"
        assigned(before) shouldBe (ana to 2)
    }

    @Test
    fun `the notice title has the date and the text only room numbers and area names, never the guest name`() {
        val (ana, anaUser) = attendantWithUser()
        val type = rooms.createRoomType()
        val room = rooms.createRoom(type)
        val guest = "Hóspede Sigiloso ${UUID.randomUUID().toString().take(8)}"
        val (reservation, _) = stays.reservation(type, stays.planFor(type), arrival = date, name = guest)
        reservationsApi.assignRoom(reservation, RoomId(room))
        val areaName = rooms.uniqueName("Piscina")
        val area = rooms.createArea(areaName)
        val roomTask = roomTask(room)
        val areaTask = areaTask(area)
        plans.draft(date, listOf(ana), listOf(roomTask to ana, areaTask to ana))

        plans.published(date)

        val notice = panel(anaUser).single()
        notice["title"].asString() shouldBe
            "Suas tarefas de ${date.format(DateTimeFormatter.ofPattern("dd/MM"))} mudaram"
        val text = notice["body"].asString()
        text shouldContain "UH ${roomsApi.room(RoomId(room)).shouldNotBeNull().number}"
        text shouldContain areaName
        text shouldNotContain guest
        text shouldNotContain "Sigiloso"
    }

    @Test
    fun `with many areas the text is cut with e mais N and stays within the limit`() {
        val (ana, anaUser) = attendantWithUser()
        val tasks = List(AREAS) { areaTask(rooms.createArea(longName(it))) }
        plans.draft(date, listOf(ana), tasks.map { it to ana })

        plans.published(date)

        val text = panel(anaUser).single()["body"].asString()
        text.length shouldBeLessThanOrEqual MAX_BODY
        text shouldContain Regex("e mais \\d+")
    }

    @Test
    fun `after a new suggest the tasks keep the published attendant and position (D12-E03)`() {
        val (ana, _) = attendantWithUser()
        val (bia, _) = attendantWithUser()
        val first = roomTask()
        val second = roomTask()
        plans.draft(date, listOf(ana, bia), listOf(first to ana, second to ana))
        plans.published(date)

        plans.suggested(date, listOf(bia))

        assigned(first) shouldBe (ana to 1)
        assigned(second) shouldBe (ana to 2)
    }

    @Test
    fun `the trail records the publication with the attendant before and after of each changed task, by ids`() {
        val (ana, _) = attendantWithUser()
        val (bia, _) = attendantWithUser()
        val task = roomTask()
        plans.draft(date, listOf(ana, bia), listOf(task to ana))
        plans.published(date)
        val etag = plans.etagOrNull(date)
        with(iam) {
            plans.adjust(date, mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to bia))), etag).ok()
        }
        plans.published(date)

        val entries = trail(task)
        entries.map { it.first }.distinct() shouldBe listOf("housekeeping.assignment-plan.publish")
        entries.map { (_, before, after) -> before?.get("assignedEmployeeId") to after["assignedEmployeeId"] } shouldBe
            listOf(null to "$ana", "$ana" to "$bia")
        val planId = UUID.fromString(with(iam) { plans.plan(date).ok() }["id"].asString())
        housekeeping.auditActions("housekeeping.assignment-plan", planId) shouldContain
            "housekeeping.assignment-plan.publish"
        entries.forEach { (_, before, after) ->
            (before?.toString().orEmpty() + after.toString()).let {
                it shouldNotContain "name"
                it shouldNotContain "Camareira"
            }
        }
    }

    @Test
    fun `publishing needs the If-Match of the current version`() {
        val (ana, _) = attendantWithUser()
        plans.draft(date, listOf(ana), listOf(roomTask() to ana))
        val etag = plans.etagOrNull(date)

        with(iam) {
            plans.publish(date, null).problem(428, "PRECONDITION_REQUIRED")
            plans.publish(date, "\"0\"").problem(412, "PRECONDITION_FAILED")
        }
        plans.etagOrNull(date) shouldBe etag
    }

    // --- Apoio -------------------------------------------------------------------------------------

    private fun attendantWithUser(): Pair<UUID, TestUser> {
        val user = iam.createUser()
        return plans.attendant(date, userId = user.id.value) to user
    }

    private fun roomTask(room: UUID = housekeeping.createRoom()): UUID =
        id(housekeeping.requestTask(housekeeping.taskBody(TOUCH_UP, roomId = room, date = "$date")))

    private fun areaTask(area: UUID = rooms.createArea()): UUID =
        id(housekeeping.requestTask(housekeeping.taskBody(TOUCH_UP, areaId = area, date = "$date")))

    private fun id(node: JsonNode): UUID = UUID.fromString(node["id"].asString())

    /** A camareira e a posição gravadas na tarefa. */
    private fun assigned(task: UUID): Pair<UUID, Int>? {
        val node = plans.task(task)
        if (node["assignedEmployeeId"].isNull) return null
        return UUID.fromString(node["assignedEmployeeId"].asString()) to node["assignmentPosition"].asInt()
    }

    private fun notNotified(body: JsonNode): Map<UUID, String> =
        body["notNotified"].values().associate {
            UUID.fromString(
                it["employeeId"].asString(),
            ) to it["reason"].asString()
        }

    /** As notificações do usuário no painel dele, da mais nova para a mais velha. */
    private fun panel(user: TestUser): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/notifications?limit=100", asTestUser(user)).ok() }["items"].values().toList()

    /** As entradas da trilha da tarefa: a ação, o antes e o depois. */
    private fun trail(task: UUID): List<Triple<String, Map<String, Any?>?, Map<String, Any?>>> =
        jdbc
            .sql(
                "SELECT action, before_state::text AS before, after_state::text AS after FROM audit.audit_entry " +
                    "WHERE entity_type = 'housekeeping.task' AND entity_id = :id " +
                    "AND action LIKE 'housekeeping.assignment-plan.%' " +
                    "ORDER BY occurred_at, id",
            ).param("id", task.toString())
            .query { rs, _ ->
                Triple(
                    rs.getString("action"),
                    rs.getString("before")?.let(::parse),
                    parse(rs.getString("after")),
                )
            }.list()

    private fun parse(json: String): Map<String, Any?> =
        JsonMapper
            .builder()
            .build()
            .readValue(json, Map::class.java)
            .mapKeys { it.key.toString() }

    private fun longName(index: Int): String = "${rooms.uniqueName("Área $index")} ".padEnd(AREA_NAME_LENGTH, 'x')

    private companion object {
        const val DAYS_AHEAD = 5L
        const val AREAS = 16
        const val AREA_NAME_LENGTH = 80
        const val MAX_BODY = 1000

        /** O tipo `TOUCH_UP` da carga (D5), só referenciado. */
        val TOUCH_UP: UUID = AssignmentPlanTestSupport.TOUCH_UP_TYPE
    }
}

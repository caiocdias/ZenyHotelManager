package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.expectInPlan
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.itemsOf
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.orderOf
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.unassignedOf
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsTestSupport
import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * O plano do dia pela API (M6 E03, Task 2; RF-GOV-03): a sugestão (`DRAFT`, `id`, versão no `ETag`, itens com tarefa,
 * camareira e posição, e as não atribuídas), só com as camareiras escaladas no setor de governança (D1-E03), o DND
 * (D4-E03), a chegada recalculada (D3-E03), a tarefa que já andou (Review Focus 4), a leitura, o ajuste e a tarefa
 * aberta de data anterior só no plano da data operacional (Review Focus 5).
 *
 * O banco é compartilhado e o plano nunca é apagado: cada teste manda o `If-Match` do plano existente, restringe as
 * camareiras às suas com `employeeIds` e afirma só sobre as próprias tarefas e camareiras.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class AssignmentPlansApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
    @Autowired private val roomsApi: RoomsApi,
    @Autowired private val reservationsApi: ReservationsApi,
    @Autowired mockMvc: MockMvc,
    @Autowired jsonMapper: JsonMapper,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)
    private val stays =
        HousekeepingStays(ReservationsTestSupport(iam, mockMvc, jsonMapper, jdbc), reservationsApi, hotel)

    @Test
    fun `suggest on a future date gives the DRAFT plan with id, ETag, items and the unassigned`() {
        val date = hotel.currentBusinessDate().plusDays(3)
        val ana = plans.attendant(date, name = plans.staff.unique("Ana"))
        val bia = plans.attendant(date, name = plans.staff.unique("Bia"))
        val type = housekeeping.createCleaningType()
        val first = id(housekeeping.requestTask(housekeeping.taskBody(type, housekeeping.createRoom(), date = "$date")))
        val second =
            id(housekeeping.requestTask(housekeeping.taskBody(type, housekeeping.createRoom(), date = "$date")))

        val response = plans.suggest(date, listOf(ana, bia))
        val body = with(iam) { response.ok() }

        body["date"].asString() shouldBe "$date"
        body["status"].asString() shouldBe "DRAFT"
        val etag = response.getHeader(HttpHeaders.ETAG)
        etag shouldNotBe null
        val items = itemsOf(body)
        items.keys shouldContainAll listOf(first, second)
        listOf(first, second).forEach { items.getValue(it).first shouldBeIn listOf(ana, bia) }
        listOf(ana, bia).forEach { attendant ->
            withClue("posições de $attendant") {
                val positions =
                    items.values
                        .filter { it.first == attendant }
                        .map { it.second }
                        .sorted()
                positions shouldBe (1..positions.size).toList()
            }
        }
        body["unassigned"].isArray shouldBe true

        // A leitura devolve o mesmo plano, com o mesmo ETag.
        val read = plans.plan(date)
        with(iam) { read.ok() } shouldBe body
        read.getHeader(HttpHeaders.ETAG) shouldBe etag
    }

    @Test
    fun `only the attendants scheduled on the date in the housekeeping department receive tasks`() {
        val date = hotel.currentBusinessDate().plusDays(4)
        val scheduled = plans.attendant(date)
        val dayOff = plans.attendant() // sem escala na data
        val otherDepartment = plans.attendant(date, department = plans.staff.createDepartment())
        val task =
            housekeeping.createCleaningType().let {
                id(housekeeping.requestTask(housekeeping.taskBody(it, housekeeping.createRoom(), date = "$date")))
            }

        val all = with(iam) { plans.suggest(date, null).ok() }
        val employees = itemsOf(all).values.map { it.first }
        employees shouldNotContain dayOff
        employees shouldNotContain otherDepartment
        expectInPlan(all, task)

        // employeeIds restringe ao subconjunto: a tarefa vai para a única camareira dada.
        val (only, _) = plans.suggested(date, listOf(scheduled))
        itemsOf(only).getValue(task).first shouldBe scheduled
        itemsOf(only).values.map { it.first }.toSet() shouldBe setOf(scheduled)
    }

    @Test
    fun `a STAYOVER in a do not disturb room stays unassigned, and a task that already moved is left out`() {
        val date = hotel.currentBusinessDate().plusDays(2)
        val attendant = plans.attendant(date)
        val dndRoom = housekeeping.createRoom()
        roomsApi.markOccupied(RoomId(dndRoom), 1, RoomStatusSource.MANUAL)
        roomsApi.setServiceFlag(RoomId(dndRoom), GuestServiceFlag.DO_NOT_DISTURB, RoomStatusSource.MANUAL)
        val stayover = plans.insertTask(date, room = dndRoom, source = "STAYOVER")
        val adHocInDnd =
            plans.insertTask(date, room = dndRoom, source = "AD_HOC", type = housekeeping.createCleaningType())
        val inProgress = plans.insertTask(date, room = housekeeping.createRoom(), status = "IN_PROGRESS")

        val (plan, _) = plans.suggested(date, listOf(attendant))

        unassignedOf(plan) shouldContain stayover
        itemsOf(plan).keys shouldNotContain stayover
        itemsOf(plan).getValue(adHocInDnd).first shouldBe attendant
        itemsOf(plan).keys shouldNotContain inProgress
        unassignedOf(plan) shouldNotContain inProgress
    }

    @Test
    fun `the arrival is recalculated, a task stored without arrival in a room with an allocated arrival comes first`() {
        val date = hotel.currentBusinessDate().plusDays(5)
        val attendant = plans.attendant(date)
        val roomType = housekeeping.rooms.createRoomType()
        val arrivalRoom = housekeeping.rooms.createRoom(roomType)
        val (reservation, _) = stays.reservation(roomType, stays.planFor(roomType), arrival = date)
        reservationsApi.assignRoom(reservation, RoomId(arrivalRoom))
        val arriving = plans.insertTask(date, room = arrivalRoom, priority = 1, arrivalExpected = false)
        val urgent = plans.insertTask(date, room = housekeeping.createRoom(), priority = 5)

        val (plan, _) = plans.suggested(date, listOf(attendant))

        val order = orderOf(plan, attendant)
        order.indexOf(arriving) shouldBeLessThan order.indexOf(urgent)
        order.indexOf(arriving) shouldNotBe -1
    }

    @Test
    fun `PATCH with If-Match moves a task, adds one created after the suggestion and removes an item`() {
        val date = hotel.currentBusinessDate().plusDays(6)
        val ana = plans.attendant(date)
        val bia = plans.attendant(date)
        val type = housekeeping.createCleaningType()
        val moved = id(housekeeping.requestTask(housekeeping.taskBody(type, housekeeping.createRoom(), date = "$date")))
        val removed =
            id(housekeeping.requestTask(housekeeping.taskBody(type, housekeeping.createRoom(), date = "$date")))
        val (suggested, etag) = plans.suggested(date, listOf(ana))
        itemsOf(suggested).getValue(moved).first shouldBe ana
        val added = id(housekeeping.requestTask(housekeeping.taskBody(type, housekeeping.createRoom(), date = "$date")))

        val response =
            plans.adjust(
                date,
                mapOf(
                    "items" to
                        listOf(
                            mapOf("taskId" to moved, "employeeId" to bia),
                            mapOf("taskId" to added, "employeeId" to ana, "position" to 1),
                        ),
                    "remove" to listOf(removed),
                ),
                etag,
            )
        val adjusted = with(iam) { response.ok() }

        response.getHeader(HttpHeaders.ETAG) shouldNotBe etag
        adjusted["status"].asString() shouldBe "DRAFT"
        itemsOf(adjusted).getValue(moved).first shouldBe bia
        itemsOf(adjusted).getValue(added) shouldBe (ana to 1)
        itemsOf(adjusted).keys shouldNotContain removed
        unassignedOf(adjusted) shouldNotContain removed
        // As posições de cada camareira seguem de 1 a n.
        listOf(ana, bia).forEach { employee ->
            itemsOf(adjusted)
                .values
                .filter { it.first == employee }
                .map { it.second }
                .sorted() shouldBe
                (1..itemsOf(adjusted).values.count { it.first == employee }).toList()
        }
        with(iam) { plans.plan(date).ok() } shouldBe adjusted

        // Um novo suggest sobre o plano existente exige If-Match.
        with(iam) {
            iam
                .call(POST, "${AssignmentPlanTestSupport.PLANS}/$date/suggest", asAdmin(), mapOf("employeeIds" to null))
                .problem(428, "PRECONDITION_REQUIRED")
        }
    }

    @Test
    fun `an open task of an earlier date enters only the plan of the operational date, never tomorrow`() {
        val today = hotel.currentBusinessDate()
        val attendant = plans.attendant(today, today.plusDays(1))
        val late = plans.insertTask(today.minusDays(1), area = housekeeping.rooms.createArea())

        val (todayPlan, _) = plans.suggested(today, listOf(attendant))
        itemsOf(todayPlan).getValue(late).first shouldBe attendant

        val (tomorrowPlan, _) = plans.suggested(today.plusDays(1), listOf(attendant))
        itemsOf(tomorrowPlan).keys shouldNotContain late
        unassignedOf(tomorrowPlan) shouldNotContain late
    }

    private fun id(node: tools.jackson.databind.JsonNode): UUID = UUID.fromString(node["id"].asString())
}

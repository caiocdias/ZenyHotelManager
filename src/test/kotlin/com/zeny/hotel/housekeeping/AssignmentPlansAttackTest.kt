package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.PLANS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.PATCH
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * As recusas do plano do dia (M6 E03, Task 2): `If-Match` no plano existente (`428`/`412`), colaborador fora da escala
 * ou inexistente (D11-E03), tarefa que já andou ou fora do conteúdo da D2-E03, tarefa inexistente (H16), data fora da
 * janela ou malformada. O `PATCH` antes de existir plano (`404`) fica no `AssignmentPlanCreationTest`, no banco
 * isolado, porque aqui o plano de qualquer data da janela pode já existir.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AssignmentPlansAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)

    @Test
    fun `on an existing plan, suggest and PATCH without If-Match are 428 and with an old version 412`(): Unit =
        with(iam) {
            val date = hotel.currentBusinessDate().plusDays(7)
            val attendant = plans.attendant(date)
            val (_, etag) = plans.suggested(date, listOf(attendant))
            val (_, current) = plans.suggested(date, listOf(attendant))
            val body = mapOf("employeeIds" to listOf(attendant))

            call(POST, "$PLANS/$date/suggest", asAdmin(), body).problem(428, "PRECONDITION_REQUIRED")
            call(POST, "$PLANS/$date/suggest", asAdmin(), body, etag).problem(412, "PRECONDITION_FAILED")
            val adjust = mapOf("remove" to listOf(plans.insertTask(date, area = housekeeping.rooms.createArea())))
            plans.adjust(date, adjust, null).problem(428, "PRECONDITION_REQUIRED")
            plans.adjust(date, adjust, etag).problem(412, "PRECONDITION_FAILED")
            plans.etagOrNull(date) shouldBe current
        }

    @Test
    fun `an employee out of the schedule or unknown is 422 ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED, in suggest and PATCH`(): Unit =
        with(iam) {
            val date = hotel.currentBusinessDate().plusDays(7)
            val attendant = plans.attendant(date)
            val notScheduled = plans.attendant()
            val otherDepartment = plans.attendant(date, department = plans.staff.createDepartment())
            val (_, etag) = plans.suggested(date, listOf(attendant))
            val task = plans.insertTask(date, area = housekeeping.rooms.createArea())

            listOf(notScheduled, otherDepartment, UUID.randomUUID()).forEach { employee ->
                withClue(employee) {
                    plans
                        .suggest(date, listOf(attendant, employee))
                        .problem(422, "ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED")
                    plans
                        .adjust(date, mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to employee))), etag)
                        .problem(422, "ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED")
                }
            }
            plans.etagOrNull(date) shouldBe etag
        }

    @Test
    fun `a task that moved on or out of the plan content is 422, and an unknown task is 400`(): Unit =
        with(iam) {
            val date = hotel.currentBusinessDate().plusDays(7)
            val attendant = plans.attendant(date)
            val (_, etag) = plans.suggested(date, listOf(attendant))
            val area = housekeeping.rooms.createArea()
            val refused =
                listOf(
                    plans.insertTask(date, area = area, status = "IN_PROGRESS"),
                    plans.insertTask(date, room = housekeeping.createRoom(), status = "DONE"),
                    plans.insertTask(date.minusDays(1), area = area), // outra data, num plano futuro
                    plans.insertTask(date.plusDays(1), area = area),
                )
            refused.forEach { task ->
                withClue(task) {
                    plans
                        .adjust(date, mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to attendant))), etag)
                        .problem(422, "ASSIGNMENT_TASK_NOT_ASSIGNABLE")
                }
            }
            val unknown = mapOf("taskId" to UUID.randomUUID(), "employeeId" to attendant)
            plans.adjust(date, mapOf("items" to listOf(unknown)), etag).problem(400, "ASSIGNMENT_UNKNOWN_TASK")
            plans.adjust(date, mapOf("remove" to listOf(UUID.randomUUID())), etag).problem(400, "ASSIGNMENT_UNKNOWN_TASK")
            plans.etagOrNull(date) shouldBe etag
        }

    @Test
    fun `a date before the operational one or after 7 days ahead is 422, and a malformed date 400`(): Unit =
        with(iam) {
            val today = hotel.currentBusinessDate()
            listOf(today.minusDays(1), today.plusDays(8), today.plusYears(1000)).forEach { date ->
                withClue(date) {
                    call(POST, "$PLANS/$date/suggest", asAdmin(), mapOf("employeeIds" to null))
                        .problem(422, "ASSIGNMENT_PLAN_DATE_INVALID")
                    call(PATCH, "$PLANS/$date", asAdmin(), mapOf("remove" to listOf(UUID.randomUUID())), "\"0\"")
                        .problem(422, "ASSIGNMENT_PLAN_DATE_INVALID")
                }
            }
            listOf("2026-13-01", "amanha", "2026-1-3", "99999999-01-01").forEach { date ->
                withClue(date) {
                    call(POST, "$PLANS/$date/suggest", asAdmin(), mapOf("employeeIds" to null)).status shouldBe 400
                    call(GET, "$PLANS/$date", asAdmin()).status shouldBe 400
                    call(PATCH, "$PLANS/$date", asAdmin(), mapOf("remove" to emptyList<UUID>()), "\"0\"")
                        .status shouldBe 400
                }
            }
        }

    @Test
    fun `a body with a null id, a repeated task or no change is 400`(): Unit =
        with(iam) {
            val date = hotel.currentBusinessDate().plusDays(7)
            val attendant = plans.attendant(date)
            val (_, etag) = plans.suggested(date, listOf(attendant))
            val task = plans.insertTask(date, area = housekeeping.rooms.createArea())
            listOf(
                mapOf("remove" to listOf(null)),
                mapOf("items" to listOf(mapOf("taskId" to null))),
                mapOf("items" to listOf(mapOf("taskId" to task), mapOf("taskId" to task))),
                mapOf("items" to listOf(mapOf("taskId" to task)), "remove" to listOf(task)),
                mapOf("items" to listOf(mapOf("taskId" to task, "position" to 0))),
                emptyMap(),
            ).forEach { body ->
                withClue(body) { plans.adjust(date, body, etag).status shouldBe 400 }
            }
            call(POST, "$PLANS/$date/suggest", asAdmin(), mapOf("employeeIds" to listOf(null)), etag).status shouldBe
                400
            plans.etagOrNull(date) shouldBe etag
        }
}

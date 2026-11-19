package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.staff.ScheduleEntryKind
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A camareira não lê tarefas (M6 E04, Task 3; D2 e Review Focus 5 da E04): com o modelo Camareira, que tem
 * `housekeeping.tasks.execute` em `OWN` mas não `housekeeping.tasks.read`, a lista do dia, a tarefa, o plano do dia e
 * a inscrição no tópico `housekeeping` dão `403 PERMISSION_DENIED`, inclusive para as tarefas atribuídas a ela: o
 * tópico não lhe entrega mensagem de tarefa nenhuma. A Governanta, com `ALL`, segue lendo a lista, a tarefa e o plano
 * (a inscrição dela no tópico está no `HousekeepingTaskRealtimeTest`, com um cliente SSE de verdade).
 *
 * A inscrição usa o token de um login de verdade (a assinatura confere a sessão); a recusa vem antes de abrir o
 * stream, então o servlet simulado basta.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingAttendantReadTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotel: HotelApi,
    @Autowired jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping
    private val plans = execution.plans

    @Test
    fun `the attendant reads neither the day list, nor the task, nor the plan, nor the housekeeping topic`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val (room, task) = ownTask(attendant.employee)
            val planDate = planWith(attendant.employee)

            call(GET, "$TASKS?date=${hotel.currentBusinessDate()}&roomId=$room", auth).problem(403, PERMISSION_DENIED)
            call(GET, "$TASKS/$task", auth).problem(403, PERMISSION_DENIED)
            plans.plan(planDate, auth).problem(403, PERMISSION_DENIED)
            call(GET, "$STREAM?topics=housekeeping", bearer(attendant.user)).problem(403, PERMISSION_DENIED)
        }

    @Test
    fun `the housekeeper, with ALL, keeps reading the day list, the task and the plan`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val housekeeper = asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
            val (room, task) = ownTask(attendant.employee)
            val planDate = planWith(attendant.employee)

            val listed = call(GET, "$TASKS?date=${hotel.currentBusinessDate()}&roomId=$room", housekeeper).ok()
            listed["items"].values().map { it["id"].asString() } shouldContainExactly listOf("$task")
            call(GET, "$TASKS/$task", housekeeper).ok()["assignedEmployeeId"].asString() shouldBe
                "${attendant.employee}"
            withClue("o plano do dia") { plans.plan(planDate, housekeeper).ok() }
        }

    /** Uma tarefa da data operacional numa UH nova, atribuída à camareira [employee]: (UH, tarefa). */
    private fun ownTask(employee: UUID): Pair<UUID, UUID> {
        val room = housekeeping.createRoom()
        val body = housekeeping.taskBody(housekeeping.createCleaningType(), roomId = room)
        val task = UUID.fromString(housekeeping.requestTask(body)["id"].asString())
        execution.assign(task, employee)
        return room to task
    }

    /**
     * Um plano numa data futura da janela com uma tarefa da camareira [employee] (ela escalada na data); devolve a
     * data. Como no `AssignmentPlanPublishTest`, o rascunho tolera o plano alheio.
     */
    private fun planWith(employee: UUID): LocalDate {
        val date = hotel.currentBusinessDate().plusDays(DAYS_AHEAD)
        plans.staff.record(employee, ScheduleEntryKind.SHIFT, date, shiftId = shift())
        val task = plans.insertTask(date, room = housekeeping.createRoom())
        plans.draft(date, listOf(employee), listOf(task to employee))
        return date
    }

    private fun shift(): UUID =
        plans.staff.createShift(plans.housekeepingDepartment, LocalTime.of(7, 0), LocalTime.of(15, 0))

    /** O token de um login de verdade de [user], no cabeçalho `Authorization` (o stream confere a sessão). */
    private fun bearer(user: TestUser): RequestPostProcessor {
        val token = iam.loginOk(user, "device-${UuidV7.generate()}")["accessToken"].asString()
        return RequestPostProcessor {
            it.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            it
        }
    }

    private companion object {
        const val TASKS = HousekeepingTestSupport.TASKS
        const val STREAM = "/api/v1/stream"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val DAYS_AHEAD = 3L
    }
}

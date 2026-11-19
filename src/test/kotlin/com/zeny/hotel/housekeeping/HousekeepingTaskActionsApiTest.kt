package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.HousekeepingExecutionTestSupport.Companion.TASKS
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * As ações online da execução da tarefa de governança (M6 E04, Task 1): a camareira atribuída inicia, pausa, retoma,
 * recusa e tenta de novo com `If-Match`, recebendo a `HousekeepingTaskActionResponse` (sem `notes`, Review Focus 5) e
 * o `ETag` novo; a transição fora da SPEC §8.4 dá `409`, o início de tarefa de data futura dá `422` (D11) e cada ação
 * fica na trilha, sem o texto das `notes` (RN-13).
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTaskActionsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)

    @BeforeEach
    fun neutralizeGeneralTemplates() = execution.neutralizeGeneralHousekeepingTemplates()

    @Test
    fun `the assigned attendant starts, pauses, resumes, refuses and retries, with the new ETag each time`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task = execution.assignedTask(attendant.employee)
            val before = execution.housekeeping.etagOf("$TASKS/$task")

            val (afterStart, started) = execution.acted(task, "start", auth)
            afterStart shouldNotBe before
            val body = json(started)
            body["id"].asString() shouldBe task.toString()
            body["status"].asString() shouldBe "IN_PROGRESS"
            body["assignedEmployeeId"].asString() shouldBe attendant.employee.toString()
            body["startedAt"].isNull shouldBe false
            body["workedSeconds"].asLong() shouldBe 0
            afterStart shouldBe execution.housekeeping.etagOf("$TASKS/$task")

            json(execution.acted(task, "pause", auth).second)["status"].asString() shouldBe "PAUSED"
            json(execution.acted(task, "resume", auth).second)["status"].asString() shouldBe "IN_PROGRESS"
            json(execution.acted(task, "pause", auth).second)["status"].asString() shouldBe "PAUSED"

            val other = execution.assignedTask(attendant.employee)
            val refused = json(execution.acted(other, "refuse", auth, mapOf("reason" to "GUEST_REFUSED")).second)
            refused["status"].asString() shouldBe "REFUSED"
            refused["refusalReason"].asString() shouldBe "GUEST_REFUSED"
            val retried = json(execution.acted(other, "retry", auth).second)
            retried["status"].asString() shouldBe "ASSIGNED"
            retried["refusalReason"].isNull shouldBe true
            retried["assignedEmployeeId"].asString() shouldBe attendant.employee.toString()

            execution.housekeeping.auditActions("housekeeping.task", task) shouldContainExactly
                listOf(
                    "housekeeping.task.request",
                    "housekeeping.task.start",
                    "housekeeping.task.pause",
                    "housekeeping.task.resume",
                    "housekeeping.task.pause",
                )
            execution.housekeeping.auditActions("housekeeping.task", other) shouldContainExactly
                listOf("housekeeping.task.request", "housekeeping.task.refuse", "housekeeping.task.retry")
            val refuseState =
                jdbc
                    .sql(
                        "SELECT after_state::text FROM audit.audit_entry WHERE entity_type = 'housekeeping.task' " +
                            "AND entity_id = :id AND action = 'housekeeping.task.refuse'",
                    ).param("id", other.toString())
                    .query(String::class.java)
                    .single()
            json.readTree(refuseState).let {
                it["status"].asString() shouldBe "REFUSED"
                it["refusalReason"].asString() shouldBe "GUEST_REFUSED"
            }
        }

    @Test
    fun `the action response never carries the notes, nor their text, and the trail neither (Review Focus 5)`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val secret = "Segredo-${UUID.randomUUID()}"
            val task = execution.assignedTask(attendant.employee, notes = secret)
            // A consulta com tasks.read segue mostrando as observações (D2).
            execution.plans.task(task)["notes"].asString() shouldBe secret

            val responses =
                listOf(
                    execution.acted(task, "start", auth).second,
                    execution.acted(task, "pause", auth).second,
                    execution.acted(task, "resume", auth).second,
                )
            val other = execution.assignedTask(attendant.employee, notes = secret)
            val more =
                listOf(
                    execution.acted(other, "refuse", auth, mapOf("reason" to "DO_NOT_DISTURB")).second,
                    execution.acted(other, "retry", auth).second,
                )
            (responses + more).forEach { response ->
                val text = response.getContentAsString(Charsets.UTF_8)
                withClue(text) {
                    text shouldNotContain secret
                    json(response).propertyNames().asSequence().toList() shouldNotContain "notes"
                }
            }
            val trail =
                jdbc
                    .sql(
                        "SELECT coalesce(before_state::text, '') || coalesce(after_state::text, '') " +
                            "FROM audit.audit_entry WHERE entity_type = 'housekeeping.task' AND entity_id IN (:ids)",
                    ).param("ids", listOf(task.toString(), other.toString()))
                    .query(String::class.java)
                    .list()
            trail.forEach { it shouldNotContain secret }
        }

    @Test
    fun `an action outside the SPEC 8_4 transitions is 409 TRANSITION_NOT_ALLOWED, and nothing changes`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task = execution.assignedTask(attendant.employee)
            val refused =
                listOf(
                    "pause" to null,
                    "resume" to null,
                    "retry" to null,
                )
            refused.forEach { (action, body) ->
                withClue(action) {
                    val problem =
                        execution
                            .act(
                                task,
                                action,
                                auth,
                                body = body,
                            ).problem(409, "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED")
                    problem["taskStatus"].asString() shouldBe "ASSIGNED"
                    problem["action"].asString() shouldBe action.uppercase()
                }
            }
            execution.acted(task, "start", auth)
            execution
                .act(task, "start", auth)
                .problem(409, "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED")["taskStatus"]
                .asString() shouldBe "IN_PROGRESS"
            execution
                .act(task, "refuse", auth, body = mapOf("reason" to "GUEST_REFUSED"))
                .problem(409, "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED")
            execution.plans.task(task)["status"].asString() shouldBe "IN_PROGRESS"
            execution.housekeeping.auditActions("housekeeping.task", task) shouldContainExactly
                listOf("housekeeping.task.request", "housekeeping.task.start")

            // A tarefa PENDING (sem camareira) também não começa, nem para a governanta com ALL.
            val pending = execution.pendingTask()
            execution.act(pending, "start", asAdmin()).problem(409, "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED")
        }

    @Test
    fun `start of a task of a future date is 422 NOT_DUE, and nothing changes (D11)`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val tomorrow = hotel.currentBusinessDate().plusDays(1)
            val body =
                execution.housekeeping.taskBody(
                    execution.housekeeping.createCleaningType(),
                    roomId = execution.housekeeping.createRoom(),
                    date = tomorrow.toString(),
                )
            val task = UUID.fromString(execution.housekeeping.requestTask(body)["id"].asString())
            execution.assign(task, attendant.employee)

            execution.act(task, "start", auth).problem(422, "HOUSEKEEPING_TASK_NOT_DUE")
            execution.plans.task(task)["status"].asString() shouldBe "ASSIGNED"
            // A recusa da tarefa de amanhã vale (só o início espera a data); devolve a tarefa à camareira no fim.
            execution.acted(task, "refuse", auth, mapOf("reason" to "GUEST_REFUSED"))
        }

    @Test
    fun `the If-Match is required (428), a stale one is 412, and a reason outside the closed enum is 400`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val auth = asTestUser(attendant.user)
            val task = execution.assignedTask(attendant.employee)

            execution.act(task, "start", auth, ifMatch = null).problem(428, "PRECONDITION_REQUIRED")
            execution.act(task, "start", auth, ifMatch = "\"999\"").problem(412, "PRECONDITION_FAILED")
            listOf(mapOf("reason" to "LOST"), mapOf("reason" to null), emptyMap<String, Any?>()).forEach { body ->
                withClue(body) { execution.act(task, "refuse", auth, body = body).problem(400, "VALIDATION_FAILED") }
            }
            execution.plans.task(task)["status"].asString() shouldBe "ASSIGNED"
        }
}

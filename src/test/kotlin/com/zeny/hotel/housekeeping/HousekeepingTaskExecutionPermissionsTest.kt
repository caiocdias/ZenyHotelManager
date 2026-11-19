package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * A permissão de execução das tarefas de governança (M6 E04, Task 1, D1): `housekeeping.tasks.execute`, escopos `OWN`
 * e `ALL`, não sensível, com `OWN` no modelo Camareira e `ALL` nos modelos Governanta e Gerente. A camareira com `OWN`
 * age só na tarefa atribuída a ela: a tarefa alheia dá `403` antes de `404`, `409` e `412`, e a inexistente dá o
 * **mesmo** `403` (código e `detail`), sem revelar se existe; com `ALL`, a governanta age em qualquer tarefa e a
 * inexistente é `404`; sem a permissão, `403 PERMISSION_DENIED`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingTaskExecutionPermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)

    @Test
    fun `execute is OWN and ALL, not sensitive, OWN for the attendant, ALL for housekeeper and manager`() {
        val permission = definition(HousekeepingPermissions.TASKS_EXECUTE)
        permission.code.value shouldBe "housekeeping.tasks.execute"
        permission.scopes shouldBe setOf(OWN, ALL)
        permission.sensitive shouldBe false
        jdbc
            .sql("SELECT sensitive, module FROM iam.permission WHERE code = :code AND obsolete_at IS NULL")
            .param("code", permission.code.value)
            .query { rs, _ -> rs.getBoolean("sensitive") to rs.getString("module") }
            .single() shouldBe (false to "housekeeping")
        jdbc
            .sql(
                """
                SELECT r.template_key, g.scope FROM iam.role r
                JOIN iam.role_grant g ON g.role_id = r.id
                WHERE r.template_key IS NOT NULL AND g.permission_code = :code
                """.trimIndent(),
            ).param("code", permission.code.value)
            .query { rs, _ -> rs.getString("template_key") to rs.getString("scope") }
            .list() shouldContainExactlyInAnyOrder
            listOf(
                "ADMINISTRATOR" to "ALL",
                "MANAGER" to "ALL",
                "HOUSEKEEPER" to "ALL",
                "ROOM_ATTENDANT" to "OWN",
            )
        // A camareira não consulta as tarefas (D2): tasks.read segue só com ALL, fora do modelo dela.
        definition(HousekeepingPermissions.TASKS_READ).templates.keys shouldBe
            setOf(RoleTemplate.MANAGER, RoleTemplate.HOUSEKEEPER, RoleTemplate.RECEPTIONIST)
    }

    @Test
    fun `with OWN, a foreign task is 403 before 404, 409 and 412, and a missing one has the same body`(): Unit =
        with(iam) {
            val mine = execution.attendant()
            val other = execution.attendant()
            val auth = asTestUser(mine.user)
            val foreign = execution.assignedTask(other.employee)
            val foreignStarted = execution.assignedTask(other.employee)
            execution.acted(foreignStarted, "start", asTestUser(other.user))
            val unassigned = execution.pendingTask()

            val outOfScope =
                listOf(
                    execution.act(foreign, "start", auth), // passaria
                    execution.act(foreign, "start", auth, ifMatch = "\"999\""), // 412
                    execution.act(foreign, "pause", auth), // 409
                    execution.act(foreignStarted, "start", auth), // 409
                    execution.act(foreign, "refuse", auth, body = mapOf("reason" to "GUEST_REFUSED")),
                    execution.act(foreign, "retry", auth), // 409
                    execution.act(unassigned, "start", auth), // sem camareira: 409
                )
            val missing = UUID.randomUUID()
            val absent =
                listOf("start", "pause", "resume", "retry").map {
                    execution.act(missing, it, auth, ifMatch = "\"0\"")
                } + execution.act(missing, "refuse", auth, ifMatch = "\"0\"", body = mapOf("reason" to "GUEST_REFUSED"))

            val details =
                (outOfScope + absent).map { response ->
                    response.problem(403, "RESOURCE_OUT_OF_SCOPE")["detail"].asString()
                }
            withClue(details) { details.toSet().size shouldBe 1 }
            execution.plans.task(foreign)["status"].asString() shouldBe "ASSIGNED"
            execution.plans.task(foreignStarted)["status"].asString() shouldBe "IN_PROGRESS"

            // A dela, ela executa.
            val own = execution.assignedTask(mine.employee)
            execution.acted(own, "start", auth)
        }

    @Test
    fun `with ALL, the housekeeper acts on any task, and a missing one is 404`(): Unit =
        with(iam) {
            val housekeeper = asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))
            val attendant = execution.attendant()
            val task = execution.assignedTask(attendant.employee)

            execution.acted(task, "start", housekeeper)
            execution.acted(task, "pause", housekeeper)
            execution.acted(task, "resume", housekeeper)
            val other = execution.assignedTask(attendant.employee)
            execution.acted(other, "refuse", housekeeper, mapOf("reason" to "DO_NOT_DISTURB"))
            execution.acted(other, "retry", housekeeper)

            execution
                .act(UUID.randomUUID(), "start", housekeeper, ifMatch = "\"0\"")
                .problem(404, "HOUSEKEEPING_TASK_NOT_FOUND")
        }

    @Test
    fun `without the permission every action is 403 PERMISSION_DENIED, before any 404, 409 or 412`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val task = execution.assignedTask(attendant.employee)
            val reader = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.TASKS_READ), ALL)))
            val nobody = asTestUser(createUser())

            listOf(reader, nobody).forEach { auth ->
                listOf(
                    execution.act(task, "start", auth),
                    execution.act(task, "start", auth, ifMatch = "\"999\""),
                    execution.act(task, "pause", auth),
                    execution.act(task, "resume", auth),
                    execution.act(task, "retry", auth),
                    execution.act(task, "refuse", auth, body = mapOf("reason" to "GUEST_REFUSED")),
                    execution.act(UUID.randomUUID(), "start", auth, ifMatch = "\"0\""),
                ).forEach { it.problem(403, "PERMISSION_DENIED") }
            }
            execution.plans.task(task)["status"].asString() shouldBe "ASSIGNED"
        }
}

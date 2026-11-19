package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/**
 * A permissão de inspeção (M6 E05, Task 1, D1): `housekeeping.inspections.perform`, só `ALL`, não sensível, nos
 * modelos Governanta e Gerente; a Camareira não a tem. Sem ela, o `start` e o `complete` da inspeção dão `403` antes de
 * `404`, `409` e `412`, inclusive para quem limpou a tarefa (Review Focus 3); com ela, as recusas de estado.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class HousekeepingInspectionPermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val execution = HousekeepingExecutionTestSupport(iam, jdbc, staffApi)
    private val housekeeping = execution.housekeeping

    @BeforeEach
    fun neutralizeGeneralTemplates() {
        execution.neutralizeGeneralHousekeepingTemplates()
        execution.neutralizeGeneralInspectionTemplates()
    }

    @Test
    fun `inspections perform is ALL only, not sensitive, for housekeeper and manager, not for the attendant`() {
        val permission = definition(HousekeepingPermissions.INSPECTIONS_PERFORM)
        permission.code.value shouldBe "housekeeping.inspections.perform"
        permission.scopes shouldBe setOf(ALL)
        permission.sensitive shouldBe false
        permission.templates shouldBe
            mapOf(RoleTemplate.MANAGER to TemplateGrant(ALL), RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL))
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
            listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL", "HOUSEKEEPER" to "ALL")
    }

    @Test
    fun `without the permission start and complete are 403 before 404, 409 and 412, even for who cleaned`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val cleaner = asTestUser(attendant.user)
            val awaiting = awaitingInspection(attendant, cleaner)
            val notAwaiting = execution.assignedTask(attendant.employee)
            val opened = awaitingInspection(attendant, cleaner)
            execution.acted(opened, "inspection/start", inspector())
            val missing = UUID.randomUUID()
            val reader = asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.TASKS_READ), ALL)))

            listOf(cleaner, reader).forEach { user ->
                listOf("inspection/start", "inspection/complete").forEach { action ->
                    withClue(action) {
                        val body = if (action == "inspection/complete") APPROVE else null
                        execution.act(awaiting, action, user, body = body).problem(403, "PERMISSION_DENIED")
                        execution.act(awaiting, action, user, "\"999\"", body).problem(403, "PERMISSION_DENIED")
                        execution.act(notAwaiting, action, user, body = body).problem(403, "PERMISSION_DENIED")
                        execution.act(opened, action, user, body = body).problem(403, "PERMISSION_DENIED")
                        execution.act(missing, action, user, "\"0\"", body).problem(403, "PERMISSION_DENIED")
                    }
                }
            }
            execution.plans.task(awaiting)["status"].asString() shouldBe "AWAITING_INSPECTION"
        }

    @Test
    fun `with the permission the state refusals come out - 404, 409, 412 and 428`(): Unit =
        with(iam) {
            val attendant = execution.attendant()
            val awaiting = awaitingInspection(attendant, asTestUser(attendant.user))
            val notAwaiting = execution.assignedTask(attendant.employee)
            val inspector = inspector()

            listOf("inspection/start", "inspection/complete").forEach { action ->
                withClue(action) {
                    val body = if (action == "inspection/complete") APPROVE else null
                    execution
                        .act(UUID.randomUUID(), action, inspector, "\"0\"", body)
                        .problem(404, "HOUSEKEEPING_TASK_NOT_FOUND")
                    execution
                        .act(notAwaiting, action, inspector, body = body)
                        .problem(409, "HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED")
                    execution.act(awaiting, action, inspector, "\"999\"", body).status shouldBe 412
                    execution.act(awaiting, action, inspector, null, body).status shouldBe 428
                }
            }
        }

    /** Uma tarefa de tipo com inspeção, limpa pela [attendant]: `AWAITING_INSPECTION`. */
    private fun awaitingInspection(
        attendant: HousekeepingExecutionTestSupport.Attendant,
        auth: RequestPostProcessor,
    ): UUID {
        val type = housekeeping.createCleaningType(housekeeping.cleaningTypeBody(requiresInspection = true))
        val body = housekeeping.taskBody(type, roomId = housekeeping.createRoom())
        val task = UUID.fromString(housekeeping.requestTask(body)["id"].asString())
        execution.assign(task, attendant.employee)
        execution.acted(task, "start", auth)
        execution.acted(task, "complete", auth)
        return task
    }

    private fun inspector(): RequestPostProcessor = iam.asTestUser(execution.userWithTemplate(RoleTemplate.HOUSEKEEPER))

    private companion object {
        val APPROVE = mapOf("result" to "APPROVED")
    }
}

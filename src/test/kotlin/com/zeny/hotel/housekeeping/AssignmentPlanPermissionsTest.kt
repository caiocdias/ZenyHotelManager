package com.zeny.hotel.housekeeping

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.housekeeping.AssignmentPlanTestSupport.Companion.PLANS
import com.zeny.hotel.housekeeping.HousekeepingTestSupport.Companion.definition
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Permissões e trilha do plano do dia (M6 E03, Task 2; D10-E03): `housekeeping.assignments.manage`, só `ALL`, não
 * sensível, nos modelos Gerente e Governanta; sem ela, `suggest` e `PATCH` dão `403` antes de qualquer recusa de
 * estado; a leitura exige `housekeeping.tasks.read`; a trilha registra a sugestão e o ajuste.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class AssignmentPlanPermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hotel: HotelApi,
    @Autowired staffApi: StaffApi,
) {
    private val housekeeping = HousekeepingTestSupport(iam, jdbc)
    private val plans = AssignmentPlanTestSupport(iam, jdbc, staffApi)

    @Test
    fun `the manage permission is ALL only, not sensitive, for manager and housekeeper`() {
        val definition = definition(HousekeepingPermissions.ASSIGNMENTS_MANAGE)
        definition.code.value shouldBe "housekeeping.assignments.manage"
        definition.scopes shouldBe setOf(ALL)
        definition.sensitive shouldBe false
        jdbc
            .sql(
                "SELECT r.template_key, g.scope FROM iam.role r JOIN iam.role_grant g ON g.role_id = r.id " +
                    "WHERE r.template_key IS NOT NULL AND g.permission_code = :code",
            ).param("code", "housekeeping.assignments.manage")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list() shouldContainExactlyInAnyOrder
            listOf("ADMINISTRATOR" to "ALL", "MANAGER" to "ALL", "HOUSEKEEPER" to "ALL")
    }

    @Test
    fun `without manage, suggest, PATCH and publish are 403 before any 400, 404, 412, 422 or 428`(): Unit =
        with(iam) {
            val reader =
                asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.TASKS_READ), ALL)))
            val today = hotel.currentBusinessDate()
            val date = today.plusDays(1)
            val attendant = plans.attendant(date)
            plans.suggested(date, listOf(attendant))
            val adjust = mapOf("remove" to listOf(UUID.randomUUID()))
            listOf(
                call(POST, "$PLANS/$date/suggest", reader, mapOf("employeeIds" to null)), // 428
                call(POST, "$PLANS/$date/suggest", reader, mapOf("employeeIds" to null), "\"999\""), // 412
                call(POST, "$PLANS/$date/suggest", reader, mapOf("employeeIds" to listOf(UUID.randomUUID())), "\"0\""),
                call(POST, "$PLANS/${today.minusDays(1)}/suggest", reader, mapOf("employeeIds" to null)), // 422
                plans.adjust(date, adjust, null, reader), // 428
                plans.adjust(date, adjust, "\"999\"", reader), // 412
                plans.adjust(date, adjust, plans.etagOrNull(date), reader), // 400 UNKNOWN_TASK
                plans.adjust(today.minusDays(1), adjust, "\"0\"", reader), // 422
                plans.adjust(date, emptyMap(), "\"0\"", reader), // 400
                plans.publish(date, null, reader), // 428
                plans.publish(date, "\"999\"", reader), // 412
                plans.publish(today.minusDays(1), "\"0\"", reader), // 422
            ).forEachIndexed { index, response ->
                withClue(index) { response.problem(403, "PERMISSION_DENIED") }
            }
        }

    @Test
    fun `reading the plan needs tasks read, and the trail records the suggestion and the adjustment`(): Unit =
        with(iam) {
            val date = hotel.currentBusinessDate().plusDays(1)
            val attendant = plans.attendant(date)
            val (plan, etag) = plans.suggested(date, listOf(attendant))
            val nobody = asTestUser(createUser())
            plans.plan(date, nobody).problem(403, "PERMISSION_DENIED")
            val reader =
                asTestUser(createUserWith(TestGrant(definition(HousekeepingPermissions.TASKS_READ), ALL)))
            plans.plan(date, reader).ok()["id"] shouldBe plan["id"]

            val task = plans.insertTask(date, area = housekeeping.rooms.createArea())
            val adjusted =
                plans.adjust(date, mapOf("items" to listOf(mapOf("taskId" to task, "employeeId" to attendant))), etag)
            adjusted.ok()

            val planId = UUID.fromString(plan["id"].asString())
            housekeeping.auditActions("housekeeping.assignment-plan", planId) shouldContainAll
                listOf("housekeeping.assignment-plan.suggest", "housekeeping.assignment-plan.adjust")
        }
}

package com.zeny.hotel.maintenance

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.maintenance.MaintenanceTestSupport.Companion.WORK_ORDERS
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.TemplateGrant
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.GET
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * As permissões das ordens de serviço (M6 E07, Task 1, D1): `read` e `manage` (`OWN` e `ALL`), `request` (só `ALL`),
 * nenhuma sensível, nos modelos da D1. Sem a permissão, tudo é `403` antes de `404`, `409` e `412`; com `OWN`, só as
 * ordens que o usuário abriu ou de que é o responsável, e a alheia ou inexistente dá `403`.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class WorkOrderPermissionsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun definition(code: PermissionCode) = MaintenancePermissions.permissions.single { it.code == code }

    private fun userWithTemplate(template: RoleTemplate) =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", template.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    /** Um técnico com `read` e `manage` `OWN` (e `request`), vinculado a um colaborador novo. */
    private fun ownTechnician(): Pair<com.zeny.hotel.iam.TestUser, UUID> {
        val user =
            iam.createUserWith(
                TestGrant(definition(MaintenancePermissions.READ), OWN),
                TestGrant(definition(MaintenancePermissions.MANAGE), OWN),
                TestGrant(definition(MaintenancePermissions.REQUEST), ALL),
            )
        return user to support.employee(userId = user.id.value)
    }

    @Test
    fun `the three permissions, not sensitive, with the scopes and templates of D1`() {
        val read = definition(MaintenancePermissions.READ)
        val request = definition(MaintenancePermissions.REQUEST)
        val manage = definition(MaintenancePermissions.MANAGE)
        read.scopes shouldBe setOf(OWN, ALL)
        request.scopes shouldBe setOf(ALL)
        manage.scopes shouldBe setOf(OWN, ALL)
        val technicians =
            mapOf(RoleTemplate.MANAGER to TemplateGrant(ALL), RoleTemplate.MAINTENANCE to TemplateGrant(ALL))
        val requesters =
            technicians +
                mapOf(RoleTemplate.HOUSEKEEPER to TemplateGrant(ALL), RoleTemplate.RECEPTIONIST to TemplateGrant(ALL))
        read.templates shouldBe requesters
        request.templates shouldBe requesters
        manage.templates shouldBe technicians

        mapOf(
            read.code.value to listOf("ADMINISTRATOR", "MANAGER", "MAINTENANCE", "HOUSEKEEPER", "RECEPTIONIST"),
            request.code.value to listOf("ADMINISTRATOR", "MANAGER", "MAINTENANCE", "HOUSEKEEPER", "RECEPTIONIST"),
            manage.code.value to listOf("ADMINISTRATOR", "MANAGER", "MAINTENANCE"),
        ).forEach { (code, templates) ->
            withClue(code) {
                jdbc
                    .sql("SELECT sensitive, module FROM iam.permission WHERE code = :code AND obsolete_at IS NULL")
                    .param("code", code)
                    .query { rs, _ -> rs.getBoolean("sensitive") to rs.getString("module") }
                    .single() shouldBe (false to "maintenance")
                jdbc
                    .sql(
                        """
                        SELECT r.template_key, g.scope FROM iam.role r
                        JOIN iam.role_grant g ON g.role_id = r.id
                        WHERE r.template_key IS NOT NULL AND g.permission_code = :code
                        """.trimIndent(),
                    ).param("code", code)
                    .query { rs, _ -> rs.getString("template_key") to rs.getString("scope") }
                    .list() shouldContainExactlyInAnyOrder templates.map { it to "ALL" }
            }
        }
    }

    @Test
    fun `without permission everything is 403, before 404, 409 and 412`(): Unit =
        with(iam) {
            val nobody = asTestUser(createUser())
            val existing = support.openedId()
            val missing = UUID.randomUUID()
            val finished = support.openedId().also { support.act(it, "complete", body = emptyMap<String, Any>()).ok() }

            support.open(nobody).problem(403, "PERMISSION_DENIED")
            support
                .open(nobody, support.body(roomId = UUID.randomUUID(), origin = "CHECKLIST"))
                .problem(403, "PERMISSION_DENIED")
            call(GET, WORK_ORDERS, nobody).problem(403, "PERMISSION_DENIED")
            listOf(
                existing,
                missing,
            ).forEach { call(GET, "$WORK_ORDERS/$it", nobody).problem(403, "PERMISSION_DENIED") }
            listOf(existing, missing, finished).forEach { id ->
                listOf("start", "resume", "complete").forEach { action ->
                    withClue("$action $id") {
                        support
                            .act(
                                id,
                                action,
                                nobody,
                                emptyMap<String, Any>(),
                                "\"999\"",
                            ).problem(403, "PERMISSION_DENIED")
                    }
                }
                support.act(id, "hold", nobody, mapOf("reason" to "X"), "\"999\"").problem(403, "PERMISSION_DENIED")
                support.act(id, "cancel", nobody, mapOf("reason" to "X"), "\"999\"").problem(403, "PERMISSION_DENIED")
                support
                    .update(id, support.updateBody(support.order(existing)), nobody, "\"999\"")
                    .problem(403, "PERMISSION_DENIED")
            }
            support.order(existing)["status"].asString() shouldBe "OPEN"
        }

    @Test
    fun `with manage the state refusals come out - 404, 409, 412 and 428`(): Unit =
        with(iam) {
            val maintenance = asTestUser(userWithTemplate(RoleTemplate.MAINTENANCE))
            val fresh = support.openedId()
            support.act(UUID.randomUUID(), "start", maintenance, ifMatch = "\"0\"").problem(404, "WORK_ORDER_NOT_FOUND")
            support.act(fresh, "resume", maintenance).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
            support.act(fresh, "start", maintenance, ifMatch = "\"999\"").status shouldBe 412
            support.act(fresh, "start", maintenance, ifMatch = null).status shouldBe 428
        }

    @Test
    fun `the maintenance template sees and acts on every order, even one it did not open`(): Unit =
        with(iam) {
            val maintenance = asTestUser(userWithTemplate(RoleTemplate.MAINTENANCE))
            val manager = asTestUser(userWithTemplate(RoleTemplate.MANAGER))
            val opened = support.openedId()
            val unassigned = support.openedId()
            listOf(maintenance, manager).forEach { user ->
                support.listedIds(auth = user) shouldContain opened.toString()
                support.listedIds(auth = user) shouldContain unassigned.toString()
                call(GET, "$WORK_ORDERS/$opened", user).ok()["id"].asString() shouldBe opened.toString()
            }
            support.act(opened, "start", maintenance).ok()["status"].asString() shouldBe "IN_PROGRESS"
            support
                .update(
                    unassigned,
                    support.updateBody(support.order(unassigned), "laborCost" to "5.00"),
                    manager,
                ).ok()
            support.open(maintenance).status shouldBe 201
        }

    @Test
    fun `reception and housekeeping open and read, but never act`(): Unit =
        with(iam) {
            listOf(RoleTemplate.RECEPTIONIST, RoleTemplate.HOUSEKEEPER).forEach { template ->
                withClue(template.name) {
                    val user = asTestUser(userWithTemplate(template))
                    val id = support.openedId(user)
                    support.listedIds(auth = user) shouldContain id.toString()
                    call(GET, "$WORK_ORDERS/$id", user).ok()
                    val other = support.openedId()
                    call(GET, "$WORK_ORDERS/$other", user).ok()
                    listOf("start", "resume", "complete").forEach {
                        support.act(id, it, user, emptyMap<String, Any>()).problem(403, "PERMISSION_DENIED")
                    }
                    support.act(id, "hold", user, mapOf("reason" to "X")).problem(403, "PERMISSION_DENIED")
                    support.act(id, "cancel", user, mapOf("reason" to "X")).problem(403, "PERMISSION_DENIED")
                    support.update(id, support.updateBody(support.order(id)), user).problem(403, "PERMISSION_DENIED")
                    support.order(id)["status"].asString() shouldBe "OPEN"
                }
            }
        }

    @Test
    fun `an OWN technician lists only the orders he opened or is responsible for`(): Unit =
        with(iam) {
            val (user, employee) = ownTechnician()
            val tech = asTestUser(user)
            val room = support.room()
            val openedByHim = support.openedId(tech, support.body(roomId = room))
            val assignedToHim = support.openedId(body = support.body(roomId = room))
            val foreign = support.openedId(body = support.body(roomId = room))
            val assignedToOther = support.openedId(body = support.body(roomId = room))
            support
                .update(
                    assignedToHim,
                    support.updateBody(support.order(assignedToHim), "assigneeEmployeeId" to employee),
                ).ok()
            support
                .update(
                    assignedToOther,
                    support.updateBody(support.order(assignedToOther), "assigneeEmployeeId" to support.employee()),
                ).ok()

            val listed = support.listedIds("$WORK_ORDERS?roomId=$room", tech)
            listed shouldContainExactlyInAnyOrder listOf(openedByHim, assignedToHim).map { it.toString() }
            listed shouldNotContain foreign.toString()
            val firstPage = call(GET, "$WORK_ORDERS?roomId=$room&limit=1", tech).ok()
            firstPage["items"].size() shouldBe 1
            firstPage["nextCursor"].isNull shouldBe false
        }

    @Test
    fun `an OWN technician gets 403, not 404, on an order of someone else and on one that does not exist`(): Unit =
        with(iam) {
            val (user, employee) = ownTechnician()
            val tech = asTestUser(user)
            val foreign = support.openedId()
            val missing = UUID.randomUUID()

            call(GET, "$WORK_ORDERS/$foreign", tech).problem(403, "RESOURCE_OUT_OF_SCOPE")
            call(GET, "$WORK_ORDERS/$missing", tech).problem(403, "RESOURCE_OUT_OF_SCOPE")
            listOf(foreign, missing).forEach { id ->
                listOf("start", "resume", "complete").forEach { action ->
                    support
                        .act(id, action, tech, emptyMap<String, Any>(), "\"999\"")
                        .problem(403, "RESOURCE_OUT_OF_SCOPE")
                }
                support.act(id, "hold", tech, mapOf("reason" to "X"), "\"999\"").problem(403, "RESOURCE_OUT_OF_SCOPE")
                support.act(id, "cancel", tech, mapOf("reason" to "X"), "\"999\"").problem(403, "RESOURCE_OUT_OF_SCOPE")
            }
            support
                .update(foreign, support.updateBody(support.order(foreign)), tech, "\"999\"")
                .problem(403, "RESOURCE_OUT_OF_SCOPE")
            support.order(foreign)["status"].asString() shouldBe "OPEN"

            // A ordem que ele abriu e a que lhe foi atribuída ele lê e age.
            val mine = support.openedId(tech)
            support.act(mine, "start", tech).ok()["status"].asString() shouldBe "IN_PROGRESS"
            val assigned = support.openedId()
            support.update(assigned, support.updateBody(support.order(assigned), "assigneeEmployeeId" to employee)).ok()
            call(GET, "$WORK_ORDERS/$assigned", tech).ok()
            support.act(assigned, "start", tech).ok()["status"].asString() shouldBe "IN_PROGRESS"
            support.act(assigned, "hold", tech, mapOf("reason" to "Peça")).ok()["status"].asString() shouldBe "ON_HOLD"
            // Perdida a responsabilidade, a ordem passa a ser alheia.
            support.update(assigned, support.updateBody(support.order(assigned), "assigneeEmployeeId" to null)).ok()
            call(GET, "$WORK_ORDERS/$assigned", tech).problem(403, "RESOURCE_OUT_OF_SCOPE")
        }

    @Test
    fun `the opening ignores the responsible, the third party and the costs of the body`(): Unit =
        with(iam) {
            val employee = support.employee()
            val body =
                support.body(roomId = support.room()) +
                    mapOf(
                        "assigneeEmployeeId" to employee,
                        "thirdPartyName" to "Terceiro Intruso",
                        "laborCost" to "10.00",
                        "thirdPartyCost" to "20.00",
                        "partsCost" to "30.00",
                        "status" to "DONE",
                    )
            val created = support.opened(body = body)
            val id = UUID.fromString(created["id"].asString())
            created["assigneeEmployeeId"].isNull shouldBe true
            created["thirdPartyName"].isNull shouldBe true
            listOf("laborCost", "thirdPartyCost", "partsCost").forEach { created[it].asString() shouldBe "0.00" }
            created["status"].asString() shouldBe "OPEN"
            val row = support.row(id)
            row["assignee_employee_id"] shouldBe null
            row["third_party_name"] shouldBe null
            listOf("labor_cost", "third_party_cost", "parts_cost").forEach {
                (row[it] as java.math.BigDecimal).signum() shouldBe 0
            }
        }
}

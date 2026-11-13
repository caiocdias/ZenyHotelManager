package com.zeny.hotel.endofday

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import endofdayexample.EndOfDayExampleConfiguration
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Ataques às permissões do `endofday` (M6 E10, Task 2): a sensibilidade de `runs.execute` aciona de fato a aprovação de
 * superior, a definição não lista o Administrador e a sincronização do catálogo é estável.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, EndOfDayExampleConfiguration::class)
class EndOfDayPermissionsAttackTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val modules: List<ModulePermissions>,
) {
    private val read = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_READ }
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    @BeforeEach
    fun synchronizeCatalog() {
        synchronizer.synchronize()
    }

    @Test
    fun `a user with only runs read gets APPROVAL_REQUIRED for the execute action`() {
        val requester = iam.createUserWith(TestGrant(read, ALL))

        val problem = iam.run { execute(requester).problem(403, "APPROVAL_REQUIRED") }

        problem["permission"].asString() shouldBe "endofday.runs.execute"
        problem["action"].asString() shouldBe "endofday.example.execute"
    }

    @Test
    fun `a user with no endofday permission also gets APPROVAL_REQUIRED, not a plain denial`() {
        val requester = iam.createUser()

        iam.run { execute(requester).problem(403, "APPROVAL_REQUIRED") }
    }

    @Test
    fun `a user holding runs execute runs without approval`() {
        val manager = iam.createUserWith(TestGrant(execute, ALL))

        val body = iam.run { execute(manager).expectStatus(200) }.shouldNotBeNull()

        body["byApproval"].asBoolean() shouldBe false
    }

    @Test
    fun `a superior holding runs execute approves the action for a user without it`() {
        val requester = iam.createUserWith(TestGrant(read, ALL))
        val sessionId = UUID.randomUUID()
        val approver = iam.createUserWith(TestGrant(execute, ALL))
        iam.setPin(approver, "4321")

        val required = iam.run { execute(requester, sessionId).problem(403, "APPROVAL_REQUIRED") }
        val token = approve(requester, sessionId, required, approver, "4321").succeeded(201)["approvalToken"].asString()

        val body = iam.run { execute(requester, sessionId, token).expectStatus(200) }.shouldNotBeNull()
        body["byApproval"].asBoolean() shouldBe true
    }

    @Test
    fun `a superior holding only runs read cannot approve the execute action`() {
        val requester = iam.createUser()
        val sessionId = UUID.randomUUID()
        val onlyReader = iam.createUserWith(TestGrant(read, ALL))
        iam.setPin(onlyReader, "4321")

        val required = iam.run { execute(requester, sessionId).problem(403, "APPROVAL_REQUIRED") }

        iam.run { approve(requester, sessionId, required, onlyReader, "4321").problem(403, "APPROVER_NOT_ALLOWED") }
    }

    @Test
    fun `the definitions leave the administrator template implicit and no other role gets either permission`() {
        EndOfDayPermissions.permissions.forEach { (RoleTemplate.ADMINISTRATOR in it.templates) shouldBe false }
        read.templates.keys shouldContainExactlyInAnyOrder
            listOf(RoleTemplate.MANAGER, RoleTemplate.RECEPTIONIST, RoleTemplate.FINANCE)
        execute.templates.keys shouldContainExactlyInAnyOrder listOf(RoleTemplate.MANAGER)

        val holders =
            jdbc
                .sql(
                    """
                    SELECT DISTINCT r.template_key FROM iam.role r JOIN iam.role_grant g ON g.role_id = r.id
                    WHERE r.template_key IS NOT NULL AND g.permission_code LIKE 'endofday.%'
                    """.trimIndent(),
                ).query(String::class.java)
                .list()
        holders shouldContainExactlyInAnyOrder listOf("ADMINISTRATOR", "MANAGER", "RECEPTIONIST", "FINANCE")
    }

    @Test
    fun `synchronizing the catalog again keeps one row per permission and the same grants`() {
        val grantsBefore = grantCount()

        synchronizer.synchronize()
        synchronizer.synchronize()

        val rows =
            jdbc
                .sql("SELECT code FROM iam.permission WHERE code LIKE 'endofday.%'")
                .query(String::class.java)
                .list()
        rows shouldContainExactlyInAnyOrder listOf("endofday.runs.read", "endofday.runs.execute")
        grantCount() shouldBe grantsBefore
    }

    @Test
    fun `the endofday permissions come from exactly one module bean with the endofday prefix`() {
        val bean = modules.single { it.module == "endofday" }

        bean.permissions.map { it.code.value } shouldContainExactlyInAnyOrder
            listOf("endofday.runs.read", "endofday.runs.execute")
        bean.permissions.all { it.code.module == "endofday" } shouldBe true
    }

    private fun grantCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM iam.role_grant WHERE permission_code LIKE 'endofday.%'")
            .query(Int::class.java)
            .single()

    private fun execute(
        user: TestUser,
        sessionId: UUID = UUID.randomUUID(),
        token: String? = null,
    ): MockHttpServletResponse =
        iam.call(POST, "/api/v1/endofday-examples/execute", auth(user, sessionId), approvalToken = token)

    private fun auth(
        user: TestUser,
        sessionId: UUID,
    ): RequestPostProcessor = asUser(user.id.value, sessionId)

    private fun approve(
        requester: TestUser,
        sessionId: UUID,
        required: JsonNode,
        approver: TestUser,
        pin: String,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/auth/approvals",
            auth(requester, sessionId),
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "approverLogin" to approver.login,
                "approverPin" to pin,
                "reason" to "Motivo do teste",
            ),
        )

    private fun MockHttpServletResponse.succeeded(status: Int): JsonNode =
        iam.run { this@succeeded.expectStatus(status) }.shouldNotBeNull()
}

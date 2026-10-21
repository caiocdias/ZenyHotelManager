package com.zeny.hotel.iam

import authzexample.AuthzExampleConfiguration
import authzexample.ExamplePermissions
import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestAdmin
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeSortedBy
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/**
 * Autorização de ponta a ponta (M1 E02, ARCHITECTURE §8.2): catálogo sincronizado no boot,
 * modelos de role, porta `Authorization` com `require`, escopo `OWN` e limites, permissões efetivas
 * em cache invalidado por versão, `GET /permissions` e `GET /auth/me`. O módulo de exemplo
 * ([AuthzExampleConfiguration]) declara permissões e usa a porta como um módulo de negócio.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, AuthzExampleConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta com os beans de que precisam
class AuthorizationTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val modules: List<ModulePermissions>,
    @Autowired private val authorization: Authorization,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val flyway: Flyway,
) {
    private val tasksRead = ExamplePermissions.permissions.single { it.code == ExamplePermissions.TASKS_READ }
    private val tasksComplete = ExamplePermissions.permissions.single { it.code == ExamplePermissions.TASKS_COMPLETE }
    private val discounts = ExamplePermissions.permissions.single { it.code == ExamplePermissions.DISCOUNTS_APPLY }
    private val permissionsRead = IamPermissions.permissions.single { it.code == IamPermissions.PERMISSIONS_READ }
    private val rolesRead = IamPermissions.permissions.single { it.code == IamPermissions.ROLES_READ }

    /**
     * O banco é compartilhado entre os contextos de teste: um contexto sem o módulo de exemplo que
     * suba depois deste marca as permissões de exemplo como obsoletas. Sincronizar de novo devolve
     * o catálogo deste contexto.
     */
    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
        jdbc.sql("DELETE FROM test_support.example_task").update()
    }

    // --- Catálogo -----------------------------------------------------------------------------

    @Test
    fun `the catalog declared in code is in the table after boot`() {
        val rows = activeCodes()

        rows shouldContainAll (IamPermissions.permissions + ExamplePermissions.permissions).map { it.code.value }

        val usersManage =
            jdbc
                .sql(
                    "SELECT module, array_to_string(scopes, ',') AS scopes, sensitive, limits::text AS limits " +
                        "FROM iam.permission WHERE code = 'iam.users.manage'",
                ).query { rs, _ ->
                    listOf(
                        rs.getString("module"),
                        rs.getString("scopes"),
                        rs.getBoolean("sensitive").toString(),
                        rs.getString("limits"),
                    )
                }.single()
        usersManage shouldBe listOf("iam", "ALL", "true", "[]")

        val discountLimits =
            jdbc
                .sql("SELECT limits::text FROM iam.permission WHERE code = 'examples.discounts.apply'")
                .query(String::class.java)
                .single()
        jsonMapper.readTree(discountLimits).let {
            it[0]["key"].asString() shouldBe "maxPercent"
            it[0]["unit"].asString() shouldBe "PERCENT"
        }
    }

    @Test
    fun `a new permission appears after synchronizing, a removed one becomes obsolete and stops granting`() {
        val code = PermissionCode("synctest.things.read")
        val extra =
            object : ModulePermissions {
                override val module = "synctest"
                override val permissions = listOf(PermissionDefinition(code, "Ler coisas de teste", setOf(OWN, ALL)))
            }
        try {
            synchronizer.synchronize(modules + extra).inserted shouldContain code
            obsoleteAt(code).shouldBeNull()
            val user = iam.createUserWith(TestGrant(extra.permissions.single(), ALL))
            actingAs(user) { authorization.require(code).scope } shouldBe ALL
            permissionsOf(user) shouldContain code.value

            synchronizer.synchronize(modules).obsoleted shouldContainExactly setOf(code)

            obsoleteAt(code).shouldNotBeNull()
            shouldThrow<PermissionDeniedException> { actingAs(user) { authorization.require(code) } }
                .code.value shouldBe "PERMISSION_DENIED"
            permissionsOf(user) shouldNotContain code.value
            // A concessão continua gravada: voltando ao código, a permissão volta a valer.
            synchronizer.synchronize(modules + extra).updated shouldContain code
            obsoleteAt(code).shouldBeNull()
            actingAs(user) { authorization.require(code).scope } shouldBe ALL
        } finally {
            synchronizer.synchronize(modules)
        }
    }

    @Test
    @Suppress("LongMethod") // as listas exatas das concessões dos modelos, que crescem com os módulos
    fun `the role templates of SPEC 3_1 exist and the administrator holds every permission`() {
        val templates =
            jdbc
                .sql("SELECT template_key, name, system_role, active FROM iam.role WHERE template_key IS NOT NULL")
                .query { rs, _ ->
                    listOf(rs.getString(1), rs.getString(2), rs.getBoolean(3).toString(), rs.getBoolean(4).toString())
                }.list()
        templates shouldContainExactlyInAnyOrder
            RoleTemplate.entries.map {
                listOf(it.name, it.displayName, (it == RoleTemplate.ADMINISTRATOR).toString(), "true")
            }

        val active = activeCodes()
        grantsOf(RoleTemplate.ADMINISTRATOR) shouldContainExactlyInAnyOrder active.map { "$it:ALL" }
        val adminDiscount =
            jdbc
                .sql(
                    """
                    SELECT l.limit_key, l.unlimited FROM iam.role_grant_limit l
                    JOIN iam.role_grant g ON g.id = l.grant_id JOIN iam.role r ON r.id = g.role_id
                    WHERE r.template_key = 'ADMINISTRATOR' AND g.permission_code = 'examples.discounts.apply'
                    """.trimIndent(),
                ).query { rs, _ -> rs.getString(1) to rs.getBoolean(2) }
                .list()
        adminDiscount shouldContainExactly listOf("maxPercent" to true)

        grantsOf(RoleTemplate.MANAGER) shouldContainExactlyInAnyOrder
            listOf(
                "iam.permissions.read:ALL",
                "iam.roles.read:ALL",
                "iam.users.read:ALL",
                "iam.sessions.manage:ALL",
                "hotel.settings.read:ALL",
                "staff.employees.read:ALL",
                "staff.employees.manage:ALL",
                "staff.schedule.read:ALL",
                "staff.schedule.manage:ALL",
                "rooms.registry.read:ALL",
                "rooms.registry.manage:ALL",
                "rooms.status.read:ALL",
                "rooms.status.manage:ALL",
                "rooms.status.flag:ALL",
                "rooms.status.observe:ALL",
                "rooms.outoforder.read:ALL",
                "rooms.outoforder.manage:ALL",
                "rooms.outofservice.manage:ALL",
                "rooms.offline.read:ALL",
                "checklists.templates.read:ALL",
                "checklists.templates.manage:ALL",
                "checklists.executions.read:ALL",
                "checklists.executions.execute:ALL",
                "checklists.routines.read:ALL",
                "checklists.routines.manage:ALL",
                "checklists.reports.read:ALL",
                "guests.profiles.read:ALL",
                "guests.profiles.manage:ALL",
                "guests.documents.reveal:ALL",
                "guests.restrictions.manage:ALL",
                "guests.profiles.merge:ALL",
                "guests.companies.read:ALL",
                "guests.companies.manage:ALL",
                "guests.attachments.read:ALL",
                "guests.attachments.manage:ALL",
                "guests.data.export:ALL",
                "guests.data.anonymize:ALL",
                "billing.codes.read:ALL",
                "billing.codes.manage:ALL",
                "billing.folios.read:ALL",
                "billing.folios.manage:ALL",
                "billing.folios.reopen:ALL",
                "billing.postings.create:ALL",
                "billing.postings.reverse:ALL",
                "billing.postings.adjust:ALL",
                "billing.routing.manage:ALL",
                "billing.postings.transfer:ALL",
                "billing.servicecharge.remove:ALL",
                "billing.cashpoints.manage:ALL",
                "billing.cashier.operate:ALL",
                "billing.shifts.read:ALL",
                "billing.shifts.review:ALL",
                "billing.payments.receive:ALL",
                "billing.payments.refund:ALL",
                "rates.plans.read:ALL",
                "rates.plans.manage:ALL",
                "rates.discounts.apply:ALL",
                "rates.prices.manage:ALL",
                "rates.prices.override:ALL",
                "rates.restrictions.manage:ALL",
                "reservations.availability.read:ALL",
                "reservations.inventory.manage:ALL",
                "reservations.reservations.read:ALL",
                "reservations.reservations.manage:ALL",
                "reservations.penalties.waive:ALL",
                "reservations.reservations.reactivate:ALL",
            )
        grantsOf(RoleTemplate.ROOM_ATTENDANT) shouldContainExactlyInAnyOrder
            listOf(
                "staff.schedule.read:OWN",
                "rooms.offline.read:ALL",
                "checklists.executions.read:OWN",
                "checklists.executions.execute:OWN",
            )

        // O administrador inicial tem a role Administrador e, com ela, todas as permissões vigentes.
        val me = iam.json(getMe(asUser(adminId())))
        me["roles"].values().map { it["name"].asString() } shouldContain "Administrador"
        me["permissions"].values().map {
            "${it["code"].asString()}:${it["scope"].asString()}"
        } shouldContainExactlyInAnyOrder
            active.map { "$it:ALL" }
    }

    @Test
    fun `the seed only adds to templates the administrator did not edit`() {
        val managerId =
            jdbc.sql("SELECT id FROM iam.role WHERE template_key = 'MANAGER'").query(UUID::class.java).single()
        try {
            iam.revokeGrant(
                com.zeny.hotel.iam.domain
                    .RoleId(managerId),
                IamPermissions.USERS_READ,
            )
            synchronizer.synchronize()
            grantsOf(RoleTemplate.MANAGER) shouldNotContain "iam.users.read:ALL"
        } finally {
            jdbc.sql("UPDATE iam.role SET customized = false WHERE id = :id").param("id", managerId).update()
            synchronizer.synchronize()
        }
        grantsOf(RoleTemplate.MANAGER) shouldContain "iam.users.read:ALL"
    }

    // --- require e cache --------------------------------------------------------------------

    @Test
    fun `without the permission the request is refused with 403 and a stable code`() {
        val token = accessToken(iam.createUser())

        with(iam) { listPermissions(token).problem(403, "PERMISSION_DENIED") }
        val granted = iam.createUserWith(TestGrant(permissionsRead, ALL))
        listPermissions(accessToken(granted)).status shouldBe 200
    }

    @Test
    fun `removing a role from the user takes the permission away on the next request, without a new login`() {
        val user = iam.createUser()
        val token = accessToken(user)
        val role = iam.createRole(TestGrant(permissionsRead, ALL))

        listPermissions(token).status shouldBe 403
        iam.assignRole(user, role)
        listPermissions(token).status shouldBe 200
        iam.unassignRole(user, role)
        with(iam) { listPermissions(token).problem(403, "PERMISSION_DENIED") }
    }

    @Test
    fun `revoking a grant or deactivating the role takes effect on the next request`() {
        val user = iam.createUser()
        val token = accessToken(user)
        val first = iam.createRole(TestGrant(permissionsRead, ALL), TestGrant(rolesRead, ALL))
        iam.assignRole(user, first)
        listPermissions(token).status shouldBe 200

        iam.revokeGrant(first, IamPermissions.PERMISSIONS_READ)
        listPermissions(token).status shouldBe 403

        val second = iam.createRole(TestGrant(permissionsRead, ALL))
        iam.assignRole(user, second)
        listPermissions(token).status shouldBe 200
        iam.deactivateRole(second)
        listPermissions(token).status shouldBe 403
    }

    @Test
    fun `deactivating the user takes the permissions away at once`() {
        val user = iam.createUserWith(TestGrant(permissionsRead, ALL))
        val token = accessToken(user)
        listPermissions(token).status shouldBe 200

        iam.deactivate(user)

        with(iam) { listPermissions(token).problem(403, "PERMISSION_DENIED") }
    }

    @Test
    fun `the cached permissions are reused until the authorization version changes`() {
        val user = iam.createUser()
        val token = accessToken(user)
        val role = iam.createRole(TestGrant(permissionsRead, ALL))
        iam.assignRole(user, role)
        listPermissions(token).status shouldBe 200

        // Tira a role sem os triggers: a versão não muda, e o cache segue valendo. Desligar os
        // triggers exige o dono dos schemas (superusuário no container); o papel da aplicação não pode.
        flyway.configuration.dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
            connection.prepareStatement("DELETE FROM iam.user_role WHERE user_id = ?").use {
                it.setObject(1, user.id.value)
                it.executeUpdate() shouldBe 1
            }
            connection.commit()
        }
        listPermissions(token).status shouldBe 200

        jdbc
            .sql("UPDATE iam.authorization_version SET value = nextval('iam.authorization_version_seq') WHERE id = 1")
            .update()
        listPermissions(token).status shouldBe 403
    }

    // --- Escopo OWN e limites ------------------------------------------------------------------

    @Test
    fun `with OWN scope a query returns only the user's records and a command on someone else's record is 403`() {
        val owner = iam.createUserWith(TestGrant(tasksRead, OWN), TestGrant(tasksComplete, OWN))
        val other = iam.createUser()
        val assignedToOwner = task(assignee = owner.id, createdBy = other.id)
        val ofOther = task(assignee = other.id, createdBy = other.id)
        val createdByOwner = task(assignee = null, createdBy = owner.id)
        val createdByOwnerForOther = task(assignee = other.id, createdBy = owner.id)

        listTasks(owner) shouldContainExactlyInAnyOrder listOf(assignedToOwner, createdByOwner, createdByOwnerForOther)
        actingAs(owner) { authorization.scopeFilter(ExamplePermissions.TASKS_READ) } shouldBe ScopeFilter.Own(owner.id)

        with(iam) { completeTask(owner, ofOther).problem(403, "RESOURCE_OUT_OF_SCOPE") }
        isCompleted(ofOther) shouldBe false
        completeTask(owner, assignedToOwner).status shouldBe 200
        completeTask(owner, createdByOwnerForOther).status shouldBe 200
        isCompleted(assignedToOwner) shouldBe true
        shouldThrow<PermissionDeniedException> {
            actingAs(owner) { authorization.require(ExamplePermissions.TASKS_COMPLETE, OwnedResource.of(other.id)) }
        }.code.value shouldBe "RESOURCE_OUT_OF_SCOPE"
    }

    @Test
    fun `with ALL scope, or OWN plus ALL, every record is reachable`() {
        val other = iam.createUser()
        val tasks = listOf(task(other.id, other.id), task(null, other.id))
        val all = iam.createUserWith(TestGrant(tasksRead, ALL), TestGrant(tasksComplete, ALL))
        val both = iam.createUserWith(TestGrant(tasksRead, OWN), TestGrant(tasksComplete, OWN))
        iam.assignRole(both, iam.createRole(TestGrant(tasksRead, ALL), TestGrant(tasksComplete, ALL)))

        listTasks(all) shouldContainExactlyInAnyOrder tasks
        listTasks(both) shouldContainExactlyInAnyOrder tasks
        completeTask(all, tasks[0]).status shouldBe 200
        completeTask(both, tasks[1]).status shouldBe 200
        actingAs(both) { authorization.scopeFilter(ExamplePermissions.TASKS_READ) } shouldBe ScopeFilter.All
    }

    @Test
    fun `a query without the permission is refused, not emptied`() {
        val user = iam.createUser()
        task(user.id, user.id)

        with(iam) { listTasksResponse(user).problem(403, "PERMISSION_DENIED") }
    }

    @Test
    fun `the limit read through the port is the largest among the roles`() {
        val user = iam.createUserWith(TestGrant(discounts, ALL, mapOf("maxPercent" to percent("5"))))
        iam.assignRole(user, iam.createRole(TestGrant(discounts, ALL, mapOf("maxPercent" to percent("12.5")))))
        val withoutValue = iam.createUserWith(TestGrant(discounts, ALL))

        discountLimit(asUser(user.id.value)) shouldBe (false to "12.5")
        discountLimit(asUser(withoutValue.id.value)) shouldBe (false to "0")
        discountLimit(asUser(adminId())) shouldBe (true to null)
        with(iam) {
            mockMvc
                .get("/api/v1/authz-examples/discount-limit") { with(asUser(iam.createUser().id.value)) }
                .andReturn()
                .response
                .problem(403, "PERMISSION_DENIED")
        }
    }

    // --- Token e GET /auth/me -------------------------------------------------------------------

    @Test
    fun `the access token carries no permission, and GET auth me lists the effective ones`() {
        val user = iam.createUserWith(TestGrant(tasksRead, OWN))
        iam.assignRole(
            user,
            iam.createRole(TestGrant(tasksRead, ALL), TestGrant(discounts, ALL, mapOf("maxPercent" to percent("10")))),
        )
        val login = iam.loginOk(user)
        val token = login["accessToken"].asString()

        SignedJWT
            .parse(token)
            .jwtClaimsSet.claims.keys shouldContainExactlyInAnyOrder
            setOf("iss", "sub", "iat", "exp", "jti", "sid")
        String(Base64.getUrlDecoder().decode(token.split('.')[1])) shouldNotContain "examples"

        val me = iam.json(iam.getWithToken("/api/v1/auth/me", token))
        me["userId"].asString() shouldBe user.id.toString()
        me["sessionId"].asString() shouldBe login["sessionId"].asString()
        me["login"].asString() shouldBe user.login
        me["roles"].values().map { it["name"].asString() } shouldBeSortedBy { it }
        me["roles"].size() shouldBe 2
        val permissions = me["permissions"].values().toList()
        permissions.map { it["code"].asString() } shouldContainExactly
            listOf("examples.discounts.apply", "examples.tasks.read")
        permissions[0]["scope"].asString() shouldBe "ALL"
        permissions[0]["limits"].values().single().let {
            it["key"].asString() shouldBe "maxPercent"
            it["unlimited"].asBoolean() shouldBe false
            it["max"].asString() shouldBe "10"
        }
        permissions[1]["scope"].asString() shouldBe "ALL"
        permissions[1]["limits"].size() shouldBe 0
    }

    @Test
    fun `GET permissions lists the current catalog by pages, without obsolete permissions`() {
        val codes = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page =
                iam.json(
                    mockMvc
                        .get("/api/v1/permissions") {
                            with(asUser(adminId()))
                            param("limit", "2")
                            cursor?.let { param("cursor", it) }
                        }.andReturn()
                        .response
                        .also { withClue(it.contentAsString) { it.status shouldBe 200 } },
                )
            if (!page["nextCursor"].isNull) page["items"].size() shouldBe 2
            codes += page["items"].values().map { it["code"].asString() }
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            pages++
        } while (cursor != null)

        codes shouldContainExactly activeCodes().sorted()
        codes shouldContainAll IamPermissions.permissions.map { it.code.value }
        pages shouldBe (codes.size + 1) / 2

        val usersManage = firstPageItem("iam.users.manage")
        usersManage["module"].asString() shouldBe "iam"
        usersManage["sensitive"].asBoolean() shouldBe true
        usersManage["scopes"].values().map { it.asString() } shouldContainExactly listOf("ALL")
        val discount = firstPageItem("examples.discounts.apply")
        discount["scopes"].values().map { it.asString() } shouldContainExactly listOf("ALL")
        discount["limits"][0]["unit"].asString() shouldBe "PERCENT"
        firstPageItem("examples.tasks.read")["scopes"].values().map { it.asString() } shouldContainExactly
            listOf("OWN", "ALL")
    }

    // --- Apoio ------------------------------------------------------------------------------------

    private fun firstPageItem(code: String): JsonNode =
        iam
            .json(
                mockMvc
                    .get("/api/v1/permissions") {
                        with(asUser(adminId()))
                        param("limit", "200")
                    }.andReturn()
                    .response,
            )["items"]
            .values()
            .single { it["code"].asString() == code }

    private fun accessToken(user: TestUser): String = iam.loginOk(user)["accessToken"].asString()

    private fun listPermissions(token: String): MockHttpServletResponse = iam.getWithToken("/api/v1/permissions", token)

    private fun getMe(auth: org.springframework.test.web.servlet.request.RequestPostProcessor) =
        mockMvc.get("/api/v1/auth/me") { with(auth) }.andReturn().response

    private fun permissionsOf(user: TestUser): List<String> =
        iam.json(getMe(asUser(user.id.value)))["permissions"].values().map { it["code"].asString() }

    private fun adminId(): UUID =
        jdbc
            .sql("SELECT id FROM iam.user_account WHERE login = :login")
            .param("login", TestAdmin.LOGIN)
            .query(UUID::class.java)
            .single()

    private fun activeCodes(): List<String> =
        jdbc
            .sql("SELECT code FROM iam.permission WHERE obsolete_at IS NULL")
            .query { rs, _ -> rs.getString(1) }
            .list()

    private fun obsoleteAt(code: PermissionCode): Instant? =
        jdbc
            .sql("SELECT obsolete_at FROM iam.permission WHERE code = :code")
            .param("code", code.value)
            .query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java)?.toInstant() }
            .list()
            .single()

    private fun grantsOf(template: RoleTemplate): List<String> =
        jdbc
            .sql(
                "SELECT g.permission_code, g.scope FROM iam.role_grant g JOIN iam.role r ON r.id = g.role_id " +
                    "JOIN iam.permission p ON p.code = g.permission_code " +
                    "WHERE r.template_key = :template AND p.obsolete_at IS NULL",
            ).param("template", template.name)
            .query { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}" }
            .list()

    private fun task(
        assignee: UserId?,
        createdBy: UserId,
    ): UUID {
        val id = UuidV7.generate()
        jdbc
            .sql(
                "INSERT INTO test_support.example_task (id, title, assignee_id, created_by, completed) " +
                    "VALUES (:id, 'Tarefa', :assignee, :createdBy, false)",
            ).param("id", id)
            .param("assignee", assignee?.value)
            .param("createdBy", createdBy.value)
            .update()
        return id
    }

    private fun isCompleted(task: UUID): Boolean =
        jdbc
            .sql("SELECT completed FROM test_support.example_task WHERE id = :id")
            .param("id", task)
            .query(Boolean::class.java)
            .single()

    private fun listTasksResponse(user: TestUser): MockHttpServletResponse =
        mockMvc.get("/api/v1/authz-examples/tasks") { with(asUser(user.id.value)) }.andReturn().response

    private fun listTasks(user: TestUser): List<UUID> {
        val response = listTasksResponse(user)
        withClue(response.contentAsString) { response.status shouldBe 200 }
        return iam.json(response).values().map { UUID.fromString(it["id"].asString()) }
    }

    private fun completeTask(
        user: TestUser,
        task: UUID,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/authz-examples/tasks/$task/complete") { with(asUser(user.id.value)) }
            .andReturn()
            .response

    private fun discountLimit(
        auth: org.springframework.test.web.servlet.request.RequestPostProcessor,
    ): Pair<Boolean, String?> {
        val response = mockMvc.get("/api/v1/authz-examples/discount-limit") { with(auth) }.andReturn().response
        withClue(response.contentAsString) { response.status shouldBe 200 }
        val body = iam.json(response)
        return body["unlimited"].asBoolean() to body["max"].takeUnless { it.isNull }?.asString()
    }

    private fun percent(value: String) = LimitValue.Max(value.toBigDecimal())

    /** Executa [block] com o usuário como ator corrente, como o Resource Server faria numa requisição. */
    private fun <T> actingAs(
        user: TestUser,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "RS256")
                .subject(user.id.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}

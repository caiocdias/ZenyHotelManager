package com.zeny.hotel.iam

import authzexample.AuthzExampleConfiguration
import authzexample.ExamplePermissions
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Gestão de roles pela API (M1 E03, RF-ACS-03, 04 e 09): criação com concessões de escopo e
 * limites, nome único, edição com `If-Match`, duplicação, desativação e reativação, a role de
 * sistema intocável e, sobretudo, ninguém concedendo permissão, escopo ou limite acima dos seus,
 * nem editando a própria role.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, AuthzExampleConfiguration::class)
class RoleManagementTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
) {
    private val rolesRead = definition(IamPermissions.ROLES_READ)
    private val rolesManage = definition(IamPermissions.ROLES_MANAGE)
    private val tasksRead = definition(ExamplePermissions.TASKS_READ)
    private val discounts = definition(ExamplePermissions.DISCOUNTS_APPLY)
    private val refunds = definition(ExamplePermissions.REFUNDS_ISSUE)

    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- Criação e leitura ---------------------------------------------------------------------------

    @Test
    fun `the administrator creates a role with grants, scopes and limits`() {
        val name = "Recepção ${suffix()}"
        val response =
            createRole(
                name,
                listOf(grant(TASKS_READ, "OWN"), grant(DISCOUNTS, "ALL", limit(max = "12.5"))),
                description = "Turno da noite",
            )

        val body = with(iam) { response.expectStatus(201) }.shouldNotBeNull()
        val id = body["id"].asString()
        response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/roles/$id"
        val etag = response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        body["name"].asString() shouldBe name
        body["description"].asString() shouldBe "Turno da noite"
        body["active"].asBoolean() shouldBe true
        body["systemRole"].asBoolean() shouldBe false
        body["template"].isNull shouldBe true
        grantsIn(body) shouldContainExactly listOf("$DISCOUNTS:ALL:maxPercent=12.5", "$TASKS_READ:OWN")

        val read = getRole(UUID.fromString(id), iam.asAdmin())
        read.getHeader(HttpHeaders.ETAG) shouldBe etag
        grantsIn(with(iam) { read.ok() }) shouldContainExactly grantsIn(body)

        // Atribuída a um usuário, a role concede o que declara.
        val user = iam.createUser()
        iam.assignRole(user, RoleId(UUID.fromString(id)))
        effectiveOf(user) shouldContainAll listOf("$DISCOUNTS:ALL:maxPercent=12.5", "$TASKS_READ:OWN")
    }

    @Test
    fun `a duplicate role name is a 409 with a stable code, whatever the case`() {
        val name = "Duplicada ${suffix()}"
        with(iam) { createRole(name, emptyList()).expectStatus(201) }

        with(iam) { createRole(name.uppercase(), emptyList()).problem(409, "ROLE_NAME_ALREADY_IN_USE") }

        val other = with(iam) { createRole("Outra ${suffix()}", emptyList()).expectStatus(201) }!!
        val otherId = UUID.fromString(other["id"].asString())
        val etag = getRole(otherId, iam.asAdmin()).getHeader(HttpHeaders.ETAG)
        with(iam) { updateRole(otherId, name.lowercase(), emptyList(), etag).problem(409, "ROLE_NAME_ALREADY_IN_USE") }
        jdbc
            .sql("SELECT count(*) FROM iam.role WHERE lower(name) = lower(:name)")
            .param("name", name)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `invalid grants are refused with 400 and a stable code`() {
        with(iam) {
            createRole(
                "R ${suffix()}",
                listOf(grant("examples.nothing.here", "ALL")),
            ).problem(400, "UNKNOWN_PERMISSION")
            createRole("R ${suffix()}", listOf(grant("x", "ALL"))).problem(400, "UNKNOWN_PERMISSION")
            createRole("R ${suffix()}", listOf(grant(DISCOUNTS, "OWN"))).problem(400, "INVALID_GRANT")
            createRole("R ${suffix()}", listOf(grant(TASKS_READ, "ALL", limit(key = "maxPercent", max = "1"))))
                .problem(400, "INVALID_GRANT")
            createRole("R ${suffix()}", listOf(grant(DISCOUNTS, "ALL", limit(max = "100.5"))))
                .problem(400, "INVALID_GRANT")
            createRole("R ${suffix()}", listOf(grant(DISCOUNTS, "ALL", limit(max = "1", unlimited = true))))
                .problem(400, "INVALID_GRANT")
            createRole("R ${suffix()}", listOf(grant(DISCOUNTS, "ALL", mapOf("key" to "maxPercent"))))
                .problem(400, "INVALID_GRANT")
            createRole("R ${suffix()}", listOf(grant(TASKS_READ, "OWN"), grant(TASKS_READ, "ALL")))
                .problem(400, "INVALID_GRANT")
            createRole("R ${suffix()}", listOf(grant(TASKS_READ, "SECTOR"))).problem(400, "VALIDATION_FAILED")
            createRole(" ", emptyList()).problem(400, "VALIDATION_FAILED")
        }
    }

    @Test
    fun `reading roles requires iam roles read and lists every role by pages`() {
        val plain = iam.createUser()
        val reader = iam.createUserWith(TestGrant(rolesRead, ALL))
        val created =
            List(3) {
                UUID.fromString(
                    with(iam) { createRole("Lista ${suffix()}", emptyList()).expectStatus(201) }!!["id"].asString(),
                )
            }

        with(iam) {
            iam.call(GET, "/api/v1/roles", iam.asTestUser(plain)).problem(403, "PERMISSION_DENIED")
            getRole(created.first(), iam.asTestUser(plain)).problem(403, "PERMISSION_DENIED")
            getRole(UuidV7.generate(), iam.asAdmin()).problem(404, "ROLE_NOT_FOUND")
            createRole(
                "Sem permissão ${suffix()}",
                emptyList(),
                iam.asTestUser(reader),
            ).problem(403, "PERMISSION_DENIED")
        }

        val ids = mutableListOf<String>()
        val templates = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page =
                with(iam) {
                    iam
                        .call(
                            GET,
                            "/api/v1/roles?limit=5" + cursor?.let { "&cursor=$it" }.orEmpty(),
                            iam.asTestUser(reader),
                        ).ok()
                }
            page["items"].values().forEach {
                ids += it["id"].asString()
                if (!it["template"].isNull) templates += it["template"].asString()
            }
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        ids shouldContainAll created.map(UUID::toString)
        ids.toSet().size shouldBe ids.size
        templates shouldContainAll listOf("ADMINISTRATOR", "MANAGER", "ROOM_ATTENDANT")
    }

    // --- Edição ---------------------------------------------------------------------------------------

    @Test
    fun `editing a role requires the current If-Match and replaces name, description and grants`() {
        val id = createdRoleId(listOf(grant(TASKS_READ, "OWN"), grant(TASKS_COMPLETE, "OWN")))
        val etag = getRole(id, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val newName = "Editada ${suffix()}"
        val grants = listOf(grant(TASKS_READ, "ALL"), grant(DISCOUNTS, "ALL", limit(unlimited = true)))

        with(iam) {
            updateRole(id, newName, grants, null).problem(428, "PRECONDITION_REQUIRED")
            updateRole(id, newName, grants, "\"999\"").problem(412, "PRECONDITION_FAILED")
        }
        val response = updateRole(id, newName, grants, etag, description = "Nova descrição")
        val body = with(iam) { response.ok() }
        body["name"].asString() shouldBe newName
        body["description"].asString() shouldBe "Nova descrição"
        grantsIn(body) shouldContainExactly listOf("$DISCOUNTS:ALL:maxPercent=unlimited", "$TASKS_READ:ALL")
        response.getHeader(HttpHeaders.ETAG).shouldNotBeNull() shouldNotBe etag
        with(iam) { updateRole(id, newName, emptyList(), etag).problem(412, "PRECONDITION_FAILED") }
        grantsIn(with(iam) { getRole(id, iam.asAdmin()).ok() }) shouldContainExactly grantsIn(body)
    }

    @Test
    @Suppress("LongMethod") // um cenário: criar, editar a própria role, baixar e tentar subir de volta
    fun `no one grants a permission, scope or limit above their own, not even editing a role they hold`() {
        val manager =
            iam.createUserWith(
                TestGrant(rolesManage, ALL),
                TestGrant(rolesRead, ALL),
                TestGrant(tasksRead, OWN),
                TestGrant(discounts, ALL, mapOf(ExamplePermissions.MAX_PERCENT to LimitValue.Max("10".toBigDecimal()))),
            )
        val asManager = iam.asTestUser(manager)
        val ownRole = roleIdsOf(manager).single()
        val own = currentGrantsOf(ownRole)

        // Criar uma role acima do que tem.
        with(iam) {
            listOf(
                listOf(grant(TASKS_READ, "ALL")),
                listOf(grant(DISCOUNTS, "ALL", limit(max = "15"))),
                listOf(grant(DISCOUNTS, "ALL", limit(unlimited = true))),
                listOf(grant(TASKS_COMPLETE, "OWN")),
                listOf(grant(IamPermissions.USERS_MANAGE.value, "ALL")),
            ).forEach { grants ->
                withClue(grants) {
                    createRole("Acima ${suffix()}", grants, asManager).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
                }
            }
        }

        // Editar a própria role para ampliar escopo, subir limite ou acrescentar permissão.
        val etag = getRole(ownRole.value, asManager).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val ownName = nameOf(ownRole)
        val base = listOf(grant(ROLES_MANAGE, "ALL"), grant(ROLES_READ, "ALL"))
        with(iam) {
            listOf(
                base + grant(TASKS_READ, "ALL") + grant(DISCOUNTS, "ALL", limit(max = "10")),
                base + grant(TASKS_READ, "OWN") + grant(DISCOUNTS, "ALL", limit(max = "20")),
                base + grant(TASKS_READ, "OWN") + grant(DISCOUNTS, "ALL", limit(max = "10")) +
                    grant(TASKS_COMPLETE, "OWN"),
            ).forEach { grants ->
                withClue(grants) {
                    updateRole(ownRole.value, ownName, grants, etag, asManager)
                        .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
                }
            }
        }
        currentGrantsOf(ownRole) shouldBe own
        getRole(ownRole.value, asManager).getHeader(HttpHeaders.ETAG) shouldBe etag
        effectiveOf(manager) shouldContain "$TASKS_READ:OWN"
        effectiveOf(manager) shouldContain "$DISCOUNTS:ALL:maxPercent=10"

        // Dentro do que tem, passa; baixar o próprio limite também, e depois não dá para subir de volta.
        with(iam) {
            createRole(
                "Dentro ${suffix()}",
                listOf(grant(TASKS_READ, "OWN"), grant(DISCOUNTS, "ALL", limit(max = "10"))),
                asManager,
            ).expectStatus(201)
        }
        val lowered = base + grant(TASKS_READ, "OWN") + grant(DISCOUNTS, "ALL", limit(max = "5"))
        val afterLowering = updateRole(ownRole.value, ownName, lowered, etag, asManager)
        with(iam) { afterLowering.ok() }
        effectiveOf(manager) shouldContain "$DISCOUNTS:ALL:maxPercent=5"
        val raisedBack = base + grant(TASKS_READ, "OWN") + grant(DISCOUNTS, "ALL", limit(max = "10"))
        with(iam) {
            updateRole(ownRole.value, ownName, raisedBack, afterLowering.getHeader(HttpHeaders.ETAG), asManager)
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
        }

        // Renomear uma role com permissões que ele não tem não concede nada: as concessões ficam como estão.
        val foreign = createdRoleId(listOf(grant(TASKS_COMPLETE, "ALL")))
        val foreignEtag = getRole(foreign, asManager).getHeader(HttpHeaders.ETAG)
        val renamed =
            with(iam) {
                updateRole(
                    foreign,
                    "Renomeada ${suffix()}",
                    listOf(grant(TASKS_COMPLETE, "ALL")),
                    foreignEtag,
                    asManager,
                ).ok()
            }
        grantsIn(renamed) shouldContainExactly listOf("$TASKS_COMPLETE:ALL")
    }

    @Test
    fun `editing or reactivating a role cannot widen the union of its holders beyond the actor`() {
        val manager =
            iam.createUserWith(
                TestGrant(rolesManage, ALL),
                TestGrant(rolesRead, ALL),
                TestGrant(refunds, OWN, mapOf(ExamplePermissions.MAX_AMOUNT to LimitValue.Max("100".toBigDecimal()))),
            )
        val asManager = iam.asTestUser(manager)
        // O alvo tem ALL com teto 0 (role do administrador) e a role editável com OWN/10.
        val holder = iam.createUser()
        iam.assignRole(holder, RoleId(createdRoleId(listOf(grant(REFUNDS, "ALL", limit(KEY_AMOUNT, max = "0"))))))
        val editable = createdRoleId(listOf(grant(REFUNDS, "OWN", limit(KEY_AMOUNT, max = "10"))))
        iam.assignRole(holder, RoleId(editable))
        val name = nameOf(RoleId(editable))
        val etag = getRole(editable, asManager).getHeader(HttpHeaders.ETAG)

        // OWN/100 está coberto sozinho, mas o alvo passaria a ALL/100.
        val raised = listOf(grant(REFUNDS, "OWN", limit(KEY_AMOUNT, max = "100")))
        with(iam) {
            updateRole(editable, name, raised, etag, asManager).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
        }
        grantsIn(with(iam) { getRole(editable, asManager).ok() }) shouldContainExactly
            listOf("$REFUNDS:OWN:$KEY_AMOUNT=10")
        effectiveOf(holder) shouldContain "$REFUNDS:ALL:$KEY_AMOUNT=10"
        // Uma edição que não amplia ninguém passa (renomear; baixar o teto).
        val lowered = listOf(grant(REFUNDS, "OWN", limit(KEY_AMOUNT, max = "5")))
        val edited = updateRole(editable, "Editável ${suffix()}", lowered, etag, asManager)
        with(iam) { edited.ok() }
        effectiveOf(holder) shouldContain "$REFUNDS:ALL:$KEY_AMOUNT=5"

        // Reativar: a role sozinha está coberta, mas devolveria ALL/5 ao alvo.
        with(iam) { iam.call(POST, "/api/v1/roles/$editable/deactivate", asManager).ok() }
        effectiveOf(holder) shouldContain "$REFUNDS:ALL:$KEY_AMOUNT=0"
        with(iam) {
            iam
                .call(
                    POST,
                    "/api/v1/roles/$editable/reactivate",
                    asManager,
                ).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            iam.call(POST, "/api/v1/roles/$editable/reactivate", iam.asAdmin()).ok()
        }
        effectiveOf(holder) shouldContain "$REFUNDS:ALL:$KEY_AMOUNT=5"
    }

    // --- Duplicação, desativação e role de sistema -----------------------------------------------------

    @Test
    fun `a role is duplicated with its grants, by someone who holds them`() {
        val source = createdRoleId(listOf(grant(TASKS_READ, "ALL"), grant(DISCOUNTS, "ALL", limit(max = "7.5"))))
        val sourceGrants = grantsIn(with(iam) { getRole(source, iam.asAdmin()).ok() })
        val name = "Cópia ${suffix()}"

        val response = iam.call(POST, "/api/v1/roles/$source/duplicate", iam.asAdmin(), mapOf("name" to name))
        val copy = with(iam) { response.expectStatus(201) }.shouldNotBeNull()
        copy["id"].asString() shouldNotBe source.toString()
        response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/roles/${copy["id"].asString()}"
        copy["name"].asString() shouldBe name
        copy["template"].isNull shouldBe true
        grantsIn(copy) shouldContainExactly sourceGrants
        grantsIn(with(iam) { getRole(source, iam.asAdmin()).ok() }) shouldContainExactly sourceGrants

        with(iam) {
            iam
                .call(POST, "/api/v1/roles/$source/duplicate", iam.asAdmin(), mapOf("name" to name.uppercase()))
                .problem(409, "ROLE_NAME_ALREADY_IN_USE")
            iam
                .call(
                    POST,
                    "/api/v1/roles/${UuidV7.generate()}/duplicate",
                    iam.asAdmin(),
                    mapOf("name" to "X ${suffix()}"),
                ).problem(404, "ROLE_NOT_FOUND")
        }

        // Quem não tem as permissões da role não a duplica (nem a do Administrador).
        val manager = iam.createUserWith(TestGrant(rolesManage, ALL), TestGrant(tasksRead, OWN))
        with(iam) {
            iam
                .call(
                    POST,
                    "/api/v1/roles/$source/duplicate",
                    iam.asTestUser(manager),
                    mapOf("name" to "X ${suffix()}"),
                ).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            iam
                .call(
                    POST,
                    "/api/v1/roles/${administratorRoleId()}/duplicate",
                    iam.asTestUser(manager),
                    mapOf("name" to "X ${suffix()}"),
                ).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
        }
    }

    @Test
    fun `a deactivated role stops granting, and only who holds its grants reactivates it`() {
        val role = createdRoleId(listOf(grant(TASKS_READ, "ALL")))
        val user = iam.createUser()
        iam.assignRole(user, RoleId(role))
        effectiveOf(user) shouldContain "$TASKS_READ:ALL"

        val manager = iam.createUserWith(TestGrant(rolesManage, ALL), TestGrant(tasksRead, OWN))
        val deactivated = with(iam) { iam.call(POST, "/api/v1/roles/$role/deactivate", iam.asTestUser(manager)).ok() }
        deactivated["active"].asBoolean() shouldBe false
        effectiveOf(user) shouldNotContain "$TASKS_READ:ALL"
        // Nada foi apagado: a role e as concessões continuam.
        currentGrantsOf(RoleId(role)) shouldContainExactly listOf("$TASKS_READ:ALL")

        with(iam) {
            iam
                .call(
                    POST,
                    "/api/v1/roles/$role/reactivate",
                    iam.asTestUser(manager),
                ).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
        }
        effectiveOf(user) shouldNotContain "$TASKS_READ:ALL"
        val reactivated = with(iam) { iam.call(POST, "/api/v1/roles/$role/reactivate", iam.asAdmin()).ok() }
        reactivated["active"].asBoolean() shouldBe true
        effectiveOf(user) shouldContain "$TASKS_READ:ALL"
    }

    @Test
    fun `the administrator system role is not edited nor deactivated`() {
        val administrator = administratorRoleId()
        val etag = getRole(administrator, iam.asAdmin()).getHeader(HttpHeaders.ETAG)
        with(iam) {
            updateRole(administrator, "Administrador", emptyList(), etag).problem(422, "SYSTEM_ROLE_IMMUTABLE")
            iam
                .call(
                    POST,
                    "/api/v1/roles/$administrator/deactivate",
                    iam.asAdmin(),
                ).problem(422, "SYSTEM_ROLE_IMMUTABLE")
            iam
                .call(
                    POST,
                    "/api/v1/roles/$administrator/reactivate",
                    iam.asAdmin(),
                ).problem(422, "SYSTEM_ROLE_IMMUTABLE")
            getRole(administrator, iam.asAdmin()).ok()["active"].asBoolean() shouldBe true
        }
        jdbc
            .sql(
                "SELECT count(*) FROM iam.role_grant g JOIN iam.permission p ON p.code = g.permission_code " +
                    "WHERE g.role_id = :id AND p.obsolete_at IS NULL",
            ).param("id", administrator)
            .query(Int::class.java)
            .single() shouldBe
            jdbc.sql("SELECT count(*) FROM iam.permission WHERE obsolete_at IS NULL").query(Int::class.java).single()
    }

    // --- Apoio -----------------------------------------------------------------------------------------

    private fun definition(code: PermissionCode): PermissionDefinition =
        (IamPermissions.permissions + ExamplePermissions.permissions).single { it.code == code }

    private fun suffix(): String = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private fun grant(
        permission: String,
        scope: String,
        vararg limits: Map<String, Any>,
    ): Map<String, Any> = mapOf("permission" to permission, "scope" to scope, "limits" to limits.toList())

    private fun limit(
        key: String = ExamplePermissions.MAX_PERCENT,
        max: String? = null,
        unlimited: Boolean = false,
    ): Map<String, Any> =
        buildMap {
            put("key", key)
            put("unlimited", unlimited)
            if (max != null) put("max", max)
        }

    private fun createRole(
        name: String,
        grants: List<Map<String, Any>>,
        auth: RequestPostProcessor = iam.asAdmin(),
        description: String = "Role de teste",
    ): MockHttpServletResponse =
        iam.call(POST, "/api/v1/roles", auth, mapOf("name" to name, "description" to description, "grants" to grants))

    private fun createdRoleId(grants: List<Map<String, Any>>): UUID =
        UUID.fromString(with(iam) { createRole("Criada ${suffix()}", grants).expectStatus(201) }!!["id"].asString())

    @Suppress("LongParameterList") // os campos da edição, com padrões para os testes
    private fun updateRole(
        id: UUID,
        name: String,
        grants: List<Map<String, Any>>,
        ifMatch: String?,
        auth: RequestPostProcessor = iam.asAdmin(),
        description: String = "Role de teste",
    ): MockHttpServletResponse =
        iam.call(
            PUT,
            "/api/v1/roles/$id",
            auth,
            mapOf("name" to name, "description" to description, "grants" to grants),
            ifMatch,
        )

    private fun getRole(
        id: UUID,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse = iam.call(GET, "/api/v1/roles/$id", auth)

    /** As concessões de uma resposta como `permissão:escopo[:chave=valor…]`, em ordem. */
    private fun grantsIn(role: JsonNode): List<String> =
        role["grants"].values().map { grant ->
            (listOf(grant["permission"].asString(), grant["scope"].asString()) + limitsIn(grant)).joinToString(":")
        }

    /** As permissões efetivas do usuário, no mesmo formato. */
    private fun effectiveOf(user: TestUser): List<String> {
        val me = with(iam) { iam.call(GET, "/api/v1/auth/me", iam.asTestUser(user)).ok() }
        return me["permissions"].values().map { permission ->
            (listOf(permission["code"].asString(), permission["scope"].asString()) + limitsIn(permission))
                .joinToString(":")
        }
    }

    private fun limitsIn(grant: JsonNode): List<String> =
        grant["limits"].values().map {
            "${it["key"].asString()}=" + if (it["unlimited"].asBoolean()) "unlimited" else it["max"].asString()
        }

    private fun roleIdsOf(user: TestUser): List<RoleId> =
        jdbc
            .sql("SELECT role_id FROM iam.user_role WHERE user_id = :id")
            .param("id", user.id.value)
            .query(UUID::class.java)
            .list()
            .map { RoleId(checkNotNull(it)) }

    private fun nameOf(role: RoleId): String =
        jdbc
            .sql("SELECT name FROM iam.role WHERE id = :id")
            .param("id", role.value)
            .query(String::class.java)
            .single()

    /** As concessões gravadas da role, como `permissão:escopo`, em ordem. */
    private fun currentGrantsOf(role: RoleId): List<String> =
        jdbc
            .sql("SELECT permission_code, scope FROM iam.role_grant WHERE role_id = :id ORDER BY permission_code")
            .param("id", role.value)
            .query { rs, _ -> "${rs.getString(1)}:${rs.getString(2)}" }
            .list()

    private fun administratorRoleId(): UUID =
        jdbc
            .sql("SELECT id FROM iam.role WHERE system_role")
            .query(UUID::class.java)
            .single()

    private companion object {
        const val SUFFIX_LENGTH = 12
        val TASKS_READ = ExamplePermissions.TASKS_READ.value
        val TASKS_COMPLETE = ExamplePermissions.TASKS_COMPLETE.value
        val DISCOUNTS = ExamplePermissions.DISCOUNTS_APPLY.value
        val REFUNDS = ExamplePermissions.REFUNDS_ISSUE.value
        const val KEY_AMOUNT = ExamplePermissions.MAX_AMOUNT
        val ROLES_MANAGE = IamPermissions.ROLES_MANAGE.value
        val ROLES_READ = IamPermissions.ROLES_READ.value
    }
}

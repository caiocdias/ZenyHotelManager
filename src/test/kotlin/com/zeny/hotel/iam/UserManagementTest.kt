package com.zeny.hotel.iam

import authzexample.AuthzExampleConfiguration
import authzexample.ExamplePermissions
import com.zeny.hotel.iam.application.PasswordHasher
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestAdmin
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Gestão de usuários pela API (M1 E03, RF-ACS-01, 09, 10 e 12): criação com senha e PIN, login
 * único, edição com `If-Match` (inclusive o `412` do lock otimista), atribuição de roles sem
 * conceder mais do que se tem, só administrador gerindo administradores, desativação que revoga
 * as sessões, redefinição de senha e PIN, sessões de outros usuários e nenhuma exclusão física.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, AuthzExampleConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta da gestão de usuários
class UserManagementTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val hasher: PasswordHasher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val usersRead = definition(IamPermissions.USERS_READ)
    private val usersManage = definition(IamPermissions.USERS_MANAGE)
    private val sessionsManage = definition(IamPermissions.SESSIONS_MANAGE)
    private val tasksRead = definition(ExamplePermissions.TASKS_READ)
    private val tasksComplete = definition(ExamplePermissions.TASKS_COMPLETE)
    private val discounts = definition(ExamplePermissions.DISCOUNTS_APPLY)
    private val refunds = definition(ExamplePermissions.REFUNDS_ISSUE)

    /** O banco é compartilhado: outro contexto pode ter marcado as permissões de exemplo como obsoletas. */
    @BeforeEach
    fun synchronizeThisContextCatalog() {
        synchronizer.synchronize()
    }

    // --- Criação ---------------------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `the administrator creates a user with name, login, password and PIN`(output: CapturedOutput) {
        val login = "Maria.${suffix()}"
        val response =
            create(mapOf("name" to "  Maria Silva ", "login" to login, "password" to PASSWORD, "pin" to "4821"))

        val body = with(iam) { response.expectStatus(201) }.shouldNotBeNull()
        val id = body["id"].asString()
        response.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/users/$id"
        val etag = response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        body["name"].asString() shouldBe "Maria Silva"
        body["login"].asString() shouldBe login.lowercase()
        body["active"].asBoolean() shouldBe true
        body["hasPin"].asBoolean() shouldBe true
        body["passwordChangeRequired"].asBoolean() shouldBe true
        body["administrator"].asBoolean() shouldBe false
        body["roles"].values().shouldBeEmpty()
        response.contentAsString shouldNotContain PASSWORD
        response.contentAsString shouldNotContain "4821"

        // Senha e PIN só como hash Argon2id.
        val (passwordHash, pinHash) =
            jdbc
                .sql("SELECT password_hash, pin_hash FROM iam.user_account WHERE id = :id")
                .param("id", UUID.fromString(id))
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .single()
        passwordHash shouldStartWith "\$argon2id\$"
        pinHash shouldStartWith "\$argon2id\$"
        hasher.matches("4821", pinHash) shouldBe true

        // A senha dada pelo administrador é provisória: o primeiro login exige a troca.
        with(iam) { iam.login(login.uppercase(), PASSWORD).ok() }["passwordChangeRequired"].asBoolean() shouldBe true

        // Nem a senha nem o PIN vão para o log.
        output.all shouldNotContain PASSWORD
        output.all shouldNotContain "\"4821\""

        val read = getUser(UUID.fromString(id), iam.asAdmin())
        read.getHeader(HttpHeaders.ETAG) shouldBe etag
        with(iam) { read.ok() }["login"].asString() shouldBe login.lowercase()
    }

    @Test
    fun `a duplicate login is a 409 with a stable code, whatever the case`() {
        val login = "dup.${suffix()}"
        with(iam) { create(newUser(login)).expectStatus(201) }

        with(iam) { create(newUser(login.uppercase())).problem(409, "LOGIN_ALREADY_IN_USE") }
        with(iam) { create(newUser(" $login ")).problem(409, "LOGIN_ALREADY_IN_USE") }

        jdbc
            .sql("SELECT count(*) FROM iam.user_account WHERE login = :login")
            .param("login", login)
            .query(Int::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `an invalid login, password or PIN is refused with 400`() {
        val invalidLogin = with(iam) { create(newUser("com espaço")).problem(400, "VALIDATION_FAILED") }
        invalidLogin["errors"].values().map { it["field"].asString() } shouldContainExactly listOf("login")

        with(iam) {
            create(newUser("curta.${suffix()}") + ("password" to "curta")).problem(400, "VALIDATION_FAILED")
            create(newUser("pin.${suffix()}") + ("pin" to "12a4")).problem(400, "VALIDATION_FAILED")
            create(newUser("pin.${suffix()}") + ("pin" to "123")).problem(400, "VALIDATION_FAILED")
            create(mapOf("login" to "semnome.${suffix()}", "password" to PASSWORD)).problem(400, "VALIDATION_FAILED")
            create(newUser("role.${suffix()}") + ("roleIds" to listOf(UuidV7.generate()))).problem(400, "UNKNOWN_ROLE")
        }
    }

    @Test
    fun `managing users requires iam users manage and reading requires iam users read`() {
        val plain = iam.createUser()
        val reader = iam.createUserWith(TestGrant(usersRead, ALL))
        val target = createdUserId()

        with(iam) {
            create(newUser("x.${suffix()}"), iam.asTestUser(plain)).problem(403, "PERMISSION_DENIED")
            create(newUser("x.${suffix()}"), iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            iam.call(GET, "/api/v1/users", iam.asTestUser(plain)).problem(403, "PERMISSION_DENIED")
            getUser(target, iam.asTestUser(plain)).problem(403, "PERMISSION_DENIED")
            getUser(target, iam.asTestUser(reader)).ok()
            action(target, "deactivate", iam.asTestUser(reader)).problem(403, "PERMISSION_DENIED")
            getUser(UuidV7.generate(), iam.asAdmin()).problem(404, "USER_NOT_FOUND")
            action(UuidV7.generate(), "deactivate", iam.asAdmin()).problem(404, "USER_NOT_FOUND")
        }
    }

    @Test
    fun `GET users lists every user by pages, in login order`() {
        val created = List(3) { createdUserId() }

        val ids = mutableListOf<String>()
        val logins = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page =
                with(iam) {
                    iam.call(GET, "/api/v1/users?limit=2" + cursor?.let { "&cursor=$it" }.orEmpty(), iam.asAdmin()).ok()
                }
            page["items"].values().size shouldBe (if (page["nextCursor"].isNull) page["items"].values().size else 2)
            page["items"].values().forEach {
                ids += it["id"].asString()
                logins += it["login"].asString()
            }
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)

        logins.shouldBeSorted()
        ids shouldContainAll created.map(UUID::toString)
        ids shouldContainAll listOf(iam.adminId().toString())
        ids.toSet() shouldHaveSize ids.size
    }

    // --- Edição e concorrência -------------------------------------------------------------------

    @Test
    fun `editing a user requires the current If-Match`() {
        val id = createdUserId()
        val etag = getUser(id, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()

        with(iam) {
            rename(id, "Sem If-Match", null).problem(428, "PRECONDITION_REQUIRED")
            rename(id, "Versão errada", "\"999\"").problem(412, "PRECONDITION_FAILED")
            rename(id, "Etag fraca", "W/$etag").problem(412, "PRECONDITION_FAILED")
        }
        val response = rename(id, "Nome Novo", etag)
        with(iam) { response.ok() }["name"].asString() shouldBe "Nome Novo"
        val newEtag = response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        newEtag shouldNotBe etag
        with(iam) { rename(id, "De novo com a velha", etag).problem(412, "PRECONDITION_FAILED") }
        with(iam) { getUser(id, iam.asAdmin()).ok() }["name"].asString() shouldBe "Nome Novo"
    }

    @Test
    fun `two concurrent edits with the same If-Match - one passes, the other gets 412 from the optimistic lock`() {
        val id = createdUserId()
        val etag = getUser(id, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val admin = iam.adminId()

        // Uma transação à parte trava a linha do usuário: as duas edições conferem o If-Match (a
        // versão ainda é a mesma) e ficam presas no UPDATE. Liberada a trava, a primeira grava e
        // a segunda encontra a versão já trocada no banco (lock otimista).
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                transactions.executeWithoutResult {
                    jdbc
                        .sql("SELECT id FROM iam.user_account WHERE id = :id FOR UPDATE")
                        .param("id", id)
                        .query()
                        .listOfRows()
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        locked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        val pool = Executors.newFixedThreadPool(2)
        try {
            val edits =
                listOf("Edição A", "Edição B").map { name ->
                    pool.submit(Callable { rename(id, name, etag, asUser(admin)) })
                }
            awaitBlockedUpdates(2)
            release.countDown()
            holder.join()

            val responses = edits.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            withClue(responses.map { it.contentAsString }) {
                responses.map { it.status }.sorted() shouldContainExactly listOf(200, 412)
            }
            val winner = responses.single { it.status == 200 }
            with(iam) { responses.single { it.status == 412 }.problem(412, "PRECONDITION_FAILED") }
            with(iam) { getUser(id, iam.asAdmin()).ok() }["name"].asString() shouldBe
                iam.json(winner)["name"].asString()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    // --- Desativação -----------------------------------------------------------------------------

    @Test
    fun `deactivating a user revokes every session - the next refresh fails - and nothing is deleted`() {
        val user = iam.createUserWith(TestGrant(usersRead, ALL))
        val first = iam.loginOk(user, "device-a")
        val second = iam.loginOk(user, "device-b")
        val accessToken = first["accessToken"].asString()
        iam.getWithToken("/api/v1/users", accessToken).status shouldBe 200

        val deactivated = action(user.id.value, "deactivate", iam.asAdmin())
        with(iam) { deactivated.ok() }["active"].asBoolean() shouldBe false
        deactivated.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        // A desativação revoga as sessões na hora, sem esperar o próximo refresh.
        sessionsOf(user.id.value) shouldContainExactlyInAnyOrder listOf("USER_DISABLED", "USER_DISABLED")

        with(iam) {
            iam.refresh(first["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            iam.refresh(second["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            // O access token ainda autentica até vencer, mas as permissões já se foram.
            iam.getWithToken("/api/v1/users", accessToken).problem(403, "PERMISSION_DENIED")
            iam.login(user.login, user.password).problem(401, "ACCOUNT_DISABLED")
        }
        // Desativar de novo não muda nada.
        with(iam) { action(user.id.value, "deactivate", iam.asAdmin()).ok() }["active"].asBoolean() shouldBe false
        // Nada foi apagado: o usuário, as sessões e as roles continuam no banco.
        count("SELECT count(*) FROM iam.user_account WHERE id = :id", user.id.value) shouldBe 1
        count("SELECT count(*) FROM iam.user_role WHERE user_id = :id", user.id.value) shouldBe 1

        val reactivated = action(user.id.value, "reactivate", iam.asAdmin())
        with(iam) { reactivated.ok() }["active"].asBoolean() shouldBe true
        // As sessões revogadas não voltam: o usuário entra de novo.
        with(iam) { iam.refresh(second["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID") }
        val again = iam.loginOk(user, "device-a")
        iam.getWithToken("/api/v1/users", again["accessToken"].asString()).status shouldBe 200
    }

    @Test
    fun `sessions revoked by the deactivation do not come back with the reactivation`() {
        val user = iam.createUser()
        val session = iam.loginOk(user)

        with(iam) {
            action(user.id.value, "deactivate", iam.asAdmin()).ok()
            // Reativado antes de qualquer refresh: a sessão de antes continua revogada.
            action(user.id.value, "reactivate", iam.asAdmin()).ok()
            iam.refresh(session["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
        }
        sessionsOf(user.id.value) shouldContainExactly listOf("USER_DISABLED")
    }

    @Test
    fun `no endpoint deletes anything`() {
        val deletes =
            handlerMapping.handlerMethods.keys
                .filter { info -> info.patternValues.any { it.startsWith("/api/") } }
                .filter { info -> HttpMethod.DELETE.name() in info.methodsCondition.methods.map { it.name } }
                .map { it.toString() }
        deletes.shouldBeEmpty()
        // Sem método declarado, o mapeamento aceitaria DELETE: todo endpoint da API declara o seu.
        handlerMapping.handlerMethods.keys
            .filter { info -> info.patternValues.any { it.startsWith("/api/") } }
            .filter { it.methodsCondition.methods.isEmpty() }
            .map { it.toString() }
            .shouldBeEmpty()
    }

    // --- Administradores ---------------------------------------------------------------------------

    @Test
    fun `a non administrator cannot manage an administrator nor make someone one`() {
        val manager =
            iam.createUserWith(TestGrant(usersManage, ALL), TestGrant(usersRead, ALL), TestGrant(sessionsManage, ALL))
        val otherAdmin = iam.createUser()
        iam.assignRole(otherAdmin, administratorRoleId())
        val adminSession = iam.loginOk(otherAdmin)
        val asManager = iam.asTestUser(manager)
        val etag = getUser(otherAdmin.id.value, asManager).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val target = otherAdmin.id.value

        with(iam) {
            rename(target, "Tomado", etag, asManager).problem(403, "ADMINISTRATOR_REQUIRED")
            setRoles(target, emptyList(), etag, asManager).problem(403, "ADMINISTRATOR_REQUIRED")
            action(target, "deactivate", asManager).problem(403, "ADMINISTRATOR_REQUIRED")
            action(target, "reactivate", asManager).problem(403, "ADMINISTRATOR_REQUIRED")
            action(target, "reset-password", asManager, mapOf("newPassword" to PASSWORD))
                .problem(403, "ADMINISTRATOR_REQUIRED")
            action(target, "reset-pin", asManager, mapOf("newPin" to "1234")).problem(403, "ADMINISTRATOR_REQUIRED")
            iam.call(GET, "/api/v1/users/$target/sessions", asManager).problem(403, "ADMINISTRATOR_REQUIRED")
            iam
                .call(POST, "/api/v1/users/$target/sessions/${adminSession["sessionId"].asString()}/revoke", asManager)
                .problem(403, "ADMINISTRATOR_REQUIRED")
        }
        // Nada mudou no administrador.
        with(iam) { getUser(target, iam.asAdmin()).ok() }.let {
            it["name"].asString() shouldNotBe "Tomado"
            it["active"].asBoolean() shouldBe true
            it["administrator"].asBoolean() shouldBe true
        }
        with(iam) { iam.refresh(adminSession["refreshToken"].asString()).ok() }

        // Nem dar a role Administrador a um usuário comum, nem criar um usuário com ela.
        val common = createdUserId()
        val commonEtag = getUser(common, asManager).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val administrator = administratorRoleId().value
        with(iam) {
            setRoles(common, listOf(administrator), commonEtag, asManager).problem(403, "ADMINISTRATOR_REQUIRED")
            val login = "novo.admin.${suffix()}"
            create(newUser(login) + ("roleIds" to listOf(administrator)), asManager)
                .problem(403, "ADMINISTRATOR_REQUIRED")
            count("SELECT count(*) FROM iam.user_account WHERE login = :id", login) shouldBe 0
        }
        // O administrador pode.
        val promoted = with(iam) { setRoles(common, listOf(administrator), commonEtag, iam.asAdmin()).ok() }
        promoted["administrator"].asBoolean() shouldBe true
        // Depois disso, o gerente já não o gere.
        with(iam) { action(common, "deactivate", asManager).problem(403, "ADMINISTRATOR_REQUIRED") }
    }

    @Test
    fun `the last active administrator cannot be deactivated nor lose the administrator role`() {
        onlyTheInitialAdminIsActive()
        val admin = iam.adminId()
        val etag = getUser(admin, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()

        with(iam) {
            action(admin, "deactivate", iam.asAdmin()).problem(409, "LAST_ADMINISTRATOR")
            setRoles(admin, emptyList(), etag, iam.asAdmin()).problem(409, "LAST_ADMINISTRATOR")
        }
        with(iam) { getUser(admin, iam.asAdmin()).ok() }.let {
            it["active"].asBoolean() shouldBe true
            it["administrator"].asBoolean() shouldBe true
        }

        // Com um segundo administrador ativo, um deles pode sair.
        val second = iam.createUser()
        iam.assignRole(second, administratorRoleId())
        with(iam) { action(second.id.value, "deactivate", iam.asAdmin()).ok() }["active"].asBoolean() shouldBe false
        with(iam) { action(admin, "deactivate", iam.asAdmin()).problem(409, "LAST_ADMINISTRATOR") }
    }

    // --- RF-ACS-09: ninguém concede o que não tem ---------------------------------------------------

    @Test
    fun `no one assigns a role with a permission, scope or limit above their own`() {
        val manager =
            iam.createUserWith(
                TestGrant(usersManage, ALL),
                TestGrant(tasksRead, OWN),
                TestGrant(discounts, ALL, mapOf(ExamplePermissions.MAX_PERCENT to percent("10"))),
            )
        val asManager = iam.asTestUser(manager)
        val target = createdUserId()
        val etag = getUser(target, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()

        val widerScope = iam.createRole(TestGrant(tasksRead, ALL))
        val higherLimit =
            iam.createRole(
                TestGrant(discounts, ALL, mapOf(ExamplePermissions.MAX_PERCENT to percent("15"))),
            )
        val unlimited =
            iam.createRole(
                TestGrant(
                    discounts,
                    ALL,
                    mapOf(ExamplePermissions.MAX_PERCENT to LimitValue.Unlimited),
                ),
            )
        val notHeld = iam.createRole(TestGrant(tasksComplete, OWN))
        val inactive = iam.createRole(TestGrant(tasksRead, ALL)).also(iam::deactivateRole)
        with(iam) {
            listOf(widerScope, higherLimit, unlimited, notHeld, inactive).forEach { role ->
                withClue(role) {
                    setRoles(target, listOf(role.value), etag, asManager).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
                }
            }
            val login = "acima.${suffix()}"
            create(newUser(login) + ("roleIds" to listOf(widerScope.value)), asManager)
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            count("SELECT count(*) FROM iam.user_account WHERE login = :id", login) shouldBe 0
        }
        rolesOf(target).shouldBeEmpty()

        // Dentro do que ele tem (escopo igual, limite igual ou menor), passa.
        val within =
            iam.createRole(
                TestGrant(tasksRead, OWN),
                TestGrant(discounts, ALL, mapOf(ExamplePermissions.MAX_PERCENT to percent("10"))),
            )
        val lower = iam.createRole(TestGrant(discounts, ALL, mapOf(ExamplePermissions.MAX_PERCENT to percent("5"))))
        val assigned = with(iam) { setRoles(target, listOf(within.value, lower.value), etag, asManager).ok() }
        assigned["roles"].values().map { it["id"].asString() } shouldContainExactlyInAnyOrder
            listOf(within.toString(), lower.toString())
        rolesOf(target) shouldContainExactlyInAnyOrder listOf(within.value, lower.value)
    }

    @Test
    fun `the resulting union of the target cannot exceed the actor, even with each role covered`() {
        // O ator tem o estorno só nos próprios registros, até 100.
        val manager = iam.createUserWith(TestGrant(usersManage, ALL), TestGrant(refunds, OWN, amount("100")))
        val asManager = iam.asTestUser(manager)
        // O alvo já tem o estorno em todos os registros, com teto zero (dado pelo administrador).
        val target = iam.createUser()
        val allZero = iam.createRole(TestGrant(refunds, ALL, amount("0")))
        iam.assignRole(target, allZero)
        val ownHundred = iam.createRole(TestGrant(refunds, OWN, amount("100")))
        val etag = getUser(target.id.value, iam.asAdmin()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()

        // Cada concessão da role nova está coberta, mas a união daria ALL com teto 100.
        with(iam) {
            setRoles(target.id.value, listOf(allZero.value, ownHundred.value), etag, asManager)
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
        }
        rolesOf(target.id.value) shouldContainExactly listOf(allZero.value)
        refundOf(target) shouldBe "ALL:maxAmount=0"

        // Sem a combinação (o alvo sem a role ALL), a mesma role passa.
        val other = createdUserId()
        val otherEtag = getUser(other, iam.asAdmin()).getHeader(HttpHeaders.ETAG)
        with(iam) { setRoles(other, listOf(ownHundred.value), otherEtag, asManager).ok() }

        // O administrador, que tem tudo, pode.
        with(iam) { setRoles(target.id.value, listOf(allZero.value, ownHundred.value), etag, iam.asAdmin()).ok() }
        refundOf(target) shouldBe "ALL:maxAmount=100"
    }

    @Test
    fun `a combination that already existed and is not widened does not block the operation`() {
        val manager = iam.createUserWith(TestGrant(usersManage, ALL), TestGrant(refunds, OWN, amount("100")))
        // O alvo já tem ALL com teto 100, acima do ator, dado pelo administrador.
        val target = iam.createUser()
        val allHundred = iam.createRole(TestGrant(refunds, ALL, amount("100")))
        iam.assignRole(target, allHundred)
        val ownFifty = iam.createRole(TestGrant(refunds, OWN, amount("50")))
        val etag = getUser(target.id.value, iam.asAdmin()).getHeader(HttpHeaders.ETAG)

        // Acrescentar OWN/50 não amplia nada: a união continua ALL/100.
        with(iam) {
            setRoles(target.id.value, listOf(allHundred.value, ownFifty.value), etag, iam.asTestUser(manager)).ok()
        }
        refundOf(target) shouldBe "ALL:maxAmount=100"
    }

    @Test
    fun `an inactive role above the actor also blocks resetting password or PIN and reactivating`() {
        val manager = iam.createUserWith(TestGrant(usersManage, ALL), TestGrant(tasksRead, OWN))
        val asManager = iam.asTestUser(manager)
        val target = iam.createUserWith(TestGrant(tasksRead, OWN))
        iam.assignRole(target, iam.createRole(TestGrant(tasksComplete, ALL)).also(iam::deactivateRole))

        with(iam) {
            action(target.id.value, "reset-password", asManager, mapOf("newPassword" to PASSWORD))
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            action(target.id.value, "reset-pin", asManager, mapOf("newPin" to "1234"))
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            action(target.id.value, "deactivate", asManager).ok()
            action(target.id.value, "reactivate", asManager).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            iam.login(target.login, target.password).problem(401, "ACCOUNT_DISABLED")
        }
    }

    @Test
    fun `resetting the password or PIN of, or reactivating, someone with more permissions is refused`() {
        val manager = iam.createUserWith(TestGrant(usersManage, ALL), TestGrant(tasksRead, OWN))
        val asManager = iam.asTestUser(manager)
        val stronger = iam.createUserWith(TestGrant(tasksRead, ALL))
        val pinBefore = pinHashOf(stronger.id.value)

        with(iam) {
            action(stronger.id.value, "reset-password", asManager, mapOf("newPassword" to PASSWORD))
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            action(stronger.id.value, "reset-pin", asManager, mapOf("newPin" to "1234"))
                .problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
            // Desativar não concede nada; reativar devolveria as permissões dele.
            action(stronger.id.value, "deactivate", asManager).ok()
            action(stronger.id.value, "reactivate", asManager).problem(403, "GRANT_EXCEEDS_OWN_PERMISSIONS")
        }
        pinHashOf(stronger.id.value) shouldBe pinBefore
        with(iam) { action(stronger.id.value, "reactivate", iam.asAdmin()).ok() }
        with(iam) { iam.loginOk(stronger) }

        // Quem tem só o que o gerente tem pode ter a senha redefinida por ele.
        val peer = iam.createUserWith(TestGrant(tasksRead, OWN))
        with(
            iam,
        ) { action(peer.id.value, "reset-password", asManager, mapOf("newPassword" to PASSWORD)).expectStatus(204) }
    }

    // --- Senha e PIN ---------------------------------------------------------------------------------

    @Test
    fun `the administrator resets a password - provisional, sessions revoked and the lockout cleared`() {
        val user = iam.createUser()
        val session = iam.loginOk(user)
        repeat(LOCKOUT_FAILURES) { iam.login(user.login, "senha-errada-de-teste") }
        with(iam) { iam.login(user.login, user.password).problem(401, "ACCOUNT_LOCKED") }

        val response = action(user.id.value, "reset-password", iam.asAdmin(), mapOf("newPassword" to PASSWORD))
        with(iam) { response.expectStatus(204) }
        response.contentAsString shouldNotContain PASSWORD

        with(iam) {
            iam.refresh(session["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            // O bloqueio foi zerado: a senha antiga só não confere.
            iam.login(user.login, user.password).problem(401, "INVALID_CREDENTIALS")
            iam.login(user.login, PASSWORD).ok()["passwordChangeRequired"].asBoolean() shouldBe true
            action(user.id.value, "reset-password", iam.asAdmin(), mapOf("newPassword" to "curta"))
                .problem(400, "VALIDATION_FAILED")
        }
        sessionsOf(user.id.value) shouldContainAll listOf("PASSWORD_RESET")
    }

    @Test
    fun `the administrator sets the PIN of a user`() {
        val user = iam.createUser()
        pinHashOf(user.id.value).shouldBeNull()
        with(iam) { getUser(user.id.value, iam.asAdmin()).ok() }["hasPin"].asBoolean() shouldBe false

        val response = action(user.id.value, "reset-pin", iam.asAdmin(), mapOf("newPin" to "90817263"))
        with(iam) { response.expectStatus(204) }

        val hash = pinHashOf(user.id.value).shouldNotBeNull()
        hash shouldStartWith "\$argon2id\$"
        hasher.matches("90817263", hash) shouldBe true
        with(iam) { getUser(user.id.value, iam.asAdmin()).ok() }["hasPin"].asBoolean() shouldBe true
        with(iam) {
            action(user.id.value, "reset-pin", iam.asAdmin(), mapOf("newPin" to "12")).problem(400, "VALIDATION_FAILED")
            action(
                user.id.value,
                "reset-pin",
                iam.asAdmin(),
                mapOf("newPin" to "abcd"),
            ).problem(400, "VALIDATION_FAILED")
        }
    }

    // --- Sessões de outros usuários --------------------------------------------------------------------

    @Test
    fun `sessions of another user are listed and revoked with iam sessions manage`() {
        val manager = iam.createUserWith(TestGrant(sessionsManage, ALL))
        val asManager = iam.asTestUser(manager)
        val user = iam.createUser()
        val first = iam.loginOk(user, "device-a")
        val second = iam.loginOk(user, "device-b")

        val listed = with(iam) { iam.call(GET, "/api/v1/users/${user.id}/sessions", asManager).ok() }
        listed["items"].values().map { it["id"].asString() } shouldContainExactlyInAnyOrder
            listOf(first["sessionId"].asString(), second["sessionId"].asString())
        listed["items"].values().map { it["current"].asBoolean() } shouldContainExactly listOf(false, false)

        val revoke = "/api/v1/users/${user.id}/sessions/${first["sessionId"].asString()}/revoke"
        with(iam) {
            iam.call(POST, revoke, asManager).expectStatus(204)
            iam.refresh(first["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            iam.refresh(second["refreshToken"].asString()).ok()
            // Revogar de novo não muda nada.
            iam.call(POST, revoke, asManager).expectStatus(204)
        }
        sessionsOf(user.id.value) shouldContainAll listOf("REVOKED_BY_ADMINISTRATOR")

        val other = iam.createUser()
        val otherSession = iam.loginOk(other)["sessionId"].asString()
        with(iam) {
            iam
                .call(POST, "/api/v1/users/${user.id}/sessions/$otherSession/revoke", asManager)
                .problem(404, "SESSION_NOT_FOUND")
            iam.call(GET, "/api/v1/users/${UuidV7.generate()}/sessions", asManager).problem(404, "USER_NOT_FOUND")
            iam.call(GET, "/api/v1/users/${user.id}/sessions", iam.asTestUser(other)).problem(403, "PERMISSION_DENIED")
        }
        with(iam) { iam.refresh(iam.loginOk(other, "device-x")["refreshToken"].asString()).ok() }
    }

    // --- Apoio -----------------------------------------------------------------------------------------

    private fun definition(code: PermissionCode): PermissionDefinition =
        (IamPermissions.permissions + ExamplePermissions.permissions).single { it.code == code }

    private fun suffix(): String = UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)

    private fun newUser(login: String): Map<String, Any> =
        mapOf("name" to "Usuária de teste", "login" to login, "password" to PASSWORD)

    private fun create(
        body: Map<String, Any>,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(POST, "/api/v1/users", auth, body)

    /** Um usuário novo criado pela API, pelo administrador. */
    private fun createdUserId(): UUID =
        UUID.fromString(with(iam) { create(newUser("criada.${suffix()}")).expectStatus(201) }!!["id"].asString())

    private fun getUser(
        id: UUID,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse = iam.call(GET, "/api/v1/users/$id", auth)

    private fun rename(
        id: UUID,
        name: String,
        ifMatch: String?,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(PUT, "/api/v1/users/$id", auth, mapOf("name" to name), ifMatch)

    private fun setRoles(
        id: UUID,
        roleIds: List<UUID>,
        ifMatch: String?,
        auth: RequestPostProcessor,
    ): MockHttpServletResponse = iam.call(PUT, "/api/v1/users/$id/roles", auth, mapOf("roleIds" to roleIds), ifMatch)

    private fun action(
        id: UUID,
        action: String,
        auth: RequestPostProcessor,
        body: Any? = null,
    ): MockHttpServletResponse = iam.call(POST, "/api/v1/users/$id/$action", auth, body)

    private fun administratorRoleId(): RoleId =
        RoleId(
            jdbc
                .sql("SELECT id FROM iam.role WHERE system_role")
                .query(UUID::class.java)
                .single(),
        )

    /** Os outros administradores ativos (criados por testes) ficam inativos; o inicial continua. */
    private fun onlyTheInitialAdminIsActive() {
        jdbc
            .sql(
                """
                UPDATE iam.user_account SET active = false
                WHERE login <> :login AND active
                  AND id IN (SELECT ur.user_id FROM iam.user_role ur JOIN iam.role r ON r.id = ur.role_id
                             WHERE r.system_role)
                """.trimIndent(),
            ).param("login", TestAdmin.LOGIN)
            .update()
    }

    private fun rolesOf(user: UUID): List<UUID> =
        jdbc
            .sql("SELECT role_id FROM iam.user_role WHERE user_id = :id")
            .param("id", user)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** O motivo de revogação de cada sessão do usuário (nulo = ativa). */
    private fun sessionsOf(user: UUID): List<String?> =
        jdbc
            .sql("SELECT revocation_reason FROM iam.device_session WHERE user_id = :id")
            .param("id", user)
            .query { rs, _ -> rs.getString(1) }
            .list()

    private fun pinHashOf(user: UUID): String? =
        jdbc
            .sql("SELECT pin_hash FROM iam.user_account WHERE id = :id")
            .param("id", user)
            .query { rs, _ -> rs.getString(1) }
            .list()
            .single()

    private fun count(
        sql: String,
        id: Any,
    ): Int =
        jdbc
            .sql(sql)
            .param("id", id)
            .query(Int::class.java)
            .single()

    /** Espera até [expected] UPDATEs de `iam.user_account` estarem presos numa trava. */
    private fun awaitBlockedUpdates(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            val blocked =
                jdbc
                    .sql(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                            "AND query ILIKE 'update iam.user_account%'",
                    ).query(Int::class.java)
                    .single()
            if (blocked >= expected) return
            Thread.sleep(POLL_MILLIS)
        }
        error("As $expected edições não chegaram a esperar pela trava")
    }

    private fun percent(value: String) = LimitValue.Max(value.toBigDecimal())

    private fun amount(value: String) = mapOf(ExamplePermissions.MAX_AMOUNT to LimitValue.Max(value.toBigDecimal()))

    /** O estorno efetivo do usuário como `escopo:maxAmount=valor`, pelo `GET /auth/me`. */
    private fun refundOf(user: TestUser): String {
        val me = with(iam) { iam.call(GET, "/api/v1/auth/me", iam.asTestUser(user)).ok() }
        val refund =
            me["permissions"].values().single {
                it["code"].asString() == ExamplePermissions.REFUNDS_ISSUE.value
            }
        val limit = refund["limits"].values().single()
        return "${refund["scope"].asString()}:${limit["key"].asString()}=${limit["max"].asString()}"
    }

    private companion object {
        const val PASSWORD = "Senha-Provisoria-123"
        const val SUFFIX_LENGTH = 12
        const val LOCKOUT_FAILURES = 5
        const val WAIT_SECONDS = 20L
        const val POLL_MILLIS = 50L
    }
}

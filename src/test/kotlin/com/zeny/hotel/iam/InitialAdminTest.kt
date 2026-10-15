package com.zeny.hotel.iam

import com.zeny.hotel.iam.application.IamProperties
import com.zeny.hotel.iam.application.InitialAdminService
import com.zeny.hotel.iam.application.PasswordHasher
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.iam.application.Roles
import com.zeny.hotel.iam.application.Users
import com.zeny.hotel.iam.infrastructure.IamStartupRunner
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestAdmin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration

/**
 * Administrador inicial (H2): criado no primeiro boot a partir das variáveis de ambiente (nos
 * testes, `TestAuthInitializer`), uma única vez, e obrigado a trocar a senha antes de usar
 * qualquer outro endpoint.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
class InitialAdminTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val initialAdmin: InitialAdminService,
) {
    @Test
    fun `the initial admin is created once, with an argon2id hash, and a new boot does not create another`() {
        adminRows() shouldBe 1
        jdbc
            .sql("SELECT password_hash FROM iam.user_account WHERE login = :login")
            .param("login", TestAdmin.LOGIN)
            .query(String::class.java)
            .single() shouldStartWith "\$argon2id\$"

        // O que o boot faz de novo (outra instância, reinício): já há usuário, nada muda.
        initialAdmin.ensureInitialAdmin() shouldBe false
        adminRows() shouldBe 1
    }

    @Test
    fun `the initial admin holds the administrator system role`() {
        jdbc
            .sql(
                "SELECT r.template_key, r.system_role FROM iam.user_role ur JOIN iam.role r ON r.id = ur.role_id " +
                    "JOIN iam.user_account u ON u.id = ur.user_id WHERE u.login = :login",
            ).param("login", TestAdmin.LOGIN)
            .query { rs, _ -> rs.getString(1) to rs.getBoolean(2) }
            .list() shouldBe listOf("ADMINISTRATOR" to true)
    }

    @Test
    fun `the boot synchronizes the permission catalog before creating the initial admin`() {
        val catalog = mockk<PermissionCatalogSynchronizer>(relaxed = true)
        val admin = mockk<InitialAdminService>(relaxed = true)

        IamStartupRunner(catalog, admin).run(DefaultApplicationArguments())

        verifyOrder {
            catalog.synchronize()
            admin.ensureInitialAdmin()
        }
        // E o boot deste contexto deixou as permissões do iam vigentes na tabela.
        jdbc
            .sql("SELECT code FROM iam.permission WHERE module = 'iam' AND obsolete_at IS NULL")
            .query(String::class.java)
            .list()
            .toSet() shouldBe IamPermissions.permissions.map { it.code.value }.toSet()
    }

    @Test
    fun `the initial admin must change the password before using other endpoints`() {
        val newPassword = "${TestAdmin.password}-nova"
        val login = with(iam) { login(TestAdmin.LOGIN, TestAdmin.password).ok() }
        login["passwordChangeRequired"].asBoolean() shouldBe true
        val token = login["accessToken"].asString()

        with(iam) {
            sessions(token).problem(403, "PASSWORD_CHANGE_REQUIRED")
            // A troca confere a senha atual e recusa a mesma senha.
            postWithToken(PASSWORD, token, mapOf("currentPassword" to "errada-errada", "newPassword" to newPassword))
                .problem(401, "INVALID_CREDENTIALS")
            postWithToken(PASSWORD, token, mapOf("currentPassword" to TestAdmin.password, "newPassword" to "curta"))
                .problem(400, "VALIDATION_FAILED")
            postWithToken(
                PASSWORD,
                token,
                mapOf(
                    "currentPassword" to TestAdmin.password,
                    "newPassword" to TestAdmin.password,
                ),
            ).problem(422, "PASSWORD_UNCHANGED")
            postWithToken(PASSWORD, token, mapOf("currentPassword" to TestAdmin.password, "newPassword" to newPassword))
                .status shouldBe 204

            // O token antigo ainda carrega a pendência; o refresh traz um sem ela.
            sessions(token).problem(403, "PASSWORD_CHANGE_REQUIRED")
            val refreshed = refresh(login["refreshToken"].asString()).ok()
            refreshed["passwordChangeRequired"].asBoolean() shouldBe false
            sessions(refreshed["accessToken"].asString()).status shouldBe 200

            login(TestAdmin.LOGIN, TestAdmin.password).problem(401, "INVALID_CREDENTIALS")
            login(TestAdmin.LOGIN, newPassword).ok()["passwordChangeRequired"].asBoolean() shouldBe false
        }
    }

    @Test
    fun `changing the password revokes the other sessions of the user`() {
        val user = iam.createUser()
        val phone = iam.loginOk(user, deviceId = "phone")
        val tablet = iam.loginOk(user, deviceId = "tablet")

        with(iam) {
            postWithToken(
                PASSWORD,
                phone["accessToken"].asString(),
                mapOf("currentPassword" to user.password, "newPassword" to "${user.password}-nova"),
            ).status shouldBe 204
            refresh(tablet["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            refresh(phone["refreshToken"].asString()).ok()
        }
    }

    @Test
    fun `without users and without the variables the application does not start`() {
        val users = mockk<Users> { every { anyExists() } returns false }
        val service =
            InitialAdminService(
                users,
                mockk<Roles>(),
                mockk<PasswordHasher>(),
                properties(login = "", password = ""),
                mockk<AuditContext>(relaxed = true),
                Clock.systemUTC(),
            )

        val error = shouldThrow<IllegalStateException> { service.ensureInitialAdmin() }

        error.message!! shouldContain "ZENY_ADMIN_LOGIN"
        error.message!! shouldContain "ZENY_ADMIN_PASSWORD"
    }

    @Test
    fun `a weak initial password is refused without showing it`() {
        val users = mockk<Users> { every { anyExists() } returns false }
        val service =
            InitialAdminService(
                users,
                mockk<Roles>(),
                mockk<PasswordHasher>(),
                properties("admin", "curta123"),
                mockk<AuditContext>(relaxed = true),
                Clock.systemUTC(),
            )

        val error = shouldThrow<IllegalStateException> { service.ensureInitialAdmin() }

        error.message!! shouldContain "ZENY_ADMIN_PASSWORD"
        error.message!! shouldNotContain "curta123"
        verify(exactly = 0) { users.add(any()) }
    }

    private fun adminRows(): Int =
        jdbc
            .sql("SELECT count(*) FROM iam.user_account WHERE login = :login")
            .param("login", TestAdmin.LOGIN)
            .query(Int::class.java)
            .single()

    private fun properties(
        login: String,
        password: String,
    ) = IamProperties(
        accessToken = IamProperties.AccessToken(Duration.ofMinutes(15), "zeny-hotel-manager"),
        refreshToken = IamProperties.RefreshToken(Duration.ofDays(30)),
        login = IamProperties.Login(5, Duration.ofMinutes(1), Duration.ofHours(1)),
        approval = IamProperties.Approval(3, Duration.ofMinutes(1), Duration.ofHours(1)),
        passwordHashing = IamProperties.PasswordHashing(1024, 1, 1),
        initialAdmin = IamProperties.InitialAdmin(login, password),
    )

    private companion object {
        const val PASSWORD = "/api/v1/auth/password"
    }
}

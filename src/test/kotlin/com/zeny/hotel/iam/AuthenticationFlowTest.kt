package com.zeny.hotel.iam

import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.MutableClock
import com.zeny.hotel.testsupport.MutableClockConfiguration
import com.zeny.hotel.testsupport.TestJwtKeys
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fluxos de autenticação de ponta a ponta (M1 E01, ARCHITECTURE §8.1), pela API real: login,
 * refresh com rotação, reuso revogando a sessão, bloqueio progressivo, usuário desativado,
 * sessão revogada, validade dos tokens e o que fica no banco e nos logs. O relógio da aplicação
 * é um [MutableClock], que os testes avançam.
 */
@IntegrationTest
@Import(MutableClockConfiguration::class, IamTestConfiguration::class)
class AuthenticationFlowTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val clock: MutableClock,
    @Autowired private val jdbc: JdbcClient,
) {
    // --- Login ---------------------------------------------------------------------------------

    @Test
    fun `a valid login returns a 15 minute RS256 access token and a refresh token`() {
        val user = iam.createUser()
        val now = clock.instant()

        val body = iam.loginOk(user)

        body["tokenType"].asString() shouldBe "Bearer"
        body["userId"].asString() shouldBe user.id.toString()
        body["passwordChangeRequired"].asBoolean() shouldBe false
        Instant.parse(body["accessTokenExpiresAt"].asString()) shouldBe
            now.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(15))
        Instant.parse(body["refreshTokenExpiresAt"].asString()) shouldBe now.plus(Duration.ofDays(30))
        body["refreshToken"].asString() shouldMatch Regex("[A-Za-z0-9_-]{43}")

        val jwt = SignedJWT.parse(body["accessToken"].asString())
        jwt.header.algorithm.name shouldBe "RS256"
        jwt.header.keyID shouldBe TestJwtKeys.ACTIVE_KEY_ID
        val claims = jwt.jwtClaimsSet
        claims.subject shouldBe user.id.toString()
        claims.getStringClaim("sid") shouldBe body["sessionId"].asString()
        claims.issuer shouldBe "zeny-hotel-manager"
        Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant()) shouldBe
            Duration.ofMinutes(15)
        // Nada de login, nome ou permissões dentro do token.
        claims.claims.keys shouldContainExactlyInAnyOrder setOf("iss", "sub", "iat", "exp", "jti", "sid")

        // O token abre os endpoints protegidos.
        val sessions = iam.json(iam.sessions(body["accessToken"].asString()))
        sessions["items"].values().map { it["id"].asString() } shouldContainExactly listOf(body["sessionId"].asString())
        sessions["items"][0]["current"].asBoolean() shouldBe true
    }

    @Test
    fun `a wrong password or an unknown login is a 401 with a stable code that does not say which`() {
        val user = iam.createUser()

        with(iam) {
            val wrongPassword = login(user.login, "senha-errada-123").problem(401, "INVALID_CREDENTIALS")
            val unknownLogin = login("ninguem.aqui", user.password).problem(401, "INVALID_CREDENTIALS")
            login("x", user.password).problem(401, "INVALID_CREDENTIALS")
            wrongPassword["detail"] shouldBe unknownLogin["detail"]
        }
    }

    @Test
    fun `the login is compared without case`() {
        val user = iam.createUser()

        iam.login(user.login.uppercase(), user.password).status shouldBe 200
        iam.login("  ${user.login.replaceFirstChar { it.uppercase() }} ", user.password).status shouldBe 200
    }

    @Test
    fun `a new login on the same device replaces the previous session`() {
        val user = iam.createUser()
        val first = iam.loginOk(user, deviceId = "phone")
        val second = iam.loginOk(user, deviceId = "phone")
        val otherDevice = iam.loginOk(user, deviceId = "tablet")

        with(iam) {
            refresh(first["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            refresh(second["refreshToken"].asString()).status shouldBe 200
            refresh(otherDevice["refreshToken"].asString()).status shouldBe 200
        }
        revocationReasonOf(first["sessionId"].asString()) shouldBe "REPLACED_BY_LOGIN"
    }

    // --- Refresh e reuso ---------------------------------------------------------------------

    @Test
    fun `refresh returns a new pair and the previous refresh token stops working`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)
        clock.advance(Duration.ofDays(10))

        val refreshed = with(iam) { refresh(login["refreshToken"].asString()).ok() }

        refreshed["refreshToken"].asString() shouldNotBe login["refreshToken"].asString()
        refreshed["accessToken"].asString() shouldNotBe login["accessToken"].asString()
        refreshed["sessionId"].asString() shouldBe login["sessionId"].asString()
        // Validade deslizante: 30 dias a partir deste refresh.
        Instant.parse(refreshed["refreshTokenExpiresAt"].asString()) shouldBe clock.instant().plus(Duration.ofDays(30))
        iam.sessions(refreshed["accessToken"].asString()).status shouldBe 200
        // O novo continua renovando.
        with(iam) { refresh(refreshed["refreshToken"].asString()).ok() }
    }

    @Test
    fun `reusing a rotated refresh token revokes the whole session, including the newest token`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)
        val refreshed = with(iam) { refresh(login["refreshToken"].asString()).ok() }

        with(iam) {
            refresh(login["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_REUSED")
            // A sessão caiu: o refresh mais novo também não vale mais, e reapresentar o velho segue recusado.
            refresh(refreshed["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            refresh(login["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_REUSED")
        }
        revocationReasonOf(login["sessionId"].asString()) shouldBe "REFRESH_TOKEN_REUSED"
    }

    @Test
    fun `simultaneous refreshes with the same token renew at most once and revoke the session`() {
        val user = iam.createUser()
        val token = iam.loginOk(user)["refreshToken"].asString()
        val threads = 4
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        val responses =
            try {
                pool
                    .invokeAll(List(threads) { Callable { barrier.await().let { iam.refresh(token) } } })
                    .map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        val renewed = responses.filter { it.status == 200 }
        renewed shouldHaveSize 1
        responses.filter { it.status == 401 }.map { iam.json(it)["code"].asString() }.toSet() shouldBe
            setOf("REFRESH_TOKEN_REUSED")
        with(iam) { refresh(json(renewed.single())["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID") }
    }

    @Test
    fun `an unknown or expired refresh token is invalid`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)

        with(iam) {
            refresh("token-que-nao-existe").problem(401, "REFRESH_TOKEN_INVALID")
            clock.advance(Duration.ofDays(30).plusSeconds(1))
            refresh(login["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
        }
    }

    @Test
    fun `refresh works even when an expired access token is sent along`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)
        clock.advance(Duration.ofMinutes(16))

        with(iam) { refresh(login["refreshToken"].asString(), bearer = login["accessToken"].asString()).ok() }
    }

    // --- Validade do access token --------------------------------------------------------------

    @Test
    fun `the access token is rejected after 15 minutes`() {
        val user = iam.createUser()
        val accessToken = iam.loginOk(user)["accessToken"].asString()

        // `exp` é o login truncado ao segundo mais 15 min: 1 s antes ainda vale; logo depois, não.
        clock.advance(Duration.ofMinutes(15).minusSeconds(1))
        iam.sessions(accessToken).status shouldBe 200
        clock.advance(Duration.ofSeconds(1).plusMillis(1))
        with(iam) { sessions(accessToken).problem(401, "UNAUTHENTICATED") }
    }

    // --- Bloqueio progressivo ------------------------------------------------------------------

    @Test
    fun `after the configured failures the login is locked for a growing time, even with the right password`() {
        val user = iam.createUser()
        with(iam) {
            repeat(5) { login(user.login, "senha-errada-123").problem(401, "INVALID_CREDENTIALS") }
            // 5ª falha: bloqueado por 1 min, mesmo com a senha certa e com outra grafia do login.
            login(user.login, user.password).problem(401, "ACCOUNT_LOCKED")
            login(user.login.uppercase(), user.password).problem(401, "ACCOUNT_LOCKED")
            clock.advance(Duration.ofSeconds(59))
            login(user.login, user.password).problem(401, "ACCOUNT_LOCKED")

            // Venceu o 1º bloqueio; mais uma falha bloqueia pelo dobro (2 min).
            clock.advance(Duration.ofSeconds(2))
            login(user.login, "senha-errada-123").problem(401, "INVALID_CREDENTIALS")
            clock.advance(Duration.ofSeconds(61))
            login(user.login, user.password).problem(401, "ACCOUNT_LOCKED")
            clock.advance(Duration.ofSeconds(60))
            login(user.login, user.password).ok()
        }
        failedLoginCountOf(user) shouldBe 0
        // A contagem recomeçou: uma falha não bloqueia mais.
        with(iam) {
            login(user.login, "senha-errada-123").problem(401, "INVALID_CREDENTIALS")
            login(user.login, user.password).ok()
        }
    }

    @Test
    fun `failures with another spelling of the login count for the same user`() {
        val user = iam.createUser()
        with(iam) {
            listOf(user.login.uppercase(), " ${user.login}", user.login, user.login.uppercase(), user.login).forEach {
                login(it, "senha-errada-123").problem(401, "INVALID_CREDENTIALS")
            }
            login(user.login, user.password).problem(401, "ACCOUNT_LOCKED")
        }
    }

    // --- Usuário desativado e sessão revogada ------------------------------------------------

    @Test
    fun `a deactivated user cannot log in nor refresh`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)
        iam.deactivate(user)

        with(iam) {
            login(user.login, user.password).problem(401, "ACCOUNT_DISABLED")
            login(user.login, "senha-errada-123").problem(401, "INVALID_CREDENTIALS")
            refresh(login["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
        }
        revocationReasonOf(login["sessionId"].asString()) shouldBe "USER_DISABLED"
    }

    @Test
    fun `after logout the session no longer refreshes`() {
        val user = iam.createUser()
        val login = iam.loginOk(user)

        iam.postWithToken("/api/v1/auth/logout", login["accessToken"].asString()).status shouldBe 204

        with(iam) { refresh(login["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID") }
        revocationReasonOf(login["sessionId"].asString()) shouldBe "LOGOUT"
    }

    @Test
    fun `a user revokes one of their own sessions, but not someone else's`() {
        val user = iam.createUser()
        val phone = iam.loginOk(user, deviceId = "phone")
        val tablet = iam.loginOk(user, deviceId = "tablet")
        val other = iam.loginOk(iam.createUser())
        val tabletSession = tablet["sessionId"].asString()

        val listed = iam.json(iam.sessions(phone["accessToken"].asString()))["items"]
        listed.values().map { it["id"].asString() } shouldContainExactly
            listOf(tabletSession, phone["sessionId"].asString())
        listed.values().map { it["deviceId"].asString() } shouldContainExactly listOf("tablet", "phone")

        with(iam) {
            postWithToken(
                "/api/v1/auth/sessions/${other["sessionId"].asString()}/revoke",
                phone["accessToken"].asString(),
            ).problem(404, "SESSION_NOT_FOUND")
            postWithToken("/api/v1/auth/sessions/$tabletSession/revoke", phone["accessToken"].asString())
                .status shouldBe 204
            refresh(tablet["refreshToken"].asString()).problem(401, "REFRESH_TOKEN_INVALID")
            refresh(other["refreshToken"].asString()).ok()
        }
        iam.json(iam.sessions(phone["accessToken"].asString()))["items"].values().map {
            it["id"].asString()
        } shouldContainExactly
            listOf(phone["sessionId"].asString())
    }

    @Test
    fun `the session list pages by cursor`() {
        val user = iam.createUser()
        val tokens = listOf("a", "b", "c").map { iam.loginOk(user, deviceId = it) }
        val accessToken = tokens.last()["accessToken"].asString()

        val first = iam.json(iam.getWithToken("/api/v1/auth/sessions?limit=2", accessToken))
        first["items"].size() shouldBe 2
        val second =
            iam.json(
                iam.getWithToken("/api/v1/auth/sessions?limit=2&cursor=${first["nextCursor"].asString()}", accessToken),
            )
        second["items"].values().map { it["deviceId"].asString() } shouldContainExactly listOf("a")
        second["nextCursor"].isNull shouldBe true
    }

    // --- Segredos ------------------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `password and refresh token are stored only as hashes and never logged`(output: CapturedOutput) {
        val user = iam.createUser()
        val login = iam.loginOk(user)
        iam.login(user.login, "senha-errada-123")
        val refreshed = with(iam) { refresh(login["refreshToken"].asString()).ok() }
        iam.refresh(login["refreshToken"].asString())

        val stored = iamRowsAsText()
        val passwordHash = column("SELECT password_hash FROM iam.user_account WHERE id = :id", user.id.value)
        passwordHash shouldStartWith "\$argon2id\$v=19\$"
        withClue("hash do refresh vigente") {
            column(
                "SELECT refresh_token_hash FROM iam.device_session WHERE id = :id",
                UUID.fromString(login["sessionId"].asString()),
            ) shouldBe
                sha256(refreshed["refreshToken"].asString())
        }
        val secrets =
            listOf(
                user.password,
                login["refreshToken"].asString(),
                refreshed["refreshToken"].asString(),
                login["accessToken"].asString(),
                refreshed["accessToken"].asString(),
            )
        secrets.forEach { secret ->
            withClue("segredo no banco") { stored shouldNotContain secret }
            withClue("segredo no log") { output.all shouldNotContain secret }
        }
        withClue("login no log") { output.all shouldNotContain user.login }
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `logs of an authenticated request carry the user and the device session, not the login`(
        output: CapturedOutput,
    ) {
        val user = iam.createUser()
        val login = iam.loginOk(user)

        iam.postWithToken("/api/v1/auth/logout", login["accessToken"].asString()).status shouldBe 204

        val logoutLine = output.all.lines().single { it.contains("Logout") }
        logoutLine shouldContain user.id.toString()
        logoutLine shouldContain login["sessionId"].asString()
        logoutLine shouldNotContain user.login
        logoutLine shouldNotContain login["accessToken"].asString()
    }

    private fun revocationReasonOf(sessionId: String): String? =
        jdbc
            .sql("SELECT revocation_reason FROM iam.device_session WHERE id = :id")
            .param("id", UUID.fromString(sessionId))
            .query(String::class.java)
            .single()

    private fun failedLoginCountOf(user: TestUser): Int =
        jdbc
            .sql("SELECT failed_login_count FROM iam.user_account WHERE id = :id")
            .param("id", user.id.value)
            .query(Int::class.java)
            .single()

    private fun column(
        sql: String,
        id: UUID,
    ): String =
        jdbc
            .sql(sql)
            .param("id", id)
            .query(String::class.java)
            .single()

    /** Todas as linhas das tabelas do `iam` como texto, para procurar segredos em claro. */
    private fun iamRowsAsText(): String =
        listOf("user_account", "device_session", "rotated_refresh_token").joinToString("\n") { table ->
            jdbc
                .sql("SELECT CAST(t AS text) FROM iam.$table t")
                .query(String::class.java)
                .list()
                .joinToString("\n")
        }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private companion object {
        const val WAIT_SECONDS = 30L
    }
}

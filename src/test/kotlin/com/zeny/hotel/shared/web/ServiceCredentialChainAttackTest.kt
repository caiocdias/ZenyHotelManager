package com.zeny.hotel.shared.web

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.ServiceCredentialRealm
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestJwtKeys
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.ServletException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ataques ao isolamento das duas cadeias de segurança (D-E10-1): token de realm, JWT de usuário válido, JWT vencido
 * e nada, contra caminhos de realm (com variações), de usuário e públicos; formatos do cabeçalho `Authorization`;
 * sessão, cookie, CSRF e despacho assíncrono; vazamento do token; falha do realm. Dois realms de teste (`attack-a` e
 * `attack-b`), com um `@TestConfiguration` próprio.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
@Import(ServiceCredentialChainAttackTest.AttackConfiguration::class, IamTestConfiguration::class)
@Suppress("TooManyFunctions")
class ServiceCredentialChainAttackTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val clock: java.time.Clock,
) {
    @Test
    fun `each token class gets the same verdict on the realm paths`() {
        val userJwt = userJwt()
        val expired = expiredJwt()
        REALM_PATHS.forEach { path ->
            assertSoftly {
                withClue("$path com o token do realm") { call(path, "Bearer $TOKEN_A").status shouldBe 200 }
                withClue("$path com JWT de usuário") { expectUnauthenticated(call(path, "Bearer $userJwt")) }
                withClue("$path com JWT vencido") { expectUnauthenticated(call(path, "Bearer $expired")) }
                withClue("$path sem token") { expectUnauthenticated(call(path, null)) }
            }
        }
    }

    @Test
    fun `each token class gets the same verdict on the user paths`() {
        val userJwt = userJwt()
        val expired = expiredJwt()
        USER_PATHS.forEach { path ->
            assertSoftly {
                withClue("$path com o token do realm") { expectUnauthenticated(call(path, "Bearer $TOKEN_A")) }
                withClue("$path com JWT de usuário") { call(path, "Bearer $userJwt").status shouldBe 200 }
                withClue("$path com JWT vencido") { expectUnauthenticated(call(path, "Bearer $expired")) }
                withClue("$path sem token") { expectUnauthenticated(call(path, null)) }
            }
        }
    }

    @Test
    fun `a sibling prefix that only shares the realm name is not served by the realm chain`() {
        expectUnauthenticated(call("/api/v1/attack-ab/ping", "Bearer $TOKEN_A"))
        calls.shouldBeEmpty()
    }

    @Test
    fun `public paths ignore a realm token and a stale jwt`() {
        listOf(null, "Bearer $TOKEN_A", "Bearer ${expiredJwt()}").forEach { header ->
            withClue("cabeçalho $header") { call("/api/v1/meta/version", header).status shouldBe 200 }
        }
        calls.shouldBeEmpty()
    }

    @Test
    fun `a realm token on login, actuator and docs never reaches a realm and never fails with a server error`() {
        val paths = listOf("/actuator/health/liveness", "/actuator/env", "/v3/api-docs", "/swagger-ui.html", "/error")
        assertSoftly {
            paths.forEach { path ->
                withClue(path) { (call(path, "Bearer $TOKEN_A").status < SERVER_ERROR) shouldBe true }
            }
            val login =
                mockMvc
                    .perform(
                        MockMvcRequestBuilders
                            .post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer $TOKEN_A"),
                    ).andReturn()
                    .response
            (login.status in listOf(400, 401, 422)) shouldBe true
        }
        calls.shouldBeEmpty()
    }

    @Test
    fun `non canonical spellings of a realm path never yield a 200 or a 500`() {
        val paths =
            listOf(
                "/API/V1/ATTACK-A/ping",
                "/api/v1/ATTACK-A/ping",
                "/api/v1/attack-a/PING",
                "/api/v1//attack-a/ping",
                "/api/v1/attack-a//ping",
                "/api/v1/./attack-a/ping",
                "/api/v1/attack-a/./ping",
                "/api/v1/attack-a/sub/../ping",
                "/api/v1/sub/../attack-a/ping",
                "/api/v1/attack-a%2Fping",
                "/api/v1%2Fattack-a/ping",
                "/api/v1/attack-a/%2e%2e/auth/sessions",
                "/api/v1/attack-a/%2E/ping",
                "/api/v1/attack-a/ping;jsessionid=1",
                "/api/v1/attack-a;x=1/ping",
                "/api/v1/attack-a/ping%00",
                "/api/v1/attack-a/ping%0d%0a",
                "/api/v1/attack-a/..;/auth/sessions",
            )
        val jwt = userJwt()
        assertSoftly {
            paths.forEach { path ->
                listOf("Bearer $TOKEN_A", "Bearer $jwt", null).forEach { header ->
                    val status = callUri(path, header).status
                    withClue("$path com ${header?.take(HEADER_CLUE)} deu $status") {
                        (status in listOf(400, 401, 403, 404)) shouldBe true
                    }
                }
            }
        }
    }

    @Test
    fun `an unreserved character spelled in percent form is the same realm path for the chain and for mvc`() {
        val path = "/api/v1/attack%2Da/ping"

        callUri(path, "Bearer $TOKEN_A").status shouldBe 200
        expectUnauthenticated(callUri(path, null))
        expectUnauthenticated(callUri(path, "Bearer ${userJwt()}"))
        expectUnauthenticated(callUri("/api/v1/attack%2Dab/ping", "Bearer $TOKEN_A"))
    }

    @Test
    fun `the trailing slash on a realm path stays in the realm chain`() {
        expectUnauthenticated(call("/api/v1/attack-a/ping/", null))
        expectUnauthenticated(call("/api/v1/attack-a/", null))
        expectUnauthenticated(call("/api/v1/attack-a/nothing-here", null))
        (call("/api/v1/attack-a/nothing-here", "Bearer $TOKEN_A").status in listOf(404, 405)) shouldBe true
    }

    @Test
    fun `the realm is picked by the path and a token of another realm is refused there`() {
        call("/api/v1/attack-b/ping", "Bearer $TOKEN_B").status shouldBe 200
        calls.clear()

        expectUnauthenticated(call("/api/v1/attack-b/ping", "Bearer $TOKEN_A"))
        expectUnauthenticated(call("/api/v1/attack-a/ping", "Bearer $TOKEN_B"))

        calls shouldBe listOf("attack-b", "attack-a")
    }

    @Test
    fun `the realm name in the principal is the realm of the path`() {
        val a = jsonMapper.readTree(call("/api/v1/attack-a/ping", "Bearer $TOKEN_A").getContentAsString(Charsets.UTF_8))
        val b = jsonMapper.readTree(call("/api/v1/attack-b/ping", "Bearer $TOKEN_B").getContentAsString(Charsets.UTF_8))

        a["realm"].asString() shouldBe "attack-a"
        b["realm"].asString() shouldBe "attack-b"
    }

    @Test
    fun `header spellings are judged by the rules and never by the realm when malformed`() {
        val rejected =
            listOf(
                "Bearer ",
                "Bearer    ",
                "Bearer $TOKEN_A",
                "Bearer $TOKEN_A ",
                " Bearer $TOKEN_A",
                "Bearer=$TOKEN_A",
                "Bearer $TOKEN_A=x",
                "Bearer =",
                "Bearer töken",
                "Basic $TOKEN_A",
                "Digest $TOKEN_A",
                "Bearer \"$TOKEN_A\"",
                "Bearer $TOKEN_A;",
                "Bearer ${"a".repeat(HUGE)}é",
            )
        assertSoftly {
            rejected.forEach { header ->
                withClue("cabeçalho ${header.take(HEADER_CLUE)}") { expectUnauthenticated(call(PING_A, header)) }
            }
        }
        calls.shouldBeEmpty()
    }

    @Test
    fun `the scheme name is case insensitive`() {
        call(PING_A, "bearer $TOKEN_A").status shouldBe 200
        call(PING_A, "BEARER $TOKEN_A").status shouldBe 200
    }

    @Test
    fun `a very long token is refused by the realm and never a 500`() {
        val started = System.nanoTime()
        listOf(HUGE, HUGE * 10).forEach { size ->
            expectUnauthenticated(call(PING_A, "Bearer ${"a".repeat(size)}"))
            expectUnauthenticated(call(PING_A, "Bearer ${"a".repeat(size)}=" + "=".repeat(size)))
            expectUnauthenticated(call(PING_A, "Bearer " + "=".repeat(size)))
        }
        val millis = Duration.ofNanos(System.nanoTime() - started).toMillis()
        withClue("o casamento do cabeçalho leva tempo linear (levou ${millis}ms)") {
            (millis < REGEX_BUDGET_MS) shouldBe true
        }
    }

    @Test
    fun `several authorization headers are a 401 whatever the mix`() {
        val mixes =
            listOf(
                listOf("Bearer $TOKEN_A", "Bearer $TOKEN_B"),
                listOf("Bearer $TOKEN_A", "Basic dXNlcjpwYXNz"),
                listOf("Basic dXNlcjpwYXNz", "Bearer $TOKEN_A"),
                listOf("Bearer $TOKEN_A", ""),
                listOf("", "Bearer $TOKEN_A"),
            )
        mixes.forEach { values ->
            val response =
                mockMvc
                    .perform(
                        MockMvcRequestBuilders
                            .get(
                                PING_A,
                            ).apply { values.forEach { header(HttpHeaders.AUTHORIZATION, it) } },
                    ).andReturn()
                    .response
            withClue(values.toString()) { expectUnauthenticated(response) }
        }
        calls.shouldBeEmpty()
    }

    @Test
    fun `a query parameter or form field named like a token is ignored`() {
        listOf("access_token", "token", "Authorization").forEach { name ->
            val response =
                mockMvc
                    .perform(MockMvcRequestBuilders.get(PING_A).param(name, TOKEN_A))
                    .andReturn()
                    .response
            withClue(name) { expectUnauthenticated(response) }
        }
        val form =
            mockMvc
                .perform(MockMvcRequestBuilders.post(POST_A).param("access_token", TOKEN_A))
                .andReturn()
                .response
        expectUnauthenticated(form)
        calls.shouldBeEmpty()
    }

    @Test
    fun `a realm token in a cookie or another header is ignored`() {
        val response =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get(PING_A)
                        .cookie(jakarta.servlet.http.Cookie("access_token", TOKEN_A))
                        .header("X-Authorization", "Bearer $TOKEN_A")
                        .header("Proxy-Authorization", "Bearer $TOKEN_A"),
                ).andReturn()
                .response

        expectUnauthenticated(response)
        calls.shouldBeEmpty()
    }

    @Test
    fun `the realm chain keeps no session, no cookie and needs no csrf token`() {
        val ok =
            mockMvc
                .perform(MockMvcRequestBuilders.post(POST_A).header(HttpHeaders.AUTHORIZATION, "Bearer $TOKEN_A"))
                .andReturn()
        val refused = mockMvc.perform(MockMvcRequestBuilders.post(POST_A)).andReturn()

        assertSoftly {
            ok.response.status shouldBe 200
            ok.request.getSession(false).shouldBeNull()
            ok.response.getHeaders(HttpHeaders.SET_COOKIE).shouldBeEmpty()
            ok.response.cookies
                .toList()
                .shouldBeEmpty()
            expectUnauthenticated(refused.response)
            refused.request.getSession(false).shouldBeNull()
            refused.response.getHeaders(HttpHeaders.SET_COOKIE).shouldBeEmpty()
        }
    }

    @Test
    fun `a session cookie does not authenticate the realm path and a realm request does not leave one behind`() {
        val response =
            mockMvc
                .perform(
                    MockMvcRequestBuilders
                        .get(PING_A)
                        .cookie(jakarta.servlet.http.Cookie("JSESSIONID", "A".repeat(32))),
                ).andReturn()

        expectUnauthenticated(response.response)
        response.response.getHeaders(HttpHeaders.SET_COOKIE).shouldBeEmpty()
    }

    @Test
    fun `other methods are judged by the realm chain too`() {
        val methods = listOf(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
        methods.forEach { method ->
            val response = mockMvc.perform(MockMvcRequestBuilders.request(method, PING_A)).andReturn().response
            withClue(method.name()) {
                // HEAD e OPTIONS sem token: nunca 200.
                (response.status in listOf(401, 403, 404, 405)) shouldBe true
            }
        }
        expectUnauthenticated(
            mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.DELETE, PING_A)).andReturn().response,
        )
    }

    @Test
    fun `the async dispatch of a realm request finishes with the principal and a 200`() {
        val started =
            mockMvc
                .perform(MockMvcRequestBuilders.get(ASYNC_A).header(HttpHeaders.AUTHORIZATION, "Bearer $TOKEN_A"))
                .andReturn()
        started.request.isAsyncStarted shouldBe true

        val done = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(started)).andReturn().response

        done.status shouldBe 200
        jsonMapper.readTree(done.getContentAsString(Charsets.UTF_8))["realm"].asString() shouldBe "attack-a"
    }

    @Test
    fun `an async endpoint of a realm is not started without a valid token`() {
        val response = call(ASYNC_A, null)
        expectUnauthenticated(response)
        expectUnauthenticated(call(ASYNC_A, "Bearer $TOKEN_B"))
        expectUnauthenticated(call(ASYNC_A, "Bearer ${userJwt()}"))
    }

    @Test
    fun `the realm principal has no authorities, no credentials and never prints the token`() {
        val body =
            jsonMapper.readTree(call("/api/v1/attack-a/auth", "Bearer $TOKEN_A").getContentAsString(Charsets.UTF_8))

        assertSoftly {
            body["authorities"].size() shouldBe 0
            body["authenticated"].asBoolean() shouldBe true
            body["credentials"].isNull shouldBe true
            body["principalType"].asString() shouldBe ServicePrincipal::class.java.name
            body["text"].asString() shouldNotContain TOKEN_A
            body["text"].asString() shouldNotContain STAMP
        }
    }

    @Test
    fun `a realm token never alcança the permission check or the actor of a user`() {
        val body =
            jsonMapper.readTree(
                call("/api/v1/attack-a/actor", "Bearer $TOKEN_A").getContentAsString(Charsets.UTF_8),
            )
        val require = call("/api/v1/attack-a/require", "Bearer $TOKEN_A")

        assertSoftly {
            body["actor"].isNull shouldBe true
            (require.status in listOf(401, 403)) shouldBe true
            require.getContentAsString(Charsets.UTF_8) shouldNotContain TOKEN_A
        }
    }

    @Test
    fun `the security context does not leak from a realm request to the next one in the same thread`() {
        call(PING_A, "Bearer $TOKEN_A").status shouldBe 200
        SecurityContextHolder.getContext().authentication.shouldBeNull()
        expectUnauthenticated(call(PING_A, null))
        expectUnauthenticated(call("/api/v1/auth/sessions", null))
        call(PING_A, "Bearer $TOKEN_A").status shouldBe 200
        expectUnauthenticated(call("/api/v1/auth/sessions", "Bearer $TOKEN_A"))
    }

    @Test
    fun `every refusal of the realm chain is the same problem with a bearer challenge and no extra detail`() {
        val jwt = userJwt()
        val headers =
            listOf(
                null,
                "Bearer $TOKEN_B",
                "Bearer $jwt",
                "Basic abc",
                "Bearer ",
                "Bearer a b",
                "bearer ${expiredJwt()}",
            )
        val problems =
            headers
                .map { call(PING_A, it) }
                .map { response ->
                    expectUnauthenticated(response)
                    response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
                    val tree = jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))
                    listOf("type", "title", "status", "detail", "code").map { tree[it]?.toString() }
                }

        problems.toSet() shouldHaveSize 1
    }

    @Test
    fun `the token never shows up in the log or in any response`(output: CapturedOutput) {
        val jwt = userJwt()
        val responses =
            listOf(
                call(PING_A, "Bearer $TOKEN_A"),
                call(PING_A, "Bearer $TOKEN_B"),
                call(PING_A, "Bearer $TOKEN_A extra"),
                call(PING_A, "Basic $TOKEN_A"),
                call("/api/v1/auth/sessions", "Bearer $TOKEN_A"),
                call("/api/v1/attack-a/fail", "Bearer $TOKEN_A"),
                call(PING_A, "Bearer $jwt"),
            )

        val appLog = output.all.lines().filterNot { it.trim().startsWith("Headers = [") }
        assertSoftly {
            responses.drop(1).forEach { it.getContentAsString(Charsets.UTF_8) shouldNotContain TOKEN_A }
            responses.forEach {
                it.getContentAsString(Charsets.UTF_8) shouldNotContain TOKEN_B
                it.getContentAsString(Charsets.UTF_8) shouldNotContain jwt
            }
            appLog.filter { it.contains(TOKEN_A) || it.contains(TOKEN_B) || it.contains(jwt) }.shouldBeEmpty()
        }
    }

    @Test
    fun `a controller failure under the realm chain is a generic 500 problem without the detail`() {
        val response = call("/api/v1/attack-a/fail", "Bearer $TOKEN_A")

        response.status shouldBe SERVER_ERROR
        response.getContentAsString(Charsets.UTF_8) shouldNotContain CONTROLLER_SECRET
    }

    @Test
    fun `a realm that throws fails closed and does not leak its detail or the token`(output: CapturedOutput) {
        var response: MockHttpServletResponse? = null
        val thrown =
            runCatching { response = call(PING_A, "Bearer $TOKEN_BOOM") }.exceptionOrNull()

        withClue("falha do realm: resposta=${response?.status} exceção=${thrown?.javaClass}") {
            if (thrown == null) {
                (response!!.status >= SERVER_ERROR) shouldBe true
                response.getContentAsString(Charsets.UTF_8) shouldNotContain REALM_SECRET
                response.getContentAsString(Charsets.UTF_8) shouldNotContain TOKEN_BOOM
            } else {
                (thrown is ServletException || thrown is IllegalStateException) shouldBe true
                thrown.message.orEmpty() shouldNotContain TOKEN_BOOM
            }
        }
        output.all
            .lines()
            .filterNot { it.trim().startsWith("Headers = [") }
            .filter { it.contains(TOKEN_BOOM) }
            .shouldBeEmpty()
        SecurityContextHolder.getContext().authentication.shouldBeNull()
        // E a cadeia continua de pé depois da falha.
        call(PING_A, "Bearer $TOKEN_A").status shouldBe 200
        expectUnauthenticated(call(PING_A, null))
    }

    @Test
    fun `a realm that throws never grants access on any path`() {
        listOf(PING_A, "/api/v1/auth/sessions", "/api/v1/attack-ab/ping").forEach { path ->
            val outcome = runCatching { call(path, "Bearer $TOKEN_BOOM") }
            val status = outcome.getOrNull()?.status
            withClue("$path: $status ${outcome.exceptionOrNull()}") { (status == null || status != 200) shouldBe true }
        }
    }

    @Test
    fun `a prefix with a single character wildcard is refused at startup`() {
        // `?` casa um caractere no PathPattern: o realm "a?" alcançaria "/api/v1/ab/" sem que a partida note.
        shouldThrow<IllegalStateException> { ServiceCredentialRealms.validate(listOf(stub("a", "/api/v1/a?/"))) }
    }

    @Test
    fun `two realms whose prefixes overlap through a wildcard refuse the startup`() {
        shouldThrow<IllegalStateException> {
            ServiceCredentialRealms.validate(listOf(stub("a", "/api/v1/a?/"), stub("b", "/api/v1/ab/")))
        }
    }

    @Test
    fun `realms that differ only in case or share a name stem are accepted and do not overlap`() {
        ServiceCredentialRealms.validate(
            listOf(stub("a", "/api/v1/a/"), stub("ab", "/api/v1/ab/"), stub("up", "/api/v1/A/")),
        )
    }

    @Test
    fun `a prefix that tries to leave the api through dot segments or a query is refused at startup`() {
        listOf("/api/v1/../admin/", "/api/v1/a/./", "/api/v1/a?x=1/", "/api/v1/a#/", "/api/v1/a b/").forEach { prefix ->
            assertSoftly {
                withClue(prefix) {
                    shouldThrow<IllegalStateException> { ServiceCredentialRealms.validate(listOf(stub("a", prefix))) }
                }
            }
        }
    }

    private fun stub(
        name: String,
        prefix: String,
    ) = object : ServiceCredentialRealm {
        override val name = name
        override val pathPrefix = prefix
        override val openApiScheme = "${name}Scheme"

        override fun authenticate(token: String): ServicePrincipal? = null
    }

    private fun userJwt(): String = iam.loginOk(iam.createUser())["accessToken"].asString()

    private fun expiredJwt(): String {
        val now = clock.instant()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer("zeny-hotel-manager")
                .subject(UuidV7.generate().toString())
                .issueTime(Date.from(now.minusSeconds(EXPIRED_AGO * 2)))
                .expirationTime(Date.from(now.minusSeconds(EXPIRED_AGO)))
                .jwtID(UuidV7.generate().toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        val header =
            JWSHeader
                .Builder(
                    JWSAlgorithm.RS256,
                ).type(JOSEObjectType.JWT)
                .keyID(TestJwtKeys.ACTIVE_KEY_ID)
                .build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(TestJwtKeys.active.private)) }.serialize()
    }

    private fun call(
        path: String,
        authorization: String?,
    ): MockHttpServletResponse = callUri(path, authorization)

    private fun callUri(
        path: String,
        authorization: String?,
    ): MockHttpServletResponse =
        mockMvc
            .perform(
                MockMvcRequestBuilders.get(URI.create(path)).apply {
                    if (authorization != null) header(HttpHeaders.AUTHORIZATION, authorization)
                },
            ).andReturn()
            .response

    private fun expectUnauthenticated(response: MockHttpServletResponse) {
        response.status shouldBe 401
        response.getHeader(HttpHeaders.WWW_AUTHENTICATE) shouldBe "Bearer"
        MediaType.parseMediaType(response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe
            true
        jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))["code"].asString() shouldBe "UNAUTHENTICATED"
    }

    /** Dois realms só de teste e os controllers que os exercitam. Nada disto existe em produção. */
    @TestConfiguration(proxyBeanMethods = false)
    class AttackConfiguration {
        @Bean
        fun attackRealmA(): ServiceCredentialRealm = AttackRealm("attack-a", "/api/v1/attack-a/", "attackAToken")

        @Bean
        fun attackRealmB(): ServiceCredentialRealm = AttackRealm("attack-b", "/api/v1/attack-b/", "attackBToken")

        @Bean
        fun attackController(
            authorization: Authorization,
            currentActor: CurrentActor,
        ) = AttackController(authorization, currentActor)
    }

    class AttackRealm(
        override val name: String,
        override val pathPrefix: String,
        override val openApiScheme: String,
    ) : ServiceCredentialRealm {
        override fun authenticate(token: String): ServicePrincipal? {
            calls += name
            check(token != TOKEN_BOOM) { REALM_SECRET }
            val expected = if (name == "attack-a") TOKEN_A else TOKEN_B
            return if (token ==
                expected
            ) {
                ServicePrincipal(name, UUID.nameUUIDFromBytes(name.toByteArray()), STAMP)
            } else {
                null
            }
        }
    }

    @RestController
    class AttackController(
        private val authorization: Authorization,
        private val currentActor: CurrentActor,
    ) {
        @GetMapping(
            "/api/v1/attack-a",
            "/api/v1/attack-a/ping",
            "/api/v1/attack-a/sub/deeper",
            "/api/v1/attack-b/ping",
            "/api/v1/attack-ab/ping",
        )
        fun ping(): Map<String, Any?> {
            val authentication = SecurityContextHolder.getContext().authentication
            val principal = authentication?.principal as? ServicePrincipal
            return mapOf("realm" to principal?.realm, "id" to principal?.id?.toString())
        }

        @PostMapping("/api/v1/attack-a/post")
        fun post() = mapOf("ok" to true)

        @GetMapping("/api/v1/attack-a/async")
        fun async(): Callable<Map<String, Any?>> {
            val principal = SecurityContextHolder.getContext().authentication?.principal as? ServicePrincipal
            return Callable { mapOf("realm" to principal?.realm) }
        }

        @GetMapping("/api/v1/attack-a/auth")
        fun auth(): Map<String, Any?> {
            val authentication = SecurityContextHolder.getContext().authentication!!
            return mapOf(
                "authorities" to authentication.authorities.map { it.authority },
                "authenticated" to authentication.isAuthenticated,
                "credentials" to authentication.credentials,
                "principalType" to authentication.principal!!.javaClass.name,
                "text" to authentication.toString() + authentication.principal.toString(),
            )
        }

        @GetMapping("/api/v1/attack-a/actor")
        fun actor(): Map<String, Any?> = mapOf("actor" to currentActor.currentOrNull()?.userId?.toString())

        @GetMapping("/api/v1/attack-a/require")
        fun require(): Map<String, Any?> {
            authorization.require(PermissionCode("fnrh.settings.manage"))
            return mapOf("granted" to true)
        }

        @GetMapping("/api/v1/attack-a/fail")
        fun fail(): Nothing = error(CONTROLLER_SECRET)
    }

    companion object {
        const val TOKEN_A = "attack-a-token-0123456789abcdef"
        const val TOKEN_B = "attack-b-token-fedcba9876543210"
        const val TOKEN_BOOM = "boom-token-aaaaaaaaaaaaaaaa"
        const val STAMP = "stamp-attack-777"
        const val REALM_SECRET = "realm-secret-db-password-hunter2"
        const val CONTROLLER_SECRET = "controller-secret-jdbc-url-hunter3"
        const val PING_A = "/api/v1/attack-a/ping"
        const val POST_A = "/api/v1/attack-a/post"
        const val ASYNC_A = "/api/v1/attack-a/async"
        const val SERVER_ERROR = 500
        const val HUGE = 20_000
        const val HEADER_CLUE = 40
        const val EXPIRED_AGO = 60L
        const val REGEX_BUDGET_MS = 20_000L
        val REALM_PATHS = listOf(PING_A, "/api/v1/attack-a", "/api/v1/attack-a/sub/deeper")
        val USER_PATHS = listOf("/api/v1/auth/sessions", "/api/v1/attack-ab/ping")

        /** Os realms que `authenticate` chamou, na ordem; esvaziado antes de cada teste. */
        val calls = CopyOnWriteArrayList<String>()
    }

    @org.junit.jupiter.api.BeforeEach
    fun resetCalls() {
        calls.clear()
        SecurityContextHolder.clearContext()
    }
}

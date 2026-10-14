package com.zeny.hotel.shared.web

import apiexample.ExampleApiConfiguration
import apiexample.ExampleItem
import apiexample.ExampleItemStore
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.infrastructure.idempotency.IdempotencyKeyPurgeJob
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `Idempotency-Key` (ARCHITECTURE §7) no pagamento do controller de exemplo, só de teste
 * (`POST /api/v1/examples/{id}/payments`, marcado com `@Idempotent`): chave obrigatória,
 * resposta guardada e repetida por 24h sem reexecutar o efeito, chave reaproveitada com outra
 * requisição recusada, requisição concorrente recusada, isolamento por usuário e expurgo.
 *
 * O efeito é contado em [ExampleItemStore.paymentAttempts] (execuções do handler) e
 * [ExampleItemStore.payments] (pagamentos gravados). Cada requisição se autentica com
 * `asUser(...)` (um access token com o id do usuário e da sessão), que vale também nas threads
 * dos testes concorrentes. O escopo da chave é o id do usuário (`user:<id>`, H9).
 */
@IntegrationTest
@Import(ExampleApiConfiguration::class)
class IdempotencyTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val store: ExampleItemStore,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val purgeJob: IdempotencyKeyPurgeJob,
) {
    private lateinit var itemId: UUID

    @BeforeEach
    fun cleanUp() {
        store.reset()
        jdbc.sql("DELETE FROM shared.idempotency_key").update()
        itemId = seed()
    }

    // --- Chave obrigatória -----------------------------------------------------------------

    @Test
    fun `a marked endpoint without the key is a 400 with a stable code and runs nothing`() {
        val problem = pay(key = null).problem(400, "IDEMPOTENCY_KEY_REQUIRED")

        problem["detail"].asString() shouldBe
            "Envie o cabeçalho Idempotency-Key com um identificador único da operação."
        problem["instance"].asString() shouldBe "/api/v1/examples/$itemId/payments"
        pay(key = "   ").problem(400, "IDEMPOTENCY_KEY_REQUIRED")
        store.paymentAttempts.get() shouldBe 0
        storedKeys().shouldBeEmpty()
    }

    @Test
    fun `a malformed key is a 400 with a stable code`() {
        listOf("a".repeat(256), "com espaço", "acentuação-é", "tab\tkey").forEach { key ->
            withClue(key) { pay(key = key).problem(400, "IDEMPOTENCY_KEY_INVALID") }
        }
        // Até 255 caracteres ASCII visíveis são aceitos.
        pay(key = "k".repeat(255)).status shouldBe 201
        store.paymentAttempts.get() shouldBe 1
    }

    @Test
    fun `security runs before idempotency`() {
        val response =
            mockMvc
                .post(paymentsPath()) {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"amount": "10.00"}"""
                }.andReturn()
                .response

        response.status shouldBe 401
        json(response)["code"].asString() shouldBe "UNAUTHENTICATED"
        store.paymentAttempts.get() shouldBe 0
    }

    @Test
    fun `endpoints that are not marked do not require the key`() {
        mockMvc
            .post("/api/v1/examples") {
                with(asUser(ALICE))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "sem chave", "price": "1.00", "dueDate": "2026-03-10"}"""
            }.andExpect { status { isCreated() } }
        storedKeys().shouldBeEmpty()
    }

    // --- Repetição devolve a resposta guardada ----------------------------------------------

    @Test
    fun `repeating the same request returns the stored response without running the effect again`() {
        val first = pay(key = KEY)
        val second = pay(key = KEY)
        val third = pay(key = KEY)

        first.status shouldBe 201
        first.getHeader(IDEMPOTENT_REPLAYED).shouldBeNull()
        listOf(second, third).forEach { replay ->
            replay.status shouldBe first.status
            replay.getContentAsString(Charsets.UTF_8) shouldBe first.getContentAsString(Charsets.UTF_8)
            replay.getHeader(HttpHeaders.LOCATION) shouldBe first.getHeader(HttpHeaders.LOCATION)
            replay.getHeader(HttpHeaders.ETAG) shouldBe first.getHeader(HttpHeaders.ETAG)
            replay.contentType shouldBe first.contentType
            replay.getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
        }
        store.paymentAttempts.get() shouldBe 1
        store.payments shouldHaveSize 1
        json(first)["id"].asString() shouldBe
            store.payments
                .single()
                .id
                .toString()
    }

    @Test
    fun `an error response is stored and replayed as the same error, never as a success`() {
        val first = pay(key = KEY, amount = "999.00").problem(422, "LIMIT_EXCEEDED")
        val replay = pay(key = KEY, amount = "999.00")

        replay.status shouldBe 422
        replay.getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
        MediaType.parseMediaType(replay.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe
            true
        json(replay) shouldBe first
        store.paymentAttempts.get() shouldBe 1
        store.payments.shouldBeEmpty()
    }

    @Test
    fun `a request refused by security inside the handler releases the key`() {
        store.paymentHook = { throw AccessDeniedException("sem permissão") }
        pay(key = KEY).status shouldBe 403

        store.paymentHook = {}
        pay(key = KEY).status shouldBe 201
        store.payments shouldHaveSize 1
    }

    @Test
    fun `an unexpected error in the handler is stored as a 500 and replayed as the same 500`() {
        store.paymentHook = { error("falha inesperada com dado sensível: senha=hunter2") }

        val first = pay(key = KEY).problem(500, "INTERNAL_ERROR")
        store.paymentHook = {}
        val replay = pay(key = KEY)

        replay.status shouldBe 500
        replay.getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
        json(replay) shouldBe first
        replay.getContentAsString(Charsets.UTF_8).contains("hunter2") shouldBe false
        store.paymentAttempts.get() shouldBe 1
        store.payments.shouldBeEmpty()
    }

    @Test
    fun `a body over the limit is a 413 and runs nothing`() {
        val padding = "x".repeat(ONE_MEGABYTE)
        val response =
            mockMvc
                .post(paymentsPath()) {
                    with(asUser(ALICE))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"amount": "10.00", "padding": "$padding"}"""
                    header(IDEMPOTENCY_KEY, KEY)
                }.andReturn()
                .response

        response.problem(413, "PAYLOAD_TOO_LARGE")
        store.paymentAttempts.get() shouldBe 0
        storedKeys().shouldBeEmpty()
    }

    @Test
    fun `form and multipart bodies are refused because the filter reads the body before the mvc`() {
        listOf(MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA).forEach { type ->
            val response =
                mockMvc
                    .post(paymentsPath()) {
                        with(asUser(ALICE))
                        contentType = type
                        content = "amount=10.00"
                        header(IDEMPOTENCY_KEY, KEY)
                    }.andReturn()
                    .response
            withClue(type) { response.problem(415, "UNSUPPORTED_MEDIA_TYPE") }
        }
        store.paymentAttempts.get() shouldBe 0
        storedKeys().shouldBeEmpty()
    }

    @Test
    fun `the stored response is encrypted at rest and replayed as the original`() {
        val first = pay(key = KEY)
        val paymentId = json(first)["id"].asString()

        val (headers, body) =
            jdbc
                .sql("SELECT response_headers, response_body FROM shared.idempotency_key WHERE idempotency_key = :key")
                .param("key", KEY)
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }
                .single()
        listOf(headers, body).forEach { stored ->
            stored shouldStartWith "v1:"
            stored shouldNotContain paymentId
            stored shouldNotContain "Location"
        }
        val replay = pay(key = KEY)
        replay.getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
        replay.getContentAsString(Charsets.UTF_8) shouldBe first.getContentAsString(Charsets.UTF_8)
        replay.getHeader(HttpHeaders.LOCATION) shouldBe first.getHeader(HttpHeaders.LOCATION)
        json(replay)["id"].asString() shouldBe paymentId
    }

    // --- Mesma chave com outra requisição ----------------------------------------------------

    @Test
    fun `the same key with a different request is a 422 with a stable code`() {
        pay(key = KEY, amount = "10.00").status shouldBe 201

        pay(key = KEY, amount = "11.00").problem(422, "IDEMPOTENCY_KEY_REUSED")
        val otherItem = seed()
        pay(key = KEY, amount = "10.00", item = otherItem).problem(422, "IDEMPOTENCY_KEY_REUSED")

        store.paymentAttempts.get() shouldBe 1
        store.payments shouldHaveSize 1
    }

    // --- Concorrência -------------------------------------------------------------------------

    @Test
    fun `a request with a key still in progress is a 409 and the effect runs once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.paymentHook = {
            entered.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<MockHttpServletResponse> { pay(key = KEY) }
            entered.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true

            pay(key = KEY).problem(409, "IDEMPOTENCY_KEY_IN_USE")

            release.countDown()
            first.get(WAIT_SECONDS, TimeUnit.SECONDS).status shouldBe 201
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        pay(key = KEY).getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
        store.paymentAttempts.get() shouldBe 1
        store.payments shouldHaveSize 1
    }

    @Test
    fun `simultaneous requests with the same key run the effect exactly once`() {
        val threads = 8
        val barrier = CyclicBarrier(threads)
        // Segura a execução um pouco, para as outras chegarem enquanto ela está em andamento.
        store.paymentHook = { Thread.sleep(HOLD_MILLIS) }
        val executor = Executors.newFixedThreadPool(threads)
        val responses =
            try {
                (1..threads)
                    .map {
                        executor.submit<MockHttpServletResponse> {
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            pay(key = KEY)
                        }
                    }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        store.paymentAttempts.get() shouldBe 1
        store.payments shouldHaveSize 1
        val original = responses.filter { it.status == 201 && it.getHeader(IDEMPOTENT_REPLAYED) == null }
        original shouldHaveSize 1
        responses.forEach { response ->
            when (response.status) {
                201 -> {
                    json(response)["id"].asString() shouldBe
                        store.payments
                            .single()
                            .id
                            .toString()
                }

                409 -> {
                    json(response)["code"].asString() shouldBe "IDEMPOTENCY_KEY_IN_USE"
                }

                else -> {
                    error("Status inesperado ${response.status}: ${response.getContentAsString(Charsets.UTF_8)}")
                }
            }
        }
    }

    @Test
    fun `simultaneous requests with different keys all run`() {
        val threads = 6
        val barrier = CyclicBarrier(threads)
        val executor = Executors.newFixedThreadPool(threads)
        val statuses =
            try {
                (1..threads)
                    .map { n ->
                        executor.submit<Int> {
                            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            pay(key = "$KEY-$n").status
                        }
                    }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        statuses.toSet() shouldBe setOf(201)
        store.payments shouldHaveSize threads
    }

    // --- Isolamento por usuário ---------------------------------------------------------------

    @Test
    fun `the key is isolated per user`() {
        val alice = pay(key = KEY, principal = ALICE)
        val bob = pay(key = KEY, principal = BOB)

        alice.status shouldBe 201
        bob.status shouldBe 201
        bob.getHeader(IDEMPOTENT_REPLAYED).shouldBeNull()
        json(bob)["id"] shouldNotBe json(alice)["id"]
        // Outro usuário com a mesma chave e outro corpo também não esbarra na chave de Alice.
        pay(key = "$KEY-2", principal = ALICE, amount = "10.00").status shouldBe 201
        pay(key = "$KEY-2", principal = BOB, amount = "12.00").status shouldBe 201

        store.payments shouldHaveSize 4
        storedKeys().map { it.first } shouldContainExactlyInAnyOrder
            listOf("user:$ALICE", "user:$BOB", "user:$ALICE", "user:$BOB")
    }

    @Test
    fun `the key belongs to the user id, not to the device session`() {
        val first = pay(key = KEY, principal = ALICE, session = UUID.fromString("0199d6b8-0000-7000-8000-0000000000a1"))
        val other = pay(key = KEY, principal = ALICE, session = UUID.fromString("0199d6b8-0000-7000-8000-0000000000a2"))

        first.status shouldBe 201
        other.status shouldBe 201
        other.getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
        json(other)["id"] shouldBe json(first)["id"]
        store.paymentAttempts.get() shouldBe 1
        storedKeys() shouldBe listOf("user:$ALICE" to KEY)
    }

    @Test
    fun `an authentication without a user id has no scope and is refused`() {
        mockMvc
            .post(paymentsPath()) {
                // Autenticação sem access token (como o antigo escopo pelo login): não há id de usuário.
                with(SecurityMockMvcRequestPostProcessors.user("alice"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount": "10.00"}"""
                header(IDEMPOTENCY_KEY, KEY)
            }.andReturn()
            .response
            .problem(401, "UNAUTHENTICATED")

        store.paymentAttempts.get() shouldBe 0
        storedKeys().shouldBeEmpty()
    }

    // --- Validade de 24h e expurgo ------------------------------------------------------------

    @Test
    fun `the response is kept for 24 hours`() {
        pay(key = KEY).status shouldBe 201

        val (createdAt, expiresAt) =
            jdbc
                .sql("SELECT created_at, expires_at FROM shared.idempotency_key WHERE idempotency_key = :key")
                .param("key", KEY)
                .query { rs, _ ->
                    rs.getObject(1, OffsetDateTime::class.java) to rs.getObject(2, OffsetDateTime::class.java)
                }.single()
        Duration.between(createdAt, expiresAt) shouldBe Duration.ofHours(24)
    }

    @Test
    fun `an expired key is not replayed and the request runs again`() {
        pay(key = KEY).status shouldBe 201
        age(KEY, Duration.ofHours(24).plusSeconds(1))

        val again = pay(key = KEY)

        again.status shouldBe 201
        again.getHeader(IDEMPOTENT_REPLAYED).shouldBeNull()
        store.payments shouldHaveSize 2
        storedKeys() shouldHaveSize 1
    }

    @Test
    fun `the purge job deletes keys older than 24 hours and keeps the others`() {
        pay(key = "old").status shouldBe 201
        pay(key = "almost").status shouldBe 201
        pay(key = "new").status shouldBe 201
        age("old", Duration.ofHours(24).plusSeconds(1))
        age("almost", Duration.ofHours(23).plusMinutes(59))

        purgeJob.purgeExpiredKeys()

        storedKeys().map { it.second } shouldContainExactlyInAnyOrder listOf("almost", "new")
        pay(key = "almost").getHeader(IDEMPOTENT_REPLAYED) shouldBe "true"
    }

    @Test
    fun `the purge job runs under a ShedLock lock`() {
        val method = IdempotencyKeyPurgeJob::class.java.getMethod("purgeExpiredKeys")
        val lock = method.getAnnotation(SchedulerLock::class.java)

        lock.name shouldBe IdempotencyKeyPurgeJob.LOCK_NAME
        method.isAnnotationPresent(Scheduled::class.java) shouldBe true
    }

    // --- Contrato -------------------------------------------------------------------------------

    @Test
    fun `the contract documents the header on marked operations only`() {
        val doc =
            jsonMapper.readTree(
                mockMvc
                    .get("/v3/api-docs")
                    .andReturn()
                    .response.contentAsString,
            )
        val parameter = doc["components"]["parameters"]["IdempotencyKey"]
        parameter["in"].asString() shouldBe "header"
        parameter["name"].asString() shouldBe "Idempotency-Key"
        parameter["required"].asBoolean() shouldBe true

        val payment = doc["paths"]["/api/v1/examples/{id}/payments"]["post"]
        payment["parameters"].values().map { it["\$ref"]?.asString() } shouldContainExactlyInAnyOrder
            listOf(null, "#/components/parameters/IdempotencyKey")
        payment["responses"]["409"].shouldNotBe(null)
        payment["responses"]["422"].shouldNotBe(null)
        val successes = payment["responses"].properties().filter { (status, _) -> status.startsWith("2") }
        successes.shouldNotBeEmpty()
        successes.forEach { (_, response) -> response["headers"]["Idempotent-Replayed"].shouldNotBe(null) }

        val create = doc["paths"]["/api/v1/examples"]["post"]
        create.toString().contains("IdempotencyKey") shouldBe false
    }

    // --- Apoio ----------------------------------------------------------------------------------

    private fun seed(): UUID {
        val item =
            ExampleItem(
                id = UuidV7.generate(),
                name = "Diária",
                price = Money.of("100.00"),
                dueDate = LocalDate.parse("2026-03-10"),
                createdAt = Instant.parse("2026-03-10T01:30:00Z"),
            )
        store.save(item)
        return item.id
    }

    private fun paymentsPath(item: UUID = itemId) = "/api/v1/examples/$item/payments"

    private fun pay(
        key: String?,
        amount: String = "10.00",
        item: UUID = itemId,
        principal: UUID = ALICE,
        session: UUID = UUID.fromString("0199d6b8-0000-7000-8000-0000000000a0"),
    ): MockHttpServletResponse =
        mockMvc
            .post(paymentsPath(item)) {
                with(asUser(principal, session))
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount": "$amount"}"""
                if (key != null) header(IDEMPOTENCY_KEY, key)
            }.andReturn()
            .response

    /** Recua no tempo os instantes de uma chave, como se ela tivesse sido criada há [age]. */
    private fun age(
        key: String,
        age: Duration,
    ) {
        jdbc
            .sql(
                """
                UPDATE shared.idempotency_key
                SET created_at = created_at - CAST(:age AS interval),
                    expires_at = expires_at - CAST(:age AS interval)
                WHERE idempotency_key = :key
                """.trimIndent(),
            ).param("age", "${age.seconds} seconds")
            .param("key", key)
            .update() shouldBe 1
    }

    private fun storedKeys(): List<Pair<String, String>> =
        jdbc
            .sql("SELECT scope, idempotency_key FROM shared.idempotency_key")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list()

    private fun json(response: MockHttpServletResponse): JsonNode =
        jsonMapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        MediaType.parseMediaType(contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) shouldBe true
        val problem = json(this)
        problem["status"].asInt() shouldBe status
        problem["code"].asString() shouldBe code
        problem.has("trace") shouldBe false
        return problem
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENT_REPLAYED = "Idempotent-Replayed"
        const val KEY = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"
        val ALICE: UUID = UUID.fromString("0199d6b8-0000-7000-8000-00000000a11c")
        val BOB: UUID = UUID.fromString("0199d6b8-0000-7000-8000-000000000b0b")
        const val WAIT_SECONDS = 20L
        const val HOLD_MILLIS = 300L
        const val ONE_MEGABYTE = 1024 * 1024
    }
}

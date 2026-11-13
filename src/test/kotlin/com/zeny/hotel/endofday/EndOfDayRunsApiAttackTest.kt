package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.THREE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayRunStatusView
import com.zeny.hotel.endofday.application.EndOfDayService
import com.zeny.hotel.endofday.application.EndOfDayStepStatusView
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques ao fechamento manual pela API (M6 E10, Task 9): entrada externa malformada ou absurda, a idempotência
 * (chave inválida, reusada, em uso e a resposta `APPROVAL_REQUIRED` guardada), cada recusa do token de aprovação sem
 * criar execução, a autorização antes do estado com uma execução `FAILED`, a atribuição em massa pelo corpo, o
 * `RUNNING` de uma espera vencida, a resposta sem dado pessoal e o contrato. No banco isolado, com as etapas falsas;
 * todo teste termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass") // os apoios do contexto e um ataque por teste
class EndOfDayRunsApiAttackTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val context: ApplicationContext,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    private fun settled(block: () -> Unit) {
        try {
            probe.reset()
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun manager(): TestUser = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))

    private fun post(
        auth: RequestPostProcessor?,
        content: String,
        key: String? = UUID.randomUUID().toString(),
        approvalToken: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/end-of-day/runs") {
                if (auth != null) with(auth)
                if (key != null) header(IDEMPOTENCY_KEY_HEADER, key)
                if (approvalToken != null) header(APPROVAL_TOKEN_HEADER, approvalToken)
                contentType = MediaType.APPLICATION_JSON
                this.content = content
            }.andReturn()
            .response

    private fun start(
        auth: RequestPostProcessor?,
        date: LocalDate,
        key: String? = UUID.randomUUID().toString(),
        approvalToken: String? = null,
    ) = post(auth, """{"businessDate":"$date"}""", key, approvalToken)

    private fun MockHttpServletResponse.body(status: Int): JsonNode {
        withClue(getContentAsString(Charsets.UTF_8)) { this.status shouldBe status }
        return json.readTree(getContentAsString(Charsets.UTF_8))
    }

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = body(status).also { it["code"].asString() shouldBe code }

    private fun runCount(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(Int::class.java)
            .single()

    /** Os ids de todas as execuções do banco isolado: a foto de antes e a de depois mostram o que o teste criou. */
    private fun runIds(): Set<UUID> =
        jdbc
            .sql("SELECT id FROM endofday.run")
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()

    private fun invocations(date: LocalDate): List<Int> = STEPS.map { probe.invocations(it, date) }

    private fun awaitInvocation(
        step: String,
        date: LocalDate,
    ) {
        val deadline = System.nanoTime() + AWAIT.toNanos()
        while (probe.invocations(step, date) == 0) {
            check(System.nanoTime() < deadline) { "A etapa $step não começou em $AWAIT." }
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** Um aprovador com `runs.execute` e PIN, e o token que ele aprova para o solicitante na sessão dada. */
    private fun approvalToken(
        requester: TestUser,
        sessionId: UUID,
        resourceRef: String,
        action: String = START,
    ): String {
        synchronizer.synchronize()
        val approver = manager()
        iam.setPin(approver, "4321")
        val response =
            iam.call(
                POST,
                "/api/v1/auth/approvals",
                asUser(requester.id.value, sessionId),
                mapOf(
                    "action" to action,
                    "permission" to EndOfDayPermissions.RUNS_EXECUTE.value,
                    "resourceRef" to resourceRef,
                    "approverLogin" to approver.login,
                    "approverPin" to "4321",
                    "reason" to "Motivo do teste",
                ),
            )
        return response.body(201)["approvalToken"].asString()
    }

    private fun <T> actingAs(
        user: TestUser,
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.id.value.toString())
                .claim("sid", UUID.randomUUID().toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    // --- Entrada externa ---

    @Test
    fun `a malformed JSON body is a 400 MALFORMED_REQUEST and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)

            listOf("""{"businessDate":""", "not json", "", "[]", "\"$date\"", "null").forEach { body ->
                withClue("corpo: <$body>") { post(auth, body).problem(400, "MALFORMED_REQUEST") }
            }

            runCount(date) shouldBe 0
        }

    @Test
    fun `an explicit null businessDate is a validation error on the field`() =
        settled {
            val date = hotelApi.currentBusinessDate()

            val problem =
                post(
                    asUser(manager().id.value),
                    """{"businessDate":null}""",
                ).problem(400, "VALIDATION_FAILED")

            problem["errors"].values().map { it["field"].asString() } shouldContainExactly listOf("businessDate")
            runCount(date) shouldBe 0
        }

    @Test
    fun `a businessDate that is not an ISO date is a 400 on the field and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)
            val before = runIds()
            val invalid =
                listOf(
                    "\"13/11/2026\"",
                    "\"2026-13-01\"",
                    "\"2026-02-30\"",
                    "\"${date.toString().replace("-", "")}\"",
                    "\"\"",
                    "\" \"",
                    "\"amanhã\"",
                    "true",
                    "{}",
                    "[\"$date\"]",
                )

            invalid.forEach { value ->
                withClue("businessDate = $value") {
                    val problem = post(auth, """{"businessDate":$value}""").problem(400, "VALIDATION_FAILED")
                    problem["errors"].values().map { it["field"].asString() } shouldContainExactly
                        listOf("businessDate")
                }
            }

            runCount(date) shouldBe 0
            runIds() shouldBe before
        }

    @Test
    fun `absurd but valid dates are a DATE_MISMATCH and never reach a 500`() =
        settled {
            val current = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)
            val before = runIds()

            listOf("9999-12-31", "0001-01-01", "1970-01-01", "+10000-01-01").forEach { value ->
                withClue("businessDate = $value") {
                    val problem =
                        post(auth, """{"businessDate":"$value"}""").problem(409, "END_OF_DAY_DATE_MISMATCH")
                    problem["currentBusinessDate"].asString() shouldBe current.toString()
                }
            }

            runIds() shouldBe before
        }

    @Test
    fun `the extremes of LocalDate are a DATE_MISMATCH and never reach a 500`() =
        settled {
            val auth = asUser(manager().id.value)
            val before = runIds()

            listOf("+999999999-12-31", "-999999999-01-01").forEach { value ->
                withClue("businessDate = $value") {
                    post(auth, """{"businessDate":"$value"}""").problem(409, "END_OF_DAY_DATE_MISMATCH")
                }
            }

            runIds() shouldBe before
        }

    /**
     * Achado do testador na Task 9 (corrigido em `8fab7a9`): uma data válida para o `LocalDate`, mas fora do intervalo
     * do tipo `date` do PostgreSQL (4713 a.C. a 5874897 d.C.), chegava à consulta `findByBusinessDate` e voltava
     * `500 INTERNAL_ERROR`, guardado pela idempotência. Entrada externa nunca dá 500: o esperado é um 4xx estável.
     */
    @Test
    fun `dates outside the range of the database are a stable 4xx and never a 500`() =
        settled {
            val auth = asUser(manager().id.value)
            val before = runIds()

            val answers =
                listOf("+5874898-01-01", "-4714-11-23").associateWith { value ->
                    post(
                        auth,
                        """{"businessDate":"$value"}""",
                    ).let { "${it.status} ${it.getContentAsString(Charsets.UTF_8)}" }
                }

            withClue(answers) { answers.values.all { it.take(3).toInt() in 400..499 } shouldBe true }

            runIds() shouldBe before
        }

    @Test
    fun `fields the client cannot set are ignored by the body`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val user = manager()
            val forgedId = UuidV7.generate()
            val other = UuidV7.generate()

            val run =
                post(
                    asUser(user.id.value),
                    """
                    {"businessDate":"$date","id":"$forgedId","status":"COMPLETED","trigger":"AUTOMATIC",
                     "triggeredBy":"$other","version":99,"steps":[],"startedAt":"2000-01-01T00:00:00Z"}
                    """.trimIndent(),
                ).body(201)

            run["id"].asString() shouldNotBe forgedId.toString()
            run["trigger"].asString() shouldBe "MANUAL"
            run["triggeredBy"].asString() shouldBe user.id.value.toString()
            run["steps"].values().map { it["name"].asString() } shouldContainExactly STEPS
            run["startedAt"].asString() shouldNotBe "2000-01-01T00:00:00Z"
            runCount(date) shouldBe 1
        }

    @Test
    fun `without a token the POST is a 401 and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()

            start(null, date).problem(401, "UNAUTHENTICATED")

            runCount(date) shouldBe 0
        }

    // --- Idempotência ---

    @Test
    fun `a malformed idempotency key is refused before anything runs`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)

            listOf("a".repeat(256), "chave com espaço", "chave-acentuação").forEach { key ->
                withClue("chave: <$key>") { start(auth, date, key).problem(400, "IDEMPOTENCY_KEY_INVALID") }
            }

            runCount(date) shouldBe 0
            invocations(date) shouldContainExactly listOf(0, 0, 0, 0)
        }

    @Test
    fun `the same key with another body is IDEMPOTENCY_KEY_REUSED and does not run again`() =
        settled {
            val auth = asUser(manager().id.value)
            val closed = hotelApi.currentBusinessDate()
            val key = UUID.randomUUID().toString()
            start(auth, closed, key).body(201)["status"].asString() shouldBe "COMPLETED"
            val current = hotelApi.currentBusinessDate()

            start(auth, current, key).problem(422, "IDEMPOTENCY_KEY_REUSED")

            runCount(current) shouldBe 0
            invocations(current) shouldContainExactly listOf(0, 0, 0, 0)
        }

    @Test
    fun `the same key while the first POST is still running is IDEMPOTENCY_KEY_IN_USE`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)
            val key = UUID.randomUUID().toString()
            val gate = CountDownLatch(1)
            probe.holdOn(ONE, gate)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<MockHttpServletResponse> { start(auth, date, key) }
                awaitInvocation(ONE, date)

                start(auth, date, key).problem(409, "IDEMPOTENCY_KEY_IN_USE")

                gate.countDown()
                first.get(60, TimeUnit.SECONDS).body(201)["status"].asString() shouldBe "COMPLETED"
                runCount(date) shouldBe 1
                invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
            } finally {
                gate.countDown()
                pool.shutdownNow()
            }
        }

    @Test
    fun `the stored APPROVAL_REQUIRED is replayed even with a token, and the token still works with another key`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val key = UUID.randomUUID().toString()
            start(auth, date, key).problem(403, "APPROVAL_REQUIRED")

            val replayed = start(auth, date, key)
            replayed.problem(403, "APPROVAL_REQUIRED")
            replayed.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"

            val token = approvalToken(requester, sessionId, "endofday.business-date:$date")
            val withToken = start(auth, date, key, token)
            withClue("a mesma chave repete a recusa guardada, sem executar nem gastar o token") {
                withToken.problem(403, "APPROVAL_REQUIRED")
                withToken.getHeader(IDEMPOTENT_REPLAYED_HEADER) shouldBe "true"
                runCount(date) shouldBe 0
            }

            start(auth, date, approvalToken = token).body(201)["status"].asString() shouldBe "COMPLETED"
            runCount(date) shouldBe 1
        }

    // --- Token de aprovação ---

    @Test
    fun `a token approved for another date is a mismatch and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val token = approvalToken(requester, sessionId, "endofday.business-date:${date.plusDays(1)}")

            start(asUser(requester.id.value, sessionId), date, approvalToken = token)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")

            runCount(date) shouldBe 0
        }

    @Test
    fun `a token approved for another action is a mismatch and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val token =
                approvalToken(requester, sessionId, "endofday.business-date:$date", action = "endofday.run.resume")

            start(asUser(requester.id.value, sessionId), date, approvalToken = token)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")

            runCount(date) shouldBe 0
        }

    @Test
    fun `a token approved in another session is a mismatch and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val token = approvalToken(requester, UuidV7.generate(), "endofday.business-date:$date")

            start(asUser(requester.id.value, UuidV7.generate()), date, approvalToken = token)
                .problem(403, "APPROVAL_TOKEN_MISMATCH")

            runCount(date) shouldBe 0
        }

    @Test
    fun `an unknown token is APPROVAL_TOKEN_INVALID and creates nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()

            start(asUser(iam.createUser().id.value), date, approvalToken = "token-inventado")
                .problem(403, "APPROVAL_TOKEN_INVALID")

            runCount(date) shouldBe 0
        }

    @Test
    fun `a used token is APPROVAL_TOKEN_USED, not the 409 of the closed date`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val token = approvalToken(requester, sessionId, "endofday.business-date:$date")
            start(auth, date, approvalToken = token).body(201)["status"].asString() shouldBe "COMPLETED"

            start(auth, date, approvalToken = token).problem(403, "APPROVAL_TOKEN_USED")

            runCount(date) shouldBe 1
        }

    @Test
    fun `a token spent on a refused run is given back and still answers the same refusal`() =
        settled {
            val future = hotelApi.currentBusinessDate().plusDays(1)
            val requester = iam.createUser()
            val sessionId = UuidV7.generate()
            val auth = asUser(requester.id.value, sessionId)
            val token = approvalToken(requester, sessionId, "endofday.business-date:$future")

            start(auth, future, approvalToken = token).problem(409, "END_OF_DAY_DATE_MISMATCH")
            withClue("a transação desfeita devolve o token; não vira APPROVAL_TOKEN_USED") {
                start(auth, future, approvalToken = token).problem(409, "END_OF_DAY_DATE_MISMATCH")
            }

            runCount(future) shouldBe 0
        }

    @Test
    fun `without the permission a failed run of the date is not revealed`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            probe.failNext(THREE, 1)
            start(asUser(manager().id.value), date).body(201)["status"].asString() shouldBe "FAILED"

            start(asUser(iam.createUser().id.value), date).problem(403, "APPROVAL_REQUIRED")
        }

    // --- Estado da resposta e concorrência ---

    @Test
    fun `a POST while the run of the date is running is RUN_EXISTS with its id and does not wait`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val auth = asUser(manager().id.value)
            val gate = CountDownLatch(1)
            probe.holdOn(ONE, gate)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<MockHttpServletResponse> { start(auth, date) }
                awaitInvocation(ONE, date)
                val runId = runs.findByBusinessDate(date)!!.id.value

                val began = System.nanoTime()
                val exists = start(auth, date).problem(409, "END_OF_DAY_RUN_EXISTS")
                val took = Duration.ofNanos(System.nanoTime() - began)

                exists["runId"].asString() shouldBe runId.toString()
                withClue("levou $took") { (took < Duration.ofSeconds(10)) shouldBe true }
                gate.countDown()
                first.get(60, TimeUnit.SECONDS).body(201)["id"].asString() shouldBe runId.toString()
                runCount(date) shouldBe 1
            } finally {
                gate.countDown()
                pool.shutdownNow()
            }
        }

    @Test
    fun `when the wait runs out the facade returns the run as RUNNING and the engine goes on`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val user = manager()
            val gate = CountDownLatch(1)
            probe.holdOn(ONE, gate)
            val shortWait =
                EndOfDayService(commands, engine, runs, Duration.ofMillis(SHORT_WAIT_MILLIS), transactionManager)
            try {
                val run = actingAs(user) { shortWait.startManually(date, null) }

                run.status shouldBe EndOfDayRunStatusView.RUNNING
                run.finishedAt shouldBe null
                run.steps.drop(1).map { it.status } shouldContainExactly List(3) { EndOfDayStepStatusView.PENDING }
                gate.countDown()
                engine.execute(EndOfDayRunId(run.id), AWAIT)
                runs.findByBusinessDate(date)!!.status shouldBe RunStatus.COMPLETED
                invocations(date) shouldContainExactly listOf(1, 1, 1, 1)
            } finally {
                gate.countDown()
            }
        }

    @Test
    fun `the response has only the run fields and no personal data, even in the reason of a failed step`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val user = manager()
            probe.throwNext(THREE, IllegalStateException("Hóspede Maria Souza, CPF 123.456.789-09"))

            val response = start(asUser(user.id.value), date)
            val run = response.body(201)

            run.propertyNames().toList() shouldContainExactlyInAnyOrder
                listOf(
                    "id",
                    "businessDate",
                    "trigger",
                    "status",
                    "triggeredBy",
                    "startedAt",
                    "finishedAt",
                    "version",
                    "steps",
                )
            run["steps"].values().forEach {
                it.propertyNames().toList() shouldContainExactlyInAnyOrder
                    listOf("name", "position", "status", "startedAt", "finishedAt", "result", "attempts")
            }
            run["status"].asString() shouldBe "FAILED"
            run["steps"].values().single { it["name"].asString() == THREE }["result"].asString() shouldBe
                "IllegalStateException"
            val text = response.getContentAsString(Charsets.UTF_8)
            listOf("Maria", "123.456", user.login).forEach { text shouldNotContain it }
        }

    @Test
    fun `two simultaneous POSTs from users without the permission create nothing`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val users = List(2) { iam.createUser() }
                val go = CountDownLatch(1)
                val calls =
                    users.map { user ->
                        pool.submit<MockHttpServletResponse> {
                            go.await()
                            start(asUser(user.id.value), date)
                        }
                    }
                go.countDown()

                calls.map { it.get(60, TimeUnit.SECONDS) }.forEach { it.problem(403, "APPROVAL_REQUIRED") }
                runCount(date) shouldBe 0
            } finally {
                pool.shutdownNow()
            }
        }

    // --- Contrato ---

    @Test
    fun `the contract lists exactly the domain values of trigger and statuses`() {
        val schemas = contract.components.schemas

        @Suppress("UNCHECKED_CAST")
        fun listed(
            schema: String,
            property: String,
        ) = schemas[schema]!!.properties[property]!!.extensions["x-extensible-enum"] as List<String>

        listed("EndOfDayRunResponse", "trigger") shouldContainExactly RunTrigger.entries.map { it.name }
        listed("EndOfDayRunResponse", "status") shouldContainExactly RunStatus.entries.map { it.name }
        listed("EndOfDayRunStepResponse", "status") shouldContainExactly StepStatus.entries.map { it.name }
    }

    @Test
    fun `the contract of the POST has the idempotency key, the approval token and the 201, 403, 409 and 422`() {
        val operation = contract.paths["/api/v1/end-of-day/runs"]!!.post

        operation.operationId shouldBe "startEndOfDayRun"
        operation.tags shouldContainExactly listOf("end-of-day")
        operation.parameters.map { it.`$ref`.substringAfterLast('/') } shouldContainExactlyInAnyOrder
            listOf("IdempotencyKey", "ApprovalToken")
        operation.requestBody.required shouldBe true
        operation.responses.keys shouldContainExactlyInAnyOrder listOf("201", "403", "409", "422", "default")
        operation.responses["201"]!!
            .content["application/json"]!!
            .schema.`$ref` shouldBe "#/components/schemas/EndOfDayRunResponse"
        schemas("StartEndOfDayRunRequest").required shouldContainExactly listOf("businessDate")
        listOf(
            "END_OF_DAY_ALREADY_CLOSED",
            "END_OF_DAY_RUN_EXISTS",
            "END_OF_DAY_DATE_MISMATCH",
            "END_OF_DAY_NOT_CONFIGURED",
            "RUNNING",
        ).forEach { withClue(it) { operation.description.contains(it) shouldBe true } }
        withClue("o endpoint não é público: nada de `security: []`") {
            (operation.security?.isEmpty() == true) shouldBe false
        }
    }

    private fun schemas(name: String) = contract.components.schemas[name]!!

    private companion object {
        const val START = "endofday.run.start"
        const val POLL_MILLIS = 20L
        const val SHORT_WAIT_MILLIS = 300L
        val AWAIT: Duration = Duration.ofSeconds(20)
        val contract: OpenAPI by lazy {
            Yaml.mapper().readValue(Path.of("api/openapi.yaml").toFile(), OpenAPI::class.java)
        }
    }
}

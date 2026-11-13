package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.application.PermissionCatalogSynchronizer
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.ApprovalRequiredException
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ataques à criação da execução do fechamento (M6 E10, Task 8): a ordem exata das recusas, a autorização antes de
 * qualquer 409, a aprovação de superior de ponta a ponta, o aviso no tempo real e a criação concorrente da mesma data.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto
class EndOfDayRunCommandsAttackTest(
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val clock: Clock,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val authorization: Authorization,
    @Autowired private val actor: CurrentActor,
    @Autowired private val audit: AuditContext,
    @Autowired private val realtime: RealtimePublisher,
    @Autowired private val synchronizer: PermissionCatalogSynchronizer,
    @Autowired private val context: ApplicationContext,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    private class Frames : StreamSink {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit
    }

    private fun settled(block: () -> Unit) {
        try {
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun <T> actingAs(
        user: UserId,
        sessionId: UUID = UUID.randomUUID(),
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.value.toString())
                .claim("sid", sessionId.toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    private fun commandsWithEmptyRegistry() =
        EndOfDayRunCommands(
            runs,
            hotelApi,
            EndOfDayStepRegistry(emptyList()),
            authorization,
            actor,
            audit,
            clock,
            realtime,
        )

    private fun closeCurrentDate(): LocalDate =
        hotelApi.currentBusinessDate().also {
            val run = commands.createAutomatic(it)
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
        }

    private fun runCount(date: LocalDate): Int =
        jdbc
            .sql("SELECT count(*) FROM endofday.run WHERE business_date = :d")
            .param("d", date)
            .query(Int::class.java)
            .single()

    private fun startEntries(runId: UUID): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT user_id, outcome, approval_id FROM audit.audit_entry " +
                    "WHERE entity_id = :id AND action = 'endofday.run.start'",
            ).param("id", runId.toString())
            .query()
            .listOfRows()

    /** Um aprovador com `runs.execute` e PIN, e o token que ele aprova para o solicitante na sessão dada. */
    private fun approvalFor(
        requester: TestUser,
        sessionId: UUID,
        resourceRef: String,
    ): Pair<TestUser, String> {
        synchronizer.synchronize()
        val approver = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))
        iam.setPin(approver, "4321")
        val response =
            iam.call(
                POST,
                "/api/v1/auth/approvals",
                asUser(requester.id.value, sessionId),
                mapOf(
                    "action" to "endofday.run.start",
                    "permission" to EndOfDayPermissions.RUNS_EXECUTE.value,
                    "resourceRef" to resourceRef,
                    "approverLogin" to approver.login,
                    "approverPin" to "4321",
                    "reason" to "Motivo do teste",
                ),
            )
        val body = iam.run { response.expectStatus(201) }.shouldNotBeNull()
        return approver to body["approvalToken"].asString()
    }

    @Test
    fun `a running run of a date that is no longer current is RUN_EXISTS and not DATE_MISMATCH`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val run = commands.createAutomatic(date)
            hotelApi.advanceBusinessDate(date)
            (hotelApi.currentBusinessDate() == date) shouldBe false

            val error = shouldThrow<EndOfDayRunExistsException> { commands.createAutomatic(date) }

            error.problemProperties shouldBe mapOf("runId" to run.id.value.toString())
        }

    @Test
    fun `a completed date that is not current is ALREADY_CLOSED also for the manual creation by a permitted user`() =
        settled {
            val closed = closeCurrentDate()
            val user = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))

            val error =
                shouldThrow<EndOfDayAlreadyClosedException> {
                    actingAs(user.id) { commands.createManually(closed, null) }
                }

            error.code shouldBe EndOfDayErrorCodes.END_OF_DAY_ALREADY_CLOSED
            runCount(closed) shouldBe 1
        }

    @Test
    fun `with an empty registry an existing run still wins over NOT_CONFIGURED`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val run = commands.createAutomatic(date)

            val error = shouldThrow<EndOfDayRunExistsException> { commandsWithEmptyRegistry().createAutomatic(date) }

            error.runId shouldBe run.id.value
        }

    @Test
    fun `with an empty registry a completed date is ALREADY_CLOSED and another date is DATE_MISMATCH`() =
        settled {
            val closed = closeCurrentDate()
            val empty = commandsWithEmptyRegistry()

            shouldThrow<EndOfDayAlreadyClosedException> { empty.createAutomatic(closed) }
            val current = hotelApi.currentBusinessDate()
            val mismatch = shouldThrow<EndOfDayDateMismatchException> { empty.createAutomatic(current.plusDays(1)) }

            mismatch.currentBusinessDate shouldBe current
        }

    @Test
    fun `a past date without any run is DATE_MISMATCH`() =
        settled {
            val current = hotelApi.currentBusinessDate()

            val past = current.minusYears(1)

            val error = shouldThrow<EndOfDayDateMismatchException> { commands.createAutomatic(past) }

            error.currentBusinessDate shouldBe current
            runCount(past) shouldBe 0
        }

    @Test
    fun `without the permission APPROVAL_REQUIRED comes before DATE_MISMATCH and RUN_EXISTS`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val user = iam.createUser()

            shouldThrow<ApprovalRequiredException> {
                actingAs(user.id) { commands.createManually(date.plusDays(1), null) }
            }
            commands.createAutomatic(date)
            val error =
                shouldThrow<ApprovalRequiredException> { actingAs(user.id) { commands.createManually(date, null) } }

            error.problemProperties["resourceRef"] shouldBe "endofday.business-date:$date"
            runCount(date.plusDays(1)) shouldBe 0
        }

    @Test
    fun `a valid approval creates the manual run with the requester as triggeredBy and the approval in the trail`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UUID.randomUUID()
            val (approver, token) = approvalFor(requester, sessionId, "endofday.business-date:$date")

            val run = actingAs(requester.id, sessionId) { commands.createManually(date, token) }

            val stored = runs.findById(run.id.value).orElseThrow()
            stored.trigger shouldBe RunTrigger.MANUAL
            stored.triggeredBy shouldBe requester.id
            val entries = startEntries(run.id.value)
            entries shouldHaveSize 1
            entries.single()["user_id"] shouldBe requester.id.value
            entries.single()["approval_id"].shouldNotBeNull()
            val approval =
                jdbc
                    .sql("SELECT requester_id, approver_id FROM iam.approval WHERE id = :id")
                    .param("id", entries.single()["approval_id"])
                    .query()
                    .singleRow()
            approval["requester_id"] shouldBe requester.id.value
            approval["approver_id"] shouldBe approver.id.value
        }

    @Test
    fun `an approval token cannot be used twice`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UUID.randomUUID()
            val (_, token) = approvalFor(requester, sessionId, "endofday.business-date:$date")
            actingAs(requester.id, sessionId) { commands.createManually(date, token) }

            val error =
                shouldThrow<DomainException> {
                    actingAs(requester.id, sessionId) { commands.createManually(date, token) }
                }

            error.code shouldBe IamErrorCodes.APPROVAL_TOKEN_USED
            runCount(date) shouldBe 1
        }

    @Test
    fun `an approval for another date is refused and creates no run`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val requester = iam.createUser()
            val sessionId = UUID.randomUUID()
            val (_, token) = approvalFor(requester, sessionId, "endofday.business-date:${date.plusDays(1)}")

            val error =
                shouldThrow<DomainException> {
                    actingAs(requester.id, sessionId) { commands.createManually(date, token) }
                }

            error.code shouldBe IamErrorCodes.APPROVAL_TOKEN_MISMATCH
            runs.findByBusinessDate(date).shouldBeNull()
        }

    @Test
    fun `a refusal after the approval rolls back the token use so the token still works`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val next = date.plusDays(1)
            val requester = iam.createUser()
            val sessionId = UUID.randomUUID()
            val (_, token) = approvalFor(requester, sessionId, "endofday.business-date:$next")
            shouldThrow<EndOfDayDateMismatchException> {
                actingAs(requester.id, sessionId) { commands.createManually(next, token) }
            }
            hotelApi.advanceBusinessDate(date)

            val run = actingAs(requester.id, sessionId) { commands.createManually(next, token) }

            run.businessDate shouldBe next
        }

    @Test
    fun `a refused creation publishes nothing and an accepted one publishes exactly one message`() =
        settled {
            val frames = Frames()
            val connection =
                hub.connect(
                    StreamSubscription(
                        Actor(UserId.new(), DeviceSessionId.new()),
                        mapOf(EndOfDayRealtime.TOPIC to PermissionScope.ALL),
                        clock.instant().plus(1, ChronoUnit.HOURS),
                    ),
                    null,
                    frames,
                )
            try {
                val date = hotelApi.currentBusinessDate()
                shouldThrow<EndOfDayDateMismatchException> { commands.createAutomatic(date.plusDays(1)) }
                val run = commands.createAutomatic(date)
                shouldThrow<EndOfDayRunExistsException> { commands.createAutomatic(date) }

                val published = { frames.events.filter { it.name == EndOfDayRealtime.TOPIC.name } }
                await().atMost(Duration.ofSeconds(5)).until { published().isNotEmpty() }
                Thread.sleep(SETTLE_MILLIS)
                published() shouldHaveSize 1
                (""""id":"${run.id.value}"""" in published().single().data) shouldBe true
            } finally {
                hub.release(connection)
            }
        }

    @Test
    fun `two simultaneous creations of the same date leave a single run and one fails with RUN_EXISTS`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val results =
                    (1..2).map {
                        pool.submit<Result<UUID>> {
                            ready.countDown()
                            go.await()
                            runCatching { commands.createAutomatic(date).id.value }
                        }
                    }
                ready.await(5, TimeUnit.SECONDS)
                go.countDown()
                val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }

                outcomes.count { it.isSuccess } shouldBe 1
                val failure = outcomes.single { it.isFailure }.exceptionOrNull().shouldNotBeNull()
                failure.shouldBeInstanceOf<EndOfDayRunExistsException>()
                runCount(date) shouldBe 1
                val stored = runs.findByBusinessDate(date).shouldNotBeNull()
                stored.id.value shouldBe outcomes.single { it.isSuccess }.getOrThrow()
                startEntries(stored.id.value).map { it["outcome"] } shouldContainExactly listOf("SUCCEEDED")
            } finally {
                pool.shutdownNow()
            }
        }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
        const val SETTLE_MILLIS = 400L
    }
}

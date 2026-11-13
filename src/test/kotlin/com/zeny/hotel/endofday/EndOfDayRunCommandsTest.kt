package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.ApprovalRequiredException
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A criação auditada da execução do fechamento (M6 E10, Task 8), no banco isolado e com as etapas falsas: criação
 * automática (ator sistema), as recusas na ordem do plano, o aviso no tempo real e a aprovação de superior da criação
 * manual. Todo teste que cria execução termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "TooManyFunctions") // os apoios do contexto
class EndOfDayRunCommandsTest(
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val clock: Clock,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val authorization: Authorization,
    @Autowired private val actor: CurrentActor,
    @Autowired private val audit: AuditContext,
    @Autowired private val realtime: RealtimePublisher,
    @Autowired private val context: ApplicationContext,
) {
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
        block: () -> T,
    ): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(user.value.toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        val previous = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = previous
        }
    }

    private fun auditUsers(
        runId: UUID,
        action: String,
    ): List<UUID?> =
        jdbc
            .sql("SELECT user_id FROM audit.audit_entry WHERE entity_id = :id AND action = :action")
            .param("id", runId.toString())
            .param("action", action)
            .query { rs, _ -> rs.getObject(1) as UUID? }
            .list()

    /** Cria a execução da data corrente e a conclui pelo motor: a data avança e a execução fica `COMPLETED`. */
    private fun closeCurrentDate() =
        hotelApi.currentBusinessDate().also {
            val run = commands.createAutomatic(it)
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
        }

    @Test
    fun `createAutomatic creates a RUNNING run with the registered steps and is audited as the system`() =
        settled {
            val date = hotelApi.currentBusinessDate()

            val run = commands.createAutomatic(date)

            val stored = runs.findById(run.id.value).orElseThrow()
            stored.businessDate shouldBe date
            stored.status shouldBe RunStatus.RUNNING
            stored.trigger shouldBe RunTrigger.AUTOMATIC
            stored.triggeredBy.shouldBeNull()
            run.steps.map { it.name } shouldContainExactly registry.slots.map { it.name }
            run.steps.map { it.status }.toSet() shouldBe setOf(StepStatus.PENDING)
            auditUsers(run.id.value, "endofday.run.start") shouldContainExactly listOf(null)
        }

    @Test
    fun `a completed date is END_OF_DAY_ALREADY_CLOSED`() =
        settled {
            val closed = closeCurrentDate()

            val error = shouldThrow<EndOfDayAlreadyClosedException> { commands.createAutomatic(closed) }

            error.code shouldBe EndOfDayErrorCodes.END_OF_DAY_ALREADY_CLOSED
        }

    @Test
    fun `a running or failed date is END_OF_DAY_RUN_EXISTS with runId`() =
        settled {
            val date = hotelApi.currentBusinessDate()
            val run = commands.createAutomatic(date)

            val running = shouldThrow<EndOfDayRunExistsException> { commands.createAutomatic(date) }
            running.code shouldBe EndOfDayErrorCodes.END_OF_DAY_RUN_EXISTS
            running.problemProperties shouldBe mapOf("runId" to run.id.value.toString())

            probe.failNext(ONE, 1)
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)
            val failed = shouldThrow<EndOfDayRunExistsException> { commands.createAutomatic(date) }
            failed.problemProperties shouldBe mapOf("runId" to run.id.value.toString())
        }

    @Test
    fun `another date is END_OF_DAY_DATE_MISMATCH with currentBusinessDate`() =
        settled {
            val current = hotelApi.currentBusinessDate()

            val error = shouldThrow<EndOfDayDateMismatchException> { commands.createAutomatic(current.plusDays(1)) }

            error.code shouldBe EndOfDayErrorCodes.END_OF_DAY_DATE_MISMATCH
            error.problemProperties shouldBe mapOf("currentBusinessDate" to current.toString())
            runs.findByBusinessDate(current.plusDays(1)).shouldBeNull()
        }

    @Test
    fun `createAutomatic with an empty registry fails with END_OF_DAY_NOT_CONFIGURED`() {
        val empty =
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

        val error =
            shouldThrow<EndOfDayNotConfiguredException> { empty.createAutomatic(hotelApi.currentBusinessDate()) }

        error.code shouldBe EndOfDayErrorCodes.END_OF_DAY_NOT_CONFIGURED
        runs.findByBusinessDate(hotelApi.currentBusinessDate()).shouldBeNull()
    }

    @Test
    fun `the creation publishes the stored version on the end-of-day topic`() =
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
                val run = commands.createAutomatic(hotelApi.currentBusinessDate())

                await().atMost(Duration.ofSeconds(5)).until {
                    frames.events.any { """"id":"${run.id.value}"""" in it.data }
                }
                val message = json.readTree(frames.events.first { """"id":"${run.id.value}"""" in it.data }.data)
                message["type"].asString() shouldBe EndOfDayRealtime.RUN
                message["version"].asLong() shouldBe runs.findById(run.id.value).orElseThrow().version
            } finally {
                hub.release(connection)
            }
        }

    @Test
    fun `createManually with the permission records the requester and the run is MANUAL`() =
        settled {
            val user =
                iam.createUserWith(
                    TestGrant(
                        EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE },
                        PermissionScope.ALL,
                    ),
                )

            val run = actingAs(user.id) { commands.createManually(hotelApi.currentBusinessDate(), null) }

            val stored = runs.findById(run.id.value).orElseThrow()
            stored.trigger shouldBe RunTrigger.MANUAL
            stored.triggeredBy shouldBe user.id
            auditUsers(run.id.value, "endofday.run.start") shouldContainExactly listOf(user.id.value)
        }

    @Test
    fun `without the permission the approval comes before any check that reveals the state`() =
        settled {
            val closed = closeCurrentDate()
            val user = iam.createUser()

            val error =
                shouldThrow<ApprovalRequiredException> { actingAs(user.id) { commands.createManually(closed, null) } }

            error.problemProperties["action"] shouldBe "endofday.run.start"
            error.problemProperties["permission"] shouldBe EndOfDayPermissions.RUNS_EXECUTE.value
            error.problemProperties["resourceRef"] shouldBe "endofday.business-date:$closed"
            runs.findByBusinessDate(closed).shouldNotBeNull().status shouldBe RunStatus.COMPLETED
        }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
    }
}

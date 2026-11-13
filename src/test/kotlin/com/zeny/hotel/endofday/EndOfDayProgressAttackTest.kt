package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ataques ao andamento do fechamento no tempo real (M6 E10, Task 7), pelo testador: versão gravada e crescente na
 * falha, nada publicado por transição que não muda nada, por transação desfeita ou por assinante sem escopo `ALL`, o
 * conteúdo exato da mensagem e a inscrição pela API (`/api/v1/stream`). Banco isolado; afirma só sobre as próprias
 * execuções.
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList", "LargeClass")
class EndOfDayProgressAttackTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val transitions: EndOfDayRunTransitions,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val json: JsonMapper,
) {
    private class Frames : StreamSink {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit
    }

    private fun connect(
        frames: Frames,
        scope: PermissionScope = PermissionScope.ALL,
    ): UUID =
        hub.connect(
            StreamSubscription(
                Actor(UserId.new(), DeviceSessionId.new()),
                mapOf(EndOfDayRealtime.TOPIC to scope),
                clock.instant().plus(1, ChronoUnit.HOURS),
            ),
            null,
            frames,
        )

    private fun newRun(): EndOfDayRun =
        tx
            .execute {
                runs.save(
                    EndOfDayRun.start(
                        hotelApi.currentBusinessDate(),
                        RunTrigger.AUTOMATIC,
                        null,
                        registry.slots,
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                    ),
                )
            }.shouldNotBeNull()

    private fun Frames.about(run: EndOfDayRun): List<StreamFrame.Event> =
        events.filter { it.name == EndOfDayRealtime.TOPIC.name && """"id":"${run.id.value}"""" in it.data }

    private fun StreamFrame.Event.version(): Long = json.readTree(data)["version"].asLong()

    private fun persistedVersion(run: EndOfDayRun): Long = runs.findById(run.id.value).orElseThrow().version

    private fun awaitCount(
        frames: Frames,
        run: EndOfDayRun,
        count: Int,
    ) {
        await().atMost(Duration.ofSeconds(5)).until { frames.about(run).size == count }
    }

    private fun settleDelivery() = Thread.sleep(SETTLE_MILLIS)

    private fun stream(
        path: String,
        user: TestUser,
    ) = iam.getWithToken(path, iam.loginOk(user)["accessToken"].asString())

    private fun grant(
        code: PermissionCode,
        scope: PermissionScope = PermissionScope.ALL,
    ) = TestGrant(EndOfDayPermissions.permissions.single { it.code == code }, scope)

    @Test
    fun `a failing step publishes begin and fail only, with strictly rising versions ending at the stored one`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            probe.failNext(ONE, 1)
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.FAILED)

            // begin + fail; o complete desfeito (a etapa lançou) não publica.
            awaitCount(frames, run, 2)
            settleDelivery()
            val versions = frames.about(run).map { it.version() }
            versions.size shouldBe 2
            versions shouldBe versions.sorted().distinct()
            versions.last() shouldBe persistedVersion(run)
            frames.about(run).forEach {
                json.readTree(it.data).propertyNames().toList() shouldContainExactly listOf("type", "id", "version")
            }
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a stale complete or fail publishes nothing and does not move the stored version`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            val start = transitions.begin(run.id).shouldNotBeNull()
            awaitCount(frames, run, 1)
            val stored = persistedVersion(run)

            transitions.complete(run.id, start.name, start.attempts + 1) { EndOfDayStepOutcome.DONE } shouldBe false
            transitions.fail(run.id, start.name, start.attempts + 1, "STALE") shouldBe false
            settleDelivery()

            frames.about(run).size shouldBe 1
            persistedVersion(run) shouldBe stored
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a complete whose work throws is rolled back and publishes nothing`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            val start = transitions.begin(run.id).shouldNotBeNull()
            awaitCount(frames, run, 1)
            val stored = persistedVersion(run)

            shouldThrow<IllegalStateException> {
                transitions.complete(run.id, start.name, start.attempts) { error("etapa quebrada") }
            }
            settleDelivery()

            frames.about(run).size shouldBe 1
            persistedVersion(run) shouldBe stored
            val step =
                tx.execute {
                    runs
                        .lockForUpdate(run.id)
                        .shouldNotBeNull()
                        .steps
                        .first { s -> s.name == start.name }
                }
            step.shouldNotBeNull().status shouldBe StepStatus.RUNNING
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a begin inside an outer transaction that rolls back publishes nothing`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            tx.executeWithoutResult { status ->
                transitions.begin(run.id).shouldNotBeNull()
                status.setRollbackOnly()
            }
            settleDelivery()

            frames.about(run).shouldBeEmpty()
            persistedVersion(run) shouldBe 0L
            // Controle: o mesmo begin confirmado publica, então o silêncio de antes não é do canal quebrado.
            transitions.begin(run.id).shouldNotBeNull()
            awaitCount(frames, run, 1)
            frames.about(run).single().version() shouldBe persistedVersion(run)
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a begin on an already completed run publishes nothing`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            val persisted = persistedVersion(run)
            await().atMost(Duration.ofSeconds(5)).until { frames.about(run).lastOrNull()?.version() == persisted }
            val before = frames.about(run).size

            transitions.begin(run.id) shouldBe null
            settleDelivery()

            frames.about(run).size shouldBe before
            persistedVersion(run) shouldBe persisted
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a subscriber without the ALL scope receives nothing, and no message carries step or personal data`() {
        val all = Frames()
        val own = Frames()
        val allConnection = connect(all)
        val ownConnection = connect(own, PermissionScope.OWN)
        val run = newRun()
        try {
            probe.raiseIssueOn(ONE, "TEST_ISSUE")
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            val persisted = persistedVersion(run)
            await().atMost(Duration.ofSeconds(5)).until { all.about(run).lastOrNull()?.version() == persisted }
            settleDelivery()
            own.events.filter { run.id.value.toString() in it.data }.shouldBeEmpty()
            all.about(run).forEach {
                it.data shouldBe """{"type":"endofday.run","id":"${run.id.value}","version":${it.version()}}"""
                it.data shouldNotContain "owners"
                it.data shouldNotContain "TEST_ISSUE"
            }
            issues.findByRunIdOrderByCreatedAtAscIdAsc(run.id.value).isEmpty() shouldBe false
        } finally {
            hub.release(allConnection)
            hub.release(ownConnection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `subscribing to end-of-day needs endofday runs read, and an unknown topic is a 400`() {
        val reader = iam.createUserWith(grant(EndOfDayPermissions.RUNS_READ))
        val executor = iam.createUserWith(grant(EndOfDayPermissions.RUNS_EXECUTE))
        val nobody = iam.createUser()

        listOf(nobody, executor).forEach {
            val response = stream("/api/v1/stream?topics=end-of-day", it)
            response.status shouldBe 403
            json.readTree(response.contentAsString)["code"].asString() shouldBe "PERMISSION_DENIED"
        }
        val unknown = stream("/api/v1/stream?topics=end-of-day,unknown-topic", reader)
        unknown.status shouldBe 400
        json.readTree(unknown.contentAsString)["code"].asString() shouldBe "UNKNOWN_STREAM_TOPIC"
        stream("/api/v1/stream?topics=endofday", reader).status shouldBe 400
    }

    @Test
    fun `a reader of end-of-day is accepted by the stream`() {
        val reader = iam.createUserWith(grant(EndOfDayPermissions.RUNS_READ))
        val response = stream("/api/v1/stream?topics=end-of-day", reader)
        response.status shouldBe 200
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(60)
        const val SETTLE_MILLIS = 500L
    }
}

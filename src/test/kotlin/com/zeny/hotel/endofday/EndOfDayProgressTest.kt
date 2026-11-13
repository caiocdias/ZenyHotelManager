package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.application.realtime.RealtimeHub
import com.zeny.hotel.shared.application.realtime.StreamFrame
import com.zeny.hotel.shared.application.realtime.StreamSink
import com.zeny.hotel.shared.application.realtime.StreamSubscription
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** O andamento do fechamento no tópico `end-of-day` do tempo real (M6 E10, Task 7), lido em processo. */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, o registro, o hub e os apoios de transação e relógio
class EndOfDayProgressTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val hub: RealtimeHub,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
) {
    private class Frames : StreamSink {
        val events = CopyOnWriteArrayList<StreamFrame.Event>()

        override fun send(frame: StreamFrame) {
            if (frame is StreamFrame.Event) events += frame
        }

        override fun complete() = Unit
    }

    private fun connect(frames: Frames): UUID =
        hub.connect(
            StreamSubscription(
                Actor(UserId.new(), DeviceSessionId.new()),
                mapOf(EndOfDayRealtime.TOPIC to PermissionScope.ALL),
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

    private fun Frames.about(id: UUID): List<StreamFrame.Event> =
        events.filter { it.name == EndOfDayRealtime.TOPIC.name && """"id":"$id"""" in it.data }

    private fun StreamFrame.Event.version(): Long =
        VERSION
            .find(data)
            .shouldNotBeNull()
            .groupValues[1]
            .toLong()

    @Test
    fun `progress is published on the end-of-day topic`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)

            val persisted = runs.findById(run.id.value).orElseThrow().version
            await().atMost(Duration.ofSeconds(5)).until {
                frames.about(run.id.value).lastOrNull()?.version() ==
                    persisted
            }
            val messages = frames.about(run.id.value)
            messages.forEach {
                it.data shouldBe """{"type":"endofday.run","id":"${run.id.value}","version":${it.version()}}"""
            }
            val versions = messages.map { it.version() }
            versions shouldContainExactly versions.sorted().distinct()
            versions.size shouldBeGreaterThanOrEqualTo STEPS.size * 2
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    @Test
    fun `a transition that changes nothing publishes nothing`() {
        val frames = Frames()
        val connection = connect(frames)
        val run = newRun()
        try {
            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            val persisted = runs.findById(run.id.value).orElseThrow().version
            await().atMost(Duration.ofSeconds(5)).until {
                frames.about(run.id.value).lastOrNull()?.version() ==
                    persisted
            }
            val before = frames.about(run.id.value).size

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            Thread.sleep(SETTLE_MILLIS)

            frames.about(run.id.value).size shouldBe before
        } finally {
            hub.release(connection)
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        val VERSION = Regex(""""version":(\d+)""")
        val WAIT: Duration = Duration.ofSeconds(60)
        const val SETTLE_MILLIS = 500L
    }
}

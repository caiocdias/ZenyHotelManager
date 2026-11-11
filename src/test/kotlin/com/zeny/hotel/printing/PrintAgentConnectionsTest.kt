package com.zeny.hotel.printing

import com.zeny.hotel.printing.application.PrintAgentConnections
import com.zeny.hotel.printing.infrastructure.PrintingProperties
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** O registro das conexões do agente sem Spring: parada do servidor e despejo concorrente (M5 E10, Task 11). */
class PrintAgentConnectionsTest {
    private fun started() = PrintAgentConnections(PrintingProperties(maxConnectionsPerAgent = 2)).also { it.start() }

    @Test
    fun `stopping ends every open connection at once and runs each eviction callback`() {
        val connections = started()
        val agent = UUID.randomUUID()
        val other = UUID.randomUUID()
        val evictions = AtomicInteger()
        val opened =
            listOf(agent, agent, other).map { id -> connections.open(id) { evictions.incrementAndGet() } }

        connections.stop()

        opened.forEach { it.isDone shouldBe true }
        opened.forEach { it.evicted shouldBe true }
        opened.forEach { it.awaitDone(Duration.ZERO) shouldBe true }
        evictions.get() shouldBe 3
        connections.openCount(agent) shouldBe 0
        connections.openCount(other) shouldBe 0
        connections.isRunning() shouldBe false
    }

    @Test
    fun `a connection opened after the stop is born ended and is not registered`() {
        val connections = started()
        connections.stop()
        val agent = UUID.randomUUID()
        val evictions = AtomicInteger()

        val late = connections.open(agent) { evictions.incrementAndGet() }

        late.isDone shouldBe true
        late.evicted shouldBe true
        evictions.get() shouldBe 1
        connections.openCount(agent) shouldBe 0
    }

    @Test
    fun `a stop racing with the eviction by the limit runs the callback of each connection exactly once`() {
        repeat(RACES) {
            val connections = started()
            val agent = UUID.randomUUID()
            val evictions = AtomicInteger()
            val first = connections.open(agent) { evictions.incrementAndGet() }
            connections.open(agent) { evictions.incrementAndGet() }
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val stopping =
                    pool.submit {
                        gate.await()
                        connections.stop()
                    }
                // O terceiro despeja a mais antiga (first) enquanto o stop encerra todas.
                val opening =
                    pool.submit {
                        gate.await()
                        connections.open(agent) { evictions.incrementAndGet() }
                    }
                gate.countDown()
                stopping.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                opening.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            first.isDone shouldBe true
            evictions.get() shouldBe 3
        }
    }

    private companion object {
        const val RACES = 5_000
        const val TIMEOUT_SECONDS = 10L
    }
}

package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.infrastructure.EndOfDayExecutionConfiguration
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.javacrumbs.shedlock.core.ExtensibleLockProvider
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.core.SimpleLock
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * O *keep-alive* da trava global do fechamento (M6 E13, obrigação herdada da E10; Review Focus 5): o executor montado
 * por [EndOfDayExecutionConfiguration] sobre um `LockProvider` falso, com `lockAtMostFor` de 30 s e um agendador que o
 * teste dispara à mão. Uma tarefa mais longa que o `lockAtMostFor` (um render longo do PDF) tem a trava estendida antes
 * de vencer, e uma segunda tentativa na mesma janela é recusada, então o fechamento não roda em paralelo.
 */
class EndOfDayKeepAliveTest {
    /** O `LockProvider` falso: relógio próprio, a trava vence em `lockAtMostFor` e registra as extensões. */
    private class FakeLockProvider : ExtensibleLockProvider {
        var now: Instant = Instant.parse("2026-11-20T21:00:00Z")
        val extensions = mutableListOf<Duration>()
        private val lockedUntil = mutableMapOf<String, Instant>()

        override fun lock(lockConfiguration: LockConfiguration): Optional<SimpleLock> {
            val name = lockConfiguration.name
            if (lockedUntil[name]?.isAfter(now) == true) return Optional.empty()
            lockedUntil[name] = now.plus(lockConfiguration.lockAtMostFor)
            return Optional.of(FakeLock(lockConfiguration))
        }

        private inner class FakeLock(
            private val configuration: LockConfiguration,
        ) : SimpleLock {
            override fun unlock() {
                lockedUntil.remove(configuration.name)
            }

            override fun extend(
                lockAtMostFor: Duration,
                lockAtLeastFor: Duration,
            ): Optional<SimpleLock> {
                if (lockedUntil[configuration.name]?.isAfter(now) != true) return Optional.empty()
                extensions += lockAtMostFor
                lockedUntil[configuration.name] = now.plus(lockAtMostFor)
                return Optional.of(this)
            }
        }
    }

    @Test
    fun `a task longer than the lock-at-most-for keeps the lock and a second attempt is refused`() {
        val provider = FakeLockProvider()
        val ticks = mutableListOf<Runnable>()
        val scheduler = mockk<ScheduledExecutorService>()
        every { scheduler.scheduleAtFixedRate(any(), any(), any(), any()) } answers {
            ticks += firstArg<Runnable>()
            mockk<ScheduledFuture<*>>(relaxed = true)
        }
        val executor = EndOfDayExecutionConfiguration().endOfDayLockingTaskExecutor(provider, scheduler)
        val configuration =
            LockConfiguration(provider.now, "end-of-day-run", Duration.ofSeconds(AT_MOST_SECONDS), Duration.ZERO)
        val secondAttempts = mutableListOf<Boolean>()

        val first =
            executor.executeWithLock(
                LockingTaskExecutor.TaskWithResult {
                    // O render dura 40 s: sem o keep-alive, a trava venceria aos 30 s.
                    provider.now = provider.now.plusSeconds(HALF)
                    ticks.single().run()
                    provider.now = provider.now.plusSeconds(HALF)
                    ticks.single().run()
                    provider.now = provider.now.plusSeconds(TAIL)
                    // Outra thread: na mesma thread o ShedLock reentra na trava que ela já segura.
                    secondAttempts +=
                        CompletableFuture
                            .supplyAsync {
                                executor
                                    .executeWithLock(LockingTaskExecutor.TaskWithResult { "outra" }, configuration)
                                    .wasExecuted()
                            }.get(ATTEMPT_SECONDS, TimeUnit.SECONDS)
                    "primeira"
                },
                configuration,
            )

        first.wasExecuted() shouldBe true
        secondAttempts shouldContainExactly listOf(false)
        provider.extensions shouldContainExactly
            listOf(Duration.ofSeconds(AT_MOST_SECONDS), Duration.ofSeconds(AT_MOST_SECONDS))
        // Um agendamento por trava, a cada metade do lockAtMostFor.
        verifyPeriod(scheduler)
        // Solta a trava ao terminar: a tentativa seguinte roda.
        executor.executeWithLock(LockingTaskExecutor.TaskWithResult { "depois" }, configuration).wasExecuted() shouldBe
            true
    }

    private fun verifyPeriod(scheduler: ScheduledExecutorService) {
        io.mockk.verify(exactly = 1) {
            scheduler.scheduleAtFixedRate(any(), HALF * MILLIS, HALF * MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val AT_MOST_SECONDS = 30L
        const val HALF = 15L
        const val TAIL = 10L
        const val ATTEMPT_SECONDS = 10L
        const val MILLIS = 1000L
    }
}

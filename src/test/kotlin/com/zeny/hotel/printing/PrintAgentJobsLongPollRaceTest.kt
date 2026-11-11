package com.zeny.hotel.printing

import com.zeny.hotel.printing.application.AgentConnection
import com.zeny.hotel.printing.application.DeliveredJob
import com.zeny.hotel.printing.application.PrintAgentJobsService
import com.zeny.hotel.printing.web.PrintAgentJobsController
import com.zeny.hotel.shared.ServicePrincipal
import com.zeny.hotel.shared.UuidV7
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** O long-poll que perde a corrida para o timeout devolve o que reivindicou (revisão final da M5 E10, M1). */
class PrintAgentJobsLongPollRaceTest {
    private val principal = ServicePrincipal("print-agent", UuidV7.generate(), "stamp")

    private fun delivered() =
        DeliveredJob(UuidV7.generate(), UuidV7.generate(), "frontdesk.card", "application/pdf", 1, Instant.now(), 1L)

    @Test
    fun `a poll whose response was already completed by the timeout releases every claimed job`() {
        val first = delivered()
        val second = delivered()
        val service = mockk<PrintAgentJobsService>()
        val reached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        every { service.resolveWait(any()) } returns Duration.ZERO
        every { service.open(principal) } returns mockk<AgentConnection>(relaxed = true)
        every { service.awaitJobs(principal, any(), any()) } answers {
            reached.countDown()
            proceed.await(5, TimeUnit.SECONDS)
            listOf(first, second)
        }
        every { service.release(principal, any()) } just Runs

        val result = PrintAgentJobsController(service).poll(principal, 0)
        reached.await(5, TimeUnit.SECONDS)
        result.setResult(emptyList()) // o onTimeout ganhou a corrida
        proceed.countDown()

        verify(timeout = 5_000) { service.release(principal, first.id) }
        verify(timeout = 5_000) { service.release(principal, second.id) }
    }
}

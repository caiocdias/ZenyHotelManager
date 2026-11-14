package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunCommands
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * A retomada manual não espera a etapa em curso (M6 E11, Task 18): enquanto o motor roda uma etapa, a transação dela
 * segura a linha da execução; a retomada limita a espera pela trava (`lock_timeout`) e responde
 * `409 END_OF_DAY_RUN_IN_PROGRESS` em vez de prender a requisição até a etapa terminar. No banco isolado, com as etapas
 * falsas; o teste termina com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // os apoios do contexto
class EndOfDayResumeLockTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val json: JsonMapper,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val commands: EndOfDayRunCommands,
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val context: ApplicationContext,
) {
    private val execute = EndOfDayPermissions.permissions.single { it.code == EndOfDayPermissions.RUNS_EXECUTE }

    @Test
    @Timeout(60)
    fun `a resume while a step holds the run is IN_PROGRESS without waiting for the step`() {
        val gate = CountDownLatch(1)
        try {
            probe.reset()
            probe.holdOn(TWO, gate)
            val run = commands.createAutomatic(hotelApi.currentBusinessDate())
            engine.execute(run.id, Duration.ofMillis(100)) shouldBe EndOfDayExecution.StillRunning
            await().atMost(LIMIT).until { probe.invocations(TWO, run.businessDate) == 1 }
            val manager = iam.createUserWith(TestGrant(execute, PermissionScope.ALL))

            val started = System.nanoTime()
            val response =
                mockMvc
                    .post("/api/v1/end-of-day/runs/${run.id.value}/resume") {
                        with(asUser(manager.id.value))
                        header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    }.andReturn()
                    .response
            val elapsed = Duration.ofNanos(System.nanoTime() - started)

            val body = response.getContentAsString(Charsets.UTF_8)
            withClue(body) { response.status shouldBe 409 }
            json.readTree(body)["code"].asString() shouldBe "END_OF_DAY_RUN_IN_PROGRESS"
            elapsed shouldBeLessThan RESPONDS_WITHIN

            gate.countDown()
            EndOfDayTestRuns.awaitEngine(context)
            runs.findById(run.id.value).orElseThrow().status shouldBe RunStatus.COMPLETED
        } finally {
            gate.countDown()
            EndOfDayTestRuns.settle(context)
        }
    }

    private companion object {
        val LIMIT: Duration = Duration.ofSeconds(30)
        val RESPONDS_WITHIN: Duration = Duration.ofSeconds(10)
    }
}

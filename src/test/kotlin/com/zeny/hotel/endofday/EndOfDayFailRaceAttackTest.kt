package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.FakeStepsProbe.Companion.ONE
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.STEPS
import com.zeny.hotel.endofday.FakeStepsProbe.Companion.TWO
import com.zeny.hotel.endofday.application.EndOfDayEngine
import com.zeny.hotel.endofday.application.EndOfDayExecution
import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.application.StepAttemptLostException
import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunMetrics
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ExecutorService

/**
 * Ataques ao tipo próprio da corrida no `fail` (M6 E11, Task 18), pelo testador, com as transições reais no banco
 * isolado: [StepAttemptLostException] nos dois caminhos (etapa e execução fora de `RUNNING`), nunca para execução
 * inexistente nem etapa desconhecida; o motor devolve `Busy` quando o `fail` **real** a lança depois de outro executor
 * passar à frente (concluindo ou falhando a etapa); e, depois de uma `IllegalStateException` comum que escapou do
 * `fail`, o motor seguinte conclui a execução sem repetir etapa concluída.
 *
 * As transições diretas usam datas 70 anos atrás (sem motor, sem `settle`); as do motor usam a data corrente e
 * terminam com [EndOfDayTestRuns.settle].
 */
@EndOfDayIntegrationTest
@Import(EndOfDayTestConfiguration::class)
@Suppress("LongParameterList") // o motor, as transições, os repositórios e os apoios do contexto
class EndOfDayFailRaceAttackTest(
    @Autowired private val engine: EndOfDayEngine,
    @Autowired private val transitions: EndOfDayRunTransitions,
    @Autowired private val registry: EndOfDayStepRegistry,
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val metrics: EndOfDayRunMetrics,
    @Autowired private val probe: FakeStepsProbe,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
    @Autowired private val context: ApplicationContext,
) {
    @BeforeEach
    fun resetProbe() = probe.reset()

    /**
     * Uma data 70 anos **atrás**: as execuções que estes testes deixam ficam no fim da lista (data mais nova
     * primeiro) e não passam à frente das execuções da data corrente que os testes de paginação conferem.
     */
    private fun pastDate(days: Long): LocalDate = hotelApi.currentBusinessDate().minusYears(PAST_YEARS).minusDays(days)

    private fun saved(date: LocalDate): EndOfDayRun =
        tx
            .execute {
                runs.save(
                    EndOfDayRun.start(
                        date,
                        RunTrigger.AUTOMATIC,
                        null,
                        registry.slots,
                        clock.instant().truncatedTo(ChronoUnit.MICROS),
                    ),
                )
            }.shouldNotBeNull()

    private fun settled(block: () -> Unit) {
        try {
            block()
        } finally {
            EndOfDayTestRuns.settle(context)
        }
    }

    private fun versionOf(id: EndOfDayRunId): Long = runs.findById(id.value).orElseThrow().version

    private fun issuesOf(id: EndOfDayRunId): List<EndOfDayIssue> = issues.findByRunIdOrderByCreatedAtAscIdAsc(id.value)

    private fun inspect(
        id: EndOfDayRunId,
        check: EndOfDayRun.() -> Unit,
    ) = tx.executeWithoutResult { _ -> runs.lockForUpdate(id).shouldNotBeNull().check() }

    private fun EndOfDayRun.step(name: String) = steps.single { it.name == name }

    private fun failEntries(id: EndOfDayRunId): Long =
        jdbc
            .sql(
                "SELECT count(*) FROM audit.audit_entry WHERE entity_id = :id AND action = 'endofday.run-step.fail'",
            ).param("id", id.value.toString())
            .query(Long::class.java)
            .single()

    private fun resumeUnderLock(id: EndOfDayRunId) =
        tx.executeWithoutResult { _ ->
            runs.lockForUpdate(id).shouldNotBeNull().resume(clock.instant().truncatedTo(ChronoUnit.MICROS))
        }

    /** O `fail` de quem perdeu a corrida: lança o tipo próprio e não muda nada (versão, pendência, trilha). */
    private fun lostRaceChangesNothing(
        id: EndOfDayRunId,
        step: String,
        attempts: Int,
    ) {
        val version = versionOf(id)
        val issueCount = issuesOf(id).size
        val entries = failEntries(id)

        shouldThrow<StepAttemptLostException> { transitions.fail(id, step, attempts, "TEST_STALE") }

        versionOf(id) shouldBe version
        issuesOf(id).size shouldBe issueCount
        failEntries(id) shouldBe entries
    }

    /** O motor do contexto com as transições [overtaken] no lugar das reais. */
    private fun engineWith(overtaken: EndOfDayRunTransitions) =
        EndOfDayEngine(
            runs,
            overtaken,
            registry,
            context.getBean("endOfDayLockingTaskExecutor", LockingTaskExecutor::class.java),
            context.getBean("endOfDayExecutor", ExecutorService::class.java),
            HELD_FOR,
            metrics,
        )

    /**
     * As transições reais da execução [run], com o `fail` trocado por [onFail]: o que outro executor (depois de uma
     * trava vencida) faz antes de o `fail` real deste executor chegar ao banco.
     */
    private fun overtakenAt(
        run: EndOfDayRun,
        onFail: (step: String, attempts: Int, reason: String) -> Boolean,
    ): EndOfDayRunTransitions {
        val real = transitions
        return mockk {
            every { begin(run.id) } answers { real.begin(run.id) }
            every { complete(run.id, any(), any(), any()) } answers {
                real.complete(run.id, secondArg(), thirdArg(), arg<(EndOfDayStepContext) -> EndOfDayStepOutcome>(3))
            }
            every { fail(run.id, any(), any(), any()) } answers { onFail(secondArg(), thirdArg(), arg(3)) }
        }
    }

    private fun createdForToday(): EndOfDayRun = saved(hotelApi.currentBusinessDate())

    // --- As transições: os dois caminhos do tipo próprio ---

    @Test
    fun `fail on a step another executor already completed is a lost race and changes nothing`() {
        val run = saved(pastDate(0))
        transitions.begin(run.id).shouldNotBeNull().attempts shouldBe 1
        transitions.complete(run.id, ONE, 1) { EndOfDayStepOutcome.DONE } shouldBe true

        lostRaceChangesNothing(run.id, ONE, 1)

        inspect(run.id) {
            status shouldBe RunStatus.RUNNING
            step(ONE).status shouldBe StepStatus.COMPLETED
        }
    }

    @Test
    fun `fail on a step that a resume put back to PENDING is a lost race and changes nothing`() {
        val run = saved(pastDate(STEP_DAYS))
        transitions.begin(run.id)
        transitions.fail(run.id, ONE, 1, "TEST_FIRST") shouldBe true
        resumeUnderLock(run.id)

        lostRaceChangesNothing(run.id, ONE, 1)

        inspect(run.id) {
            status shouldBe RunStatus.RUNNING
            step(ONE).status shouldBe StepStatus.PENDING
        }
    }

    @Test
    fun `fail on a run that another executor already failed is a lost race and opens no second issue`() {
        val run = saved(pastDate(2 * STEP_DAYS))
        transitions.begin(run.id)
        transitions.fail(run.id, ONE, 1, "TEST_OTHER") shouldBe true

        lostRaceChangesNothing(run.id, ONE, 1)

        issuesOf(run.id).map { it.kind to it.message } shouldContainExactly
            listOf(EndOfDayRunTransitions.STEP_FAILED to "TEST_OTHER")
        inspect(run.id) { status shouldBe RunStatus.FAILED }
    }

    @Test
    fun `fail on a COMPLETED run is a lost race for any of its steps`() {
        val run = saved(pastDate(3 * STEP_DAYS))
        while (true) {
            val start = transitions.begin(run.id) ?: break
            transitions.complete(run.id, start.name, start.attempts) { EndOfDayStepOutcome.DONE } shouldBe true
        }

        lostRaceChangesNothing(run.id, STEPS.last(), 1)
        lostRaceChangesNothing(run.id, ONE, 1)

        inspect(run.id) { status shouldBe RunStatus.COMPLETED }
    }

    @Test
    fun `an unknown run or an unknown step is refused but never as a lost race`() {
        val run = saved(pastDate(4 * STEP_DAYS))
        transitions.begin(run.id)

        val missingRun = shouldThrow<IllegalStateException> { transitions.fail(EndOfDayRunId.new(), ONE, 1, "TEST") }
        val unknownStep = shouldThrow<IllegalArgumentException> { transitions.fail(run.id, "fake-nope", 1, "TEST") }

        missingRun.shouldNotBeInstanceOf<StepAttemptLostException>()
        unknownStep.shouldNotBeInstanceOf<StepAttemptLostException>()
        issuesOf(run.id).shouldBeEmpty()
        failEntries(run.id) shouldBe 0L
    }

    // --- O motor com o fail real depois de outro executor passar à frente ---

    @Test
    fun `the engine is Busy when the real fail finds the step completed by another executor`() =
        settled {
            probe.failNext(TWO, 1)
            val run = createdForToday()
            val overtaken =
                overtakenAt(run) { step, attempts, reason ->
                    val restarted = transitions.begin(run.id).shouldNotBeNull()
                    transitions.complete(run.id, restarted.name, restarted.attempts) { EndOfDayStepOutcome.DONE }
                    transitions.fail(run.id, step, attempts, reason)
                }

            engineWith(overtaken).execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

            issuesOf(run.id).shouldBeEmpty()
            inspect(run.id) {
                status shouldBe RunStatus.RUNNING
                step(TWO).status shouldBe StepStatus.COMPLETED
                step(TWO).attempts shouldBe 2
            }
        }

    @Test
    fun `the engine is Busy when the real fail finds the run failed by another executor`() =
        settled {
            probe.failNext(TWO, 1)
            val run = createdForToday()
            val overtaken =
                overtakenAt(run) { step, attempts, reason ->
                    val restarted = transitions.begin(run.id).shouldNotBeNull()
                    transitions.fail(run.id, restarted.name, restarted.attempts, "TEST_OTHER_EXECUTOR")
                    transitions.fail(run.id, step, attempts, reason)
                }

            engineWith(overtaken).execute(run.id, WAIT) shouldBe EndOfDayExecution.Busy

            issuesOf(run.id).map { it.message } shouldContainExactly listOf("TEST_OTHER_EXECUTOR")
            inspect(run.id) {
                status shouldBe RunStatus.FAILED
                step(TWO).attempts shouldBe 2
            }
        }

    // --- Depois da exceção que escapa do fail ---

    @Test
    fun `after an unexpected exception escapes the fail, the next execute completes the run without repeating steps`() =
        settled {
            probe.failNext(TWO, 1)
            val run = createdForToday()
            val broken = overtakenAt(run) { _, _, _ -> throw IllegalStateException("TEST_BROKEN_FAIL") }

            val escaped = shouldThrow<IllegalStateException> { engineWith(broken).execute(run.id, WAIT) }
            escaped.shouldNotBeInstanceOf<StepAttemptLostException>()

            engine.execute(run.id, WAIT) shouldBe EndOfDayExecution.Finished(RunStatus.COMPLETED)
            STEPS.map { probe.invocations(it, run.businessDate) } shouldContainExactly listOf(1, 2, 1, 1)
            issuesOf(run.id).shouldBeEmpty()
            inspect(run.id) {
                status shouldBe RunStatus.COMPLETED
                step(ONE).attempts shouldBe 1
                step(TWO).attempts shouldBe 2
            }
        }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(30)
        val HELD_FOR: Duration = Duration.ofMinutes(1)
        const val PAST_YEARS = 70L
        const val STEP_DAYS = 400L
    }
}

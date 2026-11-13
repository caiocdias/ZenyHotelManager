package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayRunTransitions
import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.endofday.infrastructure.EndOfDayRunRepository
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ataques às transições da execução do fechamento (M6 E10, Task 4), pelo testador: repetição e concorrência do
 * `begin`, `complete` obsoleto sob duas threads, `complete` contra `fail`, rollback do trabalho, conteúdo da trilha e
 * falhas de `fail`. Banco isolado; datas 61 anos à frente da data operacional (o teste do desenvolvedor usa 60).
 */
@EndOfDayIntegrationTest
@Suppress("LongParameterList", "LargeClass")
class EndOfDayRunTransitionsAttackTest(
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val audit: AuditContext,
    @Autowired private val clock: Clock,
    @Autowired private val beanFactory: AutowireCapableBeanFactory,
) {
    private class FakeStep(
        override val name: String,
        override val position: Int,
    ) : EndOfDayStepHandler {
        override fun execute(context: EndOfDayStepContext) = EndOfDayStepOutcome.DONE
    }

    private data class AuditRow(
        val action: String,
        val userId: Any?,
        val sessionId: Any?,
        val outcome: String,
        val before: String?,
        val after: String?,
    )

    private val one = FakeStep("atk-one", 10)
    private val two = FakeStep("atk-two", 20)

    private fun farDate(days: Long): LocalDate = hotelApi.currentBusinessDate().plusYears(FAR_YEARS).plusDays(days)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun saved(
        date: LocalDate,
        steps: List<StepSlot> = listOf(StepSlot(one.name, one.position), StepSlot(two.name, two.position)),
    ): EndOfDayRun = tx.execute { runs.save(EndOfDayRun.start(date, RunTrigger.AUTOMATIC, null, steps, now())) }

    private fun transitionsWith(vararg handlers: EndOfDayStepHandler): EndOfDayRunTransitions =
        beanFactory.initializeBean(
            EndOfDayRunTransitions(runs, issues, EndOfDayStepRegistry(handlers.toList()), audit, clock),
            "endOfDayRunTransitionsAttack",
        ) as EndOfDayRunTransitions

    private fun inspect(
        id: EndOfDayRunId,
        check: EndOfDayRun.() -> Unit,
    ) = tx.executeWithoutResult { _ -> runs.lockForUpdate(id).shouldNotBeNull().check() }

    private fun versionOf(id: EndOfDayRunId): Long = tx.execute { runs.lockForUpdate(id).shouldNotBeNull().version }

    private fun EndOfDayRun.statuses() = steps.map { it.name to it.status }

    private fun issuesOf(id: EndOfDayRunId): List<EndOfDayIssue> = issues.findByRunIdOrderByCreatedAtAscIdAsc(id.value)

    private fun auditActions(id: EndOfDayRunId): List<String> =
        jdbc
            .sql(
                "SELECT action FROM audit.audit_entry WHERE entity_type = 'endofday.run' AND entity_id = :id " +
                    "ORDER BY occurred_at, id",
            ).param("id", id.value.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    @Test
    fun `a repeated begin never opens a second STEP_UNREGISTERED issue for the same skipped step`() {
        val run = saved(farDate(100), listOf(StepSlot("atk-gone", 5), StepSlot(one.name, one.position)))
        val transitions = transitionsWith(one)

        transitions.begin(run.id) shouldBe StepStart("atk-one", 1)
        transitions.begin(run.id) shouldBe StepStart("atk-one", 2)
        transitions.begin(run.id) shouldBe StepStart("atk-one", 3)

        issuesOf(run.id).map { it.kind to it.reference } shouldContainExactly
            listOf(EndOfDayRunTransitions.STEP_UNREGISTERED to "atk-gone")
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two concurrent begins serialize, open one issue per skipped step and hand out distinct attempts`() {
        val run = saved(farDate(150), listOf(StepSlot("atk-gone", 5), StepSlot(one.name, one.position)))
        val transitions = transitionsWith(one)
        val go = CountDownLatch(1)

        val futures =
            List(2) {
                CompletableFuture.supplyAsync {
                    go.await(LATCH_SECONDS, TimeUnit.SECONDS)
                    transitions.begin(run.id)
                }
            }
        go.countDown()
        val starts = futures.map { it.get(LATCH_SECONDS * 2, TimeUnit.SECONDS) }

        starts.map { it?.attempts } shouldContainExactlyInAnyOrder listOf(1, 2)
        issuesOf(run.id).map { it.kind to it.reference } shouldContainExactly
            listOf(EndOfDayRunTransitions.STEP_UNREGISTERED to "atk-gone")
    }

    @Test
    fun `begin on a failed run is refused and changes nothing`() {
        val run = saved(farDate(200))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)
        transitions.fail(run.id, "atk-one", 1, "TEST_BROKEN")
        val version = versionOf(run.id)
        val issueCount = issuesOf(run.id).size

        shouldThrow<IllegalStateException> { transitions.begin(run.id) }

        versionOf(run.id) shouldBe version
        issuesOf(run.id).size shouldBe issueCount
        inspect(run.id) { status shouldBe RunStatus.FAILED }
    }

    @Test
    fun `complete refuses a failed run, a step not begun and an unknown step without calling the work`() {
        val run = saved(farDate(250))
        val transitions = transitionsWith(one, two)
        val calls = AtomicInteger()
        val work: (EndOfDayStepContext) -> EndOfDayStepOutcome = {
            calls.incrementAndGet()
            EndOfDayStepOutcome.DONE
        }
        transitions.begin(run.id)

        transitions.complete(run.id, "atk-two", 0, work) shouldBe false
        transitions.complete(run.id, "atk-two", 1, work) shouldBe false
        transitions.complete(run.id, "atk-nope", 1, work) shouldBe false
        transitions.fail(run.id, "atk-one", 1, "TEST_BROKEN")
        transitions.complete(run.id, "atk-one", 1, work) shouldBe false

        calls.get() shouldBe 0
        inspect(run.id) {
            status shouldBe RunStatus.FAILED
            statuses() shouldContainExactly listOf("atk-one" to StepStatus.FAILED, "atk-two" to StepStatus.PENDING)
        }
    }

    @Test
    fun `an executor that lost the lock after a resumed begin cannot complete with its old attempts`() {
        val run = saved(farDate(300))
        val transitions = transitionsWith(one, two)
        val calls = AtomicInteger()
        val work: (EndOfDayStepContext) -> EndOfDayStepOutcome = {
            calls.incrementAndGet()
            EndOfDayStepOutcome.DONE
        }
        val first = transitions.begin(run.id).shouldNotBeNull()
        val second = transitions.begin(run.id).shouldNotBeNull()
        second.attempts shouldBe first.attempts + 1

        transitions.complete(run.id, first.name, first.attempts, work) shouldBe false
        calls.get() shouldBe 0
        transitions.complete(run.id, second.name, second.attempts, work) shouldBe true
        calls.get() shouldBe 1
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two concurrent completes with a stale attempts value never call the work and change nothing`() {
        val run = saved(farDate(350))
        val transitions = transitionsWith(one, two)
        val stale = transitions.begin(run.id).shouldNotBeNull()
        transitions.begin(run.id).shouldNotBeNull()
        val version = versionOf(run.id)
        val calls = AtomicInteger()
        val go = CountDownLatch(1)

        val futures =
            List(2) {
                CompletableFuture.supplyAsync {
                    go.await(LATCH_SECONDS, TimeUnit.SECONDS)
                    transitions.complete(run.id, stale.name, stale.attempts) {
                        calls.incrementAndGet()
                        EndOfDayStepOutcome.DONE
                    }
                }
            }
        go.countDown()
        val results = futures.map { it.get(LATCH_SECONDS * 2, TimeUnit.SECONDS) }

        results shouldContainExactly listOf(false, false)
        calls.get() shouldBe 0
        versionOf(run.id) shouldBe version
        inspect(run.id) { steps.first().status shouldBe StepStatus.RUNNING }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a complete and a fail racing on the same step leave one consistent outcome`() {
        val run = saved(farDate(400))
        val transitions = transitionsWith(one, two)
        val started = transitions.begin(run.id).shouldNotBeNull()
        val go = CountDownLatch(1)

        val completeFuture =
            CompletableFuture.supplyAsync {
                go.await(LATCH_SECONDS, TimeUnit.SECONDS)
                runCatching {
                    transitions.complete(run.id, started.name, started.attempts) {
                        Thread.sleep(WORK_MILLIS)
                        EndOfDayStepOutcome.DONE
                    }
                }
            }
        val failFuture =
            CompletableFuture.supplyAsync {
                go.await(LATCH_SECONDS, TimeUnit.SECONDS)
                runCatching { transitions.fail(run.id, started.name, started.attempts, "TEST_BROKEN") }
            }
        go.countDown()
        val completed = completeFuture.get(LATCH_SECONDS * 2, TimeUnit.SECONDS)
        val failed = failFuture.get(LATCH_SECONDS * 2, TimeUnit.SECONDS)

        val failIssues = issuesOf(run.id).filter { it.kind == EndOfDayRunTransitions.STEP_FAILED }
        if (completed.getOrNull() == true) {
            failed.isFailure shouldBe true
            failIssues.shouldBeEmpty()
            inspect(run.id) { steps.first().status shouldBe StepStatus.COMPLETED }
        } else {
            failed.isSuccess shouldBe true
            failIssues.size shouldBe 1
            inspect(run.id) {
                status shouldBe RunStatus.FAILED
                steps.first().status shouldBe StepStatus.FAILED
            }
        }
    }

    @Test
    fun `a crashed work leaves no audit entry, and the step can be completed afterwards with the same attempts`() {
        val run = saved(farDate(450))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)
        val before = auditActions(run.id)

        shouldThrow<IllegalStateException> {
            transitions.complete(run.id, "atk-one", 1) {
                it.issues.raise("TEST_PENDING", null, "Pendência levantada antes da queda.")
                error("queda")
            }
        }

        auditActions(run.id) shouldContainExactly before
        issuesOf(run.id).shouldBeEmpty()
        transitions.complete(run.id, "atk-one", 1) { EndOfDayStepOutcome.DONE } shouldBe true
        auditActions(run.id) shouldContainExactly before + "endofday.run-step.complete"
    }

    @Test
    fun `an invalid issue kind raised by the work undoes the whole complete`() {
        val run = saved(farDate(500))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)

        shouldThrow<IllegalArgumentException> {
            transitions.complete(run.id, "atk-one", 1) {
                it.issues.raise("TEST_OK", "x", "Primeira pendência.")
                it.issues.raise("kind em minúsculas", "x", "Segunda pendência.")
                EndOfDayStepOutcome.DONE
            }
        }

        issuesOf(run.id).shouldBeEmpty()
        inspect(run.id) { steps.first().status shouldBe StepStatus.RUNNING }
    }

    @Test
    fun `a stale complete writes no audit entry`() {
        val run = saved(farDate(550))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)
        val before = auditActions(run.id)

        transitions.complete(run.id, "atk-one", 99) { EndOfDayStepOutcome.DONE } shouldBe false

        auditActions(run.id) shouldContainExactly before
    }

    @Test
    fun `the audit trail has the system actor, before and after states and no issue message text`() {
        val run = saved(farDate(600), listOf(StepSlot("atk-gone", 5), StepSlot(one.name, one.position)))
        val transitions = transitionsWith(one)

        transitions.begin(run.id)
        transitions.complete(run.id, "atk-one", 1) {
            it.issues.raise("TEST_PENDING", "Fulano de Tal", "Hóspede Fulano de Tal sem documento")
            EndOfDayStepOutcome.DONE
        }

        val rows =
            jdbc
                .sql(
                    "SELECT action, user_id, device_session_id, outcome, CAST(before_state AS text), " +
                        "CAST(after_state AS text) FROM audit.audit_entry " +
                        "WHERE entity_type = 'endofday.run' AND entity_id = :id ORDER BY occurred_at, id",
                ).param("id", run.id.value.toString())
                .query { rs, _ ->
                    AuditRow(
                        rs.getString(1),
                        rs.getObject(2),
                        rs.getObject(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                    )
                }.list()

        rows.map { it.action } shouldContainExactly listOf("endofday.run-step.begin", "endofday.run-step.complete")
        rows.map { it.userId }.toSet() shouldBe setOf(null)
        rows.map { it.sessionId }.toSet() shouldBe setOf(null)
        rows.map { it.outcome }.toSet() shouldBe setOf("SUCCEEDED")
        rows[1].before.shouldNotBeNull()
        rows[1].after.shouldNotBeNull()
        val issueStates =
            jdbc
                .sql(
                    "SELECT COALESCE(CAST(after_state AS text), '') FROM audit.audit_entry " +
                        "WHERE entity_type = 'endofday.run-issue'",
                ).query(String::class.java)
                .list()
        val everything = rows.joinToString("\n") { "${it.before}${it.after}" } + issueStates.joinToString("\n")
        everything shouldNotContain "Fulano"
        everything shouldNotContain "documento"
        everything shouldNotContain "não está registrada"
    }

    @Test
    fun `fail on an unknown step, a completed step or an unknown run is refused and opens no issue`() {
        val run = saved(farDate(650))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)
        transitions.complete(run.id, "atk-one", 1) { EndOfDayStepOutcome.DONE }
        val version = versionOf(run.id)

        shouldThrow<IllegalArgumentException> { transitions.fail(run.id, "atk-nope", 1, "TEST_BROKEN") }
        shouldThrow<IllegalStateException> { transitions.fail(run.id, "atk-one", 1, "TEST_BROKEN") }
        shouldThrow<IllegalStateException> { transitions.fail(run.id, "atk-two", 0, "TEST_BROKEN") }
        shouldThrow<IllegalStateException> { transitions.fail(EndOfDayRunId.new(), "atk-one", 1, "TEST_BROKEN") }

        issuesOf(run.id).shouldBeEmpty()
        versionOf(run.id) shouldBe version
        inspect(run.id) { status shouldBe RunStatus.RUNNING }
    }

    @Test
    fun `fail cuts the reason at exactly 200 characters and keeps a reason of 200 whole`() {
        val whole = saved(farDate(700))
        val cut = saved(farDate(701))
        val transitions = transitionsWith(one, two)
        transitions.begin(whole.id)
        transitions.begin(cut.id)

        transitions.fail(whole.id, "atk-one", 1, "A".repeat(EXACT))
        transitions.fail(cut.id, "atk-one", 1, "B".repeat(EXACT + 1))

        issuesOf(whole.id).single().message shouldBe "A".repeat(EXACT)
        issuesOf(cut.id).single().message shouldBe "B".repeat(EXACT)
        inspect(cut.id) { steps.first().result shouldBe "B".repeat(EXACT) }
    }

    @Test
    fun `reasonOf gives the code of every domain exception, the class name otherwise, and never the message`() {
        val secret = "Fulano de Tal CPF 123.456.789-09"
        val wrapped = RuntimeException(secret, BusinessRuleException(ErrorCode("TEST_X"), secret))
        val local = object : IllegalArgumentException(secret) {}

        EndOfDayRunTransitions.reasonOf(NotFoundException(ErrorCode("TEST_NOT_FOUND"), secret)) shouldBe
            "TEST_NOT_FOUND"
        EndOfDayRunTransitions.reasonOf(ConflictException(ErrorCode("TEST_CONFLICT"), secret)) shouldBe "TEST_CONFLICT"
        EndOfDayRunTransitions.reasonOf(NullPointerException(secret)) shouldBe "NullPointerException"
        EndOfDayRunTransitions.reasonOf(IllegalStateException()) shouldBe "IllegalStateException"
        EndOfDayRunTransitions.reasonOf(wrapped) shouldBe "RuntimeException"
        EndOfDayRunTransitions.reasonOf(local) shouldNotContain "Fulano"
        EndOfDayRunTransitions.reasonOf(local).isNotBlank() shouldBe true
    }

    private companion object {
        const val FAR_YEARS = 61L
        const val LATCH_SECONDS = 5L
        const val WORK_MILLIS = 300L
        const val EXACT = 200
    }
}

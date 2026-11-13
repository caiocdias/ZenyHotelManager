package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayIssues
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
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.testsupport.EndOfDayIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.AutowireCapableBeanFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * As transições auditadas da execução do fechamento (M6 E10, Task 4), no banco isolado: as execuções ficam 60 anos à
 * frente da data operacional, que nenhum outro teste usa, e a data corrente não é tocada.
 *
 * O contexto não tem etapas registradas (as etapas falsas são da Task 5, noutro contexto). Cada teste monta as
 * transições com o próprio registro e as passa pelos pós-processadores do contexto ([AutowireCapableBeanFactory]
 * `initializeBean`), que aplicam a transação e a auditoria como no bean da aplicação.
 */
@EndOfDayIntegrationTest
@Suppress("LongParameterList") // os repositórios e os apoios de transação, JDBC, auditoria e relógio
class EndOfDayRunTransitionsTest(
    @Autowired private val runs: EndOfDayRunRepository,
    @Autowired private val issues: EndOfDayIssueRepository,
    @Autowired private val hotelApi: HotelApi,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val audit: AuditContext,
    @Autowired private val clock: Clock,
    @Autowired private val realtime: RealtimePublisher,
    @Autowired private val beanFactory: AutowireCapableBeanFactory,
    @Autowired private val notifications: NotificationsApi,
) {
    private class FakeStep(
        override val name: String,
        override val position: Int,
    ) : EndOfDayStepHandler {
        override fun execute(context: EndOfDayStepContext) = EndOfDayStepOutcome.DONE
    }

    private val one = FakeStep("fake-one", 10)
    private val two = FakeStep("fake-two", 20)

    private fun farDate(days: Long): LocalDate = hotelApi.currentBusinessDate().plusYears(FAR_YEARS).plusDays(days)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun saved(
        date: LocalDate,
        steps: List<StepSlot> = listOf(StepSlot(one.name, one.position), StepSlot(two.name, two.position)),
    ): EndOfDayRun = tx.execute { runs.save(EndOfDayRun.start(date, RunTrigger.AUTOMATIC, null, steps, now())) }

    private fun transitionsWith(vararg handlers: EndOfDayStepHandler): EndOfDayRunTransitions =
        beanFactory.initializeBean(
            EndOfDayRunTransitions(
                runs,
                EndOfDayIssues(issues, notifications, clock),
                EndOfDayStepRegistry(handlers.toList()),
                audit,
                clock,
                realtime,
            ),
            "endOfDayRunTransitionsUnderTest",
        ) as EndOfDayRunTransitions

    private fun inspect(
        id: EndOfDayRunId,
        check: EndOfDayRun.() -> Unit,
    ) = tx.executeWithoutResult { _ -> runs.lockForUpdate(id).shouldNotBeNull().check() }

    private fun versionOf(id: EndOfDayRunId): Long = tx.execute { runs.lockForUpdate(id).shouldNotBeNull().version }

    private fun EndOfDayRun.statuses() = steps.map { it.name to it.status }

    private fun issuesOf(id: EndOfDayRunId): List<EndOfDayIssue> = issues.findByRunIdOrderByCreatedAtAscIdAsc(id.value)

    @Test
    fun `begin then complete runs the work once and completes the step`() {
        val date = farDate(100)
        val run = saved(date)
        val transitions = transitionsWith(one, two)
        val calls = AtomicInteger()

        transitions.begin(run.id) shouldBe StepStart("fake-one", 1)
        val done =
            transitions.complete(run.id, "fake-one", 1) {
                calls.incrementAndGet()
                it.runId shouldBe run.id
                it.businessDate shouldBe date
                EndOfDayStepOutcome.DONE
            }

        done shouldBe true
        calls.get() shouldBe 1
        inspect(run.id) {
            status shouldBe RunStatus.RUNNING
            statuses() shouldContainExactly listOf("fake-one" to StepStatus.COMPLETED, "fake-two" to StepStatus.PENDING)
            steps.first().result shouldBe "DONE"
        }

        transitions.begin(run.id) shouldBe StepStart("fake-two", 1)
        transitions.complete(run.id, "fake-two", 1) { EndOfDayStepOutcome.NOT_APPLICABLE } shouldBe true
        transitions.begin(run.id).shouldBeNull()
        inspect(run.id) {
            status shouldBe RunStatus.COMPLETED
            finishedAt.shouldNotBeNull()
            steps.map { it.result } shouldContainExactly listOf("DONE", "NOT_APPLICABLE")
        }
        issuesOf(run.id).shouldBeEmpty()
    }

    @Test
    fun `begin returns null and completes the run when every remaining step is unregistered`() {
        val run = saved(farDate(200))

        transitionsWith().begin(run.id).shouldBeNull()

        inspect(run.id) {
            status shouldBe RunStatus.COMPLETED
            statuses() shouldContainExactly listOf("fake-one" to StepStatus.SKIPPED, "fake-two" to StepStatus.SKIPPED)
            steps.map { it.result }.toSet() shouldBe setOf(EndOfDayRun.UNREGISTERED)
        }
        issuesOf(run.id).map { Triple(it.kind, it.stepName, it.reference) } shouldContainExactly
            listOf(
                Triple(EndOfDayRunTransitions.STEP_UNREGISTERED, "fake-one", "fake-one"),
                Triple(EndOfDayRunTransitions.STEP_UNREGISTERED, "fake-two", "fake-two"),
            )
    }

    @Test
    fun `begin on a completed run returns null and changes nothing`() {
        val run = saved(farDate(250), listOf(StepSlot(one.name, one.position)))
        val transitions = transitionsWith(one)
        transitions.begin(run.id)
        transitions.complete(run.id, "fake-one", 1) { EndOfDayStepOutcome.DONE }
        transitions.begin(run.id).shouldBeNull()
        val version = versionOf(run.id)

        transitions.begin(run.id).shouldBeNull()

        versionOf(run.id) shouldBe version
    }

    @Test
    fun `complete with a stale attempts value does nothing and returns false`() {
        val run = saved(farDate(300))
        val transitions = transitionsWith(one, two)
        val calls = AtomicInteger()
        val work: (EndOfDayStepContext) -> EndOfDayStepOutcome = {
            calls.incrementAndGet()
            EndOfDayStepOutcome.DONE
        }
        transitions.begin(run.id) shouldBe StepStart("fake-one", 1)

        transitions.complete(run.id, "fake-one", 2, work) shouldBe false
        transitions.complete(run.id, "fake-one", 1, work) shouldBe true
        val version = versionOf(run.id)
        transitions.complete(run.id, "fake-one", 1, work) shouldBe false
        transitions.complete(run.id, "fake-two", 0, work) shouldBe false

        calls.get() shouldBe 1
        inspect(run.id) {
            this.version shouldBe version
            statuses() shouldContainExactly listOf("fake-one" to StepStatus.COMPLETED, "fake-two" to StepStatus.PENDING)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `two concurrent completes of the same step commit once`() {
        val run = saved(farDate(400))
        val transitions = transitionsWith(one, two)
        val started = transitions.begin(run.id).shouldNotBeNull()
        val calls = AtomicInteger()
        val go = CountDownLatch(1)

        val futures =
            List(2) {
                CompletableFuture.supplyAsync {
                    go.await(LATCH_SECONDS, TimeUnit.SECONDS)
                    transitions.complete(run.id, started.name, started.attempts) {
                        calls.incrementAndGet()
                        Thread.sleep(WORK_MILLIS)
                        EndOfDayStepOutcome.DONE
                    }
                }
            }
        go.countDown()
        val results = futures.map { it.get(LATCH_SECONDS * 2, TimeUnit.SECONDS) }

        results.count { it } shouldBe 1
        calls.get() shouldBe 1
        inspect(run.id) {
            steps.first().status shouldBe StepStatus.COMPLETED
            steps.first().attempts shouldBe 1
        }
    }

    @Test
    fun `fail records STEP_FAILED with the error code and no message text`() {
        val run = saved(farDate(500))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)
        val error = BusinessRuleException(ErrorCode("TEST_RULE_BROKEN"), "Hóspede Fulano de Tal sem documento")

        transitions.fail(run.id, "fake-one", 1, EndOfDayRunTransitions.reasonOf(error)) shouldBe true

        inspect(run.id) {
            status shouldBe RunStatus.FAILED
            finishedAt.shouldNotBeNull()
            statuses() shouldContainExactly listOf("fake-one" to StepStatus.FAILED, "fake-two" to StepStatus.PENDING)
            steps.first().result shouldBe "TEST_RULE_BROKEN"
        }
        issuesOf(run.id).map { listOf(it.kind, it.stepName, it.reference, it.message) } shouldContainExactly
            listOf(listOf(EndOfDayRunTransitions.STEP_FAILED, "fake-one", "fake-one", "TEST_RULE_BROKEN"))
        auditTextOf(run.id) shouldNotContain "Fulano"
    }

    @Test
    fun `the reason of a failure is the error code or the simple class name, never the message`() {
        val domain = BusinessRuleException(ErrorCode("TEST_RULE_BROKEN"), "mensagem")

        EndOfDayRunTransitions.reasonOf(domain) shouldBe "TEST_RULE_BROKEN"
        EndOfDayRunTransitions.reasonOf(IllegalStateException("Fulano")) shouldBe "IllegalStateException"
        EndOfDayRunTransitions.reasonOf(object : RuntimeException("Fulano") {}) shouldNotContain "Fulano"
    }

    @Test
    fun `fail with a reason longer than a step result still fails the step and the run`() {
        val run = saved(farDate(550))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)

        transitions.fail(run.id, "fake-one", 1, "R".repeat(LONG_REASON)) shouldBe true

        inspect(run.id) {
            status shouldBe RunStatus.FAILED
            steps.first().status shouldBe StepStatus.FAILED
            steps.first().result shouldBe "R".repeat(RESULT_LENGTH)
        }
        issuesOf(run.id).single().message shouldBe "R".repeat(RESULT_LENGTH)
    }

    @Test
    fun `issues raised by the work are recorded with the step name`() {
        val run = saved(farDate(600))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)

        transitions.complete(run.id, "fake-one", 1) {
            it.issues.raise("TEST_PENDING", "item-1", "Item pendente.")
            EndOfDayStepOutcome.DONE
        } shouldBe true

        issuesOf(run.id).map { listOf(it.kind, it.stepName, it.reference, it.message) } shouldContainExactly
            listOf(listOf("TEST_PENDING", "fake-one", "item-1", "Item pendente."))
    }

    @Test
    fun `an exception from the work undoes the issues it raised and leaves the step running`() {
        val run = saved(farDate(650))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)

        shouldThrow<IllegalStateException> {
            transitions.complete(run.id, "fake-one", 1) {
                it.issues.raise("TEST_PENDING", "item-1", "Item pendente.")
                error("falhou")
            }
        }

        issuesOf(run.id).shouldBeEmpty()
        inspect(run.id) {
            status shouldBe RunStatus.RUNNING
            steps.first().status shouldBe StepStatus.RUNNING
            steps.first().attempts shouldBe 1
        }
    }

    @Test
    fun `a checked exception from the work undoes the complete like any other`() {
        val run = saved(farDate(660))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id)
        val audited = auditActionsOf(run.id)

        shouldThrow<IOException> {
            transitions.complete(run.id, "fake-one", 1) {
                it.issues.raise("TEST_PENDING", "item-1", "Item pendente.")
                throw IOException("x")
            }
        }

        issuesOf(run.id).shouldBeEmpty()
        auditActionsOf(run.id) shouldContainExactly audited
        inspect(run.id) {
            status shouldBe RunStatus.RUNNING
            steps.first().status shouldBe StepStatus.RUNNING
            steps.first().attempts shouldBe 1
        }
        transitions.complete(run.id, "fake-one", 1) { EndOfDayStepOutcome.DONE } shouldBe true
        inspect(run.id) { steps.first().status shouldBe StepStatus.COMPLETED }
    }

    @Test
    fun `a fail with the attempts of an executor that was overtaken does nothing and returns false`() {
        val run = saved(farDate(670))
        val transitions = transitionsWith(one, two)
        transitions.begin(run.id) shouldBe StepStart("fake-one", 1)
        shouldThrow<IllegalStateException> { transitions.complete(run.id, "fake-one", 1) { error("queda") } }
        transitions.begin(run.id) shouldBe StepStart("fake-one", 2)
        val version = versionOf(run.id)
        val audited = auditActionsOf(run.id)

        transitions.fail(run.id, "fake-one", 1, "TEST_RULE_BROKEN") shouldBe false

        versionOf(run.id) shouldBe version
        auditActionsOf(run.id) shouldContainExactly audited
        issuesOf(run.id).shouldBeEmpty()
        inspect(run.id) {
            status shouldBe RunStatus.RUNNING
            steps.first().status shouldBe StepStatus.RUNNING
            steps.first().attempts shouldBe 2
        }
        transitions.complete(run.id, "fake-one", 2) { EndOfDayStepOutcome.DONE } shouldBe true
        inspect(run.id) { steps.first().status shouldBe StepStatus.COMPLETED }
    }

    @Test
    fun `the transitions are audited`() {
        val run = saved(farDate(700))
        val transitions = transitionsWith(one, two)

        transitions.begin(run.id)
        transitions.complete(run.id, "fake-one", 1) { EndOfDayStepOutcome.DONE }
        transitions.begin(run.id)
        transitions.fail(run.id, "fake-two", 1, "TEST_RULE_BROKEN")

        val entries =
            jdbc
                .sql(
                    "SELECT action, entity_type, user_id FROM audit.audit_entry " +
                        "WHERE entity_id = :id ORDER BY occurred_at, id",
                ).param("id", run.id.value.toString())
                .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getObject(3)) }
                .list()
        entries.map { it.first to it.second } shouldContainExactly
            listOf(
                "endofday.run-step.begin" to "endofday.run",
                "endofday.run-step.complete" to "endofday.run",
                "endofday.run-step.begin" to "endofday.run",
                "endofday.run-step.fail" to "endofday.run",
            )
        entries.map { it.third }.toSet() shouldBe setOf(null)
        val issueId = issuesOf(run.id).single().id.toString()
        jdbc
            .sql("SELECT action FROM audit.audit_entry WHERE entity_type = 'endofday.run-issue' AND entity_id = :id")
            .param("id", issueId)
            .query(String::class.java)
            .list() shouldContainExactly listOf("endofday.run-step.fail")
    }

    @Test
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // o nome do teste é o do plano, literal
    fun `begin reconciles an unregistered step into SKIPPED with a STEP_UNREGISTERED issue and starts the next registered one`() {
        val run = saved(farDate(800), listOf(StepSlot("fake-gone", 5), StepSlot(two.name, two.position)))
        val transitions = transitionsWith(one, two)

        transitions.begin(run.id) shouldBe StepStart("fake-one", 1)

        inspect(run.id) {
            statuses() shouldContainExactly
                listOf(
                    "fake-gone" to StepStatus.SKIPPED,
                    "fake-one" to StepStatus.RUNNING,
                    "fake-two" to StepStatus.PENDING,
                )
            steps.first().result shouldBe EndOfDayRun.UNREGISTERED
        }
        issuesOf(run.id).map { Triple(it.kind, it.stepName, it.reference) } shouldContainExactly
            listOf(Triple(EndOfDayRunTransitions.STEP_UNREGISTERED, "fake-gone", "fake-gone"))
    }

    private fun auditActionsOf(id: EndOfDayRunId): List<String> =
        jdbc
            .sql("SELECT action FROM audit.audit_entry WHERE entity_id = :id ORDER BY occurred_at, id")
            .param("id", id.value.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun auditTextOf(id: EndOfDayRunId): String =
        jdbc
            .sql(
                "SELECT COALESCE(CAST(before_state AS text), '') || COALESCE(CAST(after_state AS text), '') " +
                    "FROM audit.audit_entry WHERE entity_id = :id OR CAST(after_state AS text) LIKE :like",
            ).param("id", id.value.toString())
            .param("like", "%${id.value}%")
            .query(String::class.java)
            .list()
            .joinToString("\n")

    private companion object {
        const val FAR_YEARS = 60L
        const val LATCH_SECONDS = 5L
        const val WORK_MILLIS = 300L
        const val LONG_REASON = 600
        const val RESULT_LENGTH = 200
    }
}

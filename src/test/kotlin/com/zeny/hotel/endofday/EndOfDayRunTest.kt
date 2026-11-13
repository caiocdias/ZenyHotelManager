package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.BeginResult
import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.domain.StepStatus
import com.zeny.hotel.shared.UserId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** O agregado da execução do fechamento (M6 E10, Task 3): etapas, transições e reconciliação com o registro. */
class EndOfDayRunTest {
    private val date = LocalDate.of(2026, 11, 12)
    private val t0 = Instant.parse("2026-11-13T03:00:00Z")
    private val slots = listOf(StepSlot("one", 10), StepSlot("two", 20), StepSlot("three", 30))

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private fun run(steps: List<StepSlot> = slots) =
        EndOfDayRun.start(date, RunTrigger.MANUAL, UserId(UUID.randomUUID()), steps, t0)

    private fun EndOfDayRun.statuses() = steps.map { it.name to it.status }

    private fun EndOfDayRun.step(name: String) = steps.single { it.name == name }

    @Test
    fun `start creates pending steps in position order`() {
        val run = run(listOf(StepSlot("three", 30), StepSlot("one", 10), StepSlot("two", 20)))

        run.status shouldBe RunStatus.RUNNING
        run.businessDate shouldBe date
        run.trigger shouldBe RunTrigger.MANUAL
        run.startedAt shouldBe t0
        run.updatedAt shouldBe t0
        run.finishedAt.shouldBeNull()
        run.steps.map { it.name to it.position } shouldContainExactly listOf("one" to 10, "two" to 20, "three" to 30)
        run.steps.forEach {
            it.status shouldBe StepStatus.PENDING
            it.attempts shouldBe 0
            it.startedAt.shouldBeNull()
            it.finishedAt.shouldBeNull()
            it.result.shouldBeNull()
        }
    }

    @Test
    fun `completing the last step completes the run`() {
        val run = run()
        slots.forEachIndexed { index, slot ->
            run.beginNext(slots, at(index * 10L + 1)).started shouldBe StepStart(slot.name, 1)
            run.status shouldBe RunStatus.RUNNING
            run.completeStep(slot.name, "DONE", at(index * 10L + 2))
        }

        run.status shouldBe RunStatus.COMPLETED
        run.finishedAt shouldBe at(22)
        run.steps.forEach {
            it.status shouldBe StepStatus.COMPLETED
            it.result shouldBe "DONE"
            it.finishedAt.shouldNotBeNull()
        }
        run.step("one").startedAt shouldBe at(1)
        run.step("one").finishedAt shouldBe at(2)
    }

    @Test
    fun `fail marks step and run failed`() {
        val run = run()
        run.beginNext(slots, at(1))

        run.failStep("one", "IllegalStateException", at(2))

        run.status shouldBe RunStatus.FAILED
        run.finishedAt shouldBe at(2)
        run.step("one").status shouldBe StepStatus.FAILED
        run.step("one").result shouldBe "IllegalStateException"
        run.step("one").finishedAt shouldBe at(2)
        run.step("two").status shouldBe StepStatus.PENDING
    }

    @Test
    fun `resume from failed reopens the failed step and keeps completed ones`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))
        run.beginNext(slots, at(3))
        run.failStep("two", "BOOM", at(4))

        run.resume(at(5))

        run.status shouldBe RunStatus.RUNNING
        run.finishedAt.shouldBeNull()
        run.statuses() shouldContainExactly
            listOf("one" to StepStatus.COMPLETED, "two" to StepStatus.PENDING, "three" to StepStatus.PENDING)
        run.step("one").result shouldBe "DONE"
        run.step("two").attempts shouldBe 1
        run.step("two").finishedAt.shouldBeNull()
        run.step("two").result.shouldBeNull()
    }

    @Test
    fun `resume from running or completed is refused`() {
        val running = run()
        shouldThrow<IllegalStateException> { running.resume(at(1)) }

        val completed = run(listOf(StepSlot("one", 10)))
        completed.beginNext(listOf(StepSlot("one", 10)), at(1))
        completed.completeStep("one", "DONE", at(2))
        completed.status shouldBe RunStatus.COMPLETED
        shouldThrow<IllegalStateException> { completed.resume(at(3)) }
    }

    @Test
    fun `start refuses empty list, duplicated names or positions`() {
        shouldThrow<IllegalArgumentException> { run(emptyList()) }
        shouldThrow<IllegalArgumentException> { run(listOf(StepSlot("one", 10), StepSlot("one", 20))) }
        shouldThrow<IllegalArgumentException> { run(listOf(StepSlot("one", 10), StepSlot("two", 10))) }
    }

    @Test
    fun `beginStep counts attempts`() {
        val run = run()
        run.beginNext(slots, at(1)).started shouldBe StepStart("one", 1)
        run.step("one").status shouldBe StepStatus.RUNNING
        run.step("one").startedAt shouldBe at(1)
        run.failStep("one", "BOOM", at(2))
        run.resume(at(3))

        run.beginNext(slots, at(4)).started shouldBe StepStart("one", 2)

        run.step("one").attempts shouldBe 2
        run.step("one").startedAt shouldBe at(4)
    }

    @Test
    fun `reconcile skips unregistered pending steps and adds new registered steps after the last completed`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))
        val registered = listOf(StepSlot("one", 10), StepSlot("three", 30), StepSlot("four", 25))

        val skipped = run.reconcile(registered, at(3))

        skipped shouldContainExactly listOf("two")
        run.steps.map { Triple(it.name, it.position, it.status) } shouldContainExactly
            listOf(
                Triple("one", 10, StepStatus.COMPLETED),
                Triple("two", 20, StepStatus.SKIPPED),
                Triple("four", 25, StepStatus.PENDING),
                Triple("three", 30, StepStatus.PENDING),
            )
        run.step("two").result shouldBe "UNREGISTERED"
        run.step("two").finishedAt shouldBe at(3)
        run.step("four").attempts shouldBe 0
    }

    @Test
    fun `reconcile ignores a new step positioned before the last completed`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))
        run.beginNext(slots, at(3))
        run.completeStep("two", "DONE", at(4))

        val skipped = run.reconcile(slots + StepSlot("early", 15), at(5))

        skipped.shouldBeEmpty()
        run.steps.map { it.name } shouldContainExactly listOf("one", "two", "three")
    }

    @Test
    fun `reconcile ignores a new step whose position is already taken in the run`() {
        val run = run()

        val skipped = run.reconcile(listOf(StepSlot("one", 10), StepSlot("renamed", 20), StepSlot("three", 30)), at(1))

        skipped shouldContainExactly listOf("two")
        run.steps.map { it.name to it.status } shouldContainExactly
            listOf("one" to StepStatus.PENDING, "two" to StepStatus.SKIPPED, "three" to StepStatus.PENDING)
    }

    @Test
    fun `reconcile only runs while the run is running`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.failStep("one", "BOOM", at(2))

        shouldThrow<IllegalStateException> { run.reconcile(slots, at(3)) }
    }

    @Test
    fun `beginNext after reconcile starts a new step positioned before the failed one first`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))
        run.beginNext(slots, at(3))
        run.failStep("two", "BOOM", at(4))
        run.resume(at(5))

        val result = run.beginNext(slots + StepSlot("fix", 15), at(6))

        result shouldBe BeginResult(StepStart("fix", 1), emptyList())
        run.step("fix").status shouldBe StepStatus.RUNNING
        run.step("two").status shouldBe StepStatus.PENDING
    }

    @Test
    fun `beginNext never starts a skipped step and completes the run when nothing is left`() {
        val two = listOf(StepSlot("one", 10), StepSlot("gone", 20))
        val run = run(two)
        run.beginNext(two, at(1))
        run.completeStep("one", "DONE", at(2))
        run.beginNext(two, at(3))
        run.failStep("gone", "BOOM", at(4))
        run.resume(at(5))

        val result = run.beginNext(listOf(StepSlot("one", 10)), at(6))

        result.started.shouldBeNull()
        result.skipped shouldContainExactly listOf("gone")
        run.status shouldBe RunStatus.COMPLETED
        run.finishedAt shouldBe at(6)
        run.step("gone").status shouldBe StepStatus.SKIPPED
        run.step("gone").attempts shouldBe 1
    }

    @Test
    fun `completing or failing a step that is not running is refused`() {
        val run = run()
        shouldThrow<IllegalStateException> { run.completeStep("one", "DONE", at(1)) }
        shouldThrow<IllegalStateException> { run.failStep("one", "BOOM", at(1)) }
        shouldThrow<IllegalArgumentException> { run.completeStep("nope", "DONE", at(1)) }
    }

    @Test
    fun `every transition changes updatedAt`() {
        val run = run()
        val seen = mutableListOf(run.updatedAt)

        fun record() {
            run.updatedAt shouldNotBe seen.last()
            run.updatedAt shouldBeGreaterThan seen.last()
            seen += run.updatedAt
        }

        val withoutThree = slots.dropLast(1)

        run.beginNext(slots, at(1)).also { record() }
        run.failStep("one", "BOOM", at(2)).also { record() }
        run.resume(at(3)).also { record() }
        run.reconcile(withoutThree, at(4)) shouldContainExactly listOf("three")
        record()
        run.beginNext(withoutThree, at(5)).also { record() }
        run.completeStep("one", "DONE", at(6)).also { record() }
        // Mesmo instante da transição anterior (relógio parado): ainda assim muda.
        run.beginNext(withoutThree, at(6)).also { record() }
        run.completeStep("two", "DONE", at(6)).also { record() }
        run.status shouldBe RunStatus.COMPLETED
    }

    @Test
    fun `an issue requires an upper snake kind and bounded texts`() {
        val runId = run().id
        val issue = EndOfDayIssue.open(runId, "two", "STEP_FAILED", "two", "BOOM", t0)

        issue.runId shouldBe runId
        issue.stepName shouldBe "two"
        issue.kind shouldBe "STEP_FAILED"
        issue.reference shouldBe "two"
        issue.message shouldBe "BOOM"
        issue.resolved shouldBe false
        issue.createdAt shouldBe t0
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "step_failed", null, "x", t0) }
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "A".repeat(61), null, "x", t0) }
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "K", "r".repeat(121), "x", t0) }
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "K", null, "m".repeat(501), t0) }
    }
}

package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.RunStatus
import com.zeny.hotel.endofday.domain.RunTrigger
import com.zeny.hotel.endofday.domain.StepSlot
import com.zeny.hotel.endofday.domain.StepStart
import com.zeny.hotel.endofday.domain.StepStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Ataques ao agregado da execução do fechamento (M6 E10, Task 3): transições fora de ordem, bordas e limites. */
class EndOfDayRunAttackTest {
    private val date = LocalDate.of(2090, 1, 1)
    private val t0 = Instant.parse("2090-01-02T03:00:00Z")
    private val slots = listOf(StepSlot("one", 10), StepSlot("two", 20), StepSlot("three", 30))

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private fun run(steps: List<StepSlot> = slots) = EndOfDayRun.start(date, RunTrigger.AUTOMATIC, null, steps, t0)

    private fun EndOfDayRun.step(name: String) = steps.single { it.name == name }

    private fun EndOfDayRun.snapshot() =
        listOf(status, finishedAt, updatedAt) + steps.map { listOf(it.status, it.result, it.attempts, it.finishedAt) }

    private fun failedRun(): EndOfDayRun =
        run().also {
            it.beginNext(slots, at(1))
            it.failStep("one", "BOOM", at(2))
        }

    private fun completedRun(): EndOfDayRun {
        val single = listOf(StepSlot("one", 10))
        return run(single).also {
            it.beginNext(single, at(1))
            it.completeStep("one", "DONE", at(2))
        }
    }

    @Test
    fun `completing a pending step is refused and changes nothing`() {
        val run = run()
        val before = run.snapshot()

        shouldThrow<IllegalStateException> { run.completeStep("two", "DONE", at(1)) }

        run.snapshot() shouldBe before
    }

    @Test
    fun `completing an already completed step is refused and keeps the first result`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))

        shouldThrow<IllegalStateException> { run.completeStep("one", "OTHER", at(3)) }

        run.step("one").result shouldBe "DONE"
        run.step("one").finishedAt shouldBe at(2)
    }

    @Test
    fun `completing or failing a step of a failed run is refused`() {
        val run = failedRun()
        val before = run.snapshot()

        shouldThrow<IllegalStateException> { run.completeStep("one", "DONE", at(3)) }
        shouldThrow<IllegalStateException> { run.failStep("one", "AGAIN", at(3)) }

        run.snapshot() shouldBe before
    }

    @Test
    fun `failing a completed run is refused and the run stays completed`() {
        val run = completedRun()
        val before = run.snapshot()

        shouldThrow<IllegalStateException> { run.failStep("one", "BOOM", at(3)) }
        shouldThrow<IllegalStateException> { run.completeStep("one", "DONE", at(3)) }

        run.status shouldBe RunStatus.COMPLETED
        run.snapshot() shouldBe before
    }

    @Test
    fun `beginNext on a failed or completed run is refused`() {
        val failed = failedRun()
        val failedBefore = failed.snapshot()
        shouldThrow<IllegalStateException> { failed.beginNext(slots, at(3)) }
        failed.snapshot() shouldBe failedBefore

        val completed = completedRun()
        val completedBefore = completed.snapshot()
        shouldThrow<IllegalStateException> { completed.beginNext(listOf(StepSlot("one", 10)), at(3)) }
        completed.snapshot() shouldBe completedBefore
    }

    @Test
    fun `resume twice is refused`() {
        val run = failedRun()
        run.resume(at(3))

        shouldThrow<IllegalStateException> { run.resume(at(4)) }
    }

    @Test
    fun `completing or failing an unknown step name is refused and changes nothing`() {
        val run = run()
        run.beginNext(slots, at(1))
        val before = run.snapshot()

        shouldThrow<IllegalArgumentException> { run.completeStep("nope", "DONE", at(2)) }
        shouldThrow<IllegalArgumentException> { run.failStep("nope", "BOOM", at(2)) }

        run.snapshot() shouldBe before
    }

    @Test
    fun `a step result of 200 characters is accepted and 201 is refused without changing the step`() {
        val run = run()
        run.beginNext(slots, at(1))

        shouldThrow<IllegalArgumentException> { run.completeStep("one", "R".repeat(201), at(2)) }
        run.step("one").status shouldBe StepStatus.RUNNING
        run.status shouldBe RunStatus.RUNNING

        run.completeStep("one", "R".repeat(200), at(3))
        run.step("one").result shouldBe "R".repeat(200)
    }

    @Test
    fun `a failure reason of 201 characters is refused without changing the step`() {
        val run = run()
        run.beginNext(slots, at(1))

        shouldThrow<IllegalArgumentException> { run.failStep("one", "R".repeat(201), at(2)) }

        run.step("one").status shouldBe StepStatus.RUNNING
        run.status shouldBe RunStatus.RUNNING
    }

    @Test
    fun `reconcile with an empty registry skips every unfinished step and beginNext then completes the run`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))

        run.reconcile(emptyList(), at(3)) shouldContainExactly listOf("two", "three")

        run.step("one").status shouldBe StepStatus.COMPLETED
        run.step("two").status shouldBe StepStatus.SKIPPED
        run.step("three").status shouldBe StepStatus.SKIPPED
        run.status shouldBe RunStatus.RUNNING
        val result = run.beginNext(emptyList(), at(4))
        result.started.shouldBeNull()
        result.skipped.shouldBeEmpty()
        run.status shouldBe RunStatus.COMPLETED
        run.finishedAt shouldBe at(4)
    }

    @Test
    fun `a completed step that left the registry is never skipped`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))

        val skipped = run.reconcile(listOf(StepSlot("two", 20), StepSlot("three", 30)), at(3))

        skipped.shouldBeEmpty()
        run.step("one").status shouldBe StepStatus.COMPLETED
        run.step("one").result shouldBe "DONE"
        run.beginNext(listOf(StepSlot("two", 20), StepSlot("three", 30)), at(4)).started shouldBe StepStart("two", 1)
    }

    @Test
    fun `reconcile adds two new steps in position order after the last completed`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))

        val skipped = run.reconcile(slots + StepSlot("late", 50) + StepSlot("mid", 25), at(3))

        skipped.shouldBeEmpty()
        run.steps.map { it.name to it.status } shouldContainExactly
            listOf(
                "one" to StepStatus.COMPLETED,
                "two" to StepStatus.PENDING,
                "mid" to StepStatus.PENDING,
                "three" to StepStatus.PENDING,
                "late" to StepStatus.PENDING,
            )
    }

    @Test
    fun `a new step at the position of the last completed step is ignored`() {
        val run = run()
        run.beginNext(slots, at(1))
        run.completeStep("one", "DONE", at(2))

        run.reconcile(slots + StepSlot("twin", 10), at(3))

        run.steps.map { it.name } shouldContainExactly listOf("one", "two", "three")
    }

    @Test
    fun `reconcile without any change leaves the run untouched and is idempotent`() {
        val run = run()
        val before = run.snapshot()

        run.reconcile(slots, at(1)).shouldBeEmpty()
        run.snapshot() shouldBe before

        run.reconcile(slots + StepSlot("new", 40), at(2)).shouldBeEmpty()
        val afterAdd = run.snapshot()
        run.reconcile(slots + StepSlot("new", 40), at(3)).shouldBeEmpty()
        run.snapshot() shouldBe afterAdd
    }

    @Test
    fun `reconcile never reports the same skipped step twice`() {
        val run = run()
        val registered = listOf(StepSlot("one", 10), StepSlot("three", 30))

        run.reconcile(registered, at(1)) shouldContainExactly listOf("two")
        run.reconcile(registered, at(2)).shouldBeEmpty()
        run.beginNext(registered, at(3)).skipped.shouldBeEmpty()
    }

    @Test
    fun `completing the last open step completes the run even when another step was skipped`() {
        val run = run()
        val registered = listOf(StepSlot("one", 10), StepSlot("three", 30))
        run.beginNext(registered, at(1)).skipped shouldContainExactly listOf("two")
        run.completeStep("one", "DONE", at(2))
        run.beginNext(registered, at(3)).started shouldBe StepStart("three", 1)

        run.completeStep("three", "DONE", at(4))

        run.status shouldBe RunStatus.COMPLETED
        run.finishedAt shouldBe at(4)
        run.step("two").status shouldBe StepStatus.SKIPPED
    }

    @Test
    fun `attempts accumulate across several failures and resumes`() {
        val run = run()
        repeat(3) { round ->
            val base = round * 10L
            run.beginNext(slots, at(base + 1)).started shouldBe StepStart("one", round + 1)
            run.failStep("one", "BOOM", at(base + 2))
            run.resume(at(base + 3))
        }

        run.step("one").attempts shouldBe 3
        run.step("two").attempts shouldBe 0
    }

    @Test
    fun `updatedAt keeps growing when the clock stands still or goes back`() {
        val run = run()
        val seen = mutableListOf(run.updatedAt)

        fun record() {
            run.updatedAt shouldBeGreaterThan seen.last()
            seen += run.updatedAt
        }

        run.beginNext(slots, t0).also { record() }
        run.failStep("one", "BOOM", t0.minusSeconds(60)).also { record() }
        run.resume(t0.minusSeconds(120)).also { record() }
        run.beginNext(slots, t0).also { record() }
    }

    @Test
    fun `a failed run keeps the steps that were not started pending`() {
        val run = failedRun()

        run.step("two").status shouldBe StepStatus.PENDING
        run.step("three").status shouldBe StepStatus.PENDING
        run.step("two").attempts shouldBe 0
    }

    @Test
    fun `a new run has version zero and no finish`() {
        val run = run()

        run.version shouldBe 0L
        run.finishedAt.shouldBeNull()
        run.status shouldBe RunStatus.RUNNING
    }

    @Test
    fun `issue limits accept the boundary values and refuse one past them`() {
        val runId = run().id

        val max = EndOfDayIssue.open(runId, null, "K".repeat(60), "r".repeat(120), "m".repeat(500), t0)
        max.kind.length shouldBe 60
        max.reference?.length shouldBe 120
        max.message.length shouldBe 500
        EndOfDayIssue.open(runId, null, "K", null, "", t0).reference.shouldBeNull()
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "K".repeat(61), null, "x", t0) }
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "K", "r".repeat(121), "x", t0) }
        shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, "K", null, "m".repeat(501), t0) }
    }

    @Test
    fun `issue kind refuses empty, lowercase, leading digit or underscore, dash, space and trailing newline`() {
        val runId = run().id

        listOf("", "step_failed", "1STEP", "_STEP", "STEP-FAILED", "STEP FAILED", "STEP_FAILED\n", "STEP\nFAILED")
            .forEach { kind ->
                withClue("kind=[$kind]") {
                    shouldThrow<IllegalArgumentException> { EndOfDayIssue.open(runId, null, kind, null, "x", t0) }
                }
            }
        EndOfDayIssue.open(runId, null, "A1_B2", null, "x", t0).kind shouldBe "A1_B2"
    }
}

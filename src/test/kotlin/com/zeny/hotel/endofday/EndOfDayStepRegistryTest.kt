package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import com.zeny.hotel.endofday.domain.StepSlot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/** O registro ordenado das etapas do fechamento (M6 E10, Task 4): o boot falha com registro inválido. */
class EndOfDayStepRegistryTest {
    private class Step(
        override val name: String,
        override val position: Int,
    ) : EndOfDayStepHandler {
        override fun execute(context: EndOfDayStepContext) = EndOfDayStepOutcome.DONE
    }

    @Test
    fun `orders the steps by position`() {
        val three = Step("fake-three", 300)
        val one = Step("fake-one", 100)
        val two = Step("fake-two", 200)

        val registry = EndOfDayStepRegistry(listOf(three, one, two))

        registry.steps shouldContainExactly listOf(one, two, three)
        registry.slots shouldContainExactly
            listOf(StepSlot("fake-one", 100), StepSlot("fake-two", 200), StepSlot("fake-three", 300))
        registry.handlerOrNull("fake-two") shouldBeSameInstanceAs two
        registry.handlerOrNull("fake-gone").shouldBeNull()
    }

    @Test
    fun `an empty registry has no steps`() {
        val registry = EndOfDayStepRegistry(emptyList())

        registry.steps.shouldBeEmpty()
        registry.slots.shouldBeEmpty()
    }

    @Test
    fun `refuses a repeated name`() {
        shouldThrow<IllegalStateException> {
            EndOfDayStepRegistry(listOf(Step("fake-one", 100), Step("fake-one", 200)))
        }
    }

    @Test
    fun `refuses a repeated position`() {
        shouldThrow<IllegalStateException> {
            EndOfDayStepRegistry(listOf(Step("fake-one", 100), Step("fake-two", 100)))
        }
    }

    @Test
    fun `refuses a name outside the pattern`() {
        val invalid = listOf("", "Fake", "1-fake", "-fake", "fake_one", "fake one", "fake.one", "f".repeat(61))

        invalid.forEach { name ->
            shouldThrow<IllegalStateException> { EndOfDayStepRegistry(listOf(Step(name, 100))) }
        }
        EndOfDayStepRegistry(listOf(Step("f".repeat(60), 100))).slots.single().name shouldBe "f".repeat(60)
        EndOfDayStepRegistry(listOf(Step("a", 100))).slots.single().name shouldBe "a"
    }

    @Test
    fun `refuses a position that is not positive`() {
        shouldThrow<IllegalStateException> { EndOfDayStepRegistry(listOf(Step("fake-one", 0))) }
        shouldThrow<IllegalStateException> { EndOfDayStepRegistry(listOf(Step("fake-one", -100))) }
    }
}

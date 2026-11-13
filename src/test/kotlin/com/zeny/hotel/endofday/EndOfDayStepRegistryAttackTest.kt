package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayStepContext
import com.zeny.hotel.endofday.application.EndOfDayStepHandler
import com.zeny.hotel.endofday.application.EndOfDayStepOutcome
import com.zeny.hotel.endofday.application.EndOfDayStepRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Ataques ao registro das etapas (M6 E10, Task 4), pelo testador: bordas do padrão do nome e da posição. */
class EndOfDayStepRegistryAttackTest {
    private class Step(
        override val name: String,
        override val position: Int,
    ) : EndOfDayStepHandler {
        override fun execute(context: EndOfDayStepContext) = EndOfDayStepOutcome.DONE
    }

    @Test
    fun `refuses names that only a partial or lenient match would let through`() {
        val invalid =
            listOf(
                "fake-one\n",
                "\nfake-one",
                " fake-one",
                "fake-one ",
                "fakeé",
                "fakе",
                "fake١",
                "FAKE",
                "fake/one",
                "fake:one",
                "-",
                "9",
                "f".repeat(61),
            )

        invalid.forEach { name ->
            shouldThrow<IllegalStateException> { EndOfDayStepRegistry(listOf(Step(name, 100))) }
        }
    }

    @Test
    fun `accepts a trailing hyphen and digits after the first letter`() {
        val registry = EndOfDayStepRegistry(listOf(Step("a-", 100), Step("a1-2-3", 200)))

        registry.slots.map { it.name } shouldContainExactly listOf("a-", "a1-2-3")
    }

    @Test
    fun `refuses a repeated name even when one of the steps is also invalid in position or the order differs`() {
        shouldThrow<IllegalStateException> {
            EndOfDayStepRegistry(listOf(Step("fake-two", 200), Step("fake-one", 100), Step("fake-two", 300)))
        }
        shouldThrow<IllegalStateException> {
            EndOfDayStepRegistry(listOf(Step("fake-one", 100), Step("fake-two", 100), Step("fake-three", 200)))
        }
    }

    @Test
    fun `position bounds are the first positive value up to Int MAX`() {
        val registry = EndOfDayStepRegistry(listOf(Step("fake-max", Int.MAX_VALUE), Step("fake-min", 1)))

        registry.steps.map { it.position } shouldContainExactly listOf(1, Int.MAX_VALUE)
        shouldThrow<IllegalStateException> { EndOfDayStepRegistry(listOf(Step("fake-low", Int.MIN_VALUE))) }
    }

    @Test
    fun `lookup by name is exact and case sensitive`() {
        val registry = EndOfDayStepRegistry(listOf(Step("fake-one", 100)))

        registry.handlerOrNull("fake-one").shouldNotBeNullAndNamed("fake-one")
        registry.handlerOrNull("FAKE-ONE").shouldBeNull()
        registry.handlerOrNull("fake-one ").shouldBeNull()
        registry.handlerOrNull("").shouldBeNull()
    }

    @Test
    fun `the registry does not follow later changes to the list it was built from`() {
        val source = mutableListOf<EndOfDayStepHandler>(Step("fake-one", 100))
        val registry = EndOfDayStepRegistry(source)

        source += Step("fake-two", 200)

        registry.steps.size shouldBe 1
        registry.slots.size shouldBe 1
        registry.handlerOrNull("fake-two").shouldBeNull()
    }

    private fun EndOfDayStepHandler?.shouldNotBeNullAndNamed(name: String) {
        (this?.name) shouldBe name
    }
}

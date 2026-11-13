package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayTriggerProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/** A validação no boot da configuração do gatilho automático do fechamento (M6 E10, Task 12, correção 1). */
class EndOfDayTriggerPropertiesTest {
    private val minute = Duration.ofMinutes(1)

    @Test
    fun `the production values are accepted`() {
        val properties = EndOfDayTriggerProperties(minute, minute, Duration.ofHours(24))

        properties.catchUpWindow shouldBe Duration.ofHours(24)
    }

    @Test
    fun `a catch-up window up to seven days is accepted`() {
        EndOfDayTriggerProperties(minute, minute, Duration.ofDays(7)).catchUpWindow shouldBe Duration.ofDays(7)
        EndOfDayTriggerProperties(minute, minute, Duration.ofNanos(1)).catchUpWindow shouldBe Duration.ofNanos(1)
    }

    @Test
    fun `a catch-up window that is not positive or longer than seven days is refused`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(7).plusNanos(1), Duration.ofDays(30)).forEach {
            withClue(it) {
                shouldThrow<IllegalArgumentException> { EndOfDayTriggerProperties(minute, minute, it) }
                    .message shouldContain "zeny.endofday.trigger.catch-up-window"
            }
        }
    }

    @Test
    fun `an interval that is not positive is refused`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach {
            withClue(it) {
                shouldThrow<IllegalArgumentException> { EndOfDayTriggerProperties(it, minute, Duration.ofHours(24)) }
                    .message shouldContain "zeny.endofday.trigger.interval"
            }
        }
    }
}

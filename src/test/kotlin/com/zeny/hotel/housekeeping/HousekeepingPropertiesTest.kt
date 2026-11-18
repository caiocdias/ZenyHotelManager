package com.zeny.hotel.housekeeping

import com.zeny.hotel.housekeeping.application.HousekeepingConfiguration
import com.zeny.hotel.housekeeping.application.HousekeepingProperties
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Os parâmetros da governança conferidos no boot (M6 E02, Task 3): `zeny.housekeeping.pre-arrival-after-days` de 1 a 30
 * (padrão 3, D3-E02); fora disso o contexto não sobe.
 */
class HousekeepingPropertiesTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(HousekeepingConfiguration::class.java)

    @Test
    fun `without the property the pre-arrival waits 3 days`() {
        runner.run { it.getBean(HousekeepingProperties::class.java).preArrivalAfterDays shouldBe 3 }
    }

    @Test
    fun `a value inside 1 to 30 is accepted`() {
        listOf(1, 5, 30).forEach { days ->
            runner.withPropertyValues("zeny.housekeeping.pre-arrival-after-days=$days").run {
                withClue(days) { it.getBean(HousekeepingProperties::class.java).preArrivalAfterDays shouldBe days }
            }
        }
    }

    @Test
    fun `a value outside 1 to 30 refuses the start`() {
        listOf(0, 31, -1).forEach { days ->
            runner.withPropertyValues("zeny.housekeeping.pre-arrival-after-days=$days").run {
                withClue(days) { assertThat(it).hasFailed() }
            }
        }
    }
}

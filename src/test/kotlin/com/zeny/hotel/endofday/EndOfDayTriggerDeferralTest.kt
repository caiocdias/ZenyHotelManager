package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayTriggerProperties
import com.zeny.hotel.endofday.infrastructure.EndOfDayTriggerJob
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.time.Duration

/**
 * O gatilho automático do fechamento fica adiado em todo contexto de teste (M6 E10, Task 12), pelo
 * `src/test/resources/config/application.yaml`: solto, ele fecharia a data operacional do banco compartilhado dos
 * `@IntegrationTest`.
 */
@IntegrationTest
class EndOfDayTriggerDeferralTest(
    @Autowired private val environment: Environment,
    @Autowired private val holders: List<ScheduledTaskHolder>,
    @Autowired private val properties: EndOfDayTriggerProperties,
) {
    @Test
    fun `the trigger configuration is bound and validated at boot`() {
        properties shouldBe EndOfDayTriggerProperties(Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofHours(24))
    }

    @Test
    fun `the trigger is deferred in every test context`() {
        environment.getProperty("zeny.endofday.trigger.initial-delay") shouldBe "PT24H"

        val task =
            holders
                .flatMap { it.scheduledTasks }
                .map { it.task }
                .filterIsInstance<FixedDelayTask>()
                .single { it.toString().contains(EndOfDayTriggerJob::class.java.name) }
        task.initialDelayDuration shouldBe Duration.ofHours(24)
    }
}

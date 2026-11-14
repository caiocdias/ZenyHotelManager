package com.zeny.hotel.endofday

import com.zeny.hotel.endofday.application.EndOfDayTriggerProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.DefaultResourceLoader
import java.time.Duration

/**
 * A espera do gatilho pelo motor (`zeny.endofday.trigger.wait`, M6 E11, Task 17) pelo mesmo `Binder` que o Boot usa no
 * boot, sem subir contexto: o limite de 1 minuto vale também nos formatos curtos (`60s`, `1m`), e o valor de produção
 * é `PT5S` em todo profile (o adiamento dos testes não o muda).
 */
class EndOfDayTriggerWaitBindingTest {
    private fun bind(wait: String): EndOfDayTriggerProperties =
        Binder(
            MapConfigurationPropertySource(
                mapOf(
                    "zeny.endofday.trigger.interval" to "PT1M",
                    "zeny.endofday.trigger.initial-delay" to "PT1M",
                    "zeny.endofday.trigger.catch-up-window" to "PT24H",
                    "zeny.endofday.trigger.wait" to wait,
                ),
            ),
        ).bind("zeny.endofday.trigger", EndOfDayTriggerProperties::class.java).get()

    private fun Throwable.messages(): String =
        generateSequence(this) { it.cause }.joinToString(" | ") { "${it.message}" }

    @Test
    fun `a wait of exactly one minute binds in every format`() {
        listOf("PT1M", "60s", "1m", "60000").forEach {
            withClue(it) { bind(it).wait shouldBe Duration.ofMinutes(1) }
        }
        bind("PT0.000000001S").wait shouldBe Duration.ofNanos(1)
    }

    @Test
    fun `a wait over one minute or not positive fails the binding in every format`() {
        listOf("PT1M0.000000001S", "PT1M1S", "61s", "2m", "60001", "PT0S", "0s", "0", "-1s", "PT-5S").forEach {
            withClue(it) {
                shouldThrow<BindException> { bind(it) }.messages() shouldContain "zeny.endofday.trigger.wait"
            }
        }
    }

    @Test
    fun `production waits five seconds for the engine in every profile`() {
        YamlPropertySourceLoader()
            .load("application.yaml", ClassPathResource("application.yaml"))
            .mapNotNull { it.getProperty("zeny.endofday.trigger.wait")?.toString() } shouldContainExactly listOf("PT5S")
        listOf(emptyArray(), arrayOf("local"), arrayOf("prod")).forEach { profiles ->
            val environment =
                StandardEnvironment().also {
                    ConfigDataEnvironmentPostProcessor.applyTo(
                        it,
                        DefaultResourceLoader(),
                        DefaultBootstrapContext(),
                        profiles.toList(),
                    )
                }
            withClue("profiles ${profiles.toList()}") {
                environment.getProperty("zeny.endofday.trigger.wait") shouldBe "PT5S"
            }
        }
    }
}

package com.zeny.hotel.endofday

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.DefaultResourceLoader

/**
 * A configuração do gatilho automático do fechamento (M6 E10, Task 12), sem subir contexto (nem o sistema de log do
 * Boot): os padrões de produção do `application.yaml` e o adiamento do `config/application.yaml` dos testes, que vale
 * para qualquer profile, inclusive o `prod` e o de um contexto montado à mão sem profile. O ambiente é carregado pelo
 * mesmo `ConfigDataEnvironmentPostProcessor` que o Boot usa no boot.
 */
class EndOfDayTriggerConfigurationTest {
    private fun environment(vararg profiles: String): StandardEnvironment =
        StandardEnvironment().also {
            ConfigDataEnvironmentPostProcessor.applyTo(
                it,
                DefaultResourceLoader(),
                DefaultBootstrapContext(),
                profiles.toList(),
            )
        }

    /** Os valores da [key] em todos os documentos do `application.yaml` de produção, em ordem. */
    private fun productionValues(key: String): List<String> =
        YamlPropertySourceLoader()
            .load("application.yaml", ClassPathResource("application.yaml"))
            .mapNotNull { it.getProperty(key)?.toString() }

    @Test
    fun `production runs the trigger every minute from one minute after the boot with a 24-hour window`() {
        productionValues("zeny.endofday.trigger.interval") shouldContainExactly listOf("PT1M")
        productionValues("zeny.endofday.trigger.initial-delay") shouldContainExactly listOf("PT1M")
        productionValues("zeny.endofday.trigger.catch-up-window") shouldContainExactly listOf("PT24H")
    }

    @Test
    fun `the trigger is deferred for every profile and only the initial delay changes`() {
        listOf(emptyArray(), arrayOf("local"), arrayOf("prod")).forEach { profiles ->
            val environment = environment(*profiles)
            withClue("profiles ${profiles.toList()}") {
                environment.getProperty("zeny.endofday.trigger.initial-delay") shouldBe "PT24H"
                environment.getProperty("zeny.endofday.trigger.interval") shouldBe "PT1M"
                environment.getProperty("zeny.endofday.trigger.catch-up-window") shouldBe "PT24H"
            }
        }
    }
}

package com.zeny.hotel.shared

import com.zeny.hotel.shared.infrastructure.scheduling.ShedLockLogFilter
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Configuration
import java.sql.SQLException

/**
 * O filtro do ShedLock com a saída estruturada (ECS) do profile `prod` (M6 E21): o Boot reinicializa o logging ao ler o
 * `Environment` e zera o contexto do Logback, e o instalador do filtro refaz o registro depois. Aqui sobe-se uma
 * aplicação mínima (sem banco) com o profile `prod`, para provar que o filtro continua valendo no formato ECS, e que um
 * logger qualquer com exceção continua saindo. Ao fim, o logging volta à configuração do profile `local` para não
 * mudar a saída dos outros testes da JVM.
 */
@ExtendWith(OutputCaptureExtension::class)
class ShedLockLogFilterStructuredOutputTest {
    @Configuration(proxyBeanMethods = false)
    class EmptyConfiguration

    private fun run(profile: String) =
        SpringApplicationBuilder(EmptyConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .profiles(profile)
            .properties("spring.main.banner-mode=off")
            .run()
            .close()

    @AfterEach
    fun restoreLocalLogging() = run("local")

    @Test
    fun `the filter still drops the shedlock exception event under the prod ECS output`(output: CapturedOutput) {
        run("prod")
        val sentinel = "sentinela_ecs_e21"
        val failure = IllegalStateException("banco: $sentinel", SQLException("causa $sentinel"))

        LoggerFactory.getLogger(ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER).error("Unexpected exception", failure)
        LoggerFactory.getLogger("com.zeny.hotel.e21.OtherEcs").error("other ecs failure", failure)

        // Prova que a saída é mesmo a estruturada: o evento do outro logger sai em JSON ECS.
        output.all shouldContain "\"logger\":\"com.zeny.hotel.e21.OtherEcs\""
        output.all shouldContain "other ecs failure"
        output.all shouldNotContain "\"message\":\"Unexpected exception\""
        output.all shouldNotContain "logger\":\"${ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER}\""
    }

    @Test
    fun `an event without exception from the shedlock logger still shows up under the prod ECS output`(
        output: CapturedOutput,
    ) {
        run("prod")

        LoggerFactory.getLogger(ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER).error("Unlock failed after 10 attempts")

        output.all shouldContain "Unlock failed after 10 attempts"
        output.all shouldContain "\"logger\":\"${ShedLockLogFilter.SHEDLOCK_STORAGE_LOGGER}\""
    }
}

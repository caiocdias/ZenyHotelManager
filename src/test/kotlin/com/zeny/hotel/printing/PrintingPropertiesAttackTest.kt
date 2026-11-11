package com.zeny.hotel.printing

import com.zeny.hotel.printing.infrastructure.PrintingConfiguration
import com.zeny.hotel.printing.infrastructure.PrintingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

/** Ataque às propriedades `zeny.printing.*` (M5 E10, Task 3): limites, tipos inválidos e o application.yaml real. */
class PrintingPropertiesAttackTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(PrintingConfiguration::class.java)

    @Test
    fun `application yaml entrega os valores de D-E10-4, 5, 6 e 10`() {
        runner.withInitializer(ConfigDataApplicationContextInitializer()).run {
            assertThat(it).hasNotFailed()
            val p = it.getBean(PrintingProperties::class.java)
            assertThat(p).isEqualTo(PrintingProperties())
            assertThat(p.maxContentBytes).isEqualTo(5_242_880)
            assertThat(p.maxManualContentBytes).isEqualTo(716_800)
            assertThat(p.contentRetention).isEqualTo(Duration.ofHours(24))
            assertThat(p.pendingTtl).isEqualTo(Duration.ofHours(24))
            assertThat(p.ackTimeout).isEqualTo(Duration.ofMinutes(2))
            assertThat(p.maxAttempts).isEqualTo(3)
            assertThat(p.longPollMaxWait).isEqualTo(Duration.ofSeconds(30))
            assertThat(p.deliveryPollInterval).isEqualTo(Duration.ofSeconds(1))
            assertThat(p.streamHeartbeat).isEqualTo(Duration.ofSeconds(15))
            assertThat(p.streamMaxDuration).isEqualTo(Duration.ofMinutes(30))
            assertThat(p.maxConnectionsPerAgent).isEqualTo(2)
            assertThat(p.maintenanceInterval).isEqualTo(Duration.ofMinutes(1))
            assertThat(p.maintenanceInitialDelay).isEqualTo(Duration.ofMinutes(1))
        }
    }

    @Test
    fun `teto manual padrao cabe em 1 MB depois do base64`() {
        val p = PrintingProperties()
        assertThat(p.maxManualContentBytes).isLessThan(p.maxContentBytes)
        assertThat((p.maxManualContentBytes + 2) / 3 * 4).isLessThan(1_048_576)
    }

    @ParameterizedTest
    @ValueSource(strings = ["max-manual-content-bytes", "max-attempts", "max-connections-per-agent"])
    fun `numero de 1 e aceito`(name: String) {
        runner.withPropertyValues("zeny.printing.$name=1").run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `teto manual de 1 e aceito e teto geral abaixo do manual padrao recusa a partida`() {
        runner.withPropertyValues("zeny.printing.max-manual-content-bytes=1").run { assertThat(it).hasNotFailed() }
        runner.withPropertyValues("zeny.printing.max-content-bytes=716799").run { assertThat(it).hasFailed() }
    }

    @Test
    fun `teto geral de 1 com o manual padrao recusa a partida`() {
        runner.withPropertyValues("zeny.printing.max-content-bytes=1").run { assertThat(it).hasFailed() }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "content-retention", "pending-ttl", "ack-timeout", "long-poll-max-wait", "delivery-poll-interval",
            "stream-heartbeat", "stream-max-duration", "maintenance-interval", "maintenance-initial-delay",
        ],
    )
    fun `duracao de 1 milissegundo e aceita e a invalida recusa a partida`(name: String) {
        runner.withPropertyValues("zeny.printing.$name=PT0.001S").run { assertThat(it).hasNotFailed() }
        runner.withPropertyValues("zeny.printing.$name=abc").run { assertThat(it).hasFailed() }
        runner.withPropertyValues("zeny.printing.$name=PT-0.001S").run { assertThat(it).hasFailed() }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["max-content-bytes", "max-manual-content-bytes", "max-attempts", "max-connections-per-agent"],
    )
    fun `numero nao numerico ou acima de Int recusa a partida`(name: String) {
        listOf("abc", "2147483648", "").forEach { value ->
            runner.withPropertyValues("zeny.printing.$name=$value").run { assertThat(it).hasFailed() }
        }
    }

    @Test
    fun `numero no limite de Int e aceito`() {
        runner
            .withPropertyValues("zeny.printing.max-content-bytes=2147483647")
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `chave desconhecida em zeny printing nao e usada e a partida segue com os padroes`() {
        runner.withPropertyValues("zeny.printing.max-attempts=7").run {
            assertThat(it.getBean(PrintingProperties::class.java).maxAttempts).isEqualTo(7)
        }
    }

    @Test
    fun `mensagem de recusa nomeia a propriedade`() {
        runner.withPropertyValues("zeny.printing.ack-timeout=PT0S").run {
            assertThat(chain(it.startupFailure)).contains("ack-timeout")
        }
        runner.withPropertyValues("zeny.printing.max-attempts=0").run {
            assertThat(chain(it.startupFailure)).contains("max-attempts")
        }
    }

    private fun chain(error: Throwable?): String =
        generateSequence(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
}

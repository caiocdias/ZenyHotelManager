package com.zeny.hotel.printing

import com.zeny.hotel.printing.infrastructure.PrintingConfiguration
import com.zeny.hotel.printing.infrastructure.PrintingProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration

class PrintingPropertiesTest {
    private val runner =
        ApplicationContextRunner().withUserConfiguration(PrintingConfiguration::class.java)

    @Test
    fun `sem propriedades usa os valores da etapa`() {
        runner.run {
            val p = it.getBean(PrintingProperties::class.java)
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
    fun `le os valores do ambiente`() {
        runner.withPropertyValues("zeny.printing.max-attempts=5", "zeny.printing.ack-timeout=PT30S").run {
            val p = it.getBean(PrintingProperties::class.java)
            assertThat(p.maxAttempts).isEqualTo(5)
            assertThat(p.ackTimeout).isEqualTo(Duration.ofSeconds(30))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "max-content-bytes", "max-manual-content-bytes", "max-attempts", "max-connections-per-agent",
        ],
    )
    fun `numero zero ou negativo recusa a partida`(name: String) {
        listOf("0", "-1").forEach { value ->
            runner.withPropertyValues("zeny.printing.$name=$value").run {
                assertThat(it).hasFailed()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "content-retention", "pending-ttl", "ack-timeout", "long-poll-max-wait", "delivery-poll-interval",
            "stream-heartbeat", "stream-max-duration", "maintenance-interval", "maintenance-initial-delay",
        ],
    )
    fun `duracao zero ou negativa recusa a partida`(name: String) {
        listOf("PT0S", "PT-1S").forEach { value ->
            runner.withPropertyValues("zeny.printing.$name=$value").run {
                assertThat(it).hasFailed()
            }
        }
    }

    @Test
    fun `teto manual maior que o teto geral recusa a partida`() {
        runner
            .withPropertyValues("zeny.printing.max-content-bytes=1000", "zeny.printing.max-manual-content-bytes=1001")
            .run { assertThat(it).hasFailed() }
    }

    @Test
    fun `teto manual igual ao teto geral e aceito`() {
        runner
            .withPropertyValues("zeny.printing.max-content-bytes=1000", "zeny.printing.max-manual-content-bytes=1000")
            .run { assertThat(it).hasNotFailed() }
    }
}

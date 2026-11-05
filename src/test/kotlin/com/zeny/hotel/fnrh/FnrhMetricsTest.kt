package com.zeny.hotel.fnrh

import com.zeny.hotel.fnrh.application.FnrhStatus
import com.zeny.hotel.fnrh.application.FnrhStatusReader
import com.zeny.hotel.fnrh.infrastructure.FnrhMetrics
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Instant

/** Ataques do testador às métricas `zeny.fnrh.*` sem banco (M5 E07, Task 5): `NaN`, unidade e leitura que falha. */
@ExtendWith(OutputCaptureExtension::class)
class FnrhMetricsTest {
    private fun registryWith(reader: FnrhStatusReader): SimpleMeterRegistry {
        val provider = mockk<ObjectProvider<FnrhStatusReader>>()
        every { provider.getObject() } returns reader
        return SimpleMeterRegistry().also { FnrhMetrics(provider).bindTo(it) }
    }

    @Test
    fun `without any record both instants are NaN, with the unit seconds, and configured is 0`() {
        val reader = mockk<FnrhStatusReader>()
        every { reader.read() } returns FnrhStatus(false, false, FnrhEnvironment.HOMOLOGATION, null, null, null)

        val registry = registryWith(reader)

        registry
            .get("zeny.fnrh.last.success")
            .gauge()
            .value()
            .isNaN() shouldBe true
        registry
            .get("zeny.fnrh.last.failure")
            .gauge()
            .value()
            .isNaN() shouldBe true
        registry.get("zeny.fnrh.configured").gauge().value() shouldBe 0.0
        registry
            .get("zeny.fnrh.last.success")
            .gauge()
            .id.baseUnit shouldBe "seconds"
        registry
            .get("zeny.fnrh.last.failure")
            .gauge()
            .id.baseUnit shouldBe "seconds"
    }

    @Test
    fun `the gauges are epoch seconds of the status and are read again on every scrape`() {
        val reader = mockk<FnrhStatusReader>()
        val first = Instant.parse("2026-11-04T10:00:00Z")
        every { reader.read() } returns FnrhStatus(true, true, FnrhEnvironment.PRODUCTION, first, null, null)
        val registry = registryWith(reader)
        registry.get("zeny.fnrh.last.success").gauge().value() shouldBe first.epochSecond.toDouble()
        registry.get("zeny.fnrh.configured").gauge().value() shouldBe 1.0

        val second = first.plusSeconds(90)
        every { reader.read() } returns FnrhStatus(true, true, FnrhEnvironment.PRODUCTION, second, second, null)

        registry.get("zeny.fnrh.last.success").gauge().value() shouldBe second.epochSecond.toDouble()
        registry.get("zeny.fnrh.last.failure").gauge().value() shouldBe second.epochSecond.toDouble()
    }

    @Test
    fun `a read that fails gives NaN, does not throw and logs only the exception class`(output: CapturedOutput) {
        val reader = mockk<FnrhStatusReader>()
        every { reader.read() } throws IllegalStateException("senha SEGREDO-123 do banco")

        val registry = registryWith(reader)

        registry
            .get("zeny.fnrh.last.success")
            .gauge()
            .value()
            .isNaN() shouldBe true
        registry
            .get("zeny.fnrh.configured")
            .gauge()
            .value()
            .isNaN() shouldBe true
        output.all shouldNotContain "SEGREDO"
    }
}

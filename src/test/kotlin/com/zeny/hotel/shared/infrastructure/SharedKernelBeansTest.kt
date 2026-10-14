package com.zeny.hotel.shared.infrastructure

import com.zeny.hotel.shared.Money
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset

/** Os beans do kernel no contexto da aplicação: o `JsonMapper` do Boot e o relógio injetável. */
@IntegrationTest
class SharedKernelBeansTest(
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val clock: Clock,
) {
    @Test
    fun `the application JSON mapper writes money as a decimal string`() {
        jsonMapper.writeValueAsString(mapOf("total" to Money.of("123.45"))) shouldBe """{"total":"123.45"}"""
        jsonMapper.readValue("\"0.10\"", Money::class.java) shouldBe Money.of("0.10")
    }

    @Test
    fun `an injectable clock in UTC is available`() {
        clock.zone shouldBe ZoneOffset.UTC
        val before = System.currentTimeMillis()
        clock.millis() shouldBeInRange before..System.currentTimeMillis()
    }
}

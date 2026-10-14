package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Percentuais em pontos percentuais com 4 casas, como `numeric(7,4)` (ARCHITECTURE §11). */
class PercentTest {
    @Test
    fun `keeps four decimal places`() {
        Percent.of("10.5").value shouldBe BigDecimal("10.5000")
        Percent.of("10.5").toString() shouldBe "10.5000"
        Percent.of(BigDecimal("0.0001")).toString() shouldBe "0.0001"
        Percent.ZERO.toString() shouldBe "0.0000"
        Percent.HUNDRED.toString() shouldBe "100.0000"
    }

    @Test
    fun `refuses values that do not fit numeric(7,4) instead of rounding`() {
        shouldThrow<IllegalArgumentException> { Percent.of("10.12345") }
        shouldThrow<IllegalArgumentException> { Percent.of("1000") }
        shouldThrow<IllegalArgumentException> { Percent.of("-1000") }
        shouldThrow<IllegalArgumentException> { Percent.of("x") }
        Percent.of("999.9999").toString() shouldBe "999.9999"
        Percent.of("-12.5").toString() shouldBe "-12.5000"
    }

    @Test
    fun `applies to money through the single money rounding`() {
        Percent.of("10").of(Money.of("12.25")) shouldBe Money.of("1.22")
        Percent.of("33.3333").of(Money.of("300.00")) shouldBe Money.of("100.00")
        Percent.of("0.5").of(Money.of("0.01")) shouldBe Money.ZERO
        Percent.HUNDRED.of(Money.of("42.42")) shouldBe Money.of("42.42")
    }

    @Test
    fun `exposes the exact fraction`() {
        Percent.of("12.5").fraction shouldBe BigDecimal("0.125000")
        Percent.of("0.0001").fraction shouldBe BigDecimal("0.000001")
    }

    @Test
    fun `has value equality and ordering`() {
        Percent.of("10") shouldBe Percent.of("10.0000")
        Percent.of("10").hashCode() shouldBe Percent.of("10.0000").hashCode()
        Percent.of("10.0001") shouldBeGreaterThan Percent.of("10")
    }
}

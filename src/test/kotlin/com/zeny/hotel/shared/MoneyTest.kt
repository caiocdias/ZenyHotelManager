package com.zeny.hotel.shared

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.Currency
import kotlin.random.Random

/** RN-02: BRL, decimal exato com 2 casas e uma única regra de arredondamento. */
class MoneyTest {
    @Test
    fun `keeps exactly two decimal places`() {
        Money.of("10").amount shouldBe BigDecimal("10.00")
        Money.of("10.5").toString() shouldBe "10.50"
        Money.of(BigDecimal("0.1")).amount.scale() shouldBe 2
        Money.ofCents(12_345).toString() shouldBe "123.45"
        Money.ZERO.toString() shouldBe "0.00"
        Money.of("-7.1").toString() shouldBe "-7.10"
    }

    @Test
    fun `refuses values that would need rounding instead of rounding silently`() {
        shouldThrow<IllegalArgumentException> { Money.of("10.555") }
        shouldThrow<IllegalArgumentException> { Money.of(BigDecimal("0.001")) }
        shouldThrow<IllegalArgumentException> { Money.of("abc") }
        shouldThrow<IllegalArgumentException> { Money.of("1e3") }
        // Zeros à direita além da 2ª casa não mudam o valor: são aceitos.
        Money.of("10.5000") shouldBe Money.of("10.50")
    }

    @Test
    fun `accepts only BRL`() {
        Money.of("10.00", "BRL") shouldBe Money.of("10.00")
        Money.of("10.00").currency shouldBe Currency.getInstance("BRL")
        shouldThrow<IllegalArgumentException> { Money.of("10.00", "USD") }
        shouldThrow<IllegalArgumentException> { Money.of("10.00", "brl") }
    }

    @Test
    fun `the single rounding rule is half-even to cents`() {
        assertSoftly {
            Money.round(BigDecimal("10.555")) shouldBe Money.of("10.56")
            Money.round(BigDecimal("10.545")) shouldBe Money.of("10.54")
            Money.round(BigDecimal("10.5451")) shouldBe Money.of("10.55")
            Money.round(BigDecimal("10.5449")) shouldBe Money.of("10.54")
            Money.round(BigDecimal("0.005")) shouldBe Money.of("0.00")
            Money.round(BigDecimal("0.015")) shouldBe Money.of("0.02")
            Money.round(BigDecimal("-10.555")) shouldBe Money.of("-10.56")
            Money.round(BigDecimal("-0.005")) shouldBe Money.of("0.00")
            Money.round(BigDecimal("7")) shouldBe Money.of("7.00")
        }
        Money.ROUNDING_MODE shouldBe java.math.RoundingMode.HALF_EVEN
    }

    @Test
    fun `arithmetic is exact and multiplication rounds once through the single rule`() {
        (Money.of("10.10") + Money.of("0.25")) shouldBe Money.of("10.35")
        (Money.of("10.10") - Money.of("20.25")) shouldBe Money.of("-10.15")
        (-Money.of("3.50")) shouldBe Money.of("-3.50")
        (Money.of("3.33") * 3) shouldBe Money.of("9.99")
        (Money.of("10.00") * BigDecimal("0.333")) shouldBe Money.of("3.33")
        (Money.of("0.05") * BigDecimal("0.5")) shouldBe Money.of("0.02")
        (Money.of("0.07") * BigDecimal("0.5")) shouldBe Money.of("0.04")
        (Money.of("12.25") * Percent.of("10")) shouldBe Money.of("1.22")
        (Money.of("100.00") * Percent.of("12.5")) shouldBe Money.of("12.50")
        Money.of("-4.20").abs() shouldBe Money.of("4.20")
    }

    @Test
    fun `compares by value and has value equality`() {
        Money.of("10") shouldBe Money.of("10.00")
        Money.of("10").hashCode() shouldBe Money.of("10.00").hashCode()
        Money.of("10.01") shouldNotBe Money.of("10.00")
        Money.of("10.01") shouldBeGreaterThan Money.of("10.00")
        Money.of("-0.01") shouldBeLessThan Money.ZERO
        Money.of("-0.01").isNegative() shouldBe true
        Money.of("0.01").isPositive() shouldBe true
        Money.of("0.00").isZero() shouldBe true
        Money.of("-0.00") shouldBe Money.ZERO
        maxOf(Money.of("1.00"), Money.of("2.00")) shouldBe Money.of("2.00")
    }

    @Test
    fun `sums lists`() {
        listOf(Money.of("0.10"), Money.of("0.20"), Money.of("0.30")).sum() shouldBe Money.of("0.60")
        emptyList<Money>().sum() shouldBe Money.ZERO
        listOf(Money.of("1.00"), Money.of("-1.00")).sum() shouldBe Money.ZERO
    }

    @Test
    fun `splits into equal parts without losing or creating cents`() {
        Money.of("100.00").split(3) shouldContainExactly
            listOf(Money.of("33.34"), Money.of("33.33"), Money.of("33.33"))
        Money.of("0.05").split(3) shouldContainExactly listOf(Money.of("0.02"), Money.of("0.02"), Money.of("0.01"))
        Money.of("0.01").split(3) shouldContainExactly listOf(Money.of("0.01"), Money.ZERO, Money.ZERO)
        Money.of("-100.00").split(3) shouldContainExactly
            listOf(Money.of("-33.34"), Money.of("-33.33"), Money.of("-33.33"))
        Money.of("10.00").split(1) shouldContainExactly listOf(Money.of("10.00"))
        shouldThrow<IllegalArgumentException> { Money.of("10.00").split(0) }
    }

    @Test
    fun `allocates proportionally and the parts always add up to the total`() {
        Money.of("100.00").allocate(1, 2, 3) shouldContainExactly
            listOf(Money.of("16.67"), Money.of("33.33"), Money.of("50.00"))
        // 70/30 de R$ 0,99: 0,693 e 0,297; o centavo que sobra vai para a maior fração.
        Money.of("0.99").allocate(BigDecimal("70"), BigDecimal("30")) shouldContainExactly
            listOf(Money.of("0.69"), Money.of("0.30"))
        Money.of("10.00").allocate(0, 1) shouldContainExactly listOf(Money.ZERO, Money.of("10.00"))
        Money.of("0.00").allocate(1, 1) shouldContainExactly listOf(Money.ZERO, Money.ZERO)
        // Empate nas frações: o desempate é pela ordem, para o resultado ser determinístico.
        Money.of("0.02").allocate(1, 1, 1) shouldContainExactly listOf(Money.of("0.01"), Money.of("0.01"), Money.ZERO)
    }

    @Test
    fun `allocation never loses cents, including repeating decimals`() {
        val random = Random(seed = 20261013)
        repeat(2_000) { _ ->
            val total = Money.ofCents(random.nextLong(-10_000_000L, 10_000_000L))
            val ratios = List(random.nextInt(1, 12)) { BigDecimal(random.nextInt(0, 1_000)).movePointLeft(2) }
            if (ratios.all { it.signum() == 0 }) return@repeat

            val parts = total.allocate(ratios)

            withClue("total=$total ratios=$ratios parts=$parts") {
                parts.size shouldBe ratios.size
                parts.sum() shouldBe total
                val ratioSum = ratios.fold(BigDecimal.ZERO, BigDecimal::add)
                parts.zip(ratios).forEach { (part, ratio) ->
                    val exact = total.amount.multiply(ratio).divide(ratioSum, MathContext.DECIMAL128)
                    // Cada parte fica a menos de um centavo da parte exata.
                    (part.amount - exact).abs() shouldBeLessThan BigDecimal("0.01")
                }
            }
        }
    }

    @Test
    fun `allocation refuses invalid ratios`() {
        shouldThrow<IllegalArgumentException> { Money.of("10.00").allocate(emptyList()) }
        shouldThrow<IllegalArgumentException> { Money.of("10.00").allocate(0, 0) }
        shouldThrow<IllegalArgumentException> { Money.of("10.00").allocate(1, -1) }
    }

    @Test
    fun `cents round-trip without floating point`() {
        Money.of("123.45").toCents() shouldBe 12_345L
        Money.of("-0.01").toCents() shouldBe -1L
        Money.ofCents(BigInteger.valueOf(Long.MAX_VALUE)).toCents() shouldBe Long.MAX_VALUE
    }

    @Test
    fun `no money or percent API accepts or returns floating point`() {
        val floating =
            setOf(
                Double::class.javaPrimitiveType,
                Double::class.javaObjectType,
                Float::class.javaPrimitiveType,
                Float::class.javaObjectType,
            )
        listOf(Money::class.java, Money.Companion::class.java, Percent::class.java, Percent.Companion::class.java)
            .flatMap { type -> type.declaredMethods.toList() + type.declaredConstructors.toList() }
            .forEach { executable ->
                withClue(executable.toString()) {
                    executable.parameterTypes.filter { it in floating }.shouldContainExactly(emptyList())
                    if (executable is java.lang.reflect.Method) {
                        (executable.returnType in floating) shouldBe false
                    }
                }
            }
        listOf(Money::class.java, Percent::class.java).flatMap { it.declaredFields.toList() }.forEach { field ->
            withClue(field.toString()) { (field.type in floating) shouldBe false }
        }
    }
}

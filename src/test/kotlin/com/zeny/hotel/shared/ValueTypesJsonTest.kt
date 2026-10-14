package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * ARCHITECTURE §7: dinheiro trafega como string decimal (`"123.45"`), nunca como número JSON.
 * Os tipos se serializam pelas próprias anotações, então valem em qualquer `JsonMapper`,
 * inclusive no do outbox de eventos.
 */
class ValueTypesJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    data class Charge(
        val total: Money,
        val discount: Percent,
        val number: DocumentNumber,
        val cpf: Cpf,
        val cnpj: Cnpj,
    )

    private val charge =
        Charge(
            total = Money.of("123.4"),
            discount = Percent.of("12.5"),
            number = DocumentNumber("R", 2026, 123),
            cpf = Cpf.of("529.982.247-25"),
            cnpj = Cnpj.of("12.ABC.345/01DE-35"),
        )

    @Test
    fun `money is a JSON string with two decimals`() {
        mapper.writeValueAsString(Money.of("123.45")) shouldBe "\"123.45\""
        mapper.writeValueAsString(Money.of("100")) shouldBe "\"100.00\""
        mapper.writeValueAsString(Money.of("-0.5")) shouldBe "\"-0.50\""
        mapper.readValue<Money>("\"123.45\"") shouldBe Money.of("123.45")
    }

    @Test
    fun `value types round-trip inside objects`() {
        val json = mapper.writeValueAsString(charge)

        json shouldBe
            """{"total":"123.40","discount":"12.5000","number":"R-2026-000123","cpf":"52998224725",""" +
            """"cnpj":"12ABC34501DE35"}"""
        mapper.readValue<Charge>(json) shouldBe charge
    }

    @Test
    fun `money and percent refuse JSON numbers, which parsers read as floating point`() {
        shouldThrow<JacksonException> { mapper.readValue<Money>("123.45") }
        shouldThrow<JacksonException> { mapper.readValue<Money>("100") }
        shouldThrow<JacksonException> { mapper.readValue<Percent>("12.5") }
    }

    @Test
    fun `money from JSON is not rounded silently`() {
        shouldThrow<JacksonException> { mapper.readValue<Money>("\"1.234\"") }
        shouldThrow<JacksonException> { mapper.readValue<Money>("\"abc\"") }
        shouldThrow<JacksonException> { mapper.readValue<Cpf>("\"52998224724\"") }
    }
}

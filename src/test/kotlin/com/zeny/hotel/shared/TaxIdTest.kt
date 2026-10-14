package com.zeny.hotel.shared

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** CPF e CNPJ com dígito verificador, incluindo o CNPJ alfanumérico (IN RFB 2.229/2024). */
class TaxIdTest {
    @Test
    fun `accepts valid CPFs with or without punctuation`() {
        Cpf.of("529.982.247-25").digits shouldBe "52998224725"
        Cpf.of("52998224725").formatted() shouldBe "529.982.247-25"
        Cpf.of(" 168.995.350-09 ").toString() shouldBe "16899535009"
        Cpf.of("111.444.777-35") shouldBe Cpf.of("11144477735")
        Cpf.of("000.000.001-91").digits shouldBe "00000000191"
        Cpf.isValid("52998224725") shouldBe true
    }

    @Test
    fun `rejects CPFs with wrong check digits, repeated digits or wrong shape`() {
        val invalid =
            listOf(
                "52998224724", // 2º dígito errado
                "52998224735", // 1º dígito errado
                "529.982.247-2",
                "529982247250",
                "00000000000",
                "11111111111",
                "99999999999",
                "5299822472A",
                "529-982-247.25",
                "",
            )
        assertSoftly {
            invalid.forEach { value ->
                withClue(value) {
                    Cpf.isValid(value) shouldBe false
                    shouldThrow<IllegalArgumentException> { Cpf.of(value) }
                }
            }
        }
    }

    @Test
    fun `accepts valid numeric and alphanumeric CNPJs`() {
        Cnpj.of("11.222.333/0001-81").value shouldBe "11222333000181"
        Cnpj.of("11222333000181").formatted() shouldBe "11.222.333/0001-81"
        // Exemplo oficial da Receita Federal para o CNPJ alfanumérico.
        Cnpj.of("12.ABC.345/01DE-35").value shouldBe "12ABC34501DE35"
        Cnpj.of("12abc34501de35") shouldBe Cnpj.of("12ABC34501DE35")
        Cnpj.of("12ABC34501DE35").formatted() shouldBe "12.ABC.345/01DE-35"
        Cnpj.of("ABCDEFGHIJKL80").value shouldBe "ABCDEFGHIJKL80"
        Cnpj.of("00.000.000/0001-91").value shouldBe "00000000000191"
    }

    @Test
    fun `rejects CNPJs with wrong check digits, repeated characters or wrong shape`() {
        val invalid =
            listOf(
                "11222333000182",
                "11222333000171",
                "12ABC34501DE36",
                "12ABC34501DE3A", // dígitos verificadores são sempre numéricos
                "00000000000000",
                "11111111111111",
                "AAAAAAAAAAAA00",
                "1122233300018",
                "112223330001810",
                "11.222.333/0001-8",
                "11222333#00181",
                "",
            )
        assertSoftly {
            invalid.forEach { value ->
                withClue(value) {
                    Cnpj.isValid(value) shouldBe false
                    shouldThrow<IllegalArgumentException> { Cnpj.of(value) }
                }
            }
        }
    }

    @Test
    fun `has value equality`() {
        Cpf.of("52998224725") shouldNotBe Cpf.of("16899535009")
        Cpf.of("529.982.247-25").hashCode() shouldBe Cpf.of("52998224725").hashCode()
        Cnpj.of("11.222.333/0001-81").hashCode() shouldBe Cnpj.of("11222333000181").hashCode()
    }
}

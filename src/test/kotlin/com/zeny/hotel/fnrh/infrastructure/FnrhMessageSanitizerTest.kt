package com.zeny.hotel.fnrh.infrastructure

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class FnrhMessageSanitizerTest {
    @Test
    fun `a null or blank message stays absent`() {
        FnrhMessageSanitizer.sanitize(null, listOf("MARIA")).shouldBeNull()
        FnrhMessageSanitizer.sanitize("   ", listOf("MARIA")).shouldBeNull()
    }

    @Test
    fun `sent values are masked whole, ignoring case and accents`() {
        val out = FnrhMessageSanitizer.sanitize("Pessoa Maria da Luz já existe", listOf("MARIA DA LUZ"))!!

        out shouldBe "Pessoa *** já existe"
    }

    @Test
    fun `each word of a sent value is masked on its own, since the server may echo only part of it`() {
        val out =
            FnrhMessageSanitizer.sanitize(
                "Nome JOSÉ inválido; sobrenome silva repetido",
                listOf("Jose da Silva"),
            )!!

        out shouldNotContain "JOSÉ"
        out shouldNotContain "silva"
        out shouldContain "Nome"
        out shouldContain "inválido"
        out shouldContain "repetido"
    }

    @Test
    fun `values and words shorter than 3 characters are not masked`() {
        FnrhMessageSanitizer.sanitize("de da do ok", listOf("de", "ok", "Ana de Sá"))!! shouldBe "de da do ok"
    }

    @Test
    fun `sequences of 5 or more digits are masked, shorter ones are kept`() {
        val out = FnrhMessageSanitizer.sanitize("CPF 12345678901 e cep 01310100, campo 1234 e 12", emptyList())!!

        out shouldBe "CPF *** e cep ***, campo 1234 e 12"
    }

    @Test
    fun `a formatted CPF is masked too`() {
        FnrhMessageSanitizer.sanitize("CPF 123.456.789-01 inválido", emptyList())!! shouldBe "CPF *** inválido"
    }

    @Test
    fun `e-mail addresses are masked`() {
        val out = FnrhMessageSanitizer.sanitize("Contato joao.silva+x@exemplo.com.br recusado", emptyList())!!

        out shouldBe "Contato *** recusado"
    }

    @Test
    fun `a formatted phone echoed in part is masked by its groups of 4 or more digits`() {
        val out = FnrhMessageSanitizer.sanitize("Telefone 9999-8888 inválido, ddd 11", listOf("(11) 9999-8888"))!!

        out shouldBe "Telefone ***-*** inválido, ddd 11"
    }

    @Test
    fun `the message is cut at 200 characters`() {
        val out = FnrhMessageSanitizer.sanitize("a".repeat(500), emptyList())!!

        out.length shouldBe 200
    }
}

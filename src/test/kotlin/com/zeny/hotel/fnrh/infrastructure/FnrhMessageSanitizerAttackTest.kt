package com.zeny.hotel.fnrh.infrastructure

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Ataques do testador ao saneador (E07, Task 4): eco em minúsculas e sem acento, bordas, corte e e-mails. */
class FnrhMessageSanitizerAttackTest {
    @Test
    fun `an echo in lowercase and without accents of any word of the name is masked`() {
        val out =
            FnrhMessageSanitizer.sanitize(
                "campo nome invalido: maria; ultimo: jose conceicao",
                listOf("MARIA DA LUZ", "José Conceição"),
            )!!

        listOf("maria", "jose", "conceicao").forEach { out shouldNotContain it }
        out shouldContain "campo nome invalido"
    }

    @Test
    fun `a word echoed in the middle of a longer word is masked too, never leaving the name readable`() {
        FnrhMessageSanitizer.sanitize("usuario_maria_2", listOf("Maria"))!! shouldNotContain "aria"
    }

    @Test
    fun `blank and empty sent values mask nothing`() {
        FnrhMessageSanitizer.sanitize("abc def ghi", listOf("", "   ", "\t"))!! shouldBe "abc def ghi"
    }

    @Test
    fun `sent values with regex metacharacters are matched literally`() {
        val out = FnrhMessageSanitizer.sanitize("erro em A.* (B+) fim", listOf("A.* (B+)"))!!

        out shouldBe "erro em *** fim"
    }

    @Test
    fun `an uppercase dotted I and other special letters keep the length, so the mask lands on the right text`() {
        val out = FnrhMessageSanitizer.sanitize("Nome ismail ja existe", listOf("İsmail"))!!

        out shouldBe "Nome *** ja existe"
    }

    @Test
    fun `emoji and surrogate pairs in the message do not break the masking`() {
        val out = FnrhMessageSanitizer.sanitize("😀 Maria 😀 erro", listOf("Maria"))!!

        out shouldBe "😀 *** 😀 erro"
    }

    @Test
    fun `two sent values glued together in the echo become one mask`() {
        FnrhMessageSanitizer.sanitize("MariaLuz", listOf("Maria", "Luz"))!! shouldBe "***"
    }

    @Test
    fun `a message of exactly 200 characters is kept and one of 201 is cut`() {
        FnrhMessageSanitizer.sanitize("b".repeat(200), emptyList())!!.length shouldBe 200
        FnrhMessageSanitizer.sanitize("b".repeat(201), emptyList())!!.length shouldBe 200
    }

    @Test
    fun `an e-mail or number that straddles the 200 character cut is masked before cutting, never half shown`() {
        val prefix = "x".repeat(190) + " "

        val email = FnrhMessageSanitizer.sanitize(prefix + "joao.silva@exemplo.com.br", emptyList())!!
        val digits = FnrhMessageSanitizer.sanitize(prefix + "123456789012345", emptyList())!!

        email shouldNotContain "@"
        email shouldNotContain "joao"
        digits shouldNotContain "12345"
        email.length shouldBe 194
    }

    @Test
    fun `masking runs on the whole message before the cut, so a name after position 200 never leaves a half mask`() {
        val out = FnrhMessageSanitizer.sanitize("a".repeat(198) + " Maria", listOf("Maria"))!!

        out.length shouldBe 200
        out shouldNotContain "Mar"
    }

    @Test
    fun `digit runs of 4 or fewer stay, but a run of exactly 5 is masked`() {
        FnrhMessageSanitizer.sanitize("a 1234 b 12345 c", emptyList())!! shouldBe "a 1234 b *** c"
    }

    @Test
    fun `a CNPJ and a CPF with dots are masked`() {
        val out = FnrhMessageSanitizer.sanitize("doc 12.345.678/0001-95 e 123.456.789-01", emptyList())!!

        out shouldNotContain "345"
        out shouldNotContain "789"
    }

    @Test
    fun `an e-mail with an accented local part is masked entirely`() {
        val out = FnrhMessageSanitizer.sanitize("erro em josé@exemplo.com.br agora", emptyList())!!

        out shouldNotContain "exemplo"
        out shouldNotContain "@"
    }

    @Test
    fun `an upper case e-mail with a subdomain is masked`() {
        FnrhMessageSanitizer.sanitize("MARIA.LUZ@MAIL.EXEMPLO.COM.BR!", emptyList())!! shouldBe "***!"
    }
}

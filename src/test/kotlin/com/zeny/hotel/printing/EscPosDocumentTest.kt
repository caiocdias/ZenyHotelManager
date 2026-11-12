package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.ESC_POS_COLUMNS
import com.zeny.hotel.printing.domain.EscPosDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EscPosDocumentTest {
    private val doc = EscPosDocument(ESC_POS_COLUMNS)
    private val ibm850 = charset("IBM850")

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ByteArray.indexOfSub(sub: ByteArray): Int =
        (0..size - sub.size).firstOrNull { i -> sub.indices.all { this[i + it] == sub[it] } } ?: -1

    private fun ByteArray.contains(sub: ByteArray) = indexOfSub(sub) >= 0

    @Test
    fun `comeca por inicializar e IBM850 e termina com corte parcial`() {
        val out = doc.encode(listOf("Hotel"), "corpo", listOf("rodape"))

        assertThat(out.take(5)).containsExactly(0x1B, 0x40, 0x1B, 0x74, 0x02)
        assertThat(out.takeLast(4)).containsExactly(0x1D, 0x56, 0x42, 0x00)
    }

    @Test
    fun `codifica acentos em IBM850`() {
        val out = doc.encode(emptyList(), "ã õ ç é", emptyList())

        assertThat(out.contains(bytes(0xC6, 0x20, 0xE4, 0x20, 0x87, 0x20, 0x82))).isTrue()
    }

    @Test
    fun `troca o que o IBM850 nao codifica por interrogacao`() {
        val out = doc.encode(emptyList(), "a李b😀c", emptyList())

        assertThat(out.contains("a?b?c".toByteArray(ibm850))).isTrue()
    }

    @Test
    fun `decompoe o que o IBM850 nao codifica e perde as marcas`() {
        val out = doc.encode(emptyList(), "Ťest", emptyList())

        assertThat(out.contains("Test".toByteArray(ibm850))).isTrue()
    }

    @Test
    fun `quebra linha longa por palavra em ate 48 colunas`() {
        val text = List(24) { "abcd" }.joinToString(" ") // 24 palavras de 4 + 23 espacos = 119
        val out = doc.encode(emptyList(), text, emptyList())

        val lines = bodyLines(out)
        assertThat(lines).hasSizeGreaterThan(1)
        assertThat(lines).allSatisfy { assertThat(it.length).isLessThanOrEqualTo(48) }
        assertThat(lines.joinToString(" ")).isEqualTo(text)
    }

    @Test
    fun `corta palavra maior que a largura`() {
        val out = doc.encode(emptyList(), "x".repeat(100), emptyList())

        assertThat(bodyLines(out).map { it.length }).containsExactly(48, 48, 4)
    }

    @Test
    fun `centraliza o cabecalho`() {
        val out = doc.encode(listOf("Hotel Zeny"), "corpo", emptyList())

        val expected = bytes(0x1B, 0x61, 0x01) + "Hotel Zeny\n".toByteArray(ibm850) + bytes(0x1B, 0x61, 0x00)
        assertThat(out.contains(expected)).isTrue()
    }

    @Test
    fun `troca tres hifens por uma regua na largura`() {
        val out = doc.encode(emptyList(), "a\n---\nb", emptyList())

        assertThat(out.contains(("\n" + "-".repeat(48) + "\n").toByteArray(ibm850))).isTrue()
    }

    @Test
    fun `controle no corpo nao vira comando fora dos do proprio encoder`() {
        val out = doc.encode(listOf("H\u001B@"), "a\u001B\u001Db\u001D", listOf("f\u001B"))

        val withoutOwn =
            String(out, ibm850)
                .replace(Regex("^\u001B@\u001Bt\u0002"), "")
                .replace("\u001Ba\u0001", "")
                .replace("\u001Ba\u0000", "")
                .replace(Regex("\u001DVB\u0000$"), "")
        assertThat(withoutOwn).doesNotContain("\u001B").doesNotContain("\u001D")
    }

    @Test
    fun `colunas fora de 32 a 64 falham`() {
        assertThatThrownBy { EscPosDocument(20) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EscPosDocument(65) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun bodyLines(out: ByteArray): List<String> {
        val text = String(out, ibm850)
        val body = text.substringAfter("\u001Bt\u0002").substringBefore("\n\n\n\n")
        return body.split("\n").filter { it.isNotEmpty() }
    }
}

package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.EscPosDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EscPosDocumentAttackTest {
    private val ibm850 = charset("IBM850")
    private val prefix = byteArrayOf(0x1B, 0x40, 0x1B, 0x74, 0x02)
    private val suffix = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

    /** Com cabecalho e rodape vazios, o miolo entre o prefixo e o sufixo e so o corpo + 4 avancos. */
    private fun bodyBytes(
        body: String,
        columns: Int = 48,
    ): ByteArray {
        val out = EscPosDocument(columns).encode(emptyList(), body, emptyList())
        return out.copyOfRange(prefix.size, out.size - suffix.size)
    }

    private fun assertNoCommandBytes(
        bytes: ByteArray,
        label: String,
    ) {
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            assertThat(v == 0x0A || (v >= 0x20 && v != 0x7F))
                .describedAs("$label: byte %02X na posicao %d", v, i)
                .isTrue()
        }
    }

    @Test
    fun `nenhum code point Unicode gera byte de controle no corpo`() {
        val sb = StringBuilder()
        var start = 0

        fun flush() {
            if (sb.isEmpty()) return
            assertNoCommandBytes(bodyBytes(sb.toString()), "a partir do code point $start")
            sb.setLength(0)
        }
        for (cp in 0..0x10FFFF) {
            if (sb.isEmpty()) start = cp
            sb.appendCodePoint(cp)
            if (sb.length > 3000) flush()
        }
        flush()
    }

    @Test
    fun `comandos de entrada em cabecalho corpo e rodape nao chegam como bytes`() {
        val hostile = "x\u001B@y\u001DVB\u0000z\u001C\u0010\u0004\u007F\u0085"
        val out = EscPosDocument(48).encode(listOf(hostile), hostile, listOf(hostile))

        val mid = out.copyOfRange(prefix.size, out.size - suffix.size)
        // so os dois alinhamentos do proprio encoder (ESC a 1 / ESC a 0) podem ter byte de controle alem do LF
        val text = String(mid, ibm850).replace("\u001Ba\u0001", "").replace("\u001Ba\u0000", "")
        assertNoCommandBytes(text.toByteArray(ibm850), "hostil")
    }

    @Test
    fun `um controle sozinho como linha nao vira comando e o corte e o unico GS`() {
        val out = EscPosDocument(48).encode(listOf("\u001D"), "\u001B\n\u001D\n\u0010", listOf("\u001C"))

        val gsCount = out.count { it == 0x1D.toByte() }
        val escCount = out.count { it == 0x1B.toByte() }
        assertThat(gsCount).isEqualTo(1)
        assertThat(escCount).isEqualTo(4) // ESC @, ESC t, ESC a 1, ESC a 0
    }

    @Test
    fun `texto vazio gera so o esqueleto`() {
        val out = EscPosDocument(48).encode(emptyList(), "", emptyList())

        assertThat(out.drop(5).dropLast(4)).containsOnly(0x0A.toByte())
    }

    @Test
    fun `cabecalho vazio nao emite alinhamento`() {
        val out = EscPosDocument(48).encode(emptyList(), "x", emptyList())

        assertThat(String(out, ibm850)).doesNotContain("\u001Ba")
    }

    @Test
    fun `linha so de espacos vira linha vazia`() {
        val body = bodyBytes("a\n   \t \nb")

        assertThat(String(body, ibm850)).startsWith("a\n\nb\n")
    }

    @Test
    fun `largura 32 respeitada e largura 64 respeitada`() {
        val text = List(40) { "palavra" }.joinToString(" ")
        listOf(32, 64).forEach { cols ->
            val lines = String(bodyBytes(text, cols), ibm850).trimEnd('\n').split("\n")
            assertThat(lines).describedAs("cols=$cols").allSatisfy { assertThat(it.length).isLessThanOrEqualTo(cols) }
            assertThat(lines.joinToString(" ")).isEqualTo(text)
        }
    }

    @Test
    fun `colunas 31 e 65 falham e 32 e 64 passam`() {
        assertThatThrownBy { EscPosDocument(31) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EscPosDocument(65) }.isInstanceOf(IllegalArgumentException::class.java)
        EscPosDocument(32)
        EscPosDocument(64)
    }

    @Test
    fun `linha de exatamente a largura nao quebra e a de largura mais um quebra`() {
        val exact = "a".repeat(23) + " " + "b".repeat(24) // 48
        assertThat(String(bodyBytes(exact), ibm850).trimEnd('\n').split("\n")).containsExactly(exact)

        val over = "a".repeat(24) + " " + "b".repeat(24) // 49
        assertThat(
            String(bodyBytes(over), ibm850).trimEnd('\n').split("\n"),
        ).containsExactly("a".repeat(24), "b".repeat(24))
    }

    @Test
    fun `palavra longa depois de texto abre linha nova e o resto continua na mesma`() {
        val lines = String(bodyBytes("ab " + "x".repeat(50) + " cd"), ibm850).trimEnd('\n').split("\n")

        assertThat(lines).containsExactly("ab", "x".repeat(48), "xx cd")
    }

    @Test
    fun `a regua so vale quando a linha e exatamente tres hifens`() {
        val text = String(bodyBytes("--- x\n----\n---"), ibm850)

        assertThat(text).startsWith("--- x\n----\n" + "-".repeat(48) + "\n")
    }

    @Test
    fun `regua no cabecalho e no rodape tambem usa a largura`() {
        val out = String(EscPosDocument(32).encode(listOf("---"), "x", listOf("  ---  ")), ibm850)

        assertThat(out.split("-".repeat(32) + "\n")).hasSize(3)
    }

    @Test
    fun `controle no meio da palavra separa a palavra em vez de colar`() {
        assertThat(String(bodyBytes("ab\u001Bcd"), ibm850)).startsWith("ab cd\n")
    }

    @Test
    fun `emoji com ZWJ vira interrogacoes sem byte de controle`() {
        val body = bodyBytes("a👨‍👩‍👧b")

        assertThat(String(body, ibm850)).startsWith("a???b\n")
    }

    @Test
    fun `combinantes em excesso nao estouram a largura nem geram controle`() {
        val text = "e" + "́".repeat(500)
        val body = bodyBytes(text)

        assertNoCommandBytes(body, "combinantes")
        String(body, ibm850).trimEnd('\n').split("\n").forEach { assertThat(it.length).isLessThanOrEqualTo(48) }
    }

    @Test
    fun `acento combinante em texto decomposto sai como a letra acentuada`() {
        val body = bodyBytes("José Açao")

        assertThat(String(body, ibm850)).startsWith("José Ação\n")
    }

    @Test
    fun `corte de palavra longa nao parte um par substituto ao meio`() {
        val text = "x".repeat(47) + "😀" + "y"
        val out = String(bodyBytes(text), ibm850)

        assertThat(out.count { it == '?' }).describedAs("um emoji = uma interrogacao").isEqualTo(1)
    }

    @Test
    fun `surrogate solto vira interrogacao`() {
        assertThat(String(bodyBytes("a\uD800b"), ibm850)).startsWith("a?b\n")
    }

    @Test
    fun `NBSP e separadores Z viram espaco e quebram a linha como espaco`() {
        val text = "a b c　d"

        assertThat(String(bodyBytes(text), ibm850)).startsWith("a b c d\n")
    }

    @Test
    fun `quebra de linha CRLF e CR no corpo nao deixa CR na saida`() {
        val body = bodyBytes("a\r\nb\rc")

        assertThat(body).doesNotContain(0x0D.toByte())
    }
}

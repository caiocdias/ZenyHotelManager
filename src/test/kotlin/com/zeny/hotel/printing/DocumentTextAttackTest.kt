package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.DocumentText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentTextAttackTest {
    @Test
    fun `todo controle C0 C1 e DEL some da saida`() {
        val controls = (0x00..0x1F) + (0x7F..0x9F)
        controls.forEach { cp ->
            val out = DocumentText.clean("a${cp.toChar()}b")
            assertThat(out).describedAs("U+%04X", cp).isEqualTo("a b")
        }
    }

    @Test
    fun `nenhum code point de controle ou formatacao sobrevive em texto sujo misto`() {
        val all = StringBuilder()
        for (cp in 0..0x10FFFF) {
            val t = Character.getType(cp)
            if (t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()) all.appendCodePoint(cp).append('x')
        }
        val out = DocumentText.clean(all.toString())

        out.codePoints().forEach { cp ->
            assertThat(Character.getType(cp)).describedAs("U+%04X", cp).isNotIn(
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
            )
        }
    }

    @Test
    fun `separadores Z viram um unico espaco`() {
        val seps = listOf(0x00A0, 0x1680, 0x2000, 0x2003, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        seps.forEach { cp ->
            assertThat(DocumentText.clean("a${cp.toChar()}${cp.toChar()}b")).describedAs("U+%04X", cp).isEqualTo("a b")
        }
    }

    @Test
    fun `vazio e so espacos e so controles viram vazio`() {
        assertThat(DocumentText.clean("")).isEmpty()
        assertThat(DocumentText.clean("   \t\n  ")).isEmpty()
        assertThat(DocumentText.clean("\u001B\u001D​‮")).isEmpty()
    }

    @Test
    fun `emoji com ZWJ perde so o ZWJ`() {
        assertThat(DocumentText.clean("👨‍👩")).isEqualTo("👨👩")
    }

    @Test
    fun `surrogate solto nao quebra a limpeza nem o winansi`() {
        val lone = "a\uD800b\uDC00c"

        assertThat(DocumentText.clean(lone)).isEqualTo(lone)
        assertThat(DocumentText.toWinAnsi(lone)).isEqualTo("a?b?c")
    }

    @Test
    fun `clean e idempotente`() {
        val once = DocumentText.clean("  Jo‮ão  Silva\u001B  ")

        assertThat(DocumentText.clean(once)).isEqualTo(once)
    }

    @Test
    fun `toWinAnsi so produz caracteres que o windows 1252 codifica`() {
        val enc = charset("windows-1252").newEncoder()
        val sb = StringBuilder()
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            sb.appendCodePoint(cp)
            if (sb.length > 4000) {
                assertThat(enc.canEncode(DocumentText.toWinAnsi(sb.toString()))).isTrue()
                sb.setLength(0)
            }
        }
        assertThat(enc.canEncode(DocumentText.toWinAnsi(sb.toString()))).isTrue()
    }

    @Test
    fun `combinante em texto decomposto NFD nao vira interrogacao no winansi`() {
        // "José" com o acento combinante U+0301 (NFD, como vem de teclados/arquivos de macOS)
        assertThat(DocumentText.toWinAnsi("José")).isEqualTo("José")
    }
}

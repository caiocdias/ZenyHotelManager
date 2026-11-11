package com.zeny.hotel.printing.domain

import java.nio.charset.Charset
import java.text.Normalizer

/**
 * Limpeza e adequação de texto de documentos (D-E11-4). O escape do Thymeleaf protege a marcação; esta limpeza
 * protege o ESC/POS contra injeção de comando e o PDF contra marcas de direção.
 */
object DocumentText {
    private val whitespace = Regex("""[\s\p{Z}]+""")
    private val winAnsi: Charset = charset("windows-1252")

    /**
     * Troca os caracteres de controle (`Cc`, inclusive `\n`, `\t`, ESC e GS) por espaço, remove os de formatação
     * (`Cf`: marcas bidi, largura zero, `U+FEFF`), colapsa os espaços repetidos e apara as pontas.
     */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            when (Character.getType(cp)) {
                Character.CONTROL.toInt() -> out.append(' ')
                Character.FORMAT.toInt() -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString().replace(whitespace, " ").trim()
    }

    /** O que o `windows-1252` (WinAnsi das fontes base-14 do PDF) não codifica vira `?`, um por caractere. */
    fun toWinAnsi(text: String): String =
        mapCodePoints(text) { codePoint ->
            val s = String(Character.toChars(codePoint))
            if (winAnsi.newEncoder().canEncode(s)) s else "?"
        }

    /** Aplica [transform] a cada code point (um par substituto é um só caractere). */
    internal fun mapCodePoints(
        text: String,
        transform: (Int) -> String,
    ): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { out.append(transform(it)) }
        return out.toString()
    }

    /** Decompõe (NFD) e descarta as marcas combinantes; usado pela térmica quando o IBM850 não codifica o caractere. */
    internal fun stripMarks(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
}

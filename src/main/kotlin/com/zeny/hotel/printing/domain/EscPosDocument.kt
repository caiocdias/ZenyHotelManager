package com.zeny.hotel.printing.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Largura da térmica de 80 mm, fonte A (D-E11-6). */
const val ESC_POS_COLUMNS = 48

/**
 * Compõe o documento ESC/POS: `ESC @`, `ESC t 2` (IBM850), cabeçalho centralizado, corpo quebrado por palavra em
 * [columns] colunas, rodapé, 4 avanços e corte parcial (D-E11-6). Todo texto passa por [DocumentText.clean] (linha a
 * linha, no corpo), então nenhum ESC/GS de entrada chega à impressora.
 */
class EscPosDocument(
    private val columns: Int,
) {
    init {
        require(columns in MIN_COLUMNS..MAX_COLUMNS) { "Colunas fora de $MIN_COLUMNS a $MAX_COLUMNS: $columns" }
    }

    fun encode(
        header: List<String>,
        body: String,
        footer: List<String>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC, '@'.code.toByte(), ESC, 't'.code.toByte(), CODE_PAGE_850))
        if (header.isNotEmpty()) {
            out.write(byteArrayOf(ESC, 'a'.code.toByte(), 1))
            header.flatMap(::wrap).forEach { line(out, it) }
            out.write(byteArrayOf(ESC, 'a'.code.toByte(), 0))
        }
        body.split('\n').flatMap(::wrap).forEach { line(out, it) }
        footer.flatMap(::wrap).forEach { line(out, it) }
        repeat(FEED_LINES) { out.write(LF) }
        out.write(byteArrayOf(GS, 'V'.code.toByte(), CUT_PARTIAL, 0))
        return out.toByteArray()
    }

    private fun line(
        out: ByteArrayOutputStream,
        text: String,
    ) {
        out.write(encodeText(text))
        out.write(LF)
    }

    /** Limpa a linha e a quebra por palavra; palavra maior que a largura é cortada. `---` vira a régua. */
    private fun wrap(raw: String): List<String> {
        val cleaned = DocumentText.clean(raw)
        return when {
            cleaned == RULE_MARK -> listOf("-".repeat(columns))
            cleaned.isEmpty() -> listOf("")
            else -> wrapWords(cleaned)
        }
    }

    private fun wrapWords(cleaned: String): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (word in cleaned.split(' ')) {
            val pieces = if (word.length > columns) word.chunked(columns) else listOf(word)
            for (piece in pieces) {
                current =
                    when {
                        current.isEmpty() -> {
                            piece
                        }

                        current.length + 1 + piece.length <= columns -> {
                            "$current $piece"
                        }

                        else -> {
                            lines += current
                            piece
                        }
                    }
            }
        }
        lines += current
        return lines
    }

    /** IBM850; o que ele não codifica é decomposto (NFD) sem as marcas, e o que sobrar vira `?`. */
    private fun encodeText(text: String): ByteArray =
        DocumentText
            .mapCodePoints(text) { cp ->
                val s = String(Character.toChars(cp))
                when {
                    ibm850.newEncoder().canEncode(s) -> {
                        s
                    }

                    else -> {
                        DocumentText.stripMarks(s).let { base ->
                            if (base.isNotEmpty() && ibm850.newEncoder().canEncode(base)) base else "?"
                        }
                    }
                }
            }.toByteArray(ibm850)

    private companion object {
        const val ESC: Byte = 0x1B
        const val GS: Byte = 0x1D
        const val LF: Int = 0x0A
        const val CODE_PAGE_850: Byte = 2
        const val CUT_PARTIAL: Byte = 66
        const val FEED_LINES = 4
        const val MIN_COLUMNS = 32
        const val MAX_COLUMNS = 64
        const val RULE_MARK = "---"
        val ibm850: Charset = charset("IBM850")
    }
}

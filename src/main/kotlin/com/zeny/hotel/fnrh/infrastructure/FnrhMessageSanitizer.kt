package com.zeny.hotel.fnrh.infrastructure

import java.text.Normalizer

/**
 * Limpa a `mensagem` de um erro do Serpro antes de ela ir a log ou à exceção, porque o `400` pode ecoar o que
 * enviamos (nome, CPF, e-mail). Troca por `***`:
 *
 * - os valores enviados (só os de 3 caracteres ou mais) e cada palavra deles com 3 ou mais letras, ignorando
 *   maiúsculas e acentos, porque o Serpro pode ecoar só "MARIA" de "Maria da Luz";
 * - os e-mails;
 * - o CPF e o CNPJ pontuados e as sequências de 5 ou mais dígitos.
 *
 * E corta em [MAX_LENGTH] caracteres.
 */
object FnrhMessageSanitizer {
    const val MAX_LENGTH = 200
    private const val MASK = "***"
    private const val MIN_VALUE_LENGTH = 3
    private const val MIN_WORD_LETTERS = 3
    private val EMAIL = Regex("""[\w.+%-]+@[\w-]+(\.[\w-]+)+""")
    private val FORMATTED_TAX_ID = Regex("""\d{2,3}\.\d{3}\.\d{3}[-/]\d{2,4}(-\d{2})?""")
    private val LONG_DIGITS = Regex("""\d{5,}""")
    private val WHITESPACE = Regex("""\s+""")

    fun sanitize(
        raw: String?,
        sentValues: Collection<String>,
    ): String? {
        if (raw.isNullOrBlank()) return null
        val masked = maskSentValues(raw, sentValues)
        return masked
            .replace(EMAIL, MASK)
            .replace(FORMATTED_TAX_ID, MASK)
            .replace(LONG_DIGITS, MASK)
            .take(MAX_LENGTH)
    }

    private fun maskSentValues(
        raw: String,
        sentValues: Collection<String>,
    ): String {
        val needles =
            sentValues
                .flatMap { value ->
                    listOf(value) +
                        value.split(WHITESPACE).filter { it.count(Char::isLetter) >= MIN_WORD_LETTERS }
                }.map { fold(it.trim()) }
                .filter { it.length >= MIN_VALUE_LENGTH }
                .distinct()
        if (needles.isEmpty()) return raw
        val folded = fold(raw)
        val hidden = BooleanArray(raw.length)
        for (needle in needles) {
            var from = folded.indexOf(needle)
            while (from >= 0) {
                for (i in from until from + needle.length) hidden[i] = true
                from = folded.indexOf(needle, from + 1)
            }
        }
        return buildString {
            var inMask = false
            raw.forEachIndexed { i, c ->
                if (hidden[i]) {
                    if (!inMask) append(MASK)
                    inMask = true
                } else {
                    inMask = false
                    append(c)
                }
            }
        }
    }

    /** Minúsculas e sem acento, com o mesmo comprimento do texto (um caractere vira um caractere). */
    private fun fold(text: String): String =
        buildString(text.length) {
            for (c in text) {
                val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first()
                append(base.lowercaseChar())
            }
        }
}

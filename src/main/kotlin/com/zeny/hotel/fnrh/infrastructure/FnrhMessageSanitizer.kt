package com.zeny.hotel.fnrh.infrastructure

import java.text.Normalizer

/**
 * Limpa a `mensagem` de um erro do Serpro antes de ela ir a log ou à exceção, porque o `400` pode ecoar o que
 * enviamos (nome, CPF, e-mail). Troca por `***`:
 *
 * - os valores enviados (só os de 3 caracteres ou mais), cada palavra deles com 3 ou mais letras e cada grupo de 4 ou
 *   mais dígitos (um telefone pontuado pode voltar em parte), ignorando
 *   maiúsculas e acentos, porque o Serpro pode ecoar só "MARIA" de "Maria da Luz";
 * - os e-mails;
 * - o CPF e o CNPJ pontuados e as sequências de 5 ou mais dígitos.
 *
 * Caracteres de controle (CR, LF) viram espaço, para a mensagem sair numa linha só do log. E corta em [MAX_LENGTH]
 * caracteres.
 */
object FnrhMessageSanitizer {
    const val MAX_LENGTH = 200
    private const val MASK = "***"
    private const val MIN_VALUE_LENGTH = 3
    private const val MIN_WORD_LETTERS = 3
    private const val INPUT_FACTOR = 5
    private val CONTROL = Regex("""\p{Cntrl}""")
    private val EMAIL = Regex("""[\p{L}\p{N}_.+%-]+@[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)+""")
    private val FORMATTED_TAX_ID = Regex("""\d{2,3}\.\d{3}\.\d{3}[-/]\d{2,4}(-\d{2})?""")
    private val LONG_DIGITS = Regex("""\d{5,}""")
    private val WORD_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")
    private val DIGIT_GROUP = Regex("""\d{4,}""")

    fun sanitize(
        raw: String?,
        sentValues: Collection<String>,
    ): String? {
        if (raw.isNullOrBlank()) return null
        // Limita o trabalho das regex: a mensagem pode ter até 1 MiB e o resultado é cortado em MAX_LENGTH.
        val masked = maskSentValues(raw.take(MAX_LENGTH * INPUT_FACTOR).replace(CONTROL, " "), sentValues)
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
                        value.split(WORD_SEPARATOR).filter { it.count(Char::isLetter) >= MIN_WORD_LETTERS } +
                        DIGIT_GROUP.findAll(value).map { it.value }
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

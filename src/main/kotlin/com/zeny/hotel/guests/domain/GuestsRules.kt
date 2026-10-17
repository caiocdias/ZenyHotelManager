package com.zeny.hotel.guests.domain

import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import java.text.Normalizer
import java.util.Locale

/**
 * Validações e normalizações comuns de `guests`. As recusas saem como `400` com o campo em `errors[]` e
 * mensagens que nunca repetem o valor recebido (dado pessoal).
 */
internal object GuestsRules {
    private val COUNTRIES: Set<String> = Locale.getISOCountries().toSet()
    private val EMAIL = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
    private val PHONE = Regex("""\+?[0-9()\- ]{6,30}""")
    private val COMBINING_MARKS = Regex("""\p{M}+""")
    private val SPACES = Regex("""\s+""")
    private const val MAX_EMAIL_LENGTH = 254

    /** Texto obrigatório, sem espaços nas pontas, de 1 a [max] caracteres. */
    fun required(
        field: String,
        value: String,
        max: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > max) {
            throw invalid(GuestsErrorCodes.GUESTS_DATA_INVALID, field, "Informe $field com até $max caracteres.")
        }
        return trimmed
    }

    /** Texto opcional: em branco vira `null`; senão, até [max] caracteres. */
    fun optional(
        field: String,
        value: String?,
        max: Int,
    ): String? {
        val trimmed = value?.trim()?.ifEmpty { null } ?: return null
        if (trimmed.length > max) {
            throw invalid(GuestsErrorCodes.GUESTS_DATA_INVALID, field, "Use até $max caracteres em $field.")
        }
        return trimmed
    }

    /** País pela ISO 3166-1 alfa-2, em maiúsculas; em branco vira `null`. */
    fun country(
        field: String,
        value: String?,
    ): String? {
        val code = value?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: return null
        if (code !in COUNTRIES) {
            throw invalid(
                GuestsErrorCodes.INVALID_COUNTRY,
                field,
                "Informe o país pelo código ISO 3166-1 de duas letras (BR, AR, US…).",
            )
        }
        return code
    }

    /** E-mail sem espaços nas pontas e em minúsculas (a busca compara assim); em branco vira `null`. */
    fun email(
        field: String,
        value: String?,
    ): String? {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null } ?: return null
        if (normalized.length > MAX_EMAIL_LENGTH || !EMAIL.matches(normalized)) {
            throw invalid(GuestsErrorCodes.GUESTS_DATA_INVALID, field, "Informe um e-mail válido.")
        }
        return normalized
    }

    /** Telefone com dígitos, `+`, parênteses, hífen e espaços, de 6 a 30 caracteres; em branco vira `null`. */
    fun phone(
        field: String,
        value: String?,
    ): String? {
        val trimmed = value?.trim()?.ifEmpty { null } ?: return null
        if (!PHONE.matches(trimmed)) {
            throw invalid(GuestsErrorCodes.GUESTS_DATA_INVALID, field, "Informe o telefone só com dígitos e +()-.")
        }
        return trimmed
    }

    /**
     * A chave de busca por nome: sem acento, em minúsculas e com os espaços colapsados (decisão do usuário em
     * 2026-10-16: coluna normalizada com índice de trigramas). Vale para o que se grava e para o que se busca.
     */
    fun searchKey(vararg names: String?): String =
        names
            .filterNotNull()
            .joinToString(" ") {
                Normalizer
                    .normalize(it, Normalizer.Form.NFD)
                    .replace(COMBINING_MARKS, "")
                    .lowercase(Locale.ROOT)
            }.replace(SPACES, " ")
            .trim()

    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

/**
 * Os campos alterados por uma edição, para a trilha (H10: a trilha leva a lista dos campos, nunca os
 * valores). [set] grava o valor novo e anota o campo quando ele muda.
 */
internal class ChangedFields {
    private val names = linkedSetOf<String>()

    val fields: List<String> get() = names.toList()

    fun <T> set(
        field: String,
        current: T,
        new: T,
        assign: (T) -> Unit,
    ) {
        if (current != new) {
            names += field
            assign(new)
        }
    }
}

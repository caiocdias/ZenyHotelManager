package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException

/** Validações comuns do cadastro de UHs e áreas; as recusas saem como `400` com o campo em `errors[]`. */
internal object RoomsRules {
    private val CODE = Regex("[A-Z0-9][A-Z0-9_-]{0,19}")
    private val NUMBER = Regex("[0-9A-Za-z][0-9A-Za-z-]{0,9}")

    /** Código de tipo ou característica: sem espaços nas pontas, em maiúsculas, letras, dígitos, `_` e `-`, até 20. */
    fun code(value: String): String {
        val normalized = value.trim().uppercase()
        if (!CODE.matches(normalized)) {
            throw invalid(
                RoomsErrorCodes.ROOMS_DATA_INVALID,
                "code",
                "Informe o código com até 20 letras, dígitos, \"_\" ou \"-\".",
            )
        }
        return normalized
    }

    /** Número da UH: sem espaços nas pontas, letras, dígitos e `-`, até 10; a caixa é preservada. */
    fun number(value: String): String {
        val trimmed = value.trim()
        if (!NUMBER.matches(trimmed)) {
            throw invalid(
                RoomsErrorCodes.ROOMS_DATA_INVALID,
                "number",
                "Informe o número da UH com até 10 letras, dígitos ou \"-\".",
            )
        }
        return trimmed
    }

    /** Texto obrigatório, sem espaços nas pontas, de 1 a [max] caracteres. */
    fun required(
        field: String,
        value: String,
        max: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > max) {
            throw invalid(RoomsErrorCodes.ROOMS_DATA_INVALID, field, "Informe $field com até $max caracteres.")
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
            throw invalid(RoomsErrorCodes.ROOMS_DATA_INVALID, field, "Use até $max caracteres em $field.")
        }
        return trimmed
    }

    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

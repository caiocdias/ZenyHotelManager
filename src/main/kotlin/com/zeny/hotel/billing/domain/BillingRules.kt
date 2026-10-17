package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException

/** As validações de formato do `billing`, com o campo da recusa em `errors[]`. */
internal object BillingRules {
    private val CODE = Regex("^[A-Z0-9][A-Z0-9_-]{0,19}$")

    /** Texto obrigatório, sem espaços nas pontas, até [max] caracteres. */
    fun required(
        field: String,
        value: String?,
        max: Int,
        code: ErrorCode = BillingErrorCodes.BILLING_DATA_INVALID,
    ): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) throw invalid(code, field, "Informe `$field`.")
        if (trimmed.length > max) throw invalid(code, field, "`$field` aceita até $max caracteres.")
        return trimmed
    }

    /** Texto opcional: vazio vira nulo. */
    fun optional(
        field: String,
        value: String?,
        max: Int,
        code: ErrorCode = BillingErrorCodes.BILLING_DATA_INVALID,
    ): String? = value?.trim()?.ifEmpty { null }?.let { required(field, it, max, code) }

    /** Código curto: maiúsculas, dígitos, `_` e `-`, até 20, começando por letra ou dígito. */
    fun code(value: String?): String {
        val upper = required("code", value, MAX_CODE_LENGTH).uppercase()
        if (!CODE.matches(upper)) {
            throw invalid(
                BillingErrorCodes.BILLING_DATA_INVALID,
                "code",
                "O código usa letras, dígitos, `_` e `-` (até 20), começando por letra ou dígito.",
            )
        }
        return upper
    }

    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))

    const val MAX_CODE_LENGTH = 20
}

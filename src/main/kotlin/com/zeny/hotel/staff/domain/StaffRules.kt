package com.zeny.hotel.staff.domain

import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.staff.StaffErrorCodes
import java.time.LocalTime

/** Validações comuns do cadastro de pessoas e escala; as recusas saem como `400` com o campo em `errors[]`. */
internal object StaffRules {
    /** Texto obrigatório, sem espaços nas pontas, de 1 a [max] caracteres. */
    fun required(
        field: String,
        value: String,
        max: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > max) {
            throw invalid(StaffErrorCodes.STAFF_DATA_INVALID, field, "Informe $field com até $max caracteres.")
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
            throw invalid(StaffErrorCodes.STAFF_DATA_INVALID, field, "Use até $max caracteres em $field.")
        }
        return trimmed
    }

    /** Horário em horas e minutos, sem segundos nem frações. */
    fun wholeMinute(
        field: String,
        time: LocalTime,
    ) {
        if (time.second != 0 || time.nano != 0) {
            throw invalid(StaffErrorCodes.SHIFT_TIMES_INVALID, field, "Informe o horário em horas e minutos.")
        }
    }

    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

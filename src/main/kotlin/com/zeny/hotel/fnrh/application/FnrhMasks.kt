package com.zeny.hotel.fnrh.application

private const val CPF_VISIBLE_DIGITS = 2

/**
 * `***.***.***-25`: só os 2 últimos dígitos do CPF aparecem. Recebe os dígitos, nunca o `Cpf`, porque o `toString`
 * dele os devolve.
 */
internal fun maskCpf(digits: String): String = "***.***.***-" + digits.takeLast(CPF_VISIBLE_DIGITS)

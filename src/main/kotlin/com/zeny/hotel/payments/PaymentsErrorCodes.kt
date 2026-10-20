package com.zeny.hotel.payments

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do `payments` (ARCHITECTURE §7): um código publicado não muda de nome nem de sentido. */
object PaymentsErrorCodes {
    /**
     * 400: intenção com valor não positivo ou referência do provedor fora do formato (até 40 letras, dígitos, `.`,
     * `_` ou `-`, com menos de 13 dígitos: nunca um número de cartão).
     */
    val PAYMENT_INTENT_INVALID = ErrorCode("PAYMENT_INTENT_INVALID")
}

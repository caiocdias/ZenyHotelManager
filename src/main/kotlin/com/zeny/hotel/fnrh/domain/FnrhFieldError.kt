package com.zeny.hotel.fnrh.domain

/**
 * Um problema achado na validação local dos dados da FNRH. [field] é o caminho no [FnrhPersonData] ou no [FnrhGuest]
 * (`person.document`, `person.cityIbge`, `guest.travelReason`…) e [code] é `REQUIRED`, `INVALID_VALUE`,
 * `NOT_IN_DOMAIN` ou `INCONSISTENT`. Nunca carrega o valor recusado: o erro pode ir ao log e à resposta da API.
 */
data class FnrhFieldError(
    val field: String,
    val code: String,
)

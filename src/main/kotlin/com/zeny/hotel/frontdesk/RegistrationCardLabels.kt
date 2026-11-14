package com.zeny.hotel.frontdesk

/**
 * A porta pela qual a ficha de registro obtém os rótulos dos códigos da FNRH (M6 E15, D-E11-11): o `frontdesk` não
 * conhece o `fnrh` (que fica acima dele), então o `fnrh` a implementa lendo o catálogo de domínios. Exatamente uma
 * implementação no contexto; só leitura.
 */
interface RegistrationCardLabels {
    /** O rótulo do motivo de viagem [code], ou `null` se o código não consta do catálogo. */
    fun travelReason(code: String): String?

    /** O rótulo do meio de transporte [code], ou `null` se o código não consta do catálogo. */
    fun transportMode(code: String): String?
}

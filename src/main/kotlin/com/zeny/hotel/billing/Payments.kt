package com.zeny.hotel.billing

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

/** Id de um pagamento ou de um reembolso na conta (M3 E08). */
@JvmInline
value class PaymentId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PaymentId = PaymentId(UuidV7.generate())
    }
}

/**
 * Tipo do registro de pagamento na conta (RF-CTA-06 e 15, M3 E08): o recebimento e a devolução vinculada a ele. No
 * contrato, nas respostas, é `x-extensible-enum`. A captura de uma caução (E09) é um `PAYMENT` vinculado à caução.
 */
enum class PaymentType {
    /** Recebimento (diminui o saldo da conta; entra no esperado do turno de quem recebe). */
    PAYMENT,

    /** Reembolso de um recebimento (aumenta o saldo; sai do esperado do turno de quem devolve, na mesma forma). */
    REFUND,
}

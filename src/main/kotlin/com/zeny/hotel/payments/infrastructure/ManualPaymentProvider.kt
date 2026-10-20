package com.zeny.hotel.payments.infrastructure

import com.zeny.hotel.payments.PaymentProviderCode
import com.zeny.hotel.payments.application.PaymentProvider
import com.zeny.hotel.payments.application.ProviderOutcome
import com.zeny.hotel.payments.domain.PaymentIntent
import org.springframework.stereotype.Component

/**
 * O provedor `MANUAL` (RF-PAG-03, H1 do M3): o pagamento já aconteceu fora do sistema (dinheiro na gaveta, maquininha
 * não integrada, Pix conferido no aplicativo do banco) e quem opera o registra com a forma, o valor e, se houver, o
 * NSU ou a autorização. Confirma na hora, com a referência informada; nunca fala com ninguém.
 */
@Component
class ManualPaymentProvider : PaymentProvider {
    override val code = PaymentProviderCode.MANUAL

    override fun start(
        intent: PaymentIntent,
        reference: String?,
    ): ProviderOutcome = ProviderOutcome.Succeeded(reference)
}

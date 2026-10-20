package com.zeny.hotel.payments.application

import com.zeny.hotel.payments.PaymentProviderCode
import com.zeny.hotel.payments.domain.PaymentIntent
import org.springframework.stereotype.Component

/**
 * Porta de um provedor de pagamento (RF-PAG-06, ARCHITECTURE §12: porta no módulo e um adaptador por provedor, em
 * `infrastructure`). O provedor recebe a intenção já gravada (pendente) e a referência informada por quem opera, e
 * devolve o resultado: confirmado na hora (o `MANUAL`), pendente (o Pix e o cartão do M7, confirmados depois por
 * notificação ou consulta) ou recusado. Um provedor novo é um bean que implementa esta porta com um [code] próprio; o
 * boot recusa código repetido.
 */
interface PaymentProvider {
    val code: PaymentProviderCode

    fun start(
        intent: PaymentIntent,
        reference: String?,
    ): ProviderOutcome
}

/** O resultado de um provedor ao iniciar a intenção. */
sealed interface ProviderOutcome {
    /** Confirmado, com a referência do provedor. */
    data class Succeeded(
        val reference: String?,
    ) : ProviderOutcome

    /** A confirmar depois (M7), com a referência da cobrança no provedor. */
    data class Pending(
        val reference: String?,
    ) : ProviderOutcome

    /** Recusado, com o motivo (sem dado de cartão). */
    data class Failed(
        val reason: String,
    ) : ProviderOutcome
}

/** Os provedores registrados, por código; código repetido impede o boot. */
@Component
class PaymentProviders(
    providers: List<PaymentProvider>,
) {
    private val byCode: Map<PaymentProviderCode, PaymentProvider> =
        providers.groupBy { it.code }.mapValues { (code, found) ->
            check(found.size == 1) { "Provedor de pagamento $code registrado ${found.size} vezes" }
            found.single()
        }

    /** O provedor do código; desconhecido é erro de programação. */
    fun of(code: PaymentProviderCode): PaymentProvider =
        requireNotNull(byCode[code]) { "Provedor de pagamento desconhecido: $code" }

    val codes: Set<PaymentProviderCode> get() = byCode.keys
}

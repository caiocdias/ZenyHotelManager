package com.zeny.hotel.payments

import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * API pública do `payments` (H1 do M3, M3 E08): toda cobrança passa por uma intenção de pagamento ([start]) com a
 * finalidade e o destino **opacos** (o `payments` não conhece contas, comandas nem parcelas) e um provedor. O provedor
 * `MANUAL` (RF-PAG-03: maquininha não integrada, Pix ou dinheiro conferidos por quem opera) confirma na hora; os do
 * M7 (Pix com PSP, cartão integrado) confirmam depois. A intenção confirmada publica [PaymentSucceeded] pelo outbox, na
 * transação de quem a iniciou. **Não confere permissão** (quem chama já se autorizou); a escrita é auditada
 * (`payments.payment-intent.start`) em nome do ator corrente, ou do sistema.
 */
interface PaymentsApi {
    /**
     * Inicia a intenção e a submete ao provedor ([NewPaymentIntent.provider]): com o `MANUAL`, ela volta já
     * `SUCCEEDED`, com a referência informada ([NewPaymentIntent.reference]), e [PaymentSucceeded] fica no outbox da
     * transação de quem chama (desfeita, nada fica). Chame dentro da transação de escrita do caso de uso. Provedor
     * desconhecido: `IllegalArgumentException` (erro de programação). Valor não positivo ou referência fora do formato
     * (até 40 letras, dígitos, `.`, `_` ou `-`, com menos de 13 dígitos: nunca um número de cartão, RNF-10):
     * `400 PAYMENT_INTENT_INVALID`.
     */
    fun start(intent: NewPaymentIntent): PaymentIntentSummary

    /** A intenção; `null` se não existe. */
    fun intent(id: PaymentIntentId): PaymentIntentSummary?
}

/** Id de uma intenção de pagamento. */
@JvmInline
value class PaymentIntentId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PaymentIntentId = PaymentIntentId(UuidV7.generate())
    }
}

/**
 * Código de um provedor de pagamento (porta `PaymentProvider`, um adaptador por provedor; RF-PAG-06): maiúsculas,
 * dígitos e `_`, até 30. No M3 só existe o [MANUAL]; o M7 acrescenta os do PSP Pix e do adquirente.
 */
@JvmInline
value class PaymentProviderCode(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Código de provedor fora do formato: $value" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[A-Z][A-Z0-9_]{0,29}$")

        /** Registro manual (RF-PAG-03): confirma na hora, com a referência informada por quem opera. */
        val MANUAL = PaymentProviderCode("MANUAL")
    }
}

/**
 * A finalidade da intenção, opaca para o `payments`: `<módulo>.<nome-kebab>` (`billing.folio-payment`,
 * `billing.folio-deposit`). Quem inicia a intenção a define e a lê de volta no evento.
 */
@JvmInline
value class PaymentPurpose(
    val value: String,
) {
    init {
        require(OPAQUE_CODE.matches(value)) { "Finalidade fora do formato: $value" }
    }

    override fun toString(): String = value
}

/** O destino do pagamento, opaco para o `payments`: o tipo (`<módulo>.<nome-kebab>`, como `billing.folio`) e o id. */
data class PaymentTarget(
    val type: String,
    val id: UUID,
) {
    init {
        require(OPAQUE_CODE.matches(type)) { "Tipo de destino fora do formato: $type" }
    }
}

private val OPAQUE_CODE = Regex("^[a-z][a-z0-9]*\\.[a-z][a-z0-9-]*$")

/** Situação de uma intenção: pendente no provedor, confirmada ou recusada (as duas últimas não mudam mais). */
enum class PaymentIntentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
}

/**
 * Uma intenção nova: a finalidade, o destino, o valor (positivo) e o provedor. [reference] é o identificador que quem
 * opera informou no registro manual (NSU, autorização ou o identificador do Pix), opcional; nunca dado de cartão.
 */
data class NewPaymentIntent(
    val purpose: PaymentPurpose,
    val target: PaymentTarget,
    val amount: Money,
    val provider: PaymentProviderCode,
    val reference: String? = null,
)

/** Uma intenção de pagamento. [providerReference] é a referência que o provedor devolveu (no manual, a informada). */
data class PaymentIntentSummary(
    val id: PaymentIntentId,
    val purpose: PaymentPurpose,
    val target: PaymentTarget,
    val amount: Money,
    val provider: PaymentProviderCode,
    val status: PaymentIntentStatus,
    val providerReference: String?,
    val createdAt: Instant,
    val succeededAt: Instant?,
)

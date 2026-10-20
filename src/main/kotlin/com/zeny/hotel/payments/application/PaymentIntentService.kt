package com.zeny.hotel.payments.application

import com.zeny.hotel.payments.NewPaymentIntent
import com.zeny.hotel.payments.PaymentIntentId
import com.zeny.hotel.payments.PaymentIntentSummary
import com.zeny.hotel.payments.PaymentSucceeded
import com.zeny.hotel.payments.PaymentsApi
import com.zeny.hotel.payments.domain.PaymentIntent
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.CurrentActor
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** As intenções de pagamento na transação corrente. */
interface PaymentIntentStore {
    fun intent(id: PaymentIntentId): PaymentIntent?

    fun add(intent: PaymentIntent)

    fun flush()
}

/** Ações e entidades da trilha do `payments`. */
object PaymentsAudit {
    const val PAYMENT_INTENT = "payments.payment-intent"
    const val START_INTENT = "payments.payment-intent.start"
}

/**
 * A [PaymentsApi] (H1 do M3): grava a intenção pendente, submete-a ao provedor e aplica o resultado; confirmada,
 * publica [PaymentSucceeded] na transação (o outbox o entrega depois do commit). Sem conferir permissão; auditada.
 */
@Service
class PaymentIntentService(
    private val store: PaymentIntentStore,
    private val providers: PaymentProviders,
    private val events: ApplicationEventPublisher,
    private val audit: AuditContext,
    private val currentActor: CurrentActor,
    private val clock: Clock,
) : PaymentsApi {
    @Audited(PaymentsAudit.START_INTENT)
    @Transactional
    override fun start(intent: NewPaymentIntent): PaymentIntentSummary {
        val provider = providers.of(intent.provider)
        val reference = PaymentIntent.reference(intent.reference)
        val created =
            PaymentIntent.start(
                intent.purpose,
                intent.target,
                intent.amount,
                intent.provider,
                currentActor.currentOrNull()?.userId,
                now(),
            )
        audit.created(PaymentsAudit.PAYMENT_INTENT, created.id.value) { created.auditState() }
        store.add(created)
        when (val outcome = provider.start(created, reference)) {
            is ProviderOutcome.Succeeded -> {
                if (created.markSucceeded(outcome.reference, now())) events.publishEvent(created.succeeded())
            }

            is ProviderOutcome.Pending -> {
                created.markPending(outcome.reference)
            }

            is ProviderOutcome.Failed -> {
                created.markFailed(outcome.reason, now())
            }
        }
        store.flush()
        return created.summary()
    }

    @Transactional(readOnly = true)
    override fun intent(id: PaymentIntentId): PaymentIntentSummary? = store.intent(id)?.summary()

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

internal fun PaymentIntent.summary() =
    PaymentIntentSummary(
        id,
        purpose,
        target,
        amount,
        provider,
        status,
        providerReference,
        createdAt,
        succeededAt,
    )

internal fun PaymentIntent.succeeded() =
    PaymentSucceeded(
        id.value,
        purpose.value,
        target.type,
        target.id,
        amount,
        provider.value,
        providerReference,
        checkNotNull(succeededAt),
    )

/** Retrato da trilha de uma intenção: ids, valor, provedor e situação (a referência, só se existe). */
internal fun PaymentIntent.auditState(): Map<String, Any?> =
    mapOf(
        "purpose" to purpose.value,
        "targetType" to target.type,
        "targetId" to target.id.toString(),
        "amount" to amount.amount.toPlainString(),
        "provider" to provider.value,
        "status" to status.name,
        "hasProviderReference" to (providerReference != null),
    )

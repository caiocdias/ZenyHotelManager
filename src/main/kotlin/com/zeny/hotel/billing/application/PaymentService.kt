package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.PaymentType
import com.zeny.hotel.billing.domain.Payment
import com.zeny.hotel.billing.domain.PaymentDraft
import com.zeny.hotel.billing.domain.PaymentReferences
import com.zeny.hotel.billing.domain.PaymentRefunds
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.payments.NewPaymentIntent
import com.zeny.hotel.payments.PaymentIntentStatus
import com.zeny.hotel.payments.PaymentProviderCode
import com.zeny.hotel.payments.PaymentPurpose
import com.zeny.hotel.payments.PaymentSucceeded
import com.zeny.hotel.payments.PaymentTarget
import com.zeny.hotel.payments.PaymentsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import com.zeny.hotel.shared.UserId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A finalidade e o destino das intenções de pagamento que o `billing` inicia (opacos para o `payments`). */
object BillingPaymentIntents {
    /** O destino: a conta. */
    const val FOLIO_TARGET = "billing.folio"

    /** Recebimento comum na conta. */
    val FOLIO_PAYMENT = PaymentPurpose("billing.folio-payment")

    /** Depósito (sinal antes da chegada, RF-CTA-07). */
    val FOLIO_DEPOSIT = PaymentPurpose("billing.folio-deposit")
}

/** Um recebimento como chega da API: a forma pelo nome (o `CITY_LEDGER` é recusado no domínio). */
data class PaymentInput(
    val method: String,
    val amount: Money,
    val tendered: Money? = null,
    val deposit: Boolean = false,
    val nsu: String? = null,
    val authorizationCode: String? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
)

/** Um pagamento ou reembolso lido; no recebimento, o total reembolsado e o que ainda é reembolsável. */
data class PaymentView(
    val id: PaymentId,
    val folioId: FolioId,
    val type: PaymentType,
    val method: PaymentMethod,
    val amount: Money,
    val tendered: Money?,
    val change: Money?,
    val deposit: Boolean,
    val nsu: String?,
    val authorizationCode: String?,
    val cardBrand: String?,
    val cardLast4: String?,
    val paymentIntentId: UUID?,
    val refundOfId: PaymentId?,
    val reason: String?,
    val cashierShiftId: CashierShiftId,
    val businessDate: LocalDate,
    val receivedAt: Instant,
    val receivedBy: UserId,
    val approvalId: UUID?,
    val approvedBy: UserId?,
    val refunded: Money?,
    val refundable: Money?,
)

/**
 * Pagamentos na conta (RF-CTA-06, 07, 10 e 15, RF-PAG-03, H1 e H13 do M3, M3 E08; decisões do usuário em 2026-10-19).
 *
 * - **Receber** (`billing.payments.receive`): no turno de caixa aberto do próprio usuário (sem turno,
 *   `422 CASHIER_SHIFT_REQUIRED`), travado antes da conta; a conta aberta e travada; o valor aplicado pode passar do
 *   saldo devedor (a conta fica credora); troco só no dinheiro (o valor entregue menos o aplicado; o esperado do turno
 *   soma só o aplicado); depósito por marcação explícita. O recebimento passa pela intenção de pagamento do `payments`
 *   (H1): o provedor `MANUAL` a confirma na hora, na mesma transação, e o `PaymentSucceeded` vai ao outbox; o
 *   pagamento imutável nasce dela ([Payment.paymentIntentId], único).
 * - **Reembolsar** (ação **sensível** `billing.payment.refund`, `billing.payments.refund` com o limite `maxAmount`
 *   sobre o total reembolsado do pagamento): exige antes `billing.folios.read`; sempre na forma do recebimento, no
 *   turno aberto de quem devolve; até o reembolsável (`422 REFUND_EXCEEDS_REFUNDABLE`, conferido com a conta travada,
 *   então dois reembolsos simultâneos nunca passam do pago) e antes do step-up.
 */
@Service
@Suppress("LongParameterList") // as portas que os pagamentos usam
class PaymentService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val ledger: PaymentLedger,
    private val cashier: CashierStore,
    private val payments: PaymentsApi,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
) {
    @Transactional(readOnly = true)
    fun list(
        folioId: FolioId,
        page: PageParams,
    ): CursorPage<PaymentView> {
        authorization.require(BillingPermissions.FOLIOS_READ)
        store.folio(folioId) ?: throw folioNotFound()
        val after = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        return CursorPage
            .fromRows(ledger.page(folioId, after, page.limit + 1), page.limit) {
                PageCursor.of(it.payment.receivedAt.toString(), it.payment.id.toString())
            }.map { it.view() }
    }

    @Transactional(readOnly = true)
    fun get(
        folioId: FolioId,
        id: PaymentId,
    ): PaymentView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        return (ledger.row(id)?.takeIf { it.payment.folioId == folioId } ?: throw paymentNotFound()).view()
    }

    /** Recebe na conta, no turno aberto do próprio usuário, pela intenção de pagamento `MANUAL` (H1). */
    @Audited(BillingAudit.RECEIVE_PAYMENT)
    @Transactional
    fun receive(
        folioId: FolioId,
        input: PaymentInput,
    ): PaymentView {
        authorization.require(BillingPermissions.PAYMENTS_RECEIVE)
        val draft =
            PaymentDraft.of(
                input.method,
                input.amount,
                input.tendered,
                input.deposit,
                input.nsu,
                input.authorizationCode,
                input.cardBrand,
                input.cardLast4,
            )
        // O turno antes da conta (a ordem das travas, E07): um fechamento simultâneo espera o recebimento.
        val shift = cashier.requireOpenShiftOf(currentActor.current().userId)
        val folio = store.folioForUpdate(folioId) ?: throw folioNotFound()
        folio.requireOpen()
        val intent =
            payments.start(
                NewPaymentIntent(
                    if (draft.deposit) BillingPaymentIntents.FOLIO_DEPOSIT else BillingPaymentIntents.FOLIO_PAYMENT,
                    PaymentTarget(BillingPaymentIntents.FOLIO_TARGET, folio.id.value),
                    draft.amount,
                    PaymentProviderCode.MANUAL,
                    draft.references.providerReference,
                ),
            )
        check(intent.status == PaymentIntentStatus.SUCCEEDED) { "Intenção manual ${intent.id} não confirmada" }
        val payment = Payment.receive(folio, draft, shift, intent.id.value, operations.stamp())
        record(payment)
        store.markChanged(folio)
        return checkNotNull(ledger.row(payment.id)).view()
    }

    /**
     * Reembolsa [amount] do recebimento [id] (RF-CTA-15), na mesma forma, no turno aberto de quem devolve, com o motivo
     * e os dados da devolução feita fora (NSU, autorização). Ação sensível com o limite `maxAmount` sobre o total
     * reembolsado do pagamento (os reembolsos já feitos, por qualquer usuário, mais este).
     */
    @Audited(BillingAudit.REFUND_PAYMENT)
    @Transactional
    @Suppress("LongParameterList") // os dados do reembolso
    fun refund(
        folioId: FolioId,
        id: PaymentId,
        amount: Money,
        reason: String?,
        nsu: String?,
        authorizationCode: String?,
        approvalToken: String?,
    ): PaymentView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        PaymentDraft.requireAmount("amount", amount)
        val text = Payment.reason(reason)
        val shift = cashier.requireOpenShiftOf(currentActor.current().userId)
        val folio = store.folioForUpdate(folioId) ?: throw folioNotFound()
        folio.requireOpen()
        val original = ledger.payment(id)?.takeIf { it.folioId == folioId } ?: throw paymentNotFound()
        val references = PaymentReferences.of(original.method, nsu, authorizationCode)
        val refunds = PaymentRefunds(original, ledger.refundsOf(id))
        val cumulative = refunds.requireRefundable(amount)
        val approval = authorize(id, cumulative, approvalToken)
        val refund = refunds.refund(folio, amount, text, references, shift, operations.stamp(), approval)
        record(refund)
        store.markChanged(folio)
        return checkNotNull(ledger.row(refund.id)).view()
    }

    private fun record(payment: Payment) {
        audit.created(BillingAudit.PAYMENT, payment.id.value) { payment.auditState() }
        ledger.add(payment)
    }

    private fun authorize(
        id: PaymentId,
        cumulative: Money,
        approvalToken: String?,
    ): PostingApproval? {
        val result =
            authorization.requireOrApproval(
                SensitiveAction(
                    BillingAudit.REFUND_PAYMENT,
                    BillingPermissions.PAYMENTS_REFUND,
                    "billing.payment:$id",
                    limit = LimitAmount(BillingPermissions.MAX_AMOUNT, cumulative.amount),
                ),
                approvalToken,
            )
        return (result as? SensitiveAuthorization.ByApproval)?.let { PostingApproval(it.approvalId, it.approverId) }
    }
}

/**
 * Escuta [PaymentSucceeded] do `payments` (H1 do M3): idempotente pela intenção. A intenção cujo pagamento já está na
 * conta (todo recebimento manual, registrado na mesma transação que a confirmou) é ignorada, inclusive na reentrega
 * do outbox; intenções de outros destinos também. Uma intenção da conta confirmada **sem** pagamento registrado só
 * existirá com os provedores assíncronos do M7 (que registram o pagamento aqui, com `INSERT … ON CONFLICT` no índice
 * único da intenção): até lá, é uma anomalia, e o listener falha para a publicação ficar `FAILED` e visível no
 * outbox (métrica e alerta de abandono), sem registrar nada.
 */
@Component
class PaymentSucceededListener(
    private val ledger: PaymentLedger,
) {
    @ApplicationModuleListener
    fun on(event: PaymentSucceeded) {
        if (event.targetType != BillingPaymentIntents.FOLIO_TARGET) return
        if (ledger.existsForIntent(event.intentId)) {
            log.debug("Intenção de pagamento {} já registrada na conta {}", event.intentId, event.targetId)
            return
        }
        error(
            "Intenção de pagamento ${event.intentId} confirmada sem pagamento na conta ${event.targetId}: o registro " +
                "por evento (provedores assíncronos) entra no M7",
        )
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PaymentSucceededListener::class.java)
    }
}

internal fun paymentNotFound() =
    NotFoundException(BillingErrorCodes.PAYMENT_NOT_FOUND, "Pagamento não encontrado nesta conta.")

internal fun PaymentRow.view() =
    PaymentView(
        payment.id,
        payment.folioId,
        payment.type,
        payment.method,
        payment.amount,
        payment.tendered,
        payment.change,
        payment.deposit,
        payment.references.nsu,
        payment.references.authorizationCode,
        payment.references.cardBrand,
        payment.references.cardLast4,
        payment.paymentIntentId,
        payment.refundOfId,
        payment.reason,
        payment.shiftId,
        payment.businessDate,
        payment.receivedAt,
        payment.receivedBy,
        payment.approval?.approvalId,
        payment.approval?.approvedBy,
        refunded,
        refunded?.let { payment.amount - it },
    )

/**
 * Retrato da trilha de um pagamento: ids, tipo, forma, valores, depósito, turno e aprovação; sem o motivo (texto livre,
 * H10) e sem os dados da transação (só se existem).
 */
internal fun Payment.auditState(): Map<String, Any?> =
    mapOf(
        "folioId" to folioId.toString(),
        "type" to type.name,
        "method" to method.name,
        "amount" to amount.amount.toPlainString(),
        "tendered" to tendered?.amount?.toPlainString(),
        "change" to change?.amount?.toPlainString(),
        "deposit" to deposit,
        "cashierShiftId" to shiftId.toString(),
        "paymentIntentId" to paymentIntentId?.toString(),
        "refundOfId" to refundOfId?.toString(),
        "businessDate" to businessDate.toString(),
        "approvalId" to approval?.approvalId?.toString(),
        "approvedBy" to approval?.approvedBy?.toString(),
        "hasNsu" to (references.nsu != null),
        "hasAuthorizationCode" to (references.authorizationCode != null),
        "hasCardData" to (references.cardBrand != null || references.cardLast4 != null),
    )

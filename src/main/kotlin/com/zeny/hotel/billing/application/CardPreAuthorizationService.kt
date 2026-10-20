package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.CardPreAuthorizationSummary
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.domain.BillingRules
import com.zeny.hotel.billing.domain.CardPreAuthorization
import com.zeny.hotel.billing.domain.Payment
import com.zeny.hotel.billing.domain.PaymentDraft
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.payments.NewPaymentIntent
import com.zeny.hotel.payments.PaymentIntentStatus
import com.zeny.hotel.payments.PaymentProviderCode
import com.zeny.hotel.payments.PaymentPurpose
import com.zeny.hotel.payments.PaymentTarget
import com.zeny.hotel.payments.PaymentsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Uma caução como chega da API para registro. */
data class PreAuthorizationInput(
    val amount: Money,
    val expiresAt: Instant,
    val nsu: String? = null,
    val authorizationCode: String? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
)

/** Uma caução lida, com a situação e o capturável calculados no instante da leitura. */
data class CardPreAuthorizationView(
    val id: CardPreAuthorizationId,
    val folioId: FolioId,
    val status: CardPreAuthorizationStatus,
    val amount: Money,
    val capturedAmount: Money,
    val capturableAmount: Money,
    val releasedAmount: Money?,
    val expiresAt: Instant,
    val nsu: String?,
    val authorizationCode: String,
    val cardBrand: String?,
    val cardLast4: String?,
    val businessDate: LocalDate,
    val registeredAt: Instant,
    val registeredBy: UserId,
    val releasedAt: Instant?,
    val releasedBy: UserId?,
    val captureIds: List<PaymentId>,
    val version: Long,
) {
    fun summary() =
        CardPreAuthorizationSummary(id, folioId, status, amount, capturedAmount, capturableAmount, expiresAt)
}

/** O resultado de uma captura: a caução depois dela e o recebimento que ela gerou. */
data class PreAuthorizationCapture(
    val preAuthorization: CardPreAuthorizationView,
    val payment: PaymentView,
)

/** A finalidade da intenção de pagamento de uma captura de caução (opaca para o `payments`). */
object BillingPreAuthorizationIntents {
    val FOLIO_CAPTURE = PaymentPurpose("billing.folio-capture")
}

/**
 * Cauções registradas manualmente (RF-CTA-08 no recorte manual, RN-03, RNF-10, H13 do M3, M3 E09; decisões do usuário
 * em 2026-10-19).
 *
 * - **Registrar** (`billing.payments.receive`, não sensível; sem turno de caixa): na conta aberta e travada; não muda o
 *   saldo nem a versão da conta.
 * - **Capturar** (`billing.payments.receive`): só no turno de caixa aberto do próprio usuário (`422
 *   CASHIER_SHIFT_REQUIRED`), travado antes da conta, que é travada antes da caução; parcial ou total, várias até o
 *   autorizado; cada captura é um recebimento no cartão de crédito pelo fluxo dos pagamentos (intenção `MANUAL` com a
 *   finalidade `billing.folio-capture`, `PaymentSucceeded` no outbox, pagamento imutável vinculado à caução), que
 *   entra no saldo da conta e no esperado do turno. Vencida `422 PRE_AUTHORIZATION_EXPIRED`; acima do que ainda está
 *   autorizado `422 CAPTURE_EXCEEDS_AUTHORIZED`; encerrada `409 PRE_AUTHORIZATION_SETTLED`.
 * - **Liberar** (`billing.payments.receive`; sem turno de caixa): registra a liberação da sobra e encerra a caução;
 *   vale também com a conta encerrada e depois da validade (decisão do usuário em 2026-10-20).
 * - **Consultar** (`billing.folios.read`): as cauções da conta e as que vencem num período.
 */
@Service
@Suppress("LongParameterList") // as portas que as cauções usam
class CardPreAuthorizationService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val preAuthorizations: CardPreAuthorizationStore,
    private val ledger: PaymentLedger,
    private val cashier: CashierStore,
    private val payments: PaymentsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        folioId: FolioId,
        page: PageParams,
    ): CursorPage<CardPreAuthorizationView> {
        authorization.require(BillingPermissions.FOLIOS_READ)
        store.folio(folioId) ?: throw folioNotFound()
        val now = clock.instant()
        return CursorPage
            .fromRows(preAuthorizations.page(folioId, page.decodedCursor()?.uuid(0), page.limit + 1), page.limit) {
                PageCursor.of(it.preAuthorization.id.toString())
            }.map { it.view(now) }
    }

    @Transactional(readOnly = true)
    fun get(
        folioId: FolioId,
        id: CardPreAuthorizationId,
    ): CardPreAuthorizationView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        return row(folioId, id).view(clock.instant())
    }

    /**
     * As cauções ainda não encerradas (nem capturadas por inteiro nem liberadas) que vencem de [from] a [to], datas
     * no fuso do hotel, inclusive (sem [from], a data operacional), em ordem de validade; as que já venceram no
     * período vêm com a situação `EXPIRED`.
     */
    @Transactional(readOnly = true)
    fun expiring(
        from: LocalDate?,
        to: LocalDate,
        page: PageParams,
    ): CursorPage<CardPreAuthorizationView> {
        authorization.require(BillingPermissions.FOLIOS_READ)
        val start = from ?: hotel.currentBusinessDate()
        if (to.isBefore(start)) {
            throw BillingRules.invalid(
                BillingErrorCodes.PRE_AUTHORIZATION_INVALID,
                "to",
                "O fim do período não pode ser antes do início.",
            )
        }
        val zone = hotel.timezone()
        val cursor = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        val now = clock.instant()
        val rows =
            preAuthorizations.expiring(
                start.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                cursor,
                page.limit + 1,
            )
        return CursorPage
            .fromRows(rows, page.limit) {
                PageCursor.of(it.preAuthorization.expiresAt.toString(), it.preAuthorization.id.toString())
            }.map { it.view(now) }
    }

    /** As cauções ainda não encerradas que vencem em [from] (inclusive) a [to] (exclusive), para a `BillingApi`. */
    @Transactional(readOnly = true)
    fun expiringBetween(
        from: Instant,
        to: Instant,
    ): List<CardPreAuthorizationSummary> {
        val now = clock.instant()
        return preAuthorizations.expiring(from, to, null, MAX_EXPIRING).map { it.view(now).summary() }
    }

    /** Registra na conta a caução feita na maquininha (sem turno de caixa; não muda o saldo). */
    @Audited(BillingAudit.REGISTER_PRE_AUTHORIZATION)
    @Transactional
    fun register(
        folioId: FolioId,
        input: PreAuthorizationInput,
    ): CardPreAuthorizationView {
        authorization.require(BillingPermissions.PAYMENTS_RECEIVE)
        val references =
            CardPreAuthorization.references(input.nsu, input.authorizationCode, input.cardBrand, input.cardLast4)
        val folio = store.folioForUpdate(folioId) ?: throw folioNotFound()
        val preAuthorization =
            CardPreAuthorization.register(folio, input.amount, input.expiresAt, references, operations.stamp())
        preAuthorizations.add(preAuthorization)
        audit.created(BillingAudit.CARD_PRE_AUTHORIZATION, preAuthorization.id.value) {
            preAuthorization.auditState()
        }
        return row(folioId, preAuthorization.id).view(preAuthorization.registeredAt)
    }

    /**
     * Captura [amount] da caução: um recebimento no cartão de crédito, no turno aberto de quem captura, com o NSU e a
     * autorização da captura (opcionais) e a bandeira e os últimos dígitos da caução.
     */
    @Audited(BillingAudit.CAPTURE_PRE_AUTHORIZATION)
    @Transactional
    @Suppress("LongParameterList") // os dados da captura
    fun capture(
        folioId: FolioId,
        id: CardPreAuthorizationId,
        amount: Money,
        nsu: String?,
        authorizationCode: String?,
    ): PreAuthorizationCapture {
        authorization.require(BillingPermissions.PAYMENTS_RECEIVE)
        CardPreAuthorization.requireAmount(amount)
        val captureReferences = CardPreAuthorization.references(nsu, authorizationCode)
        // As travas na ordem dos pagamentos (E08): o turno, a conta e então a caução.
        val shift = cashier.requireOpenShiftOf(currentActor.current().userId)
        val folio = store.folioForUpdate(folioId) ?: throw folioNotFound()
        folio.requireOpen()
        val before = lockedPreAuthorization(folioId, id)
        val stamp = operations.stamp()
        val after = before.capture(amount, stamp.postedAt)
        val references =
            captureReferences.copy(
                cardBrand = before.references.cardBrand,
                cardLast4 = before.references.cardLast4,
            )
        val intent =
            payments.start(
                NewPaymentIntent(
                    BillingPreAuthorizationIntents.FOLIO_CAPTURE,
                    PaymentTarget(BillingPaymentIntents.FOLIO_TARGET, folio.id.value),
                    amount,
                    PaymentProviderCode.MANUAL,
                    references.providerReference ?: before.references.providerReference,
                ),
            )
        check(intent.status == PaymentIntentStatus.SUCCEEDED) { "Intenção manual ${intent.id} não confirmada" }
        val payment =
            Payment.receive(
                folio,
                PaymentDraft(PaymentMethod.CREDIT_CARD, amount, null, false, references),
                shift,
                intent.id.value,
                stamp,
                before.id,
            )
        preAuthorizations.update(after)
        audit.record(BillingAudit.CARD_PRE_AUTHORIZATION, id.value, before.auditState(), after.auditState())
        audit.created(BillingAudit.PAYMENT, payment.id.value) { payment.auditState() }
        ledger.add(payment)
        store.markChanged(folio)
        return PreAuthorizationCapture(
            row(folioId, id).view(stamp.postedAt),
            checkNotNull(ledger.row(payment.id)).view(),
        )
    }

    /** Registra a liberação da sobra da caução (sem turno; vale com a conta encerrada e depois da validade). */
    @Audited(BillingAudit.RELEASE_PRE_AUTHORIZATION)
    @Transactional
    fun release(
        folioId: FolioId,
        id: CardPreAuthorizationId,
    ): CardPreAuthorizationView {
        authorization.require(BillingPermissions.PAYMENTS_RECEIVE)
        // A conta antes da caução, como na captura: uma captura e uma liberação simultâneas se enfileiram.
        store.folioForUpdate(folioId) ?: throw folioNotFound()
        val before = lockedPreAuthorization(folioId, id)
        val stamp = operations.stamp()
        val (after, release) = before.release(stamp)
        preAuthorizations.update(after)
        preAuthorizations.addRelease(release)
        audit.record(
            BillingAudit.CARD_PRE_AUTHORIZATION,
            id.value,
            before.auditState(),
            after.auditState() + ("releasedAmount" to release.amount.amount.toPlainString()),
        )
        return row(folioId, id).view(stamp.postedAt)
    }

    private fun lockedPreAuthorization(
        folioId: FolioId,
        id: CardPreAuthorizationId,
    ): CardPreAuthorization =
        preAuthorizations.forUpdate(id)?.takeIf { it.folioId == folioId } ?: throw preAuthorizationNotFound()

    private fun row(
        folioId: FolioId,
        id: CardPreAuthorizationId,
    ): CardPreAuthorizationRow =
        preAuthorizations.row(id)?.takeIf { it.preAuthorization.folioId == folioId }
            ?: throw preAuthorizationNotFound()

    private companion object {
        /** Teto da leitura da `BillingApi` (um hotel de até 30 UHs tem poucas cauções abertas). */
        const val MAX_EXPIRING = 1000
    }
}

internal fun preAuthorizationNotFound() =
    NotFoundException(BillingErrorCodes.PRE_AUTHORIZATION_NOT_FOUND, "Caução não encontrada nesta conta.")

internal fun CardPreAuthorizationRow.view(now: Instant): CardPreAuthorizationView =
    with(preAuthorization) {
        CardPreAuthorizationView(
            id,
            folioId,
            statusAt(now),
            amount,
            capturedAmount,
            capturableAt(now),
            release?.amount,
            expiresAt,
            references.nsu,
            checkNotNull(references.authorizationCode),
            references.cardBrand,
            references.cardLast4,
            businessDate,
            registeredAt,
            registeredBy,
            release?.releasedAt,
            release?.releasedBy,
            captureIds,
            version,
        )
    }

/**
 * Retrato da trilha de uma caução: ids, situação guardada, valores, validade e data; sem os dados da maquininha (só se
 * existem), como o pagamento.
 */
internal fun CardPreAuthorization.auditState(): Map<String, Any?> =
    mapOf(
        "folioId" to folioId.toString(),
        "status" to status.name,
        "amount" to amount.amount.toPlainString(),
        "capturedAmount" to capturedAmount.amount.toPlainString(),
        "expiresAt" to expiresAt.toString(),
        "businessDate" to businessDate.toString(),
        "hasNsu" to (references.nsu != null),
        "hasAuthorizationCode" to (references.authorizationCode != null),
        "hasCardData" to (references.cardBrand != null || references.cardLast4 != null),
    )

package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.domain.CardPreAuthorization
import com.zeny.hotel.billing.domain.Payment
import com.zeny.hotel.billing.domain.PreAuthorizationRelease
import com.zeny.hotel.shared.Money
import java.time.Instant
import java.util.UUID

/** Os pagamentos e reembolsos, só inseridos e lidos (RN-03, M3 E08). */
interface PaymentLedger {
    fun add(payment: Payment)

    fun payment(id: PaymentId): Payment?

    /** Os reembolsos do recebimento, em ordem de registro. */
    fun refundsOf(id: PaymentId): List<Payment>

    /** Se algum recebimento já nasceu da intenção de pagamento (o listener de `PaymentSucceeded` a ignora). */
    fun existsForIntent(intentId: UUID): Boolean

    /** Os pagamentos e reembolsos da conta em ordem de registro, depois da posição, com o total reembolsado. */
    fun page(
        folioId: FolioId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<PaymentRow>

    fun row(id: PaymentId): PaymentRow?

    /**
     * O que o turno recebeu por forma de pagamento: os recebimentos menos os reembolsos registrados nele (só as formas
     * com algum registro).
     */
    fun shiftTotals(shiftId: CashierShiftId): Map<PaymentMethod, Money>
}

/** Um pagamento com o total já reembolsado dele (só no recebimento; nulo no reembolso). */
data class PaymentRow(
    val payment: Payment,
    val refunded: Money?,
)

/**
 * As cauções (M3 E09): a linha mutável de cada uma (situação e capturado), lida e travada, e a liberação, só inserida.
 * As capturas ficam em `billing.payment` ([PaymentLedger]).
 */
interface CardPreAuthorizationStore {
    fun add(preAuthorization: CardPreAuthorization)

    /** A caução com a linha travada (`SELECT … FOR UPDATE`): capturas e liberação da caução se enfileiram. */
    fun forUpdate(id: CardPreAuthorizationId): CardPreAuthorization?

    /** Grava a situação, o capturado e a versão nova (a versão gravada precisa ser a anterior a esta). */
    fun update(preAuthorization: CardPreAuthorization)

    fun addRelease(release: PreAuthorizationRelease)

    /**
     * As cauções da conta ainda capturáveis pela situação guardada (`ACTIVE`, `PARTIALLY_CAPTURED`; as vencidas
     * também, que só a leitura reconhece), em ordem de registro: o encerramento da conta confere (M3 E10).
     */
    fun unsettledOf(folioId: FolioId): List<CardPreAuthorization>

    /** A caução com a liberação e as capturas; `null` se não existe. */
    fun row(id: CardPreAuthorizationId): CardPreAuthorizationRow?

    /** As cauções da conta em ordem de registro (id UUIDv7), depois de [afterId]. */
    fun page(
        folioId: FolioId,
        afterId: UUID?,
        limit: Int,
    ): List<CardPreAuthorizationRow>

    /**
     * As cauções ainda capturáveis pela situação guardada (`ACTIVE`, `PARTIALLY_CAPTURED`; as vencidas também, que só
     * a leitura reconhece) com a validade em [from] (inclusive) a [to] (exclusive), em ordem de validade e id, depois
     * da posição.
     */
    fun expiring(
        from: Instant,
        to: Instant,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<CardPreAuthorizationRow>
}

/** Uma caução com a liberação (se houve) e os ids dos recebimentos das capturas, em ordem de registro. */
data class CardPreAuthorizationRow(
    val preAuthorization: CardPreAuthorization,
    val release: PreAuthorizationRelease?,
    val captureIds: List<PaymentId>,
)

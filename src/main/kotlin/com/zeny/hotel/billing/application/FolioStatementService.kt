package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.CardPreAuthorizationId
import com.zeny.hotel.billing.CardPreAuthorizationStatus
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.PaymentType
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.domain.FolioReopening
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Para quem é o extrato (RF-CTA-12, M3 E10; decisão do usuário em 2026-10-20): [INTERNAL] para quem opera, com tudo;
 * [GUEST], a prévia para o hóspede, sem usuários, motivos, aprovações, NSU, autorização nem outros dados internos
 * (turno, roteamento, contas de origem e destino das transferências), mas com todas as linhas (estornos, ajustes,
 * transferências, taxa de serviço e pagamentos), para a soma bater com o saldo.
 */
enum class StatementAudience {
    INTERNAL,
    GUEST,
}

/** O tipo de uma linha do extrato: um lançamento ou um pagamento (recebimento ou reembolso). */
enum class StatementLineKind {
    POSTING,
    PAYMENT,
}

/**
 * O extrato da conta (RF-CTA-12, parte de dados; o PDF é do M5): as linhas agrupadas por data operacional, em ordem,
 * com os totais. [balance] = [chargesTotal] − [paymentsTotal] = a soma de [StatementLine.amount] de todas as linhas
 * = o saldo da conta, exatamente (lido num só retrato do banco).
 */
data class FolioStatement(
    val folio: FolioView,
    val audience: StatementAudience,
    val generatedAt: Instant,
    val days: List<StatementDay>,
    val chargesTotal: Money,
    val paymentsTotal: Money,
    val balance: Money,
    val preAuthorizations: List<StatementPreAuthorization>,
    val reopenings: List<FolioReopeningView>?,
)

/** Uma reabertura da conta lida (M3 E10), com o motivo e a aprovação: só no extrato interno. */
data class FolioReopeningView(
    val id: UUID,
    val reason: String,
    val closedOn: LocalDate,
    val closedAt: Instant,
    val businessDate: LocalDate,
    val reopenedAt: Instant,
    val reopenedBy: UserId,
    val approvalId: UUID?,
    val approvedBy: UserId?,
)

/**
 * As linhas de uma data operacional: [charges] (a soma dos lançamentos), [payments] (o recebido líquido: recebimentos
 * menos reembolsos) e [balanceAtEnd] (o saldo acumulado até o fim do dia).
 */
data class StatementDay(
    val businessDate: LocalDate,
    val lines: List<StatementLine>,
    val charges: Money,
    val payments: Money,
    val balanceAtEnd: Money,
)

/**
 * Uma linha do extrato. [amount] é o efeito no saldo: o valor do lançamento com o sinal dele (débito positivo; crédito
 * e correção de débito negativos), o recebimento negativo e o reembolso positivo. [internal] só no extrato
 * [StatementAudience.INTERNAL].
 */
data class StatementLine(
    val kind: StatementLineKind,
    val id: UUID,
    val postingType: PostingType?,
    val paymentType: PaymentType?,
    val businessDate: LocalDate,
    val at: Instant,
    val description: String,
    val amount: Money,
    val transactionCode: String? = null,
    val transactionCodeName: String? = null,
    val revenueGroup: RevenueGroup? = null,
    val quantity: BigDecimal? = null,
    val unitPrice: Money? = null,
    val serviceChargeRate: Percent? = null,
    val correctsPostingId: UUID? = null,
    val serviceChargeForPostingId: UUID? = null,
    val method: PaymentMethod? = null,
    val deposit: Boolean? = null,
    val tendered: Money? = null,
    val change: Money? = null,
    val cardBrand: String? = null,
    val cardLast4: String? = null,
    val refundOfPaymentId: UUID? = null,
    val preAuthorizationId: CardPreAuthorizationId? = null,
    val internal: StatementLineInternal? = null,
)

/** O que só quem opera vê de uma linha: quem, motivo, aprovação, dados da maquininha, turno e vínculos entre contas. */
data class StatementLineInternal(
    val user: UserId?,
    val reason: String?,
    val approvalId: UUID?,
    val approvedBy: UserId?,
    val source: PostingSource? = null,
    val routedFromFolioId: FolioId? = null,
    val routingRuleId: RoutingRuleId? = null,
    val transferredFromPostingId: UUID? = null,
    val transferredFromFolioId: FolioId? = null,
    val transferredToPostingId: UUID? = null,
    val transferredToFolioId: FolioId? = null,
    val triggeredByPostingId: UUID? = null,
    val nsu: String? = null,
    val authorizationCode: String? = null,
    val cashierShiftId: CashierShiftId? = null,
    val paymentIntentId: UUID? = null,
)

/**
 * Uma caução da conta no extrato (informativa: não entra no saldo; as capturas aparecem como pagamentos), com a
 * situação calculada agora. NSU, autorização e quem registrou ou liberou só no extrato interno.
 */
data class StatementPreAuthorization(
    val id: CardPreAuthorizationId,
    val status: CardPreAuthorizationStatus,
    val amount: Money,
    val capturedAmount: Money,
    val releasedAmount: Money?,
    val expiresAt: Instant,
    val businessDate: LocalDate,
    val cardBrand: String?,
    val cardLast4: String?,
    val nsu: String?,
    val authorizationCode: String?,
    val registeredBy: UserId?,
    val releasedBy: UserId?,
)

/**
 * O extrato da conta em JSON (RF-CTA-12, parte de dados, M3 E10; decisões do usuário em 2026-10-20): exige
 * `billing.folios.read`. Lê a conta, os lançamentos, os pagamentos, as cauções e as reaberturas num só retrato do
 * banco (`REPEATABLE READ`), então a soma das linhas é exatamente o saldo da conta, mesmo com escritas simultâneas.
 */
@Service
@Suppress("LongParameterList") // as portas que o extrato lê
class FolioStatementService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val postings: PostingLedger,
    private val payments: PaymentLedger,
    private val preAuthorizations: CardPreAuthorizationStore,
    private val reopenings: FolioReopenings,
    private val authorization: Authorization,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun statement(
        id: FolioId,
        audience: StatementAudience,
    ): FolioStatement {
        authorization.require(BillingPermissions.FOLIOS_READ)
        val folio = operations.view(store.folio(id) ?: throw folioNotFound())
        val internal = audience == StatementAudience.INTERNAL
        val postingRows = allPostings(id)
        val codes =
            store
                .transactionCodes(postingRows.map { it.posting.transactionCodeId.value }.distinct())
                .associateBy { it.id }
        val lines =
            (
                postingRows.map { it.line(codes.getValue(it.posting.transactionCodeId), internal) } +
                    allPayments(id).map { it.line(internal) }
            ).sortedWith(compareBy<StatementLine>({ it.businessDate }, { it.at }, { it.id }))
        val days = days(lines)
        val chargesTotal = lines.filter { it.kind == StatementLineKind.POSTING }.sumOf()
        val paymentsTotal = -lines.filter { it.kind == StatementLineKind.PAYMENT }.sumOf()
        val balance = chargesTotal - paymentsTotal
        check(balance == folio.balance) { "Extrato da conta $id não soma o saldo: $balance ≠ ${folio.balance}" }
        val now = clock.instant()
        return FolioStatement(
            folio,
            audience,
            now,
            days,
            chargesTotal,
            paymentsTotal,
            balance,
            allPreAuthorizations(id).map { it.statementEntry(now, internal) },
            if (internal) reopenings.of(id).map { it.view() } else null,
        )
    }

    private fun days(lines: List<StatementLine>): List<StatementDay> {
        var running = Money.ZERO
        return lines.groupBy { it.businessDate }.map { (date, dayLines) ->
            val charges = dayLines.filter { it.kind == StatementLineKind.POSTING }.sumOf()
            val received = -dayLines.filter { it.kind == StatementLineKind.PAYMENT }.sumOf()
            running += charges - received
            StatementDay(date, dayLines, charges, received, running)
        }
    }

    private fun allPostings(id: FolioId): List<PostingRow> =
        pages { after: PostingRow? ->
            postings.page(id, after?.let { it.posting.postedAt to it.posting.id.value }, PAGE)
        }

    private fun allPayments(id: FolioId): List<PaymentRow> =
        pages { after: PaymentRow? ->
            payments.page(id, after?.let { it.payment.receivedAt to it.payment.id.value }, PAGE)
        }

    private fun allPreAuthorizations(id: FolioId): List<CardPreAuthorizationRow> =
        pages { after: CardPreAuthorizationRow? ->
            preAuthorizations.page(id, after?.preAuthorization?.id?.value, PAGE)
        }

    private companion object {
        const val PAGE = 500

        /** Lê todas as páginas: cada uma depois da última linha da anterior. */
        fun <T> pages(next: (T?) -> List<T>): List<T> {
            val rows = mutableListOf<T>()
            do {
                val page = next(rows.lastOrNull())
                rows += page
            } while (page.size == PAGE)
            return rows
        }

        fun List<StatementLine>.sumOf(): Money = fold(Money.ZERO) { sum, line -> sum + line.amount }
    }
}

private fun PostingRow.line(
    code: TransactionCode,
    internal: Boolean,
): StatementLine =
    with(posting) {
        StatementLine(
            kind = StatementLineKind.POSTING,
            id = id.value,
            postingType = type,
            paymentType = null,
            businessDate = businessDate,
            at = postedAt,
            description = description,
            amount = amount,
            transactionCode = code.code,
            transactionCodeName = code.name,
            revenueGroup = code.revenueGroup,
            quantity = quantity,
            unitPrice = unitPrice,
            serviceChargeRate = serviceCharge?.rate,
            correctsPostingId = correctsId?.value,
            serviceChargeForPostingId = serviceCharge?.itemId?.value,
            internal =
                if (internal) {
                    StatementLineInternal(
                        user = postedBy,
                        reason = reason,
                        approvalId = approval?.approvalId,
                        approvedBy = approval?.approvedBy,
                        source = source,
                        routedFromFolioId = routing?.fromFolioId,
                        routingRuleId = routing?.ruleId,
                        transferredFromPostingId = transferredFromId?.value,
                        transferredFromFolioId = transferredFromFolioId,
                        transferredToPostingId = transferredToId?.value,
                        transferredToFolioId = transferredToFolioId,
                        triggeredByPostingId = triggeredById?.value,
                    )
                } else {
                    null
                },
        )
    }

private fun PaymentRow.line(internal: Boolean): StatementLine =
    with(payment) {
        StatementLine(
            kind = StatementLineKind.PAYMENT,
            id = id.value,
            postingType = null,
            paymentType = type,
            businessDate = businessDate,
            at = receivedAt,
            description = paymentDescription(type, method, deposit, preAuthorizationId != null),
            amount = if (type == PaymentType.REFUND) amount else -amount,
            method = method,
            deposit = deposit,
            tendered = tendered,
            change = change,
            cardBrand = references.cardBrand,
            cardLast4 = references.cardLast4,
            refundOfPaymentId = refundOfId?.value,
            preAuthorizationId = preAuthorizationId,
            internal =
                if (internal) {
                    StatementLineInternal(
                        user = receivedBy,
                        reason = reason,
                        approvalId = approval?.approvalId,
                        approvedBy = approval?.approvedBy,
                        nsu = references.nsu,
                        authorizationCode = references.authorizationCode,
                        cashierShiftId = shiftId,
                        paymentIntentId = paymentIntentId,
                    )
                } else {
                    null
                },
        )
    }

private fun FolioReopening.view() =
    FolioReopeningView(
        id,
        reason,
        closedOn,
        closedAt,
        businessDate,
        reopenedAt,
        reopenedBy,
        approval?.approvalId,
        approval?.approvedBy,
    )

/** "Pagamento — Dinheiro", "Depósito — Pix", "Captura de caução — Cartão de crédito", "Reembolso — Pix". */
private fun paymentDescription(
    type: PaymentType,
    method: PaymentMethod,
    deposit: Boolean,
    capture: Boolean,
): String {
    val what =
        when {
            type == PaymentType.REFUND && deposit -> "Reembolso de depósito"
            type == PaymentType.REFUND -> "Reembolso"
            capture -> "Captura de caução"
            deposit -> "Depósito"
            else -> "Pagamento"
        }
    return "$what — ${method.label}"
}

private fun CardPreAuthorizationRow.statementEntry(
    now: Instant,
    internal: Boolean,
): StatementPreAuthorization =
    with(preAuthorization) {
        StatementPreAuthorization(
            id = id,
            status = statusAt(now),
            amount = amount,
            capturedAmount = capturedAmount,
            releasedAmount = release?.amount,
            expiresAt = expiresAt,
            businessDate = businessDate,
            cardBrand = references.cardBrand,
            cardLast4 = references.cardLast4,
            nsu = if (internal) references.nsu else null,
            authorizationCode = if (internal) references.authorizationCode else null,
            registeredBy = if (internal) registeredBy else null,
            releasedBy = if (internal) release?.releasedBy else null,
        )
    }

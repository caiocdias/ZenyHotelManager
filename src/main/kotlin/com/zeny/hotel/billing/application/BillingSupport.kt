package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwner
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.FolioSummary
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingSummary
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.billing.TransactionCodeSummary
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.RoutingCriteria
import com.zeny.hotel.billing.domain.RoutingRule
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.UserId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** As permissões do módulo no catálogo. */
@Configuration(proxyBeanMethods = false)
class BillingPermissionsConfiguration {
    @Bean
    fun billingPermissions(): ModulePermissions = BillingPermissions
}

/**
 * Ações e entidades da trilha do `billing`. Os retratos (H10) não levam texto livre (descrição do lançamento, motivo
 * da correção): só ids, tipos, valores, datas e a situação. O texto fica no próprio lançamento, imutável.
 */
object BillingAudit {
    const val TRANSACTION_CODE = "billing.transaction-code"
    const val FOLIO = "billing.folio"
    const val POSTING = "billing.posting"
    const val ROUTING_RULE = "billing.routing-rule"

    const val CREATE_TRANSACTION_CODE = "billing.transaction-code.create"
    const val UPDATE_TRANSACTION_CODE = "billing.transaction-code.update"
    const val DEACTIVATE_TRANSACTION_CODE = "billing.transaction-code.deactivate"
    const val REACTIVATE_TRANSACTION_CODE = "billing.transaction-code.reactivate"
    const val OPEN_FOLIO = "billing.folio.open"
    const val UPDATE_FOLIO = "billing.folio.update"
    const val CREATE_POSTING = "billing.posting.create"
    const val REVERSE_POSTING = "billing.posting.reverse"
    const val ADJUST_POSTING = "billing.posting.adjust"
    const val TRANSFER_POSTINGS = "billing.posting.transfer"
    const val SPLIT_POSTING = "billing.posting.split"
    const val REMOVE_SERVICE_CHARGE = "billing.posting.remove-service-charge"
    const val CREATE_ROUTING_RULE = "billing.routing-rule.create"
    const val UPDATE_ROUTING_RULE = "billing.routing-rule.update"
    const val DEACTIVATE_ROUTING_RULE = "billing.routing-rule.deactivate"
    const val REACTIVATE_ROUTING_RULE = "billing.routing-rule.reactivate"
}

/** Um código de lançamento lido. */
data class TransactionCodeView(
    val id: TransactionCodeId,
    val key: TransactionCodeKey?,
    val code: String,
    val name: String,
    val kind: TransactionCodeKind,
    val revenueGroup: RevenueGroup,
    val serviceChargeApplies: Boolean,
    val serviceChargeRate: Percent?,
    val fiscalClassification: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    fun summary() =
        TransactionCodeSummary(
            id,
            key,
            code,
            name,
            kind,
            revenueGroup,
            serviceChargeApplies,
            serviceChargeRate,
            active,
        )
}

/** Uma conta lida, com o saldo. */
data class FolioView(
    val id: FolioId,
    val number: DocumentNumber,
    val ownerType: FolioOwnerType,
    val ownerId: UUID,
    val payerCompanyId: CompanyId?,
    val creditLimit: Money?,
    val status: FolioStatus,
    val balance: Money,
    val openedOn: LocalDate,
    val openedAt: Instant,
    val openedBy: UserId?,
    val updatedAt: Instant,
    val version: Long,
) {
    /** Saldo acima do limite de crédito (sem limite, nunca). */
    val creditLimitExceeded: Boolean get() = creditLimit?.let { balance > it } ?: false

    fun summary() =
        FolioSummary(
            id,
            number,
            FolioOwner(ownerType, ownerId),
            payerCompanyId,
            creditLimit,
            status,
            openedOn,
            balance,
        )
}

/**
 * Um lançamento lido, com o estorno que o corrigiu e o que ainda resta (só no normal, na entrada e na taxa); a taxa de
 * serviço com o item e o percentual, e o item com a taxa dele nesta conta (M3 E06).
 */
data class PostingView(
    val id: PostingId,
    val folioId: FolioId,
    val transactionCodeId: TransactionCodeId,
    val type: PostingType,
    val quantity: BigDecimal,
    val unitPrice: Money,
    val amount: Money,
    val description: String,
    val source: PostingSource,
    val businessDate: LocalDate,
    val postedAt: Instant,
    val postedBy: UserId?,
    val correctsId: PostingId?,
    val reason: String?,
    val approvalId: UUID?,
    val approvedBy: UserId?,
    val reversedBy: PostingId?,
    val remaining: Money?,
    val routedFromFolioId: FolioId? = null,
    val routingRuleId: RoutingRuleId? = null,
    val transferredFromId: PostingId? = null,
    val transferredFromFolioId: FolioId? = null,
    val transferredToId: PostingId? = null,
    val transferredToFolioId: FolioId? = null,
    val serviceChargeForId: PostingId? = null,
    val serviceChargeRate: Percent? = null,
    val serviceChargeId: PostingId? = null,
    val triggeredById: PostingId? = null,
)

internal fun TransactionCode.view() =
    TransactionCodeView(
        id,
        key,
        code,
        name,
        kind,
        revenueGroup,
        serviceChargeApplies,
        serviceChargeRate,
        fiscalClassification,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

internal fun Folio.view(balance: Money) =
    FolioView(
        id,
        number,
        ownerType,
        ownerId,
        payerCompanyId,
        creditLimit,
        status,
        balance,
        openedOn,
        openedAt,
        openedBy,
        updatedAt,
        checkNotNull(version),
    )

internal fun PostingRow.view() =
    PostingView(
        posting.id,
        posting.folioId,
        posting.transactionCodeId,
        posting.type,
        posting.quantity,
        posting.unitPrice,
        posting.amount,
        posting.description,
        posting.source,
        posting.businessDate,
        posting.postedAt,
        posting.postedBy,
        posting.correctsId,
        posting.reason,
        posting.approval?.approvalId,
        posting.approval?.approvedBy,
        reversedBy,
        remaining,
        posting.routing?.fromFolioId,
        posting.routing?.ruleId,
        posting.transferredFromId,
        transferredFromFolioId,
        transferredToId,
        transferredToFolioId,
        posting.serviceCharge?.itemId,
        posting.serviceCharge?.rate,
        serviceChargeId,
        posting.triggeredById,
    )

internal fun Posting.summary(serviceCharge: Posting? = null): PostingSummary =
    PostingSummary(
        id,
        folioId,
        transactionCodeId,
        type,
        quantity,
        unitPrice,
        amount,
        source,
        businessDate,
        postedAt,
        postedBy,
        correctsId,
        routing?.fromFolioId,
        routing?.ruleId,
        transferredFromId,
        this.serviceCharge?.itemId,
        this.serviceCharge?.rate,
        serviceCharge?.summary(),
    )

/** Retrato da trilha de um código de lançamento. */
internal fun TransactionCode.auditState(): Map<String, Any?> =
    mapOf(
        "key" to key?.name,
        "code" to code,
        "name" to name,
        "kind" to kind.name,
        "revenueGroup" to revenueGroup.name,
        "serviceChargeApplies" to serviceChargeApplies,
        "serviceChargeRate" to serviceChargeRate?.toString(),
        "fiscalClassification" to fiscalClassification,
        "active" to active,
    )

/** Retrato da trilha de uma conta: só ids, situação e valores (H10). */
internal fun Folio.auditState(): Map<String, Any?> =
    mapOf(
        "number" to number.toString(),
        "ownerType" to ownerType.name,
        "ownerId" to ownerId.toString(),
        "payerCompanyId" to payerCompanyId?.toString(),
        "creditLimit" to creditLimit?.amount?.toPlainString(),
        "status" to status.name,
        "openedOn" to openedOn.toString(),
    )

/** Retrato da trilha de um lançamento: sem a descrição nem o motivo (texto livre, H10). */
internal fun Posting.auditState(): Map<String, Any?> =
    mapOf(
        "folioId" to folioId.toString(),
        "transactionCodeId" to transactionCodeId.toString(),
        "type" to type.name,
        "quantity" to quantity.toPlainString(),
        "unitPrice" to unitPrice.amount.toPlainString(),
        "amount" to amount.amount.toPlainString(),
        "source" to source.name,
        "businessDate" to businessDate.toString(),
        "correctsId" to correctsId?.toString(),
        "approvalId" to approval?.approvalId?.toString(),
        "approvedBy" to approval?.approvedBy?.toString(),
        "routedFromFolioId" to routing?.fromFolioId?.toString(),
        "routingRuleId" to routing?.ruleId?.toString(),
        "transferredFromId" to transferredFromId?.toString(),
        "serviceChargeForId" to serviceCharge?.itemId?.toString(),
        "serviceChargeRate" to serviceCharge?.rate?.toString(),
        "triggeredById" to triggeredById?.toString(),
    )

internal fun folioNotFound() = NotFoundException(BillingErrorCodes.FOLIO_NOT_FOUND, "Conta não encontrada.")

internal fun postingNotFound() =
    NotFoundException(BillingErrorCodes.POSTING_NOT_FOUND, "Lançamento não encontrado nesta conta.")

internal fun transactionCodeNotFound() =
    NotFoundException(BillingErrorCodes.TRANSACTION_CODE_NOT_FOUND, "Código de lançamento não encontrado.")

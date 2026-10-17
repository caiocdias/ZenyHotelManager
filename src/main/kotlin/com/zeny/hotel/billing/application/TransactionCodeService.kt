package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.billing.domain.TransactionCode
import com.zeny.hotel.billing.domain.TransactionCodeDetails
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Os dados editáveis de um código de lançamento. */
data class TransactionCodeCommand(
    val code: String,
    val name: String,
    val serviceChargeApplies: Boolean,
    val serviceChargeRate: Percent?,
    val fiscalClassification: String?,
) {
    internal fun details() =
        TransactionCodeDetails(code, name, serviceChargeApplies, serviceChargeRate, fiscalClassification)
}

/** Um código novo: o tipo e o grupo de receita (fixos depois) e os dados editáveis. */
data class NewTransactionCodeCommand(
    val kind: TransactionCodeKind,
    val revenueGroup: RevenueGroup,
    val details: TransactionCodeCommand,
)

/**
 * Cadastro de códigos de lançamento (RF-CTA-02). Consultar exige `billing.codes.read`; escrever,
 * `billing.codes.manage`. O código curto é único (`409 TRANSACTION_CODE_ALREADY_IN_USE`, inclusive na corrida); o
 * tipo e o grupo de receita não mudam depois da criação; nada é apagado, só desativado (o lançamento num código
 * desativado é recusado).
 */
@Service
class TransactionCodeService(
    private val store: BillingStore,
    private val directory: BillingDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        filter: TransactionCodeFilter,
        page: PageParams,
    ): CursorPage<TransactionCodeView> {
        authorization.require(BillingPermissions.CODES_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.transactionCodes(filter, after, page.limit + 1)
        val cursorPage = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        val codes = store.transactionCodes(cursorPage.items.map { it.second }).map { it.view() }
        return CursorPage(codes, cursorPage.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: TransactionCodeId): TransactionCodeView {
        authorization.require(BillingPermissions.CODES_READ)
        return find(id).view()
    }

    @Audited(BillingAudit.CREATE_TRANSACTION_CODE)
    @Transactional
    fun create(command: NewTransactionCodeCommand): TransactionCodeView {
        authorization.require(BillingPermissions.CODES_MANAGE)
        val code =
            TransactionCode.create(
                command.kind,
                command.revenueGroup,
                command.details.details(),
                clock.instant(),
            )
        audit.created(BillingAudit.TRANSACTION_CODE, code.id.value) { code.auditState() }
        if (!store.tryAdd(code)) throw codeInUse()
        return code.view()
    }

    @Audited(BillingAudit.UPDATE_TRANSACTION_CODE)
    @Transactional
    fun update(
        id: TransactionCodeId,
        expectedVersion: Long,
        command: TransactionCodeCommand,
    ): TransactionCodeView {
        authorization.require(BillingPermissions.CODES_MANAGE)
        val code = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(code.version))
        audit.track(BillingAudit.TRANSACTION_CODE, id.value) { code.auditState() }
        if (code.update(command.details(), clock.instant()) && !store.tryFlushCode()) throw codeInUse()
        return code.view()
    }

    @Audited(BillingAudit.DEACTIVATE_TRANSACTION_CODE)
    @Transactional
    fun deactivate(id: TransactionCodeId): TransactionCodeView {
        authorization.require(BillingPermissions.CODES_MANAGE)
        val code = find(id)
        audit.track(BillingAudit.TRANSACTION_CODE, id.value) { code.auditState() }
        if (code.deactivate(clock.instant())) store.flush()
        return code.view()
    }

    @Audited(BillingAudit.REACTIVATE_TRANSACTION_CODE)
    @Transactional
    fun reactivate(id: TransactionCodeId): TransactionCodeView {
        authorization.require(BillingPermissions.CODES_MANAGE)
        val code = find(id)
        audit.track(BillingAudit.TRANSACTION_CODE, id.value) { code.auditState() }
        if (code.reactivate(clock.instant())) store.flush()
        return code.view()
    }

    private fun find(id: TransactionCodeId): TransactionCode =
        store.transactionCode(id) ?: throw transactionCodeNotFound()

    private fun codeInUse() =
        ConflictException(
            BillingErrorCodes.TRANSACTION_CODE_ALREADY_IN_USE,
            "Já existe um código de lançamento com este código; se estiver desativado, reative-o.",
        )
}

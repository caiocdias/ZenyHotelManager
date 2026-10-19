package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.PostingId
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.domain.CorrectionLimit
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.Posting
import com.zeny.hotel.billing.domain.PostingApproval
import com.zeny.hotel.billing.domain.PostingCorrections
import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock

/**
 * Contas pela API (RF-CTA-01): consultar exige `billing.folios.read`; abrir e trocar o pagador e o limite de crédito,
 * `billing.folios.manage`. O dono não muda depois da abertura; a conta nunca é apagada.
 */
@Service
@Suppress("LongParameterList") // as portas que o cadastro de contas usa, injetadas pelo Spring
class FolioService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val directory: BillingDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        filter: FolioFilter,
        page: PageParams,
    ): CursorPage<FolioView> {
        authorization.require(BillingPermissions.FOLIOS_READ)
        val ids = directory.folios(filter, page.decodedCursor()?.uuid(0), page.limit + 1)
        val cursorPage = CursorPage.fromRows(ids, page.limit) { PageCursor.of(it.toString()) }
        val folios = store.folios(cursorPage.items)
        val balances = ledger.balances(folios.map { it.id })
        return CursorPage(folios.map { it.view(balances[it.id] ?: Money.ZERO) }, cursorPage.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: FolioId): FolioView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        return operations.view(store.folio(id) ?: throw folioNotFound())
    }

    @Audited(BillingAudit.OPEN_FOLIO)
    @Transactional
    fun open(
        owner: FolioOwnerInput,
        payerCompanyId: CompanyId?,
        creditLimit: Money?,
    ): FolioView {
        authorization.require(BillingPermissions.FOLIOS_MANAGE)
        return operations.openFolio(owner, payerCompanyId, creditLimit)
    }

    @Audited(BillingAudit.UPDATE_FOLIO)
    @Transactional
    fun update(
        id: FolioId,
        expectedVersion: Long,
        payerCompanyId: CompanyId?,
        creditLimit: Money?,
    ): FolioView {
        authorization.require(BillingPermissions.FOLIOS_MANAGE)
        val folio = store.folioForUpdate(id) ?: throw folioNotFound()
        requireCurrentVersion(expectedVersion, checkNotNull(folio.version))
        operations.requirePayer(folio, payerCompanyId)
        audit.track(BillingAudit.FOLIO, id.value) { folio.auditState() }
        if (folio.update(payerCompanyId, creditLimit, clock.instant()).isNotEmpty()) store.flush()
        return operations.view(folio)
    }
}

/**
 * Lançamentos pela API (RF-CTA-02 e 03, RF-ACS-06 a 08): consultar exige `billing.folios.read`; lançar,
 * `billing.postings.create`. Estornar e ajustar exigem antes `billing.folios.read` (sem ela, `403 PERMISSION_DENIED`,
 * antes de travar ou ler a conta: quem não tem acesso ao `billing` não descobre nada dela) e são **sensíveis**, com o
 * limite `maxAmount` sobre o **total das correções do lançamento** (as já feitas, por qualquer usuário, mais esta, em
 * valor absoluto), para o fracionamento não passar o teto: sem a permissão, ou acima do limite, pedem a aprovação de
 * um superior (`403 APPROVAL_REQUIRED`, concluída com `X-Approval-Token`). Desde a E05, o total soma toda a linhagem do
 * lançamento (o normal de origem e as entradas de transferência que descendem dele): dividir ou transferir antes de
 * estornar não passa o teto. As regras da correção (lançamento
 * corrigível, ainda não estornado, ajuste até o que resta) são conferidas com a conta travada e antes do step-up,
 * porque o total depende delas.
 */
@Service
class PostingService(
    private val operations: BillingOperations,
    private val store: BillingStore,
    private val ledger: PostingLedger,
    private val authorization: Authorization,
) {
    @Transactional(readOnly = true)
    fun list(
        folioId: FolioId,
        page: PageParams,
    ): CursorPage<PostingView> {
        authorization.require(BillingPermissions.FOLIOS_READ)
        store.folio(folioId) ?: throw folioNotFound()
        val after = page.decodedCursor()?.let { it.instant(0) to it.uuid(1) }
        val rows = ledger.page(folioId, after, page.limit + 1)
        return CursorPage
            .fromRows(rows, page.limit) { PageCursor.of(it.posting.postedAt.toString(), it.posting.id.toString()) }
            .map { it.view() }
    }

    @Transactional(readOnly = true)
    fun get(
        folioId: FolioId,
        id: PostingId,
    ): PostingView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        return (ledger.row(id)?.takeIf { it.posting.folioId == folioId } ?: throw postingNotFound()).view()
    }

    @Audited(BillingAudit.CREATE_POSTING)
    @Transactional
    @Suppress("LongParameterList") // os dados do lançamento
    fun post(
        folioId: FolioId,
        transactionCodeId: TransactionCodeId,
        quantity: BigDecimal,
        unitPrice: Money,
        description: String?,
    ): PostingView {
        authorization.require(BillingPermissions.POSTINGS_CREATE)
        val posting =
            operations.post(folioId, transactionCodeId, quantity, unitPrice, description, PostingSource.MANUAL)
        return checkNotNull(ledger.row(posting.id)).view()
    }

    @Audited(BillingAudit.REVERSE_POSTING)
    @Transactional
    fun reverse(
        folioId: FolioId,
        id: PostingId,
        reason: String,
        approvalToken: String?,
    ): PostingView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        val text = Posting.reason(reason)
        val (folio, corrections) = lockAndLoad(folioId, id)
        val cumulative = lineageCumulative(id, corrections.reversalAmount(folio))
        val approval =
            authorize(BillingAudit.REVERSE_POSTING, BillingPermissions.POSTINGS_REVERSE, id, cumulative, approvalToken)
        val reversal = corrections.reverse(folio, text, operations.stamp(), approval)
        if (!operations.record(folio, reversal)) throw alreadyReversed()
        return checkNotNull(ledger.row(reversal.id)).view()
    }

    @Audited(BillingAudit.ADJUST_POSTING)
    @Transactional
    fun adjust(
        folioId: FolioId,
        id: PostingId,
        magnitude: Money,
        reason: String,
        approvalToken: String?,
    ): PostingView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        val text = Posting.reason(reason)
        val (folio, corrections) = lockAndLoad(folioId, id)
        val cumulative = lineageCumulative(id, corrections.adjustmentAmount(folio, magnitude))
        val approval =
            authorize(BillingAudit.ADJUST_POSTING, BillingPermissions.POSTINGS_ADJUST, id, cumulative, approvalToken)
        val adjustment = corrections.adjust(folio, magnitude, text, operations.stamp(), approval)
        check(operations.record(folio, adjustment)) { "Ajuste $id recusado pelo banco" }
        return checkNotNull(ledger.row(adjustment.id)).view()
    }

    /** Trava a conta e lê o original com as correções dele (já com a trava: ninguém corrige ao mesmo tempo). */
    private fun lockAndLoad(
        folioId: FolioId,
        id: PostingId,
    ): Pair<Folio, PostingCorrections> {
        val folio = store.folioForUpdate(folioId) ?: throw folioNotFound()
        val original = ledger.posting(id)?.takeIf { it.folioId == folioId } ?: throw postingNotFound()
        return folio to PostingCorrections(original, ledger.correctionsOf(id))
    }

    /**
     * O total que o limite `maxAmount` confere: as correções de toda a linhagem do lançamento (o normal de origem e as
     * entradas de transferência que descendem dele) mais esta, com a linhagem travada.
     */
    private fun lineageCumulative(
        id: PostingId,
        amount: Money,
    ): Money {
        val root = ledger.lineageRoot(id)
        ledger.lockLineage(root)
        return CorrectionLimit.cumulative(ledger.lineageCorrections(root), amount)
    }

    /** A ação sensível com o limite `maxAmount` = o total cumulativo das correções; a aprovação usada, se houve. */
    private fun authorize(
        action: String,
        permission: PermissionCode,
        id: PostingId,
        cumulative: Money,
        approvalToken: String?,
    ): PostingApproval? {
        val result =
            authorization.requireOrApproval(
                SensitiveAction(
                    action,
                    permission,
                    "billing.posting:$id",
                    limit = LimitAmount(BillingPermissions.MAX_AMOUNT, cumulative.amount),
                ),
                approvalToken,
            )
        return (result as? SensitiveAuthorization.ByApproval)?.let { PostingApproval(it.approvalId, it.approverId) }
    }

    private fun alreadyReversed() =
        ConflictException(BillingErrorCodes.POSTING_ALREADY_REVERSED, "Este lançamento já foi estornado.")
}

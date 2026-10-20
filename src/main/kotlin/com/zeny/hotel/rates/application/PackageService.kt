package com.zeny.hotel.rates.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.domain.RatePackage
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Cadastro de pacotes (RF-TAR-06). Consultar exige `rates.plans.read`; escrever, `rates.plans.manage`; as edições
 * conferem a versão (`If-Match`). O código de lançamento é obrigatório e, quando novo no pacote, conferido na
 * `BillingApi` (nunca nas tabelas do `billing`): inexistente `400 UNKNOWN_TRANSACTION_CODE` (campo
 * `transactionCodeId`), desativado `422 TRANSACTION_CODE_INACTIVE`, de outro tipo que não consumo `422
 * TRANSACTION_CODE_KIND_NOT_ALLOWED`. O código já vinculado não é conferido de novo (o `billing` pode desativá-lo).
 * Nada é apagado; o pacote num plano ativo não é desativado (`422 PACKAGE_IN_USE`).
 */
@Service
class PackageService(
    private val store: RatesStore,
    private val directory: RatesDirectory,
    private val billing: BillingApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<PackageView> {
        authorization.require(RatesPermissions.PLANS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.ratePackages(active, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        return CursorPage(store.ratePackages(keys.items.map { it.second }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: PackageId): PackageView {
        authorization.require(RatesPermissions.PLANS_READ)
        return find(id).view()
    }

    @Audited(RatesAudit.CREATE_PACKAGE)
    @Transactional
    fun create(command: PackageCommand): PackageView {
        val details = command.details
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val ratePackage = RatePackage.create(details, clock.instant())
        requireUsableTransactionCode(details.transactionCodeId)
        audit.created(RatesAudit.PACKAGE, ratePackage.id.value) { ratePackage.auditState() }
        if (!store.tryAdd(ratePackage)) throw codeInUse()
        return ratePackage.view()
    }

    @Audited(RatesAudit.UPDATE_PACKAGE)
    @Transactional
    fun update(
        id: PackageId,
        expectedVersion: Long,
        command: PackageCommand,
    ): PackageView {
        val details = command.details
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val ratePackage = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(ratePackage.version))
        audit.track(RatesAudit.PACKAGE, id.value) { ratePackage.auditState() }
        RatePackage.validate(details)
        if (details.transactionCodeId != ratePackage.transactionCodeId) {
            requireUsableTransactionCode(details.transactionCodeId)
        }
        if (ratePackage.update(details, clock.instant()) && !store.tryFlush(RatesConstraints.PACKAGE_CODE)) {
            throw codeInUse()
        }
        return ratePackage.view()
    }

    @Audited(RatesAudit.DEACTIVATE_PACKAGE)
    @Transactional
    fun deactivate(id: PackageId): PackageView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val ratePackage = store.ratePackageForUpdate(id) ?: throw notFound()
        audit.track(RatesAudit.PACKAGE, id.value) { ratePackage.auditState() }
        if (ratePackage.active && store.ratePackageInUse(id)) {
            throw BusinessRuleException(
                RatesErrorCodes.PACKAGE_IN_USE,
                "O pacote está num plano ativo: retire-o dos planos ou desative-os antes.",
            )
        }
        if (ratePackage.deactivate(clock.instant())) store.flush()
        return ratePackage.view()
    }

    @Audited(RatesAudit.REACTIVATE_PACKAGE)
    @Transactional
    fun reactivate(id: PackageId): PackageView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val ratePackage = store.ratePackageForUpdate(id) ?: throw notFound()
        audit.track(RatesAudit.PACKAGE, id.value) { ratePackage.auditState() }
        if (ratePackage.reactivate(clock.instant())) store.flush()
        return ratePackage.view()
    }

    /** O código de lançamento existe (`400`), está ativo e é de consumo (`422`), pela `BillingApi`. */
    @Suppress("ThrowsCount") // uma recusa por motivo
    private fun requireUsableTransactionCode(id: TransactionCodeId) {
        val code =
            billing.transactionCode(id)
                ?: throw InvalidInputException(
                    RatesErrorCodes.UNKNOWN_TRANSACTION_CODE,
                    "O código de lançamento informado não existe.",
                    listOf(
                        FieldViolation(
                            "transactionCodeId",
                            RatesErrorCodes.UNKNOWN_TRANSACTION_CODE.value,
                            "O código de lançamento informado não existe.",
                        ),
                    ),
                )
        if (!code.active) {
            throw BusinessRuleException(
                RatesErrorCodes.TRANSACTION_CODE_INACTIVE,
                "O código de lançamento ${code.code} está desativado.",
            )
        }
        if (code.kind != TransactionCodeKind.CHARGE) {
            throw BusinessRuleException(
                RatesErrorCodes.TRANSACTION_CODE_KIND_NOT_ALLOWED,
                "O pacote só é lançado num código de consumo (CHARGE); ${code.code} é ${code.kind}.",
            )
        }
    }

    private fun find(id: PackageId) = store.ratePackage(id) ?: throw notFound()

    private fun notFound() = NotFoundException(RatesErrorCodes.PACKAGE_NOT_FOUND, "Pacote não encontrado.")

    private fun codeInUse() =
        ConflictException(
            RatesErrorCodes.PACKAGE_CODE_ALREADY_IN_USE,
            "Já existe um pacote com este código; se estiver desativado, reative-o.",
        )
}

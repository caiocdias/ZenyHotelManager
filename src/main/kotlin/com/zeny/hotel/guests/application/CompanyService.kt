package com.zeny.hotel.guests.application

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestsErrorCodes
import com.zeny.hotel.guests.GuestsPermissions
import com.zeny.hotel.guests.domain.Company
import com.zeny.hotel.guests.domain.CompanyDetails
import com.zeny.hotel.guests.domain.GuestsRules
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Cadastro de empresas (RF-HOS-06). Consultar exige `guests.companies.read`; escrever, `guests.companies.manage`.
 * O CNPJ é único sempre (decisão do usuário em 2026-10-16): repetido, inclusive o de uma empresa desativada, é
 * `409 CNPJ_ALREADY_IN_USE` (o banco garante, `company_cnpj_uk`; a corrida também dá 409, nunca 500). Nada é
 * apagado: a empresa é desativada e reativada.
 */
@Service
class CompanyService(
    private val store: GuestsStore,
    private val directory: GuestsDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun search(
        filter: CompanyFilter,
        page: PageParams,
    ): CursorPage<CompanyView> {
        authorization.require(GuestsPermissions.COMPANIES_READ)
        val after = page.decodedCursor()?.let { SearchRow(it.uuid(1), it.string(0)) }
        val normalized = filter.copy(name = filter.name?.let { GuestsRules.searchKey(it) }?.ifEmpty { null })
        val rows = directory.searchCompanies(normalized, after, page.limit + 1)
        val cursorPage = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.searchName, it.id.toString()) }
        return CursorPage(store.companies(cursorPage.items.map { it.id }).map { it.view() }, cursorPage.nextCursor)
    }

    @Transactional(readOnly = true)
    fun company(id: CompanyId): CompanyView {
        authorization.require(GuestsPermissions.COMPANIES_READ)
        return find(id).view()
    }

    @Audited(GuestsAudit.CREATE_COMPANY)
    @Transactional
    fun create(command: CompanyCommand): CompanyView {
        authorization.require(GuestsPermissions.COMPANIES_MANAGE)
        val company = Company.create(details(command), clock.instant())
        audit.created(GuestsAudit.COMPANY, company.id.value) { company.auditState() }
        if (!store.tryAdd(company)) throw cnpjInUse()
        return company.view()
    }

    @Audited(GuestsAudit.UPDATE_COMPANY)
    @Transactional
    fun update(
        id: CompanyId,
        expectedVersion: Long,
        command: CompanyCommand,
    ): CompanyView {
        authorization.require(GuestsPermissions.COMPANIES_MANAGE)
        val company = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(company.version))
        val before = company.auditState()
        val changed = company.update(details(command), clock.instant())
        if (changed.isEmpty()) {
            audit.noChanges()
        } else {
            audit.record(GuestsAudit.COMPANY, id.value, before, withChangedFields(company.auditState(), changed))
            if (!store.tryFlush(GuestsConstraints.COMPANY_CNPJ)) throw cnpjInUse()
        }
        return company.view()
    }

    @Audited(GuestsAudit.DEACTIVATE_COMPANY)
    @Transactional
    fun deactivate(id: CompanyId): CompanyView {
        authorization.require(GuestsPermissions.COMPANIES_MANAGE)
        val company = find(id)
        audit.track(GuestsAudit.COMPANY, id.value) { company.auditState() }
        if (company.deactivate(clock.instant())) store.flush()
        return company.view()
    }

    @Audited(GuestsAudit.REACTIVATE_COMPANY)
    @Transactional
    fun reactivate(id: CompanyId): CompanyView {
        authorization.require(GuestsPermissions.COMPANIES_MANAGE)
        val company = find(id)
        audit.track(GuestsAudit.COMPANY, id.value) { company.auditState() }
        if (company.reactivate(clock.instant())) store.flush()
        return company.view()
    }

    private fun details(command: CompanyCommand) =
        CompanyDetails(
            legalName = command.legalName,
            tradeName = command.tradeName,
            cnpj = command.cnpj,
            stateRegistration = command.stateRegistration,
            municipalRegistration = command.municipalRegistration,
            address = command.address?.toAddress(),
            contactName = command.contactName,
            phone = command.phone,
            email = command.email,
            billingEmail = command.billingEmail,
            creditLimit = command.creditLimit,
        )

    private fun find(id: CompanyId): Company =
        store.company(id) ?: throw NotFoundException(GuestsErrorCodes.COMPANY_NOT_FOUND, "Empresa não encontrada.")

    private fun cnpjInUse() =
        ConflictException(
            GuestsErrorCodes.CNPJ_ALREADY_IN_USE,
            "Já existe uma empresa com este CNPJ; se estiver desativada, reative-a.",
        )
}

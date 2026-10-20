package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.domain.CancellationPolicy
import com.zeny.hotel.rates.domain.GuaranteePolicy
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
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
 * Cadastro de políticas de cancelamento (RF-TAR-07). Consultar exige `rates.plans.read`; escrever,
 * `rates.plans.manage`; as edições conferem a versão da última leitura (`If-Match`). **Não é versionada** (decisão do
 * usuário em 2026-10-20): a edição vale na hora para as reservas criadas depois (a reserva copia os termos, RN-15).
 * Nada é apagado; a política usada por um plano ativo não é desativada (`422 CANCELLATION_POLICY_IN_USE`).
 */
@Service
class CancellationPolicyService(
    private val store: RatesStore,
    private val directory: RatesDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<CancellationPolicyView> {
        authorization.require(RatesPermissions.PLANS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.cancellationPolicies(active, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        return CursorPage(store.cancellationPolicies(keys.items.map { it.second }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: CancellationPolicyId): CancellationPolicyView {
        authorization.require(RatesPermissions.PLANS_READ)
        return find(id).view()
    }

    @Audited(RatesAudit.CREATE_CANCELLATION_POLICY)
    @Transactional
    fun create(command: CancellationPolicyCommand): CancellationPolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = CancellationPolicy.create(command.terms, clock.instant())
        audit.created(RatesAudit.CANCELLATION_POLICY, policy.id.value) { policy.auditState() }
        if (!store.tryAdd(policy)) throw nameInUse()
        return policy.view()
    }

    @Audited(RatesAudit.UPDATE_CANCELLATION_POLICY)
    @Transactional
    fun update(
        id: CancellationPolicyId,
        expectedVersion: Long,
        command: CancellationPolicyCommand,
    ): CancellationPolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(policy.version))
        audit.track(RatesAudit.CANCELLATION_POLICY, id.value) { policy.auditState() }
        if (policy.update(command.terms, clock.instant()) &&
            !store.tryFlush(RatesConstraints.CANCELLATION_POLICY_NAME)
        ) {
            throw nameInUse()
        }
        return policy.view()
    }

    @Audited(RatesAudit.DEACTIVATE_CANCELLATION_POLICY)
    @Transactional
    fun deactivate(id: CancellationPolicyId): CancellationPolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = store.cancellationPolicyForUpdate(id) ?: throw notFound()
        audit.track(RatesAudit.CANCELLATION_POLICY, id.value) { policy.auditState() }
        if (policy.active && store.cancellationPolicyInUse(id)) {
            throw BusinessRuleException(
                RatesErrorCodes.CANCELLATION_POLICY_IN_USE,
                "A política de cancelamento está num plano ativo: troque-a nos planos ou desative-os antes.",
            )
        }
        if (policy.deactivate(clock.instant())) store.flush()
        return policy.view()
    }

    @Audited(RatesAudit.REACTIVATE_CANCELLATION_POLICY)
    @Transactional
    fun reactivate(id: CancellationPolicyId): CancellationPolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = store.cancellationPolicyForUpdate(id) ?: throw notFound()
        audit.track(RatesAudit.CANCELLATION_POLICY, id.value) { policy.auditState() }
        if (policy.reactivate(clock.instant())) store.flush()
        return policy.view()
    }

    private fun find(id: CancellationPolicyId) = store.cancellationPolicy(id) ?: throw notFound()

    private fun notFound() =
        NotFoundException(RatesErrorCodes.CANCELLATION_POLICY_NOT_FOUND, "Política de cancelamento não encontrada.")

    private fun nameInUse() =
        ConflictException(
            RatesErrorCodes.CANCELLATION_POLICY_NAME_ALREADY_IN_USE,
            "Já existe uma política de cancelamento com este nome; se estiver desativada, reative-a.",
        )
}

/**
 * Cadastro de políticas de garantia (RF-TAR-07), com as mesmas regras da de cancelamento: `rates.plans.read` e
 * `rates.plans.manage`, `If-Match`, não versionada, nunca apagada e não desativada em uso por plano ativo (`422
 * GUARANTEE_POLICY_IN_USE`).
 */
@Service
class GuaranteePolicyService(
    private val store: RatesStore,
    private val directory: RatesDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<GuaranteePolicyView> {
        authorization.require(RatesPermissions.PLANS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.guaranteePolicies(active, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        return CursorPage(store.guaranteePolicies(keys.items.map { it.second }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: GuaranteePolicyId): GuaranteePolicyView {
        authorization.require(RatesPermissions.PLANS_READ)
        return find(id).view()
    }

    @Audited(RatesAudit.CREATE_GUARANTEE_POLICY)
    @Transactional
    fun create(command: GuaranteePolicyCommand): GuaranteePolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = GuaranteePolicy.create(command.terms, clock.instant())
        audit.created(RatesAudit.GUARANTEE_POLICY, policy.id.value) { policy.auditState() }
        if (!store.tryAdd(policy)) throw nameInUse()
        return policy.view()
    }

    @Audited(RatesAudit.UPDATE_GUARANTEE_POLICY)
    @Transactional
    fun update(
        id: GuaranteePolicyId,
        expectedVersion: Long,
        command: GuaranteePolicyCommand,
    ): GuaranteePolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(policy.version))
        audit.track(RatesAudit.GUARANTEE_POLICY, id.value) { policy.auditState() }
        if (policy.update(command.terms, clock.instant()) && !store.tryFlush(RatesConstraints.GUARANTEE_POLICY_NAME)) {
            throw nameInUse()
        }
        return policy.view()
    }

    @Audited(RatesAudit.DEACTIVATE_GUARANTEE_POLICY)
    @Transactional
    fun deactivate(id: GuaranteePolicyId): GuaranteePolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = store.guaranteePolicyForUpdate(id) ?: throw notFound()
        audit.track(RatesAudit.GUARANTEE_POLICY, id.value) { policy.auditState() }
        if (policy.active && store.guaranteePolicyInUse(id)) {
            throw BusinessRuleException(
                RatesErrorCodes.GUARANTEE_POLICY_IN_USE,
                "A política de garantia está num plano ativo: troque-a nos planos ou desative-os antes.",
            )
        }
        if (policy.deactivate(clock.instant())) store.flush()
        return policy.view()
    }

    @Audited(RatesAudit.REACTIVATE_GUARANTEE_POLICY)
    @Transactional
    fun reactivate(id: GuaranteePolicyId): GuaranteePolicyView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val policy = store.guaranteePolicyForUpdate(id) ?: throw notFound()
        audit.track(RatesAudit.GUARANTEE_POLICY, id.value) { policy.auditState() }
        if (policy.reactivate(clock.instant())) store.flush()
        return policy.view()
    }

    private fun find(id: GuaranteePolicyId) = store.guaranteePolicy(id) ?: throw notFound()

    private fun notFound() =
        NotFoundException(RatesErrorCodes.GUARANTEE_POLICY_NOT_FOUND, "Política de garantia não encontrada.")

    private fun nameInUse() =
        ConflictException(
            RatesErrorCodes.GUARANTEE_POLICY_NAME_ALREADY_IN_USE,
            "Já existe uma política de garantia com este nome; se estiver desativada, reative-a.",
        )
}

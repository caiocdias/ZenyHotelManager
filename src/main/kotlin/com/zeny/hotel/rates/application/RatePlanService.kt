package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RatePlanDetails
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
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
 * Cadastro de planos tarifários (RF-TAR-01). Consultar exige `rates.plans.read`; escrever, `rates.plans.manage`; as
 * edições conferem a versão (`If-Match`). Nada é apagado, só desativado.
 *
 * Referências, conferidas só quando **novas** no plano (as que ficam não são conferidas de novo): cada tipo de UH pela
 * `RoomsApi` (nunca pelas tabelas do `rooms`): inexistente `400 UNKNOWN_ROOM_TYPE`, desativado `422
 * ROOM_TYPE_INACTIVE`; as políticas e os pacotes do próprio módulo, travados (`SELECT … FOR UPDATE`): inexistente `400
 * UNKNOWN_…`, desativado `422 …_INACTIVE`. Reativar o plano exige as políticas e os pacotes dele ativos. Com a
 * desativação das políticas e dos pacotes em uso recusada (que trava a mesma linha), **um plano ativo nunca fica com
 * política ou pacote desativado**. O tipo de UH desativado no `rooms` continua no plano (o `rooms` não o impede); a
 * vendabilidade (E04) o descarta.
 */
@Service
@Suppress("TooManyFunctions") // um caso de uso por operação e uma conferência por referência
class RatePlanService(
    private val store: RatesStore,
    private val directory: RatesDirectory,
    private val rooms: RoomsApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        filter: RatePlanFilter,
        page: PageParams,
    ): CursorPage<RatePlanView> {
        authorization.require(RatesPermissions.PLANS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.ratePlans(filter, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        return CursorPage(store.ratePlans(keys.items.map { it.second }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: RatePlanId): RatePlanView {
        authorization.require(RatesPermissions.PLANS_READ)
        return find(id).view()
    }

    @Audited(RatesAudit.CREATE_RATE_PLAN)
    @Transactional
    fun create(command: NewRatePlanCommand): RatePlanView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val plan = RatePlan.create(command.category, command.plan.details, clock.instant())
        requireLinkable(plan.details, current = null)
        audit.created(RatesAudit.RATE_PLAN, plan.id.value) { plan.auditState() }
        if (!store.tryAdd(plan)) throw codeInUse()
        return plan.view()
    }

    @Audited(RatesAudit.UPDATE_RATE_PLAN)
    @Transactional
    fun update(
        id: RatePlanId,
        expectedVersion: Long,
        command: RatePlanCommand,
    ): RatePlanView {
        val details = command.details
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val plan = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(plan.version))
        audit.track(RatesAudit.RATE_PLAN, id.value) { plan.auditState() }
        requireLinkable(RatePlan.validate(details), current = plan.details)
        if (plan.update(details, clock.instant()) && !store.tryFlush(RatesConstraints.RATE_PLAN_CODE)) {
            throw codeInUse()
        }
        return plan.view()
    }

    @Audited(RatesAudit.DEACTIVATE_RATE_PLAN)
    @Transactional
    fun deactivate(id: RatePlanId): RatePlanView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val plan = find(id)
        audit.track(RatesAudit.RATE_PLAN, id.value) { plan.auditState() }
        if (plan.deactivate(clock.instant())) store.flush()
        return plan.view()
    }

    @Audited(RatesAudit.REACTIVATE_RATE_PLAN)
    @Transactional
    fun reactivate(id: RatePlanId): RatePlanView {
        authorization.require(RatesPermissions.PLANS_MANAGE)
        val plan = find(id)
        audit.track(RatesAudit.RATE_PLAN, id.value) { plan.auditState() }
        if (!plan.active) {
            requireActivePolicies(plan.cancellationPolicyId, plan.guaranteePolicyId)
            requireActivePackages(plan.packageIds)
            plan.reactivate(clock.instant())
            store.flush()
        }
        return plan.view()
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** As referências novas em [details] (as que não estão em [current]) existem e estão ativas. */
    private fun requireLinkable(
        details: RatePlanDetails,
        current: RatePlanDetails?,
    ) {
        requireActiveRoomTypes(details.roomTypeIds - current?.roomTypeIds.orEmpty())
        requireActivePolicies(
            details.cancellationPolicyId.takeIf { it != current?.cancellationPolicyId },
            details.guaranteePolicyId.takeIf { it != current?.guaranteePolicyId },
        )
        requireActivePackages(details.packageIds - current?.packageIds.orEmpty())
    }

    /** Cada tipo existe (`400`) e está ativo (`422`), pela `RoomsApi`. */
    private fun requireActiveRoomTypes(ids: Set<RoomTypeId>) {
        val types = ids.sortedBy { it.value }.map { it to rooms.roomType(it) }
        if (types.any { it.second == null }) {
            throw unknown(RatesErrorCodes.UNKNOWN_ROOM_TYPE, "roomTypeIds", "Algum tipo de UH informado não existe.")
        }
        if (types.any { it.second?.active == false }) {
            throw BusinessRuleException(
                RatesErrorCodes.ROOM_TYPE_INACTIVE,
                "Algum tipo de UH informado está desativado.",
            )
        }
    }

    /** As políticas informadas (nulas: nada a conferir) existem (`400`) e estão ativas (`422`), travadas. */
    @Suppress("ThrowsCount") // uma recusa por motivo
    private fun requireActivePolicies(
        cancellation: CancellationPolicyId?,
        guarantee: GuaranteePolicyId?,
    ) {
        if (cancellation != null) {
            val policy =
                store.cancellationPolicyForUpdate(cancellation)
                    ?: throw unknown(
                        RatesErrorCodes.UNKNOWN_CANCELLATION_POLICY,
                        "cancellationPolicyId",
                        "A política de cancelamento informada não existe.",
                    )
            if (!policy.active) {
                throw BusinessRuleException(
                    RatesErrorCodes.CANCELLATION_POLICY_INACTIVE,
                    "A política de cancelamento informada está desativada.",
                )
            }
        }
        if (guarantee != null) {
            val policy =
                store.guaranteePolicyForUpdate(guarantee)
                    ?: throw unknown(
                        RatesErrorCodes.UNKNOWN_GUARANTEE_POLICY,
                        "guaranteePolicyId",
                        "A política de garantia informada não existe.",
                    )
            if (!policy.active) {
                throw BusinessRuleException(
                    RatesErrorCodes.GUARANTEE_POLICY_INACTIVE,
                    "A política de garantia informada está desativada.",
                )
            }
        }
    }

    /** Os pacotes existem (`400`) e estão ativos (`422`), travados em ordem de id. */
    private fun requireActivePackages(ids: Set<PackageId>) {
        if (ids.isEmpty()) return
        val found = store.ratePackagesForUpdate(ids)
        if (found.size < ids.size) {
            throw unknown(RatesErrorCodes.UNKNOWN_PACKAGE, "packageIds", "Algum pacote informado não existe.")
        }
        if (found.any { !it.active }) {
            throw BusinessRuleException(RatesErrorCodes.PACKAGE_INACTIVE, "Algum pacote informado está desativado.")
        }
    }

    private fun find(id: RatePlanId) =
        store.ratePlan(id) ?: throw NotFoundException(RatesErrorCodes.RATE_PLAN_NOT_FOUND, "Plano não encontrado.")

    private fun codeInUse() =
        ConflictException(
            RatesErrorCodes.RATE_PLAN_CODE_ALREADY_IN_USE,
            "Já existe um plano com este código; se estiver desativado, reative-o.",
        )

    private fun unknown(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

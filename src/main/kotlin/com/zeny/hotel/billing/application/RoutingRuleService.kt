package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.domain.BillingRules
import com.zeny.hotel.billing.domain.BusinessPeriod
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.RoutingCriteria
import com.zeny.hotel.billing.domain.RoutingNetwork
import com.zeny.hotel.billing.domain.RoutingRule
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** O destino, o critério (códigos **ou** grupo de receita) e o período de uma regra. */
data class RoutingRuleCommand(
    val targetFolioId: FolioId,
    val transactionCodeIds: List<TransactionCodeId>?,
    val revenueGroup: RevenueGroup?,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
)

/**
 * Regras de roteamento pela API (RF-CTA-04; decisões do usuário em 2026-10-19): consultar exige `billing.folios.read`;
 * escrever, `billing.routing.manage` (não sensível). A origem é fixa; destino, critério e período mudam por `PUT` com
 * `If-Match`; a regra é desativada e reativada, nunca apagada. Toda escrita toma antes a trava única das regras
 * (`BillingStore.lockRoutingRules`) e depois as contas de origem e destino em ordem de id, e confere, com a regra
 * ativa: origem aberta (`409 FOLIO_CLOSED`), destino aberto (`409 ROUTING_TARGET_CLOSED`), sem sobreposição no mesmo
 * nível (`409 ROUTING_RULE_OVERLAP`) e sem ciclo (`422 ROUTING_RULE_CYCLE`). Nenhum lançamento já feito muda.
 */
@Service
@Suppress("LongParameterList") // as portas que o cadastro de regras usa, injetadas pelo Spring
class RoutingRuleService(
    private val store: BillingStore,
    private val directory: BillingDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val currentActor: CurrentActor,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        filter: RoutingRuleFilter,
        page: PageParams,
    ): CursorPage<RoutingRuleView> {
        authorization.require(BillingPermissions.FOLIOS_READ)
        val ids = directory.routingRules(filter, page.decodedCursor()?.uuid(0), page.limit + 1)
        val cursorPage = CursorPage.fromRows(ids, page.limit) { PageCursor.of(it.toString()) }
        return CursorPage(store.routingRules(cursorPage.items).map { it.view() }, cursorPage.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: RoutingRuleId): RoutingRuleView {
        authorization.require(BillingPermissions.FOLIOS_READ)
        return (store.routingRule(id) ?: throw routingRuleNotFound()).view()
    }

    @Audited(BillingAudit.CREATE_ROUTING_RULE)
    @Transactional
    fun create(
        sourceFolioId: FolioId,
        command: RoutingRuleCommand,
    ): RoutingRuleView {
        authorization.require(BillingPermissions.ROUTING_MANAGE)
        val criteria = criteria(command)
        val period = BusinessPeriod.of(command.startsOn, command.endsOn)
        store.lockRoutingRules()
        val folios = store.foliosForUpdate(setOf(sourceFolioId, command.targetFolioId))
        val source = folios[sourceFolioId] ?: throw unknownFolio("sourceFolioId")
        val target = folios[command.targetFolioId] ?: throw unknownFolio("targetFolioId")
        requireCodes(command.transactionCodeIds)
        val rule =
            RoutingRule.create(
                source.id,
                target.id,
                criteria,
                period,
                currentActor.currentOrNull()?.userId,
                now(),
            )
        requireValid(rule, source, target)
        audit.created(BillingAudit.ROUTING_RULE, rule.id.value) { rule.auditState() }
        store.add(rule)
        return rule.view()
    }

    @Audited(BillingAudit.UPDATE_ROUTING_RULE)
    @Transactional
    fun update(
        id: RoutingRuleId,
        expectedVersion: Long,
        command: RoutingRuleCommand,
    ): RoutingRuleView {
        authorization.require(BillingPermissions.ROUTING_MANAGE)
        val criteria = criteria(command)
        val period = BusinessPeriod.of(command.startsOn, command.endsOn)
        store.lockRoutingRules()
        val rule = store.routingRule(id) ?: throw routingRuleNotFound()
        requireCurrentVersion(expectedVersion, checkNotNull(rule.version))
        val folios = store.foliosForUpdate(setOf(rule.sourceFolioId, command.targetFolioId))
        val source = checkNotNull(folios[rule.sourceFolioId]) { "Conta de origem ${rule.sourceFolioId} ausente" }
        val target = folios[command.targetFolioId] ?: throw unknownFolio("targetFolioId")
        requireCodes(command.transactionCodeIds)
        audit.track(BillingAudit.ROUTING_RULE, id.value) { rule.auditState() }
        if (rule.update(target.id, criteria, period, now()).isNotEmpty()) {
            if (rule.active) requireValid(rule, source, target)
            store.flush()
        }
        return rule.view()
    }

    @Audited(BillingAudit.DEACTIVATE_ROUTING_RULE)
    @Transactional
    fun deactivate(id: RoutingRuleId): RoutingRuleView {
        authorization.require(BillingPermissions.ROUTING_MANAGE)
        store.lockRoutingRules()
        val rule = store.routingRule(id) ?: throw routingRuleNotFound()
        audit.track(BillingAudit.ROUTING_RULE, id.value) { rule.auditState() }
        if (rule.deactivate(now())) store.flush()
        return rule.view()
    }

    @Audited(BillingAudit.REACTIVATE_ROUTING_RULE)
    @Transactional
    fun reactivate(id: RoutingRuleId): RoutingRuleView {
        authorization.require(BillingPermissions.ROUTING_MANAGE)
        store.lockRoutingRules()
        val rule = store.routingRule(id) ?: throw routingRuleNotFound()
        if (rule.active) return rule.view()
        val folios = store.foliosForUpdate(setOf(rule.sourceFolioId, rule.targetFolioId))
        audit.track(BillingAudit.ROUTING_RULE, id.value) { rule.auditState() }
        rule.reactivate(now())
        requireValid(
            rule,
            checkNotNull(folios[rule.sourceFolioId]) { "Conta de origem ${rule.sourceFolioId} ausente" },
            checkNotNull(folios[rule.targetFolioId]) { "Conta de destino ${rule.targetFolioId} ausente" },
        )
        store.flush()
        return rule.view()
    }

    /** As regras de uma regra ativa: origem e destino abertos, sem sobreposição nem ciclo com as outras ativas. */
    private fun requireValid(
        rule: RoutingRule,
        source: Folio,
        target: Folio,
    ) {
        source.requireOpen()
        target.requireOpenAsRoutingTarget()
        val rules = store.activeRoutingRules().filter { it.id != rule.id } + rule
        val codeIds = rules.flatMap { (it.criteria as? RoutingCriteria.Codes)?.ids.orEmpty() }.distinct()
        val groups = store.transactionCodes(codeIds.map { it.value }).associate { it.id to it.revenueGroup }
        val network = RoutingNetwork(rules) { groups[it] }
        network.requireNoOverlap(rule)
        network.requireNoCycle(rule)
    }

    /** O critério: códigos (de 1 a 50, sem repetir) **ou** um grupo de receita. */
    @Suppress("ThrowsCount") // uma recusa por motivo
    private fun criteria(command: RoutingRuleCommand): RoutingCriteria {
        val codes = command.transactionCodeIds
        return when {
            codes != null && command.revenueGroup != null -> {
                throw BillingRules.invalid(
                    BillingErrorCodes.ROUTING_RULE_INVALID,
                    "revenueGroup",
                    "Informe os códigos ou o grupo de receita, não os dois.",
                )
            }

            command.revenueGroup != null -> {
                RoutingCriteria.Group(command.revenueGroup)
            }

            codes.isNullOrEmpty() -> {
                throw BillingRules.invalid(
                    BillingErrorCodes.ROUTING_RULE_INVALID,
                    "transactionCodeIds",
                    "Informe os códigos (ao menos um) ou o grupo de receita.",
                )
            }

            codes.size > RoutingRule.MAX_CODES || codes.toSet().size != codes.size -> {
                throw BillingRules.invalid(
                    BillingErrorCodes.ROUTING_RULE_INVALID,
                    "transactionCodeIds",
                    "Informe até ${RoutingRule.MAX_CODES} códigos, sem repetir.",
                )
            }

            else -> {
                RoutingCriteria.Codes(codes.toSet())
            }
        }
    }

    /** Os códigos de uma regra por código existem (ativos ou não). */
    private fun requireCodes(codes: List<TransactionCodeId>?) {
        if (codes.isNullOrEmpty()) return
        val found = store.transactionCodes(codes.map { it.value }).map { it.id }.toSet()
        val violations =
            codes.mapIndexedNotNull { index, id ->
                if (id in found) {
                    null
                } else {
                    FieldViolation(
                        "transactionCodeIds[$index]",
                        BillingErrorCodes.UNKNOWN_TRANSACTION_CODE.value,
                        "Código de lançamento inexistente.",
                    )
                }
            }
        if (violations.isNotEmpty()) {
            throw InvalidInputException(
                BillingErrorCodes.UNKNOWN_TRANSACTION_CODE,
                "Código de lançamento inexistente.",
                violations,
            )
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

internal fun unknownFolio(field: String) =
    BillingRules.invalid(BillingErrorCodes.UNKNOWN_FOLIO, field, "Conta inexistente.")

/** Uma regra de roteamento lida. */
data class RoutingRuleView(
    val id: RoutingRuleId,
    val sourceFolioId: FolioId,
    val targetFolioId: FolioId,
    val transactionCodeIds: List<TransactionCodeId>,
    val revenueGroup: RevenueGroup?,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    val active: Boolean,
    val createdAt: Instant,
    val createdBy: UserId?,
    val updatedAt: Instant,
    val version: Long,
)

internal fun RoutingRule.view(): RoutingRuleView {
    val criteria = criteria
    return RoutingRuleView(
        id,
        sourceFolioId,
        targetFolioId,
        (criteria as? RoutingCriteria.Codes)?.ids.orEmpty().sortedBy { it.value.toString() },
        (criteria as? RoutingCriteria.Group)?.group,
        period.startsOn,
        period.endsOn,
        active,
        createdAt,
        createdBy,
        updatedAt,
        checkNotNull(version),
    )
}

/** Retrato da trilha de uma regra de roteamento: só ids, critério, período e situação. */
internal fun RoutingRule.auditState(): Map<String, Any?> {
    val view = view()
    return mapOf(
        "sourceFolioId" to view.sourceFolioId.toString(),
        "targetFolioId" to view.targetFolioId.toString(),
        "transactionCodeIds" to view.transactionCodeIds.map { it.toString() },
        "revenueGroup" to view.revenueGroup?.name,
        "startsOn" to view.startsOn.toString(),
        "endsOn" to view.endsOn?.toString(),
        "active" to view.active,
    )
}

internal fun routingRuleNotFound() =
    NotFoundException(BillingErrorCodes.ROUTING_RULE_NOT_FOUND, "Regra de roteamento não encontrada.")

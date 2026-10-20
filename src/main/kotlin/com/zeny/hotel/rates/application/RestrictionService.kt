package com.zeny.hotel.rates.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.RestrictionsChanged
import com.zeny.hotel.rates.domain.EffectiveRestriction
import com.zeny.hotel.rates.domain.PriceSelection
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RatesRules
import com.zeny.hotel.rates.domain.RestrictionChanges
import com.zeny.hotel.rates.domain.RestrictionKey
import com.zeny.hotel.rates.domain.RestrictionPatch
import com.zeny.hotel.rates.domain.RestrictionValues
import com.zeny.hotel.rates.domain.Restrictions
import com.zeny.hotel.rates.domain.SelectionErrors
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.NotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Restrições de venda por data, plano e tipo de UH (RF-TAR-05, H15 e H16; M4 E03). Consultar exige `rates.plans.read`;
 * gravar e retirar, `rates.restrictions.manage` (não sensível).
 *
 * **Edição em massa** ([update]): a restrição **geral** do plano (`roomTypeIds` vazio) ou as dos tipos de UH pedidos
 * (do plano, `422 ROOM_TYPE_NOT_IN_PLAN`) × as datas do período (explícito, a partir da data operacional, `422
 * RESTRICTION_PERIOD_IN_PAST`; ou a temporada, cortada na data operacional) nos dias da semana pedidos, até 366 dias
 * por requisição, num só comando SQL. A edição é parcial ([RestrictionPatch]): os campos de `set` passam a valer, os de
 * `clear` voltam a "não informado" e os demais ficam; a linha que fica vazia sai. Nenhuma combinação com o mínimo maior
 * que o máximo é gravada (`400 RESTRICTION_RANGE_INVALID` no pedido; `422 RESTRICTION_RANGE_CONFLICT` com o que já está
 * gravado, inclusive a efetiva de um tipo, a dele sobre a geral). Vale também num plano derivado ou inativo: as
 * restrições são de cada plano (a derivação é só de preço).
 *
 * Toda mudança vai à trilha e publica [RestrictionsChanged] pelo outbox; sem mudança, nada. A linha do plano é travada
 * (`SELECT … FOR UPDATE`) antes de ler as restrições: duas edições do mesmo plano se enfileiram, então a conferência da
 * combinação geral × tipo vê o que a anterior gravou.
 */
@Service
@Suppress("LongParameterList") // as portas de que a edição de restrições precisa
class RestrictionService(
    private val store: RatesStore,
    private val restrictions: RestrictionStore,
    private val reader: RestrictionReader,
    private val selections: PriceSelections,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun read(
        id: RatePlanId,
        roomTypeId: RoomTypeId?,
        from: LocalDate,
        to: LocalDate,
    ): RestrictionsView {
        authorization.require(RatesPermissions.PLANS_READ)
        val selection = PriceSelection.of(from, to, errors = SelectionErrors.RESTRICTIONS)
        val plan = store.ratePlan(id) ?: throw notFound()
        val types = plan.roomTypeIds.filter { roomTypeId == null || it == roomTypeId }.sortedBy { it.value }
        val rows = reader.rows(plan.id, types, selection.from, selection.to)
        val items =
            rows.keys
                .map { it.date }
                .distinct()
                .sorted()
                .map { date ->
                    val general = rows[RestrictionKey(null, date)]
                    RestrictionDayView(
                        date,
                        general?.let(RestrictionData::of),
                        types.map { type ->
                            val specific = rows[RestrictionKey(type, date)]
                            RoomTypeRestrictionView(
                                type,
                                specific?.let(RestrictionData::of),
                                EffectiveRestrictionView.of(EffectiveRestriction.of(general, specific)),
                            )
                        },
                    )
                }
        return RestrictionsView(plan.id, selection.from, selection.to, items)
    }

    @Audited(RatesAudit.UPDATE_RESTRICTIONS)
    @Transactional
    fun update(
        id: RatePlanId,
        command: RestrictionCommand,
    ): RestrictionChangeView {
        authorization.require(RatesPermissions.RESTRICTIONS_MANAGE)
        val patch = RestrictionPatch.of(command.set.toValues(), command.clear)
        RatesRules.sized("roomTypeIds", command.roomTypeIds, 0, MAX_ROOM_TYPES)
        selections.requireWellFormed(command.dates, SelectionErrors.RESTRICTIONS)
        val plan = store.ratePlanForUpdate(id) ?: throw notFound()
        val selection = select(plan, command)
        val dates = selection.dates()
        val general = command.roomTypeIds.isEmpty()
        val targets: List<RoomTypeId?> = if (general) listOf(null) else command.roomTypeIds.sortedBy { it.value }
        val keys = dates.flatMap { date -> targets.map { RestrictionKey(it, date) } }
        val planTypes = plan.roomTypeIds.sortedBy { it.value }
        val existing = restrictions.restrictions(plan.id, planTypes, selection.from, selection.to)
        val changes = RestrictionChanges.of(keys, existing, patch)
        if (changes.isEmpty()) {
            audit.noChanges()
            return RestrictionChangeView(plan.id, general, emptyList(), dates.size, 0, 0, 0, emptyList(), null, null)
        }
        val after = existing - changes.removed.toSet() + changes.after
        Restrictions.requireCoherent(after, planTypes, changes.all.map { it.date })
        restrictions.upsert(plan.id, changes.after, clock.instant())
        restrictions.remove(plan.id, changes.removed)
        return finish(plan, general, planTypes, selection, existing, patch, changes)
    }

    /** Os tipos pedidos estão no plano, e a seleção tem alguma data (a partir da data operacional). */
    private fun select(
        plan: RatePlan,
        command: RestrictionCommand,
    ): PriceSelection {
        if (!plan.roomTypeIds.containsAll(command.roomTypeIds)) {
            throw BusinessRuleException(
                RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN,
                "Algum tipo de UH informado não está no plano.",
            )
        }
        val selection = selections.resolve(command.dates, hotel.currentBusinessDate(), SelectionErrors.RESTRICTIONS)
        if (selection.dates().isEmpty()) {
            throw RatesRules.invalid(
                RatesErrorCodes.RESTRICTION_PERIOD_INVALID,
                "weekdays",
                "Nenhuma data do período cai nos dias da semana informados.",
            )
        }
        return selection
    }

    @Suppress("LongParameterList") // a edição inteira, para a trilha e o evento
    private fun finish(
        plan: RatePlan,
        general: Boolean,
        planTypes: List<RoomTypeId>,
        selection: PriceSelection,
        existing: Map<RestrictionKey, RestrictionValues>,
        patch: RestrictionPatch,
        changes: RestrictionChanges,
    ): RestrictionChangeView {
        val touched = changes.all
        val from = touched.minOf { it.date }
        val to = touched.maxOf { it.date }
        val types = if (general) planTypes else touched.mapNotNull { it.roomTypeId }.distinct().sortedBy { it.value }
        val fields = changes.fields.sorted()
        val previous = (changes.changed + changes.removed).mapNotNull { existing[it] }.distinct()
        audit.record(
            RatesAudit.RESTRICTIONS,
            plan.id.value,
            mapOf(
                "recordedRows" to touched.count { existing[it] != null },
                "previousValues" to previous.take(MAX_AUDITED_VALUES).map { it.auditState() },
            ),
            mapOf(
                "general" to general,
                "roomTypeIds" to types.map { it.toString() },
                "from" to from.toString(),
                "to" to to.toString(),
                "weekdays" to selection.weekdays.sorted().map { it.name },
                "set" to patch.set.auditState(),
                "clear" to patch.clear.sorted().map { it.name },
                "created" to changes.created.size,
                "changed" to changes.changed.size,
                "removed" to changes.removed.size,
                "fields" to fields.map { it.name },
            ),
        )
        events.publishEvent(
            RestrictionsChanged(plan.id.value, general, types.map { it.value }, fields, from, to, clock.instant()),
        )
        return RestrictionChangeView(
            plan.id,
            general,
            types,
            selection.dates().size,
            changes.created.size,
            changes.changed.size,
            changes.removed.size,
            fields,
            from,
            to,
        )
    }

    private fun notFound() = NotFoundException(RatesErrorCodes.RATE_PLAN_NOT_FOUND, "Plano não encontrado.")

    private companion object {
        const val MAX_ROOM_TYPES = 50
        const val MAX_AUDITED_VALUES = 20
    }
}

/**
 * A leitura das restrições de um plano (M4 E03), reusada pela avaliação de uma estadia (E04): as linhas gravadas e a
 * restrição **efetiva** de cada tipo de UH por data (a do tipo sobre a geral, campo a campo, [EffectiveRestriction]).
 * Sem checar permissão (quem chama já se autorizou).
 */
@Component
class RestrictionReader(
    private val restrictions: RestrictionStore,
) {
    /** As linhas gravadas do plano de [from] a [to]: as gerais e as dos tipos [roomTypeIds]. */
    fun rows(
        planId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<RestrictionKey, RestrictionValues> = restrictions.restrictions(planId, roomTypeIds, from, to)

    /**
     * As restrições efetivas dos tipos [roomTypeIds] em cada data de [from] a [to]; a data sem nenhuma linha gravada
     * vem com [EffectiveRestriction.NONE] (sem restrição).
     */
    fun effective(
        planId: RatePlanId,
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<Pair<RoomTypeId, LocalDate>, EffectiveRestriction> {
        val rows = rows(planId, roomTypeIds, from, to)
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .flatMap { date ->
                val general = rows[RestrictionKey(null, date)]
                roomTypeIds.asSequence().map { type ->
                    (type to date) to EffectiveRestriction.of(general, rows[RestrictionKey(type, date)])
                }
            }.toMap()
    }
}

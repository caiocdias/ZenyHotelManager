package com.zeny.hotel.rates.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.RatesChangeKind
import com.zeny.hotel.rates.RatesChanged
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.domain.ChildPrice
import com.zeny.hotel.rates.domain.PriceChanges
import com.zeny.hotel.rates.domain.PriceKey
import com.zeny.hotel.rates.domain.PriceSelection
import com.zeny.hotel.rates.domain.PriceSet
import com.zeny.hotel.rates.domain.RatePlan
import com.zeny.hotel.rates.domain.RatesRules
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.SensitiveAction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Preços por tipo de UH, data e ocupação (RF-TAR-02 e 04, H1, H2 e H9; M4 E02). Consultar exige `rates.plans.read`;
 * gravar e retirar, `rates.prices.manage`.
 *
 * **Edição em massa** ([update], [remove]): os tipos de UH (do plano, `422 ROOM_TYPE_NOT_IN_PLAN`) × as datas do
 * período (explícito, a partir da data operacional, `422 RATE_PRICE_PERIOD_IN_PAST`; ou a temporada, cortada na data
 * operacional) nos dias da semana pedidos, até 366 dias por requisição, num só comando SQL. Só planos com preços
 * próprios (`422 RATE_PLAN_DERIVED`). Os preços de criança vêm nas faixas **atuais** do hotel
 * (`400 RATE_PRICE_CHILD_BANDS_INVALID`); regravar uma data troca todas as faixas dela (as obsoletas saem).
 *
 * **Tarifa publicada** (H9): alterar ou retirar um preço **já gravado** (a partir da data operacional, sempre, aqui)
 * de um plano **publicado** (ativo, ou base de um plano ativo: os preços dele aparecem no derivado) é ação sensível
 * (`rates.prices.override`, `requireOrApproval`; sem ela, `403 APPROVAL_REQUIRED`, e com a aprovação a trilha leva o
 * aprovador). Gravar onde não havia, regravar os mesmos valores ou mexer num plano não publicado só exige
 * `rates.prices.manage`.
 *
 * Toda mudança vai à trilha e publica [RatesChanged] pelo outbox, com os planos derivados afetados; sem mudança, nada.
 */
@Service
@Suppress("LongParameterList") // as portas de que a edição de preços precisa
class RatePriceService(
    private val store: RatesStore,
    private val prices: RatePriceStore,
    private val reader: RatePriceReader,
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
    ): RatePricesView {
        authorization.require(RatesPermissions.PLANS_READ)
        val selection = PriceSelection.of(from, to)
        val plan = find(id)
        val types = plan.roomTypeIds.filter { roomTypeId == null || it == roomTypeId }
        val bands = reader.currentChildBands()
        val items =
            reader
                .effectivePrices(plan, types, selection.from, selection.to)
                .entries
                .sortedWith(compareBy({ it.key.date }, { it.key.roomTypeId.value }))
                .map { (key, set) ->
                    val (current, obsolete) = bands.classify(set.children)
                    DailyPriceView(
                        key.date,
                        key.roomTypeId,
                        set.oneAdult,
                        set.twoAdults,
                        set.extraAdult,
                        current.map { (band, amount) -> ChildPriceView(band.minAge, band.maxAge, band.label, amount) },
                        obsolete.map { ChildPriceValue(it.minAge, it.maxAge, it.amount) },
                        set.withoutPrice.takeUnless { it.isEmpty() }?.let { gaps ->
                            UnpricedByDerivation(
                                gaps.oneAdult,
                                gaps.twoAdults,
                                gaps.extraAdult,
                                gaps.children.map { (min, max) -> AgeRange(min, max) },
                            )
                        },
                    )
                }
        val pricedBy = reader.pricing(plan)?.first ?: plan.id
        return RatePricesView(plan.id, plan.derivation != null, pricedBy, selection.from, selection.to, items)
    }

    @Audited(RatesAudit.UPDATE_PRICES)
    @Transactional
    fun update(
        id: RatePlanId,
        target: PriceTarget,
        input: PriceInput,
        approvalToken: String?,
    ): PriceChangeView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val children = input.children.map { ChildPrice(it.minAge, it.maxAge, it.amount) }
        val wanted = PriceSet.of(input.oneAdult, input.twoAdults, input.extraAdult, children)
        reader.currentChildBands().requireCurrent(wanted.children)
        val edit = begin(id, target)
        val changes = PriceChanges.toSet(edit.keys, edit.existing, wanted)
        authorizeOverride(edit, changes, RatesAudit.UPDATE_PRICES, approvalToken)
        if (changes.isEmpty()) return unchanged(edit)
        prices.upsert(edit.plan.id, changes.created + changes.changed, wanted, clock.instant())
        return finish(edit, changes, RatesChangeKind.PRICES_SET, wanted)
    }

    @Audited(RatesAudit.REMOVE_PRICES)
    @Transactional
    fun remove(
        id: RatePlanId,
        target: PriceTarget,
        approvalToken: String?,
    ): PriceChangeView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val edit = begin(id, target)
        val changes = PriceChanges.toRemove(edit.keys, edit.existing)
        authorizeOverride(edit, changes, RatesAudit.REMOVE_PRICES, approvalToken)
        if (changes.isEmpty()) return unchanged(edit)
        prices.remove(edit.plan.id, changes.removed)
        return finish(edit, changes, RatesChangeKind.PRICES_REMOVED, null)
    }

    // --- Apoio -------------------------------------------------------------------------------------

    /** Uma edição em andamento: o plano travado, as linhas pedidas, o que já existe nelas e os derivados. */
    private class Edit(
        val plan: RatePlan,
        val selection: PriceSelection,
        val keys: List<PriceKey>,
        val existing: Map<PriceKey, PriceSet>,
        val descendants: List<DerivedPlan>,
    ) {
        /** Publicado (H9): ativo, ou base de um plano ativo. */
        val published: Boolean get() = plan.active || descendants.any { it.active }

        val matchedDates: Int get() = keys.map { it.date }.distinct().size
    }

    /** Nada mudou: nenhuma entrada na trilha nem evento. */
    private fun unchanged(edit: Edit): PriceChangeView {
        audit.noChanges()
        return PriceChangeView(edit.plan.id, edit.matchedDates, 0, 0, 0, null, null, emptyList())
    }

    private fun begin(
        id: RatePlanId,
        target: PriceTarget,
    ): Edit {
        RatesRules.sized("roomTypeIds", target.roomTypeIds, 1, MAX_ROOM_TYPES)
        selections.requireWellFormed(target)
        prices.lockDerivationsShared()
        val plan = store.ratePlanForUpdate(id) ?: throw notFound()
        requireOwnPrices(plan, target)
        val selection = selections.resolve(target, hotel.currentBusinessDate())
        val dates = selection.dates()
        if (dates.isEmpty()) {
            throw RatesRules.invalid(
                RatesErrorCodes.RATE_PRICE_PERIOD_INVALID,
                "weekdays",
                "Nenhuma data do período cai nos dias da semana informados.",
            )
        }
        val types = target.roomTypeIds.sortedBy { it.value }
        val keys = dates.flatMap { date -> types.map { PriceKey(it, date) } }
        val existing = prices.prices(plan.id, types, selection.from, selection.to)
        return Edit(plan, selection, keys, existing, prices.descendants(plan.id))
    }

    /** O plano tem preços próprios (não é derivado) e os tipos de UH pedidos são dele. */
    private fun requireOwnPrices(
        plan: RatePlan,
        target: PriceTarget,
    ) {
        if (plan.derivation != null) {
            throw BusinessRuleException(
                RatesErrorCodes.RATE_PLAN_DERIVED,
                "O plano é derivado: os preços dele vêm do plano base.",
            )
        }
        if (!plan.roomTypeIds.containsAll(target.roomTypeIds)) {
            throw BusinessRuleException(
                RatesErrorCodes.ROOM_TYPE_NOT_IN_PLAN,
                "Algum tipo de UH informado não está no plano.",
            )
        }
    }

    /** Alterar ou retirar um preço gravado de um plano publicado pede `rates.prices.override` ou a aprovação (H9). */
    private fun authorizeOverride(
        edit: Edit,
        changes: PriceChanges,
        action: String,
        approvalToken: String?,
    ) {
        if (!changes.touchesRecordedPrices || !edit.published) return
        authorization.requireOrApproval(
            SensitiveAction(action, RatesPermissions.PRICES_OVERRIDE, "rates.rate-plan:${edit.plan.id}"),
            approvalToken,
        )
    }

    private fun finish(
        edit: Edit,
        changes: PriceChanges,
        kind: RatesChangeKind,
        wanted: PriceSet?,
    ): PriceChangeView {
        val touched = changes.all
        val from = touched.minOf { it.date }
        val to = touched.maxOf { it.date }
        val types = touched.map { it.roomTypeId }.distinct().sortedBy { it.value }
        val derived = edit.descendants.map { it.id }.sortedBy { it.value }
        val previous = (changes.changed + changes.removed).mapNotNull { edit.existing[it] }.distinct()
        audit.record(
            RatesAudit.RATE_PRICES,
            edit.plan.id.value,
            mapOf(
                "recordedPrices" to edit.keys.count { edit.existing[it] != null },
                "previousValues" to previous.take(MAX_AUDITED_VALUES).map { it.auditState() },
            ),
            mapOf(
                "change" to kind.name,
                "roomTypeIds" to types.map { it.toString() },
                "from" to from.toString(),
                "to" to to.toString(),
                "weekdays" to
                    edit.selection.weekdays
                        .sorted()
                        .map { it.name },
                "prices" to wanted?.auditState(),
                "created" to changes.created.size,
                "changed" to changes.changed.size,
                "removed" to changes.removed.size,
                "published" to edit.published,
                "derivedRatePlanIds" to derived.map { it.toString() },
            ),
        )
        val now = clock.instant()
        events.publishEvent(
            RatesChanged(edit.plan.id.value, kind, types.map { it.value }, from, to, derived.map { it.value }, now),
        )
        return PriceChangeView(
            edit.plan.id,
            edit.matchedDates,
            changes.created.size,
            changes.changed.size,
            changes.removed.size,
            from,
            to,
            derived,
        )
    }

    private fun find(id: RatePlanId) = store.ratePlan(id) ?: throw notFound()

    private fun notFound() = NotFoundException(RatesErrorCodes.RATE_PLAN_NOT_FOUND, "Plano não encontrado.")

    private companion object {
        const val MAX_ROOM_TYPES = 50
        const val MAX_AUDITED_VALUES = 20
    }
}

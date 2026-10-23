package com.zeny.hotel.reservations.application

import com.zeny.hotel.billing.BillingApi
import com.zeny.hotel.billing.NewPosting
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.CancellationPolicySummary
import com.zeny.hotel.rates.GuaranteePolicySummary
import com.zeny.hotel.rates.GuaranteeType
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.PackagePricing
import com.zeny.hotel.rates.RatePlanCategory
import com.zeny.hotel.rates.RatePlanSummary
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.SaleRefusal
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.CancellationPenalty
import com.zeny.hotel.reservations.CancellationReason
import com.zeny.hotel.reservations.CancellationTerms
import com.zeny.hotel.reservations.GuaranteeMethod
import com.zeny.hotel.reservations.GuaranteeTerms
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationCancellation
import com.zeny.hotel.reservations.ReservationCancellationRequest
import com.zeny.hotel.reservations.ReservationCancelled
import com.zeny.hotel.reservations.ReservationChange
import com.zeny.hotel.reservations.ReservationChanged
import com.zeny.hotel.reservations.ReservationConfirmed
import com.zeny.hotel.reservations.ReservationGuarantee
import com.zeny.hotel.reservations.ReservationHistoryEntry
import com.zeny.hotel.reservations.ReservationHistoryId
import com.zeny.hotel.reservations.ReservationHistoryKind
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationReactivated
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.domain.CancellationPenalties
import com.zeny.hotel.reservations.domain.CancellationRules
import com.zeny.hotel.reservations.domain.GuaranteeAction
import com.zeny.hotel.reservations.domain.GuaranteeRules
import com.zeny.hotel.reservations.domain.InHouseChangeRules
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryNights
import com.zeny.hotel.reservations.domain.NightlyRates
import com.zeny.hotel.reservations.domain.ProvisionalValidity
import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationChangeRules
import com.zeny.hotel.reservations.domain.ReservationFields
import com.zeny.hotel.reservations.domain.ReservationTransitions
import com.zeny.hotel.reservations.domain.StayNotSellableException
import com.zeny.hotel.reservations.domain.StayRepricing
import com.zeny.hotel.reservations.domain.StayShape
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Como o cancelamento trata a isenção da multa: com o step-up (a API HTTP) ou já autorizada por quem chama. */
sealed interface WaiverAuthorization {
    /** Pela permissão sensível `reservations.penalties.waive` ou pela aprovação de superior. */
    data class Required(
        val approvalToken: String?,
    ) : WaiverAuthorization

    /** Quem chama já se autorizou (a `ReservationsApi`). */
    data object AlreadyAuthorized : WaiverAuthorization
}

/**
 * O histórico (*append-only*) e a trilha de uma escrita na reserva (sem texto livre nem dado de
 * hóspede): uma entrada no
 * histórico com o retrato antes e depois e uma entrada da reserva na trilha, com os campos mudados.
 */
@Component
class ReservationHistoryRecorder(
    private val store: BookingStore,
    private val hotel: HotelApi,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
) {
    @Suppress("LongParameterList") // a entrada inteira do histórico
    fun record(
        kind: ReservationHistoryKind,
        before: ReservationSummary,
        after: ReservationSummary,
        fields: List<String>,
        approval: SensitiveAuthorization.ByApproval?,
        now: Instant,
    ) {
        store.insertHistory(
            ReservationHistoryEntry(
                id = ReservationHistoryId.new(),
                reservationId = after.id,
                kind = kind,
                businessDate = hotel.currentBusinessDate(),
                occurredAt = now,
                actorId = currentActor.currentOrNull()?.userId,
                approvalId = approval?.approvalId,
                approvedBy = approval?.approverId,
                changedFields = fields,
                before = ReservationsAudit.state(before),
                after = ReservationsAudit.state(after),
                version = after.version,
            ),
        )
        audit.record(
            ReservationsAudit.RESERVATION,
            after.id.value,
            ReservationsAudit.reservationSnapshot(before),
            ReservationsAudit.reservationSnapshot(after) + ("changedFields" to fields),
        )
    }
}

/** A reserva com os alertas da lista de restrição dos hóspedes, lidos na hora (RF-HOS-04). */
@Component
class ReservationViews(
    private val store: BookingStore,
    private val guests: GuestsApi,
    private val rooms: RoomsApi,
) {
    fun of(reservation: ReservationSummary): ReservationView {
        val booking = checkNotNull(store.booking(reservation.bookingId)) { "Reserva sem a reserva agrupadora" }
        val alerts: List<RestrictionAlert> =
            (listOf(booking.holderGuestId) + reservation.guests.map { it.guestId })
                .distinct()
                .mapNotNull { guests.restrictionAlert(it) }
                .distinctBy { it.guestId }
        val roomNumber = reservation.roomAssignment?.let { rooms.room(it.roomId)?.number }
        return ReservationView(reservation, booking.code.toString(), booking.holderGuestId, alerts, roomNumber)
    }

    /** A reserva agrupadora com os alertas da lista de restrição dos hóspedes e os números das UHs alocadas. */
    fun bookingView(booking: BookingSummary): BookingView {
        val alerts =
            (listOf(booking.holderGuestId) + booking.reservations.flatMap { r -> r.guests.map { it.guestId } })
                .distinct()
                .mapNotNull { guests.restrictionAlert(it) }
                .distinctBy { it.guestId }
        return BookingView(booking, alerts, roomNumbers(booking.reservations))
    }

    /** O número atual da UH alocada de cada reserva que tem uma (E10). */
    fun roomNumbers(reservations: List<ReservationSummary>): Map<ReservationId, String> =
        reservations
            .mapNotNull { reservation ->
                reservation.roomAssignment
                    ?.let { rooms.room(it.roomId)?.number }
                    ?.let { reservation.id to it }
            }.toMap()

    fun holderOf(reservation: ReservationSummary): GuestId =
        checkNotNull(store.booking(reservation.bookingId)) { "Reserva sem a reserva agrupadora" }.holderGuestId
}

/**
 * O ciclo de vida de uma reserva depois de criada (M4 E08; RF-RES-05 e 06, RF-TAR-07, RN-14, RN-15, SPEC §8.4, H6,
 * H15), **na transação de escrita e no caso de uso auditado de quem chama** ([ReservationLifecycleService]). Toda
 * escrita começa travando a linha da reserva ([BookingStore.reservationForUpdate]) e confere a transição
 * ([ReservationTransitions]); depois, na ordem, o inventário (as linhas travadas em ordem de tipo e data) e a conta (a
 * multa pela `BillingApi`). Uma recusa em qualquer passo desfaz tudo, inclusive as unidades e a multa.
 *
 * - **Alteração** ([change]): recalcula só as noites afetadas ([StayRepricing]), as mantidas guardam o preço
 *   congelado; confere a venda só no que mudou; devolve as unidades antigas e toma as novas numa só
 *   `RoomTypeInventory.apply`; plano novo copia as políticas vigentes dele (RN-15); o desconto novo passa pelo step-up
 *   (H7).
 * - **Cancelamento** ([cancel]): a multa pela política **copiada** e pelo prazo (H15), lançada na conta da reserva
 *   no código `CANCELLATION_FEE` com origem `MANUAL` (H6) ou isentada com aprovação (`reservations.penalties.waive`);
 *   as unidades a partir da data operacional voltam ao inventário.
 * - **Reativação** ([reactivate]): ação sensível (`reservations.reservations.reactivate`); toma as unidades de novo
 *   (sem disponibilidade, `409` e nada muda) e a reserva volta a confirmada, com o preço congelado.
 *
 * - **Confirmação** ([confirm], E09): a pré-reserva com a garantia da política copiada; no depósito, o total líquido
 *   de depósitos da conta pela `BillingApi`.
 * - **Expiração** ([expire], E09): a pré-reserva vencida é cancelada com o motivo `EXPIRED`, sem multa.
 *
 * A UH alocada (E10, [RoomAssignments]) acompanha: o cancelamento e a expiração a soltam; a alteração a mantém se
 * continuar livre e sem OOO no período novo, senão a solta ("não mover" recusa a alteração); a reativação volta sem UH.
 *
 * Cada uma grava uma entrada no histórico (*append-only*), a trilha (sem texto livre nem dado de hóspede) e o evento
 * pelo outbox ([ReservationChanged], [ReservationCancelled], [ReservationReactivated], [ReservationConfirmed]).
 */
@Component
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass") // as portas e os passos do ciclo de vida
class ReservationLifecycle(
    private val store: BookingStore,
    private val history: ReservationHistoryRecorder,
    private val rates: RatesApi,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val billing: BillingApi,
    private val inventory: RoomTypeInventory,
    private val assignments: RoomAssignments,
    private val blockUnits: BlockUnits,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // --- Alteração ------------------------------------------------------------------------------------------------

    fun change(
        id: ReservationId,
        expectedVersion: Long?,
        command: ReservationChange,
        discount: DiscountAuthorization,
    ): ReservationSummary {
        requireWriteTransaction()
        val reservation = locked(id)
        expectedVersion?.let { requireCurrentVersion(it, reservation.version) }
        ReservationTransitions.require(ReservationAction.CHANGE, reservation.status)
        requireNotExpired(reservation, now())
        ReservationChangeRules.requireValid(command, reservation.guests.size)
        val old = StayShape.of(reservation)
        val new = StayShape.of(command)
        // O hospedado (M5 E01) só muda a saída (extensão e redução); a chegada, o tipo, o plano e a ocupação ficam.
        val inHouse = reservation.status == ReservationStatus.IN_HOUSE
        if (inHouse) InHouseChangeRules.require(old, new, hotel.currentBusinessDate())
        val restay =
            when {
                old == new -> null

                // A reserva do pick-up (E11) guarda a tarifa do grupo: só a ocupação muda, sem recalcular as noites.
                reservation.blockId != null -> requireBlockStay(reservation, old, new).let { null }

                else -> restay(reservation, old, new, command)
            }
        if (inHouse && restay != null) {
            InHouseChangeRules.requireNoPastNight(
                restay.repriced.map { it.date },
                reservation.nightlyRates.map { it.date },
                hotel.currentBusinessDate(),
            )
        }
        if (restay == null) ReservationChangeRules.requireOverridesOn(command, emptyList())
        val approval = restay?.let { authorizeDiscount(reservation, it.repriced, discount) }
        val candidate = candidate(reservation, command, restay, approval)
        val fields = ReservationFields.changed(reservation, candidate)
        if (fields.isEmpty()) {
            audit.noChanges()
            return reservation
        }
        if (restay != null) inventory.apply(restay.inventoryChanges)
        val now = now()
        // A UH alocada acompanha a estadia nova (E10): fica se continua livre, ou é solta; "não mover" recusa soltar. A
        // do hospedado (M5 E01) nunca é solta: vai até a saída nova, ou a alteração é recusada.
        val assignment =
            if (inHouse) {
                assignments.followInHouseChange(reservation, candidate)
            } else {
                assignments.followChange(reservation, candidate, now)
            }
        val updated = candidate.copy(version = reservation.version + 1, roomAssignment = assignment)
        store.update(updated, now, replaceNights = updated.nightlyRates != reservation.nightlyRates)
        record(ReservationHistoryKind.CHANGE, reservation, updated, fields, approval, now)
        events.publishEvent(changed(reservation, updated, fields, now))
        return updated
    }

    /** A reserva como fica depois da alteração (ainda com a versão anterior). */
    private fun candidate(
        reservation: ReservationSummary,
        command: ReservationChange,
        restay: Restay?,
        approval: SensitiveAuthorization.ByApproval?,
    ): ReservationSummary {
        val nights = restay?.nights ?: reservation.nightlyRates
        val (roomTotal, additional, total) = NightlyRates.totals(nights)
        val guaranteeTerms = restay?.guaranteeTerms ?: reservation.guaranteeTerms
        // Num plano que não exige garantia, a pré-reserva vira confirmada (como na criação, InitialStatus).
        val confirm = reservation.status == ReservationStatus.PROVISIONAL && guaranteeTerms.type == GuaranteeType.NONE
        val discounted = nights.any { it.discountPercent != null }
        return reservation.copy(
            status = if (confirm) ReservationStatus.CONFIRMED else reservation.status,
            provisionalUntil = if (confirm) null else reservation.provisionalUntil,
            roomTypeId = command.roomTypeId,
            ratePlanId = command.ratePlanId,
            ratePlanCategory = restay?.ratePlanCategory ?: reservation.ratePlanCategory,
            arrival = command.arrival,
            departure = command.departure,
            adults = command.adults,
            childAges = command.childAges,
            segment = command.segment,
            specialRequests = command.specialRequests,
            nightlyRates = nights,
            roomTotal = roomTotal,
            additionalPackagesTotal = additional,
            total = total,
            cancellationTerms = restay?.cancellationTerms ?: reservation.cancellationTerms,
            guaranteeTerms = guaranteeTerms,
            approvalId = approval?.approvalId ?: reservation.approvalId.takeIf { discounted },
            approvedBy = approval?.approverId ?: reservation.approvedBy.takeIf { discounted },
        )
    }

    /** A estadia nova conferida, precificada nas noites afetadas e com as mudanças do inventário. */
    private data class Restay(
        val nights: List<NightlyRateSummary>,
        val repriced: List<NightlyRateSummary>,
        /** Os termos das políticas do plano novo (RN-15); nulos quando o plano não mudou (ficam os copiados). */
        val cancellationTerms: CancellationTerms?,
        val guaranteeTerms: GuaranteeTerms?,
        val inventoryChanges: List<InventoryChange>,
        /** A categoria do plano da estadia nova (Q-15; E12), copiada para as views de relatório. */
        val ratePlanCategory: RatePlanCategory,
    )

    private fun restay(
        reservation: ReservationSummary,
        old: StayShape,
        new: StayShape,
        command: ReservationChange,
    ): Restay {
        val plan = planOf(new)
        val channel = checkNotNull(store.booking(reservation.bookingId)) { "Reserva sem a agrupadora" }.channel
        val oldArrivalPerStay =
            reservation.nightlyRates
                .firstOrNull { it.date == reservation.arrival }
                ?.packages
                .orEmpty()
                .any { it.pricing == PackagePricing.PER_STAY }
        val affected = StayRepricing.affectedNights(old, new, oldArrivalPerStay)
        val sellability = withStayFields { rates.isSellable(query(new, new.arrival, new.departure), channel) }
        // A redução do hospedado devolve noites, não vende: estadia mínima/máxima, CTD e fechamento não a recusam (D3).
        val shortensInHouse = reservation.status == ReservationStatus.IN_HOUSE && StayRepricing.onlyShortens(old, new)
        val relevant = { StayRepricing.relevantRefusals(sellability.refusals, old, new, affected) }
        val refusals = if (shortensInHouse) emptyList() else relevant()
        if (refusals.isNotEmpty()) throw StayNotSellableException(null, refusals)
        ReservationChangeRules.requireOverridesOn(command, affected)
        val repriced =
            NightlyRates.freeze(
                prices(new, affected),
                plan.id,
                command.nightlyRates.associate { it.date to it.roomAmount },
            )
        val kept = reservation.nightlyRates.filter { it.date !in affected }
        val nights = StayRepricing.merge(kept, repriced, new.nights)
        val planChanged = plan.id != reservation.ratePlanId
        val businessDate = hotel.currentBusinessDate()
        val changes =
            listOfNotNull(
                InventoryNights.of(old.roomTypeId, old.arrival, old.departure, businessDate, take = false),
                InventoryNights.of(new.roomTypeId, new.arrival, new.departure, businessDate, take = true),
            )
        return Restay(
            nights = nights,
            repriced = repriced,
            cancellationTerms =
                if (planChanged) {
                    checkNotNull(rates.cancellationPolicy(plan.cancellationPolicyId)) { "Plano sem a política" }.terms()
                } else {
                    null
                },
            guaranteeTerms =
                if (planChanged) {
                    checkNotNull(rates.guaranteePolicy(plan.guaranteePolicyId)) { "Plano sem a política" }.terms()
                } else {
                    null
                },
            inventoryChanges = changes,
            ratePlanCategory = plan.category,
        )
    }

    /**
     * A estadia de uma reserva do pick-up (E11): o tipo de UH, o plano e as datas ficam
     * (`409 ROOM_BLOCK_RESERVATION_STAY_FIXED`); a ocupação nova respeita a máxima do tipo e a idade das crianças.
     */
    @Suppress("ThrowsCount") // a estadia fixa, as idades e a ocupação
    private fun requireBlockStay(
        reservation: ReservationSummary,
        old: StayShape,
        new: StayShape,
    ) {
        if (old.copy(adults = new.adults, childAges = new.childAges) != new) {
            throw ConflictException(
                ReservationsErrorCodes.ROOM_BLOCK_RESERVATION_STAY_FIXED,
                "A reserva ${reservation.code} veio de um bloqueio: o tipo de UH, o plano e as datas não mudam.",
            )
        }
        val maxChildAge = hotel.policy().childMaxAge
        val invalidAges =
            new.childAges.withIndex().filter { (_, age) -> age !in 0..maxChildAge }.map { (index, _) ->
                FieldViolation(
                    "childAges[$index]",
                    RatesErrorCodes.STAY_INVALID.value,
                    "Informe a idade da criança de 0 a $maxChildAge.",
                )
            }
        if (invalidAges.isNotEmpty()) {
            throw InvalidInputException(RatesErrorCodes.STAY_INVALID, invalidAges.first().message, invalidAges)
        }
        val occupancy = checkNotNull(rooms.roomType(new.roomTypeId)) { "Reserva de um tipo inexistente" }.occupancy
        if (new.adults > occupancy.maxAdults || new.childAges.size > occupancy.maxChildren) {
            throw StayNotSellableException(
                null,
                listOf(
                    SaleRefusal(
                        RatesErrorCodes.OCCUPANCY_EXCEEDS_ROOM_TYPE,
                        "A ocupação passa da máxima do tipo de UH.",
                    ),
                ),
            )
        }
    }

    /** O plano e o tipo de UH pedidos (inexistentes: `400` com o campo). */
    private fun planOf(new: StayShape): RatePlanSummary {
        val plan =
            rates.ratePlan(new.ratePlanId)
                ?: throw invalid(
                    ReservationsErrorCodes.UNKNOWN_RATE_PLAN,
                    "ratePlanId",
                    "O plano tarifário não existe.",
                )
        rooms.roomType(new.roomTypeId)
            ?: throw invalid(ReservationsErrorCodes.UNKNOWN_ROOM_TYPE, "roomTypeId", "O tipo de UH não existe.")
        return plan
    }

    /**
     * O preço das noites afetadas: a estadia inteira quando todas mudam (ou no day use), senão cada sequência contínua
     * como uma estadia, sem os pacotes por estadia fora da noite da chegada ([StayRepricing.withoutPerStayPackages]).
     */
    private fun prices(
        new: StayShape,
        affected: List<LocalDate>,
    ): List<NightPrice> =
        when {
            affected.isEmpty() -> {
                emptyList()
            }

            new.dayUse || affected == new.nights -> {
                priced(query(new, new.arrival, new.departure))
            }

            else -> {
                StayRepricing.runs(affected).flatMap { (from, until) ->
                    val nights = priced(query(new, from, until))
                    if (from == new.arrival) nights else nights.map(StayRepricing::withoutPerStayPackages)
                }
            }
        }

    private fun priced(query: StayQuery): List<NightPrice> =
        when (val result = withStayFields { rates.priceFor(query) }) {
            is StayPriceResult.Priced -> result.price.nights
            is StayPriceResult.NotPriced -> throw StayNotSellableException(null, result.refusals)
        }

    private fun query(
        stay: StayShape,
        from: LocalDate,
        until: LocalDate,
    ) = StayQuery(stay.ratePlanId, stay.roomTypeId, from, until, stay.adults, stay.childAges)

    /** O desconto das diárias editadas nas noites recalculadas (H7): o maior, com o step-up na API HTTP. */
    private fun authorizeDiscount(
        reservation: ReservationSummary,
        repriced: List<NightlyRateSummary>,
        discount: DiscountAuthorization,
    ): SensitiveAuthorization.ByApproval? {
        val maxDiscount = NightlyRates.maxDiscount(repriced)
        if (maxDiscount == null || discount !is DiscountAuthorization.Required) return null
        val outcome =
            authorization.requireOrApproval(
                SensitiveAction(
                    ReservationsAudit.CHANGE_RESERVATION,
                    RatesPermissions.DISCOUNTS_APPLY,
                    resourceRef(reservation.id),
                    limit = LimitAmount(RatesPermissions.MAX_PERCENT, maxDiscount.value),
                ),
                discount.approvalToken,
            )
        return outcome as? SensitiveAuthorization.ByApproval
    }

    // --- Cancelamento ---------------------------------------------------------------------------------------------

    fun cancel(
        id: ReservationId,
        request: ReservationCancellationRequest,
        waiver: WaiverAuthorization,
    ): ReservationSummary {
        requireWriteTransaction()
        CancellationRules.requireValid(request)
        val reservation = locked(id)
        ReservationTransitions.require(ReservationAction.CANCEL, reservation.status)
        val now = now()
        val (deadline, penalty) = penaltyOf(reservation, now)
        val due = CancellationPenalties.due(penalty, reservation.penaltiesCharged)
        val waived = due.isPositive() && request.waivePenalty
        val approval = if (waived) authorizeWaiver(reservation, waiver) else null
        return applyCancellation(
            reservation,
            request.reason,
            request.notes,
            PenaltyOutcome(deadline, due, waived, approval),
            now,
        )
    }

    /** A decisão sobre a multa de um cancelamento: o prazo, o devido, se foi isentada e a aprovação da isenção. */
    private data class PenaltyOutcome(
        val deadline: Instant,
        val due: Money,
        val waived: Boolean,
        val approval: SensitiveAuthorization.ByApproval?,
    )

    /**
     * Cancela a reserva já travada e conferida: devolve as unidades a partir da data operacional, lança a multa devida
     * não isentada, grava a reserva, o histórico e a trilha e publica [ReservationCancelled].
     */
    private fun applyCancellation(
        reservation: ReservationSummary,
        reason: CancellationReason,
        notes: String?,
        penalty: PenaltyOutcome,
        now: Instant,
    ): ReservationSummary {
        val businessDate = hotel.currentBusinessDate()
        // As unidades a partir da data operacional voltam ao inventário ou, na reserva do pick-up de um bloqueio ativo,
        // ao bloqueio (E11), numa só mudança.
        inventory.apply(blockUnits.onCancellation(reservation, businessDate, now))
        val posting = if (penalty.due.isPositive() && !penalty.waived) postPenalty(reservation, penalty.due) else null
        // A UH alocada é solta (E10): a reserva cancelada ou expirada não bloqueia mais a UH.
        assignments.releaseOnCancellation(reservation, now)
        val updated =
            reservation.copy(
                status = ReservationStatus.CANCELLED,
                roomAssignment = null,
                provisionalUntil = null,
                cancellation =
                    ReservationCancellation(
                        reason = reason,
                        notes = notes,
                        previousStatus = reservation.status,
                        cancelledAt = now,
                        cancelledBy = actor(),
                        penalty =
                            CancellationPenalty(
                                deadline = penalty.deadline,
                                amount = penalty.due,
                                waived = penalty.waived,
                                postingId = posting?.id,
                                waiverApprovalId = penalty.approval?.approvalId,
                                waiverApprovedBy = penalty.approval?.approverId,
                            ),
                    ),
                penaltiesCharged = reservation.penaltiesCharged + (posting?.amount ?: Money.ZERO),
                version = reservation.version + 1,
            )
        store.update(updated, now, replaceNights = false)
        record(ReservationHistoryKind.CANCEL, reservation, updated, listOf("status"), penalty.approval, now)
        events.publishEvent(cancelled(reservation, updated))
        return updated
    }

    // --- Pré-reserva: confirmação e expiração (E09) ----------------------------------------------------------------

    /**
     * Confirma a pré-reserva com a garantia [method] (RF-RES-08 no recorte manual, SPEC §8.4 "garantia recebida"), ou
     * registra a garantia de uma reserva confirmada sem garantia ([GuaranteeRules.actionFor]): travada, a pré-reserva
     * ainda na validade ([ProvisionalValidity]), com a garantia que a política copiada pede ([GuaranteeRules]) e, no
     * depósito, o total líquido de depósitos da conta da reserva (`BillingApi.netDeposits`, lido depois da trava da
     * reserva) cobrindo o exigido. A pré-reserva vira confirmada (histórico `CONFIRM` e [ReservationConfirmed]); a
     * confirmada só ganha a garantia (histórico `GUARANTEE`, sem evento). O inventário não muda.
     */
    fun confirm(
        id: ReservationId,
        method: GuaranteeMethod,
    ): ReservationSummary {
        requireWriteTransaction()
        val reservation = locked(id)
        val action = GuaranteeRules.actionFor(reservation)
        val now = now()
        requireNotExpired(reservation, now)
        GuaranteeRules.requireAccepted(reservation.guaranteeTerms, method)
        var required: Money? = null
        var received: Money? = null
        if (method == GuaranteeMethod.DEPOSIT) {
            required = reservation.requiredDeposit ?: Money.ZERO
            received = billing.netDeposits(reservation.folioId)
            GuaranteeRules.requireDeposit(required, received)
        }
        val updated =
            reservation.copy(
                status = ReservationStatus.CONFIRMED,
                provisionalUntil = null,
                guarantee = ReservationGuarantee(method, now, actor(), required, received),
                version = reservation.version + 1,
            )
        store.update(updated, now, replaceNights = false)
        if (action == GuaranteeAction.REGISTER) {
            // A reserva já estava confirmada: só a garantia muda, sem evento (nada que o channel manager precise).
            record(ReservationHistoryKind.GUARANTEE, reservation, updated, listOf("guarantee"), null, now)
            return updated
        }
        record(ReservationHistoryKind.CONFIRM, reservation, updated, listOf("status", "guarantee"), null, now)
        events.publishEvent(
            ReservationConfirmed(
                reservationId = updated.id.value,
                bookingId = updated.bookingId.value,
                roomTypeId = updated.roomTypeId.value,
                arrival = updated.arrival,
                departure = updated.departure,
                dayUse = updated.dayUse,
                guaranteeMethod = method,
                version = updated.version,
                confirmedAt = now,
            ),
        )
        return updated
    }

    /**
     * A pré-reserva com a validade vencida não é mais confirmada nem alterada (RN-23; o job de expiração a cancela):
     * `422 PROVISIONAL_RESERVATION_EXPIRED`. As outras situações passam.
     */
    private fun requireNotExpired(
        reservation: ReservationSummary,
        now: Instant,
    ) {
        val until = reservation.provisionalUntil ?: return
        if (reservation.status == ReservationStatus.PROVISIONAL && ProvisionalValidity.expired(until, now)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.PROVISIONAL_RESERVATION_EXPIRED,
                "A validade da pré-reserva ${reservation.code} já passou: ela não é mais confirmada nem alterada.",
            )
        }
    }

    /**
     * Expira a pré-reserva vencida (RN-23): trava a linha e confere **de novo** que ainda é pré-reserva e que a
     * validade passou (uma confirmação ou um cancelamento confirmados antes da trava a tiram daqui: nada muda e devolve
     * `null`). Vencida, é cancelada com o motivo [CancellationReason.EXPIRED], **sem multa** (a pré-reserva não foi
     * garantida, e não é o hóspede quem cancela), com as unidades devolvidas a partir da data operacional, o histórico
     * e [ReservationCancelled].
     */
    fun expire(id: ReservationId): ReservationSummary? {
        requireWriteTransaction()
        val reservation = locked(id)
        val until = reservation.provisionalUntil
        val now = now()
        if (reservation.status != ReservationStatus.PROVISIONAL ||
            until == null ||
            !ProvisionalValidity.expired(until, now)
        ) {
            audit.noChanges()
            return null
        }
        val (deadline, _) = penaltyOf(reservation, now)
        return applyCancellation(
            reservation,
            CancellationReason.EXPIRED,
            null,
            PenaltyOutcome(deadline, Money.ZERO, waived = false, approval = null),
            now,
        )
    }

    /** O prazo da multa e o valor dela pela política copiada (zero antes do prazo). */
    private fun penaltyOf(
        reservation: ReservationSummary,
        now: Instant,
    ): Pair<Instant, Money> {
        val terms = reservation.cancellationTerms
        val deadline =
            CancellationPenalties.deadline(
                reservation.arrival,
                hotel.policy().checkInTime,
                hotel.timezone(),
                terms.deadlineHours,
            )
        val penalty =
            if (CancellationPenalties.applies(now, deadline)) {
                CancellationPenalties.amount(terms, reservation.nightlyRates, reservation.roomTotal)
            } else {
                Money.ZERO
            }
        return deadline to penalty
    }

    /** A multa na conta da reserva (H6): o código `CANCELLATION_FEE`, a origem `MANUAL`, na data operacional. */
    private fun postPenalty(
        reservation: ReservationSummary,
        due: Money,
    ) = billing.post(
        reservation.folioId,
        NewPosting(
            transactionCodeId = billing.transactionCode(TransactionCodeKey.CANCELLATION_FEE).id,
            unitPrice = due,
            quantity = BigDecimal.ONE,
            description = "Multa de cancelamento da reserva ${reservation.code}",
            source = PostingSource.MANUAL,
        ),
    )

    private fun authorizeWaiver(
        reservation: ReservationSummary,
        waiver: WaiverAuthorization,
    ): SensitiveAuthorization.ByApproval? {
        if (waiver !is WaiverAuthorization.Required) return null
        val outcome =
            authorization.requireOrApproval(
                SensitiveAction(
                    ReservationsAudit.CANCEL_RESERVATION,
                    ReservationsPermissions.PENALTIES_WAIVE,
                    resourceRef(reservation.id),
                ),
                waiver.approvalToken,
            )
        return outcome as? SensitiveAuthorization.ByApproval
    }

    // --- Reativação -----------------------------------------------------------------------------------------------

    fun reactivate(
        id: ReservationId,
        approvalToken: String?,
    ): ReservationSummary {
        requireWriteTransaction()
        val reservation = locked(id)
        ReservationTransitions.require(ReservationAction.REACTIVATE, reservation.status)
        val businessDate = hotel.currentBusinessDate()
        if (!InventoryNights.hasFutureNight(reservation.arrival, reservation.departure, businessDate)) {
            throw BusinessRuleException(
                ReservationsErrorCodes.RESERVATION_STAY_ENDED,
                "A estadia da reserva já terminou e ela não pode ser reativada.",
            )
        }
        val outcome =
            authorization.requireOrApproval(
                SensitiveAction(
                    ReservationsAudit.REACTIVATE_RESERVATION,
                    ReservationsPermissions.RESERVATIONS_REACTIVATE,
                    resourceRef(reservation.id),
                ),
                approvalToken,
            )
        val now = now()
        // Toma as unidades de novo: do bloqueio ativo, na reserva do pick-up (E11), ou do inventário geral.
        inventory.apply(blockUnits.onReactivation(reservation, businessDate, now))
        val updated =
            reservation.copy(
                status = ReservationStatus.CONFIRMED,
                provisionalUntil = null,
                cancellation = null,
                version = reservation.version + 1,
            )
        store.update(updated, now, replaceNights = false)
        val approval = outcome as? SensitiveAuthorization.ByApproval
        record(ReservationHistoryKind.REACTIVATE, reservation, updated, listOf("status"), approval, now)
        events.publishEvent(
            ReservationReactivated(
                reservationId = updated.id.value,
                bookingId = updated.bookingId.value,
                previousStatus = reservation.status,
                roomTypeId = updated.roomTypeId.value,
                arrival = updated.arrival,
                departure = updated.departure,
                dayUse = updated.dayUse,
                version = updated.version,
                reactivatedAt = now,
            ),
        )
        return updated
    }

    // --- Comum ----------------------------------------------------------------------------------------------------

    private fun locked(id: ReservationId): ReservationSummary =
        store.reservationForUpdate(id)
            ?: throw NotFoundException(ReservationsErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")

    @Suppress("LongParameterList") // a entrada inteira do histórico
    private fun record(
        kind: ReservationHistoryKind,
        before: ReservationSummary,
        after: ReservationSummary,
        fields: List<String>,
        approval: SensitiveAuthorization.ByApproval?,
        now: Instant,
    ) = history.record(kind, before, after, fields, approval, now)

    private fun changed(
        before: ReservationSummary,
        after: ReservationSummary,
        fields: List<String>,
        now: Instant,
    ) = ReservationChanged(
        reservationId = after.id.value,
        bookingId = after.bookingId.value,
        status = after.status,
        roomTypeId = after.roomTypeId.value,
        previousRoomTypeId = before.roomTypeId.value,
        ratePlanId = after.ratePlanId.value,
        previousRatePlanId = before.ratePlanId.value,
        arrival = after.arrival,
        previousArrival = before.arrival,
        departure = after.departure,
        previousDeparture = before.departure,
        dayUse = after.dayUse,
        adults = after.adults,
        children = after.childAges.size,
        changedFields = fields,
        version = after.version,
        changedAt = now,
    )

    private fun cancelled(
        before: ReservationSummary,
        after: ReservationSummary,
    ): ReservationCancelled {
        val cancellation = checkNotNull(after.cancellation)
        return ReservationCancelled(
            reservationId = after.id.value,
            bookingId = after.bookingId.value,
            previousStatus = before.status,
            roomTypeId = after.roomTypeId.value,
            arrival = after.arrival,
            departure = after.departure,
            dayUse = after.dayUse,
            reason = cancellation.reason,
            penaltyCharged = cancellation.penalty.postingId != null,
            penaltyWaived = cancellation.penalty.waived,
            version = after.version,
            cancelledAt = cancellation.cancelledAt,
        )
    }

    private fun actor(): UserId? = currentActor.currentOrNull()?.userId

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun resourceRef(id: ReservationId) = "${ReservationsAudit.RESERVATION}:$id"

    private fun requireWriteTransaction() {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "A reserva só muda dentro da transação de escrita de quem chama" }
    }

    /** O formato da estadia recusado pelo `rates` (`400 STAY_INVALID`), com os campos da alteração. */
    private fun <T> withStayFields(block: () -> T): T =
        try {
            block()
        } catch (e: InvalidInputException) {
            if (e.code != RatesErrorCodes.STAY_INVALID) throw e
            throw InvalidInputException(e.code, e.message, e.violations, e)
        }

    private fun CancellationPolicySummary.terms() =
        CancellationTerms(id, name, version, deadlineHours, penaltyType, penaltyPercent, penaltyNights, penaltyAmount)

    private fun GuaranteePolicySummary.terms() = GuaranteeTerms(id, name, version, type, depositPercent, dueDaysBefore)

    private fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

/**
 * As escritas do ciclo de vida pela API (M4 E08): alterar e cancelar exigem `reservations.reservations.manage` (o
 * desconto novo, a permissão sensível do `rates`, H7; a isenção da multa, `reservations.penalties.waive`, sensível);
 * reativar exige `reservations.reservations.manage` e a permissão sensível `reservations.reservations.reactivate`
 * (ou a aprovação de superior); o histórico, `reservations.reservations.read`.
 */
@Service
class ReservationLifecycleService(
    private val lifecycle: ReservationLifecycle,
    private val store: BookingStore,
    private val views: ReservationViews,
    private val authorization: Authorization,
) {
    @Audited(ReservationsAudit.CHANGE_RESERVATION)
    @Transactional
    fun change(
        id: ReservationId,
        expectedVersion: Long,
        command: ReservationChange,
        approvalToken: String?,
    ): ReservationView {
        authorization.require(ReservationsPermissions.RESERVATIONS_MANAGE)
        return views.of(
            lifecycle.change(id, expectedVersion, command, DiscountAuthorization.Required(approvalToken)),
        )
    }

    @Audited(ReservationsAudit.CANCEL_RESERVATION)
    @Transactional
    fun cancel(
        id: ReservationId,
        request: ReservationCancellationRequest,
        approvalToken: String?,
    ): ReservationView {
        authorization.require(ReservationsPermissions.RESERVATIONS_MANAGE)
        return views.of(lifecycle.cancel(id, request, WaiverAuthorization.Required(approvalToken)))
    }

    /** O cancelamento pela `ReservationsApi`, sem permissão nem step-up da isenção (quem chama já se autorizou). */
    @Audited(ReservationsAudit.CANCEL_RESERVATION)
    @Transactional
    fun cancelAuthorized(
        id: ReservationId,
        request: ReservationCancellationRequest,
    ): ReservationSummary = lifecycle.cancel(id, request, WaiverAuthorization.AlreadyAuthorized)

    @Audited(ReservationsAudit.CONFIRM_RESERVATION)
    @Transactional
    fun confirm(
        id: ReservationId,
        method: GuaranteeMethod,
    ): ReservationView {
        authorization.require(ReservationsPermissions.RESERVATIONS_MANAGE)
        return views.of(lifecycle.confirm(id, method))
    }

    /** A confirmação pela `ReservationsApi`, sem permissão (quem chama já se autorizou). */
    @Audited(ReservationsAudit.CONFIRM_RESERVATION)
    @Transactional
    fun confirmAuthorized(
        id: ReservationId,
        method: GuaranteeMethod,
    ): ReservationSummary = lifecycle.confirm(id, method)

    @Audited(ReservationsAudit.REACTIVATE_RESERVATION)
    @Transactional
    fun reactivate(
        id: ReservationId,
        approvalToken: String?,
    ): ReservationView {
        authorization.require(ReservationsPermissions.RESERVATIONS_MANAGE)
        return views.of(lifecycle.reactivate(id, approvalToken))
    }

    /** O histórico da reserva, da entrada mais recente à mais antiga, por cursor. */
    @Transactional(readOnly = true)
    fun history(
        id: ReservationId,
        page: PageParams,
    ): CursorPage<ReservationHistoryEntry> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        store.reservation(id)
            ?: throw NotFoundException(ReservationsErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")
        val cursor = page.decodedCursor()
        val after = cursor?.let { it.instant(0) to ReservationHistoryId(it.uuid(1)) }
        val rows = store.history(id, after, page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.occurredAt.toString(), it.id.toString()) }
    }
}

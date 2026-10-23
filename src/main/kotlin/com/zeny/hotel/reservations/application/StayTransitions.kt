package com.zeny.hotel.reservations.application

import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.rates.NightPrice
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.rates.StayPriceResult
import com.zeny.hotel.rates.StayQuery
import com.zeny.hotel.reservations.CheckIn
import com.zeny.hotel.reservations.CheckInGuest
import com.zeny.hotel.reservations.GuestCheckInUndone
import com.zeny.hotel.reservations.GuestCheckedIn
import com.zeny.hotel.reservations.GuestCheckedOut
import com.zeny.hotel.reservations.NightlyRateSummary
import com.zeny.hotel.reservations.ReservationHistoryKind
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationStatus
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.RoomMove
import com.zeny.hotel.reservations.RoomMoveTariff
import com.zeny.hotel.reservations.RoomMoved
import com.zeny.hotel.reservations.StayGuestSummary
import com.zeny.hotel.reservations.StayTransitionRecord
import com.zeny.hotel.reservations.domain.CheckInGuestFacts
import com.zeny.hotel.reservations.domain.CheckInRules
import com.zeny.hotel.reservations.domain.CheckOutPlan
import com.zeny.hotel.reservations.domain.MinorRules
import com.zeny.hotel.reservations.domain.NightlyRates
import com.zeny.hotel.reservations.domain.ReservationAction
import com.zeny.hotel.reservations.domain.ReservationTransitions
import com.zeny.hotel.reservations.domain.RoomMoveRules
import com.zeny.hotel.reservations.domain.StayNotSellableException
import com.zeny.hotel.reservations.domain.StayRepricing
import com.zeny.hotel.reservations.domain.Stays
import com.zeny.hotel.reservations.domain.UndoCheckInRules
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * As transições de hospedagem da reserva (M5 E01; SPEC §8.2.6 e §8.4, RF-REC-01, 04, 05 e 06, RN-04, RN-14, RN-24, H10,
 * H16), **na transação de escrita e no caso de uso auditado de quem chama** ([StayTransitionsService], o `frontdesk`
 * pela `ReservationsApi`). Toda escrita começa travando a linha da reserva ([BookingStore.reservationForUpdate]) e
 * confere a transição ([ReservationTransitions]); depois, na ordem, o inventário (as linhas travadas em ordem de tipo e
 * data) e a alocação (a trava da UH). Não confere RN-05, não muda o status da UH nem a conta: isso é do `frontdesk`.
 *
 * - **Check-in** ([checkIn]): só uma confirmada, com a data operacional dentro da estadia ([CheckInRules]), a UH
 *   alocada ativa e do tipo da reserva, a lista completa dos hóspedes com os dados de check-in (mesclado vira o
 *   sobrevivente; anonimizado recusado) e a RN-24 ([MinorRules]); publica [GuestCheckedIn].
 * - **Desfazer** ([undoCheckIn]): só na mesma data operacional do check-in ([UndoCheckInRules]); a reserva volta a
 *   confirmada, sem os dados de check-in (a comprovação do menor fica no histórico); publica [GuestCheckInUndone].
 * - **Check-out** ([checkOut]): inclusive antecipado ([CheckOutPlan]: a saída passa à data operacional e as noites
 *   seguintes voltam ao inventário, ou ao bloqueio do pick-up, numa só `RoomTypeInventory.apply`); a alocação
 *   encerrada na mesma transação; publica [GuestCheckedOut].
 * - **Troca de UH** ([moveRoom]): a alocação atual termina na data operacional e outra começa nela ([RoomMoveRules]);
 *   a tarifa das noites restantes mantida ou recalculada (as anteriores ficam, RN-14); publica [RoomMoved].
 *
 * Cada uma grava uma entrada no histórico (*append-only*) e a trilha. Os eventos levam `performedBy` (H16).
 */
@Component
@Suppress("LongParameterList", "TooManyFunctions") // as portas e as quatro transições
class StayTransitions(
    private val store: BookingStore,
    private val history: ReservationHistoryRecorder,
    private val guestResolution: GuestResolution,
    private val guests: GuestsApi,
    private val rates: RatesApi,
    private val hotel: HotelApi,
    private val inventory: RoomTypeInventory,
    private val assignments: RoomAssignments,
    private val blockUnits: BlockUnits,
    private val currentActor: CurrentActor,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // --- Check-in -------------------------------------------------------------------------------------------------

    fun checkIn(
        id: ReservationId,
        command: CheckIn,
    ): ReservationSummary {
        requireWriteTransaction()
        val reservation = locked(id)
        ReservationTransitions.require(ReservationAction.CHECK_IN, reservation.status)
        val businessDate = hotel.currentBusinessDate()
        CheckInRules.requireDate(reservation, businessDate)
        CheckInRules.requireValid(command, reservation.adults + reservation.childAges.size)
        val assignment = assignments.requireForCheckIn(reservation)
        val resolved = resolve(command)
        CheckInRules.requireDistinctResolved(resolved)
        val facts = resolved.map { CheckInGuestFacts(it, guests.isMinorOn(it.guestId, businessDate)) }
        MinorRules.require(facts, businessDate)
        val now = now()
        val updated =
            reservation.copy(
                status = ReservationStatus.IN_HOUSE,
                checkIn = StayTransitionRecord(businessDate, now, actor()),
                guests =
                    resolved.map {
                        StayGuestSummary(
                            guestId = it.guestId,
                            primary = it.primary,
                            travelReason = it.travelReason,
                            transportMode = it.transportMode,
                            checkedInAt = now,
                            minorAuthorization = it.minorAuthorization,
                        )
                    },
                roomAssignment = assignment,
                version = reservation.version + 1,
            )
        store.update(updated, now, replaceNights = false, replaceGuests = true)
        history.record(
            ReservationHistoryKind.CHECK_IN,
            reservation,
            updated,
            listOf("status", "checkIn", "guests"),
            null,
            now,
        )
        events.publishEvent(
            GuestCheckedIn(
                reservationId = updated.id.value,
                bookingId = updated.bookingId.value,
                roomId = assignment.roomId.value,
                roomTypeId = updated.roomTypeId.value,
                arrival = updated.arrival,
                departure = updated.departure,
                dayUse = updated.dayUse,
                adults = updated.adults,
                children = updated.childAges.size,
                guestCount = updated.guests.size,
                businessDate = businessDate,
                performedBy = actor()?.value,
                version = updated.version,
                checkedInAt = now,
            ),
        )
        return updated
    }

    /**
     * Os hóspedes do check-in com os perfis finais (mesclado vira o sobrevivente, também o responsável pelo menor;
     * anonimizado `422 GUEST_ANONYMIZED`; inexistente `400 UNKNOWN_GUEST` com o campo), pela mesma resolução da
     * criação (as travas compartilhadas dos perfis, contra a mesclagem e a anonimização simultâneas).
     */
    private fun resolve(command: CheckIn): List<CheckInGuest> {
        val requested =
            command.guests.flatMapIndexed { index, guest ->
                listOf(guest.guestId to "guests[$index].guestId") +
                    listOfNotNull(
                        guest.minorAuthorization?.let {
                            it.responsibleGuestId to "guests[$index].minorAuthorization.responsibleGuestId"
                        },
                    )
            }
        val finals: Map<GuestId, GuestId> = guestResolution.resolve(requested)
        return command.guests.map { guest ->
            guest.copy(
                guestId = finals.getValue(guest.guestId),
                minorAuthorization =
                    guest.minorAuthorization?.let {
                        it.copy(responsibleGuestId = finals.getValue(it.responsibleGuestId))
                    },
            )
        }
    }

    // --- Desfazer o check-in -------------------------------------------------------------------------------------

    fun undoCheckIn(id: ReservationId): ReservationSummary {
        requireWriteTransaction()
        val reservation = locked(id)
        ReservationTransitions.require(ReservationAction.UNDO_CHECK_IN, reservation.status)
        val businessDate = hotel.currentBusinessDate()
        UndoCheckInRules.require(checkNotNull(reservation.checkIn) { "Hospedada sem check-in" }, businessDate)
        val roomId = checkNotNull(reservation.roomAssignment) { "Hospedada sem UH alocada" }.roomId
        val now = now()
        val updated =
            reservation.copy(
                status = ReservationStatus.CONFIRMED,
                checkIn = null,
                guests = reservation.guests.map { StayGuestSummary(it.guestId, it.primary) },
                version = reservation.version + 1,
            )
        store.update(updated, now, replaceNights = false, replaceGuests = true)
        history.record(
            ReservationHistoryKind.UNDO_CHECK_IN,
            reservation,
            updated,
            listOf("status", "checkIn", "guests"),
            null,
            now,
        )
        events.publishEvent(
            GuestCheckInUndone(
                reservationId = updated.id.value,
                bookingId = updated.bookingId.value,
                roomId = roomId.value,
                businessDate = businessDate,
                performedBy = actor()?.value,
                version = updated.version,
                undoneAt = now,
            ),
        )
        return updated
    }

    // --- Check-out ------------------------------------------------------------------------------------------------

    fun checkOut(
        id: ReservationId,
        expectedVersion: Long?,
    ): ReservationSummary {
        requireWriteTransaction()
        val reservation = locked(id)
        // A versão lida pelo chamador vale contra a reserva já travada (M5 E05, como na troca): outra alteração é 412.
        expectedVersion?.let { requireCurrentVersion(it, reservation.version) }
        ReservationTransitions.require(ReservationAction.CHECK_OUT, reservation.status)
        val businessDate = hotel.currentBusinessDate()
        val plan = CheckOutPlan.of(reservation, businessDate)
        val now = now()
        // As noites a partir da data operacional voltam ao inventário ou, na reserva do pick-up de um bloqueio ativo,
        // ao bloqueio (como no cancelamento), numa só mudança.
        if (plan.early) inventory.apply(blockUnits.onCancellation(reservation, businessDate, now))
        // A alocação é encerrada na mesma transação (Ajuste D 2 da M4 E10): o fim acompanha a saída.
        val ended =
            checkNotNull(assignments.endOnCheckOut(reservation, plan.departure, now)) { "Hospedada sem UH alocada" }
        val (roomTotal, additional, total) = NightlyRates.totals(plan.nights)
        val discounted = plan.nights.any { it.discountPercent != null }
        val updated =
            reservation.copy(
                status = ReservationStatus.CHECKED_OUT,
                departure = plan.departure,
                nightlyRates = plan.nights,
                roomTotal = roomTotal,
                additionalPackagesTotal = additional,
                total = total,
                approvalId = reservation.approvalId.takeIf { discounted },
                approvedBy = reservation.approvedBy.takeIf { discounted },
                checkOut = StayTransitionRecord(businessDate, now, actor()),
                guests = reservation.guests.map { it.copy(checkedOutAt = now) },
                roomAssignment = null,
                version = reservation.version + 1,
            )
        store.update(updated, now, replaceNights = plan.early, replaceGuests = true)
        val fields =
            buildList {
                add("status")
                add("checkOut")
                add("guests")
                if (plan.early) {
                    add("departure")
                    add("nightlyRates")
                }
            }
        history.record(ReservationHistoryKind.CHECK_OUT, reservation, updated, fields, null, now)
        events.publishEvent(
            GuestCheckedOut(
                reservationId = updated.id.value,
                bookingId = updated.bookingId.value,
                roomId = ended.roomId.value,
                roomTypeId = updated.roomTypeId.value,
                arrival = updated.arrival,
                departure = updated.departure,
                previousDeparture = reservation.departure,
                early = plan.early,
                businessDate = businessDate,
                performedBy = actor()?.value,
                version = updated.version,
                checkedOutAt = now,
            ),
        )
        return updated
    }

    // --- Troca de UH ----------------------------------------------------------------------------------------------

    fun moveRoom(
        id: ReservationId,
        move: RoomMove,
        expectedVersion: Long?,
    ): ReservationSummary {
        requireWriteTransaction()
        RoomMoveRules.requireValid(move)
        val reservation = locked(id)
        // A versão do cliente vale contra a reserva já travada (M5 E05, D5): outra alteração antes é 412.
        expectedVersion?.let { requireCurrentVersion(it, reservation.version) }
        ReservationTransitions.require(ReservationAction.MOVE_ROOM, reservation.status)
        val businessDate = hotel.currentBusinessDate()
        RoomMoveRules.requireRemaining(reservation, businessDate)
        val recalculate = move.tariff == RoomMoveTariff.RECALCULATE
        if (recalculate && reservation.blockId != null) {
            throw ConflictException(
                ReservationsErrorCodes.ROOM_BLOCK_RESERVATION_STAY_FIXED,
                "A reserva ${reservation.code} veio de um bloqueio: a tarifa do grupo não é recalculada.",
            )
        }
        val now = now()
        val (ended, created) = assignments.moveInHouse(reservation, move, businessDate, now)
        val nights = if (recalculate) repriced(reservation, businessDate) else reservation.nightlyRates
        val (roomTotal, additional, total) = NightlyRates.totals(nights)
        val discounted = nights.any { it.discountPercent != null }
        val updated =
            reservation.copy(
                nightlyRates = nights,
                roomTotal = roomTotal,
                additionalPackagesTotal = additional,
                total = total,
                approvalId = reservation.approvalId.takeIf { discounted },
                approvedBy = reservation.approvedBy.takeIf { discounted },
                roomAssignment = created,
                version = reservation.version + 1,
            )
        val repricedNights = nights != reservation.nightlyRates
        store.update(updated, now, replaceNights = repricedNights)
        val fields = listOf("roomAssignment") + listOfNotNull("nightlyRates".takeIf { repricedNights })
        history.record(ReservationHistoryKind.ROOM_MOVE, reservation, updated, fields, null, now)
        events.publishEvent(
            RoomMoved(
                reservationId = updated.id.value,
                bookingId = updated.bookingId.value,
                fromRoomId = ended.roomId.value,
                toRoomId = created.roomId.value,
                roomTypeId = updated.roomTypeId.value,
                businessDate = businessDate,
                reason = move.reason,
                tariff = move.tariff,
                performedBy = actor()?.value,
                version = updated.version,
                movedAt = now,
            ),
        )
        return updated
    }

    /**
     * As noites com a tarifa recalculada (RN-14): as da data operacional em diante pela tarifa atual do plano e do tipo
     * (`RatesApi.priceFor`, sem conferir a venda: o hóspede já está em casa; sem preço, `422` com o motivo do `rates`),
     * sem os pacotes por estadia fora da noite da chegada e sem diária editada; as anteriores ficam como estão.
     */
    private fun repriced(
        reservation: ReservationSummary,
        businessDate: LocalDate,
    ): List<NightlyRateSummary> {
        val dates = RoomMoveRules.repricedNights(reservation, businessDate)
        val from = dates.first()
        val until = reservation.departure
        val prices =
            when (
                val result =
                    rates.priceFor(
                        StayQuery(
                            reservation.ratePlanId,
                            reservation.roomTypeId,
                            from,
                            until,
                            reservation.adults,
                            reservation.childAges,
                        ),
                    )
            ) {
                is StayPriceResult.Priced -> result.price.nights
                is StayPriceResult.NotPriced -> throw StayNotSellableException(null, result.refusals)
            }
        val adjusted: List<NightPrice> =
            if (from == reservation.arrival) prices else prices.map(StayRepricing::withoutPerStayPackages)
        val fresh = NightlyRates.freeze(adjusted, reservation.ratePlanId, emptyMap())
        val kept = reservation.nightlyRates.filter { it.date.isBefore(from) }
        return StayRepricing.merge(kept, fresh, Stays.nights(reservation.arrival, reservation.departure))
    }

    // --- Comum ----------------------------------------------------------------------------------------------------

    private fun locked(id: ReservationId): ReservationSummary =
        store.reservationForUpdate(id)
            ?: throw NotFoundException(ReservationsErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")

    private fun actor(): UserId? = currentActor.currentOrNull()?.userId

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun requireWriteTransaction() {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "A reserva só muda dentro da transação de escrita de quem chama" }
    }
}

/**
 * Os casos de uso das transições de hospedagem (M5 E01): só pela `ReservationsApi`, **sem conferir permissão** (o
 * `frontdesk` se autoriza: `frontdesk.stays.checkin`, o desfazer sensível, `frontdesk.stays.checkout`,
 * `frontdesk.stays.move`, M5 E02 a E05). Cada um é auditado e abre a própria transação, ou entra na de quem chama (o
 * check-in orquestrado do `frontdesk` é uma transação só, ARCHITECTURE §6.1).
 */
@Service
class StayTransitionsService(
    private val transitions: StayTransitions,
) {
    @Audited(ReservationsAudit.CHECK_IN)
    @Transactional
    fun checkIn(
        id: ReservationId,
        command: CheckIn,
    ): ReservationSummary = transitions.checkIn(id, command)

    @Audited(ReservationsAudit.UNDO_CHECK_IN)
    @Transactional
    fun undoCheckIn(id: ReservationId): ReservationSummary = transitions.undoCheckIn(id)

    @Audited(ReservationsAudit.CHECK_OUT)
    @Transactional
    fun checkOut(
        id: ReservationId,
        expectedVersion: Long?,
    ): ReservationSummary = transitions.checkOut(id, expectedVersion)

    @Audited(ReservationsAudit.MOVE_ROOM)
    @Transactional
    fun moveRoom(
        id: ReservationId,
        move: RoomMove,
        expectedVersion: Long?,
    ): ReservationSummary = transitions.moveRoom(id, move, expectedVersion)
}

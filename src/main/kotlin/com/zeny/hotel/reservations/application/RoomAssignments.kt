package com.zeny.hotel.reservations.application

import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.notifications.ModuleNotificationTypes
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.reservations.ReallocationMark
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsNotificationTypes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.RoomAssignmentEndReason
import com.zeny.hotel.reservations.RoomAssignmentId
import com.zeny.hotel.reservations.RoomAssignmentReason
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.RoomMove
import com.zeny.hotel.reservations.RoomSuggestion
import com.zeny.hotel.reservations.domain.AssignmentPeriod
import com.zeny.hotel.reservations.domain.AssignmentRules
import com.zeny.hotel.reservations.domain.BlockingPeriod
import com.zeny.hotel.reservations.domain.ReallocationMarks
import com.zeny.hotel.reservations.domain.ReallocationNotice
import com.zeny.hotel.reservations.domain.RoomAlreadyAssignedException
import com.zeny.hotel.reservations.domain.RoomCandidate
import com.zeny.hotel.reservations.domain.RoomMoveRules
import com.zeny.hotel.reservations.domain.RoomOutOfOrderException
import com.zeny.hotel.reservations.domain.RoomSuggestions
import com.zeny.hotel.rooms.OutOfOrderChanged
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomSummary
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Os tipos de notificação do `reservations` no catálogo do `notifications` (H11). */
@Configuration(proxyBeanMethods = false)
class ReservationsNotificationsConfiguration {
    @Bean
    fun reservationsNotificationTypes(): ModuleNotificationTypes = ReservationsNotificationTypes
}

/** Uma alocação com o número atual da UH (lido na hora pela `RoomsApi`; nulo se a UH sumiu). */
data class RoomAssignmentView(
    val assignment: RoomAssignmentSummary,
    val roomNumber: String?,
)

/** As UHs sugeridas para uma reserva e se havia preferência de andar do hóspede a considerar. */
data class RoomSuggestionsView(
    val reservation: ReservationSummary,
    val floorPreferenceConsidered: Boolean,
    val suggestions: List<RoomSuggestion>,
)

/** Uma reserva que requer realocação, com o número atual da UH. */
data class ReallocationView(
    val row: ReallocationRow,
    val roomNumber: String?,
)

/**
 * A alocação de UH de uma reserva já travada por quem chama (M4 E10; RF-RES-04, RN-04, RN-07, RF-UH-05), na transação
 * de escrita e no caso de uso auditado de quem chama ([RoomAssignmentService] e o ciclo de vida da reserva). Toma a
 * trava das UHs envolvidas ([RoomAssignmentStore.lockRooms]) antes de ler os períodos OOO e de gravar; a sobreposição
 * com outra alocação ativa da UH é recusada pelo banco (`room_assignment_overlap_ex`) e vira `409
 * ROOM_ALREADY_ASSIGNED`.
 */
@Component
@Suppress("LongParameterList", "TooManyFunctions") // as portas e os passos da alocação
class RoomAssignments(
    private val store: RoomAssignmentStore,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /**
     * Aloca a UH [roomId] à reserva ou, se ela já tem outra, realoca (a anterior fica solta como `REALLOCATED`; com
     * "não mover", `409 ROOM_ASSIGNMENT_LOCKED`). A mesma UH de novo só troca "não mover", se pedido. O período é a
     * estadia inteira da reserva.
     */
    @Suppress("ReturnCount") // a mesma UH (nada ou só "não mover") e a alocação nova
    fun assign(
        reservation: ReservationSummary,
        roomId: RoomId,
        reason: RoomAssignmentReason?,
        doNotMove: Boolean?,
    ): RoomAssignmentSummary {
        AssignmentRules.requireAssignable(reservation, hotel.currentBusinessDate())
        val room = AssignmentRules.requireRoom(rooms.room(roomId), reservation)
        val current = store.active(reservation.id)
        if (current != null && current.roomId == roomId) {
            if (doNotMove == null || doNotMove == current.doNotMove) {
                audit.noChanges()
                return current
            }
            return updateDoNotMove(reservation, current, doNotMove)
        }
        current?.let(AssignmentRules::requireMovable)
        store.lockRooms(listOfNotNull(current?.roomId, room.id))
        val period = AssignmentPeriod.of(reservation)
        AssignmentRules.requireNotOutOfOrder(room, period, blockingPeriods(period, room.id))
        val now = now()
        current?.let { release(it, RoomAssignmentEndReason.REALLOCATED, now) }
        val assignment =
            RoomAssignmentSummary(
                id = RoomAssignmentId.new(),
                reservationId = reservation.id,
                roomId = room.id,
                startsOn = period.startsOn,
                endsOn = period.endsOn,
                reason =
                    reason ?: if (current == null) RoomAssignmentReason.INITIAL else RoomAssignmentReason.OPERATIONAL,
                doNotMove = doNotMove ?: false,
                reallocation = null,
                assignedAt = now,
                assignedBy = actor(),
            )
        if (!store.tryInsert(assignment)) throw RoomAlreadyAssignedException(room.number)
        audit.record(
            ReservationsAudit.ROOM_ASSIGNMENT,
            assignment.id.value,
            null,
            ReservationsAudit.assignmentSnapshot(assignment) + ("previousRoomId" to current?.roomId?.toString()),
        )
        store.touchReservation(reservation.id, now)
        return assignment
    }

    /** Solta a UH alocada (`UNASSIGNED`); sem UH, `409 ROOM_NOT_ASSIGNED`; com "não mover", `409`. */
    fun unassign(reservation: ReservationSummary): RoomAssignmentSummary {
        AssignmentRules.requireAssignable(reservation, hotel.currentBusinessDate())
        val current = store.active(reservation.id) ?: throw notAssigned()
        AssignmentRules.requireMovable(current)
        val now = now()
        val released = release(current, RoomAssignmentEndReason.UNASSIGNED, now)
        store.touchReservation(reservation.id, now)
        return released
    }

    /** Marca ou desmarca "não mover" na UH alocada; sem UH, `409 ROOM_NOT_ASSIGNED`. */
    fun setDoNotMove(
        reservation: ReservationSummary,
        doNotMove: Boolean,
    ): RoomAssignmentSummary {
        AssignmentRules.requireAssignable(reservation, hotel.currentBusinessDate())
        val current = store.active(reservation.id) ?: throw notAssigned()
        if (current.doNotMove == doNotMove) {
            audit.noChanges()
            return current
        }
        return updateDoNotMove(reservation, current, doNotMove)
    }

    /**
     * Acompanha a alteração da reserva (E08) já gravada no inventário: sem UH alocada ou sem mudança de tipo nem de
     * datas, nada muda. Com outro tipo de UH, a UH é solta (`RESERVATION_CHANGED`). Com outras datas, a UH continua se
     * estiver livre e sem OOO no período novo (a marca de realocação sai), senão é solta. "Não mover" impede soltar:
     * `409 ROOM_ASSIGNMENT_LOCKED` e a alteração inteira é desfeita. Devolve a alocação que fica (nula se nenhuma).
     */
    @Suppress("ReturnCount") // sem UH, sem mudança, UH mantida e UH solta
    fun followChange(
        before: ReservationSummary,
        after: ReservationSummary,
        now: Instant,
    ): RoomAssignmentSummary? {
        val current = store.active(before.id) ?: return null
        val period = AssignmentPeriod.of(after)
        val samePeriod = current.startsOn == period.startsOn && current.endsOn == period.endsOn
        if (before.roomTypeId == after.roomTypeId && samePeriod) return current
        if (before.roomTypeId == after.roomTypeId && keeps(current, period, after)) {
            if (!store.movePeriod(current.id, period)) throw RoomAlreadyAssignedException(roomNumber(current.roomId))
            val moved = current.copy(startsOn = period.startsOn, endsOn = period.endsOn, reallocation = null)
            audit.record(
                ReservationsAudit.ROOM_ASSIGNMENT,
                current.id.value,
                ReservationsAudit.assignmentSnapshot(current),
                ReservationsAudit.assignmentSnapshot(moved),
            )
            return moved
        }
        AssignmentRules.requireMovable(current)
        release(current, RoomAssignmentEndReason.RESERVATION_CHANGED, now)
        return null
    }

    // --- Hospedagem (M5 E01) --------------------------------------------------------------------------------------

    /**
     * A UH do check-in (Ajuste D 1 da M4 E10): a alocação ativa, cuja UH continua ativa e do tipo da
     * reserva (o cadastro
     * do `rooms` pode ter mudado desde a alocação: `RoomRegistryChanged` não solta alocações). Sem UH,
     * `409 ROOM_NOT_ASSIGNED`; UH desativada `422 ROOM_INACTIVE`; de outro tipo `422 ROOM_TYPE_MISMATCH`.
     */
    fun requireForCheckIn(reservation: ReservationSummary): RoomAssignmentSummary {
        val current = store.active(reservation.id) ?: throw notAssigned()
        AssignmentRules.requireRoom(rooms.room(current.roomId), reservation)
        return current
    }

    /**
     * O check-out encerra a alocação ativa na mesma transação (Ajuste D 2 da M4 E10; motivo `CHECKED_OUT`), com o fim
     * em [endsOn] (a saída da estadia, que o check-out antecipado já trouxe para a data operacional),
     * com "não mover" ou
     * não. Devolve a encerrada, ou `null` se não havia.
     */
    fun endOnCheckOut(
        reservation: ReservationSummary,
        endsOn: LocalDate,
        now: Instant,
    ): RoomAssignmentSummary? {
        val current = store.active(reservation.id) ?: return null
        return end(current, endsOn, RoomAssignmentEndReason.CHECKED_OUT, now)
    }

    /**
     * A troca de UH do hospedado ([RoomMoveRules]): trava as duas UHs, confere a nova (ativa, do tipo da reserva, sem
     * OOO no período novo), encerra a atual na data operacional (`ROOM_MOVED`) e abre a nova da data
     * operacional à saída
     * (a sobreposição com outra alocação ativa é recusada pelo banco: `409 ROOM_ALREADY_ASSIGNED`). "Não mover" passa à
     * nova. Devolve a encerrada e a nova.
     */
    fun moveInHouse(
        reservation: ReservationSummary,
        move: RoomMove,
        businessDate: LocalDate,
        now: Instant,
    ): Pair<RoomAssignmentSummary, RoomAssignmentSummary> {
        val current = store.active(reservation.id) ?: throw notAssigned()
        RoomMoveRules.requireOtherRoom(current, move)
        val room = AssignmentRules.requireRoom(rooms.room(move.roomId), reservation)
        store.lockRooms(listOf(current.roomId, room.id))
        val period = RoomMoveRules.newPeriod(reservation, businessDate)
        AssignmentRules.requireNotOutOfOrder(room, period, blockingPeriods(period, room.id))
        val ended = end(current, RoomMoveRules.oldEnd(current, businessDate), RoomAssignmentEndReason.ROOM_MOVED, now)
        val assignment =
            RoomAssignmentSummary(
                id = RoomAssignmentId.new(),
                reservationId = reservation.id,
                roomId = room.id,
                startsOn = period.startsOn,
                endsOn = period.endsOn,
                reason = move.reason,
                doNotMove = current.doNotMove,
                reallocation = null,
                assignedAt = now,
                assignedBy = actor(),
            )
        if (!store.tryInsert(assignment)) throw RoomAlreadyAssignedException(room.number)
        audit.record(
            ReservationsAudit.ROOM_ASSIGNMENT,
            assignment.id.value,
            null,
            ReservationsAudit.assignmentSnapshot(assignment) + ("previousRoomId" to current.roomId.toString()),
        )
        return ended to assignment
    }

    /**
     * A alteração da saída do hospedado ([InHouseChangeRules]): a alocação ativa vai até a saída nova, a partir do
     * início dela (que, depois de uma troca de UH, é a data da troca, não a chegada). Na extensão, a UH continua dele
     * se não tiver OOO nas noites novas (`422 ROOM_OUT_OF_ORDER`) nem outra reserva alocada nelas
     * (`409 ROOM_ALREADY_ASSIGNED`, pelo banco); a UH nunca é solta (o hóspede está nela). Devolve a alocação que fica.
     */
    @Suppress("ThrowsCount") // sem UH, OOO nas noites novas e outra reserva na UH
    fun followInHouseChange(
        before: ReservationSummary,
        after: ReservationSummary,
    ): RoomAssignmentSummary {
        val current = store.active(before.id) ?: throw notAssigned()
        if (before.departure == after.departure) return current
        val period = AssignmentPeriod(current.startsOn, after.departure)
        store.lockRooms(listOf(current.roomId))
        if (after.departure.isAfter(before.departure)) {
            val added = AssignmentPeriod(before.departure, after.departure)
            val room = rooms.room(current.roomId)
            val dates = AssignmentRules.outOfOrderDates(added, blockingPeriods(added, current.roomId))
            if (dates.isNotEmpty()) throw RoomOutOfOrderException(room?.number ?: current.roomId.toString(), dates)
        }
        if (!store.movePeriod(current.id, period)) throw RoomAlreadyAssignedException(roomNumber(current.roomId))
        val moved = current.copy(startsOn = period.startsOn, endsOn = period.endsOn, reallocation = null)
        audit.record(
            ReservationsAudit.ROOM_ASSIGNMENT,
            current.id.value,
            ReservationsAudit.assignmentSnapshot(current),
            ReservationsAudit.assignmentSnapshot(moved),
        )
        return moved
    }

    private fun end(
        current: RoomAssignmentSummary,
        endsOn: LocalDate,
        reason: RoomAssignmentEndReason,
        now: Instant,
    ): RoomAssignmentSummary {
        val ended =
            checkNotNull(store.end(current.id, endsOn, now, actor(), reason)) { "Alocação ${current.id} solta" }
        audit.record(
            ReservationsAudit.ROOM_ASSIGNMENT,
            current.id.value,
            ReservationsAudit.assignmentSnapshot(current),
            ReservationsAudit.assignmentSnapshot(ended),
        )
        return ended
    }

    /** O cancelamento ou a expiração solta a UH alocada (`RESERVATION_CANCELLED`), com "não mover" ou não. */
    fun releaseOnCancellation(
        reservation: ReservationSummary,
        now: Instant,
    ) {
        store.active(reservation.id)?.let { release(it, RoomAssignmentEndReason.RESERVATION_CANCELLED, now) }
    }

    /**
     * As UHs que a reserva pode receber (RF-RES-04, RF-UH-05): as ativas do tipo da reserva, sem OOO em nenhuma noite e
     * sem outra reserva alocada em parte do período, na ordem de [RoomSuggestions] (OOS por último, depois as
     * características pedidas e o andar preferido pelo hóspede principal, pela `GuestsApi`).
     */
    fun suggestions(
        reservation: ReservationSummary,
        featureIds: Set<RoomFeatureId>,
        preferredFloor: String?,
    ): List<RoomSuggestion> {
        AssignmentRules.requireAssignable(reservation, hotel.currentBusinessDate())
        val period = AssignmentPeriod.of(reservation)
        val blocking = blockingPeriods(period, null)
        val taken =
            store
                .activeOverlapping(period)
                .filter { it.reservationId != reservation.id && AssignmentPeriod.of(it).conflictsWith(period) }
                .map { it.roomId }
                .toSet()
        val current = store.active(reservation.id)?.roomId
        val candidates =
            rooms
                .activeRooms(reservation.roomTypeId)
                .filter { it.id !in taken }
                .filter { room -> AssignmentRules.outOfOrderDates(period, blocking.of(room)).isEmpty() }
                .map { room ->
                    val outOfService =
                        blocking.of(room).any { it.outOfService && period.datesBlockedBy(it).isNotEmpty() }
                    RoomCandidate(room, outOfService, room.id == current)
                }
        return RoomSuggestions.rank(candidates, featureIds, preferredFloor)
    }

    /**
     * Se a UH da alocação continua servindo à reserva no período novo (a trava da UH vem antes da leitura): ainda ativa
     * e do tipo da reserva (o cadastro do `rooms` pode ter mudado desde a alocação), sem OOO e sem outra alocação ativa
     * que ocupe o mesmo meio-dia ([AssignmentPeriod.conflictsWith]).
     */
    private fun keeps(
        current: RoomAssignmentSummary,
        period: AssignmentPeriod,
        reservation: ReservationSummary,
    ): Boolean {
        store.lockRooms(listOf(current.roomId))
        val room = rooms.room(current.roomId)
        if (room == null || !room.active || room.roomTypeId != reservation.roomTypeId) return false
        val outOfOrder = AssignmentRules.outOfOrderDates(period, blockingPeriods(period, current.roomId))
        val others =
            store
                .activeOverlapping(period, current.roomId)
                .filter { it.id != current.id && AssignmentPeriod.of(it).conflictsWith(period) }
        return outOfOrder.isEmpty() && others.isEmpty()
    }

    private fun updateDoNotMove(
        reservation: ReservationSummary,
        current: RoomAssignmentSummary,
        doNotMove: Boolean,
    ): RoomAssignmentSummary {
        store.setDoNotMove(current.id, doNotMove)
        val updated = current.copy(doNotMove = doNotMove)
        audit.record(
            ReservationsAudit.ROOM_ASSIGNMENT,
            current.id.value,
            ReservationsAudit.assignmentSnapshot(current),
            ReservationsAudit.assignmentSnapshot(updated),
        )
        store.touchReservation(reservation.id, now())
        return updated
    }

    private fun release(
        current: RoomAssignmentSummary,
        reason: RoomAssignmentEndReason,
        now: Instant,
    ): RoomAssignmentSummary {
        val released = checkNotNull(store.release(current.id, now, actor(), reason)) { "Alocação ${current.id} solta" }
        audit.record(
            ReservationsAudit.ROOM_ASSIGNMENT,
            current.id.value,
            ReservationsAudit.assignmentSnapshot(current),
            ReservationsAudit.assignmentSnapshot(released),
        )
        return released
    }

    /** Os períodos OOO e OOS que bloqueiam alguma data do [period], de todas as UHs ou só da [roomId]. */
    private fun blockingPeriods(
        period: AssignmentPeriod,
        roomId: RoomId?,
    ): List<BlockingPeriod> = blockingPeriodsOf(rooms, period, roomId)

    private fun List<BlockingPeriod>.of(room: RoomSummary) = filter { it.roomId == room.id }

    private fun roomNumber(id: RoomId): String = rooms.room(id)?.number ?: id.toString()

    private fun notAssigned() =
        ConflictException(ReservationsErrorCodes.ROOM_NOT_ASSIGNED, "A reserva não tem UH alocada.")

    private fun actor(): UserId? = currentActor.currentOrNull()?.userId

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

/** Os períodos OOO e OOS da `RoomsApi` que bloqueiam alguma data do [period] (todos, ou só os da [roomId]). */
internal fun blockingPeriodsOf(
    rooms: RoomsApi,
    period: AssignmentPeriod,
    roomId: RoomId?,
): List<BlockingPeriod> =
    rooms.outOfOrderPeriods(period.startsOn, period.lastDate, roomId).mapNotNull { blocking ->
        blocking.blockedUntil?.let {
            BlockingPeriod(
                id = blocking.id,
                roomId = blocking.roomId,
                startsOn = blocking.startsOn,
                until = it,
                outOfService = blocking.kind == OutOfOrderKind.OUT_OF_SERVICE,
            )
        }
    }

/**
 * A alocação de UH pela API (M4 E10): alocar, realocar, desalocar, marcar "não mover" e as sugestões exigem
 * `reservations.assignments.manage` (não sensível); o histórico das alocações de uma reserva e a lista das reservas que
 * requerem realocação, `reservations.reservations.read`. Toda escrita trava a reserva antes
 * ([BookingStore.reservationForUpdate]): duas escritas na mesma reserva se enfileiram.
 */
@Service
@Suppress("LongParameterList") // as portas dos casos de uso da alocação
class RoomAssignmentService(
    private val assignments: RoomAssignments,
    private val store: RoomAssignmentStore,
    private val bookings: BookingStore,
    private val views: ReservationViews,
    private val rooms: RoomsApi,
    private val guests: GuestsApi,
    private val authorization: Authorization,
) {
    @Audited(ReservationsAudit.ASSIGN_ROOM)
    @Transactional
    fun assign(
        id: ReservationId,
        roomId: RoomId,
        reason: RoomAssignmentReason?,
        doNotMove: Boolean?,
    ): ReservationView {
        authorization.require(ReservationsPermissions.ASSIGNMENTS_MANAGE)
        assignments.assign(locked(id), roomId, reason, doNotMove)
        return views.of(checkNotNull(bookings.reservation(id)))
    }

    /**
     * A alocação pela `ReservationsApi` (M5 E01, Ajuste D 4 da M4 E10: o check-in e o walk-in), com as mesmas regras e
     * a mesma trilha do endpoint, sem a permissão (quem chama já se autorizou).
     */
    @Audited(ReservationsAudit.ASSIGN_ROOM)
    @Transactional
    fun assignAuthorized(
        id: ReservationId,
        roomId: RoomId,
        reason: RoomAssignmentReason?,
        doNotMove: Boolean?,
    ): ReservationSummary {
        assignments.assign(locked(id), roomId, reason, doNotMove)
        return checkNotNull(bookings.reservation(id))
    }

    @Audited(ReservationsAudit.UNASSIGN_ROOM)
    @Transactional
    fun unassign(id: ReservationId): ReservationView {
        authorization.require(ReservationsPermissions.ASSIGNMENTS_MANAGE)
        assignments.unassign(locked(id))
        return views.of(checkNotNull(bookings.reservation(id)))
    }

    @Audited(ReservationsAudit.SET_DO_NOT_MOVE)
    @Transactional
    fun setDoNotMove(
        id: ReservationId,
        doNotMove: Boolean,
    ): ReservationView {
        authorization.require(ReservationsPermissions.ASSIGNMENTS_MANAGE)
        assignments.setDoNotMove(locked(id), doNotMove)
        return views.of(checkNotNull(bookings.reservation(id)))
    }

    /** As UHs sugeridas, com as características pedidas ([featureIds], até 20, sem repetir). */
    @Transactional(readOnly = true)
    fun suggestions(
        id: ReservationId,
        featureIds: List<RoomFeatureId>,
    ): RoomSuggestionsView {
        authorization.require(ReservationsPermissions.ASSIGNMENTS_MANAGE)
        if (featureIds.size > MAX_FEATURES || featureIds.toSet().size != featureIds.size) {
            val message = "Informe até $MAX_FEATURES características, sem repetir."
            throw InvalidInputException(
                ReservationsErrorCodes.ROOM_SUGGESTION_INVALID,
                message,
                listOf(FieldViolation("featureIds", ReservationsErrorCodes.ROOM_SUGGESTION_INVALID.value, message)),
            )
        }
        val reservation = bookings.reservation(id) ?: throw reservationNotFound()
        val principal =
            reservation.guests.firstOrNull { it.primary }?.guestId ?: views.holderOf(reservation)
        val floor =
            guests
                .preferences(principal)
                ?.floor
                ?.takeIf { it.isNotBlank() }
        val suggestions = assignments.suggestions(reservation, featureIds.toSet(), floor)
        return RoomSuggestionsView(reservation, floor != null, suggestions)
    }

    /** Todas as alocações da reserva, da mais recente à mais antiga, com o número da UH. */
    @Transactional(readOnly = true)
    fun history(id: ReservationId): List<RoomAssignmentView> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        bookings.reservation(id) ?: throw reservationNotFound()
        val numbers = mutableMapOf<RoomId, String?>()
        return store.history(id).map {
            RoomAssignmentView(it, numbers.getOrPut(it.roomId) { rooms.room(it.roomId)?.number })
        }
    }

    /** As reservas com a UH alocada marcada "requer realocação" (RN-07), da que começa primeiro, por cursor. */
    @Transactional(readOnly = true)
    fun reallocationRequired(page: PageParams): CursorPage<ReallocationView> {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val cursor = page.decodedCursor()
        val after = cursor?.let { it.localDate(0) to RoomAssignmentId(it.uuid(1)) }
        val rows = store.reallocationRequired(after, page.limit + 1)
        val numbers = mutableMapOf<RoomId, String?>()
        return CursorPage
            .fromRows(rows, page.limit) {
                PageCursor.of(it.assignment.startsOn.toString(), it.assignment.id.toString())
            }.map {
                ReallocationView(
                    it,
                    numbers.getOrPut(it.assignment.roomId) { rooms.room(it.assignment.roomId)?.number },
                )
            }
    }

    private fun locked(id: ReservationId): ReservationSummary =
        bookings.reservationForUpdate(id) ?: throw reservationNotFound()

    private fun reservationNotFound() =
        NotFoundException(ReservationsErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")

    private companion object {
        const val MAX_FEATURES = 20
    }
}

/**
 * A marca "requer realocação" (RN-07, H11): depois de cada `OutOfOrderChanged` de um OOO (o OOS não conta), refaz a
 * marca das alocações ativas da UH no período do evento (e no anterior, numa alteração) contra **todos** os períodos
 * OOO da UH na `RoomsApi` (a fonte da verdade): marca a alocação cuja estadia algum OOO atinge, passa a marca a outro
 * OOO que ainda a atinge quando o citado é liberado ou alterado (sem notificar de novo) e só a tira quando nenhum a
 * atinge mais. Toma a trava da UH antes de ler: uma alocação gravada ao mesmo tempo que o OOO é
 * vista aqui ou recusada lá. Notifica (H11) **só as alocações marcadas agora**, uma notificação por período OOO, a quem
 * tem `reservations.assignments.manage`, com o número da UH, as datas e os códigos das reservas (sem dado de hóspede):
 * o evento reentregue não marca nem notifica de novo.
 */
@Service
@Suppress("LongParameterList") // as portas da marca e da notificação
class ReallocationMarking(
    private val store: RoomAssignmentStore,
    private val bookings: BookingStore,
    private val rooms: RoomsApi,
    private val notifications: NotificationsApi,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    /** Devolve quantas alocações foram marcadas agora. */
    @Audited(ReservationsAudit.MARK_REALLOCATION)
    @Transactional
    @Suppress("ReturnCount") // OOS ou UH inexistente, nenhuma alocação no período e a marca refeita
    fun refresh(event: OutOfOrderChanged): Int {
        val room = rooms.room(RoomId(event.roomId))
        if (event.kind != OutOfOrderKind.OUT_OF_ORDER || room == null) {
            audit.noChanges()
            return 0
        }
        store.lockRooms(listOf(room.id))
        val from = listOfNotNull(event.startsOn, event.previousStartsOn).min()
        val to = listOfNotNull(event.endsOn, event.previousEndsOn).max()
        val active = store.activeOverlapping(AssignmentPeriod(from, to.plusDays(1)), room.id)
        if (active.isEmpty()) {
            audit.noChanges()
            return 0
        }
        val span =
            AssignmentPeriod(
                active.minOf { it.startsOn },
                active.maxOf { AssignmentPeriod.of(it).lastDate }.plusDays(1),
            )
        val blocking = blockingPeriodsOf(rooms, span, room.id)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val outcomes = active.map { it to refreshMark(it, blocking, now) }
        val marked =
            outcomes.mapNotNull { (assignment, outcome) ->
                (outcome as? MarkOutcome.Marked)?.let {
                    assignment to
                        it.by
                }
            }
        val changed = outcomes.count { it.second != MarkOutcome.Unchanged }
        if (changed == 0) audit.noChanges()
        marked.groupBy({ it.second }, { it.first }).forEach { (period, assignments) ->
            notify(room, period, assignments)
        }
        return marked.size
    }

    /** O que o recálculo fez com a marca de uma alocação. */
    private sealed interface MarkOutcome {
        data class Marked(
            val by: BlockingPeriod,
        ) : MarkOutcome

        data object Changed : MarkOutcome

        data object Unchanged : MarkOutcome
    }

    /** Refaz a marca de uma alocação contra os OOO da UH ([ReallocationMarks.markFor]). */
    @Suppress("ReturnCount") // a gravação que não pegou (a alocação mudou no meio) em cada caso
    private fun refreshMark(
        assignment: RoomAssignmentSummary,
        blocking: List<BlockingPeriod>,
        now: Instant,
    ): MarkOutcome {
        val mark = assignment.reallocation
        val hit = ReallocationMarks.markFor(AssignmentPeriod.of(assignment), blocking, mark?.outOfOrderId)
        return when {
            hit != null && mark == null -> {
                if (!store.mark(assignment.id, hit.id, now)) return MarkOutcome.Unchanged
                record(assignment, assignment.copy(reallocation = ReallocationMark(hit.id, now)))
                MarkOutcome.Marked(hit)
            }

            // Outro OOO ainda atinge a estadia: a marca fica e passa a citá-lo, sem notificar de novo.
            hit != null && mark != null && mark.outOfOrderId != hit.id -> {
                if (!store.remark(assignment.id, mark.outOfOrderId, hit.id)) return MarkOutcome.Unchanged
                record(assignment, assignment.copy(reallocation = mark.copy(outOfOrderId = hit.id)))
                MarkOutcome.Changed
            }

            hit == null && mark != null -> {
                if (!store.unmark(assignment.id)) return MarkOutcome.Unchanged
                record(assignment, assignment.copy(reallocation = null))
                MarkOutcome.Changed
            }

            else -> {
                MarkOutcome.Unchanged
            }
        }
    }

    private fun notify(
        room: RoomSummary,
        period: BlockingPeriod,
        assignments: List<RoomAssignmentSummary>,
    ) {
        val codes =
            assignments
                .sortedWith(compareBy<RoomAssignmentSummary> { it.startsOn }.thenBy { it.id.value.toString() })
                .mapNotNull { bookings.reservation(it.reservationId)?.code?.toString() }
        notifications.notify(
            ReservationsNotificationTypes.REALLOCATION_REQUIRED,
            ReallocationNotice.title(room.number),
            ReallocationNotice.body(room.number, period.startsOn, period.until, codes),
            NotificationRecipients.withPermission(ReservationsPermissions.ASSIGNMENTS_MANAGE),
        )
    }

    private fun record(
        before: RoomAssignmentSummary,
        after: RoomAssignmentSummary,
    ) = audit.record(
        ReservationsAudit.ROOM_ASSIGNMENT,
        before.id.value,
        ReservationsAudit.assignmentSnapshot(before),
        ReservationsAudit.assignmentSnapshot(after),
    )
}

/**
 * O listener de `OutOfOrderChanged` da marca de realocação (H11; o refazimento do inventário é o [InventoryListeners],
 * noutra transação): assíncrono, reentregue pelo outbox se falhar e idempotente (a marca é refeita a partir da
 * `RoomsApi`; a notificação só sai para a alocação marcada agora). O OOS é ignorado.
 */
@Component
class ReallocationListener(
    private val marking: ReallocationMarking,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: OutOfOrderChanged) {
        if (event.kind != OutOfOrderKind.OUT_OF_ORDER) return
        val marked = marking.refresh(event)
        if (marked >
            0
        ) {
            log.info(
                "OOO {} ({}): {} reserva(s) marcadas para realocação",
                event.outOfOrderId,
                event.action,
                marked,
            )
        }
    }
}

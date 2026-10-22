package com.zeny.hotel.reservations.application

import com.zeny.hotel.reservations.AvailabilityChanged
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.HistoryCancellation
import com.zeny.hotel.reservations.HistoryNight
import com.zeny.hotel.reservations.HistoryStayGuest
import com.zeny.hotel.reservations.ReservationState
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.RoomAssignmentSummary
import com.zeny.hotel.reservations.RoomBlockSummary
import com.zeny.hotel.reservations.StayTransitionRecord
import com.zeny.hotel.reservations.domain.InventoryChange
import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryLedger
import com.zeny.hotel.reservations.domain.InventoryUse
import com.zeny.hotel.rooms.RoomTypeId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Ações e entidade do inventário na trilha de auditoria. */
internal object ReservationsAudit {
    const val ROOM_TYPE_INVENTORY = "reservations.room-type-inventory"

    const val REFRESH_INVENTORY = "reservations.inventory.refresh"
    const val EXTEND_INVENTORY_HORIZON = "reservations.inventory.extend-horizon"
    const val RECOUNT_INVENTORY = "reservations.inventory.recount"
    const val SET_OVERBOOKING_LIMIT = "reservations.inventory.set-overbooking-limit"

    const val BOOKING = "reservations.booking"
    const val RESERVATION = "reservations.reservation"

    const val CREATE_BOOKING = "reservations.booking.create"
    const val REPOINT_GUEST = "reservations.guest-reference.repoint"
    const val CHANGE_RESERVATION = "reservations.reservation.change"
    const val CANCEL_RESERVATION = "reservations.reservation.cancel"
    const val REACTIVATE_RESERVATION = "reservations.reservation.reactivate"
    const val CONFIRM_RESERVATION = "reservations.reservation.confirm"
    const val EXPIRE_RESERVATION = "reservations.reservation.expire"
    const val CHECK_IN = "reservations.reservation.check-in"
    const val UNDO_CHECK_IN = "reservations.reservation.undo-check-in"
    const val CHECK_OUT = "reservations.reservation.check-out"
    const val MOVE_ROOM = "reservations.reservation.move-room"

    const val ROOM_ASSIGNMENT = "reservations.room-assignment"
    const val ASSIGN_ROOM = "reservations.room-assignment.assign"
    const val UNASSIGN_ROOM = "reservations.room-assignment.unassign"
    const val SET_DO_NOT_MOVE = "reservations.room-assignment.set-do-not-move"
    const val MARK_REALLOCATION = "reservations.room-assignment.mark-reallocation"

    const val ROOM_BLOCK = "reservations.room-block"
    const val CREATE_BLOCK = "reservations.room-block.create"
    const val UPDATE_BLOCK = "reservations.room-block.update"
    const val PICK_UP_BLOCK = "reservations.room-block.pick-up"
    const val RELEASE_BLOCK = "reservations.room-block.release-unused"

    private const val MAX_AUDITED_VALUES = 20

    /**
     * O retrato de um bloqueio de grupo (E11): ids, código, titular pelo id, plano, data de corte, situação, conta
     * master e as unidades resumidas (as noites, os tipos, o total contratado, convertido e liberado e as tarifas
     * distintas, até 20); sem o nome do grupo (texto livre que pode citar pessoas).
     */
    fun blockSnapshot(block: RoomBlockSummary): Map<String, Any?> =
        mapOf(
            "code" to block.code.toString(),
            "holderGuestId" to block.holderGuestId?.toString(),
            "holderCompanyId" to block.holderCompanyId?.toString(),
            "ratePlanId" to block.ratePlanId.toString(),
            "cutoffDate" to block.cutoffDate.toString(),
            "status" to block.status.name,
            "folioId" to block.folioId.toString(),
            "releasedOn" to block.releasedOn?.toString(),
            "allocations" to
                mapOf(
                    "from" to block.allocations.minOfOrNull { it.date }?.toString(),
                    "to" to block.allocations.maxOfOrNull { it.date }?.toString(),
                    "rows" to block.allocations.size,
                    "roomTypeIds" to
                        block.allocations
                            .map { it.roomTypeId.toString() }
                            .distinct()
                            .sorted(),
                    "quantity" to block.allocations.sumOf { it.quantity },
                    "pickedUp" to block.allocations.sumOf { it.pickedUp },
                    "released" to block.allocations.sumOf { it.released },
                    "rates" to
                        block.allocations
                            .map { it.rate }
                            .distinct()
                            .sorted()
                            .take(MAX_AUDITED_VALUES)
                            .map { it.toString() },
                ),
        )

    /**
     * O retrato de uma alocação de UH (E10): ids, período, motivo, "não mover", a marca de realocação (o período
     * OOO) e, se foi solta, quando e por quê. Sem dado de hóspede.
     */
    fun assignmentSnapshot(assignment: RoomAssignmentSummary): Map<String, Any?> =
        mapOf(
            "reservationId" to assignment.reservationId.toString(),
            "roomId" to assignment.roomId.toString(),
            "startsOn" to assignment.startsOn.toString(),
            "endsOn" to assignment.endsOn.toString(),
            "reason" to assignment.reason.name,
            "doNotMove" to assignment.doNotMove,
            "reallocationOutOfOrderId" to assignment.reallocation?.outOfOrderId?.toString(),
            "releasedAt" to assignment.releasedAt?.toString(),
            "releaseReason" to assignment.releaseReason?.name,
        )

    /** O retrato de uma reserva agrupadora: ids, código, origem, canal e o id externo; o titular só pelo id. */
    fun bookingSnapshot(booking: BookingSummary): Map<String, Any?> =
        mapOf(
            "code" to booking.code.toString(),
            "holderGuestId" to booking.holderGuestId.toString(),
            "origin" to booking.origin.name,
            "channel" to booking.channel.name,
            "externalId" to booking.externalId,
            "reservationIds" to booking.reservations.map { it.id.toString() },
        )

    /**
     * O retrato de uma reserva (H10 do M3): ids, situação, datas, contagens, segmento, os termos copiados pelo id e a
     * versão, os totais e o desconto; os pedidos especiais só como `hasSpecialRequests` (texto livre) e os hóspedes só
     * pelos ids.
     */
    fun reservationSnapshot(reservation: ReservationSummary): Map<String, Any?> =
        mapOf(
            "code" to reservation.code.toString(),
            "bookingId" to reservation.bookingId.toString(),
            "status" to reservation.status.name,
            "provisionalUntil" to reservation.provisionalUntil?.toString(),
            "roomTypeId" to reservation.roomTypeId.toString(),
            "ratePlanId" to reservation.ratePlanId.toString(),
            "arrival" to reservation.arrival.toString(),
            "departure" to reservation.departure.toString(),
            "dayUse" to reservation.dayUse,
            "adults" to reservation.adults,
            "children" to reservation.childAges.size,
            "segment" to reservation.segment.name,
            "hasSpecialRequests" to (reservation.specialRequests != null),
            "guestIds" to reservation.guests.map { it.guestId.toString() },
            "primaryGuestId" to
                reservation.guests
                    .firstOrNull { it.primary }
                    ?.guestId
                    ?.toString(),
            "nights" to reservation.nightlyRates.size,
            "overriddenDates" to reservation.nightlyRates.filter { it.overridden }.map { it.date.toString() },
            "maxDiscountPercent" to
                reservation.nightlyRates
                    .mapNotNull { it.discountPercent }
                    .maxOrNull()
                    ?.toString(),
            "roomTotal" to reservation.roomTotal.toString(),
            "total" to reservation.total.toString(),
            "cancellationPolicy" to
                mapOf(
                    "id" to reservation.cancellationTerms.policyId.toString(),
                    "version" to reservation.cancellationTerms.version,
                ),
            "guaranteePolicy" to
                mapOf(
                    "id" to reservation.guaranteeTerms.policyId.toString(),
                    "version" to reservation.guaranteeTerms.version,
                ),
            "folioId" to reservation.folioId.toString(),
        ) + listOfNotNull(reservation.blockId?.let { "blockId" to it.toString() }).toMap() +
            cancellationSnapshot(reservation) +
            guaranteeSnapshot(reservation) +
            stayTransitionsSnapshot(reservation)

    /**
     * O check-in e o check-out (M5 E01): as datas, os instantes e quem; por hóspede, só o id e os códigos de check-in e
     * da comprovação do menor (nunca nome nem documento).
     */
    private fun stayTransitionsSnapshot(reservation: ReservationSummary): Map<String, Any?> {
        val checkIn = reservation.checkIn ?: return emptyMap()
        return mapOf(
            "checkIn" to stampSnapshot(checkIn),
            "checkOut" to reservation.checkOut?.let(::stampSnapshot),
            "stayGuests" to
                reservation.guests.map { guest ->
                    mapOf(
                        "guestId" to guest.guestId.toString(),
                        "primary" to guest.primary,
                        "travelReason" to guest.travelReason,
                        "transportMode" to guest.transportMode,
                        "minorAuthorization" to
                            guest.minorAuthorization?.let {
                                mapOf(
                                    "proofType" to it.proofType.name,
                                    "responsibleGuestId" to it.responsibleGuestId.toString(),
                                    "relationship" to it.relationship,
                                    "attachmentId" to it.attachmentId?.toString(),
                                    "validUntil" to it.validUntil?.toString(),
                                )
                            },
                    )
                },
        )
    }

    private fun stampSnapshot(record: StayTransitionRecord): Map<String, Any?> =
        mapOf(
            "businessDate" to record.businessDate.toString(),
            "at" to record.at.toString(),
            "by" to record.by?.toString(),
        )

    /** A garantia recebida na confirmação (E09): o meio, quando, por quem e, no depósito, o exigido e o recebido. */
    private fun guaranteeSnapshot(reservation: ReservationSummary): Map<String, Any?> {
        val guarantee = reservation.guarantee ?: return emptyMap()
        return mapOf(
            "guarantee" to
                mapOf(
                    "method" to guarantee.method.name,
                    "confirmedAt" to guarantee.confirmedAt.toString(),
                    "confirmedBy" to guarantee.confirmedBy?.toString(),
                    "requiredDeposit" to guarantee.requiredDeposit?.toString(),
                    "depositReceived" to guarantee.depositReceived?.toString(),
                ),
        )
    }

    /**
     * O cancelamento no retrato (E08): o motivo, a situação anterior e a multa (prazo, valor, isenção, lançamento e as
     * multas já lançadas); a observação só como `hasNotes` (texto livre).
     */
    private fun cancellationSnapshot(reservation: ReservationSummary): Map<String, Any?> {
        val cancellation = reservation.cancellation
        val charged =
            reservation.penaltiesCharged.takeIf { it.isPositive() }?.let {
                mapOf("penaltiesCharged" to it.toString())
            }
        if (cancellation == null) return charged.orEmpty()
        return charged.orEmpty() +
            mapOf(
                "cancellation" to
                    mapOf(
                        "reason" to cancellation.reason.name,
                        "previousStatus" to cancellation.previousStatus.name,
                        "hasNotes" to (cancellation.notes != null),
                        "penaltyDeadline" to cancellation.penalty.deadline.toString(),
                        "penaltyAmount" to cancellation.penalty.amount.toString(),
                        "penaltyWaived" to cancellation.penalty.waived,
                        "penaltyPostingId" to cancellation.penalty.postingId?.toString(),
                    ),
            )
    }

    /** O retrato de uma reserva no histórico (E08): a estadia, as noites com o preço, os totais e o cancelamento. */
    fun state(reservation: ReservationSummary): ReservationState =
        ReservationState(
            status = reservation.status,
            roomTypeId = reservation.roomTypeId,
            ratePlanId = reservation.ratePlanId,
            arrival = reservation.arrival,
            departure = reservation.departure,
            adults = reservation.adults,
            childAges = reservation.childAges,
            segment = reservation.segment,
            hasSpecialRequests = reservation.specialRequests != null,
            nights =
                reservation.nightlyRates.map {
                    HistoryNight(it.date, it.ratePlanId, it.roomAmount, it.amount, it.overridden)
                },
            roomTotal = reservation.roomTotal,
            additionalPackagesTotal = reservation.additionalPackagesTotal,
            total = reservation.total,
            cancellationPolicyId = reservation.cancellationTerms.policyId,
            cancellationPolicyVersion = reservation.cancellationTerms.version,
            guaranteePolicyId = reservation.guaranteeTerms.policyId,
            guaranteePolicyVersion = reservation.guaranteeTerms.version,
            cancellation =
                reservation.cancellation?.let {
                    HistoryCancellation(it.reason, it.penalty.amount, it.penalty.waived, it.penalty.postingId)
                },
            guaranteeMethod = reservation.guarantee?.method,
            checkIn = reservation.checkIn,
            checkOut = reservation.checkOut,
            roomId = reservation.roomAssignment?.roomId,
            stayGuests =
                reservation.guests.map {
                    HistoryStayGuest(it.guestId, it.primary, it.travelReason, it.transportMode, it.minorAuthorization)
                },
        )

    /**
     * O retrato de um conjunto de linhas de um tipo: o período, quantas e os valores distintos de cada contador (até
     * [MAX_AUDITED_VALUES]); sem as linhas uma a uma (uma recontagem toca até 730).
     */
    fun snapshot(rows: Map<InventoryKey, InventoryCounters>): Map<String, Any?> =
        mapOf(
            "from" to rows.keys.minOfOrNull { it.date }?.toString(),
            "to" to rows.keys.maxOfOrNull { it.date }?.toString(),
            "rows" to rows.size,
            "total" to distinct(rows.values.map { it.total }),
            "sold" to distinct(rows.values.map { it.sold }),
            "blocked" to distinct(rows.values.map { it.blocked }),
            "outOfOrder" to distinct(rows.values.map { it.outOfOrder }),
            "overbookingLimit" to distinct(rows.values.map { it.overbookingLimit }),
        )

    private fun distinct(values: List<Int>): List<Int> = values.distinct().sorted().take(MAX_AUDITED_VALUES)
}

/** Publica [AvailabilityChanged] na transação: um evento por tipo, com a primeira e a última data mudada. */
@Component
class AvailabilityEvents(
    private val events: ApplicationEventPublisher,
) {
    fun publish(
        keys: Collection<InventoryKey>,
        now: Instant,
    ) {
        keys
            .groupBy { it.roomTypeId }
            .toSortedMap(compareBy { it.value.toString() })
            .forEach { (type, typeKeys) ->
                events.publishEvent(
                    AvailabilityChanged(type.value, typeKeys.minOf { it.date }, typeKeys.maxOf { it.date }, now),
                )
            }
    }
}

/**
 * Tomada e devolução de unidades do inventário (RF-TAR-08 e 09, Q-09, ARCHITECTURE §5 e §11), para as reservas (E07)
 * e os bloqueios (E11), **na transação de escrita de quem chama** (fora dela, ou numa só de leitura,
 * `IllegalStateException`): o caso de uso de quem chama é o auditado.
 *
 * Trava as linhas das datas envolvidas com `SELECT … FOR UPDATE` em ordem de (tipo de UH, data), então duas tomadas
 * simultâneas da última unidade se enfileiram e só a primeira passa (a segunda vê o contador já mudado e recebe `409
 * ROOM_TYPE_UNAVAILABLE`), e intervalos sobrepostos pedidos em qualquer ordem nunca dão deadlock. As regras ficam no
 * [InventoryLedger]: a linha cujo uso cresce precisa continuar com o disponível ≥ 0 (o limite de overbooking entra na
 * conta); a devolução passa sempre. As travas duram até o fim da transação de quem chama, que deve ser curta. Publica
 * [AvailabilityChanged] por tipo, pelo outbox, quando algo mudou.
 */
@Component
class RoomTypeInventory(
    private val store: InventoryStore,
    private val availability: AvailabilityEvents,
    private val clock: Clock,
) {
    /** Aplica as mudanças juntas (por exemplo, devolver as datas antigas e tomar as novas de uma alteração). */
    fun apply(changes: List<InventoryChange>) {
        check(
            TransactionSynchronizationManager.isActualTransactionActive() &&
                !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
        ) { "O inventário só muda dentro da transação de escrita de quem chama" }
        if (changes.isEmpty()) return
        val current = store.lockRows(InventoryLedger.keys(changes))
        val changed = InventoryLedger.apply(current, changes)
        if (changed.isEmpty()) return
        val now = clock.instant()
        store.update(changed, now)
        availability.publish(changed.keys, now)
    }

    /** Toma [quantity] unidades do tipo nas noites de [from] até a véspera de [until]. */
    fun take(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        until: LocalDate,
        quantity: Int = 1,
        use: InventoryUse = InventoryUse.SOLD,
    ) = apply(listOf(InventoryChange.take(roomTypeId, from, until, quantity, use)))

    /** Devolve [quantity] unidades do tipo nas noites de [from] até a véspera de [until]. */
    fun release(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        until: LocalDate,
        quantity: Int = 1,
        use: InventoryUse = InventoryUse.SOLD,
    ) = apply(listOf(InventoryChange.release(roomTypeId, from, until, quantity, use)))
}

package com.zeny.hotel.reservations.application

import com.zeny.hotel.guests.GuestActiveStayCheck
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.GuestPersonalDataContributor
import com.zeny.hotel.guests.GuestStatus
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.guests.GuestsMerged
import com.zeny.hotel.guests.RestrictionAlert
import com.zeny.hotel.rates.RatesApi
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryUsage
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** A reserva agrupadora com os alertas da lista de restrição dos hóspedes (RF-HOS-04), lidos na hora. */
data class BookingView(
    val booking: BookingSummary,
    val restrictionAlerts: List<RestrictionAlert>,
)

/** A reserva com os alertas da lista de restrição dos hóspedes e o código da reserva agrupadora. */
data class ReservationView(
    val reservation: ReservationSummary,
    val bookingCode: String,
    val holderGuestId: GuestId,
    val restrictionAlerts: List<RestrictionAlert>,
)

/**
 * As reservas pela API (M4 E07): criar exige `reservations.reservations.manage` (e o desconto, a permissão sensível do
 * `rates`, H7); consultar, `reservations.reservations.read`. A resposta traz o alerta da lista de restrição (RF-HOS-04)
 * de cada hóspede, que não bloqueia a reserva. A criação é [BookingCreation].
 */
@Service
class BookingService(
    private val creation: BookingCreation,
    private val store: BookingStore,
    private val guests: GuestsApi,
    private val authorization: Authorization,
) {
    @Audited(ReservationsAudit.CREATE_BOOKING)
    @Transactional
    fun create(
        command: NewBooking,
        approvalToken: String?,
    ): BookingView {
        authorization.require(ReservationsPermissions.RESERVATIONS_MANAGE)
        val booking = creation.create(command, DiscountAuthorization.Required(approvalToken))
        return BookingView(booking, alerts(booking.guestIds()))
    }

    /** A criação pela `ReservationsApi`, sem permissão nem step-up (quem chama já se autorizou). */
    @Audited(ReservationsAudit.CREATE_BOOKING)
    @Transactional
    fun createAuthorized(command: NewBooking): BookingSummary =
        creation.create(command, DiscountAuthorization.AlreadyAuthorized)

    @Transactional(readOnly = true)
    fun booking(id: BookingId): BookingView {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val booking = store.booking(id) ?: throw bookingNotFound()
        return BookingView(booking, alerts(booking.guestIds()))
    }

    @Transactional(readOnly = true)
    fun reservation(id: ReservationId): ReservationView {
        authorization.require(ReservationsPermissions.RESERVATIONS_READ)
        val reservation = store.reservation(id) ?: throw reservationNotFound()
        val booking = checkNotNull(store.booking(reservation.bookingId)) { "Reserva sem a reserva agrupadora" }
        return ReservationView(
            reservation,
            booking.code.toString(),
            booking.holderGuestId,
            alerts(listOf(booking.holderGuestId) + reservation.guests.map { it.guestId }),
        )
    }

    private fun alerts(guestIds: Collection<GuestId>): List<RestrictionAlert> =
        guestIds.distinct().mapNotNull { guests.restrictionAlert(it) }.distinctBy { it.guestId }

    private fun BookingSummary.guestIds(): List<GuestId> =
        listOf(holderGuestId) + reservations.flatMap { reservation -> reservation.guests.map { it.guestId } }

    private fun bookingNotFound() =
        NotFoundException(ReservationsErrorCodes.BOOKING_NOT_FOUND, "Reserva agrupadora não encontrada.")

    private fun reservationNotFound() =
        NotFoundException(ReservationsErrorCodes.RESERVATION_NOT_FOUND, "Reserva não encontrada.")
}

/**
 * O reapontamento das referências de um hóspede mesclado (H8; SPEC §8.3): o titular dos bookings e os hóspedes das
 * reservas passam do absorvido ao sobrevivente **final** (seguindo `mergedInto` pela `GuestsApi`: numa cadeia A → B →
 * C com os eventos fora de ordem, A vai direto a C). Idempotente: repontar o que já foi repontado não muda nada (sem
 * entrada na trilha). Toma a trava exclusiva do absorvido ([BookingStore.lockGuests]): uma criação em andamento com ele
 * termina antes, e o reapontamento a vê.
 */
@Service
class GuestReferenceRepointing(
    private val store: BookingStore,
    private val guests: GuestsApi,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Audited(ReservationsAudit.REPOINT_GUEST)
    @Transactional
    fun repoint(
        merged: GuestId,
        survivor: GuestId,
    ): GuestRepointing {
        val target = finalProfile(survivor)
        store.lockGuests(listOf(merged), exclusive = true)
        val changes = store.repointGuest(merged, target, clock.instant())
        if (changes.empty) {
            audit.noChanges()
            return changes
        }
        changes.bookings.forEach {
            audit.record(
                ReservationsAudit.BOOKING,
                it.value,
                mapOf("holderGuestId" to merged.toString()),
                mapOf("holderGuestId" to target.toString(), "mergedGuestId" to merged.toString()),
            )
        }
        changes.reservations.forEach { (id, change) ->
            audit.record(
                ReservationsAudit.RESERVATION,
                id.value,
                mapOf("guestIds" to change.first.map { it.toString() }),
                mapOf("guestIds" to change.second.map { it.toString() }, "mergedGuestId" to merged.toString()),
            )
        }
        return changes
    }

    private fun finalProfile(id: GuestId): GuestId {
        var current = id
        repeat(MAX_CHAIN) {
            val guest = guests.guest(current)
            if (guest?.status != GuestStatus.MERGED) return current
            current = checkNotNull(guest.mergedInto) { "Perfil mesclado sem o sobrevivente" }
        }
        return current
    }

    private companion object {
        const val MAX_CHAIN = 50
    }
}

/**
 * O primeiro consumidor de produção de [GuestsMerged] (H8): assíncrono, numa transação própria e reentregue pelo outbox
 * se falhar; idempotente ([GuestReferenceRepointing]).
 */
@Component
class GuestsMergedListener(
    private val repointing: GuestReferenceRepointing,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun on(event: GuestsMerged) {
        val changes = repointing.repoint(GuestId(event.mergedId), GuestId(event.survivorId))
        log.info(
            "Reservas reapontadas do hóspede {}: {} reserva(s) agrupadora(s), {} reserva(s)",
            event.mergedId,
            changes.bookings.size,
            changes.reservations.size,
        )
    }
}

/**
 * A estadia ativa para a anonimização (H12, porta `GuestActiveStayCheck` do `guests`): o hóspede é titular ou hóspede
 * de uma reserva pré-reserva, confirmada ou hospedada. Toma a trava exclusiva dos perfis antes de conferir (roda na
 * transação da anonimização, até o commit): uma reserva sendo criada com o perfil termina antes e conta; uma criada
 * depois espera a anonimização e a vê.
 */
@Component
class ReservationsActiveStayCheck(
    private val store: BookingStore,
) : GuestActiveStayCheck {
    override fun hasActiveStay(guestIds: List<GuestId>): Boolean {
        if (guestIds.isEmpty()) return false
        store.lockGuests(guestIds, exclusive = true)
        return store.hasActiveStay(guestIds)
    }
}

/**
 * A seção `reservations` da exportação dos dados do titular (porta `GuestPersonalDataContributor` do `guests`, H12):
 * por reserva em que algum dos perfis é titular ou hóspede, o código, a reserva agrupadora, o papel, a situação, as
 * datas, o tipo de UH e o plano (ids e nomes), a ocupação, o segmento, os pedidos especiais e as noites com o preço.
 * `null` sem reservas.
 */
@Component
class ReservationsPersonalData(
    private val store: BookingStore,
    private val rooms: RoomsApi,
    private val rates: RatesApi,
) : GuestPersonalDataContributor {
    override val section = "reservations"

    override fun personalData(guestIds: List<GuestId>): Any? {
        val ids = store.reservationsOfGuests(guestIds)
        if (ids.isEmpty()) return null
        val wanted = guestIds.toSet()
        return mapOf(
            "reservations" to
                ids.map { id ->
                    val reservation = checkNotNull(store.reservation(id))
                    val booking = checkNotNull(store.booking(reservation.bookingId))
                    mapOf(
                        "code" to reservation.code.toString(),
                        "bookingCode" to booking.code.toString(),
                        "roles" to roles(booking, reservation, wanted),
                        "status" to reservation.status.name,
                        "arrival" to reservation.arrival,
                        "departure" to reservation.departure,
                        "dayUse" to reservation.dayUse,
                        "roomType" to
                            mapOf(
                                "id" to reservation.roomTypeId.toString(),
                                "name" to rooms.roomType(reservation.roomTypeId)?.name,
                            ),
                        "ratePlan" to
                            mapOf(
                                "id" to reservation.ratePlanId.toString(),
                                "name" to rates.ratePlan(reservation.ratePlanId)?.name,
                            ),
                        "adults" to reservation.adults,
                        "childAges" to reservation.childAges,
                        "segment" to reservation.segment.name,
                        "specialRequests" to reservation.specialRequests,
                        "nights" to reservation.nightlyRates.map { mapOf("date" to it.date, "amount" to it.amount) },
                        "total" to reservation.total,
                        "createdAt" to reservation.createdAt,
                    )
                },
        )
    }

    private fun roles(
        booking: BookingSummary,
        reservation: ReservationSummary,
        wanted: Set<GuestId>,
    ): List<String> =
        buildList {
            if (booking.holderGuestId in wanted) add("HOLDER")
            reservation.guests.filter { it.guestId in wanted }.forEach {
                add(
                    if (it.primary) "PRIMARY_GUEST" else "GUEST",
                )
            }
        }.distinct()
}

/** O uso do inventário pelas reservas para a recontagem (E05): as vendidas por tipo e noite. */
@Component
class ReservationsInventoryUsage(
    private val store: BookingStore,
) : InventoryUsageSource {
    override fun usage(
        roomTypeIds: Collection<RoomTypeId>,
        from: LocalDate,
        to: LocalDate,
    ): Map<InventoryKey, InventoryUsage> = store.soldUnits(roomTypeIds, from, to)
}

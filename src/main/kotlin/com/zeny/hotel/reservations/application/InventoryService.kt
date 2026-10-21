package com.zeny.hotel.reservations.application

import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.reservations.BookingId
import com.zeny.hotel.reservations.BookingSummary
import com.zeny.hotel.reservations.InventoryDay
import com.zeny.hotel.reservations.InventoryRecount
import com.zeny.hotel.reservations.NewBooking
import com.zeny.hotel.reservations.ReservationCancellationRequest
import com.zeny.hotel.reservations.ReservationId
import com.zeny.hotel.reservations.ReservationSummary
import com.zeny.hotel.reservations.ReservationsApi
import com.zeny.hotel.reservations.ReservationsErrorCodes
import com.zeny.hotel.reservations.ReservationsPermissions
import com.zeny.hotel.reservations.domain.InventoryCounters
import com.zeny.hotel.reservations.domain.InventoryKey
import com.zeny.hotel.reservations.domain.InventoryOutOfHorizonException
import com.zeny.hotel.reservations.domain.InventoryPeriod
import com.zeny.hotel.reservations.domain.InventoryRules
import com.zeny.hotel.reservations.domain.OverbookingLimit
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsApi
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ModulePermissions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

/** O pedido de limite de overbooking: os tipos de UH, o período, os dias da semana e o limite. */
data class OverbookingLimitCommand(
    val roomTypeIds: List<RoomTypeId>,
    val from: LocalDate,
    val to: LocalDate,
    val weekdays: Set<DayOfWeek>,
    val limit: Int,
)

/** O resultado de uma mudança de limite: as datas pedidas, as linhas que mudaram e o intervalo delas. */
data class OverbookingLimitChange(
    val matchedDates: Int,
    val changedRows: Int,
    val from: LocalDate?,
    val to: LocalDate?,
)

/**
 * O inventário pela API (RF-TAR-08 e 09, Q-09, H16 do M4): consultar exige `reservations.availability.read`; o
 * limite de overbooking e a recontagem, `reservations.inventory.manage` (não sensível).
 *
 * **Limite de overbooking** ([setOverbookingLimit]): o mesmo limite (de 0 a 50) nos tipos de UH pedidos × as datas do
 * período (até 366 dias, a partir da data operacional) nos dias da semana pedidos. Trava as linhas em ordem de (tipo,
 * data), como a tomada de unidades: um limite baixado ao mesmo tempo que uma reserva toma a última unidade vale para a
 * que vier depois. Toda data precisa ter inventário (`422 INVENTORY_DATE_OUT_OF_HORIZON`). Baixar o limite não desfaz
 * vendas: a data fica sobrevendida. Toda mudança vai à trilha e publica `AvailabilityChanged`; sem mudança, nada.
 */
@Service
@Suppress("LongParameterList") // as portas de que o inventário pela API precisa
class InventoryService(
    private val store: InventoryStore,
    private val refresher: InventoryRefresher,
    private val rooms: RoomsApi,
    private val hotel: HotelApi,
    private val authorization: Authorization,
    private val availability: AvailabilityEvents,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun read(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay> {
        authorization.require(ReservationsPermissions.AVAILABILITY_READ)
        val period = InventoryPeriod.of(from, to)
        if (rooms.roomType(roomTypeId) == null) throw unknownRoomType("roomTypeId")
        return days(store.rows(roomTypeId, period.from, period.to))
    }

    @Audited(ReservationsAudit.SET_OVERBOOKING_LIMIT)
    @Transactional
    fun setOverbookingLimit(command: OverbookingLimitCommand): OverbookingLimitChange {
        authorization.require(ReservationsPermissions.INVENTORY_MANAGE)
        val limit = OverbookingLimit.of(command.limit)
        val types = roomTypes(command.roomTypeIds)
        val period = InventoryPeriod.of(command.from, command.to, command.weekdays)
        val dates = datesFrom(period)
        types.forEach { if (rooms.roomType(it) == null) throw unknownRoomType("roomTypeIds") }
        val keys = types.flatMap { type -> dates.map { InventoryKey(type, it) } }
        val current = store.lockRows(keys)
        val missing = keys.filter { it !in current }
        if (missing.isNotEmpty()) throw InventoryOutOfHorizonException(missing.map { it.date })
        val changed =
            current
                .filterValues { it.overbookingLimit != limit }
                .mapValues { (_, counters) -> counters.copy(overbookingLimit = limit) }
        if (changed.isEmpty()) {
            audit.noChanges()
            return OverbookingLimitChange(dates.size, 0, null, null)
        }
        val now = clock.instant()
        store.update(changed, now)
        changed.keys.groupBy { it.roomTypeId }.forEach { (type, typeKeys) ->
            audit.record(
                ReservationsAudit.ROOM_TYPE_INVENTORY,
                type.value,
                ReservationsAudit.snapshot(typeKeys.associateWith { current.getValue(it) }),
                ReservationsAudit.snapshot(typeKeys.associateWith { changed.getValue(it) }) +
                    ("weekdays" to period.weekdays.sorted().map { it.name }),
            )
        }
        availability.publish(changed.keys, now)
        return OverbookingLimitChange(
            dates.size,
            changed.size,
            changed.keys.minOf { it.date },
            changed.keys.maxOf { it.date },
        )
    }

    /** A recontagem pedida por quem administra o inventário (a mesma do fechamento do dia). */
    @Audited(ReservationsAudit.RECOUNT_INVENTORY)
    @Transactional
    fun recount(
        from: LocalDate?,
        to: LocalDate?,
    ): InventoryRecount {
        authorization.require(ReservationsPermissions.INVENTORY_MANAGE)
        if (from != null && to != null && to.isBefore(from)) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.INVENTORY_PERIOD_INVALID,
                "to",
                "O fim do período não pode ser antes do início.",
            )
        }
        return refresher.recount(from, to)
    }

    /** As datas do período nos dias da semana (alguma, `400`), sem começar antes da data operacional (`422`). */
    private fun datesFrom(period: InventoryPeriod): List<LocalDate> {
        val dates = period.dates()
        if (dates.isEmpty()) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.INVENTORY_PERIOD_INVALID,
                "weekdays",
                "Nenhuma data do período cai nos dias da semana informados.",
            )
        }
        if (period.from.isBefore(hotel.currentBusinessDate())) {
            throw BusinessRuleException(
                ReservationsErrorCodes.INVENTORY_PERIOD_IN_PAST,
                "O período não pode começar antes da data operacional.",
            )
        }
        return dates
    }

    private fun roomTypes(ids: List<RoomTypeId>): List<RoomTypeId> {
        if (ids.isEmpty() || ids.size > MAX_ROOM_TYPES || ids.toSet().size != ids.size) {
            throw InventoryRules.invalid(
                ReservationsErrorCodes.OVERBOOKING_LIMIT_INVALID,
                "roomTypeIds",
                "Informe de 1 a $MAX_ROOM_TYPES tipos de UH, sem repetir.",
            )
        }
        return ids.sortedBy { it.value.toString() }
    }

    private fun unknownRoomType(field: String) =
        InventoryRules.invalid(ReservationsErrorCodes.UNKNOWN_ROOM_TYPE, field, "O tipo de UH informado não existe.")

    private companion object {
        const val MAX_ROOM_TYPES = 50
    }
}

/** Converte as linhas lidas nos dias da API pública. */
internal fun days(rows: Map<InventoryKey, InventoryCounters>): List<InventoryDay> =
    rows.entries
        .sortedWith(
            compareBy({
                it.key.roomTypeId.value
                    .toString()
            }, { it.key.date }),
        ).map { (key, counters) ->
            InventoryDay(
                key.roomTypeId,
                key.date,
                counters.total,
                counters.sold,
                counters.blocked,
                counters.outOfOrder,
                counters.overbookingLimit,
            )
        }

/** A API pública do `reservations` ([ReservationsApi]), sem checar permissão. */
@Service
class ReservationsApiService(
    private val store: InventoryStore,
    private val recalculation: InventoryRecalculation,
    private val bookings: BookingService,
    private val bookingStore: BookingStore,
    private val lifecycle: ReservationLifecycleService,
) : ReservationsApi {
    override fun createBooking(booking: NewBooking): BookingSummary = bookings.createAuthorized(booking)

    override fun cancelReservation(
        id: ReservationId,
        cancellation: ReservationCancellationRequest,
    ): ReservationSummary = lifecycle.cancelAuthorized(id, cancellation)

    @Transactional(readOnly = true)
    override fun booking(id: BookingId): BookingSummary? = bookingStore.booking(id)

    @Transactional(readOnly = true)
    override fun reservation(id: ReservationId): ReservationSummary? = bookingStore.reservation(id)

    @Transactional(readOnly = true)
    override fun inventory(
        roomTypeId: RoomTypeId,
        from: LocalDate,
        to: LocalDate,
    ): List<InventoryDay> {
        val period = InventoryPeriod.of(from, to)
        return days(store.rows(roomTypeId, period.from, period.to))
    }

    override fun recountInventory(
        from: LocalDate?,
        to: LocalDate?,
    ): InventoryRecount = recalculation.recount(from, to)
}

@Configuration(proxyBeanMethods = false)
class ReservationsPermissionsConfiguration {
    @Bean
    fun reservationsPermissions(): ModulePermissions = ReservationsPermissions
}

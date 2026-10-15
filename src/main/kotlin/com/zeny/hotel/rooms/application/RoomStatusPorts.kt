package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatus
import com.zeny.hotel.rooms.RoomStatusField
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.domain.CheckInReadiness
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomStatusFieldChange
import com.zeny.hotel.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** As mudanças de governança que a mudança manual aceita (RN-08), uma por operação da SPEC §8.4. */
enum class HousekeepingTransition {
    MARK_DIRTY,
    MARK_CLEAN,
    MARK_INSPECTED,
    REJECT_INSPECTION,
}

/** O tipo de discrepância entre recepção e governança (RF-UH-08). */
enum class RoomDiscrepancyKind {
    /** A recepção diz Ocupada e a governança achou a UH vazia. */
    OCCUPIED_BUT_FOUND_VACANT,

    /** A recepção diz Vaga e a governança achou a UH ocupada. */
    VACANT_BUT_FOUND_OCCUPIED,

    /** As duas dizem Ocupada, mas o número de hóspedes diverge. */
    GUEST_COUNT_MISMATCH,
}

/**
 * O status de uma UH com o que o painel mostra dela (RF-UH-07), inclusive a sobreposição OOO/OOS em
 * vigor na data operacional ([outOfOrder]).
 */
data class RoomStatusView(
    val roomId: RoomId,
    val number: String,
    val floor: String?,
    val roomTypeId: RoomTypeId,
    val active: Boolean,
    val occupancy: OccupancyStatus,
    val guestCount: Int?,
    val housekeepingStatus: HousekeepingStatus,
    val inspectionPending: Boolean,
    val serviceFlag: GuestServiceFlag,
    val changedAt: Instant,
    val version: Long,
    val outOfOrder: OutOfOrderOverlay?,
) {
    /** RN-05, com o OOO. */
    val readyForCheckIn: Boolean
        get() = CheckInReadiness.isReady(active, occupancy, housekeepingStatus, inspectionPending, outOfOrder?.kind)
}

/** Um registro do histórico de status (RF-UH-06): quem ([changedBy] nulo = o sistema), quando e a origem. */
data class RoomStatusChangeView(
    val id: UUID,
    val roomId: RoomId,
    val field: RoomStatusField,
    val from: String?,
    val to: String?,
    val changedAt: Instant,
    val changedBy: UserId?,
    val source: RoomStatusSource,
    val roomVersion: Long,
)

/** O que a governança encontrou numa UH (RF-UH-08). [guestCount] nulo: não contou (ou a UH estava vazia). */
data class HousekeepingObservationView(
    val id: UUID,
    val roomId: RoomId,
    val occupancy: OccupancyStatus,
    val guestCount: Int?,
    val observedAt: Instant,
    val observedBy: UserId,
)

/** Uma discrepância vigente: o status da recepção e a última observação da governança, que diverge dele. */
data class RoomDiscrepancyView(
    val roomId: RoomId,
    val number: String,
    val kind: RoomDiscrepancyKind,
    val frontDeskOccupancy: OccupancyStatus,
    val frontDeskGuestCount: Int?,
    val observation: HousekeepingObservationView,
)

/**
 * O histórico de status, as observações da governança e as leituras do status (`JdbcClient`, ARCHITECTURE
 * §11). O histórico e as observações são *append-only*: só se acrescenta, na transação corrente.
 */
interface RoomStatusRecords {
    /** Acrescenta ao histórico um registro por campo de [changes], todos com o mesmo quem, quando e origem. */
    @Suppress("LongParameterList") // os dados do registro (RF-UH-06)
    fun appendChanges(
        roomId: RoomId,
        changes: List<RoomStatusFieldChange>,
        changedAt: Instant,
        changedBy: UserId?,
        source: RoomStatusSource,
        roomVersion: Long,
    )

    fun appendObservation(observation: HousekeepingObservationView)

    /**
     * Uma página do painel: UHs ativas em ordem de número (sem diferenciar maiúsculas) e id, depois de
     * [after], com a sobreposição OOO/OOS em vigor na data [today].
     */
    @Suppress("LongParameterList") // os filtros, a data e a página
    fun board(
        today: LocalDate,
        occupancy: OccupancyStatus?,
        housekeepingStatus: HousekeepingStatus?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomStatusView>

    /** Uma página do histórico da UH, das mudanças mais recentes às mais antigas, depois de [after]. */
    fun history(
        roomId: RoomId,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<RoomStatusChangeView>

    /**
     * Uma página das discrepâncias vigentes, em ordem de número e id, depois de [after]: UHs ativas cuja
     * última observação, feita depois da última mudança da ocupação ou do número de hóspedes, diverge da
     * recepção.
     */
    fun discrepancies(
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomDiscrepancyView>
}

internal fun Room.statusView(outOfOrder: OutOfOrderOverlay?) =
    RoomStatusView(
        id,
        number,
        floor,
        roomTypeId,
        active,
        occupancy,
        guestCount,
        housekeepingStatus,
        inspectionPending,
        serviceFlag,
        statusChangedAt,
        checkNotNull(version),
        outOfOrder,
    )

internal fun RoomStatusView.status() =
    RoomStatus(
        roomId,
        active,
        occupancy,
        guestCount,
        housekeepingStatus,
        inspectionPending,
        serviceFlag,
        outOfOrder?.kind,
        readyForCheckIn,
        changedAt,
        version,
    )

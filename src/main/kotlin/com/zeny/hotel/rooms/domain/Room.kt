package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.GuestServiceFlag
import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OccupancyStatus
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomStatusField
import com.zeny.hotel.rooms.RoomStatusSource
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant

/** Os dados de uma UH (RF-UH-02), como chegam para criar ou editar. */
data class RoomDetails(
    val number: String,
    val floor: String?,
    val roomTypeId: RoomTypeId,
    val featureIds: Set<RoomFeatureId>,
    val notes: String?,
)

/**
 * UH (RF-UH-02, SPEC §8.2.3): número único (sem diferenciar maiúsculas; o banco garante), andar ou
 * ala, tipo, características (vista, acessível, conjugada…), observações e ativa/inativa. Nunca é
 * apagada (o banco recusa): desativada, sai das listas de UHs ativas. O OOO/OOS (E03) entra depois.
 *
 * **Status** (RF-UH-04, M2 E02), em dimensões independentes: ocupação ([occupancy], com o número de
 * hóspedes informado pela recepção, H4), governança ([housekeepingStatus] com [inspectionPending]) e
 * sinalização do hóspede ([serviceFlag]). A UH nasce Vaga e Suja (SPEC §8.4). O status de governança
 * segue só as transições da SPEC §8.4:
 *
 * - Sujo → Limpo ([markClean] sem inspeção) ou Limpo com inspeção pendente ([markClean] com inspeção);
 * - Limpo com inspeção pendente → Inspecionado ([markInspected]) ou Sujo ([rejectInspection]);
 * - Limpo → Inspecionado ([markInspected], inspeção voluntária);
 * - Limpo (com ou sem inspeção pendente) e Inspecionado → Sujo ([markDirty]: check-out, fechamento, uso).
 *
 * Qualquer outra (Sujo → Inspecionado, Inspecionado → Limpo, pular a inspeção pendente…) é recusada
 * com `422 HOUSEKEEPING_TRANSITION_NOT_ALLOWED`, sem efeito; repetir a operação do estado atual não muda
 * nada. Cada operação devolve os campos que mudaram, para o histórico (RF-UH-06); a versão (lock
 * otimista) é a mesma do cadastro.
 */
@Entity
@Table(name = "room", schema = "rooms")
@Suppress("TooManyFunctions") // o cadastro e uma operação por mudança de status
class Room private constructor(
    @Id
    val id: RoomId,
    details: RoomDetails,
    createdAt: Instant,
) : RegistryRecord(createdAt) {
    @Column(nullable = false)
    var number: String = ""
        protected set

    var floor: String? = null
        protected set

    @Column(name = "room_type_id", nullable = false)
    var roomTypeId: RoomTypeId = details.roomTypeId
        protected set

    var notes: String? = null
        protected set

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "room_feature_link", schema = "rooms", joinColumns = [JoinColumn(name = "room_id")])
    @Column(name = "feature_id", nullable = false)
    @Convert(converter = RoomFeatureIdConverter::class)
    private val featureIdSet: MutableSet<RoomFeatureId> = mutableSetOf()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var occupancy: OccupancyStatus = OccupancyStatus.VACANT
        protected set

    /** Número de hóspedes informado pela recepção; só numa UH ocupada. */
    @Column(name = "guest_count")
    var guestCount: Int? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "housekeeping_status", nullable = false)
    var housekeepingStatus: HousekeepingStatus = HousekeepingStatus.DIRTY
        protected set

    /** "Limpo — inspeção pendente": só com [housekeepingStatus] Limpo. */
    @Column(name = "inspection_pending", nullable = false)
    var inspectionPending: Boolean = false
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "service_flag", nullable = false)
    var serviceFlag: GuestServiceFlag = GuestServiceFlag.NONE
        protected set

    /** Instante da última mudança de status (qualquer dimensão). */
    @Column(name = "status_changed_at", nullable = false)
    var statusChangedAt: Instant = createdAt
        protected set

    /**
     * Instante da última mudança da ocupação ou do número de hóspedes: uma observação da governança
     * anterior a ele não vale mais para as discrepâncias (RF-UH-08).
     */
    @Column(name = "occupancy_changed_at", nullable = false)
    var occupancyChangedAt: Instant = createdAt
        protected set

    init {
        apply(details)
    }

    /** RN-05 (sem o OOO, que entra na E03). */
    val isReadyForCheckIn: Boolean
        get() = CheckInReadiness.isReady(active, occupancy, housekeepingStatus, inspectionPending)

    // --- Status de governança (SPEC §8.4) ----------------------------------------------------------

    /** Limpo (com ou sem inspeção pendente) ou Inspecionado → Sujo; já suja, nada muda. */
    fun markDirty(now: Instant): List<RoomStatusFieldChange> =
        housekeeping(HousekeepingStatus.DIRTY, pending = false, now)

    /** Sujo → Limpo, com a inspeção pendente se [requiresInspection]. */
    fun markClean(
        requiresInspection: Boolean,
        now: Instant,
    ): List<RoomStatusFieldChange> {
        if (housekeepingStatus == HousekeepingStatus.CLEAN && inspectionPending == requiresInspection) {
            return emptyList()
        }
        if (housekeepingStatus != HousekeepingStatus.DIRTY) {
            throw transitionRefused(
                if (inspectionPending) {
                    "A UH está aguardando inspeção: aprove ou reprove a inspeção."
                } else {
                    "Só uma UH suja é marcada como limpa."
                },
            )
        }
        return housekeeping(HousekeepingStatus.CLEAN, requiresInspection, now)
    }

    /** Limpo (inspeção pendente ou voluntária) → Inspecionado. */
    fun markInspected(now: Instant): List<RoomStatusFieldChange> {
        if (housekeepingStatus == HousekeepingStatus.DIRTY) {
            throw transitionRefused("A UH está suja: ela precisa ser limpa antes da inspeção.")
        }
        return housekeeping(HousekeepingStatus.INSPECTED, pending = false, now)
    }

    /** Limpo com inspeção pendente → Sujo (inspeção reprovada). */
    fun rejectInspection(now: Instant): List<RoomStatusFieldChange> {
        if (housekeepingStatus == HousekeepingStatus.DIRTY) return emptyList()
        if (!inspectionPending) throw transitionRefused("A UH não está aguardando inspeção.")
        return housekeeping(HousekeepingStatus.DIRTY, pending = false, now)
    }

    // --- Ocupação e sinalização do hóspede -----------------------------------------------------------

    /** A UH passa a Ocupada com [guestCount] hóspedes; já ocupada, só troca o número. */
    fun markOccupied(
        guestCount: Int,
        now: Instant,
    ): List<RoomStatusFieldChange> {
        requireGuestCount(guestCount)
        if (!active) {
            throw BusinessRuleException(RoomsErrorCodes.ROOM_INACTIVE, "A UH está desativada e não pode ser ocupada.")
        }
        val changes =
            buildList {
                addIfChanged(RoomStatusField.OCCUPANCY, occupancy, OccupancyStatus.OCCUPIED)
                addIfChanged(RoomStatusField.GUEST_COUNT, this@Room.guestCount, guestCount)
            }
        if (changes.isEmpty()) return changes
        occupancy = OccupancyStatus.OCCUPIED
        this.guestCount = guestCount
        occupancyChangedAt = now
        statusChangedAt = now
        return changes
    }

    /**
     * A UH passa a Vaga, sem número de hóspedes e sem sinalização do hóspede; com a origem
     * [RoomStatusSource.CHECK_OUT], também Suja (RN-09).
     */
    fun markVacant(
        source: RoomStatusSource,
        now: Instant,
    ): List<RoomStatusFieldChange> {
        val changes =
            buildList {
                addIfChanged(RoomStatusField.OCCUPANCY, occupancy, OccupancyStatus.VACANT)
                addIfChanged(RoomStatusField.GUEST_COUNT, guestCount, null)
                addIfChanged(RoomStatusField.SERVICE_FLAG, serviceFlag, GuestServiceFlag.NONE)
            }
        if (changes.isNotEmpty()) {
            occupancy = OccupancyStatus.VACANT
            guestCount = null
            serviceFlag = GuestServiceFlag.NONE
            occupancyChangedAt = now
            statusChangedAt = now
        }
        return if (source == RoomStatusSource.CHECK_OUT) changes + markDirty(now) else changes
    }

    /** A sinalização do hóspede; só numa UH ocupada, exceto [GuestServiceFlag.NONE]. */
    fun setServiceFlag(
        flag: GuestServiceFlag,
        now: Instant,
    ): List<RoomStatusFieldChange> {
        if (flag == serviceFlag) return emptyList()
        if (flag != GuestServiceFlag.NONE && occupancy != OccupancyStatus.OCCUPIED) {
            throw BusinessRuleException(
                RoomsErrorCodes.ROOM_NOT_OCCUPIED,
                "A sinalização do hóspede só vale numa UH ocupada.",
            )
        }
        val changes = listOf(RoomStatusFieldChange(RoomStatusField.SERVICE_FLAG, serviceFlag.name, flag.name))
        serviceFlag = flag
        statusChangedAt = now
        return changes
    }

    private fun housekeeping(
        status: HousekeepingStatus,
        pending: Boolean,
        now: Instant,
    ): List<RoomStatusFieldChange> {
        val changes =
            buildList {
                addIfChanged(RoomStatusField.HOUSEKEEPING_STATUS, housekeepingStatus, status)
                addIfChanged(RoomStatusField.INSPECTION_PENDING, inspectionPending, pending)
            }
        if (changes.isNotEmpty()) {
            housekeepingStatus = status
            inspectionPending = pending
            statusChangedAt = now
        }
        return changes
    }

    /** As características da UH. */
    val featureIds: Set<RoomFeatureId> get() = featureIdSet.toSet()

    /** Troca os dados; devolve se algo mudou. A validação do tipo e das características é do caso de uso. */
    fun update(
        details: RoomDetails,
        now: Instant,
    ): Boolean {
        val changed = apply(details)
        if (changed) updatedAt = now
        return changed
    }

    private fun apply(details: RoomDetails): Boolean {
        val number = RoomsRules.number(details.number)
        val floor = RoomsRules.optional("floor", details.floor, MAX_FLOOR_LENGTH)
        val notes = RoomsRules.optional("notes", details.notes, MAX_NOTES_LENGTH)
        val changed =
            number != this.number ||
                floor != this.floor ||
                notes != this.notes ||
                details.roomTypeId != roomTypeId
        this.number = number
        this.floor = floor
        this.notes = notes
        roomTypeId = details.roomTypeId
        return replaceAll(featureIdSet, details.featureIds) or changed
    }

    companion object {
        const val MAX_FLOOR_LENGTH = 40
        const val MAX_NOTES_LENGTH = 1000

        /** Maior número de hóspedes numa UH (o dobro do teto de adultos de um tipo de UH). */
        const val MAX_GUEST_COUNT = 40

        /** O número de hóspedes vai de 1 a [MAX_GUEST_COUNT]; fora disso, `400 GUEST_COUNT_INVALID`. */
        fun requireGuestCount(
            guestCount: Int,
            field: String = "guestCount",
        ) {
            if (guestCount !in 1..MAX_GUEST_COUNT) {
                throw RoomsRules.invalid(
                    RoomsErrorCodes.GUEST_COUNT_INVALID,
                    field,
                    "Informe de 1 a $MAX_GUEST_COUNT hóspedes.",
                )
            }
        }

        /**
         * O número de pessoas que a governança encontrou (RF-UH-08): opcional (ela pode não ter contado), de 1 a
         * [MAX_GUEST_COUNT] e só com a UH ocupada; vazia com número: `400 GUEST_COUNT_INVALID`.
         */
        fun requireObservedGuestCount(
            occupancy: OccupancyStatus,
            guestCount: Int?,
        ) {
            if (guestCount == null) return
            if (occupancy == OccupancyStatus.VACANT) {
                throw RoomsRules.invalid(
                    RoomsErrorCodes.GUEST_COUNT_INVALID,
                    "guestCount",
                    "Uma UH encontrada vazia não tem número de hóspedes.",
                )
            }
            requireGuestCount(guestCount)
        }

        private fun transitionRefused(message: String) =
            BusinessRuleException(RoomsErrorCodes.HOUSEKEEPING_TRANSITION_NOT_ALLOWED, message)

        private fun MutableList<RoomStatusFieldChange>.addIfChanged(
            field: RoomStatusField,
            from: Any?,
            to: Any?,
        ) {
            if (from != to) add(RoomStatusFieldChange(field, from?.text(), to?.text()))
        }

        private fun Any.text(): String = if (this is Enum<*>) name else toString()

        fun create(
            details: RoomDetails,
            now: Instant,
        ): Room = Room(RoomId.new(), details, now)
    }
}

package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
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
 * apagada (o banco recusa): desativada, sai das listas de UHs ativas. O status (E02) e o OOO/OOS (E03)
 * entram depois.
 */
@Entity
@Table(name = "room", schema = "rooms")
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

    init {
        apply(details)
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

        fun create(
            details: RoomDetails,
            now: Instant,
        ): Room = Room(RoomId.new(), details, now)
    }
}

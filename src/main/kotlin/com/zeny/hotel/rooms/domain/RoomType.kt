package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.Occupancy
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.RoomsErrorCodes
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

/** Os dados de um tipo de UH (RF-UH-01), como chegam para criar ou editar. */
data class RoomTypeDetails(
    val code: String,
    val name: String,
    val description: String?,
    val occupancy: Occupancy,
    val bedSetup: String?,
    val displayOrder: Int,
    val featureIds: Set<RoomFeatureId>,
)

/**
 * Tipo de UH (RF-UH-01, SPEC §8.2.3): código único, nome, descrição, ocupação padrão e máxima de
 * adultos e crianças (a máxima nunca abaixo da padrão; ao menos um adulto), configuração de camas,
 * características e ordem de exibição. Nunca é apagado, só desativado, e só sem UHs ativas (o caso de
 * uso confere).
 */
@Entity
@Table(name = "room_type", schema = "rooms")
class RoomType private constructor(
    @Id
    val id: RoomTypeId,
    details: RoomTypeDetails,
    createdAt: Instant,
) : RegistryRecord(createdAt) {
    @Column(nullable = false)
    var code: String = ""
        protected set

    @Column(nullable = false)
    var name: String = ""
        protected set

    var description: String? = null
        protected set

    @Column(name = "standard_adults", nullable = false)
    var standardAdults: Int = 0
        protected set

    @Column(name = "standard_children", nullable = false)
    var standardChildren: Int = 0
        protected set

    @Column(name = "max_adults", nullable = false)
    var maxAdults: Int = 0
        protected set

    @Column(name = "max_children", nullable = false)
    var maxChildren: Int = 0
        protected set

    @Column(name = "bed_setup")
    var bedSetup: String? = null
        protected set

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0
        protected set

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "room_type_feature_link",
        schema = "rooms",
        joinColumns = [JoinColumn(name = "room_type_id")],
    )
    @Column(name = "feature_id", nullable = false)
    @Convert(converter = RoomFeatureIdConverter::class)
    private val featureIdSet: MutableSet<RoomFeatureId> = mutableSetOf()

    init {
        apply(details)
    }

    val occupancy: Occupancy get() = Occupancy(standardAdults, standardChildren, maxAdults, maxChildren)

    /** As características do tipo. */
    val featureIds: Set<RoomFeatureId> get() = featureIdSet.toSet()

    /** Troca os dados; devolve se algo mudou. A validação das características é do caso de uso. */
    fun update(
        details: RoomTypeDetails,
        now: Instant,
    ): Boolean {
        val changed = apply(details)
        if (changed) updatedAt = now
        return changed
    }

    private fun apply(details: RoomTypeDetails): Boolean {
        val code = RoomsRules.code(details.code)
        val name = RoomsRules.required("name", details.name, MAX_NAME_LENGTH)
        val description = RoomsRules.optional("description", details.description, MAX_DESCRIPTION_LENGTH)
        val bedSetup = RoomsRules.optional("bedSetup", details.bedSetup, MAX_BED_SETUP_LENGTH)
        validate(details.occupancy)
        if (details.displayOrder !in 0..MAX_DISPLAY_ORDER) {
            throw RoomsRules.invalid(
                RoomsErrorCodes.ROOMS_DATA_INVALID,
                "displayOrder",
                "Informe a ordem de exibição de 0 a $MAX_DISPLAY_ORDER.",
            )
        }
        val changed =
            code != this.code ||
                name != this.name ||
                description != this.description ||
                bedSetup != this.bedSetup ||
                details.occupancy != occupancy ||
                details.displayOrder != displayOrder
        this.code = code
        this.name = name
        this.description = description
        this.bedSetup = bedSetup
        standardAdults = details.occupancy.standardAdults
        standardChildren = details.occupancy.standardChildren
        maxAdults = details.occupancy.maxAdults
        maxChildren = details.occupancy.maxChildren
        displayOrder = details.displayOrder
        return replaceAll(featureIdSet, details.featureIds) or changed
    }

    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_DESCRIPTION_LENGTH = 1000
        const val MAX_BED_SETUP_LENGTH = 200
        const val MAX_DISPLAY_ORDER = 9999
        const val MAX_GUESTS = 20

        fun create(
            details: RoomTypeDetails,
            now: Instant,
        ): RoomType = RoomType(RoomTypeId.new(), details, now)

        /** Ao menos um adulto na padrão; a máxima nunca abaixo da padrão nem acima de [MAX_GUESTS]. */
        private fun validate(occupancy: Occupancy) {
            val problem =
                when {
                    occupancy.standardAdults < 1 -> {
                        "standardAdults" to "O tipo precisa acomodar ao menos um adulto."
                    }

                    occupancy.standardChildren < 0 -> {
                        "standardChildren" to "Informe zero ou mais crianças."
                    }

                    occupancy.maxAdults < occupancy.standardAdults -> {
                        "maxAdults" to "A ocupação máxima de adultos não pode ser menor que a padrão."
                    }

                    occupancy.maxChildren < occupancy.standardChildren -> {
                        "maxChildren" to "A ocupação máxima de crianças não pode ser menor que a padrão."
                    }

                    occupancy.maxAdults > MAX_GUESTS -> {
                        "maxAdults" to "Use no máximo $MAX_GUESTS adultos."
                    }

                    occupancy.maxChildren > MAX_GUESTS -> {
                        "maxChildren" to "Use no máximo $MAX_GUESTS crianças."
                    }

                    else -> {
                        null
                    }
                }
            if (problem != null) {
                throw RoomsRules.invalid(RoomsErrorCodes.ROOM_TYPE_OCCUPANCY_INVALID, problem.first, problem.second)
            }
        }
    }
}

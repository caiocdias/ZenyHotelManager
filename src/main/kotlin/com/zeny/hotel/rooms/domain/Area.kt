package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Os dados de uma área comum (RF-UH-03), como chegam para criar ou editar. */
data class AreaDetails(
    val name: String,
    val kind: AreaKind,
    val description: String?,
)

/**
 * Área comum (RF-UH-03, SPEC §8.2.3): lobby, piscina, cozinha, salão, corredor ou outra, usada como
 * local de checklists, tarefas e manutenção. Nome único (sem diferenciar maiúsculas; o banco
 * garante). Nunca é apagada (o banco recusa), só desativada.
 */
@Entity
@Table(name = "area", schema = "rooms")
class Area private constructor(
    @Id
    val id: AreaId,
    details: AreaDetails,
    createdAt: Instant,
) : RegistryRecord(createdAt) {
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: AreaKind = details.kind
        protected set

    var description: String? = null
        protected set

    init {
        apply(details)
    }

    /** Troca os dados; devolve se algo mudou. */
    fun update(
        details: AreaDetails,
        now: Instant,
    ): Boolean {
        val changed = apply(details)
        if (changed) updatedAt = now
        return changed
    }

    private fun apply(details: AreaDetails): Boolean {
        val name = RoomsRules.required("name", details.name, MAX_NAME_LENGTH)
        val description = RoomsRules.optional("description", details.description, MAX_DESCRIPTION_LENGTH)
        val changed = name != this.name || details.kind != kind || description != this.description
        this.name = name
        kind = details.kind
        this.description = description
        return changed
    }

    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_DESCRIPTION_LENGTH = 500

        fun create(
            details: AreaDetails,
            now: Instant,
        ): Area = Area(AreaId.new(), details, now)
    }
}

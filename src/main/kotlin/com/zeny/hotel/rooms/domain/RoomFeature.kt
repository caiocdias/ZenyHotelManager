package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Característica de tipo de UH ou de UH (RF-UH-01 e 02, SPEC §8.2.3): vista, acessível, conjugada…
 * O código, em maiúsculas, é único (o banco garante). Desativada, não é atribuída de novo, mas os
 * vínculos existentes ficam. As três pré-carregadas pela migração têm a [key] (que nunca muda); as
 * criadas pela API, não.
 */
@Entity
@Table(name = "room_feature", schema = "rooms")
class RoomFeature private constructor(
    @Id
    val id: RoomFeatureId,
    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", updatable = false)
    val key: RoomFeatureKey?,
    code: String,
    name: String,
    createdAt: Instant,
) : RegistryRecord(createdAt) {
    @Column(nullable = false)
    var code: String = RoomsRules.code(code)
        protected set

    @Column(nullable = false)
    var name: String = RoomsRules.required("name", name, MAX_NAME_LENGTH)
        protected set

    /** Troca código e nome; devolve se algo mudou. */
    fun update(
        code: String,
        name: String,
        now: Instant,
    ): Boolean {
        val validCode = RoomsRules.code(code)
        val validName = RoomsRules.required("name", name, MAX_NAME_LENGTH)
        if (validCode == this.code && validName == this.name) return false
        this.code = validCode
        this.name = validName
        updatedAt = now
        return true
    }

    companion object {
        const val MAX_NAME_LENGTH = 80

        fun create(
            code: String,
            name: String,
            now: Instant,
        ): RoomFeature = RoomFeature(RoomFeatureId.new(), null, code, name, now)
    }
}

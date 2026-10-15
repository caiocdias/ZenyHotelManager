package com.zeny.hotel.rooms.domain

import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.shared.TypedIdConverter
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import java.time.Instant

/**
 * O que todo registro do cadastro de UHs e áreas tem: criação, última alteração, versão (lock otimista,
 * `ETag`) e ativo/inativo. Nada é apagado (o banco recusa): desativar tira o registro de uso e guarda o
 * histórico.
 */
@MappedSuperclass
@Suppress("AbstractClassCanBeConcreteClass") // superclasse mapeada do JPA: só existe nas entidades que a estendem
abstract class RegistryRecord(
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    /** Desativa; devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /** Reativa; devolve se mudou. As condições (tipo ativo, por exemplo) são do caso de uso. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }
}

/** Conversor do [RoomFeatureId] para as coleções de características (`RoomType`, `Room`). */
class RoomFeatureIdConverter : TypedIdConverter<RoomFeatureId>(::RoomFeatureId)

/** Troca o conteúdo de [target] por [values]; devolve se mudou. */
internal fun <T> replaceAll(
    target: MutableSet<T>,
    values: Set<T>,
): Boolean {
    if (target == values) return false
    target.retainAll(values)
    target.addAll(values)
    return true
}

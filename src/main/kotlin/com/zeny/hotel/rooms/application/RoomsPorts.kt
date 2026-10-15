package com.zeny.hotel.rooms.application

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.AreaKind
import com.zeny.hotel.rooms.Occupancy
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.domain.Area
import com.zeny.hotel.rooms.domain.AreaDetails
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomDetails
import com.zeny.hotel.rooms.domain.RoomFeature
import com.zeny.hotel.rooms.domain.RoomType
import com.zeny.hotel.rooms.domain.RoomTypeDetails
import java.time.Instant
import java.util.UUID

/** Dados de um tipo de UH como chegam da API (RF-UH-01). */
data class RoomTypeCommand(
    val code: String,
    val name: String,
    val description: String?,
    val occupancy: Occupancy,
    val bedSetup: String?,
    val displayOrder: Int,
    val featureIds: Set<RoomFeatureId>,
) {
    internal val details: RoomTypeDetails
        get() = RoomTypeDetails(code, name, description, occupancy, bedSetup, displayOrder, featureIds)
}

/** Dados de uma UH como chegam da API (RF-UH-02). */
data class RoomCommand(
    val number: String,
    val floor: String?,
    val roomTypeId: RoomTypeId,
    val featureIds: Set<RoomFeatureId>,
    val notes: String?,
) {
    internal val details: RoomDetails get() = RoomDetails(number, floor, roomTypeId, featureIds, notes)
}

/** Dados de uma área comum como chegam da API (RF-UH-03). */
data class AreaCommand(
    val name: String,
    val kind: AreaKind,
    val description: String?,
) {
    internal val details: AreaDetails get() = AreaDetails(name, kind, description)
}

/**
 * Os agregados de `rooms` para escrita (JPA), na transação corrente. As gravações que podem violar
 * uma unicidade do banco devolvem `false` em vez de falhar, para o caso de uso responder `409`.
 */
@Suppress("TooManyFunctions") // um método por acesso de cada agregado
interface RoomsStore {
    fun feature(id: RoomFeatureId): RoomFeature?

    /** A característica pré-carregada com a chave [key]. */
    fun feature(key: RoomFeatureKey): RoomFeature?

    fun features(ids: Collection<RoomFeatureId>): List<RoomFeature>

    /** `false` se o código já existe. */
    fun tryAdd(feature: RoomFeature): Boolean

    fun roomType(id: RoomTypeId): RoomType?

    /**
     * O tipo travado para escrita (`SELECT … FOR UPDATE`) até o fim da transação: desativar o tipo e
     * pôr nele uma UH ativa passam por essa trava, então nunca sobra UH ativa num tipo desativado.
     */
    fun roomTypeForUpdate(id: RoomTypeId): RoomType?

    /** `false` se o código já existe. */
    fun tryAdd(roomType: RoomType): Boolean

    /** Se o tipo tem alguma UH ativa (lida depois da trava do tipo). */
    fun hasActiveRooms(roomTypeId: RoomTypeId): Boolean

    fun room(id: RoomId): Room?

    /**
     * A UH travada para escrita (`SELECT … FOR UPDATE`) até o fim da transação, relida do banco: as
     * mudanças de status passam por essa trava, então nunca se sobrescrevem.
     */
    fun roomForUpdate(id: RoomId): Room?

    /** `false` se o número já existe (sem diferenciar maiúsculas). */
    fun tryAdd(room: Room): Boolean

    fun area(id: AreaId): Area?

    /** `false` se o nome já existe (sem diferenciar maiúsculas). */
    fun tryAdd(area: Area): Boolean

    /** Grava já as mudanças pendentes (conferindo a versão: lock otimista). */
    fun flush()

    /** Como [flush], mas `false` se violar a unicidade [constraint] (nome de restrição ou índice único). */
    fun tryFlush(constraint: String): Boolean
}

/** Nomes das unicidades do banco que viram `409`. */
object RoomsConstraints {
    const val FEATURE_CODE = "room_feature_code_uk"
    const val ROOM_TYPE_CODE = "room_type_code_uk"
    const val ROOM_NUMBER = "room_number_uk"
    const val AREA_NAME = "area_name_uk"
}

data class RoomFeatureView(
    val id: RoomFeatureId,
    val key: RoomFeatureKey?,
    val code: String,
    val name: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class RoomTypeView(
    val id: RoomTypeId,
    val code: String,
    val name: String,
    val description: String?,
    val occupancy: Occupancy,
    val bedSetup: String?,
    val displayOrder: Int,
    val featureIds: Set<RoomFeatureId>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class RoomView(
    val id: RoomId,
    val number: String,
    val floor: String?,
    val roomTypeId: RoomTypeId,
    val featureIds: Set<RoomFeatureId>,
    val notes: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class AreaView(
    val id: AreaId,
    val name: String,
    val kind: AreaKind,
    val description: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

/** Leituras de `rooms` para a API e para os outros módulos (`JdbcClient`, ARCHITECTURE §11). */
interface RoomsDirectory {
    /** Uma página de características em ordem de código e id, depois de [after]. */
    fun features(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomFeatureView>

    /** Uma página de tipos de UH em ordem de exibição e id, depois de [after]. */
    fun roomTypes(
        active: Boolean?,
        after: Pair<Long, UUID>?,
        limit: Int,
    ): List<RoomTypeView>

    /** Uma página de UHs em ordem de número (sem diferenciar maiúsculas) e id, depois de [after]. */
    fun rooms(
        roomTypeId: RoomTypeId?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<RoomView>

    /** Uma página de áreas em ordem de nome (sem diferenciar maiúsculas) e id, depois de [after]. */
    fun areas(
        kind: AreaKind?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<AreaView>
}

internal fun RoomFeature.view() =
    RoomFeatureView(id, key, code, name, active, createdAt, updatedAt, checkNotNull(version))

internal fun RoomType.view() =
    RoomTypeView(
        id,
        code,
        name,
        description,
        occupancy,
        bedSetup,
        displayOrder,
        featureIds,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

internal fun Room.view() =
    RoomView(
        id,
        number,
        floor,
        roomTypeId,
        featureIds,
        notes,
        active,
        createdAt,
        updatedAt,
        checkNotNull(version),
    )

internal fun Area.view() = AreaView(id, name, kind, description, active, createdAt, updatedAt, checkNotNull(version))

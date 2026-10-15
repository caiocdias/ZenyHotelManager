package com.zeny.hotel.rooms

import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

/**
 * API pública do módulo `rooms` para os outros módulos (ARCHITECTURE §3.1, RF-UH-01 a 03): leituras de
 * UH, tipo de UH e área comum. Não checa permissão: quem chama é um caso de uso que já se autorizou.
 * O status da UH (E02) e o OOO/OOS (E03) entram aqui depois.
 */
interface RoomsApi {
    /** A UH, ativa ou não, ou `null` se não existe. */
    fun room(id: RoomId): RoomSummary?

    /** O tipo de UH, ativo ou não, ou `null` se não existe. */
    fun roomType(id: RoomTypeId): RoomTypeSummary?

    /** A área comum, ativa ou não, ou `null` se não existe. */
    fun area(id: AreaId): AreaSummary?

    /** As UHs ativas, de todos os tipos ou só do tipo [roomTypeId], em ordem de número. UH desativada não entra. */
    fun activeRooms(roomTypeId: RoomTypeId? = null): List<RoomSummary>

    /** Os tipos de UH ativos, na ordem de exibição. */
    fun activeRoomTypes(): List<RoomTypeSummary>

    /** A característica, ativa ou não, ou `null` se não existe. */
    fun feature(id: RoomFeatureId): RoomFeatureSummary?

    /**
     * A característica pré-carregada com a chave [key] (vista, acessível, conjugada), ativa ou não. Ela
     * sempre existe: vem da migração e nunca é apagada.
     */
    fun feature(key: RoomFeatureKey): RoomFeatureSummary
}

/**
 * As características pré-carregadas pela migração (decisão do usuário, M2 E01): a chave as identifica
 * para os outros módulos mesmo que o gestor troque o código ou o nome. As criadas depois não têm chave.
 */
enum class RoomFeatureKey {
    /** Vista (código inicial `VIEW`). */
    VIEW,

    /** Acessível (código inicial `ACCESSIBLE`). */
    ACCESSIBLE,

    /** Conjugada (código inicial `CONNECTING`): uma característica da UH, não um vínculo entre duas UHs. */
    CONNECTING,
}

/** Id de um tipo de UH. */
@JvmInline
value class RoomTypeId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomTypeId = RoomTypeId(UuidV7.generate())
    }
}

/** Id de uma característica de UH ou de tipo de UH (vista, acessível, conjugada…). */
@JvmInline
value class RoomFeatureId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomFeatureId = RoomFeatureId(UuidV7.generate())
    }
}

/** Id de uma UH. */
@JvmInline
value class RoomId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoomId = RoomId(UuidV7.generate())
    }
}

/** Id de uma área comum. */
@JvmInline
value class AreaId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): AreaId = AreaId(UuidV7.generate())
    }
}

/** Tipo de área comum (RF-UH-03). */
enum class AreaKind {
    LOBBY,
    POOL,
    KITCHEN,

    /** Salão (de eventos, de café da manhã…). */
    HALL,
    CORRIDOR,
    OTHER,
}

/** Ocupação de um tipo de UH (RF-UH-01): a padrão e a máxima, de adultos e de crianças. */
data class Occupancy(
    val standardAdults: Int,
    val standardChildren: Int,
    val maxAdults: Int,
    val maxChildren: Int,
)

/** Uma característica como os outros módulos a veem; [key] só nas pré-carregadas. */
data class RoomFeatureSummary(
    val id: RoomFeatureId,
    val key: RoomFeatureKey?,
    val code: String,
    val name: String,
    val active: Boolean,
)

/** Um tipo de UH como os outros módulos o veem. */
data class RoomTypeSummary(
    val id: RoomTypeId,
    val code: String,
    val name: String,
    val occupancy: Occupancy,
    val displayOrder: Int,
    val featureIds: Set<RoomFeatureId>,
    val active: Boolean,
)

/** Uma UH como os outros módulos a veem. [floor] é o andar ou a ala, se informado. */
data class RoomSummary(
    val id: RoomId,
    val number: String,
    val floor: String?,
    val roomTypeId: RoomTypeId,
    val featureIds: Set<RoomFeatureId>,
    val active: Boolean,
)

/** Uma área comum como os outros módulos a veem. */
data class AreaSummary(
    val id: AreaId,
    val name: String,
    val kind: AreaKind,
    val active: Boolean,
)

package com.zeny.hotel.rooms.infrastructure

import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomFeatureId
import com.zeny.hotel.rooms.RoomFeatureKey
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.rooms.application.RoomsConstraints
import com.zeny.hotel.rooms.application.RoomsStore
import com.zeny.hotel.rooms.domain.Area
import com.zeny.hotel.rooms.domain.Room
import com.zeny.hotel.rooms.domain.RoomFeature
import com.zeny.hotel.rooms.domain.RoomType
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.util.UUID

interface RoomFeatureJpaRepository : JpaRepository<RoomFeature, UUID> {
    fun findByKey(key: RoomFeatureKey): RoomFeature?
}

interface RoomTypeJpaRepository : JpaRepository<RoomType, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RoomType t WHERE t.id = :id")
    fun findForUpdate(id: UUID): RoomType?
}

interface RoomJpaRepository : JpaRepository<Room, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    fun findForUpdate(id: UUID): Room?

    @Query(
        "SELECT CASE WHEN count(r) > 0 THEN true ELSE false END FROM Room r " +
            "WHERE r.roomTypeId = :roomTypeId AND r.active = true",
    )
    fun existsActiveOfType(roomTypeId: UUID): Boolean
}

interface AreaJpaRepository : JpaRepository<Area, UUID>

/** Os agregados de `rooms` por JPA, na transação corrente. */
@Component
@Suppress("TooManyFunctions") // um repositório e os acessos de cada agregado
class JpaRoomsStore(
    private val entityManager: EntityManager,
    private val features: RoomFeatureJpaRepository,
    private val roomTypes: RoomTypeJpaRepository,
    private val rooms: RoomJpaRepository,
    private val areas: AreaJpaRepository,
) : RoomsStore {
    override fun feature(id: RoomFeatureId): RoomFeature? = features.findById(id.value).orElse(null)

    override fun feature(key: RoomFeatureKey): RoomFeature? = features.findByKey(key)

    override fun features(ids: Collection<RoomFeatureId>): List<RoomFeature> =
        if (ids.isEmpty()) emptyList() else features.findAllById(ids.map { it.value })

    override fun tryAdd(feature: RoomFeature): Boolean =
        unlessViolated(RoomsConstraints.FEATURE_CODE) { features.saveAndFlush(feature) }

    override fun roomType(id: RoomTypeId): RoomType? = roomTypes.findById(id.value).orElse(null)

    override fun roomTypeForUpdate(id: RoomTypeId): RoomType? = roomTypes.findForUpdate(id.value)

    override fun tryAdd(roomType: RoomType): Boolean =
        unlessViolated(RoomsConstraints.ROOM_TYPE_CODE) { roomTypes.saveAndFlush(roomType) }

    override fun hasActiveRooms(roomTypeId: RoomTypeId): Boolean = rooms.existsActiveOfType(roomTypeId.value)

    override fun room(id: RoomId): Room? = rooms.findById(id.value).orElse(null)

    override fun roomForUpdate(id: RoomId): Room? =
        rooms.findForUpdate(id.value)?.also {
            // Já carregada nesta transação (por uma leitura anterior), a entidade traria o estado lido
            // antes da trava: relê, agora com a linha travada.
            entityManager.refresh(it)
        }

    override fun tryAdd(room: Room): Boolean = unlessViolated(RoomsConstraints.ROOM_NUMBER) { rooms.saveAndFlush(room) }

    override fun area(id: AreaId): Area? = areas.findById(id.value).orElse(null)

    override fun tryAdd(area: Area): Boolean = unlessViolated(RoomsConstraints.AREA_NAME) { areas.saveAndFlush(area) }

    override fun flush() = rooms.flush()

    override fun tryFlush(constraint: String): Boolean = unlessViolated(constraint) { rooms.flush() }
}

/**
 * Violação da restrição [constraint] — unicidade (`23505`) ou exclusão (`23P01`), reconhecida pelo campo
 * `constraint` do erro do servidor na cadeia de causas, nunca pelo texto — vira `false`; qualquer outra
 * segue.
 */
internal fun unlessViolated(
    constraint: String,
    write: () -> Unit,
): Boolean =
    try {
        write()
        true
    } catch (e: DataIntegrityViolationException) {
        val violated =
            generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<PSQLException>()
                .any { it.sqlState in CONSTRAINT_VIOLATIONS && it.serverErrorMessage?.constraint == constraint }
        if (!violated) throw e
        false
    }

/** `unique_violation` e `exclusion_violation`. */
private val CONSTRAINT_VIOLATIONS = setOf("23505", "23P01")

package com.zeny.hotel.rooms.infrastructure

import com.zeny.hotel.rooms.HousekeepingStatus
import com.zeny.hotel.rooms.OutOfOrderId
import com.zeny.hotel.rooms.OutOfOrderKind
import com.zeny.hotel.rooms.OutOfOrderPeriod
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.application.OutOfOrderOverlay
import com.zeny.hotel.rooms.application.OutOfOrderRecords
import com.zeny.hotel.rooms.application.OutOfOrderStore
import com.zeny.hotel.rooms.domain.OutOfOrder
import com.zeny.hotel.shared.UserId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

interface OutOfOrderJpaRepository : JpaRepository<OutOfOrder, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutOfOrder o WHERE o.id = :id")
    fun findForUpdate(id: UUID): OutOfOrder?

    @Query(
        "SELECT o FROM OutOfOrder o WHERE o.roomId = :roomId AND o.startsOn <= :to AND o.endsOn >= :from " +
            "ORDER BY o.startsOn",
    )
    fun overlappingPlanned(
        roomId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<OutOfOrder>
}

/** Os períodos OOO/OOS por JPA, na transação corrente. */
@Component
class JpaOutOfOrderStore(
    private val entityManager: EntityManager,
    private val periods: OutOfOrderJpaRepository,
) : OutOfOrderStore {
    override fun find(id: OutOfOrderId): OutOfOrder? = periods.findById(id.value).orElse(null)

    override fun findForUpdate(id: OutOfOrderId): OutOfOrder? =
        periods.findForUpdate(id.value)?.also {
            // Já carregado nesta transação, traria o estado lido antes da trava: relê.
            entityManager.refresh(it)
        }

    override fun ofRoom(
        roomId: RoomId,
        from: LocalDate,
        to: LocalDate,
    ): List<OutOfOrder> = periods.overlappingPlanned(roomId.value, from, to)

    override fun tryAdd(period: OutOfOrder): Boolean = unlessViolated(OVERLAP) { periods.saveAndFlush(period) }

    override fun tryFlush(): Boolean = unlessViolated(OVERLAP) { periods.flush() }

    private companion object {
        const val OVERLAP = "out_of_order_overlap_ex"
    }
}

/**
 * As leituras dos períodos OOO/OOS pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizadas. Os dias
 * bloqueados vêm da coluna gerada `blocked_period` (`daterange` meio-aberto), a mesma que o banco usa
 * para recusar a sobreposição.
 */
@Component
class JdbcOutOfOrderRecords(
    private val jdbc: JdbcClient,
) : OutOfOrderRecords {
    override fun inEffect(
        roomId: RoomId,
        date: LocalDate,
    ): OutOfOrderOverlay? =
        jdbc
            .sql(
                """
                SELECT id, kind, starts_on, ends_on FROM rooms.out_of_order
                WHERE room_id = :roomId AND blocked_period @> CAST(:date AS date)
                """.trimIndent(),
            ).param("roomId", roomId.value)
            .param("date", date)
            .query { rs, _ -> rs.overlay() }
            .optional()
            .orElse(null)

    override fun periods(
        from: LocalDate,
        to: LocalDate?,
        roomId: RoomId?,
        kind: OutOfOrderKind?,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<OutOfOrderPeriod> =
        jdbc
            .sql(
                """
                SELECT * FROM rooms.out_of_order
                WHERE blocked_period && daterange(CAST(:from AS date), CAST(:to AS date), '[]')
                  AND (CAST(:roomId AS uuid) IS NULL OR room_id = CAST(:roomId AS uuid))
                  AND (CAST(:kind AS text) IS NULL OR kind = CAST(:kind AS text))
                  AND (CAST(:afterStart AS date) IS NULL
                       OR (starts_on, id) > (CAST(:afterStart AS date), CAST(:afterId AS uuid)))
                ORDER BY starts_on, id LIMIT :limit
                """.trimIndent(),
            ).param("from", from)
            .param("to", to)
            .param("roomId", roomId?.value)
            .param("kind", kind?.name)
            .param("afterStart", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.period() }
            .list()
}

/** A sobreposição em vigor numa linha com `id`, `kind`, `starts_on` e `ends_on` (com prefixo, num JOIN). */
internal fun ResultSet.overlay(prefix: String = ""): OutOfOrderOverlay? =
    getObject("${prefix}id", UUID::class.java)?.let {
        OutOfOrderOverlay(
            OutOfOrderId(it),
            OutOfOrderKind.valueOf(getString("${prefix}kind")),
            getObject("${prefix}starts_on", LocalDate::class.java),
            getObject("${prefix}ends_on", LocalDate::class.java),
        )
    }

private fun ResultSet.period(): OutOfOrderPeriod {
    val startsOn = getObject("starts_on", LocalDate::class.java)
    val endsOn = getObject("ends_on", LocalDate::class.java)
    val releasedOn = getObject("released_on", LocalDate::class.java)
    return OutOfOrderPeriod(
        OutOfOrderId(getObject("id", UUID::class.java)),
        RoomId(getObject("room_id", UUID::class.java)),
        OutOfOrderKind.valueOf(getString("kind")),
        startsOn,
        endsOn,
        getString("reason"),
        getString("responsible"),
        HousekeepingStatus.valueOf(getString("return_status")),
        requiredInstant("opened_at"),
        getObject("opened_by", UUID::class.java)?.let(::UserId),
        requiredInstant("updated_at"),
        releasedOn,
        instant("released_at"),
        getObject("released_by", UUID::class.java)?.let(::UserId),
        OutOfOrder.blockedUntil(startsOn, endsOn, releasedOn),
        getLong("version"),
    )
}

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun ResultSet.requiredInstant(column: String): Instant = checkNotNull(instant(column)) { "$column nulo" }

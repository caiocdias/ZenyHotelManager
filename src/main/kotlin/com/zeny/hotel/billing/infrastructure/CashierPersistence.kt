package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.CashMovementId
import com.zeny.hotel.billing.CashMovementType
import com.zeny.hotel.billing.CashPointId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftStatus
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.billing.application.CashierDirectory
import com.zeny.hotel.billing.application.CashierLedger
import com.zeny.hotel.billing.application.CashierShiftFilter
import com.zeny.hotel.billing.application.CashierStore
import com.zeny.hotel.billing.domain.CashMovement
import com.zeny.hotel.billing.domain.CashPoint
import com.zeny.hotel.billing.domain.CashierShift
import com.zeny.hotel.billing.domain.ShiftCount
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.UserId
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface CashPointJpaRepository : JpaRepository<CashPoint, UUID>

interface CashierShiftJpaRepository : JpaRepository<CashierShift, UUID>

/** Restrições do caixa que a aplicação reconhece pelo nome. */
internal object CashierConstraints {
    const val CASH_POINT_NAME = "cash_point_name_uk"
    const val OPEN_SHIFT_PER_OPERATOR = "cashier_shift_open_operator_uk"
}

/** Os pontos e turnos de caixa por JPA, na transação corrente (M3 E07). */
@Component
@Suppress("TooManyFunctions") // os dois agregados do caixa (CashierStore)
class JpaCashierStore(
    private val cashPoints: CashPointJpaRepository,
    private val shifts: CashierShiftJpaRepository,
    private val entityManager: EntityManager,
    private val jdbc: JdbcClient,
) : CashierStore {
    override fun cashPoint(id: CashPointId): CashPoint? = cashPoints.findById(id.value).orElse(null)

    override fun cashPoints(ids: Collection<UUID>): List<CashPoint> =
        if (ids.isEmpty()) emptyList() else cashPoints.findAllById(ids)

    override fun tryAdd(cashPoint: CashPoint): Boolean =
        unlessViolated(CashierConstraints.CASH_POINT_NAME) { cashPoints.saveAndFlush(cashPoint) }

    override fun tryFlushCashPoint(): Boolean =
        unlessViolated(CashierConstraints.CASH_POINT_NAME) { cashPoints.flush() }

    override fun shift(id: CashierShiftId): CashierShift? = shifts.findById(id.value).orElse(null)

    override fun shifts(ids: List<UUID>): List<CashierShift> {
        if (ids.isEmpty()) return emptyList()
        val found = shifts.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(found::get)
    }

    override fun shiftForUpdate(id: CashierShiftId): CashierShift? =
        entityManager.find(CashierShift::class.java, id.value)?.also {
            // Relê com a linha travada: já carregada nesta transação, a entidade traria o estado de antes da trava.
            entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE)
        }

    override fun openShiftOf(operator: UserId): CashierShift? = openShiftId(operator)?.let { shift(CashierShiftId(it)) }

    override fun openShiftOfForUpdate(operator: UserId): CashierShift? =
        openShiftId(operator)
            ?.let { shiftForUpdate(CashierShiftId(it)) }
            // Fechado enquanto se esperava a trava: o operador não tem mais turno aberto.
            ?.takeIf { it.status == CashierShiftStatus.OPEN }

    override fun tryAdd(shift: CashierShift): Boolean =
        unlessViolated(CashierConstraints.OPEN_SHIFT_PER_OPERATOR) { shifts.saveAndFlush(shift) }

    override fun markChanged(shift: CashierShift) = entityManager.lock(shift, LockModeType.PESSIMISTIC_FORCE_INCREMENT)

    override fun flush() = shifts.flush()

    private fun openShiftId(operator: UserId): UUID? =
        jdbc
            .sql("SELECT id FROM billing.cashier_shift WHERE operator_id = :operator AND status = 'OPEN'")
            .param("operator", operator.value)
            .query(UUID::class.java)
            .optional()
            .orElse(null)
}

/**
 * Os movimentos de caixa e as contagens do fechamento por SQL (RN-03): só `INSERT` e `SELECT`, que é tudo o que o
 * papel da aplicação pode nessas tabelas (bean `BillingTablesConfiguration.billingAppendOnlyTables`); um trigger
 * recusa `UPDATE`/`DELETE` até do dono.
 */
@Component
class JdbcCashierLedger(
    private val jdbc: JdbcClient,
) : CashierLedger {
    override fun add(movement: CashMovement) {
        jdbc
            .sql(
                """
                INSERT INTO billing.cash_movement (id, shift_id, movement_type, amount, reason, business_date,
                    recorded_at, recorded_by)
                VALUES (:id, :shiftId, :type, :amount, :reason, :businessDate, :recordedAt, :recordedBy)
                """.trimIndent(),
            ).param("id", movement.id.value)
            .param("shiftId", movement.shiftId.value)
            .param("type", movement.type.name)
            .param("amount", movement.amount.amount)
            .param("reason", movement.reason)
            .param("businessDate", movement.businessDate)
            .param("recordedAt", movement.recordedAt.atOffset(ZoneOffset.UTC))
            .param("recordedBy", movement.recordedBy.value)
            .update()
    }

    override fun movementsOf(shiftId: CashierShiftId): List<CashMovement> =
        jdbc
            .sql("SELECT * FROM billing.cash_movement WHERE shift_id = :shiftId ORDER BY recorded_at, id")
            .param("shiftId", shiftId.value)
            .query { rs, _ ->
                CashMovement(
                    CashMovementId(rs.getObject("id", UUID::class.java)),
                    CashierShiftId(rs.getObject("shift_id", UUID::class.java)),
                    CashMovementType.valueOf(rs.getString("movement_type")),
                    Money.of(rs.getBigDecimal("amount")),
                    rs.getString("reason"),
                    rs.getObject("business_date", LocalDate::class.java),
                    rs.getObject("recorded_at", OffsetDateTime::class.java).toInstant(),
                    UserId(rs.getObject("recorded_by", UUID::class.java)),
                )
            }.list()

    override fun addCounts(
        shiftId: CashierShiftId,
        counts: List<ShiftCount>,
    ) {
        counts.forEach { count ->
            jdbc
                .sql(
                    """
                    INSERT INTO billing.cashier_shift_count (shift_id, method, declared, expected, difference)
                    VALUES (:shiftId, :method, :declared, :expected, :difference)
                    """.trimIndent(),
                ).param("shiftId", shiftId.value)
                .param("method", count.method.name)
                .param("declared", count.declared.amount)
                .param("expected", count.expected.amount)
                .param("difference", count.difference.amount)
                .update()
        }
    }

    override fun countsOf(shiftId: CashierShiftId): List<ShiftCount> =
        jdbc
            .sql("SELECT method, declared, expected FROM billing.cashier_shift_count WHERE shift_id = :shiftId")
            .param("shiftId", shiftId.value)
            .query { rs, _ ->
                ShiftCount(
                    PaymentMethod.valueOf(rs.getString("method")),
                    Money.of(rs.getBigDecimal("declared")),
                    Money.of(rs.getBigDecimal("expected")),
                )
            }.list()
            .sortedBy { it.method.ordinal }
}

/** As listagens do caixa por SQL: só as chaves do cursor e os ids, na ordem da página. */
@Component
class JdbcCashierDirectory(
    private val jdbc: JdbcClient,
) : CashierDirectory {
    override fun cashPoints(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> =
        jdbc
            .sql(
                """
                SELECT lower(name) AS sort_name, id FROM billing.cash_point
                WHERE (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                  AND (CAST(:afterName AS varchar) IS NULL
                       OR (lower(name), id) > (CAST(:afterName AS varchar), CAST(:afterId AS uuid)))
                ORDER BY lower(name), id
                LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterName", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.getString("sort_name") to rs.getObject("id", UUID::class.java) }
            .list()

    override fun shifts(
        filter: CashierShiftFilter,
        owner: UUID?,
        afterId: UUID?,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM billing.cashier_shift
                WHERE (CAST(:owner AS uuid) IS NULL OR operator_id = CAST(:owner AS uuid))
                  AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                  AND (CAST(:cashPoint AS uuid) IS NULL OR cash_point_id = CAST(:cashPoint AS uuid))
                  AND (CAST(:operator AS uuid) IS NULL OR operator_id = CAST(:operator AS uuid))
                  AND (CAST(:afterId AS uuid) IS NULL OR id < CAST(:afterId AS uuid))
                ORDER BY id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("owner", owner)
            .param("status", filter.status?.name)
            .param("cashPoint", filter.cashPointId?.value)
            .param("operator", filter.operatorId?.value)
            .param("afterId", afterId)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()
}

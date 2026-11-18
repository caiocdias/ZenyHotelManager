package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.housekeeping.application.ScheduledCleaningStore
import com.zeny.hotel.housekeeping.domain.ScheduledCleaning
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

interface ScheduledCleaningJpaRepository : JpaRepository<ScheduledCleaning, UUID>

/**
 * As faxinas programadas por JPA, na transação corrente (escrita com `saveAndFlush`, para a unicidade da faxina ativa
 * por local e tipo virar `409` no caso de uso); as páginas por `JdbcClient`, sempre parametrizadas e só no schema
 * `housekeeping`.
 */
@Component
class JpaScheduledCleaningStore(
    private val entityManager: EntityManager,
    private val jdbc: JdbcClient,
    private val repository: ScheduledCleaningJpaRepository,
) : ScheduledCleaningStore {
    override fun scheduledCleaning(id: ScheduledCleaningId): ScheduledCleaning? =
        repository.findById(id.value).orElse(null)

    /** A linha travada (`FOR NO KEY UPDATE`, o `PESSIMISTIC_WRITE` do Hibernate 7) e relida. */
    override fun forUpdate(id: ScheduledCleaningId): ScheduledCleaning? =
        entityManager
            .find(ScheduledCleaning::class.java, id.value)
            ?.also { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }

    override fun scheduledCleanings(ids: List<UUID>): List<ScheduledCleaning> {
        val byId = repository.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(byId::get)
    }

    override fun dueOn(date: LocalDate): List<ScheduledCleaning> =
        scheduledCleanings(
            jdbc
                .sql(
                    "SELECT id FROM housekeeping.scheduled_cleaning WHERE active AND next_due_on <= :date " +
                        "ORDER BY next_due_on, id",
                ).param("date", date)
                .query { rs, _ -> rs.getObject("id", UUID::class.java) }
                .list(),
        )

    override fun page(
        active: Boolean?,
        after: Pair<LocalDate, UUID>?,
        limit: Int,
    ): List<Pair<LocalDate, UUID>> =
        jdbc
            .sql(
                """
                SELECT s.next_due_on, s.id FROM housekeeping.scheduled_cleaning s
                WHERE (CAST(:active AS boolean) IS NULL OR s.active = CAST(:active AS boolean))
                  AND (CAST(:afterDate AS date) IS NULL
                       OR (s.next_due_on, s.id) > (CAST(:afterDate AS date), CAST(:afterId AS uuid)))
                ORDER BY s.next_due_on, s.id LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterDate", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                rs.getObject("next_due_on", LocalDate::class.java) to rs.getObject("id", UUID::class.java)
            }.list()

    override fun tryAdd(scheduledCleaning: ScheduledCleaning): Boolean =
        unlessAlreadyActive { repository.saveAndFlush(scheduledCleaning) }

    override fun tryFlush(): Boolean = unlessAlreadyActive { repository.flush() }

    override fun flush() = repository.flush()

    /**
     * Violação do índice único parcial da faxina ativa por (local, tipo) (`23505` em `scheduled_cleaning_active_uk`,
     * reconhecida pelo campo `constraint` do erro do servidor na cadeia de causas, nunca pelo texto) vira `false`;
     * qualquer outra segue.
     */
    private fun unlessAlreadyActive(write: () -> Unit): Boolean =
        try {
            write()
            true
        } catch (e: DataIntegrityViolationException) {
            val violated =
                generateSequence<Throwable>(e) { it.cause }
                    .filterIsInstance<PSQLException>()
                    .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == ACTIVE_INDEX }
            if (!violated) throw e
            false
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val ACTIVE_INDEX = "scheduled_cleaning_active_uk"
    }
}

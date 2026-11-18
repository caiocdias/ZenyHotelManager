package com.zeny.hotel.housekeeping.infrastructure

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.application.CleaningTypeStore
import com.zeny.hotel.housekeeping.domain.CleaningType
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

interface CleaningTypeJpaRepository : JpaRepository<CleaningType, UUID>

/**
 * Os tipos de limpeza por JPA, na transação corrente (escrita com `saveAndFlush`, para a unicidade do código virar
 * `409` no caso de uso); as páginas por `JdbcClient`, sempre parametrizadas e só no schema `housekeeping`.
 */
@Component
class JpaCleaningTypeStore(
    private val entityManager: EntityManager,
    private val jdbc: JdbcClient,
    private val repository: CleaningTypeJpaRepository,
) : CleaningTypeStore {
    override fun cleaningType(id: CleaningTypeId): CleaningType? = repository.findById(id.value).orElse(null)

    /**
     * A linha travada e relida: já carregada nesta transação, a entidade traria o estado lido antes da trava. O
     * `PESSIMISTIC_WRITE` do Hibernate 7 no PostgreSQL é `FOR NO KEY UPDATE`.
     */
    override fun forUpdate(id: CleaningTypeId): CleaningType? =
        entityManager
            .find(CleaningType::class.java, id.value)
            ?.also { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }

    override fun cleaningTypes(ids: List<UUID>): List<CleaningType> {
        val byId = repository.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(byId::get)
    }

    override fun page(
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> =
        jdbc
            .sql(
                """
                SELECT t.code, t.id FROM housekeeping.cleaning_type t
                WHERE (CAST(:active AS boolean) IS NULL OR t.active = CAST(:active AS boolean))
                  AND (CAST(:afterKey AS text) IS NULL
                       OR (t.code, t.id) > (CAST(:afterKey AS text), CAST(:afterId AS uuid)))
                ORDER BY t.code, t.id LIMIT :limit
                """.trimIndent(),
            ).param("active", active)
            .param("afterKey", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.getString("code") to rs.getObject("id", UUID::class.java) }
            .list()

    override fun tryAdd(cleaningType: CleaningType): Boolean = unlessCodeInUse { repository.saveAndFlush(cleaningType) }

    override fun tryFlush(): Boolean = unlessCodeInUse { repository.flush() }

    override fun flush() = repository.flush()

    /** Ainda não há faxinas programadas (Task 3 da E01): nenhum tipo está em uso por elas. */
    override fun hasActiveSchedules(id: CleaningTypeId): Boolean = false

    /**
     * Violação da unicidade do código (`23505` na restrição `cleaning_type_code_uk`, reconhecida pelo campo
     * `constraint` do erro do servidor na cadeia de causas, nunca pelo texto) vira `false`; qualquer outra segue.
     */
    private fun unlessCodeInUse(write: () -> Unit): Boolean =
        try {
            write()
            true
        } catch (e: DataIntegrityViolationException) {
            val violated =
                generateSequence<Throwable>(e) { it.cause }
                    .filterIsInstance<PSQLException>()
                    .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == CODE_CONSTRAINT }
            if (!violated) throw e
            false
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val CODE_CONSTRAINT = "cleaning_type_code_uk"
    }
}

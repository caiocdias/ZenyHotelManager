package com.zeny.hotel.billing.infrastructure

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.application.BillingDirectory
import com.zeny.hotel.billing.application.BillingStore
import com.zeny.hotel.billing.application.FolioFilter
import com.zeny.hotel.billing.application.TransactionCodeFilter
import com.zeny.hotel.billing.domain.Folio
import com.zeny.hotel.billing.domain.TransactionCode
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

interface TransactionCodeJpaRepository : JpaRepository<TransactionCode, UUID>

interface FolioJpaRepository : JpaRepository<Folio, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Folio f WHERE f.id = :id")
    fun findForUpdate(id: UUID): Folio?
}

/** Restrições do schema `billing` que a aplicação reconhece pelo nome. */
internal object BillingConstraints {
    const val TRANSACTION_CODE_CODE = "transaction_code_code_uk"
    const val POSTING_REVERSAL = "posting_reversal_uk"
}

/** Os códigos e as contas por JPA, na transação corrente. */
@Component
class JpaBillingStore(
    private val codes: TransactionCodeJpaRepository,
    private val folios: FolioJpaRepository,
    private val entityManager: EntityManager,
    private val jdbc: JdbcClient,
) : BillingStore {
    override fun transactionCode(id: TransactionCodeId): TransactionCode? = codes.findById(id.value).orElse(null)

    override fun transactionCode(key: TransactionCodeKey): TransactionCode? =
        jdbc
            .sql("SELECT id FROM billing.transaction_code WHERE system_key = :key")
            .param("key", key.name)
            .query(UUID::class.java)
            .optional()
            .flatMap { codes.findById(it) }
            .orElse(null)

    override fun transactionCodes(ids: List<UUID>): List<TransactionCode> {
        if (ids.isEmpty()) return emptyList()
        val found = codes.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(found::get)
    }

    override fun tryAdd(code: TransactionCode): Boolean =
        unlessViolated(BillingConstraints.TRANSACTION_CODE_CODE) { codes.saveAndFlush(code) }

    override fun tryFlushCode(): Boolean = unlessViolated(BillingConstraints.TRANSACTION_CODE_CODE) { codes.flush() }

    override fun folio(id: FolioId): Folio? = folios.findById(id.value).orElse(null)

    override fun folioForUpdate(id: FolioId): Folio? =
        folios.findForUpdate(id.value)?.also {
            // Já carregada nesta transação (por uma leitura anterior), a entidade traria o estado lido antes da
            // trava: relê, agora com a linha travada.
            entityManager.refresh(it)
        }

    override fun folios(ids: List<UUID>): List<Folio> {
        if (ids.isEmpty()) return emptyList()
        val found = folios.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(found::get)
    }

    override fun add(folio: Folio) {
        folios.saveAndFlush(folio)
    }

    override fun markChanged(folio: Folio) = entityManager.lock(folio, LockModeType.PESSIMISTIC_FORCE_INCREMENT)

    override fun flush() = folios.flush()
}

/** As listagens de códigos e contas por SQL: só as chaves do cursor e os ids, na ordem da página. */
@Component
class JdbcBillingDirectory(
    private val jdbc: JdbcClient,
) : BillingDirectory {
    override fun transactionCodes(
        filter: TransactionCodeFilter,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<Pair<String, UUID>> =
        jdbc
            .sql(
                """
                SELECT code, id FROM billing.transaction_code
                WHERE (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
                  AND (CAST(:revenueGroup AS varchar) IS NULL OR revenue_group = CAST(:revenueGroup AS varchar))
                  AND (CAST(:afterCode AS varchar) IS NULL OR (code, id) > (CAST(:afterCode AS varchar), CAST(:afterId AS uuid)))
                ORDER BY code, id
                LIMIT :limit
                """.trimIndent(),
            ).param("active", filter.active)
            .param("revenueGroup", filter.revenueGroup?.name)
            .param("afterCode", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ -> rs.getString("code") to rs.getObject("id", UUID::class.java) }
            .list()

    override fun folios(
        filter: FolioFilter,
        afterId: UUID?,
        limit: Int,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM billing.folio
                WHERE (CAST(:ownerType AS varchar) IS NULL OR owner_type = CAST(:ownerType AS varchar))
                  AND (CAST(:ownerId AS uuid) IS NULL OR owner_id = CAST(:ownerId AS uuid))
                  AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                  AND (CAST(:payer AS uuid) IS NULL OR payer_company_id = CAST(:payer AS uuid))
                  AND (CAST(:afterId AS uuid) IS NULL OR id < CAST(:afterId AS uuid))
                ORDER BY id DESC
                LIMIT :limit
                """.trimIndent(),
            ).param("ownerType", filter.ownerType?.name)
            .param("ownerId", filter.ownerId)
            .param("status", filter.status?.name)
            .param("payer", filter.payerCompanyId?.value)
            .param("afterId", afterId)
            .param("limit", limit)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) }
            .list()
}

/**
 * Violação da restrição de unicidade [constraint] (`23505`, reconhecida pelo campo `constraint` do erro do servidor
 * na cadeia de causas, nunca pelo texto) vira `false`; qualquer outra segue.
 */
internal fun unlessViolated(
    constraint: String,
    write: () -> Unit,
): Boolean =
    try {
        write()
        true
    } catch (e: DataIntegrityViolationException) {
        if (!violated(e, constraint)) throw e
        false
    }

internal fun violated(
    e: Throwable,
    constraint: String,
): Boolean =
    generateSequence(e) { it.cause }
        .filterIsInstance<PSQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }

private const val UNIQUE_VIOLATION = "23505"

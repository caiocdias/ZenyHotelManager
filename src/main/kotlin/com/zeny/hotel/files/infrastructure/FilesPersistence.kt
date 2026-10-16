package com.zeny.hotel.files.infrastructure

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.application.StoredFiles
import com.zeny.hotel.files.domain.StoredFile
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

interface StoredFileJpaRepository : JpaRepository<StoredFile, UUID> {
    @Query("SELECT f FROM StoredFile f WHERE f.id = :id AND f.deletedAt IS NULL")
    fun findLive(
        @Param("id") id: UUID,
    ): StoredFile?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM StoredFile f WHERE f.id = :id AND f.deletedAt IS NULL")
    fun findLiveForUpdate(
        @Param("id") id: UUID,
    ): StoredFile?

    @Query(
        "SELECT f.id FROM StoredFile f WHERE f.ownerEntityId IS NULL AND f.deletedAt IS NULL " +
            "AND COALESCE(f.detachedAt, f.createdAt) < :cutoff ORDER BY COALESCE(f.detachedAt, f.createdAt), f.id",
    )
    fun findOrphanIdsBefore(
        @Param("cutoff") cutoff: Instant,
        limit: Limit,
    ): List<UUID>
}

/** Os metadados dos anexos por JPA, na transação corrente. */
@Component
class JpaStoredFiles(
    private val repository: StoredFileJpaRepository,
    private val jdbc: JdbcClient,
) : StoredFiles {
    override fun find(id: FileId): StoredFile? = repository.findLive(id.value)

    override fun findForUpdate(id: FileId): StoredFile? = repository.findLiveForUpdate(id.value)

    override fun findIncludingDeleted(id: FileId): StoredFile? = repository.findById(id.value).orElse(null)

    override fun lockClientFileId(id: FileId) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:namespace, :key)")
            .param("namespace", CLIENT_FILE_ID_LOCK_NAMESPACE)
            .param("key", id.value.hashCode())
            .query { _, _ -> }
            .list()
    }

    override fun orphansBefore(
        cutoff: Instant,
        limit: Int,
    ): List<FileId> = repository.findOrphanIdsBefore(cutoff, Limit.of(limit)).map(::FileId)

    override fun add(file: StoredFile) {
        repository.saveAndFlush(file)
    }

    override fun flush() = repository.flush()

    private companion object {
        /**
         * Primeira chave da trava consultiva de dois inteiros: separa as do `clientFileId` de outras
         * que venham a existir. Ids diferentes com o mesmo `hashCode` só esperam um pelo outro.
         */
        const val CLIENT_FILE_ID_LOCK_NAMESPACE = 0x46494C45 // "FILE"
    }
}

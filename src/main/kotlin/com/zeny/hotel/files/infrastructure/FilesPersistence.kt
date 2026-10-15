package com.zeny.hotel.files.infrastructure

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.application.StoredFiles
import com.zeny.hotel.files.domain.StoredFile
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import java.util.UUID

interface StoredFileJpaRepository : JpaRepository<StoredFile, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM StoredFile f WHERE f.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): StoredFile?
}

/** Os metadados dos anexos por JPA, na transação corrente. */
@Component
class JpaStoredFiles(
    private val repository: StoredFileJpaRepository,
) : StoredFiles {
    override fun find(id: FileId): StoredFile? = repository.findById(id.value).orElse(null)

    override fun findForUpdate(id: FileId): StoredFile? = repository.findByIdForUpdate(id.value)

    override fun add(file: StoredFile) {
        repository.saveAndFlush(file)
    }

    override fun flush() = repository.flush()
}

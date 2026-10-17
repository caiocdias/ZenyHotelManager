package com.zeny.hotel.guests.infrastructure

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.application.GuestsConstraints
import com.zeny.hotel.guests.application.GuestsStore
import com.zeny.hotel.guests.domain.Company
import com.zeny.hotel.guests.domain.Guest
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.util.UUID

interface GuestJpaRepository : JpaRepository<Guest, UUID> {
    /** Os perfis e os documentos deles numa consulta só (a listagem não faz uma consulta por perfil). */
    @Query("SELECT DISTINCT g FROM Guest g LEFT JOIN FETCH g.documentList WHERE g.id IN :ids")
    fun findAllWithDocuments(ids: Collection<UUID>): List<Guest>
}

interface CompanyJpaRepository : JpaRepository<Company, UUID>

/** Os agregados de `guests` por JPA, na transação corrente. */
@Component
class JpaGuestsStore(
    private val guests: GuestJpaRepository,
    private val companies: CompanyJpaRepository,
) : GuestsStore {
    override fun guest(id: GuestId): Guest? = guests.findById(id.value).orElse(null)

    override fun guests(ids: List<UUID>): List<Guest> {
        if (ids.isEmpty()) return emptyList()
        val found = guests.findAllWithDocuments(ids).associateBy { it.id.value }
        return ids.mapNotNull(found::get)
    }

    override fun add(guest: Guest) {
        guests.saveAndFlush(guest)
    }

    override fun company(id: CompanyId): Company? = companies.findById(id.value).orElse(null)

    override fun companies(ids: List<UUID>): List<Company> {
        if (ids.isEmpty()) return emptyList()
        val found = companies.findAllById(ids).associateBy { it.id.value }
        return ids.mapNotNull(found::get)
    }

    override fun tryAdd(company: Company): Boolean =
        unlessViolated(GuestsConstraints.COMPANY_CNPJ) { companies.saveAndFlush(company) }

    override fun flush() = guests.flush()

    override fun tryFlush(constraint: String): Boolean = unlessViolated(constraint) { guests.flush() }
}

/**
 * Violação da restrição de unicidade [constraint] (`23505`, reconhecida pelo campo `constraint` do erro do
 * servidor na cadeia de causas, nunca pelo texto) vira `false`; qualquer outra segue.
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
                .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }
        if (!violated) throw e
        false
    }

private const val UNIQUE_VIOLATION = "23505"

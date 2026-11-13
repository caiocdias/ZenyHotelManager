package com.zeny.hotel.endofday.infrastructure

import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRun
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * As execuções do fechamento, uma por data operacional. O id do repositório é o `UUID` (`id.value`), como nos demais
 * módulos: na entidade o [EndOfDayRunId] fica desembrulhado, e o Hibernate recusa o id embrulhado
 * (`TypeMismatchException`).
 */
interface EndOfDayRunRepository : JpaRepository<EndOfDayRun, UUID> {
    fun findByBusinessDate(businessDate: LocalDate): EndOfDayRun?

    /**
     * Trava a linha da execução com `SELECT … FOR NO KEY UPDATE` até o fim da transação; devolve o id, ou nulo se ela
     * não existe. Use [lockForUpdate]. Fora de uma transação, falha antes de consultar (a trava acabaria no fim do
     * comando).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("SELECT id FROM endofday.run WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    fun lockRowForNoKeyUpdate(id: UUID): UUID?

    /**
     * Trava a execução até o fim da transação e a carrega. A trava é `FOR NO KEY UPDATE`, não `FOR UPDATE`: o `INSERT`
     * de uma pendência (`run_issue`, com FK para `run`) por outra transação (`REQUIRES_NEW`, o trabalho por item de
     * uma etapa) pega `FOR KEY SHARE` na linha da execução, que `FOR UPDATE` bloquearia — e a thread esperaria por ela
     * mesma, sem o PostgreSQL detectar o impasse. Chame antes de qualquer outra carga da execução na transação, senão
     * a carga devolve a instância já em memória. Fora de uma transação, falha com `IllegalTransactionStateException`
     * antes de travar: sem transação, a trava acabaria no fim do comando e não protegeria nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockForUpdate(id: EndOfDayRunId): EndOfDayRun? =
        lockRowForNoKeyUpdate(id.value)?.let { findById(it).orElse(null) }
}

/** A restrição única da data operacional da execução (RN-21: uma execução por data). */
internal const val RUN_BUSINESS_DATE_UK = "run_business_date_uk"

private const val UNIQUE_VIOLATION = "23505"

/**
 * A violação é de unicidade (`23505`) na restrição [constraint], reconhecida pelo campo `constraint` do erro do
 * servidor (`PSQLException.serverErrorMessage`) na cadeia de causas, nunca pelo texto da mensagem.
 */
internal fun DataIntegrityViolationException.violatesUnique(constraint: String): Boolean =
    generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<PSQLException>()
        .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }

/** As pendências das execuções do fechamento. */
interface EndOfDayIssueRepository : JpaRepository<EndOfDayIssue, UUID> {
    fun findByRunIdOrderByCreatedAtAscIdAsc(runId: UUID): List<EndOfDayIssue>
}

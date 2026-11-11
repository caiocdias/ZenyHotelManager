package com.zeny.hotel.printing.infrastructure

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.application.PrintJobStore
import com.zeny.hotel.printing.domain.PrintJob
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

interface PrintJobJpaRepository : JpaRepository<PrintJob, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM PrintJob j WHERE j.id = :id")
    fun lockById(id: UUID): PrintJob?

    @Query(
        """
        SELECT j.* FROM printing.print_job j
        JOIN printing.printer p ON p.id = j.printer_id
        JOIN printing.print_agent a ON a.id = p.agent_id
        WHERE j.status = 'PENDING' AND p.agent_id = :agentId AND p.active AND a.active
        ORDER BY j.position
        LIMIT :limit
        FOR UPDATE OF j SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun claimPending(
        agentId: UUID,
        limit: Int,
    ): List<PrintJob>

    @Query(
        "SELECT j FROM PrintJob j WHERE j.status = com.zeny.hotel.printing.PrintJobStatus.SENT " +
            "AND j.deliveredTo = :agentId AND j.position > :afterPosition ORDER BY j.position",
    )
    fun unackedSent(
        agentId: UUID,
        afterPosition: Long,
    ): List<PrintJob>

    @Query(
        "SELECT j FROM PrintJob j WHERE j.status = com.zeny.hotel.printing.PrintJobStatus.SENT " +
            "AND j.sentAt < :before ORDER BY j.sentAt, j.id",
    )
    fun expiredSent(
        before: Instant,
        page: PageRequest,
    ): List<PrintJob>

    @Query(
        "SELECT j FROM PrintJob j WHERE j.status = com.zeny.hotel.printing.PrintJobStatus.PENDING " +
            "AND j.createdAt < :before ORDER BY j.createdAt, j.id",
    )
    fun stalePending(
        before: Instant,
        page: PageRequest,
    ): List<PrintJob>

    @Query(
        "SELECT j FROM PrintJob j WHERE j.status IN (com.zeny.hotel.printing.PrintJobStatus.PENDING, " +
            "com.zeny.hotel.printing.PrintJobStatus.SENT) AND j.printerId IN " +
            "(SELECT p.id FROM Printer p WHERE p.active = false) ORDER BY j.position",
    )
    fun ofInactivePrinters(page: PageRequest): List<PrintJob>

    @Query(
        "SELECT j FROM PrintJob j WHERE j.status IN (com.zeny.hotel.printing.PrintJobStatus.PRINTED, " +
            "com.zeny.hotel.printing.PrintJobStatus.FAILED) AND j.finishedAt < :before AND j.content IS NOT NULL " +
            "ORDER BY j.finishedAt, j.id",
    )
    fun purgeable(
        before: Instant,
        page: PageRequest,
    ): List<PrintJob>
}

/** Os jobs por JPA, na transação corrente. */
@Suppress("TooManyFunctions") // uma função por consulta da porta [PrintJobStore]
@Component
class JpaPrintJobStore(
    private val jobs: PrintJobJpaRepository,
    private val entityManager: EntityManager,
    private val jdbc: JdbcClient,
) : PrintJobStore {
    override fun nextPosition(): Long =
        jdbc
            .sql("SELECT nextval('printing.print_job_position_seq')")
            .query(Long::class.java)
            .single()

    override fun save(job: PrintJob) {
        jobs.save(job)
    }

    override fun flush() = jobs.flush()

    override fun byId(id: UUID): PrintJob? = jobs.findById(id).orElse(null)

    override fun lockById(id: UUID): PrintJob? = jobs.lockById(id)

    override fun page(
        status: PrintJobStatus?,
        printerId: UUID?,
        page: PageParams,
    ): CursorPage<PrintJob> {
        val after = page.decodedCursor()?.long(0)
        val conditions =
            listOfNotNull(
                status?.let { "j.status = :status" },
                printerId?.let { "j.printerId = :printerId" },
                after?.let { "j.position < :after" },
            )
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val query =
            entityManager
                .createQuery("SELECT j FROM PrintJob j$where ORDER BY j.position DESC", PrintJob::class.java)
                .setMaxResults(page.limit + 1)
        status?.let { query.setParameter("status", it) }
        printerId?.let { query.setParameter("printerId", it) }
        after?.let { query.setParameter("after", it) }
        return CursorPage.fromRows(query.resultList, page.limit) { PageCursor.of(it.position.toString()) }
    }

    override fun claimPending(
        agentId: UUID,
        limit: Int,
    ): List<PrintJob> = jobs.claimPending(agentId, limit)

    override fun unackedSent(
        agentId: UUID,
        afterPosition: Long,
    ): List<PrintJob> = jobs.unackedSent(agentId, afterPosition)

    override fun expiredSent(
        before: Instant,
        limit: Int,
    ): List<PrintJob> = jobs.expiredSent(before, PageRequest.ofSize(limit))

    override fun stalePending(
        before: Instant,
        limit: Int,
    ): List<PrintJob> = jobs.stalePending(before, PageRequest.ofSize(limit))

    override fun ofInactivePrinters(limit: Int): List<PrintJob> = jobs.ofInactivePrinters(PageRequest.ofSize(limit))

    override fun purgeable(
        before: Instant,
        limit: Int,
    ): List<PrintJob> = jobs.purgeable(before, PageRequest.ofSize(limit))

    override fun countByStatus(): Map<PrintJobStatus, Long> =
        jdbc
            .sql("SELECT status, count(*) FROM printing.print_job GROUP BY status")
            .query { rs, _ -> PrintJobStatus.valueOf(rs.getString(1)) to rs.getLong(2) }
            .list()
            .toMap()
}

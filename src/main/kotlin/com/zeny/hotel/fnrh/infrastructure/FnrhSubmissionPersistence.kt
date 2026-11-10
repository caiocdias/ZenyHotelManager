package com.zeny.hotel.fnrh.infrastructure

import com.zeny.hotel.fnrh.FnrhEnvironment
import com.zeny.hotel.fnrh.application.FnrhRemoteGuest
import com.zeny.hotel.fnrh.application.FnrhRemoteIds
import com.zeny.hotel.fnrh.application.FnrhRemoteReservation
import com.zeny.hotel.fnrh.application.FnrhSubmissionStore
import com.zeny.hotel.fnrh.domain.FnrhOperation
import com.zeny.hotel.fnrh.domain.FnrhRemoteId
import com.zeny.hotel.fnrh.domain.FnrhSubmission
import com.zeny.hotel.fnrh.domain.FnrhSubmissionStatus
import com.zeny.hotel.shared.FieldCipher
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

interface FnrhSubmissionJpaRepository : JpaRepository<FnrhSubmission, UUID> {
    @Query(
        "select s from FnrhSubmission s where s.reservationId = :reservationId and s.operation = :operation " +
            "and s.status in :statuses order by s.causalVersion, s.operationRank, s.sequence",
    )
    fun unresolved(
        @Param("reservationId") reservationId: UUID,
        @Param("operation") operation: FnrhOperation,
        @Param("statuses") statuses: Collection<FnrhSubmissionStatus>,
    ): List<FnrhSubmission>

    @Query(
        "select s from FnrhSubmission s where s.sequence > :after order by s.sequence",
    )
    fun after(
        @Param("after") after: Long,
        pageable: PageRequest,
    ): List<FnrhSubmission>

    @Query(
        "select s from FnrhSubmission s where s.status = :status and s.sequence > :after order by s.sequence",
    )
    fun afterWithStatus(
        @Param("status") status: FnrhSubmissionStatus,
        @Param("after") after: Long,
        pageable: PageRequest,
    ): List<FnrhSubmission>
}

private val UNRESOLVED = listOf(FnrhSubmissionStatus.PENDING, FnrhSubmissionStatus.FAILED, FnrhSubmissionStatus.INVALID)

/**
 * A fila de submissões por JPA (a entidade) e JDBC (a gravação idempotente e a fila por reserva). `markInFlight` roda
 * numa transação própria (`REQUIRES_NEW`): a marca precisa sobreviver a quem a desfaz.
 */
@Component
class JpaFnrhSubmissionStore(
    private val submissions: FnrhSubmissionJpaRepository,
    private val jdbc: JdbcClient,
    transactionManager: PlatformTransactionManager,
) : FnrhSubmissionStore {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun recordIfAbsent(submission: FnrhSubmission): Boolean =
        jdbc
            .sql(
                """
                INSERT INTO fnrh.submission
                    (id, reservation_id, reservation_code, operation, operation_rank, event_key, causal_version,
                     status, attempts, next_attempt_at, in_flight, performed_by, occurred_at, created_at, version)
                VALUES (:id, :reservationId, :code, :operation, :rank, :eventKey, :causalVersion,
                        :status, :attempts, :nextAttemptAt, :inFlight, :performedBy, :occurredAt, :createdAt, 0)
                ON CONFLICT (reservation_id, operation, event_key) DO NOTHING
                """.trimIndent(),
            ).param("id", submission.id)
            .param("reservationId", submission.reservationId)
            .param("code", submission.reservationCode)
            .param("operation", submission.operation.name)
            .param("rank", submission.operationRank)
            .param("eventKey", submission.eventKey)
            .param("causalVersion", submission.causalVersion)
            .param("status", submission.status.name)
            .param("attempts", submission.attempts)
            .param("nextAttemptAt", submission.nextAttemptAt.atOffset(ZoneOffset.UTC))
            .param("inFlight", submission.inFlight)
            .param("performedBy", submission.performedBy)
            .param("occurredAt", submission.occurredAt.atOffset(ZoneOffset.UTC))
            .param("createdAt", submission.createdAt.atOffset(ZoneOffset.UTC))
            .update() == 1

    @Transactional(readOnly = true)
    override fun find(id: UUID): FnrhSubmission? = submissions.findById(id).orElse(null)

    /** A coluna `in_flight` não é gravada pela entidade: ao marcar `SENT`, é limpa aqui (D-E08-15). */
    @Transactional
    override fun save(submission: FnrhSubmission) {
        submissions.save(submission)
        if (submission.status == FnrhSubmissionStatus.SENT) {
            jdbc.sql("UPDATE fnrh.submission SET in_flight = false WHERE id = :id").param("id", submission.id).update()
        }
    }

    override fun markInFlight(id: UUID) {
        ownTransaction.executeWithoutResult {
            jdbc.sql("UPDATE fnrh.submission SET in_flight = true WHERE id = :id").param("id", id).update()
        }
    }

    // O `UPDATE` incrementa `version`: uma entidade lida antes não grava por cima do cancelamento.
    override fun cancelIfIdle(
        id: UUID,
        now: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE fnrh.submission
                   SET status = 'CANCELLED', next_attempt_at = :now, version = version + 1
                 WHERE id = :id AND in_flight = false AND status IN ('PENDING', 'FAILED', 'INVALID')
                """.trimIndent(),
            ).param("id", id)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .update() == 1

    override fun dueHeads(
        now: Instant,
        limit: Int,
        reservationId: UUID?,
    ): List<UUID> =
        jdbc
            .sql(
                """
                SELECT id FROM (
                    SELECT DISTINCT ON (reservation_id) id, status, next_attempt_at, sequence
                    FROM fnrh.submission
                    WHERE status IN ('PENDING', 'FAILED', 'INVALID')
                      AND (CAST(:reservationId AS uuid) IS NULL OR reservation_id = CAST(:reservationId AS uuid))
                    ORDER BY reservation_id, causal_version, operation_rank, sequence
                ) head
                WHERE status = 'PENDING' AND next_attempt_at <= :now
                ORDER BY sequence
                LIMIT :limit
                """.trimIndent(),
            ).param("reservationId", reservationId)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("limit", limit)
            .query { rs, _ -> checkNotNull(rs.getObject(1, UUID::class.java)) }
            .list()

    @Transactional(readOnly = true)
    override fun unresolvedOf(
        reservationId: UUID,
        operation: FnrhOperation,
    ): List<FnrhSubmission> = submissions.unresolved(reservationId, operation, UNRESOLVED)

    @Transactional(readOnly = true)
    override fun page(
        status: FnrhSubmissionStatus?,
        after: Long?,
        limit: Int,
    ): List<FnrhSubmission> {
        val pageable = PageRequest.of(0, limit)
        val cursor = after ?: 0L
        return if (status == null) {
            submissions.after(cursor, pageable)
        } else {
            submissions.afterWithStatus(status, cursor, pageable)
        }
    }

    override fun countByStatus(): Map<FnrhSubmissionStatus, Long> =
        jdbc
            .sql("SELECT status, count(*) FROM fnrh.submission GROUP BY status")
            .query { rs, _ -> FnrhSubmissionStatus.valueOf(rs.getString(1)) to rs.getLong(2) }
            .list()
            .toMap()
}

/**
 * Os ids remotos por JDBC. O link de pré-check-in é cifrado pelo [FieldCipher] ao gravar e decifrado ao ler. Cada
 * escrita roda em transação própria e limpa o `in_flight` da submissão na mesma transação (D-E08-15).
 */
@Component
class JdbcFnrhRemoteIds(
    private val jdbc: JdbcClient,
    private val cipher: FieldCipher,
    transactionManager: PlatformTransactionManager,
) : FnrhRemoteIds {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    @Transactional(readOnly = true)
    override fun reservation(
        reservationId: UUID,
        env: FnrhEnvironment,
    ): FnrhRemoteReservation? =
        jdbc
            .sql(
                "SELECT remote_id, precheckin_link, cancelled FROM fnrh.remote_reservation " +
                    "WHERE reservation_id = :reservationId AND environment = :environment",
            ).param("reservationId", reservationId)
            .param("environment", env.name)
            .query { rs, _ ->
                FnrhRemoteReservation(
                    reservationId,
                    env,
                    FnrhRemoteId(rs.getString("remote_id")),
                    rs.getString("precheckin_link")?.let(cipher::decrypt),
                    rs.getBoolean("cancelled"),
                )
            }.optional()
            .orElse(null)

    override fun saveReservation(
        r: FnrhRemoteReservation,
        submissionId: UUID,
    ) {
        val link = r.precheckinLink?.let(cipher::encrypt)
        ownTransaction.executeWithoutResult {
            jdbc
                .sql(
                    """
                    INSERT INTO fnrh.remote_reservation (reservation_id, environment, remote_id, precheckin_link, cancelled)
                    VALUES (:reservationId, :environment, :remoteId, :link, :cancelled)
                    ON CONFLICT (reservation_id, environment) DO UPDATE
                    SET remote_id = EXCLUDED.remote_id, precheckin_link = EXCLUDED.precheckin_link,
                        cancelled = EXCLUDED.cancelled
                    """.trimIndent(),
                ).param("reservationId", r.reservationId)
                .param("environment", r.environment.name)
                .param("remoteId", r.remoteId.value)
                .param("link", link)
                .param("cancelled", r.cancelled)
                .update()
            clearInFlight(submissionId)
        }
    }

    @Transactional(readOnly = true)
    override fun guests(
        reservationId: UUID,
        env: FnrhEnvironment,
    ): List<FnrhRemoteGuest> =
        jdbc
            .sql(
                "SELECT guest_id, person_id, guest_remote_id, checked_out FROM fnrh.remote_guest " +
                    "WHERE reservation_id = :reservationId AND environment = :environment ORDER BY guest_id",
            ).param("reservationId", reservationId)
            .param("environment", env.name)
            .query { rs, _ ->
                FnrhRemoteGuest(
                    reservationId,
                    rs.getObject("guest_id", UUID::class.java),
                    env,
                    FnrhRemoteId(rs.getString("person_id")),
                    rs.getString("guest_remote_id")?.let(::FnrhRemoteId),
                    rs.getBoolean("checked_out"),
                )
            }.list()

    override fun saveGuest(
        g: FnrhRemoteGuest,
        submissionId: UUID,
    ) {
        ownTransaction.executeWithoutResult {
            jdbc
                .sql(
                    """
                    INSERT INTO fnrh.remote_guest
                        (reservation_id, guest_id, environment, person_id, guest_remote_id, checked_out)
                    VALUES (:reservationId, :guestId, :environment, :personId, :guestRemoteId, :checkedOut)
                    ON CONFLICT (reservation_id, guest_id, environment) DO UPDATE
                    SET person_id = EXCLUDED.person_id, guest_remote_id = EXCLUDED.guest_remote_id,
                        checked_out = EXCLUDED.checked_out
                    """.trimIndent(),
                ).param("reservationId", g.reservationId)
                .param("guestId", g.guestId)
                .param("environment", g.environment.name)
                .param("personId", g.personId.value)
                .param("guestRemoteId", g.guestRemoteId?.value)
                .param("checkedOut", g.checkedOut)
                .update()
            clearInFlight(submissionId)
        }
    }

    @Transactional(readOnly = true)
    override fun knownPerson(
        guestId: UUID,
        env: FnrhEnvironment,
    ): FnrhRemoteId? =
        jdbc
            .sql(
                "SELECT person_id FROM fnrh.remote_guest WHERE guest_id = :guestId AND environment = :environment " +
                    "ORDER BY reservation_id LIMIT 1",
            ).param("guestId", guestId)
            .param("environment", env.name)
            .query(String::class.java)
            .optional()
            .map(::FnrhRemoteId)
            .orElse(null)

    private fun clearInFlight(submissionId: UUID) {
        jdbc.sql("UPDATE fnrh.submission SET in_flight = false WHERE id = :id").param("id", submissionId).update()
    }
}

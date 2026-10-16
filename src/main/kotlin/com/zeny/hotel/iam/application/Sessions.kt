package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.IamPermissions
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Uma sessão ativa do próprio usuário; [current] marca a da requisição. */
data class SessionView(
    val id: UUID,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val current: Boolean,
)

/**
 * As sessões do próprio usuário (RF-ACS-10): logout, lista das ativas e revogação de uma delas.
 * A revogação vale para o próximo refresh; o access token já emitido vence em até 15 min.
 * Gerir as sessões de outro usuário é do [UserSessionsService].
 */
@Service
class SessionService(
    private val sessions: DeviceSessions,
    private val revocations: SessionRevocations,
    private val directory: SessionDirectory,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Encerra a sessão corrente. Sessão já revogada não muda. */
    @Audited("iam.session.logout")
    @Transactional
    fun logout(actor: Actor) {
        val session = sessions.findByIdForUpdate(actor.deviceSessionId)
        if (session == null) {
            audit.noChanges()
            return
        }
        audit.track(IamAudit.SESSION, session.id) { session.auditState() }
        revocations.revoke(session, SessionRevocationReason.LOGOUT, clock.instant())
        log.info("Logout")
    }

    @Transactional(readOnly = true)
    fun list(
        actor: Actor,
        page: PageParams,
    ): CursorPage<SessionView> {
        val rows =
            directory.activeSessionsOf(
                actor.userId,
                clock.instant(),
                page.decodedCursor()?.uuid(0),
                page.limit + 1,
            )
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }.map { it.toView(actor) }
    }

    /** Revoga uma sessão do próprio usuário; de outro usuário ou inexistente, `404`. */
    @Audited("iam.session.revoke")
    @Transactional
    fun revoke(
        actor: Actor,
        sessionId: UUID,
    ) {
        val session = sessionOf(sessions, actor.userId, sessionId)
        audit.track(IamAudit.SESSION, session.id) { session.auditState() }
        revocations.revoke(session, SessionRevocationReason.REVOKED_BY_USER, clock.instant())
        log.info("Sessão {} revogada pelo usuário", session.id)
    }
}

/**
 * As sessões de outro usuário (RF-ACS-10): o administrador as lista e revoga, por exemplo para
 * tirar o acesso de um celular perdido. Exige `iam.sessions.manage`; as de um administrador, só
 * outro administrador (RF-ACS-09). A revogação vale no próximo refresh.
 */
@Service
@Suppress("LongParameterList") // os colaboradores do caso de uso
class UserSessionsService(
    private val users: Users,
    private val sessions: DeviceSessions,
    private val revocations: SessionRevocations,
    private val directory: SessionDirectory,
    private val rules: DelegationRules,
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(
        userId: UserId,
        page: PageParams,
    ): CursorPage<SessionView> {
        val actor = authorize(userId)
        val rows = directory.activeSessionsOf(userId, clock.instant(), page.decodedCursor()?.uuid(0), page.limit + 1)
        return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }.map { it.toView(actor) }
    }

    /** Revoga uma sessão do usuário; de outro usuário ou inexistente, `404`. Revogar de novo não muda nada. */
    @Audited("iam.session.revoke-as-administrator")
    @Transactional
    fun revoke(
        userId: UserId,
        sessionId: UUID,
    ) {
        val actor = authorize(userId)
        val session = sessionOf(sessions, userId, sessionId)
        audit.track(IamAudit.SESSION, session.id) { session.auditState() }
        revocations.revoke(session, SessionRevocationReason.REVOKED_BY_ADMINISTRATOR, clock.instant())
        log.info("Sessão {} do usuário {} revogada pelo usuário {}", session.id, userId, actor.userId)
    }

    private fun authorize(userId: UserId): Actor {
        val actor = currentActor.current()
        authorization.require(IamPermissions.SESSIONS_MANAGE)
        users.findById(userId) ?: throw userNotFound()
        rules.requireCanManage(actor, userId)
        return actor
    }
}

/** A sessão do usuário, travada; de outro usuário ou inexistente, `404`. */
private fun sessionOf(
    sessions: DeviceSessions,
    userId: UserId,
    sessionId: UUID,
) = sessions
    .findByIdForUpdate(DeviceSessionId(sessionId))
    ?.takeIf { it.userId == userId }
    ?: throw NotFoundException(IamErrorCodes.SESSION_NOT_FOUND, "Sessão não encontrada.")

private fun SessionSummary.toView(actor: Actor) =
    SessionView(
        id = id,
        deviceId = deviceId,
        deviceName = deviceName,
        createdAt = createdAt,
        lastSeenAt = lastSeenAt,
        expiresAt = expiresAt,
        current = id == actor.deviceSessionId.value,
    )

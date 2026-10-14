package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
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
 * Gerir as sessões de outro usuário é do administrador (M1 E03).
 */
@Service
class SessionService(
    private val sessions: DeviceSessions,
    private val directory: SessionDirectory,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Encerra a sessão corrente. Sessão já revogada não muda. */
    @Transactional
    fun logout(actor: Actor) {
        sessions.findByIdForUpdate(actor.deviceSessionId)?.revoke(SessionRevocationReason.LOGOUT, clock.instant())
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
    @Transactional
    fun revoke(
        actor: Actor,
        sessionId: UUID,
    ) {
        val session =
            sessions
                .findByIdForUpdate(DeviceSessionId(sessionId))
                ?.takeIf { it.userId == actor.userId }
                ?: throw NotFoundException(IamErrorCodes.SESSION_NOT_FOUND, "Sessão não encontrada.")
        session.revoke(SessionRevocationReason.REVOKED_BY_USER, clock.instant())
        log.info("Sessão {} revogada pelo usuário", session.id)
    }

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
}

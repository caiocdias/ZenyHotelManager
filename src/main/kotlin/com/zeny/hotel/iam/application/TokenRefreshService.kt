package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.UnauthenticatedException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Renovação do access token pelo refresh token (ARCHITECTURE §8.1): rotação a cada uso, com
 * validade deslizante, e detecção de reuso. A sessão do token vigente é travada, então dois
 * refreshes simultâneos com o mesmo token não renovam os dois: o segundo encontra o token já
 * trocado e conta como reuso.
 *
 * - token vigente de sessão ativa e usuário ativo: novo par de tokens, e o anterior passa para
 *   a lista dos trocados;
 * - token já trocado (reuso): a sessão inteira é revogada, inclusive o refresh mais novo, e a
 *   resposta é `401 REFRESH_TOKEN_REUSED`;
 * - desconhecido, vencido, de sessão revogada ou de usuário desativado: `401 REFRESH_TOKEN_INVALID`.
 *
 * As recusas confirmam a transação: a revogação por reuso precisa ficar gravada.
 */
@Service
@Suppress("LongParameterList") // os colaboradores do caso de uso
class TokenRefreshService(
    private val users: Users,
    private val sessions: DeviceSessions,
    private val rotated: RotatedRefreshTokens,
    private val tokens: AccessTokenIssuer,
    private val properties: IamProperties,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Audited("iam.session.refresh", recordRejections = true)
    @Transactional(noRollbackFor = [UnauthenticatedException::class])
    fun refresh(refreshToken: String): AuthTokens {
        val now = clock.instant()
        val tokenHash = RefreshTokens.hash(refreshToken)
        val session = sessions.findByRefreshTokenHashForUpdate(tokenHash) ?: refuseUnknown(tokenHash, now)
        audit.attributeTo(session.userId, session.id)
        audit.track(IamAudit.SESSION, session.id) { session.auditState() }
        if (!session.isActive(now)) refuse(IamErrorCodes.REFRESH_TOKEN_INVALID)
        val user = users.findById(session.userId)
        if (user == null || !user.active) {
            session.revoke(SessionRevocationReason.USER_DISABLED, now)
            log.info("Refresh recusado: usuário da sessão {} desativado", session.id)
            refuse(IamErrorCodes.REFRESH_TOKEN_INVALID)
        }
        val newToken = RefreshTokens.generate()
        val previousHash = session.rotate(RefreshTokens.hash(newToken), now, properties.refreshToken.ttl)
        rotated.record(previousHash, session.id, now)
        return issueTokens(user, session, newToken, tokens, now)
    }

    /** Token que não é o vigente de nenhuma sessão: reuso de um já trocado, ou lixo. */
    private fun refuseUnknown(
        tokenHash: String,
        now: Instant,
    ): Nothing {
        val sessionId = rotated.sessionOf(tokenHash) ?: refuse(IamErrorCodes.REFRESH_TOKEN_INVALID)
        sessions.findByIdForUpdate(sessionId)?.let {
            audit.attributeTo(it.userId, it.id)
            audit.track(IamAudit.SESSION, it.id) { it.auditState() }
            it.revoke(SessionRevocationReason.REFRESH_TOKEN_REUSED, now)
        }
        log.warn("Reuso de refresh token detectado: sessão {} revogada", sessionId)
        refuse(IamErrorCodes.REFRESH_TOKEN_REUSED)
    }
}

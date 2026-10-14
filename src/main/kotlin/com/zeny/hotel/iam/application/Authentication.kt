package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.domain.DeviceSession
import com.zeny.hotel.iam.domain.Logins
import com.zeny.hotel.iam.domain.SessionRevocationReason
import com.zeny.hotel.iam.domain.User
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.DeviceSessionId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.UnauthenticatedException
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** Login com senha a partir de um dispositivo. A senha nunca aparece em `toString`. */
class LoginCommand(
    val login: String,
    val password: String,
    val deviceId: String,
    val deviceName: String,
) {
    override fun toString(): String = "LoginCommand(deviceId=$deviceId)"
}

/** Troca da própria senha. */
class ChangePasswordCommand(
    val currentPassword: String,
    val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordCommand"
}

/** O par de tokens de uma sessão, devolvido pelo login e pelo refresh. Os tokens nunca aparecem em `toString`. */
class AuthTokens(
    val userId: UserId,
    val sessionId: DeviceSessionId,
    val accessToken: IssuedAccessToken,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val passwordChangeRequired: Boolean,
) {
    override fun toString(): String = "AuthTokens(userId=$userId, sessionId=$sessionId)"
}

/**
 * Login e troca de senha (RF-ACS-10, ARCHITECTURE §8.1). O login normaliza o login (sem
 * diferenciar maiúsculas), confere a senha em Argon2id com a linha do usuário travada (as
 * tentativas do mesmo usuário ficam em fila, e a contagem de falhas não se perde), aplica o
 * bloqueio progressivo e abre uma sessão nova no dispositivo, revogando a anterior dele.
 *
 * As recusas de autenticação (`401`) **confirmam** a transação: a falha contada e o bloqueio
 * precisam ficar gravados mesmo com a exceção.
 */
@Service
@Suppress("LongParameterList") // os colaboradores do caso de uso
class AuthenticationService(
    private val users: Users,
    private val sessions: DeviceSessions,
    private val hasher: PasswordHasher,
    private val tokens: AccessTokenIssuer,
    private val properties: IamProperties,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Hash de uma senha qualquer, para que login inexistente leve o mesmo tempo que senha errada. */
    private val decoyHash: String by lazy { hasher.hash(RefreshTokens.generate()) }

    @Audited("iam.session.login", recordRejections = true)
    @Transactional(noRollbackFor = [UnauthenticatedException::class])
    fun login(command: LoginCommand): AuthTokens {
        val now = clock.instant()
        val user = Logins.normalize(command.login)?.let(users::findByLoginForUpdate)
        if (user == null) {
            hasher.matches(command.password, decoyHash)
            log.info("Login recusado: credenciais inválidas")
            refuse(IamErrorCodes.INVALID_CREDENTIALS)
        }
        // Login de usuário existente: a falha conta para o bloqueio e vai para a trilha como recusa.
        audit.attributeTo(user.id, null)
        audit.track(IamAudit.USER, user.id) { user.auditState() }
        verifyPassword(user, command.password, now)
        if (hasher.needsRehash(user.passwordHash)) user.rehashPassword(hasher.hash(command.password), now)

        sessions.findNotRevokedOnDeviceForUpdate(user.id, command.deviceId)?.let {
            audit.track(IamAudit.SESSION, it.id) { it.auditState() }
            it.revoke(SessionRevocationReason.REPLACED_BY_LOGIN, now)
            // A sessão nova ocupa o mesmo (usuário, dispositivo) no índice único das não revogadas.
            sessions.flush()
        }
        val refreshToken = RefreshTokens.generate()
        val session =
            DeviceSession.open(
                userId = user.id,
                deviceId = command.deviceId,
                deviceName = command.deviceName,
                refreshTokenHash = RefreshTokens.hash(refreshToken),
                now = now,
                ttl = properties.refreshToken.ttl,
            )
        sessions.add(session)
        audit.created(IamAudit.SESSION, session.id) { session.auditState() }
        audit.attributeTo(user.id, session.id)
        log.info("Login do usuário {} na sessão {}", user.id, session.id)
        return issueTokens(user, session, refreshToken, tokens, now)
    }

    /**
     * Troca a senha do próprio usuário, conferindo a atual com as mesmas regras do login
     * (falha conta para o bloqueio). Encerra a troca obrigatória e revoga as outras sessões do
     * usuário; a sessão corrente continua, e o próximo refresh já traz um token sem a pendência.
     */
    @Audited("iam.user.change-password", recordRejections = true)
    @Transactional(noRollbackFor = [UnauthenticatedException::class])
    fun changePassword(
        actor: Actor,
        command: ChangePasswordCommand,
    ) {
        val now = clock.instant()
        val user = users.findByIdForUpdate(actor.userId) ?: refuse(ErrorCodes.UNAUTHENTICATED)
        audit.track(IamAudit.USER, user.id) { user.auditState() }
        verifyPassword(user, command.currentPassword, now)
        if (command.newPassword == command.currentPassword) {
            throw BusinessRuleException(
                IamErrorCodes.PASSWORD_UNCHANGED,
                "A nova senha precisa ser diferente da atual.",
            )
        }
        user.changePassword(hasher.hash(command.newPassword), now)
        sessions.findNotRevokedOf(user.id, except = actor.deviceSessionId).forEach {
            audit.track(IamAudit.SESSION, it.id) { it.auditState() }
            it.revoke(SessionRevocationReason.PASSWORD_CHANGED, now)
        }
        log.info("Senha trocada pelo usuário {}", user.id)
    }

    /** Bloqueio, senha e usuário ativo, nessa ordem; a senha errada conta para o bloqueio. */
    private fun verifyPassword(
        user: User,
        password: String,
        now: Instant,
    ) {
        if (user.isLocked(now)) {
            log.info("Login recusado: usuário {} bloqueado", user.id)
            refuse(IamErrorCodes.ACCOUNT_LOCKED)
        }
        if (!hasher.matches(password, user.passwordHash)) {
            user.recordFailedLogin(now, properties.login.lockoutPolicy)
            log.info("Login recusado: senha errada do usuário {} ({} falhas seguidas)", user.id, user.failedLoginCount)
            refuse(IamErrorCodes.INVALID_CREDENTIALS)
        }
        if (!user.active) {
            log.info("Login recusado: usuário {} desativado", user.id)
            refuse(IamErrorCodes.ACCOUNT_DISABLED)
        }
        user.recordSuccessfulLogin(now)
    }
}

/** Os tokens de uma sessão recém-aberta ou renovada. */
internal fun issueTokens(
    user: User,
    session: DeviceSession,
    refreshToken: String,
    tokens: AccessTokenIssuer,
    now: Instant,
): AuthTokens =
    AuthTokens(
        userId = user.id,
        sessionId = session.id,
        accessToken = tokens.issue(user, session, now),
        refreshToken = refreshToken,
        refreshTokenExpiresAt = session.expiresAt,
        passwordChangeRequired = user.passwordChangeRequired,
    )

/** Recusa de autenticação (`401`) com a mensagem padrão do código. */
internal fun refuse(code: ErrorCode): Nothing = throw UnauthenticatedException(code, MESSAGES.getValue(code))

private val MESSAGES: Map<ErrorCode, String> =
    mapOf(
        ErrorCodes.UNAUTHENTICATED to "Autentique-se para acessar este recurso.",
        IamErrorCodes.INVALID_CREDENTIALS to "Login ou senha incorretos.",
        IamErrorCodes.ACCOUNT_LOCKED to "Login bloqueado temporariamente por tentativas erradas. Tente mais tarde.",
        IamErrorCodes.ACCOUNT_DISABLED to "Usuário desativado. Procure o administrador.",
        IamErrorCodes.REFRESH_TOKEN_INVALID to "Sessão encerrada ou vencida. Entre de novo.",
        IamErrorCodes.REFRESH_TOKEN_REUSED to "Sessão encerrada por segurança. Entre de novo.",
    )

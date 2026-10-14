package com.zeny.hotel.iam.web

import com.zeny.hotel.iam.application.AuthTokens
import com.zeny.hotel.iam.application.AuthenticationService
import com.zeny.hotel.iam.application.ChangePasswordCommand
import com.zeny.hotel.iam.application.LoginCommand
import com.zeny.hotel.iam.application.PasswordRules
import com.zeny.hotel.iam.application.SessionService
import com.zeny.hotel.iam.application.SessionView
import com.zeny.hotel.iam.application.TokenRefreshService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Instant
import java.util.UUID

/**
 * Autenticação e sessões do próprio usuário (RF-ACS-10, ARCHITECTURE §8.1). Login e refresh são
 * públicos; o resto exige o access token. Os tokens saem só no corpo destas respostas, nunca em
 * log, e o Spring Security marca as respostas com `Cache-Control: no-store`.
 */
@ApiV1Controller
@RequestMapping("/auth")
@Tag(name = "iam", description = "Autenticação, sessões por dispositivo e usuários.")
class AuthController(
    private val authentication: AuthenticationService,
    private val refreshes: TokenRefreshService,
    private val sessions: SessionService,
    private val currentActor: CurrentActor,
) {
    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
        operationId = "login",
        summary = "Entrar com login e senha",
        description =
            "Público. Abre uma sessão no dispositivo (um novo login no mesmo dispositivo encerra a sessão anterior " +
                "dele) e devolve um access token JWT de 15 min e um refresh token. O login não diferencia " +
                "maiúsculas. Recusas com 401: `INVALID_CREDENTIALS` (login ou senha), `ACCOUNT_LOCKED` (falhas " +
                "seguidas; o bloqueio cresce a cada nova falha e vale mesmo com a senha certa) e " +
                "`ACCOUNT_DISABLED`. Com `passwordChangeRequired`, só a troca de senha e o logout respondem até a " +
                "senha ser trocada.",
    )
    @ApiResponse(responseCode = "200", description = "Sessão aberta.")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): TokenResponse =
        authentication
            .login(
                LoginCommand(request.login, request.password, request.device.id, request.device.name),
            ).toResponse()

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
        operationId = "refreshTokens",
        summary = "Renovar os tokens da sessão",
        description =
            "Público (o cabeçalho Authorization é ignorado). Troca o refresh token por um par novo: o refresh " +
                "token usado deixa de valer, e a validade da sessão volta a 30 dias. Recusas com 401: " +
                "`REFRESH_TOKEN_INVALID` (desconhecido, vencido, sessão revogada ou usuário desativado) e " +
                "`REFRESH_TOKEN_REUSED` (o token já tinha sido trocado: a sessão inteira é revogada, inclusive o " +
                "refresh token mais novo). Nos dois casos, o cliente pede um novo login.",
    )
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): TokenResponse = refreshes.refresh(request.refreshToken).toResponse()

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "logout",
        summary = "Sair",
        description =
            "Encerra a sessão do access token: o refresh token dela deixa de valer. O access token vence sozinho " +
                "em até 15 min.",
    )
    @ApiResponse(responseCode = "204", description = "Sessão encerrada.")
    fun logout() = sessions.logout(currentActor.current())

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "changePassword",
        summary = "Trocar a própria senha",
        description =
            "Confere a senha atual (401 `INVALID_CREDENTIALS` ou `ACCOUNT_LOCKED`, contando para o bloqueio como " +
                "o login) e grava a nova, de 12 a 128 caracteres e diferente da atual (422 `PASSWORD_UNCHANGED`). " +
                "Encerra a troca obrigatória e as outras sessões do usuário; a sessão corrente continua, e o " +
                "próximo refresh traz um access token sem a pendência.",
    )
    @ApiResponse(responseCode = "204", description = "Senha trocada.")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
    ) = authentication.changePassword(
        currentActor.current(),
        ChangePasswordCommand(request.currentPassword, request.newPassword),
    )

    @GetMapping("/sessions")
    @Operation(
        operationId = "listMySessions",
        summary = "Listar as próprias sessões ativas",
        description = "Sessões não revogadas e não vencidas do usuário, da mais nova para a mais antiga.",
    )
    fun listSessions(
        @Valid page: PageParams,
    ): CursorPage<SessionResponse> = sessions.list(currentActor.current(), page).map(::toResponse)

    @PostMapping("/sessions/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "revokeMySession",
        summary = "Revogar uma sessão própria",
        description =
            "O refresh token da sessão deixa de valer (o access token vence em até 15 min). Revogar de novo não " +
                "muda nada. Sessão de outro usuário ou inexistente: 404 `SESSION_NOT_FOUND`.",
    )
    @ApiResponse(responseCode = "204", description = "Sessão revogada.")
    fun revokeSession(
        @PathVariable id: UUID,
    ) = sessions.revoke(currentActor.current(), id)

    private fun AuthTokens.toResponse() =
        TokenResponse(
            tokenType = BEARER,
            accessToken = accessToken.value,
            accessTokenExpiresAt = accessToken.expiresAt,
            refreshToken = refreshToken,
            refreshTokenExpiresAt = refreshTokenExpiresAt,
            userId = userId.value,
            sessionId = sessionId.value,
            passwordChangeRequired = passwordChangeRequired,
        )

    private fun toResponse(session: SessionView) =
        SessionResponse(
            id = session.id,
            deviceId = session.deviceId,
            deviceName = session.deviceName,
            createdAt = session.createdAt,
            lastSeenAt = session.lastSeenAt,
            expiresAt = session.expiresAt,
            current = session.current,
        )

    private companion object {
        const val BEARER = "Bearer"
    }
}

data class LoginRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "Login ou e-mail; não diferencia maiúsculas.", example = "maria.silva")
    val login: String,
    @field:NotBlank
    @field:Size(max = PasswordRules.MAX_LENGTH)
    @field:Schema(description = "Senha.", format = "password")
    val password: String,
    @field:NotNull
    @field:Valid
    @field:Schema(description = "Dispositivo de onde se entra.")
    val device: DeviceRequest,
) {
    /** Nunca mostra a senha. */
    override fun toString(): String = "LoginRequest(device=$device)"
}

data class DeviceRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(
        description = "Identificador estável do dispositivo, gerado pelo cliente na instalação (um UUID).",
        example = "0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d",
    )
    val id: String,
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "Nome do dispositivo para a lista de sessões.", example = "Celular da recepção")
    val name: String,
)

data class RefreshRequest(
    @field:NotBlank
    @field:Size(max = 200)
    @field:Schema(description = "Refresh token da sessão, recebido no login ou no último refresh.")
    val refreshToken: String,
) {
    override fun toString(): String = "RefreshRequest"
}

data class ChangePasswordRequest(
    @field:NotBlank
    @field:Size(max = PasswordRules.MAX_LENGTH)
    @field:Schema(description = "Senha atual.", format = "password")
    val currentPassword: String,
    @field:NotBlank
    @field:Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH)
    @field:Schema(description = "Senha nova, de 12 a 128 caracteres.", format = "password")
    val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest"
}

data class TokenResponse(
    @field:Schema(description = "Tipo do access token, para o cabeçalho Authorization.", example = "Bearer")
    val tokenType: String,
    @field:Schema(description = "Access token JWT (RS256), válido até accessTokenExpiresAt (15 min).")
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    @field:Schema(
        description =
            "Refresh token opaco da sessão. Vale uma vez: use o novo de cada resposta. Guarde-o só no " +
                "armazenamento seguro do dispositivo.",
    )
    val refreshToken: String,
    @field:Schema(description = "Até quando a sessão renova sem uso (30 dias depois do último refresh).")
    val refreshTokenExpiresAt: Instant,
    val userId: UUID,
    val sessionId: UUID,
    @field:Schema(description = "Se o usuário precisa trocar a senha antes de usar a API.")
    val passwordChangeRequired: Boolean,
) {
    /** Nunca mostra os tokens. */
    override fun toString(): String = "TokenResponse(userId=$userId, sessionId=$sessionId)"
}

data class SessionResponse(
    val id: UUID,
    val deviceId: String,
    val deviceName: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    @field:Schema(description = "Até quando a sessão renova sem uso.")
    val expiresAt: Instant,
    @field:Schema(description = "Se é a sessão do access token desta requisição.")
    val current: Boolean,
)

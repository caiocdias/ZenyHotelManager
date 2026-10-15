package com.zeny.hotel.iam.web

import com.zeny.hotel.iam.application.CreateUserCommand
import com.zeny.hotel.iam.application.ManagementLimits
import com.zeny.hotel.iam.application.PasswordRules
import com.zeny.hotel.iam.application.PinRules
import com.zeny.hotel.iam.application.SessionView
import com.zeny.hotel.iam.application.UserManagementService
import com.zeny.hotel.iam.application.UserQueries
import com.zeny.hotel.iam.application.UserSessionsService
import com.zeny.hotel.iam.application.UserView
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Gestão de usuários pelo administrador (RF-ACS-01, 09, 10 e 12). Leitura com `iam.users.read`;
 * escrita com `iam.users.manage` (ação sensível: sem ela, `403 APPROVAL_REQUIRED` e a repetição
 * com `X-Approval-Token`); sessões de outros usuários com `iam.sessions.manage`. Usuários
 * nunca são apagados, só desativados. As edições (`PUT`) exigem o `If-Match` da última leitura.
 */
@ApiV1Controller
@RequestMapping("/users")
@Tag(name = "iam", description = "Autenticação, sessões por dispositivo e usuários.")
@Suppress("TooManyFunctions") // um método por operação do recurso
class UsersController(
    private val management: UserManagementService,
    private val queries: UserQueries,
    private val sessions: UserSessionsService,
) {
    @GetMapping
    @Operation(
        operationId = "listUsers",
        summary = "Listar usuários",
        description = "Todos os usuários, ativos e inativos, em ordem de login. Exige `iam.users.read`.",
    )
    fun list(
        @Valid page: PageParams,
    ): CursorPage<UserResponse> = queries.list(page).map(UserResponse::of)

    @PostMapping
    @Operation(
        operationId = "createUser",
        summary = "Criar um usuário",
        description =
            "Exige `iam.users.manage`. A senha é provisória: o usuário a troca no primeiro login. O PIN (4 a 8 " +
                "dígitos) é opcional. Login já usado, sem diferenciar maiúsculas: 409 `LOGIN_ALREADY_IN_USE`. As " +
                "roles seguem as regras de `PUT /users/{id}/roles`.",
    )
    @ApiResponse(responseCode = "201", description = "Usuário criado.")
    fun create(
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: CreateUserRequest,
    ): ResponseEntity<UserResponse> {
        val view =
            management.create(
                CreateUserCommand(
                    name = request.name,
                    login = request.login,
                    password = request.password,
                    pin = request.pin,
                    roleIds = request.roleIds.toSet(),
                ),
                approvalToken,
            )
        return ResponseEntity
            .created(URI.create("/api/v1/users/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(UserResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getUser",
        summary = "Consultar um usuário",
        description =
            "Exige `iam.users.read`. O `ETag` vai no `If-Match` das edições. Inexistente: 404 `USER_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<UserResponse> = ok(queries.get(UserId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateUser",
        summary = "Editar um usuário",
        description =
            "Troca o nome (o login não muda). Exige `iam.users.manage` e o `If-Match` (ausente: 428; versão " +
                "diferente: 412). Um administrador só é editado por outro administrador (403 " +
                "`ADMINISTRATOR_REQUIRED`).",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: UpdateUserRequest,
    ): ResponseEntity<UserResponse> =
        ok(management.rename(UserId(id), ETags.expectedVersion(ifMatch), request.name, approvalToken))

    @PutMapping("/{id}/roles")
    @Operation(
        operationId = "setUserRoles",
        summary = "Definir as roles de um usuário",
        description =
            "Troca o conjunto inteiro de roles do usuário. Exige `iam.users.manage` e o `If-Match`. Cada role " +
                "acrescentada não pode conceder permissão, escopo ou limite acima dos seus (403 " +
                "`GRANT_EXCEEDS_OWN_PERMISSIONS`); só um administrador dá ou tira a role Administrador e gere " +
                "administradores (403 `ADMINISTRATOR_REQUIRED`); o último administrador ativo não perde a role " +
                "(409 `LAST_ADMINISTRATOR`). Role inexistente: 400 `UNKNOWN_ROLE`. Vale na requisição seguinte.",
    )
    fun setRoles(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: SetUserRolesRequest,
    ): ResponseEntity<UserResponse> =
        ok(management.setRoles(UserId(id), ETags.expectedVersion(ifMatch), request.roleIds.toSet(), approvalToken))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateUser",
        summary = "Desativar um usuário",
        description =
            "Exige `iam.users.manage`. O usuário não entra mais, perde as permissões já na requisição seguinte e " +
                "todas as sessões dele são revogadas (o próximo refresh falha; o access token vence em até 15 " +
                "min). Nada é apagado. O último administrador ativo não é desativado (409 `LAST_ADMINISTRATOR`).",
    )
    fun deactivate(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<UserResponse> = ok(management.deactivate(UserId(id), approvalToken))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateUser",
        summary = "Reativar um usuário",
        description =
            "Exige `iam.users.manage` e cobrir as permissões do usuário (403 `GRANT_EXCEEDS_OWN_PERMISSIONS`). As " +
                "sessões revogadas não voltam: o usuário entra de novo.",
    )
    fun reactivate(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): ResponseEntity<UserResponse> = ok(management.reactivate(UserId(id), approvalToken))

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "resetUserPassword",
        summary = "Redefinir a senha de um usuário",
        description =
            "Define uma senha provisória (12 a 128 caracteres), que o usuário troca no próximo login; zera o " +
                "bloqueio por tentativas e revoga todas as sessões dele. Exige `iam.users.manage` e cobrir as " +
                "permissões do usuário (403 `GRANT_EXCEEDS_OWN_PERMISSIONS`).",
    )
    @ApiResponse(responseCode = "204", description = "Senha redefinida.")
    fun resetPassword(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: ResetPasswordRequest,
    ) = management.resetPassword(UserId(id), request.newPassword, approvalToken)

    @PostMapping("/{id}/reset-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "resetUserPin",
        summary = "Definir o PIN de aprovação de um usuário",
        description =
            "PIN de 4 a 8 dígitos, usado para aprovar ações sensíveis de outro usuário. Exige `iam.users.manage` " +
                "e cobrir as permissões do usuário (403 `GRANT_EXCEEDS_OWN_PERMISSIONS`).",
    )
    @ApiResponse(responseCode = "204", description = "PIN definido.")
    fun resetPin(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @Valid @RequestBody request: ResetPinRequest,
    ) = management.resetPin(UserId(id), request.newPin, approvalToken)

    @GetMapping("/{id}/sessions")
    @Operation(
        operationId = "listUserSessions",
        summary = "Listar as sessões ativas de um usuário",
        description =
            "Sessões não revogadas e não vencidas, da mais nova para a mais antiga. Exige `iam.sessions.manage`; " +
                "as de um administrador, só outro administrador (403 `ADMINISTRATOR_REQUIRED`).",
    )
    fun listSessions(
        @PathVariable id: UUID,
        @Valid page: PageParams,
    ): CursorPage<SessionResponse> = sessions.list(UserId(id), page).map(::toResponse)

    @PostMapping("/{id}/sessions/{sessionId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        operationId = "revokeUserSession",
        summary = "Revogar uma sessão de um usuário",
        description =
            "O refresh token da sessão deixa de valer (o access token vence em até 15 min). Revogar de novo não " +
                "muda nada. Exige `iam.sessions.manage`. Sessão de outro usuário ou inexistente: 404 " +
                "`SESSION_NOT_FOUND`.",
    )
    @ApiResponse(responseCode = "204", description = "Sessão revogada.")
    fun revokeSession(
        @PathVariable id: UUID,
        @PathVariable sessionId: UUID,
    ) = sessions.revoke(UserId(id), sessionId)

    private fun ok(view: UserView): ResponseEntity<UserResponse> =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(UserResponse.of(view))

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
}

@Schema(description = "Um usuário do sistema. Senha e PIN nunca saem.")
data class UserResponse(
    val id: UUID,
    val name: String,
    @field:Schema(description = "Login normalizado (minúsculo).")
    val login: String,
    val active: Boolean,
    @field:Schema(description = "Se o usuário tem a role de sistema Administrador.")
    val administrator: Boolean,
    @field:Schema(description = "Se o usuário tem PIN de aprovação.")
    val hasPin: Boolean,
    @field:Schema(description = "Se o usuário precisa trocar a senha no próximo login.")
    val passwordChangeRequired: Boolean,
    @field:Schema(description = "Até quando o login está bloqueado por tentativas erradas.", nullable = true)
    val lockedUntil: Instant?,
    @field:Schema(description = "Roles atribuídas, ativas ou não, em ordem de nome.")
    val roles: List<UserRoleResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: UserView) =
            UserResponse(
                id = view.id.value,
                name = view.name,
                login = view.login,
                active = view.active,
                administrator = view.administrator,
                hasPin = view.hasPin,
                passwordChangeRequired = view.passwordChangeRequired,
                lockedUntil = view.lockedUntil,
                roles = view.roles.map { UserRoleResponse(it.id.value, it.name, it.active) },
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )
    }
}

@Schema(description = "Uma role atribuída a um usuário.")
data class UserRoleResponse(
    val id: UUID,
    val name: String,
    @field:Schema(description = "Role inativa não concede nada.")
    val active: Boolean,
)

data class CreateUserRequest(
    @field:NotBlank
    @field:Size(max = ManagementLimits.USER_NAME_MAX_LENGTH)
    val name: String,
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(
        description = "Login ou e-mail: 3 a 100 caracteres (letras, dígitos e . _ @ + -); não diferencia maiúsculas.",
        example = "maria.silva",
    )
    val login: String,
    @field:NotBlank
    @field:Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH)
    @field:Schema(description = "Senha provisória, de 12 a 128 caracteres.", format = "password")
    val password: String,
    @field:Pattern(regexp = PinRules.PATTERN)
    @field:Schema(description = "PIN de aprovação, de 4 a 8 dígitos (opcional).", format = "password", nullable = true)
    val pin: String? = null,
    @field:NotNull
    @field:Size(max = MAX_ROLES)
    @field:Schema(description = "Roles do usuário (opcional).")
    val roleIds: List<UUID> = emptyList(),
) {
    /** Nunca mostra a senha nem o PIN. */
    override fun toString(): String = "CreateUserRequest(roleIds=$roleIds)"
}

data class UpdateUserRequest(
    @field:NotBlank
    @field:Size(max = ManagementLimits.USER_NAME_MAX_LENGTH)
    val name: String,
)

data class SetUserRolesRequest(
    @field:NotNull
    @field:Size(max = MAX_ROLES)
    @field:Schema(description = "O conjunto completo de roles do usuário.")
    val roleIds: List<UUID>,
)

data class ResetPasswordRequest(
    @field:NotBlank
    @field:Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH)
    @field:Schema(description = "Senha provisória, de 12 a 128 caracteres.", format = "password")
    val newPassword: String,
) {
    override fun toString(): String = "ResetPasswordRequest"
}

data class ResetPinRequest(
    @field:NotBlank
    @field:Pattern(regexp = PinRules.PATTERN)
    @field:Schema(description = "PIN de aprovação, de 4 a 8 dígitos.", format = "password")
    val newPin: String,
) {
    override fun toString(): String = "ResetPinRequest"
}

private const val MAX_ROLES = 50

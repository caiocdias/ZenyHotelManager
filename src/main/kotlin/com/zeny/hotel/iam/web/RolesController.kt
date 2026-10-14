package com.zeny.hotel.iam.web

import com.zeny.hotel.iam.application.GrantCommand
import com.zeny.hotel.iam.application.LimitCommand
import com.zeny.hotel.iam.application.ManagementLimits
import com.zeny.hotel.iam.application.RoleCommand
import com.zeny.hotel.iam.application.RoleManagementService
import com.zeny.hotel.iam.application.RoleQueries
import com.zeny.hotel.iam.application.RoleView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.RoleTemplate
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
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Gestão de roles pelo administrador (RF-ACS-03 e 09). Leitura com `iam.roles.read`; escrita com
 * `iam.roles.manage`. Roles nunca são apagadas, só desativadas; a role de sistema Administrador
 * não muda. A edição (`PUT`) exige o `If-Match` da última leitura.
 */
@ApiV1Controller
@RequestMapping("/roles")
@Tag(name = "iam", description = "Autenticação, sessões por dispositivo e usuários.")
class RolesController(
    private val management: RoleManagementService,
    private val queries: RoleQueries,
) {
    @GetMapping
    @Operation(
        operationId = "listRoles",
        summary = "Listar roles",
        description = "Todas as roles, ativas e inativas, em ordem de criação. Exige `iam.roles.read`.",
    )
    fun list(
        @Valid page: PageParams,
    ): CursorPage<RoleResponse> = queries.list(page).map(RoleResponse::of)

    @PostMapping
    @Operation(
        operationId = "createRole",
        summary = "Criar uma role",
        description =
            "Exige `iam.roles.manage`. Ninguém concede permissão, escopo ou limite acima dos seus (403 " +
                "`GRANT_EXCEEDS_OWN_PERMISSIONS`). Permissão fora do catálogo: 400 `UNKNOWN_PERMISSION`; escopo não " +
                "suportado, limite não declarado ou inválido, ou permissão repetida: 400 `INVALID_GRANT`. Nome já " +
                "usado, sem diferenciar maiúsculas: 409 `ROLE_NAME_ALREADY_IN_USE`.",
    )
    @ApiResponse(responseCode = "201", description = "Role criada.")
    fun create(
        @Valid @RequestBody request: RoleRequest,
    ): ResponseEntity<RoleResponse> = created(management.create(request.toCommand()))

    @GetMapping("/{id}")
    @Operation(
        operationId = "getRole",
        summary = "Consultar uma role",
        description =
            "Exige `iam.roles.read`. O `ETag` vai no `If-Match` da edição. Inexistente: 404 `ROLE_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RoleResponse> = ok(queries.get(id))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateRole",
        summary = "Editar uma role",
        description =
            "Troca nome, descrição e o conjunto inteiro de concessões. Exige `iam.roles.manage` e o `If-Match` " +
                "(ausente: 428; versão diferente: 412). Cada concessão acrescentada ou mudada, inclusive numa role " +
                "que o próprio usuário tem, não pode passar das permissões dele (403 " +
                "`GRANT_EXCEEDS_OWN_PERMISSIONS`). A role Administrador não é editada (422 " +
                "`SYSTEM_ROLE_IMMUTABLE`). Vale na requisição seguinte de quem tem a role.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: RoleRequest,
    ): ResponseEntity<RoleResponse> = ok(management.update(id, ETags.expectedVersion(ifMatch), request.toCommand()))

    @PostMapping("/{id}/duplicate")
    @Operation(
        operationId = "duplicateRole",
        summary = "Duplicar uma role",
        description =
            "Cria uma role nova com as concessões vigentes da outra. Exige `iam.roles.manage` e ter todas elas (403 " +
                "`GRANT_EXCEEDS_OWN_PERMISSIONS`).",
    )
    @ApiResponse(responseCode = "201", description = "Role criada.")
    fun duplicate(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DuplicateRoleRequest,
    ): ResponseEntity<RoleResponse> = created(management.duplicate(id, request.name, request.description))

    @PostMapping("/{id}/deactivate")
    @Operation(
        operationId = "deactivateRole",
        summary = "Desativar uma role",
        description =
            "A role deixa de conceder permissões já na requisição seguinte de quem a tem; nada é apagado. Exige " +
                "`iam.roles.manage`. A role Administrador não é desativada (422 `SYSTEM_ROLE_IMMUTABLE`).",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoleResponse> = ok(management.deactivate(id))

    @PostMapping("/{id}/reactivate")
    @Operation(
        operationId = "reactivateRole",
        summary = "Reativar uma role",
        description =
            "As concessões da role voltam a valer. Exige `iam.roles.manage` e ter todas elas (403 " +
                "`GRANT_EXCEEDS_OWN_PERMISSIONS`).",
    )
    fun reactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<RoleResponse> = ok(management.reactivate(id))

    private fun ok(view: RoleView): ResponseEntity<RoleResponse> =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(RoleResponse.of(view))

    private fun created(view: RoleView): ResponseEntity<RoleResponse> =
        ResponseEntity
            .created(URI.create("/api/v1/roles/${view.id.value}"))
            .eTag(ETags.of(view.version))
            .body(RoleResponse.of(view))
}

@Schema(description = "Uma role: um conjunto de concessões de permissões do catálogo.")
data class RoleResponse(
    val id: UUID,
    val name: String,
    val description: String,
    @field:Schema(description = "Role inativa não concede nada.")
    val active: Boolean,
    @field:Schema(description = "Modelo da SPEC §3.1 de onde a role veio, se veio de um.", nullable = true)
    val template: RoleTemplate?,
    @field:Schema(description = "A role de sistema Administrador: todas as permissões, sem edição.")
    val systemRole: Boolean,
    @field:Schema(description = "Se a role de modelo foi editada (não recebe mais as sugestões do catálogo).")
    val customized: Boolean,
    @field:Schema(description = "Concessões de permissões vigentes, em ordem de código.")
    val grants: List<RoleGrantResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: RoleView) =
            RoleResponse(
                id = view.id.value,
                name = view.name,
                description = view.description,
                active = view.active,
                template = view.template,
                systemRole = view.systemRole,
                customized = view.customized,
                grants =
                    view.grants.map { grant ->
                        RoleGrantResponse(
                            permission = grant.code.value,
                            scope = grant.scope,
                            limits =
                                grant.limits.toSortedMap().map { (key, value) ->
                                    LimitValueResponse.of(key, value)
                                },
                        )
                    },
                createdAt = view.createdAt,
                updatedAt = view.updatedAt,
            )
    }
}

@Schema(description = "Uma concessão: a permissão, o escopo e os limites.")
data class RoleGrantResponse(
    @field:Schema(example = "iam.users.read")
    val permission: String,
    val scope: PermissionScope,
    @field:Schema(description = "Por chave declarada; uma chave sem valor vale teto zero.")
    val limits: List<LimitValueResponse>,
)

data class RoleRequest(
    @field:NotBlank
    @field:Size(max = ManagementLimits.ROLE_NAME_MAX_LENGTH)
    @field:Schema(description = "Único, sem diferenciar maiúsculas.", example = "Recepção noturna")
    val name: String,
    @field:NotNull
    @field:Size(max = ManagementLimits.ROLE_DESCRIPTION_MAX_LENGTH)
    val description: String = "",
    @field:NotNull
    @field:Valid
    @field:Size(max = MAX_GRANTS)
    @field:Schema(description = "O conjunto completo das concessões da role.")
    val grants: List<RoleGrantRequest> = emptyList(),
) {
    fun toCommand() = RoleCommand(name, description, grants.map(RoleGrantRequest::toCommand))
}

data class RoleGrantRequest(
    @field:NotBlank
    @field:Size(max = PermissionCode.MAX_LENGTH)
    @field:Schema(description = "Código de uma permissão do catálogo (`GET /permissions`).", example = "iam.users.read")
    val permission: String,
    @field:NotNull
    @field:Schema(description = "Um dos escopos que a permissão suporta.")
    val scope: PermissionScope,
    @field:NotNull
    @field:Valid
    @field:Size(max = MAX_LIMITS)
    @field:Schema(description = "Limites, só das chaves que a permissão declara; chave omitida vale teto zero.")
    val limits: List<LimitRequest> = emptyList(),
) {
    fun toCommand() = GrantCommand(permission, scope, limits.map(LimitRequest::toCommand))
}

@Schema(description = "O limite de uma chave: um teto (max) ou sem teto (unlimited), exatamente um dos dois.")
data class LimitRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(example = "maxPercent")
    val key: String,
    @field:Schema(description = "Sem teto.")
    val unlimited: Boolean = false,
    @field:Pattern(regexp = "\\d{1,14}(\\.\\d{1,4})?")
    @field:Schema(
        description = "O teto, como decimal em string, na unidade do limite.",
        example = "10.5",
        nullable = true,
    )
    val max: String? = null,
) {
    fun toCommand() = LimitCommand(key, unlimited, max?.toBigDecimal())
}

data class DuplicateRoleRequest(
    @field:NotBlank
    @field:Size(max = ManagementLimits.ROLE_NAME_MAX_LENGTH)
    @field:Schema(description = "Nome da role nova.")
    val name: String,
    @field:Size(max = ManagementLimits.ROLE_DESCRIPTION_MAX_LENGTH)
    @field:Schema(description = "Descrição da role nova; sem ela, a da original.", nullable = true)
    val description: String? = null,
)

private const val MAX_GRANTS = 500
private const val MAX_LIMITS = 20

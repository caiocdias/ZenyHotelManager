package com.zeny.hotel.iam.web

import com.zeny.hotel.iam.application.PermissionCatalogQueries
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/** Catálogo de permissões (RF-ACS-02), para o cliente montar a tela de roles. */
@ApiV1Controller
@RequestMapping("/permissions")
@Tag(name = "iam", description = "Autenticação, sessões por dispositivo e usuários.")
class PermissionsController(
    private val queries: PermissionCatalogQueries,
) {
    @GetMapping
    @Operation(
        operationId = "listPermissions",
        summary = "Listar o catálogo de permissões",
        description =
            "Permissões vigentes do catálogo, definido pelo sistema e não editável, em ordem de código: escopos " +
                "suportados, se é sensível (exige aprovação de superior quando falta) e os limites que aceita. " +
                "Exige `iam.permissions.read` (sem ela, 403 `PERMISSION_DENIED`).",
    )
    fun list(
        @Valid page: PageParams,
    ): CursorPage<PermissionResponse> = queries.list(page).map(::toResponse)

    private fun toResponse(definition: PermissionDefinition) =
        PermissionResponse(
            code = definition.code.value,
            module = definition.code.module,
            description = definition.description,
            scopes = definition.scopes.sortedBy { it.breadth },
            sensitive = definition.sensitive,
            limits = definition.limits.map { LimitDefinitionResponse(it.key, it.unit, it.description) },
        )
}

@Schema(description = "Uma permissão do catálogo.")
data class PermissionResponse(
    @field:Schema(description = "Código estável da permissão: o módulo e a ação.", example = "iam.users.manage")
    val code: String,
    @field:Schema(description = "Módulo dono da permissão.", example = "iam")
    val module: String,
    @field:Schema(description = "Descrição para a tela de roles.")
    val description: String,
    @field:Schema(description = "Escopos que uma concessão pode ter: OWN (só os próprios registros) e ALL.")
    val scopes: List<PermissionScope>,
    @field:Schema(description = "Ação sensível: sem a permissão, exige aprovação de superior (RF-ACS-08).")
    val sensitive: Boolean,
    @field:Schema(description = "Parâmetros de limite que uma concessão aceita.")
    val limits: List<LimitDefinitionResponse>,
)

@Schema(description = "Um parâmetro de limite que a permissão aceita.")
data class LimitDefinitionResponse(
    @field:Schema(description = "Chave do limite na concessão.", example = "maxPercent")
    val key: String,
    @field:Schema(
        description =
            "MONEY: reais, até 2 casas; PERCENT: pontos percentuais de 0 a 100, até 4 casas; " +
                "QUANTITY: inteiro.",
    )
    val unit: LimitUnit,
    val description: String,
)

/** Uma permissão efetiva do usuário corrente. */
@Schema(description = "Uma permissão efetiva do usuário corrente.")
data class EffectivePermissionResponse(
    @field:Schema(example = "iam.users.manage")
    val code: String,
    @field:Schema(description = "O escopo mais amplo entre as roles do usuário.")
    val scope: PermissionScope,
    @field:Schema(description = "Por chave, o maior limite entre as roles do usuário.")
    val limits: List<LimitValueResponse>,
) {
    companion object {
        fun of(granted: GrantedPermission) =
            EffectivePermissionResponse(
                code = granted.code.value,
                scope = granted.scope,
                limits = granted.limits.toSortedMap().map { (key, value) -> LimitValueResponse.of(key, value) },
            )
    }
}

@Schema(description = "O limite efetivo de uma chave: sem teto ou o teto.")
data class LimitValueResponse(
    @field:Schema(example = "maxPercent")
    val key: String,
    @field:Schema(description = "Sem teto.")
    val unlimited: Boolean,
    @field:Schema(
        description = "O teto, como decimal em string; nulo quando unlimited.",
        example = "10.5",
        nullable = true,
    )
    val max: String?,
) {
    companion object {
        fun of(
            key: String,
            value: LimitValue,
        ) = when (value) {
            LimitValue.Unlimited -> LimitValueResponse(key, unlimited = true, max = null)
            is LimitValue.Max -> LimitValueResponse(key, unlimited = false, max = value.value.toPlainString())
        }
    }
}

package com.zeny.hotel.iam.domain

import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition

/** As concessões de uma role atribuída ao usuário, com o estado da role. */
data class RoleGrants(
    val roleActive: Boolean,
    val grants: List<Grant>,
)

/**
 * Permissões efetivas de um usuário (RF-ACS-04, ARCHITECTURE §8.2): a união das concessões das
 * roles **ativas** dele. Por permissão, prevalece o escopo mais amplo; por chave de limite, o
 * maior valor (sem teto vence qualquer teto). Só contam permissões do catálogo vigente: uma
 * permissão obsoleta, um escopo que ela não suporta mais e uma chave de limite que ela não
 * declara mais são ignorados. Usuário inativo não tem permissão nenhuma.
 */
class EffectivePermissions private constructor(
    private val byCode: Map<PermissionCode, GrantedPermission>,
) {
    /** As permissões efetivas, por código. */
    val all: List<GrantedPermission> get() = byCode.values.sortedBy { it.code.value }

    fun of(permission: PermissionCode): GrantedPermission? = byCode[permission]

    /**
     * Se estas permissões cobrem a concessão [requested] (RF-ACS-09: ninguém concede o que não
     * tem): a mesma permissão, com escopo ao menos tão amplo e, em cada chave de limite pedida,
     * um limite ao menos tão alto (uma chave sem valor aqui vale teto zero).
     */
    fun covers(requested: GrantedPermission): Boolean {
        val own = of(requested.code) ?: return false
        return own.scope.breadth >= requested.scope.breadth &&
            requested.limits.all { (key, value) ->
                val mine = own.limit(key)
                LimitValue.max(value, mine) == mine
            }
    }

    /** As concessões de [requested] que estas permissões não cobrem ([covers]). */
    fun uncovered(requested: EffectivePermissions): List<GrantedPermission> = requested.all.filterNot(::covers)

    override fun toString(): String = "EffectivePermissions(${byCode.keys.sortedBy { it.value }})"

    companion object {
        val NONE = EffectivePermissions(emptyMap())

        /**
         * O que um conjunto de concessões concede de fato pelo catálogo vigente: as mesmas regras
         * da união (permissão obsoleta, escopo não suportado e chave não declarada não contam).
         */
        fun of(
            grants: Collection<Grant>,
            catalog: PermissionCatalog,
        ): EffectivePermissions = union(userActive = true, listOf(RoleGrants(true, grants.toList())), catalog)

        fun union(
            userActive: Boolean,
            roles: Collection<RoleGrants>,
            catalog: PermissionCatalog,
        ): EffectivePermissions {
            if (!userActive) return NONE
            val result = mutableMapOf<PermissionCode, GrantedPermission>()
            roles
                .filter { it.roleActive }
                .flatMap { it.grants }
                .forEach { grant ->
                    val definition = catalog.definitionOf(grant.permission) ?: return@forEach
                    if (grant.scope !in definition.scopes) return@forEach
                    val granted = GrantedPermission(grant.permission, grant.scope, declaredLimits(grant, definition))
                    result.merge(grant.permission, granted, ::wider)
                }
            return EffectivePermissions(result)
        }

        private fun declaredLimits(
            grant: Grant,
            definition: PermissionDefinition,
        ): Map<String, LimitValue> =
            grant.limits.filter { (key, value) -> definition.limitOf(key)?.accepts(value) == true }

        private fun wider(
            a: GrantedPermission,
            b: GrantedPermission,
        ): GrantedPermission {
            val limits = a.limits.toMutableMap()
            b.limits.forEach { (key, value) -> limits.merge(key, value, LimitValue::max) }
            val scope = if (b.scope.breadth > a.scope.breadth) b.scope else a.scope
            return GrantedPermission(a.code, scope, limits)
        }
    }
}

/**
 * Catálogo de permissões declarado em código pelos módulos (RF-ACS-02, ARCHITECTURE §8.2). Recusa
 * módulo repetido, código que não começa pelo nome do módulo e código repetido.
 */
class PermissionCatalog private constructor(
    val definitions: Map<PermissionCode, PermissionDefinition>,
) {
    fun definitionOf(permission: PermissionCode): PermissionDefinition? = definitions[permission]

    operator fun contains(permission: PermissionCode): Boolean = permission in definitions

    companion object {
        private val MODULE_NAME = Regex("[a-z][a-z0-9]*")

        fun of(modules: Collection<ModulePermissions>): PermissionCatalog {
            val duplicatedModules = modules.groupBy { it.module }.filterValues { it.size > 1 }.keys
            require(
                duplicatedModules.isEmpty(),
            ) { "Módulos com mais de uma declaração de permissões: $duplicatedModules" }
            val definitions = mutableMapOf<PermissionCode, PermissionDefinition>()
            modules.forEach { declaration ->
                require(MODULE_NAME.matches(declaration.module)) { "Nome de módulo inválido: '${declaration.module}'" }
                declaration.permissions.forEach { definition ->
                    require(definition.code.module == declaration.module) {
                        "A permissão ${definition.code} não começa pelo módulo '${declaration.module}'"
                    }
                    require(definitions.put(definition.code, definition) == null) {
                        "Permissão ${definition.code} declarada mais de uma vez"
                    }
                }
            }
            return PermissionCatalog(definitions)
        }

        /** Catálogo a partir de definições já validadas (as da tabela, sincronizada no boot). */
        fun ofDefinitions(definitions: Collection<PermissionDefinition>): PermissionCatalog =
            PermissionCatalog(definitions.associateBy { it.code })
    }
}

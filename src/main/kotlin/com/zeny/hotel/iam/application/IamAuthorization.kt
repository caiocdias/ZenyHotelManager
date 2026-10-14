package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.domain.EffectivePermissions
import com.zeny.hotel.iam.domain.PermissionCatalog
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.GrantedPermission
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.PermissionScope
import com.zeny.hotel.shared.ScopeFilter
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Permissões efetivas por usuário (RF-ACS-04), em cache na memória e invalidadas por versão
 * (ARCHITECTURE §8.2): cada entrada guarda a versão das autorizações em que foi calculada, e cada
 * consulta compara com a versão corrente no banco (uma leitura de uma linha). Mudou uma role, uma
 * concessão, uma atribuição, o catálogo ou o estado ativo de um usuário, a versão muda e a próxima
 * consulta recalcula: a mudança vale já na requisição seguinte, sem novo login. A versão é lida
 * **antes** dos dados, então uma mudança que chegue no meio do cálculo sempre deixa a entrada
 * velha. Há uma única instância da aplicação (ARCHITECTURE §10); como a versão fica no banco, o
 * cache vale também com mais de uma.
 */
@Service
class EffectivePermissionService(
    private val data: AuthorizationData,
    private val store: PermissionStore,
) {
    private val cache = ConcurrentHashMap<UserId, CachedPermissions>()

    fun of(userId: UserId): EffectivePermissions {
        val version = data.currentVersion()
        cache[userId]?.takeIf { it.version == version }?.let { return it.permissions }
        val permissions =
            EffectivePermissions.union(
                userActive = data.isUserActive(userId),
                roles = data.roleGrantsOf(userId),
                catalog = PermissionCatalog.ofDefinitions(store.activeDefinitions()),
            )
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[userId] = CachedPermissions(version, permissions)
        return permissions
    }

    private class CachedPermissions(
        val version: Long,
        val permissions: EffectivePermissions,
    )

    private companion object {
        /** Bem acima do número de usuários de um hotel de 30 UHs; só evita crescer sem limite. */
        const val MAX_ENTRIES = 1_000
    }
}

/**
 * Implementação da porta [Authorization] do `shared` com as permissões efetivas do ator corrente
 * (ARCHITECTURE §8.2). As recusas saem como `403` com código estável: `PERMISSION_DENIED` sem a
 * permissão e `RESOURCE_OUT_OF_SCOPE` com escopo `OWN` sobre registro alheio.
 */
@Service
class IamAuthorization(
    private val currentActor: CurrentActor,
    private val permissions: EffectivePermissionService,
) : Authorization {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun require(permission: PermissionCode): GrantedPermission {
        val userId = currentActor.current().userId
        return permissions.of(userId).of(permission) ?: run {
            log.info("Permissão {} negada ao usuário {}", permission, userId)
            throw PermissionDeniedException(
                IamErrorCodes.PERMISSION_DENIED,
                "Você não tem permissão para esta ação.",
            )
        }
    }

    override fun require(
        permission: PermissionCode,
        resource: OwnedResource,
    ): GrantedPermission {
        val granted = require(permission)
        val userId = currentActor.current().userId
        if (granted.scope == PermissionScope.OWN && userId !in resource.ownerIds) {
            log.info("Permissão {} do usuário {} não alcança registro de outro usuário", permission, userId)
            throw PermissionDeniedException(
                IamErrorCodes.RESOURCE_OUT_OF_SCOPE,
                "Sua permissão vale só para os registros atribuídos a você ou criados por você.",
            )
        }
        return granted
    }

    override fun grantOrNull(permission: PermissionCode): GrantedPermission? =
        permissions.of(currentActor.current().userId).of(permission)

    override fun scopeFilter(permission: PermissionCode): ScopeFilter =
        when (require(permission).scope) {
            PermissionScope.ALL -> ScopeFilter.All
            PermissionScope.OWN -> ScopeFilter.Own(currentActor.current().userId)
        }
}

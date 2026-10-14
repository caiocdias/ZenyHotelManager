package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.domain.Role
import com.zeny.hotel.iam.domain.RoleGrants
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.UserId
import java.time.Instant

/** Roles persistidas. */
interface Roles {
    fun findById(id: RoleId): Role?

    fun findByTemplate(template: RoleTemplate): Role?

    fun add(role: Role)
}

/** Uma permissão como está na tabela do catálogo. */
data class StoredPermission(
    val definition: PermissionDefinition,
    val obsolete: Boolean,
)

/**
 * A tabela do catálogo de permissões (`iam.permission`), sincronizada com o código no boot. Guarda
 * código, descrição, escopos, se é sensível e os limites; as sugestões aos modelos de role ficam
 * só no código.
 */
interface PermissionStore {
    /** Trava a sincronização até o fim da transação (duas instâncias subindo juntas). */
    fun lockForSynchronization()

    fun findAll(): List<StoredPermission>

    fun insert(
        definition: PermissionDefinition,
        now: Instant,
    )

    /** Regrava os dados da permissão e a tira do estado obsoleto. */
    fun update(
        definition: PermissionDefinition,
        now: Instant,
    )

    fun markObsolete(
        code: PermissionCode,
        now: Instant,
    )

    /** As permissões vigentes (não obsoletas). */
    fun activeDefinitions(): List<PermissionDefinition>

    /** Uma página das vigentes, em ordem de código, depois de [afterCode]. */
    fun activePage(
        afterCode: String?,
        limit: Int,
    ): List<PermissionDefinition>
}

/** Uma role atribuída ao usuário, como ele a vê. */
data class AssignedRole(
    val id: RoleId,
    val name: String,
)

/** Leitura dos dados que formam as permissões efetivas (fora do JWT, ARCHITECTURE §8.2). */
interface AuthorizationData {
    /**
     * A versão corrente das autorizações. Toda mudança em permissão, role, concessão, limite,
     * atribuição ou no estado ativo de um usuário a troca, na mesma transação, por um valor
     * nunca usado antes (inclusive depois de um rollback).
     */
    fun currentVersion(): Long

    /** Se o usuário existe e está ativo. */
    fun isUserActive(userId: UserId): Boolean

    /** As roles atribuídas ao usuário (ativas ou não), com as concessões. */
    fun roleGrantsOf(userId: UserId): List<RoleGrants>

    /** As roles ativas atribuídas ao usuário, em ordem de nome. */
    fun activeRolesOf(userId: UserId): List<AssignedRole>
}

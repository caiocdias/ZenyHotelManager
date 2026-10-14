package com.zeny.hotel.iam.application

import com.zeny.hotel.iam.IamErrorCodes
import com.zeny.hotel.iam.domain.EffectivePermissions
import com.zeny.hotel.iam.domain.Grant
import com.zeny.hotel.iam.domain.PermissionCatalog
import com.zeny.hotel.iam.domain.RoleGrants
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.shared.Actor
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * As regras de delegação da gestão de usuários e roles (RF-ACS-09), conferidas nos casos de uso
 * depois da permissão do catálogo:
 *
 * - **ninguém concede o que não tem:** cada concessão que passa a valer (acrescentada ou mudada
 *   numa role, copiada na duplicação, reativada com a role, ou recebida por um usuário ao ganhar
 *   uma role) precisa estar coberta pelas permissões efetivas do ator: a mesma permissão, escopo
 *   ao menos tão amplo e limite ao menos tão alto em cada chave. Vale também para a própria role.
 *   Redefinir a senha ou o PIN de alguém e reativá-lo dá acesso a tudo o que ele tem, então o
 *   ator também precisa cobrir as permissões dele (de todas as roles atribuídas, ativas ou não);
 * - **a união final também:** como a permissão efetiva é a união das roles (RF-ACS-04), duas
 *   concessões cobertas uma a uma podem somar uma combinação que o ator não tem (`ALL` com teto
 *   zero de uma role e `OWN` com teto 100 de outra viram `ALL` com teto 100). Por isso, para cada
 *   usuário cujas roles a operação muda (atribuição, criação com roles, edição ou reativação de
 *   uma role que ele tem), toda permissão efetiva que a operação **amplia** (nova, escopo mais
 *   amplo ou algum limite maior que antes) precisa estar coberta inteira (escopo e cada limite)
 *   pelas permissões do ator. O que já existia e a operação não amplia não é conferido. A união é
 *   calculada como se o usuário estivesse ativo e só com as roles ativas: reativar uma role é uma
 *   ampliação, conferida na reativação;
 * - **só administrador gere administrador:** quem não tem a role de sistema Administrador não
 *   altera, desativa, reativa, redefine senha ou PIN nem mexe nas sessões de quem a tem, e não a
 *   dá nem tira de ninguém.
 *
 * As recusas são `403` com código estável: `GRANT_EXCEEDS_OWN_PERMISSIONS` e
 * `ADMINISTRATOR_REQUIRED`.
 */
@Component
class DelegationRules(
    private val data: AuthorizationData,
    private val permissions: EffectivePermissionService,
    private val store: PermissionStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** O catálogo vigente. */
    fun catalog(): PermissionCatalog = PermissionCatalog.ofDefinitions(store.activeDefinitions())

    fun isAdministrator(userId: UserId): Boolean = data.isAdministrator(userId)

    /** Exige que o ator seja administrador. */
    fun requireAdministrator(actor: Actor) {
        if (!data.isAdministrator(actor.userId)) {
            log.info("Usuário {} recusado: só um administrador gerencia administradores", actor.userId)
            throw PermissionDeniedException(
                IamErrorCodes.ADMINISTRATOR_REQUIRED,
                "Só um administrador gerencia administradores.",
            )
        }
    }

    /** Se [target] é administrador, exige que o ator também seja. */
    fun requireCanManage(
        actor: Actor,
        target: UserId,
    ) {
        if (data.isAdministrator(target)) requireAdministrator(actor)
    }

    /** Exige que as permissões efetivas do ator cubram as [grants] (pelo catálogo vigente). */
    fun requireHeld(
        actor: Actor,
        grants: Collection<Grant>,
        catalog: PermissionCatalog = catalog(),
        message: String = "Você não pode conceder permissão, escopo ou limite acima dos seus.",
    ) {
        if (grants.isEmpty()) return
        val exceeding = permissions.of(actor.userId).uncovered(EffectivePermissions.of(grants, catalog))
        if (exceeding.isNotEmpty()) {
            val codes = exceeding.map { it.code.value }.sorted()
            log.info("Usuário {} recusado: concessão acima das próprias permissões {}", actor.userId, codes)
            throw PermissionDeniedException(
                IamErrorCodes.GRANT_EXCEEDS_OWN_PERMISSIONS,
                "$message Acima das suas: ${codes.joinToString(", ")}.",
            )
        }
    }

    /**
     * Exige que o ator cubra tudo o que as roles de [target] concedem, ativas ou não (uma role
     * inativa pode ser reativada depois). Cobrir cada concessão cobre também qualquer união delas.
     */
    fun requireHoldsWhatUserHas(
        actor: Actor,
        target: UserId,
    ) = requireHeld(
        actor,
        data.roleGrantsOf(target).flatMap { it.grants },
        message = "Este usuário tem permissões acima das suas.",
    )

    /** As roles atribuídas ao usuário agora, por role (o "antes" de uma [RolesChange]). */
    fun rolesOf(userId: UserId): Map<RoleId, RoleGrants> = data.roleGrantsByRoleOf(userId)

    /** Os usuários que têm a role. */
    fun holdersOf(roleId: RoleId): List<UserId> = data.holdersOf(roleId)

    /**
     * União final: para cada mudança, as permissões efetivas que o "depois" amplia em relação ao
     * "antes" precisam estar cobertas inteiras pelas permissões do ator.
     */
    fun requireResultingUnionsHeld(
        actor: Actor,
        changes: Collection<RolesChange>,
        catalog: PermissionCatalog = catalog(),
    ) {
        if (changes.isEmpty()) return
        val own = permissions.of(actor.userId)
        val exceeding =
            changes
                .flatMap { change ->
                    val before = change.effectiveBefore(catalog)
                    before.uncovered(change.effectiveAfter(catalog))
                }.filterNot(own::covers)
        if (exceeding.isNotEmpty()) {
            val codes = exceeding.map { it.code.value }.distinct().sorted()
            log.info("Usuário {} recusado: a união resultante passaria das próprias permissões {}", actor.userId, codes)
            throw PermissionDeniedException(
                IamErrorCodes.GRANT_EXCEEDS_OWN_PERMISSIONS,
                "A operação daria a um usuário, somada às roles que ele já tem, permissão, escopo ou limite acima " +
                    "dos seus. Acima das suas: ${codes.joinToString(", ")}.",
            )
        }
    }
}

/** As roles de um usuário antes e depois de uma operação, por role. */
data class RolesChange(
    val before: Map<RoleId, RoleGrants>,
    val after: Map<RoleId, RoleGrants>,
) {
    fun effectiveBefore(catalog: PermissionCatalog): EffectivePermissions = effective(before, catalog)

    fun effectiveAfter(catalog: PermissionCatalog): EffectivePermissions = effective(after, catalog)

    private fun effective(
        roles: Map<RoleId, RoleGrants>,
        catalog: PermissionCatalog,
    ) = EffectivePermissions.union(userActive = true, roles = roles.values, catalog = catalog)
}

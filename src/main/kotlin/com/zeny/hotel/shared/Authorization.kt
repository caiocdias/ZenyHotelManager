package com.zeny.hotel.shared

import java.util.UUID

/**
 * Uma permissão efetiva do usuário corrente (RF-ACS-04): a união das concessões das roles
 * ativas dele, com o escopo mais amplo e, por chave, o maior limite.
 */
data class GrantedPermission(
    val code: PermissionCode,
    val scope: PermissionScope,
    val limits: Map<String, LimitValue> = emptyMap(),
) {
    /** O limite da chave; sem valor concedido, [LimitValue.NONE] (teto zero). */
    fun limit(key: String): LimitValue = limits[key] ?: LimitValue.NONE
}

/**
 * Recurso sujeito ao escopo `OWN` (RF-ACS-05): os usuários a quem ele pertence, por atribuição
 * ou por autoria. Um agregado pode implementar a interface ou usar [of].
 */
interface OwnedResource {
    val ownerIds: Set<UserId>

    companion object {
        /** Recurso do dono e, se houver, de outro (atribuído a e criado por); nulos ignorados. */
        fun of(
            owner: UserId?,
            other: UserId? = null,
        ): OwnedResource = of(listOf(owner, other))

        /** Recurso dos donos informados (nulos ignorados). */
        fun of(owners: Collection<UserId?>): OwnedResource {
            val ids = owners.filterNotNull().toSet()
            return object : OwnedResource {
                override val ownerIds = ids
            }
        }
    }
}

/**
 * Filtro de escopo para consultas (ARCHITECTURE §8.2): com `ALL`, nenhum; com `OWN`, só os
 * registros do usuário. Aplique na própria consulta, nunca filtrando a lista depois de paginar.
 */
sealed interface ScopeFilter {
    /** O id do dono para a consulta, ou `null` sem filtro. */
    val ownerId: UUID?

    data object All : ScopeFilter {
        override val ownerId: UUID? = null
    }

    data class Own(
        val userId: UserId,
    ) : ScopeFilter {
        override val ownerId: UUID = userId.value
    }
}

/**
 * Porta de autorização, parte do contexto de segurança do kernel (ARCHITECTURE §5 e §8.2): os
 * casos de uso (camada `application`) de qualquer módulo a chamam sem depender de `iam`, que a
 * implementa com as permissões efetivas do ator corrente ([CurrentActor]), em cache invalidado
 * por versão: remover uma role ou uma concessão vale já na requisição seguinte. As permissões
 * nunca vêm do token.
 *
 * Sem ator, `401 UNAUTHENTICATED`. Sem a permissão (ou com ela obsoleta), `403 PERMISSION_DENIED`;
 * com escopo `OWN` sobre recurso alheio, `403 RESOURCE_OUT_OF_SCOPE` (códigos do `iam`).
 */
interface Authorization {
    /** Exige a permissão, em qualquer escopo; devolve a concessão efetiva (escopo e limites). */
    fun require(permission: PermissionCode): GrantedPermission

    /**
     * Exige a permissão sobre [resource]: com escopo `ALL` passa; com `OWN`, só se o ator está
     * entre os donos do recurso. Use nos comandos sobre um registro.
     */
    fun require(
        permission: PermissionCode,
        resource: OwnedResource,
    ): GrantedPermission

    /** A concessão efetiva, ou `null` sem a permissão (para decidir sem recusar). */
    fun grantOrNull(permission: PermissionCode): GrantedPermission?

    /** Exige a permissão e devolve o filtro de escopo das consultas (`OWN` → só o ator). */
    fun scopeFilter(permission: PermissionCode): ScopeFilter

    /** Exige a permissão e devolve o limite da chave (validado no domínio, ARCHITECTURE §8.2). */
    fun limit(
        permission: PermissionCode,
        key: String,
    ): LimitValue = require(permission).limit(key)

    /**
     * Autoriza uma ação sensível (RF-ACS-07 e 08, ARCHITECTURE §6.3): pela permissão do usuário
     * corrente ou por uma aprovação de superior.
     *
     * - Com a permissão, no escopo do [SensitiveAction.resource] e com o limite cobrindo o
     *   [SensitiveAction.limit]: [SensitiveAuthorization.ByPermission]; um token presente é
     *   ignorado (não é consumido).
     * - Permissão não sensível: as recusas de sempre (`403 PERMISSION_DENIED`,
     *   `403 RESOURCE_OUT_OF_SCOPE` ou `422 LIMIT_EXCEEDED`).
     * - Sensível, sem [approvalToken]: `403 APPROVAL_REQUIRED` ([ApprovalRequiredException]).
     * - Sensível, com [approvalToken]: consome o token, de uso único, conferindo que é do mesmo
     *   solicitante e sessão, da mesma ação, permissão, referência e valor de limite, que não
     *   venceu (2 min) e que o aprovador ainda tem a permissão (escopo e limite);
     *   [SensitiveAuthorization.ByApproval]. As recusas saem com `403` e código estável
     *   (`APPROVAL_TOKEN_*`, `APPROVER_NOT_ALLOWED`).
     *
     * O consumo grava na transação corrente e marca a aprovação nas entradas de auditoria do caso
     * de uso: chame dentro de um método `@Audited` com transação de escrita (fora dele, falha com
     * `IllegalStateException`). Se a transação desfizer, o token volta a valer até vencer.
     */
    fun requireOrApproval(
        action: SensitiveAction,
        approvalToken: String?,
    ): SensitiveAuthorization
}

package com.zeny.hotel.shared

import java.math.BigDecimal
import java.util.UUID

/**
 * Cabeçalho com o token de aprovação de superior (step-up, RF-ACS-07, ARCHITECTURE §6.3): o
 * cliente repete a requisição recusada com `403 APPROVAL_REQUIRED` levando o token obtido em
 * `POST /api/v1/auth/approvals`. O controller da ação sensível o recebe com
 * `@RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?` e o repassa ao
 * caso de uso, que o entrega a [Authorization.requireOrApproval].
 */
const val APPROVAL_TOKEN_HEADER = "X-Approval-Token"

/** O valor que uma ação pede de um limite da permissão (RF-ACS-06), como `maxPercent` = 15. */
data class LimitAmount(
    val key: String,
    val amount: BigDecimal,
) {
    init {
        require(amount.signum() >= 0) { "Valor de limite negativo em '$key'" }
    }
}

/**
 * Uma ação sensível (RF-ACS-08) pedida pelo usuário corrente, como a autorização a confere e a
 * aprovação de superior a vincula:
 *
 * - [action]: código estável da operação, `<módulo>.<entidade>.<verbo>` (o mesmo do `@Audited`
 *   do caso de uso, como `iam.user.deactivate`);
 * - [permission]: a permissão do catálogo que a operação exige (sensível, para aceitar aprovação);
 * - [resourceRef]: referência ao registro sobre o qual a operação age (`<tipo>:<id>`, como
 *   `iam.user:0199…`). Vai ao cliente: sem dado pessoal nem segredo;
 * - [resource]: os donos do registro, para o escopo `OWN` (do solicitante e do aprovador);
 * - [limit]: o valor pedido de um limite da permissão, quando a operação tem um.
 *
 * O token de aprovação vale só para a mesma ação, permissão, referência e valor de limite.
 */
data class SensitiveAction(
    val action: String,
    val permission: PermissionCode,
    val resourceRef: String,
    val resource: OwnedResource? = null,
    val limit: LimitAmount? = null,
) {
    init {
        require(action.length <= MAX_ACTION_LENGTH && ACTION_PATTERN.matches(action)) {
            "Ação sensível fora do padrão <módulo>.<entidade>.<verbo>: '$action'"
        }
        require(resourceRef.isNotBlank() && resourceRef.length <= MAX_RESOURCE_REF_LENGTH) {
            "Referência de recurso inválida para a ação $action"
        }
    }

    companion object {
        const val MAX_ACTION_LENGTH = 100
        const val MAX_RESOURCE_REF_LENGTH = 200

        /** O padrão das ações (o mesmo das ações de auditoria). */
        const val ACTION_REGEX = "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$"

        private val ACTION_PATTERN = Regex(ACTION_REGEX)
    }
}

/** Como uma ação sensível foi autorizada ([Authorization.requireOrApproval]). */
sealed interface SensitiveAuthorization {
    /** Pela permissão do próprio usuário, no escopo e dentro do limite. */
    data class ByPermission(
        val granted: GrantedPermission,
    ) : SensitiveAuthorization

    /** Por uma aprovação de superior, consumida nesta transação e referenciada na auditoria. */
    data class ByApproval(
        val approvalId: UUID,
        val approverId: UserId,
    ) : SensitiveAuthorization
}

/**
 * `403 APPROVAL_REQUIRED` (RF-ACS-07): a ação sensível pede a aprovação de um superior. O
 * problema leva `action`, `permission`, `resourceRef` e, se houver, `limit` (`key` e `amount`, este
 * como texto decimal), que o cliente repassa a `POST /api/v1/auth/approvals`.
 */
class ApprovalRequiredException(
    val sensitiveAction: SensitiveAction,
) : PermissionDeniedException(
        ErrorCodes.APPROVAL_REQUIRED,
        "Esta ação precisa da aprovação de um superior.",
    ) {
    override val problemProperties: Map<String, Any>
        get() =
            buildMap {
                put("action", sensitiveAction.action)
                put("permission", sensitiveAction.permission.value)
                put("resourceRef", sensitiveAction.resourceRef)
                sensitiveAction.limit?.let {
                    put("limit", mapOf("key" to it.key, "amount" to it.amount.stripTrailingZeros().toPlainString()))
                }
            }
}

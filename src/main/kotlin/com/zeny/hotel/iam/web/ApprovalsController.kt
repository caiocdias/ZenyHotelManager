package com.zeny.hotel.iam.web

import com.zeny.hotel.iam.application.ApprovalService
import com.zeny.hotel.iam.application.GrantApprovalCommand
import com.zeny.hotel.iam.application.PinRules
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.SensitiveAction
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Aprovação de superior (step-up, RF-ACS-07, ARCHITECTURE §6.3): o usuário corrente, recusado
 * numa ação sensível com `403 APPROVAL_REQUIRED`, pede no mesmo dispositivo o login e o PIN de
 * outro usuário que tenha a permissão e recebe o token de uso único que conclui a ação.
 */
@ApiV1Controller
@RequestMapping("/auth/approvals")
@Tag(name = "iam", description = "Autenticação, sessões por dispositivo e usuários.")
class ApprovalsController(
    private val approvals: ApprovalService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        operationId = "grantApproval",
        summary = "Aprovar uma ação sensível (step-up)",
        description =
            "Recebe a operação recusada com 403 `APPROVAL_REQUIRED` (`action`, `permission`, `resourceRef` e, se " +
                "houver, `limit`, copiados do problema), o login e o PIN de outro usuário e o motivo. Devolve o " +
                "token de aprovação: vale **uma vez**, por **2 min**, só para a mesma operação, pedida pelo mesmo " +
                "usuário na mesma sessão, no cabeçalho `X-Approval-Token` da requisição repetida. O aprovador " +
                "precisa estar ativo e ter a permissão com limite para o valor pedido (403 `APPROVER_NOT_ALLOWED`) " +
                "e não pode ser o próprio solicitante (403 `APPROVER_IS_REQUESTER`). Login inexistente, aprovador " +
                "sem PIN ou PIN errado: 403 `APPROVAL_CREDENTIALS_INVALID`; a partir da 3ª falha seguida, as " +
                "aprovações do aprovador ficam bloqueadas por tempo crescente (403 `APPROVER_LOCKED`, mesmo com o " +
                "PIN certo), sem bloquear o login dele. Permissão que não aceita aprovação: 422 " +
                "`APPROVAL_NOT_APPLICABLE`. A aprovação e as recusas ficam na trilha de auditoria.",
    )
    @ApiResponse(responseCode = "201", description = "Aprovação concedida.")
    fun grant(
        @Valid @RequestBody request: ApprovalRequest,
    ): ApprovalResponse {
        val granted =
            approvals.grant(
                GrantApprovalCommand(
                    action = request.action,
                    permission = request.permission,
                    resourceRef = request.resourceRef,
                    limit = request.limit?.let { LimitAmount(it.key, BigDecimal(it.amount)) },
                    approverLogin = request.approverLogin,
                    approverPin = request.approverPin,
                    reason = request.reason,
                ),
            )
        return ApprovalResponse(
            approvalId = granted.id.value,
            approvalToken = granted.token,
            approverId = granted.approverId.value,
            expiresAt = granted.expiresAt,
        )
    }
}

@Schema(description = "Pedido de aprovação de uma ação sensível recusada com 403 `APPROVAL_REQUIRED`.")
data class ApprovalRequest(
    @field:NotBlank
    @field:Size(max = SensitiveAction.MAX_ACTION_LENGTH)
    @field:Pattern(regexp = SensitiveAction.ACTION_REGEX)
    @field:Schema(description = "`action` do problema `APPROVAL_REQUIRED`.", example = "iam.user.deactivate")
    val action: String,
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "`permission` do problema `APPROVAL_REQUIRED`.", example = "iam.users.manage")
    val permission: String,
    @field:NotBlank
    @field:Size(max = SensitiveAction.MAX_RESOURCE_REF_LENGTH)
    @field:Schema(
        description = "`resourceRef` do problema `APPROVAL_REQUIRED`.",
        example = "iam.user:0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d",
    )
    val resourceRef: String,
    @field:Valid
    @field:Schema(description = "`limit` do problema `APPROVAL_REQUIRED`, quando houver.", nullable = true)
    val limit: ApprovalLimitRequest? = null,
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(description = "Login do aprovador; não diferencia maiúsculas.", example = "joana.gerente")
    val approverLogin: String,
    @field:NotBlank
    @field:Pattern(regexp = PinRules.PATTERN)
    @field:Schema(description = "PIN do aprovador (4 a 8 dígitos).", format = "password")
    val approverPin: String,
    @field:NotBlank
    @field:Size(max = 500)
    @field:Schema(description = "Motivo da aprovação, para a trilha de auditoria.", example = "Cortesia da gerência")
    val reason: String,
) {
    /** Nunca mostra o PIN. */
    override fun toString(): String = "ApprovalRequest(action=$action, permission=$permission)"
}

@Schema(description = "O valor pedido de um limite da permissão.")
data class ApprovalLimitRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @field:Schema(example = "maxPercent")
    val key: String,
    @field:NotBlank
    @field:Pattern(regexp = "^\\d{1,14}(\\.\\d{1,4})?$")
    @field:Schema(description = "Valor decimal, como no problema.", format = "decimal", example = "15")
    val amount: String,
)

@Schema(description = "Aprovação concedida: o token vai no cabeçalho `X-Approval-Token` da requisição repetida.")
data class ApprovalResponse(
    val approvalId: UUID,
    @field:Schema(description = "Token opaco de uso único. Não guarde: use na requisição repetida, em até 2 min.")
    val approvalToken: String,
    val approverId: UUID,
    @field:Schema(description = "Até quando o token vale (2 min depois da aprovação).")
    val expiresAt: Instant,
) {
    /** Nunca mostra o token. */
    override fun toString(): String = "ApprovalResponse(approvalId=$approvalId, expiresAt=$expiresAt)"
}

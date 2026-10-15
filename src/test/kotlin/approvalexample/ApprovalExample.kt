package approvalexample

import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.LimitAmount
import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.math.BigDecimal
import java.util.UUID

/**
 * Permissões de um módulo de exemplo, só de teste, para a aprovação de superior (M1 E05): um
 * desconto sensível com limite (acima do limite, exige aprovação) e uma anotação não sensível com
 * limite (acima do limite, é recusada sem aprovação).
 */
object ApprovalExamplePermissions : ModulePermissions {
    override val module = "approvalexamples"

    val DISCOUNTS_APPLY = PermissionCode("approvalexamples.discounts.apply")
    const val MAX_PERCENT = "maxPercent"

    val NOTES_WRITE = PermissionCode("approvalexamples.notes.write")
    const val MAX_LENGTH = "maxLength"

    override val permissions =
        listOf(
            PermissionDefinition(
                DISCOUNTS_APPLY,
                "Aplicar desconto de exemplo (sensível)",
                setOf(OWN, ALL),
                sensitive = true,
                limits = listOf(LimitDefinition(MAX_PERCENT, LimitUnit.PERCENT, "Desconto máximo em %")),
            ),
            PermissionDefinition(
                NOTES_WRITE,
                "Escrever anotação de exemplo (não sensível)",
                setOf(ALL),
                limits = listOf(LimitDefinition(MAX_LENGTH, LimitUnit.QUANTITY, "Tamanho máximo")),
            ),
        )
}

data class ApprovalExampleRequest(
    val amount: BigDecimal,
)

/** Como a ação foi autorizada: pela permissão do próprio usuário ou por uma aprovação. */
data class ApprovalExampleResponse(
    val byApproval: Boolean,
    val approvalId: UUID?,
    val approverId: UUID?,
)

/**
 * Casos de uso de exemplo, só de teste, que usam a porta de aprovação como um módulo de negócio
 * usaria: o caso de uso de escrita, auditado, pede a permissão sensível com o valor do limite.
 */
open class ApprovalExampleService(
    private val authorization: Authorization,
) {
    @Audited("approvalexamples.order.discount")
    @Transactional
    open fun discount(
        orderId: UUID,
        percent: BigDecimal,
        approvalToken: String?,
    ): ApprovalExampleResponse =
        authorization
            .requireOrApproval(
                SensitiveAction(
                    action = "approvalexamples.order.discount",
                    permission = ApprovalExamplePermissions.DISCOUNTS_APPLY,
                    resourceRef = "approvalexamples.order:$orderId",
                    // Pedido sem dono: com escopo OWN, nem o solicitante nem o aprovador o alcançam.
                    resource = OwnedResource.of(null),
                    limit = LimitAmount(ApprovalExamplePermissions.MAX_PERCENT, percent),
                ),
                approvalToken,
            ).toResponse()

    @Audited("approvalexamples.note.write")
    @Transactional
    open fun writeNote(
        noteId: UUID,
        length: BigDecimal,
        approvalToken: String?,
    ): ApprovalExampleResponse =
        authorization
            .requireOrApproval(
                SensitiveAction(
                    action = "approvalexamples.note.write",
                    permission = ApprovalExamplePermissions.NOTES_WRITE,
                    resourceRef = "approvalexamples.note:$noteId",
                    limit = LimitAmount(ApprovalExamplePermissions.MAX_LENGTH, length),
                ),
                approvalToken,
            ).toResponse()

    private fun SensitiveAuthorization.toResponse() =
        when (this) {
            is SensitiveAuthorization.ByPermission -> ApprovalExampleResponse(false, null, null)
            is SensitiveAuthorization.ByApproval -> ApprovalExampleResponse(true, approvalId, approverId.value)
        }
}

@ApiV1Controller
@RequestMapping("/approval-examples")
@Tag(name = "examples")
class ApprovalExampleController(
    private val service: ApprovalExampleService,
) {
    @PostMapping("/orders/{id}/discount")
    @Operation(operationId = "applyApprovalExampleDiscount")
    fun discount(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @RequestBody request: ApprovalExampleRequest,
    ): ApprovalExampleResponse = service.discount(id, request.amount, approvalToken)

    @PostMapping("/notes/{id}")
    @Operation(operationId = "writeApprovalExampleNote")
    fun writeNote(
        @PathVariable id: UUID,
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
        @RequestBody request: ApprovalExampleRequest,
    ): ApprovalExampleResponse = service.writeNote(id, request.amount, approvalToken)
}

/** Liga o módulo de exemplo ao contexto: as permissões entram no catálogo no boot. */
@TestConfiguration(proxyBeanMethods = false)
class ApprovalExampleConfiguration {
    @Bean
    fun approvalExamplePermissions(): ModulePermissions = ApprovalExamplePermissions

    @Bean
    fun approvalExampleService(authorization: Authorization) = ApprovalExampleService(authorization)

    @Bean
    fun approvalExampleController(service: ApprovalExampleService) = ApprovalExampleController(service)
}

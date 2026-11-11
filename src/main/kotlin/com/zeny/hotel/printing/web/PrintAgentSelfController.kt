package com.zeny.hotel.printing.web

import com.zeny.hotel.printing.application.PrintAgentIdentity
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ServicePrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * O agente de impressão sobre si mesmo, autenticado pelo token próprio (realm `print-agent`, D-E10-1). Tudo sob
 * `/print-agents/me/` é só do agente: nenhum caminho de usuário vive aqui.
 */
@ApiV1Controller
@RequestMapping("/print-agents/me")
@Tag(name = "Printing", description = "Agentes de impressão, impressoras e fila de impressão.")
class PrintAgentSelfController(
    private val agents: PrintAgentsService,
) {
    @GetMapping("/identity")
    @Operation(
        operationId = "getPrintAgentIdentity",
        summary = "Quem é o agente autenticado",
        description =
            "Autenticado pelo token do agente (`printAgentToken`), não por usuário. Token recusado, revogado ou " +
                "anterior à última rotação: 401 `UNAUTHENTICATED`.",
    )
    fun identity(
        @AuthenticationPrincipal principal: ServicePrincipal,
    ): PrintAgentIdentityResponse = PrintAgentIdentityResponse.of(agents.identity(principal.id))
}

@Schema(description = "Quem é o agente de impressão autenticado.")
data class PrintAgentIdentityResponse(
    val id: UUID,
    val name: String,
) {
    companion object {
        fun of(identity: PrintAgentIdentity) = PrintAgentIdentityResponse(identity.id, identity.name)
    }
}

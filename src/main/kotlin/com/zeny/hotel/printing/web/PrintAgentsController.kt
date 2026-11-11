package com.zeny.hotel.printing.web

import com.zeny.hotel.printing.application.CreatedAgent
import com.zeny.hotel.printing.application.PrintAgentView
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import java.time.Instant
import java.util.UUID

private const val AGENT_ID = "/{id:[0-9a-fA-F-]+}"
private const val TOKEN_NOTE =
    "O `token` aparece só nesta resposta (`Cache-Control: no-store`) e não se recupera: guarde-o agora."

/**
 * Administração dos agentes de impressão (RF-PRT, D-E10-8). Tudo exige `printing.agents.manage`. O `{id}` só casa com
 * hexadecimal e hífen, para que `me` (o agente sobre si mesmo) nunca caia aqui. Criação e rotação não são
 * idempotentes de propósito: a resposta guardada teria o token em claro. Nada é apagado: só desativação.
 */
@ApiV1Controller
@RequestMapping("/print-agents")
@Tag(name = "Printing", description = "Agentes de impressão, impressoras e fila de impressão.")
class PrintAgentsController(
    private val agents: PrintAgentsService,
) {
    @PostMapping
    @Operation(
        operationId = "createPrintAgent",
        summary = "Cadastrar um agente de impressão",
        description =
            "Exige `printing.agents.manage`. $TOKEN_NOTE Nome inválido: 400 `VALIDATION_FAILED` (campo `name`). " +
                "Sem `Idempotency-Key`: repetir o `POST` cria outro agente, que se desativa.",
    )
    fun create(
        @RequestBody request: PrintAgentRequest,
    ): ResponseEntity<PrintAgentTokenResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(PrintAgentTokenResponse.of(agents.createAgent(request.name)))

    @GetMapping
    @Operation(
        operationId = "listPrintAgents",
        summary = "Listar os agentes de impressão",
        description = "Exige `printing.agents.manage`. Nunca traz o token nem o hash.",
    )
    fun list(): List<PrintAgentResponse> = agents.agents().map(PrintAgentResponse::of)

    @GetMapping(AGENT_ID)
    @Operation(
        operationId = "getPrintAgent",
        summary = "Consultar um agente de impressão",
        description = "Exige `printing.agents.manage`. Inexistente: 404 `PRINT_AGENT_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): PrintAgentResponse = PrintAgentResponse.of(agents.agent(id))

    @PostMapping("$AGENT_ID/rotate-token")
    @Operation(
        operationId = "rotatePrintAgentToken",
        summary = "Trocar o token de um agente",
        description =
            "Exige `printing.agents.manage`. O token anterior deixa de valer na hora. $TOKEN_NOTE Agente " +
                "desativado: 422 `PRINT_AGENT_INACTIVE`. Sem `Idempotency-Key`, para não guardar o token.",
    )
    fun rotateToken(
        @PathVariable id: UUID,
    ): ResponseEntity<PrintAgentTokenResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(PrintAgentTokenResponse.of(agents.rotateToken(id)))

    @PostMapping("$AGENT_ID/deactivate")
    @Operation(
        operationId = "deactivatePrintAgent",
        summary = "Desativar um agente de impressão",
        description =
            "Exige `printing.agents.manage`. Revoga o token na hora e mantém o cadastro. Repetir é `200` e não " +
                "muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): PrintAgentResponse = PrintAgentResponse.of(agents.deactivateAgent(id))
}

@Schema(description = "O agente de impressão a cadastrar.")
data class PrintAgentRequest(
    @field:Schema(description = "Nome de 1 a 100 caracteres, sem controle.", example = "Agente da recepção")
    val name: String,
)

@Schema(description = "Um agente de impressão. Nunca traz o token nem o hash.")
data class PrintAgentResponse(
    val id: UUID,
    val name: String,
    val active: Boolean,
    @field:Schema(description = "Última vez que o agente se apresentou, se alguma.", nullable = true)
    val lastSeenAt: Instant?,
    @field:Schema(description = "Versão do cadastro.")
    val version: Long,
) {
    companion object {
        fun of(view: PrintAgentView) =
            PrintAgentResponse(view.id, view.name, view.active, view.lastSeenAt, view.version)
    }
}

@Schema(description = "Um agente de impressão com o token recém-emitido.")
data class PrintAgentTokenResponse(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val lastSeenAt: Instant?,
    val version: Long,
    @field:Schema(description = "O token do agente (`Authorization: Bearer`). Exibido uma única vez; não se recupera.")
    val token: String,
) {
    /** Sem o token. */
    override fun toString(): String = "PrintAgentTokenResponse(id=$id, name=$name, active=$active)"

    companion object {
        fun of(created: CreatedAgent) =
            with(created.view) { PrintAgentTokenResponse(id, name, active, lastSeenAt, version, created.token) }
    }
}

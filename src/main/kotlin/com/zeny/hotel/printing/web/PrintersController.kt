package com.zeny.hotel.printing.web

import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrinterChange
import com.zeny.hotel.printing.application.PrinterView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ExtensibleEnum
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

private const val PRINTER_ID = "/{id:[0-9a-fA-F-]+}"
private const val UNKNOWN = "A lista pode crescer (`x-extensible-enum`): trate um valor desconhecido sem falhar."

/**
 * As impressoras (RF-PRT, D-E10-8). Ler exige `printing.jobs.read` (quem imprime precisa ver as impressoras);
 * cadastrar, alterar e desativar, `printing.agents.manage`. A alteração pede o `If-Match` da última leitura. O tipo
 * da impressora é fixo depois da criação. Nada é apagado.
 */
@ApiV1Controller
@RequestMapping("/printers")
@Tag(name = "Printing", description = "Agentes de impressão, impressoras e fila de impressão.")
class PrintersController(
    private val service: PrintAgentsService,
) {
    @PostMapping
    @ApiResponse(responseCode = "201", description = "Impressora cadastrada.")
    @Operation(
        operationId = "createPrinter",
        summary = "Cadastrar uma impressora",
        description =
            "Exige `printing.agents.manage`. Nome ou endereço inválido: 400 `VALIDATION_FAILED` no campo. Agente " +
                "inexistente: 400 `UNKNOWN_PRINT_AGENT`; desativado: 422 `PRINT_AGENT_INACTIVE`.",
    )
    fun create(
        @RequestBody request: PrinterRequest,
    ): ResponseEntity<PrinterResponse> =
        service
            .createPrinter(NewPrinter(request.name, request.kind, request.address, request.agentId))
            .let { ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of(it.version)).body(PrinterResponse.of(it)) }

    @GetMapping
    @Operation(
        operationId = "listPrinters",
        summary = "Listar as impressoras",
        description = "Exige `printing.jobs.read`.",
    )
    fun list(): List<PrinterResponse> = service.printers().map(PrinterResponse::of)

    @GetMapping(PRINTER_ID)
    @Operation(
        operationId = "getPrinter",
        summary = "Consultar uma impressora",
        description =
            "Exige `printing.jobs.read`. O `ETag` vai no `If-Match` da alteração. Inexistente: 404 " +
                "`PRINTER_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<PrinterResponse> = ok(service.printer(id))

    @PutMapping(PRINTER_ID)
    @Operation(
        operationId = "updatePrinter",
        summary = "Alterar uma impressora",
        description =
            "Exige `printing.agents.manage` e o `If-Match` (ausente: 428; outra versão: 412). Troca nome, endereço " +
                "e agente; o `kind` não muda (um `kind` no corpo é ignorado). Impressora desativada: 422 " +
                "`PRINTER_INACTIVE`. Agente inexistente: 400 `UNKNOWN_PRINT_AGENT`; desativado: 422 " +
                "`PRINT_AGENT_INACTIVE`.",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @RequestBody request: PrinterChangeRequest,
    ): ResponseEntity<PrinterResponse> =
        ok(
            service.updatePrinter(
                id,
                ETags.expectedVersion(ifMatch),
                PrinterChange(request.name, request.address, request.agentId),
            ),
        )

    @PostMapping("$PRINTER_ID/deactivate")
    @Operation(
        operationId = "deactivatePrinter",
        summary = "Desativar uma impressora",
        description = "Exige `printing.agents.manage`. Mantém o cadastro. Repetir é `200` e não muda nada.",
    )
    fun deactivate(
        @PathVariable id: UUID,
    ): ResponseEntity<PrinterResponse> = ok(service.deactivatePrinter(id))

    private fun ok(view: PrinterView): ResponseEntity<PrinterResponse> =
        ResponseEntity.ok().eTag(ETags.of(view.version)).body(PrinterResponse.of(view))
}

@Schema(description = "A impressora a cadastrar.")
data class PrinterRequest(
    @field:Schema(description = "Nome de 1 a 100 caracteres, sem controle.", example = "Balcão da recepção")
    val name: String,
    @field:Schema(description = "O tipo, fixo depois da criação: decide o tipo de mídia do conteúdo.")
    val kind: PrinterKind,
    @field:Schema(
        description = "Endereço da impressora no agente: 1 a 255 caracteres, sem controle nem espaço.",
        example = "tcp://10.0.0.5:9100",
    )
    val address: String,
    @field:Schema(description = "O agente que atende a impressora (deve estar ativo).")
    val agentId: UUID,
)

@Schema(description = "O que muda numa impressora. O tipo não muda.")
data class PrinterChangeRequest(
    val name: String,
    val address: String,
    val agentId: UUID,
)

@Schema(description = "Uma impressora.")
data class PrinterResponse(
    val id: UUID,
    val name: String,
    @field:ExtensibleEnum
    @field:Schema(description = "O tipo da impressora. $UNKNOWN")
    val kind: PrinterKind,
    val address: String,
    val agentId: UUID,
    val active: Boolean,
) {
    companion object {
        fun of(view: PrinterView) =
            PrinterResponse(view.id, view.name, view.kind, view.address, view.agentId, view.active)
    }
}

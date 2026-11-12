package com.zeny.hotel.printing

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * Um documento que um módulo dono registra no `printing` (D-E11-2): o corpo em XHTML (PDF) e em texto (ESC/POS), como
 * templates Thymeleaf do classpath.
 *
 * - [code]: `<módulo>.<documento>` (`frontdesk.registration-card`); o prefixo é o módulo dono e o código é também o
 *   `documentType` do job de impressão;
 * - [title]: o título impresso no rodapé;
 * - [xhtmlTemplate] e [textTemplate]: o caminho completo no classpath, `templates/<módulo>/<nome>.xhtml` e
 *   `templates/<módulo>/<nome>.txt`; o catálogo usa esse mesmo caminho como nome do template no motor.
 */
data class DocumentTemplate(
    val code: String,
    val title: String,
    val xhtmlTemplate: String,
    val textTemplate: String,
)

/**
 * Os templates de documento de um módulo. Cada módulo dono publica um bean deste tipo (no arquivo do serviço do
 * documento, na `application`); o `printing` junta todos no boot e recusa a partida com código repetido, prefixo de
 * outro módulo, código ou caminho fora do padrão, ou template inexistente.
 */
interface ModuleDocumentTemplates {
    val module: String
    val templates: List<DocumentTemplate>
}

/**
 * O pedido de renderização: o código do template e o [model] do dono, com folhas `String`, `Number`, `Boolean`,
 * `LocalDate`, `LocalTime`, `Instant`, `Money`, `Percent` ou `null`, e mapas e listas aninhados. O modelo traz dado do
 * hóspede e **nunca** entra em `toString`.
 */
data class DocumentRequest(
    val templateCode: String,
    val model: Map<String, Any?>,
) {
    override fun toString(): String = "DocumentRequest(templateCode=$templateCode)"
}

/** O formato de saída de um documento. */
enum class DocumentFormat { PDF, ESCPOS }

/** O documento pronto: o [content] (PDF ou bytes ESC/POS) **nunca** entra em `toString`. */
class RenderedDocument(
    val format: DocumentFormat,
    val mediaType: String,
    val content: ByteArray,
) {
    override fun toString(): String = "RenderedDocument(format=$format, mediaType=$mediaType, size=${content.size})"
}

/** O pedido de impressão de um documento. */
@Schema(description = "A impressora que vai imprimir o documento.")
data class PrintDocumentRequest(
    @field:Schema(description = "Id da impressora ativa.")
    val printerId: UUID,
)

/** A resposta do pedido de impressão de um documento. */
@Schema(description = "O job de impressão criado.")
data class PrintJobAccepted(
    @field:Schema(description = "Id do job na fila da impressora.")
    val printJobId: UUID,
)

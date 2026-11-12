package com.zeny.hotel.printing

import java.util.UUID

/**
 * API pública do `printing` para os módulos donos dos documentos (ficha de hospedagem, extrato, confirmação): renderiza
 * um documento a partir dos templates que o dono registrou ([ModuleDocumentTemplates]) e põe um job na fila da
 * impressora. Não confere permissão, porque quem chama já se autorizou (D2); a validação do conteúdo (tamanho, formato)
 * e a cifragem ficam por conta do `printing` (D-E10-5).
 */
interface PrintingApi {
    /**
     * Enfileira o job e devolve o id. `UNKNOWN_PRINTER` (400), `PRINTER_INACTIVE`, `PRINT_CONTENT_TOO_LARGE`,
     * `PRINT_CONTENT_EMPTY` e `PRINT_CONTENT_NOT_PDF` (422) recusam o pedido.
     */
    fun enqueue(job: NewPrintJob): UUID

    /**
     * Renderiza o documento (D-E11-2 e D-E11-3): o modelo e os dados do hotel são limpos (D-E11-4); o PDF leva o
     * cabeçalho do hotel com o logotipo e o rodapé com o título, a emissão e a paginação; o ESC/POS, o cabeçalho e o
     * rodapé só com texto (D-E11-6). O logotipo indisponível só tira a imagem, nunca falha (D-E11-7). Template
     * desconhecido ou tipo não permitido no modelo é erro de programação (`IllegalArgumentException`).
     */
    fun render(
        document: DocumentRequest,
        format: DocumentFormat,
    ): RenderedDocument

    /**
     * Renderiza o documento no formato da impressora ([PrinterKind.A4_PDF] → PDF, [PrinterKind.THERMAL_ESCPOS] →
     * ESC/POS) e o enfileira com o código do template como tipo do documento; devolve o id do job. Falha como o
     * [enqueue] (`UNKNOWN_PRINTER`, `PRINTER_INACTIVE`, `PRINT_CONTENT_TOO_LARGE`) e como o [render].
     */
    fun print(
        document: DocumentRequest,
        printerId: UUID,
    ): UUID
}

/**
 * O pedido de impressão. O [content] é o documento em claro (PDF ou bytes ESC/POS) e **nunca** entra em `toString`,
 * `equals` nem `hashCode`: dois pedidos são iguais pela impressora e pelo tipo do documento.
 */
class NewPrintJob(
    val printerId: UUID,
    val documentType: String,
    val content: ByteArray,
) {
    override fun toString(): String = "NewPrintJob(printerId=$printerId, documentType=$documentType)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is NewPrintJob && printerId == other.printerId && documentType == other.documentType)

    override fun hashCode(): Int = 31 * printerId.hashCode() + documentType.hashCode()
}

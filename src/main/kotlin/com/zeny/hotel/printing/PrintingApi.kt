package com.zeny.hotel.printing

import java.util.UUID

/**
 * API pública do `printing` para os módulos donos dos documentos (ficha de hospedagem, comanda, nota): põe um job na
 * fila da impressora. Não confere permissão, porque quem chama já se autorizou; a validação do conteúdo (tamanho,
 * formato) e a cifragem ficam por conta do `printing` (D-E10-5).
 */
interface PrintingApi {
    /**
     * Enfileira o job e devolve o id. `UNKNOWN_PRINTER` (400), `PRINTER_INACTIVE`, `PRINT_CONTENT_TOO_LARGE`,
     * `PRINT_CONTENT_EMPTY` e `PRINT_CONTENT_NOT_PDF` (422) recusam o pedido.
     */
    fun enqueue(job: NewPrintJob): UUID
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

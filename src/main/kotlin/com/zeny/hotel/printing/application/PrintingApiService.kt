package com.zeny.hotel.printing.application

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.FilesApi
import com.zeny.hotel.hotel.CurrentHotelProfile
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.hotel.HotelFilePurposes
import com.zeny.hotel.printing.DocumentFormat
import com.zeny.hotel.printing.DocumentRequest
import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.NewPrintJob
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.RenderedDocument
import com.zeny.hotel.printing.domain.DocumentFormatter
import com.zeny.hotel.printing.domain.DocumentLogo
import com.zeny.hotel.printing.domain.DocumentText
import com.zeny.hotel.printing.domain.ESC_POS_COLUMNS
import com.zeny.hotel.printing.domain.EscPosDocument
import com.zeny.hotel.printing.infrastructure.HtmlPdfRenderer
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.thymeleaf.context.Context
import org.unbescape.html.HtmlEscape
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * A fachada da [PrintingApi] (D-E11-2), o único bean dela. O [enqueue] delega ao [PrintJobsService] (que mantém a
 * trilha `printing.job.create`); o [render] confere os tipos do modelo, limpa todo texto do modelo e do perfil do hotel
 * (D-E11-4; no PDF, também o que o WinAnsi não codifica vira `?`) e renderiza pelo catálogo: o PDF no layout
 * `templates/printing/layout.xhtml`, com o contexto de D-E11-3, e o ESC/POS pelo template de texto com o cabeçalho e o
 * rodapé do [EscPosDocument] (D-E11-6). O [print] confere a impressora antes de renderizar e enfileira com o código do
 * template como tipo do documento.
 *
 * Não abre transação: a leitura do hotel entra na de quem chama, e a do logotipo vai numa própria ([HotelLogoReader]).
 */
@Service
class PrintingApiService(
    private val jobs: PrintJobsService,
    private val catalog: DocumentTemplateCatalog,
    private val pdfRenderer: HtmlPdfRenderer,
    private val hotel: HotelApi,
    private val logos: HotelLogoReader,
    private val clock: Clock,
) : PrintingApi {
    override fun enqueue(job: NewPrintJob): UUID = jobs.enqueue(job)

    override fun render(
        document: DocumentRequest,
        format: DocumentFormat,
    ): RenderedDocument {
        val template = catalog.template(document.templateCode)
        val text: (String) -> String =
            when (format) {
                DocumentFormat.PDF -> { value -> DocumentText.toWinAnsi(DocumentText.clean(value)) }
                DocumentFormat.ESCPOS -> DocumentText::clean
            }
        val model = cleanMap(document.model, text, "document")
        val current = hotel.profile()
        val formatter = DocumentFormatter(current?.profile?.timezone ?: hotel.timezone())
        val page = Page(template, model, hotelTexts(current, formatter, text), formatter, clock.instant(), text)
        return when (format) {
            DocumentFormat.PDF -> RenderedDocument(format, PrinterKind.A4_PDF.mediaType, pdf(page, current))
            DocumentFormat.ESCPOS -> RenderedDocument(format, PrinterKind.THERMAL_ESCPOS.mediaType, escPos(page))
        }
    }

    override fun print(
        document: DocumentRequest,
        printerId: UUID,
    ): UUID {
        val format =
            when (jobs.usablePrinterKind(printerId)) {
                PrinterKind.A4_PDF -> DocumentFormat.PDF
                PrinterKind.THERMAL_ESCPOS -> DocumentFormat.ESCPOS
            }
        val rendered = render(document, format)
        return jobs.enqueue(NewPrintJob(printerId, document.templateCode, rendered.content))
    }

    /** O que um documento leva, já limpo: o template, o modelo, os textos do hotel, a formatação e a emissão. */
    private class Page(
        val template: DocumentTemplate,
        val model: Map<String, Any?>,
        val hotel: Map<String, String?>,
        val formatter: DocumentFormatter,
        val issuedAt: Instant,
        text: (String) -> String,
    ) {
        val title = text(template.title)

        /** O contexto de D-E11-3: só as variáveis do `printing`; o modelo só como `document`. */
        fun context(extra: Map<String, Any?>): Context =
            Context(
                LOCALE,
                mapOf(
                    "document" to model,
                    "hotel" to hotel,
                    "fmt" to formatter,
                    "issuedAt" to issuedAt,
                    "title" to title,
                ) + extra,
            )
    }

    private fun pdf(
        page: Page,
        current: CurrentHotelProfile?,
    ): ByteArray {
        val logo = logos.read(current, page.template.code)?.let { "data:image/png;base64," + BASE64.encodeToString(it) }
        val xhtml =
            catalog.engine.process(
                LAYOUT,
                page.context(mapOf("logo" to logo, "bodyTemplate" to page.template.xhtmlTemplate)),
            )
        return pdfRenderer.render(xhtml)
    }

    /**
     * O modo TEXT escapa o valor como HTML (`<` vira `&lt;`); as entidades voltam numa passagem só, então o texto do
     * modelo sai literal (`&#27;` do hóspede chega aqui como `&amp;#27;` e sai `&#27;`). O [EscPosDocument] limpa cada
     * linha de novo antes de codificar.
     */
    private fun escPos(page: Page): ByteArray {
        val body = HtmlEscape.unescapeHtml(catalog.engine.process(page.template.textTemplate, page.context(emptyMap())))
        val hotel = page.hotel
        val header =
            listOfNotNull(
                hotel[TRADE_NAME],
                hotel[LEGAL_NAME],
                hotel[CNPJ]?.let { "CNPJ $it" },
                hotel[CADASTUR]?.let { "Cadastur $it" },
                hotel[ADDRESS],
            )
        val footer = listOf(page.title, "Emitido em ${page.formatter.instant(page.issuedAt)}")
        return EscPosDocument(ESC_POS_COLUMNS).encode(header, body, footer)
    }

    /** Os textos do perfil do hotel, limpos; sem perfil, só o nome "Hotel" (D-E11-3). */
    private fun hotelTexts(
        current: CurrentHotelProfile?,
        formatter: DocumentFormatter,
        text: (String) -> String,
    ): Map<String, String?> {
        val profile = current?.profile
        val address =
            profile?.address?.let {
                val complement =
                    it.complement
                        ?.takeIf(String::isNotBlank)
                        ?.let { value -> ", $value" }
                        .orEmpty()
                "${it.street}, ${it.number}$complement - ${it.district} - ${it.city}/${it.state} - " +
                    "CEP ${it.formattedPostalCode()}"
            }
        return mapOf(
            TRADE_NAME to (profile?.tradeName ?: UNCONFIGURED_NAME),
            LEGAL_NAME to profile?.legalName,
            CNPJ to profile?.let { formatter.cnpj(it.cnpj) },
            CADASTUR to profile?.cadastur,
            MUNICIPAL_REGISTRATION to profile?.municipalRegistration,
            ADDRESS to address,
        ).mapValues { (_, value) -> value?.let(text)?.takeIf(String::isNotEmpty) }
    }

    /** O modelo validado (D-E11-2) com todo texto limpo; outro tipo de folha é `IllegalArgumentException`. */
    private fun cleanMap(
        map: Map<*, *>,
        text: (String) -> String,
        path: String,
    ): Map<String, Any?> =
        map.entries.associate { (key, value) ->
            require(key is String) { "Chave que não é texto no modelo do documento, em $path" }
            key to cleanValue(value, text, "$path.$key")
        }

    private fun cleanValue(
        value: Any?,
        text: (String) -> String,
        path: String,
    ): Any? =
        when (value) {
            null, is Boolean, is Number, is LocalDate, is LocalTime, is Instant, is Money, is Percent -> value

            is String -> text(value)

            is Map<*, *> -> cleanMap(value, text, path)

            is List<*> -> value.mapIndexed { index, item -> cleanValue(item, text, "$path[$index]") }

            else -> throw IllegalArgumentException(
                "Tipo não permitido no modelo do documento, em $path: ${value::class.java.name}",
            )
        }

    private companion object {
        const val LAYOUT = "templates/printing/layout.xhtml"
        const val UNCONFIGURED_NAME = "Hotel"
        const val TRADE_NAME = "tradeName"
        const val LEGAL_NAME = "legalName"
        const val CNPJ = "cnpj"
        const val CADASTUR = "cadastur"
        const val MUNICIPAL_REGISTRATION = "municipalRegistration"
        const val ADDRESS = "address"
        val LOCALE: Locale = Locale.of("pt", "BR")
        val BASE64: Base64.Encoder = Base64.getEncoder()
    }
}

/**
 * O logotipo do hotel para o cabeçalho dos documentos (D-E11-7): lido pela [FilesApi] com a finalidade
 * [HotelFilePurposes.LOGO] e o id do hotel como dono, **numa transação própria** (`REQUIRES_NEW`, só leitura), para que
 * uma falha não marque a transação de quem chama como *rollback-only*, e reduzido pelo [DocumentLogo]. Qualquer
 * problema (anexo apagado, objeto ausente no bucket, `WEBP`, imagem ilegível ou grande demais) vira "sem logotipo" com
 * um `WARN` que só cita o documento e o tipo da falha, nunca erro.
 */
@Component
class HotelLogoReader(
    private val files: FilesApi,
    transactionManager: PlatformTransactionManager,
) {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isReadOnly = true
        }

    /** O logotipo reduzido em PNG, ou `null` (hotel sem perfil ou sem logotipo, ou logotipo indisponível). */
    @Suppress("TooGenericExceptionCaught") // a leitura cruza o banco, o bucket e a conferência do SHA-256
    fun read(
        current: CurrentHotelProfile?,
        documentCode: String,
    ): ByteArray? {
        val logoId = current?.profile?.logoFileId ?: return null
        val content =
            try {
                ownTransaction.execute { _ ->
                    files.readContent(FileId(logoId), HotelFilePurposes.LOGO, current.hotelId.toString())
                }
            } catch (e: Exception) {
                log.warn("Documento {} sem logotipo: leitura falhou ({})", documentCode, e.javaClass.simpleName)
                null
            }
        return content?.let { read ->
            DocumentLogo.prepare(read.content, read.summary.contentType).also {
                if (it == null) {
                    log.warn("Documento {} sem logotipo: imagem ilegível, grande demais ou sem leitor", documentCode)
                }
            }
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HotelLogoReader::class.java)
    }
}

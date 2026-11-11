package com.zeny.hotel.printing.infrastructure

import com.openhtmltopdf.extend.FSStream
import com.openhtmltopdf.extend.FSStreamFactory
import com.openhtmltopdf.extend.FSUriResolver
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.util.XRLog
import org.springframework.stereotype.Component
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import java.util.logging.Level
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converte um documento XHTML em PDF sem ler nenhum recurso externo (D-E11-5): o *parse* usa um
 * `DocumentBuilderFactory` que recusa `DOCTYPE` (nem DTD nem entidade externa passam), e o openhtmltopdf só resolve
 * URIs `data:`; `http:`, `https:`, `file:`, `jar:` e caminhos relativos são recusados antes de qualquer acesso. O log
 * do openhtmltopdf fica em `WARN`.
 */
@Component
class HtmlPdfRenderer {
    init {
        XRLog.listRegisteredLoggers().forEach { XRLog.setLevel(it, Level.WARNING) }
    }

    /** Falha com exceção se o XHTML não for XML bem formado ou tiver `DOCTYPE`. */
    fun render(xhtml: String): ByteArray {
        val document =
            secureBuilderFactory()
                .newDocumentBuilder()
                .parse(InputSource(StringReader(xhtml)))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .useUriResolver(DataUriOnlyResolver)
            .useHttpStreamImplementation(NoStreamFactory)
            .withW3cDocument(document, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    private fun secureBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    /** Só `data:` passa; o resto vira `null` (recurso recusado). */
    private object DataUriOnlyResolver : FSUriResolver {
        override fun resolveURI(
            baseUri: String?,
            uri: String?,
        ): String? = uri?.takeIf { it.trimStart().startsWith("data:", ignoreCase = true) }?.trim()
    }

    /** Rede de segurança: se algo chegar a abrir um fluxo por `http:`, entrega um fluxo vazio, sem acesso. */
    private object NoStreamFactory : FSStreamFactory {
        override fun getUrl(url: String?): FSStream =
            object : FSStream {
                override fun getStream(): InputStream = ByteArrayInputStream(ByteArray(0))

                override fun getReader(): Reader = StringReader("")
            }
    }
}

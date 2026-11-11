package com.zeny.hotel.printing

import com.sun.net.httpserver.HttpServer
import com.zeny.hotel.printing.infrastructure.HtmlPdfRenderer
import com.zeny.hotel.testsupport.pdfImageCount
import com.zeny.hotel.testsupport.pdfPageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes

/** Conversão XHTML → PDF sem recurso externo (D-E11-5): só `data:` é lido; nada de rede, arquivo, `jar:` nem DTD. */
@ExtendWith(OutputCaptureExtension::class)
class HtmlPdfRendererTest {
    private val renderer = HtmlPdfRenderer()
    private val hits = AtomicInteger()
    private lateinit var server: HttpServer
    private val port get() = server.address.port

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, PNG.size.toLong())
            exchange.responseBody.use { it.write(PNG) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    @Test
    fun `converte XHTML em PDF com o texto acentuado`() {
        val pdf = renderer.render(page("<p>Ficha de hóspede — João Ação</p>"))

        String(pdf.copyOf(5), Charsets.US_ASCII) shouldBe "%PDF-"
        pdfText(pdf) shouldContain "Ficha de hóspede — João Ação"
    }

    @Test
    fun `imagem http nao gera acesso nem imagem`() {
        val pdf = renderer.render(page("""<img src="http://127.0.0.1:$port/x.png"/>"""))

        hits.get() shouldBe 0
        pdfImageCount(pdf) shouldBe 0
    }

    @Test
    fun `DOCTYPE com DTD externa nao gera acesso`() {
        val xhtml = """<!DOCTYPE html SYSTEM "http://127.0.0.1:$port/x.dtd"><html><body><p>x</p></body></html>"""

        runCatching { renderer.render(xhtml) }

        hits.get() shouldBe 0
    }

    @Test
    fun `entidade externa nao le arquivo local`(
        @TempDir dir: Path,
    ) {
        val secret = dir.resolve("secret.txt").also { it.writeBytes("SEGREDO-LOCAL".toByteArray()) }
        val xhtml =
            """<!DOCTYPE html [<!ENTITY e SYSTEM "${secret.toUri()}">]><html><body><p>&e;</p></body></html>"""

        val text = runCatching { pdfText(renderer.render(xhtml)) }.getOrDefault("")

        text shouldNotContain "SEGREDO-LOCAL"
    }

    @Test
    fun `imagem file nao e lida`(
        @TempDir dir: Path,
    ) {
        val png = dir.resolve("a.png").also { it.writeBytes(PNG) }

        val pdf = renderer.render(page("""<img src="file:$png"/>"""))

        pdfImageCount(pdf) shouldBe 0
    }

    @Test
    fun `imagem jar nao e lida`(
        @TempDir dir: Path,
    ) {
        val jar = dir.resolve("a.jar")
        ZipOutputStream(jar.toFile().outputStream()).use {
            it.putNextEntry(ZipEntry("x.png"))
            it.write(PNG)
            it.closeEntry()
        }

        val pdf = renderer.render(page("""<img src="jar:file:$jar!/x.png"/>"""))

        pdfImageCount(pdf) shouldBe 0
    }

    @Test
    fun `imagem de caminho relativo nao e lida`() {
        pdfImageCount(renderer.render(page("""<img src="x.png"/>"""))) shouldBe 0
    }

    @Test
    fun `imagem data URI e incluida`() {
        val data = Base64.getEncoder().encodeToString(PNG)

        pdfImageCount(renderer.render(page("""<img src="data:image/png;base64,$data"/>"""))) shouldBe 1
    }

    @Test
    fun `caracteres fora do latim nao derrubam a conversao`() {
        val pdf = renderer.render(page("<p>Łukasz 李 😀</p>"))

        String(pdf.copyOf(5), Charsets.US_ASCII) shouldBe "%PDF-"
    }

    @Test
    fun `documento longo ocupa varias paginas`() {
        val body = (1..300).joinToString("") { "<p>Linha $it</p>" }

        (pdfPageCount(renderer.render(page(body))) > 1) shouldBe true
    }

    @Test
    fun `o texto do documento nao aparece no log`(output: CapturedOutput) {
        renderer.render(page("""<p>NomeSecretoDoHospede</p><img src="x.png"/>"""))

        output.all shouldNotContain "NomeSecretoDoHospede"
    }

    private fun page(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>"""

    private companion object {
        val PNG: ByteArray =
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGP4z8AARAwQCgAf7gP9i18U1AAAAABJRU5ErkJggg==",
            )
    }
}

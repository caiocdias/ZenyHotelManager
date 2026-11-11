package com.zeny.hotel.printing

import com.sun.net.httpserver.HttpServer
import com.zeny.hotel.printing.infrastructure.HtmlPdfRenderer
import com.zeny.hotel.testsupport.pdfImageCount
import com.zeny.hotel.testsupport.pdfText
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.xml.sax.SAXException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText

/** Ataques ao renderizador XHTML → PDF (D-E11-5), provados pelo comportamento: servidor local sem acesso. */
@ExtendWith(OutputCaptureExtension::class)
class HtmlPdfRendererAttackTest {
    private val renderer = HtmlPdfRenderer()
    private val hits = AtomicInteger()
    private val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            paths += ex.requestURI.toString()
            val body = "x".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun page(
        body: String,
        head: String = "",
    ) =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title>$head</head><body>$body</body></html>"""

    private fun semAcesso(
        xhtml: String,
        tolerarExcecao: Boolean = true,
    ) {
        val result = runCatching { renderer.render(xhtml) }
        if (!tolerarExcecao) result.isSuccess shouldBe true
        result.getOrNull()?.let { pdfImageCount(it) shouldBe 0 }
        hits.get() shouldBe 0
    }

    @Test
    fun `DOCTYPE com DTD externa e recusado com SAXException e sem PDF`() {
        val ex =
            runCatching {
                renderer.render("""<!DOCTYPE html SYSTEM "$base/x.dtd"><html><body><p>x</p></body></html>""")
            }.exceptionOrNull()

        ex.shouldBeInstanceOf<SAXException>()
        hits.get() shouldBe 0
    }

    @Test
    fun `entidade externa para arquivo local e recusada e o conteudo nao vaza`(
        @TempDir dir: Path,
    ) {
        val secret = dir.resolve("s.txt").also { it.writeText("SEGREDO-LOCAL-XYZ") }
        val ex =
            runCatching {
                renderer.render(
                    """<!DOCTYPE html [<!ENTITY e SYSTEM "${secret.toUri()}">]><html><body><p>&e;</p></body></html>""",
                )
            }

        ex.isFailure shouldBe true
        ex.exceptionOrNull()?.message.orEmpty() shouldNotContain "SEGREDO-LOCAL-XYZ"
    }

    @Test
    fun `entidade externa para http nao gera acesso`() {
        semAcesso("""<!DOCTYPE html [<!ENTITY e SYSTEM "$base/e">]><html><body><p>&e;</p></body></html>""")
    }

    @Test
    fun `parameter entity externa nao gera acesso`() {
        semAcesso("""<!DOCTYPE html [<!ENTITY % p SYSTEM "$base/p.dtd"> %p;]><html><body><p>x</p></body></html>""")
    }

    @Test
    fun `XInclude de arquivo local nao inclui o conteudo`(
        @TempDir dir: Path,
    ) {
        val secret = dir.resolve("s.txt").also { it.writeText("SEGREDO-XINCLUDE") }
        val xhtml =
            page(
                """<p><xi:include xmlns:xi="http://www.w3.org/2001/XInclude" """ +
                    """href="${secret.toUri()}" parse="text"/></p>""",
            )

        val text = runCatching { pdfText(renderer.render(xhtml)) }.getOrDefault("")

        text shouldNotContain "SEGREDO-XINCLUDE"
    }

    @Test
    fun `XInclude de http nao gera acesso`() {
        semAcesso(page("""<xi:include xmlns:xi="http://www.w3.org/2001/XInclude" href="$base/i.xml"/>"""))
    }

    @Test
    fun `link stylesheet http nao gera acesso`() {
        semAcesso(page("<p>x</p>", """<link rel="stylesheet" type="text/css" href="$base/a.css"/>"""), false)
    }

    @Test
    fun `import de CSS em style nao gera acesso`() {
        semAcesso(page("<p>x</p>", """<style>@import url($base/a.css); @import "$base/b.css";</style>"""), false)
    }

    @Test
    fun `url em style nao gera acesso`() {
        semAcesso(
            page(
                "<p class=\"a\">x</p>",
                """<style>.a { background-image: url($base/bg.png); } """ +
                    """@font-face { font-family: F; src: url($base/f.ttf); }</style>""",
            ),
            false,
        )
    }

    @Test
    fun `url em CSS inline nao gera acesso`() {
        semAcesso(
            page("""<div style="background: url('$base/bg.png'); list-style-image: url($base/l.png)">x</div>"""),
            false,
        )
    }

    @Test
    fun `url file em CSS nao e lida`(
        @TempDir dir: Path,
    ) {
        val png = dir.resolve("a.png").also { it.writeText("x") }
        val pdf = renderer.render(page("""<div style="background-image: url(${png.toUri()})">x</div>"""))

        pdfImageCount(pdf) shouldBe 0
    }

    @Test
    fun `object nao gera acesso`() {
        semAcesso(page("""<object data="$base/o.png" type="image/png"></object><embed src="$base/e.png"/>"""), false)
    }

    @Test
    fun `iframe nao gera acesso`() {
        semAcesso(page("""<iframe src="$base/f.html"></iframe>"""), false)
    }

    @Test
    fun `svg com image href nao gera acesso`() {
        semAcesso(
            page(
                """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" """ +
                    """width="10" height="10">""" +
                    """<image href="$base/s.png" xlink:href="$base/s2.png" width="10" height="10"/></svg>""",
            ),
            false,
        )
    }

    @Test
    fun `base href nao redireciona caminho relativo para o servidor`() {
        semAcesso(page("""<img src="rel.png"/>""", """<base href="$base/"/>"""), false)
    }

    @Test
    fun `data URI que nao e imagem nao vira imagem`() {
        val b64 = Base64.getEncoder().encodeToString("<html>oi</html>".toByteArray())
        val pdf =
            renderer.render(
                page("""<img src="data:text/html;base64,$b64"/><img src="data:application/pdf;base64,$b64"/>"""),
            )

        pdfImageCount(pdf) shouldBe 0
    }

    @Test
    fun `data URI gigante e tratado sem estourar memoria nem tempo`() {
        val big = Base64.getEncoder().encodeToString(ByteArray(8 * 1024 * 1024) { 7 })
        val start = System.nanoTime()

        val result = runCatching { renderer.render(page("""<img src="data:image/png;base64,$big"/>""")) }

        (((System.nanoTime() - start) / 1_000_000_000) < 30) shouldBe true
        (result.exceptionOrNull() !is OutOfMemoryError) shouldBe true
        result.getOrNull()?.let { pdfImageCount(it) shouldBe 0 }
    }

    @Test
    fun `bilhao de risos e recusado rapido sem estourar memoria`() {
        val lol =
            "<!ENTITY lol \"lol\">" +
                (1..9).joinToString("") { i ->
                    val ref = if (i == 1) "&lol;" else "&lol${i - 1};"
                    "<!ENTITY lol$i \"" + ref.repeat(10) + "\">"
                }
        val xhtml = "<!DOCTYPE lolz [$lol]><html><body><p>&lol9;</p></body></html>"
        val start = System.nanoTime()

        val result = runCatching { renderer.render(xhtml) }

        (((System.nanoTime() - start) / 1_000_000_000) < 10) shouldBe true
        result.isFailure shouldBe true
        (result.exceptionOrNull() !is OutOfMemoryError) shouldBe true
    }

    @Test
    fun `XHTML mal formado falha com SAXException`() {
        val ex = runCatching { renderer.render("<html><body><p>aberto</body></html>") }.exceptionOrNull()

        ex.shouldBeInstanceOf<SAXException>()
    }

    @Test
    fun `entrada vazia falha sem PDF`() {
        runCatching { renderer.render("") }.isFailure shouldBe true
    }

    @Test
    fun `o log nao registra o texto do documento nem de recurso recusado nem de erro`(output: CapturedOutput) {
        val marker = "MARCADOR-UNICO-9f3a"
        runCatching {
            renderer.render(
                page(
                    """<p class="c$marker">$marker</p><img src="http://127.0.0.1:1/$marker.png"/>""" +
                        """<img src="$marker.png"/>""",
                    """<style>.c$marker { background: url($marker.png) }</style>""",
                ),
            )
        }
        runCatching { renderer.render("<html><body><p>$marker</body></html>") }

        output.all shouldNotContain marker
    }
}

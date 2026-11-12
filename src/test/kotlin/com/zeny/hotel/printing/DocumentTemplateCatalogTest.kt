package com.zeny.hotel.printing

import com.zeny.hotel.printing.application.DocumentTemplateCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.thymeleaf.context.Context
import printingexample.PrintingExampleTemplates
import java.util.Locale

class DocumentTemplateCatalogTest {
    private fun module(
        name: String,
        vararg templates: DocumentTemplate,
    ) = object : ModuleDocumentTemplates {
        override val module = name
        override val templates = templates.toList()
    }

    private fun template(
        code: String,
        xhtml: String = "templates/printingexample/card.xhtml",
        text: String = "templates/printingexample/card.txt",
    ) = DocumentTemplate(code, "Título", xhtml, text)

    private fun catalog(vararg modules: ModuleDocumentTemplates) = DocumentTemplateCatalog(modules.toList())

    @Test
    fun `aceita o template de exemplo e o devolve pelo codigo`() {
        val catalog = catalog(PrintingExampleTemplates)

        assertThat(catalog.template("printingexample.card").title).isEqualTo("Ficha de exemplo")
    }

    @Test
    fun `codigo desconhecido e erro de programacao`() {
        assertThatThrownBy { catalog(PrintingExampleTemplates).template("printingexample.nada") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("printingexample.nada")
    }

    @Test
    fun `recusa codigo repetido`() {
        assertThatThrownBy {
            catalog(module("printingexample", template("printingexample.card"), template("printingexample.card")))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("printingexample.card")
    }

    @Test
    fun `recusa codigo com prefixo de outro modulo`() {
        assertThatThrownBy { catalog(module("billing", template("frontdesk.x"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("frontdesk.x")
    }

    @Test
    fun `recusa o mesmo modulo declarado duas vezes`() {
        assertThatThrownBy { catalog(PrintingExampleTemplates, PrintingExampleTemplates) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `recusa codigo fora do padrao`() {
        listOf(
            "printingexample",
            "Printingexample.card",
            "printingexample.Card",
            "printingexample.card_x",
            "printingexample.",
        ).forEach {
            assertThatThrownBy { catalog(module("printingexample", template(it))) }
                .describedAs(it)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(it)
        }
    }

    @Test
    fun `recusa caminho fora de templates do modulo`() {
        val code = "printingexample.card"
        listOf(
            "templates/billing/card.xhtml",
            "templates/printingexample/../billing/card.xhtml",
            "templates/printingexample/card.txt",
            "card.xhtml",
            "/templates/printingexample/card.xhtml",
        ).forEach {
            assertThatThrownBy { catalog(module("printingexample", template(code, xhtml = it))) }
                .describedAs(it)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(code)
        }
        assertThatThrownBy {
            catalog(module("printingexample", template(code, text = "templates/printingexample/card.xhtml")))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(code)
    }

    @Test
    fun `recusa template inexistente`() {
        val code = "printingexample.card"
        assertThatThrownBy {
            catalog(module("printingexample", template(code, xhtml = "templates/printingexample/falta.xhtml")))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(code)
        assertThatThrownBy {
            catalog(module("printingexample", template(code, text = "templates/printingexample/falta.txt")))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(code)
    }

    @Test
    fun `renderiza o xhtml escapando o texto e iterando a lista`() {
        val engine = catalog(PrintingExampleTemplates).engine
        val model =
            mapOf(
                "document" to mapOf("guest" to mapOf("name" to "<b>&"), "items" to listOf("um", "<dois>")),
            )

        val html = engine.process("templates/printingexample/card.xhtml", Context(Locale.ROOT, model))

        assertThat(html).contains("&lt;b&gt;&amp;")
        assertThat(html).doesNotContain("<b>")
        assertThat(html).contains("<li>um</li>", "<li>&lt;dois&gt;</li>")
    }

    @Test
    fun `renderiza o template de texto e escapa as entidades que o ESC POS precisa desfazer`() {
        val engine = catalog(PrintingExampleTemplates).engine
        val model = mapOf("document" to mapOf("guest" to mapOf("name" to "Ana <b>")))

        val text = engine.process("templates/printingexample/card.txt", Context(Locale.ROOT, model))

        // O modo TEXT escapa [[ ]] como HTML e [( )] é proibido (D-E11-3): quem monta o ESC/POS desfaz as entidades.
        assertThat(text).contains("Hospede: Ana &lt;b&gt;")
    }

    @Test
    fun `recusa caminho absoluto, com ponto ponto, barra invertida ou nome fora do padrao`() {
        val code = "printingexample.card"
        listOf(
            "/templates/printingexample/card.xhtml",
            "templates/printingexample/../printingexample/card.xhtml",
            "templates/printingexample/./card.xhtml",
            "templates\\printingexample\\card.xhtml",
            "templates/printingexample\\card.xhtml",
            "templates/printingexample/sub/card.xhtml",
            "templates/printingexample/Card.xhtml",
            "templates/printingexample/card.xhtml\n",
            "classpath:templates/printingexample/card.xhtml",
            "../templates/printingexample/card.xhtml",
            "templates/printingexample/.xhtml",
        ).forEach {
            assertThatThrownBy { catalog(module("printingexample", template(code, xhtml = it))) }
                .describedAs("xhtml $it")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(code)
        }
        listOf(
            "/templates/printingexample/card.txt",
            "templates/printingexample/../printingexample/card.txt",
            "templates\\printingexample\\card.txt",
            "templates/printingexample/card.xhtml.txt",
        ).forEach {
            assertThatThrownBy { catalog(module("printingexample", template(code, text = it))) }
                .describedAs("txt $it")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(code)
        }
    }

    @Test
    fun `recusa titulo em branco e codigo com quebra de linha`() {
        assertThatThrownBy {
            catalog(
                module(
                    "printingexample",
                    DocumentTemplate(
                        "printingexample.card",
                        " ",
                        "templates/printingexample/card.xhtml",
                        "templates/printingexample/card.txt",
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("printingexample.card")
        assertThatThrownBy { catalog(module("printingexample", template("printingexample.card\n"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `recusa codigo repetido entre dois modulos distintos do mesmo prefixo`() {
        assertThatThrownBy {
            catalog(
                module("printingexample", template("printingexample.card")),
                module("printingexample", template("printingexample.card")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `codigo desconhecido com ponto ponto ou vazio e recusado sem vazar caminho`() {
        val catalog = catalog(PrintingExampleTemplates)
        listOf("", "../x", "printingexample.card ", "PRINTINGEXAMPLE.CARD").forEach {
            assertThatThrownBy { catalog.template(it) }
                .describedAs("'$it'")
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `o motor so resolve nomes sob templates mesmo se existirem no classpath`() {
        val engine = catalog(PrintingExampleTemplates).engine

        listOf("META-INF/MANIFEST.MF.txt", "application.xhtml", "logback.txt", "templates.txt").forEach {
            assertThatThrownBy { engine.process(it, Context(Locale.ROOT)) }.describedAs(it).isNotNull()
        }
        // um recurso real fora de templates/, com extensão aceita pelo padrão antigo
        val outside = "outside-templates.txt"
        assertThat(DocumentTemplateCatalogTest::class.java.classLoader.getResource(outside)).isNotNull()
        assertThatThrownBy { engine.process(outside, Context(Locale.ROOT)) }.isNotNull()
    }
}

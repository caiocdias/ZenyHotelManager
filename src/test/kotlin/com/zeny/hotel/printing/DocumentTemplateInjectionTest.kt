package com.zeny.hotel.printing

import com.zeny.hotel.printing.application.DocumentTemplateCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.thymeleaf.context.Context
import printingexample.PrintingExampleTemplates
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Valor vindo do modelo nunca é avaliado como expressão Thymeleaf, nos modos XML e TEXT (D-E11-3, SSTI). */
class DocumentTemplateInjectionTest {
    private val engine = DocumentTemplateCatalog(listOf(PrintingExampleTemplates)).engine

    private fun render(
        template: String,
        value: String,
    ): String = engine.process(template, Context(Locale.ROOT, mapOf("document" to mapOf("value" to value))))

    @ParameterizedTest
    @ValueSource(
        strings = [
            "\${7*7}",
            "\${T(java.lang.Runtime).getRuntime().exec('touch /tmp/zeny-ssti-xml')}",
            "__\${7*7}__",
            "[[\${7*7}]]",
            "[(\${7*7})]",
            "#{some.key}",
            "*{value}",
            "~{printingexample/card :: x}",
            "@{/x}",
            "[# th:text=\"\${7*7}\"]x[/]",
        ],
    )
    fun `valor de modelo vira texto literal no xhtml`(value: String) {
        val html = render("templates/printingexample/attack.xhtml", value)

        val ids = listOf("text", "inline", "attr")
        assertThat(html).doesNotContain("49")
        assertThat(html).doesNotContain("zeny-ssti-xml-evaluated")
        ids.forEach { assertThat(html).describedAs(it).contains("id=\"$it\"") }
        // o literal sobrevive (o `$`, `{`, `[`, `_` e `#` não são escapados; só <, >, &, aspas)
        val marker = value.substringBefore('(').substringBefore('}').take(4)
        assertThat(html).contains(marker)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\${7*7}", "__\${7*7}__", "[[\${7*7}]]", "[(\${7*7})]", "#{some.key}", "*{value}", "@{/x}"])
    fun `valor de modelo vira texto literal no modo texto`(value: String) {
        val text = render("templates/printingexample/attack.txt", value)

        assertThat(text).doesNotContain("49")
        assertThat(text.lines()[0]).startsWith("A: ")
        assertThat(text.lines()[0].removePrefix("A: ")).isEqualTo(value)
        assertThat(text.lines()[1].removePrefix("B: ")).isEqualTo(value)
    }

    @Test
    fun `o comando de Runtime nao e executado`() {
        val marker = Path.of("/tmp/zeny-ssti-${System.nanoTime()}")
        val value = "\${T(java.lang.Runtime).getRuntime().exec('touch $marker')}"

        render("templates/printingexample/attack.xhtml", value)
        render("templates/printingexample/attack.txt", value)

        assertThat(Files.exists(marker)).isFalse()
    }

    @Test
    fun `xhtml escapa marcacao e aspas do valor`() {
        val html = render("templates/printingexample/attack.xhtml", "<script>\"x\"&</script>")

        assertThat(html).doesNotContain("<script>")
        assertThat(html).contains("&lt;script&gt;")
        assertThat(html).contains("title=\"&lt;script&gt;&quot;x&quot;&amp;&lt;/script&gt;\"")
    }

    @Test
    fun `o motor so resolve templates xhtml e txt`() {
        assertThatThrownBy {
            engine.process(
                "application.yaml",
                Context(Locale.ROOT),
            )
        }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { engine.process("templates/printingexample/card", Context(Locale.ROOT)) }
            .isInstanceOf(Exception::class.java)
    }
}

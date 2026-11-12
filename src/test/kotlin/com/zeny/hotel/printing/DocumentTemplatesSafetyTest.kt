package com.zeny.hotel.printing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

/**
 * Varre os templates do classpath **principal** (`src/main/resources/templates`) e recusa as construções que
 * D-E11-3 proíbe: `th:utext`, o inline sem escape `[( )]`, o pré-processamento e fragmento escolhido por variável
 * (salvo o `th:insert` do corpo no layout). O teste prova a si mesmo com amostras ruins em memória.
 */
class DocumentTemplatesSafetyTest {
    @Test
    fun `os templates do classpath principal usam so as construcoes permitidas`() {
        val root = Path.of("src/main/resources/templates")
        if (!Files.isDirectory(root)) return
        val violations =
            Files.walk(root).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.extension in setOf("xhtml", "txt") }
                    .toList()
                    .flatMap { file ->
                        val classpath = file.invariantSeparatorsPathString.substringAfter("src/main/resources/")
                        violations(classpath, file.readText())
                    }
            }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `recusa th utext e o inline sem escape`() {
        val d = "$"
        listOf(
            """<p th:utext="$d{document.a}">x</p>""",
            """<p data-th-utext="$d{document.a}">x</p>""",
            "<p>[($d{document.a})]</p>",
            "Nome: [($d{document.a})]",
        ).forEach { assertThat(violations("templates/x/a.xhtml", it)).describedAs(it).isNotEmpty() }
    }

    @Test
    fun `recusa o pre processamento`() {
        val d = "$"
        listOf(
            """<p th:text="$d{document.__$d{x}__}">x</p>""",
            """<p th:text="__x__">x</p>""",
            """<p th:with="a=__x__">x</p>""",
            "<p>[[__x__]]</p>",
            "<p>__$d{x}__</p>",
            "<p>__*{x}__</p>",
            "<p>__#{x}__</p>",
            "<p>__@{x}__</p>",
            "<p>__~{x}__</p>",
            "Texto __$d{x}__",
        ).forEach { assertThat(violations("templates/x/a.xhtml", it)).describedAs(it).isNotEmpty() }
    }

    @Test
    fun `recusa fragmento escolhido por variavel`() {
        val d = "$"
        listOf("insert", "replace", "include").forEach { attr ->
            listOf(
                """<div th:$attr="~{$d{x} :: body}"></div>""",
                """<div th:$attr='~{$d{x} :: body}'></div>""",
                """<div data-th-$attr="$d{x}"></div>""",
            ).forEach { assertThat(violations("templates/x/a.xhtml", it)).describedAs("$attr $it").isNotEmpty() }
        }
    }

    @Test
    fun `o th insert do corpo so passa no layout do printing`() {
        val body = """<div th:insert="~{${'$'}{bodyTemplate} :: body}"></div>"""

        assertThat(violations("templates/printing/layout.xhtml", body)).isEmpty()
        assertThat(violations("templates/frontdesk/card.xhtml", body)).isNotEmpty()
        assertThat(violations("templates/printing/layout.xhtml", body.replace("body}", "other}"))).isNotEmpty()
        assertThat(violations("templates/printing/layout.xhtml", body.replace("th:insert", "th:replace"))).isNotEmpty()
    }

    @Test
    fun `aceita o que e permitido`() {
        val d = "$"
        val good =
            """
            <p th:text="$d{document.name}">x</p>
            <li th:each="i : $d{document.items}" th:text="$d{i}">i</li>
            <span>[[$d{document.name}]]</span>
            <div th:insert="~{printing/fragment :: header}"></div>
            <p>Assinatura: ______________________</p>
            """.trimIndent()

        assertThat(violations("templates/x/a.xhtml", good)).isEmpty()
    }

    @Test
    fun `pega cada forma proibida com variacoes de espaco aspas e quebra de linha`() {
        val d = "$"
        listOf(
            "<p th:utext = \"$d{document.a}\">x</p>",
            "<p th:utext\n=\n'$d{document.a}'>x</p>",
            "<p\n  data-th-utext=\"$d{document.a}\">x</p>",
            "<p th:text=\"$d{document.a}\n  + __$d{x}__\">x</p>",
            "<p th:attr=\"title=__$d{x}__\">x</p>",
            "<p th:attr='title=__x__'>x</p>",
            "<p data-th-text=\"__x__\">x</p>",
            "<p>[[\n$d{a}\n__x__\n]]</p>",
            "<p>[($d{a})]</p>",
            "[# th:utext=\"$d{document.a}\"]x[/]",
            "[# th:insert=\"$d{x}\"]x[/]",
            "<div th:replace = \"~{\n$d{x} :: body}\"></div>",
            "<div data-th-include='~{$d{x}}'></div>",
            "<div th:substituteby=\"$d{x}\"></div>",
        ).forEach { assertThat(violations("templates/x/a.xhtml", it)).describedAs(it).isNotEmpty() }
    }

    @Test
    fun `aplica as mesmas regras ao template de texto`() {
        val d = "$"
        listOf("[($d{document.a})]", "[[__$d{x}__]]", "x __#{k}__ y", "[# th:utext=\"$d{a}\"]x[/]")
            .forEach { assertThat(violations("templates/x/a.txt", it)).describedAs(it).isNotEmpty() }
    }

    @Test
    fun `sublinhado em texto comum passa mas as formas de pre processamento nao`() {
        val d = "$"
        listOf("______", "Assinatura: ____________________________", "<p>a__b</p>", "__", "_{x}_").forEach {
            assertThat(violations("templates/x/a.xhtml", it)).describedAs(it).isEmpty()
        }
        assertThat(violations("templates/x/a.xhtml", "______$d{x}")).isNotEmpty()
    }

    @Test
    fun `o th insert do layout so passa no caminho exato do layout do printing`() {
        val body = """<div th:insert="~{${'$'}{bodyTemplate} :: body}"></div>"""

        listOf(
            "templates/printing/other.xhtml",
            "templates/other/printing/layout.xhtml",
            "templates/printing/layout.txt",
            "templates/printing/Layout.xhtml",
        ).forEach { assertThat(violations(it, body)).describedAs(it).isNotEmpty() }
        assertThat(violations("templates/printing/layout.xhtml", body.replace("bodyTemplate", "other"))).isNotEmpty()
        assertThat(violations("templates/printing/layout.xhtml", body.replace("th:insert", "data-th-replace")))
            .isNotEmpty()
    }

    private fun violations(
        path: String,
        content: String,
    ): List<String> = TemplateRules.violations(path, content).map { "$path: $it" }

    private object TemplateRules {
        private const val LAYOUT = "templates/printing/layout.xhtml"
        private const val BODY_INSERT = """th:insert="~{${'$'}{bodyTemplate} :: body}""""
        private val UTEXT = Regex("""(?:th:|data-th-)utext""")
        private val INLINE_UNESCAPED = Regex("""\[\(""")
        private val PREPROCESS_FORM = Regex("""__[$*#@~]\{""")
        private val TH_ATTRIBUTE = Regex("""(?:th:|data-th-)[\w-]+\s*=\s*("[^"]*"|'[^']*')""")
        private val INLINE_ESCAPED = Regex("""\[\[(.*?)]]""", RegexOption.DOT_MATCHES_ALL)
        private val FRAGMENT =
            Regex("""((?:th:|data-th-)(?:insert|replace|include|substituteby))\s*=\s*("[^"]*"|'[^']*')""")

        fun violations(
            path: String,
            content: String,
        ): List<String> {
            val found = mutableListOf<String>()
            if (UTEXT.containsMatchIn(content)) found += "th:utext"
            if (INLINE_UNESCAPED.containsMatchIn(content)) found += "inline sem escape [( )]"
            if (PREPROCESS_FORM.containsMatchIn(content)) found += "pré-processamento (__\${ e afins)"
            if (TH_ATTRIBUTE.findAll(content).any { "__" in it.groupValues[1] }) found += "__ em atributo th:*"
            if (INLINE_ESCAPED.findAll(content).any { "__" in it.groupValues[1] }) found += "__ em [[ ]]"
            FRAGMENT.findAll(content).forEach {
                val isBodyInsert = path == LAYOUT && it.value.replace(" ", "") == BODY_INSERT.replace(" ", "")
                if ("\${" in it.groupValues[2] && !isBodyInsert) found += "fragmento por variável: ${it.value}"
            }
            return found
        }
    }
}

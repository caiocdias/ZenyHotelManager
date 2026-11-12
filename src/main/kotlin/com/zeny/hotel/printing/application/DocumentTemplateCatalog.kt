package com.zeny.hotel.printing.application

import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.ModuleDocumentTemplates
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/**
 * O catálogo de templates de documento (D-E11-2): a união dos [ModuleDocumentTemplates] dos módulos, conferida no boot,
 * e o [engine] que os renderiza. Recusa a partida com módulo declarado duas vezes, código repetido, código fora do
 * padrão `<módulo>.<documento>`, prefixo de outro módulo, caminho fora de `templates/<módulo>/` (`.xhtml` e `.txt`) ou
 * template inexistente no classpath; a mensagem cita o código.
 *
 * O [engine] tem dois resolvedores de classpath (UTF-8, cache ligado): arquivos `.xhtml` em [TemplateMode.XML] e
 * `.txt` em [TemplateMode.TEXT], só sob `templates/`; nomes fora dali não resolvem. O nome do template é o caminho
 * completo do classpath (`templates/<módulo>/<nome>.xhtml`), sem prefixo nem sufixo.
 */
class DocumentTemplateCatalog(
    modules: List<ModuleDocumentTemplates>,
    classLoader: ClassLoader = DocumentTemplateCatalog::class.java.classLoader,
) {
    private val templates: Map<String, DocumentTemplate>

    val engine: SpringTemplateEngine

    init {
        val repeatedModules = modules.groupBy { it.module }.filterValues { it.size > 1 }.keys
        require(repeatedModules.isEmpty()) { "Templates de documento declarados mais de uma vez para $repeatedModules" }
        val all =
            modules.flatMap { module ->
                module.templates.onEach { validate(module.module, it, classLoader) }
            }
        val repeated = all.groupBy { it.code }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Template de documento repetido: $repeated" }
        templates = all.associateBy { it.code }
        engine = newEngine()
    }

    /** O template do [code]; código desconhecido é erro de programação (`IllegalArgumentException`). */
    fun template(code: String): DocumentTemplate =
        requireNotNull(templates[code]) { "Template de documento desconhecido: '$code'" }

    private fun validate(
        module: String,
        template: DocumentTemplate,
        classLoader: ClassLoader,
    ) {
        val code = template.code
        require(CODE.matches(code)) { "Código de template fora do padrão <módulo>.<documento>: '$code'" }
        require(code.substringBefore('.') == module) { "O template '$code' não é do módulo $module" }
        require(template.title.isNotBlank()) { "Template '$code' sem título" }
        checkPath(code, template.xhtmlTemplate, "templates/$module/", ".xhtml", classLoader)
        checkPath(code, template.textTemplate, "templates/$module/", ".txt", classLoader)
    }

    private fun checkPath(
        code: String,
        path: String,
        prefix: String,
        suffix: String,
        classLoader: ClassLoader,
    ) {
        val name = path.removePrefix(prefix).removeSuffix(suffix)
        require(path.startsWith(prefix) && path.endsWith(suffix) && NAME.matches(name)) {
            "O template '$code' usa o caminho '$path', fora de $prefix<nome>$suffix"
        }
        requireNotNull(classLoader.getResource(path)) {
            "O template '$code' aponta para '$path', que não existe no classpath"
        }
    }

    private fun newEngine(): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(resolver(order = 1, pattern = "templates/*.xhtml", mode = TemplateMode.XML))
            addTemplateResolver(resolver(order = 2, pattern = "templates/*.txt", mode = TemplateMode.TEXT))
        }

    private fun resolver(
        order: Int,
        pattern: String,
        mode: TemplateMode,
    ) = ClassLoaderTemplateResolver().apply {
        this.order = order
        resolvablePatterns = setOf(pattern)
        templateMode = mode
        characterEncoding = "UTF-8"
        isCacheable = true
        checkExistence = true
    }

    private companion object {
        val CODE = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+")
        val NAME = Regex("[a-z][a-z0-9-]*")
    }
}

@Configuration(proxyBeanMethods = false)
class DocumentTemplatesConfiguration {
    @Bean
    fun documentTemplateCatalog(modules: ObjectProvider<ModuleDocumentTemplates>): DocumentTemplateCatalog =
        DocumentTemplateCatalog(modules.orderedStream().toList())
}

package printingexample

import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.ModuleDocumentTemplates
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** Templates de um módulo de exemplo, só de teste (como um módulo de negócio registra os seus). */
object PrintingExampleTemplates : ModuleDocumentTemplates {
    override val module = "printingexample"

    const val CARD = "printingexample.card"

    override val templates =
        listOf(
            DocumentTemplate(
                code = CARD,
                title = "Ficha de exemplo",
                xhtmlTemplate = "templates/printingexample/card.xhtml",
                textTemplate = "templates/printingexample/card.txt",
            ),
        )
}

/** Liga o módulo de exemplo ao contexto: os templates entram no catálogo no boot. */
@TestConfiguration(proxyBeanMethods = false)
class PrintingExamplesConfiguration {
    @Bean
    fun printingExampleTemplates(): ModuleDocumentTemplates = PrintingExampleTemplates
}

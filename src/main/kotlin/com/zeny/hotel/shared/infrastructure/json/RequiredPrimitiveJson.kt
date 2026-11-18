package com.zeny.hotel.shared.infrastructure.json

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.DeserializationProblemHandler
import tools.jackson.databind.exc.InvalidNullException

/**
 * `null` num campo de tipo primitivo (`Int`, `Long`, `Boolean`… não anulável no Kotlin) é "sem valor", como nos demais
 * tipos (M6 E20; ARCHITECTURE §7, "Datas e valores"): o Jackson recusa com um erro de formato, e este handler o troca
 * por [InvalidNullException], que a API devolve como `REQUIRED` no campo e o offline como `COMMAND_PAYLOAD_INVALID`.
 * O texto vazio (`""`) num primitivo continua erro de formato (`INVALID_FORMAT`). Vale no mapeador único, para a API e
 * para os payloads offline. O primitivo ausente não passa por aqui: o Jackson o recusa como parâmetro de criador
 * obrigatório, e o `FieldViolations` da API o traduz para `REQUIRED`.
 */
@Configuration(proxyBeanMethods = false)
class RequiredPrimitiveJson {
    @Bean
    fun requiredPrimitiveJsonCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder -> builder.addHandler(NullPrimitiveAsRequired) }
}

private object NullPrimitiveAsRequired : DeserializationProblemHandler() {
    override fun handleNullForPrimitives(
        context: DeserializationContext,
        targetType: Class<*>,
        parser: JsonParser,
        deserializer: ValueDeserializer<*>?,
        failureMessage: String,
    ): Any? {
        // `""` coagido a `null` também chega aqui: segue o tratamento padrão (erro de formato).
        if (parser.hasToken(JsonToken.VALUE_STRING)) return NOT_HANDLED
        throw InvalidNullException.from(context, null, context.constructType(targetType))
    }
}

package com.zeny.hotel.shared.infrastructure.json

import com.fasterxml.jackson.annotation.JsonFormat
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.Version
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.deser.std.DelegatingDeserializer
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Leitura estrita de datas e instantes no mapeador único da aplicação (ARCHITECTURE §7, "Datas e valores"): vale
 * para todo corpo JSON da API e para os payloads dos comandos offline, que passam pelo mesmo `JsonMapper`.
 *
 * Num [LocalDate] só vale o texto `ISO_LOCAL_DATE` e num [Instant] só o texto ISO-8601 com `T` (`ISO_INSTANT`), os
 * dois sem espaço nas pontas; o ano com sinal e mais de 4 dígitos, que o próprio servidor escreve nos extremos do
 * `StorableRange`, continua legível. Número (época), época em texto, data-hora numa data, array, objeto e `""` são
 * recusados com um erro de leitura no campo, que a API devolve como `400 VALIDATION_FAILED` com `INVALID_FORMAT`.
 * `null` e o campo ausente não passam por aqui e seguem como antes. A escrita não muda.
 *
 * Duas camadas: o `IsoText` confere o token e o texto antes de entregar ao deserializador do Jackson, e a
 * `leniency = false` nos dois tipos deixa esse deserializador também no modo estrito, como segunda barreira.
 */
@Configuration(proxyBeanMethods = false)
class StrictDateTimeJson {
    @Bean
    fun strictDateTimeJsonCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            ISO_TEXT.keys.forEach { type ->
                builder.withConfigOverride(type) { it.setFormat(JsonFormat.Value.forLeniency(false)) }
            }
            builder.addModule(StrictDateTimeModule())
        }
}

/** O formato aceito por tipo. */
private val ISO_TEXT: Map<Class<*>, DateTimeFormatter> =
    mapOf(
        LocalDate::class.java to DateTimeFormatter.ISO_LOCAL_DATE,
        Instant::class.java to DateTimeFormatter.ISO_INSTANT,
    )

/** Envolve o deserializador de cada tipo de [ISO_TEXT] num [IsoText]. */
private class StrictDateTimeModule : JacksonModule() {
    override fun getModuleName(): String = "zeny-strict-date-time"

    override fun version(): Version = Version.unknownVersion()

    override fun setupModule(context: SetupContext) {
        context.addDeserializerModifier(
            object : ValueDeserializerModifier() {
                override fun modifyDeserializer(
                    config: DeserializationConfig,
                    description: BeanDescription.Supplier,
                    deserializer: ValueDeserializer<*>,
                ): ValueDeserializer<*> =
                    ISO_TEXT[description.beanClass]?.let { format -> IsoText(deserializer, format) } ?: deserializer
            },
        )
    }
}

/**
 * Só deixa passar ao deserializador do Jackson um texto que o [format] lê inteiro. A recusa não repete o valor
 * recebido na mensagem.
 */
private class IsoText(
    delegate: ValueDeserializer<*>,
    private val format: DateTimeFormatter,
) : DelegatingDeserializer(delegate) {
    override fun newDelegatingInstance(newDelegatee: ValueDeserializer<*>): ValueDeserializer<*> =
        IsoText(newDelegatee, format)

    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Any? {
        if (!parser.hasToken(JsonToken.VALUE_STRING) || !isIso(parser.string)) {
            return context.reportInputMismatch(this, "esperado o texto ISO-8601 de %s", handledType().simpleName)
        }
        return super.deserialize(parser, context)
    }

    private fun isIso(text: String): Boolean =
        try {
            format.parse(text)
            true
        } catch (_: DateTimeParseException) {
            false
        }
}

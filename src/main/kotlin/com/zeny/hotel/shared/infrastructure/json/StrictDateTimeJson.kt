package com.zeny.hotel.shared.infrastructure.json

import com.fasterxml.jackson.annotation.JsonFormat
import com.zeny.hotel.shared.StorableRange
import com.zeny.hotel.shared.UnboundedInstant
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.Version
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.deser.std.DelegatingDeserializer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Leitura estrita de valores escalares no mapeador único da aplicação (ARCHITECTURE §7, "Datas e valores"): vale
 * para todo corpo JSON da API e para os payloads dos comandos offline, que passam pelo mesmo `JsonMapper`.
 *
 * - [LocalDate] e [Instant]: só o texto `ISO_LOCAL_DATE` e o texto ISO-8601 com `T` (`ISO_INSTANT`), sem espaço nas
 *   pontas, e dentro do [StorableRange]; o ano com sinal e mais de 4 dígitos, que o servidor escreve nos extremos,
 *   continua legível. Número (época), época em texto, data-hora numa data, array, objeto e `""` são recusados.
 * - [LocalTime]: só `HH:mm` ou `HH:mm:ss` (duas casas, `00:00` a `23:59:59`), sem fração, zona nem `24:00`.
 * - `Int`, `Long` e `Short`: qualquer número fracionário é recusado, inclusive `7.0` e `7e0` (o Jackson truncaria).
 *   O inteiro em texto (`"7"`) segue como antes (decisão D4 da E20).
 * - `Int`, `Long`, `Short` e `Boolean`, anuláveis ou não: o texto vazio ou em branco é recusado (o Jackson o leria
 *   como `null`, e o campo perderia o valor em silêncio).
 * - [UUID]: só a forma canônica de 36 caracteres (`8-4-4-4-12` dígitos hexadecimais), sem espaço nas pontas; `""`,
 *   texto em branco e a forma base64, que o Jackson aceitaria (os dois primeiros como `null`), são recusados.
 *
 * A recusa é um erro de leitura no campo, que a API devolve como `400 VALIDATION_FAILED` com `INVALID_FORMAT`, e a
 * mensagem não repete o valor recebido. `null` e o campo ausente não passam por aqui e seguem como antes. A escrita
 * não muda.
 *
 * Exceção, só para o envelope de sincronização offline: o [Instant] de uma propriedade marcada com
 * [UnboundedInstant] mantém o formato estrito e perde a checagem de faixa, para o comando fora do plausível ser
 * recusado por comando (`DEVICE_TIME_INVALID`) e não derrubar o lote.
 *
 * Duas camadas nas datas: o [StrictScalar] confere o token e o texto antes de entregar ao deserializador do Jackson,
 * e a `leniency = false` nos dois tipos deixa esse deserializador também no modo estrito, como segunda barreira.
 */
@Configuration(proxyBeanMethods = false)
class StrictDateTimeJson {
    @Bean
    fun strictDateTimeJsonCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            listOf(LocalDate::class.java, Instant::class.java, LocalTime::class.java).forEach { type ->
                builder.withConfigOverride(type) { it.setFormat(JsonFormat.Value.forLeniency(false)) }
            }
            builder.addModule(StrictScalarModule())
        }
}

private val TIME_TEXT = Regex("""([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?""")

/** Uma regra de leitura: o que o token aceita antes do Jackson e, depois dele, se o valor lido é aceito. */
private interface ScalarRule {
    val expected: String

    fun accepts(parser: JsonParser): Boolean

    fun accepts(value: Any?): Boolean = true
}

private fun isoRule(
    format: DateTimeFormatter,
    name: String,
    inRange: (Any?) -> Boolean = { true },
) = object : ScalarRule {
    override val expected = "o texto ISO-8601 de $name"

    override fun accepts(parser: JsonParser): Boolean =
        parser.hasToken(JsonToken.VALUE_STRING) &&
            try {
                format.parse(parser.string)
                true
            } catch (_: DateTimeParseException) {
                false
            }

    override fun accepts(value: Any?): Boolean = inRange(value)
}

private val UUID_TEXT = Regex("""\p{XDigit}{8}-\p{XDigit}{4}-\p{XDigit}{4}-\p{XDigit}{4}-\p{XDigit}{12}""")

/** O texto vazio ou em branco num escalar que não é texto: o Jackson o leria como `null`. */
private fun JsonParser.isBlankText(): Boolean = hasToken(JsonToken.VALUE_STRING) && string.isBlank()

private val INT_RULE =
    object : ScalarRule {
        override val expected = "um número inteiro"

        override fun accepts(parser: JsonParser): Boolean =
            !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT) && !parser.isBlankText()
    }

private val BOOLEAN_RULE =
    object : ScalarRule {
        override val expected = "um booleano"

        override fun accepts(parser: JsonParser): Boolean = !parser.isBlankText()
    }

private val INSTANT_RULE = isoRule(DateTimeFormatter.ISO_INSTANT, "Instant") { StorableRange.contains(it as Instant) }

/** O [Instant] só no formato, sem a faixa: o das propriedades marcadas com [UnboundedInstant]. */
private val UNBOUNDED_INSTANT_RULE = isoRule(DateTimeFormatter.ISO_INSTANT, "Instant")

/** A regra por tipo. */
private val RULES: Map<Class<*>, ScalarRule> =
    mapOf(
        LocalDate::class.java to
            isoRule(DateTimeFormatter.ISO_LOCAL_DATE, "LocalDate") { StorableRange.contains(it as LocalDate) },
        Instant::class.java to INSTANT_RULE,
        LocalTime::class.java to
            object : ScalarRule {
                override val expected = "a hora HH:mm ou HH:mm:ss"

                override fun accepts(parser: JsonParser): Boolean =
                    parser.hasToken(JsonToken.VALUE_STRING) && TIME_TEXT.matches(parser.string)
            },
        UUID::class.java to
            object : ScalarRule {
                override val expected = "um UUID"

                override fun accepts(parser: JsonParser): Boolean =
                    !parser.hasToken(JsonToken.VALUE_STRING) || UUID_TEXT.matches(parser.string)
            },
        Integer.TYPE to INT_RULE,
        Int::class.javaObjectType to INT_RULE,
        java.lang.Long.TYPE to INT_RULE,
        Long::class.javaObjectType to INT_RULE,
        java.lang.Short.TYPE to INT_RULE,
        Short::class.javaObjectType to INT_RULE,
        java.lang.Boolean.TYPE to BOOLEAN_RULE,
        Boolean::class.javaObjectType to BOOLEAN_RULE,
    )

/** Envolve o deserializador de cada tipo de [RULES] num [StrictScalar]. */
private class StrictScalarModule : JacksonModule() {
    override fun getModuleName(): String = "zeny-strict-scalar"

    override fun version(): Version = Version.unknownVersion()

    override fun setupModule(context: SetupContext) {
        context.addDeserializerModifier(
            object : ValueDeserializerModifier() {
                override fun modifyDeserializer(
                    config: DeserializationConfig,
                    description: BeanDescription.Supplier,
                    deserializer: ValueDeserializer<*>,
                ): ValueDeserializer<*> =
                    RULES[description.beanClass]?.let { rule -> StrictScalar(deserializer, rule) } ?: deserializer
            },
        )
    }
}

/**
 * Só deixa passar ao deserializador do Jackson o que a [rule] aceita. A recusa não repete o valor recebido. Numa
 * propriedade marcada com [UnboundedInstant], o [Instant] perde só a checagem de faixa.
 */
private class StrictScalar(
    delegate: ValueDeserializer<*>,
    private val rule: ScalarRule,
) : DelegatingDeserializer(delegate) {
    override fun createContextual(
        context: DeserializationContext,
        property: BeanProperty?,
    ): ValueDeserializer<*> {
        val contextual = super.createContextual(context, property)
        return if (contextual is StrictScalar && rule === INSTANT_RULE &&
            property?.getAnnotation(UnboundedInstant::class.java) != null
        ) {
            StrictScalar(contextual.getDelegatee(), UNBOUNDED_INSTANT_RULE)
        } else {
            contextual
        }
    }

    override fun newDelegatingInstance(newDelegatee: ValueDeserializer<*>): ValueDeserializer<*> =
        StrictScalar(newDelegatee, rule)

    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Any? {
        if (!rule.accepts(parser)) return refuse(context)
        val value = super.deserialize(parser, context)
        return if (rule.accepts(value)) value else refuse(context)
    }

    private fun refuse(context: DeserializationContext): Any? =
        context.reportInputMismatch(this, "esperado %s", rule.expected)
}

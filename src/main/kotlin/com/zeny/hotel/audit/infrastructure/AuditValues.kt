package com.zeny.hotel.audit.infrastructure

import com.zeny.hotel.shared.AuditMasked
import tools.jackson.core.JsonGenerator
import tools.jackson.core.Version
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.cfg.MapperConfig
import tools.jackson.databind.introspect.Annotated
import tools.jackson.databind.introspect.NopAnnotationIntrospector
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.ser.std.StdSerializer

/**
 * Converte os retratos de auditoria em JSON e mascara o que nunca pode ir para a trilha
 * (RF-ACS-11, restrições do roadmap do M1): senha, PIN, tokens, hashes, segredos e valores
 * cifrados saem como [MASK]. Três regras, aplicadas a todo nível do JSON:
 *
 * 1. **Anotação:** propriedade com [AuditMasked].
 * 2. **Nome:** propriedade cujo nome tem uma das [SENSITIVE_WORDS] (em camelCase, snake_case ou
 *    kebab-case: `pinHash`, `refresh_token`), com qualquer valor que não seja booleano (`hasPin` e
 *    `passwordChangeRequired` continuam).
 * 3. **Formato:** texto com cara de segredo, em qualquer propriedade: valor cifrado pelo
 *    `FieldCipher` (`v1:<chave>:<base64url>`), token selado pelo `TokenSealer` (`t1:<chave>:<base64url>`),
 *    hash Argon2 ou bcrypt e JWT.
 *
 * O resultado é sempre um objeto JSON: um retrato que não é objeto vira `{"value": …}`.
 */
class AuditValues(
    jsonMapper: JsonMapper,
) {
    private val mapper: JsonMapper = jsonMapper.rebuild().addModule(MaskingModule()).build()

    /** O retrato como JSON mascarado, ou `null` sem retrato. */
    fun toJson(snapshot: Any?): String? {
        if (snapshot == null) return null
        val tree = mask(mapper.valueToTree<JsonNode>(snapshot))
        val root = if (tree.isObject) tree else JsonNodeFactory.instance.objectNode().set("value", tree)
        return mapper.writeValueAsString(root)
    }

    /** O JSON gravado como mapa, para a consulta. */
    fun fromJson(json: String?): Map<String, Any?>? =
        json?.let {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(it, Map::class.java) as Map<String, Any?>
        }

    private fun mask(node: JsonNode): JsonNode =
        when {
            node.isString && looksSecret(node.stringValue()) -> {
                masked()
            }

            node is ObjectNode -> {
                node.properties().toList().forEach { (name, value) ->
                    node.set(
                        name,
                        if (isSensitiveName(name) && !value.isBoolean &&
                            !value.isNull
                        ) {
                            masked()
                        } else {
                            mask(value)
                        },
                    )
                }
                node
            }

            node is ArrayNode -> {
                for (index in 0 until node.size()) node.set(index, mask(node.get(index)))
                node
            }

            else -> {
                node
            }
        }

    companion object {
        const val MASK = "***"

        /** Palavras que marcam uma propriedade sensível pelo nome. */
        val SENSITIVE_WORDS =
            setOf(
                "password",
                "passwd",
                "senha",
                "pin",
                "token",
                "tokens",
                "secret",
                "secrets",
                "hash",
                "salt",
                "credential",
                "credentials",
                "cipher",
                "ciphertext",
                "encrypted",
            )

        private val WORD_SEPARATOR = Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")

        private val SECRET_FORMATS =
            listOf(
                // FieldCipher: v<versão>:<id da chave>:<base64url do nonce + texto cifrado>.
                Regex("""^v\d+:[A-Za-z0-9._-]+:[A-Za-z0-9_-]{16,}={0,2}$"""),
                // TokenSealer: t<versão>:<id da chave>:<base64url do nonce + texto selado + tag>, sem
                // padding; nonce (12 bytes) e tag (16) já dão 38 caracteres, então texto curto não casa.
                Regex("""^t\d+:[A-Za-z0-9._-]+:[A-Za-z0-9_-]{38,}$"""),
                Regex("^\\${'$'}argon2(?:id|i|d)\\${'$'}.*"),
                Regex("^\\${'$'}2[aby]?\\${'$'}\\d{2}\\${'$'}.*"),
                // JWT (cabeçalho, corpo e assinatura em base64url; o cabeçalho JSON começa por "eyJ").
                Regex("""^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$"""),
            )

        fun isSensitiveName(name: String): Boolean =
            name.split(WORD_SEPARATOR).any { it.isNotEmpty() && it.lowercase() in SENSITIVE_WORDS }

        fun looksSecret(value: String): Boolean = SECRET_FORMATS.any { it.matches(value) }

        private fun masked(): JsonNode = JsonNodeFactory.instance.stringNode(MASK)
    }
}

/** Registra o [MaskingIntrospector] junto com os do mapper (Kotlin, anotações do Jackson). */
private class MaskingModule : JacksonModule() {
    override fun getModuleName(): String = "zeny-audit-masking"

    override fun version(): Version = Version.unknownVersion()

    override fun setupModule(context: SetupContext) {
        context.insertAnnotationIntrospector(MaskingIntrospector())
    }
}

/** Serializa toda propriedade com [AuditMasked] como [AuditValues.MASK]. */
private class MaskingIntrospector : NopAnnotationIntrospector() {
    override fun findSerializer(
        config: MapperConfig<*>,
        annotated: Annotated,
    ): Any? = if (annotated.hasAnnotation(AuditMasked::class.java)) MaskedSerializer else null
}

private object MaskedSerializer : StdSerializer<Any>(Any::class.java) {
    override fun serialize(
        value: Any,
        generator: JsonGenerator,
        context: SerializationContext,
    ) {
        generator.writeString(AuditValues.MASK)
    }
}

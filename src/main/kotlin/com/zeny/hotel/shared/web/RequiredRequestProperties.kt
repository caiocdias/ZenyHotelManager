package com.zeny.hotel.shared.web

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springdoc.core.customizers.OpenApiCustomizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

private const val SCHEMA_REF = "#/components/schemas/"

/**
 * A classe de cada schema nomeado do contrato, anotada enquanto o swagger-core resolve os tipos: o
 * [RequiredRequestProperties] lê dela as propriedades obrigatórias do DTO.
 */
class SchemaClasses : ModelConverter {
    private val byName = ConcurrentHashMap<String, Class<*>>()

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>,
    ): Schema<*>? {
        if (!chain.hasNext()) return null
        val schema = chain.next().resolve(type, context, chain)
        // O tipo de uma propriedade volta como `$ref` ao schema nomeado, sem o nome.
        val name = schema?.name ?: schema?.`$ref`?.takeIf { it.startsWith(SCHEMA_REF) }?.removePrefix(SCHEMA_REF)
        name?.let { byName[it] = Json.mapper().constructType(type.type).rawClass }
        return schema
    }

    /** A classe do schema [name], ou `null` se nenhum tipo resolvido tem esse nome. */
    fun of(name: String): Class<*>? = byName[name]
}

/**
 * O `required` dos schemas de request sai de uma regra única (M6 E20, D1; ARCHITECTURE §7, "Contrato"): a propriedade
 * é obrigatória quando o DTO a marca com `@NotNull`, `@NotBlank` ou `@NotEmpty` no campo ou no getter, ou quando é
 * uma propriedade Kotlin não anulável sem valor padrão; as demais (anuláveis sem essas anotações, ou com valor padrão)
 * o servidor aceita ausentes e saem do `required`. Um schema que alguma resposta também alcança não perde `required`
 * (D5: tirar seria breaking change na resposta), só ganha os da regra. O `nullable` não muda (D3).
 */
class RequiredRequestProperties(
    private val classes: SchemaClasses,
) : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        val schemas = openApi.components?.schemas ?: return
        val operations =
            openApi.paths
                .orEmpty()
                .values
                .flatMap { it.readOperations() }
        val requests = reachable(schemas, operations.mapNotNull { it.requestBody?.content })
        val responses =
            reachable(
                schemas,
                operations.flatMap { op ->
                    op.responses
                        .orEmpty()
                        .values
                        .mapNotNull { it.content }
                },
            )
        requests.forEach { name ->
            val schema = schemas[name] ?: return@forEach
            val type = classes.of(name) ?: return@forEach
            val mandatory = mandatoryProperties(type)
            val kept = if (name in responses) schema.required.orEmpty().toSet() else emptySet()
            schema.required =
                schema.properties
                    .orEmpty()
                    .keys
                    .filter { it in mandatory || it in kept }
                    .ifEmpty { null }
        }
    }

    private fun reachable(
        schemas: Map<String, Schema<*>>,
        contents: List<Content>,
    ): Set<String> {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(contents.flatMap { content -> content.values.mapNotNull { it.schema } })
        while (pending.isNotEmpty()) {
            val schema = pending.removeFirst()
            schema.`$ref`?.removePrefix(SCHEMA_REF)?.let { name ->
                if (seen.add(name)) schemas[name]?.let(pending::add)
            }
            pending.addAll(schema.children())
        }
        return seen
    }

    private fun Schema<*>.children(): List<Schema<*>> =
        listOfNotNull(items, not, additionalProperties as? Schema<*>) +
            properties.orEmpty().values +
            allOf.orEmpty() +
            anyOf.orEmpty() +
            oneOf.orEmpty()

    private companion object {
        val MANDATORY = setOf(NotNull::class.java, NotBlank::class.java, NotEmpty::class.java)

        fun mandatoryProperties(type: Class<*>): Set<String> {
            val kotlinClass = type.kotlin
            val nonNullWithoutDefault =
                kotlinClass.primaryConstructor
                    ?.parameters
                    .orEmpty()
                    .filter { !it.isOptional && !it.type.isMarkedNullable }
                    .mapNotNull { it.name }
            val annotated =
                kotlinClass.memberProperties
                    .filter { property ->
                        listOfNotNull(property.javaField, property.javaGetter)
                            .flatMap { it.annotations.asList() }
                            .any { it.annotationClass.java in MANDATORY }
                    }.map { it.name }
            return (nonNullWithoutDefault + annotated).toSet()
        }
    }
}

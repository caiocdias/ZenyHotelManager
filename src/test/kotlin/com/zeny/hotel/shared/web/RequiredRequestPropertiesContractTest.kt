package com.zeny.hotel.shared.web

import com.fasterxml.jackson.databind.JsonNode
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.swagger.v3.core.util.Yaml
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * O `required` dos schemas de request do contrato versionado (`api/openapi.yaml`) sai da regra única do DTO (M6 E20,
 * D1; ARCHITECTURE §7, "Contrato"): `@NotNull`, `@NotBlank` ou `@NotEmpty` no campo ou no getter, ou propriedade
 * Kotlin não anulável sem valor padrão, é `required`; o resto não é. Um schema que alguma resposta alcança não perde
 * `required` (D5): nele, o `required` contém o da regra. O DTO de cada schema é achado pelo nome simples da classe.
 */
class RequiredRequestPropertiesContractTest {
    private val contract: JsonNode = Yaml.mapper().readTree(Path.of("api/openapi.yaml").toFile())
    private val schemas: JsonNode = contract["components"]["schemas"]
    private val operations: List<JsonNode> =
        contract["paths"].properties().flatMap { (_, item) -> item.properties().map { it.value } }
    private val requestSchemas: Set<String> = reachable(operations.mapNotNull { it["requestBody"] })
    private val responseSchemas: Set<String> = reachable(operations.mapNotNull { it["responses"] })
    private val classes by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.zeny.hotel")
            .groupBy { it.simpleName }
    }

    @Test
    fun `every request schema requires exactly the mandatory properties of its DTO, or more if a response uses it`() {
        val exempt = (requestSchemas intersect responseSchemas).sorted()
        println("Schemas de request alcançados por alguma resposta (D5, não perdem required): $exempt")
        exempt.forEach { name ->
            println("  $name mantém pela D5: ${(required(name) - mandatory(dtoOf(name))).sorted()}")
        }
        val divergences =
            requestSchemas.sorted().flatMap { name ->
                val rule = mandatory(dtoOf(name))
                val declared = required(name)
                val missing = (rule - declared).map { "$name.$it: obrigatório no DTO, fora do required" }
                val extra =
                    if (name in responseSchemas) {
                        emptyList()
                    } else {
                        (declared - rule).map { "$name.$it: required no contrato, opcional no DTO" }
                    }
                missing + extra
            }
        withClue(divergences.joinToString("\n", postfix = "\n")) { divergences.shouldBeEmpty() }
    }

    @Test
    fun `no mandatory annotation sits only on a constructor parameter, where the validator ignores it`() {
        val misplaced =
            requestSchemas.sorted().flatMap { name ->
                dtoOf(name)
                    .kotlin.primaryConstructor
                    ?.parameters
                    .orEmpty()
                    .filter { parameter -> parameter.annotations.any { it.annotationClass.java in MANDATORY } }
                    .map { "$name.${it.name}" }
            }
        misplaced.shouldBeEmpty()
    }

    @Test
    fun `the required of the samples follows the rule in both directions`() {
        required("CleaningTypeRequest") shouldBe
            setOf("code", "credits", "defaultPriority", "name", "requiresInspection")
        required("ReservationCancelRequest") shouldNotContain "waivePenalty"
        required("CreateGuestRequest") shouldNotContainAnyOf listOf("documents", "consents")
        WRITE_SAMPLES.forEach { (schema, expected) ->
            withClue(schema) { required(schema) shouldBe expected }
        }
    }

    @Test
    fun `a schema shared with a response keeps its required`() {
        responseSchemas shouldContainAll listOf("ChecklistSectionDto", "ChecklistItemDto")
        required("ChecklistSectionDto") shouldBe setOf("items", "title")
    }

    private fun required(schema: String): Set<String> =
        schemas[schema]["required"]?.map { it.asText() }.orEmpty().toSet()

    /** A classe do DTO pelo nome simples do schema; um nome que dois pacotes usam falha nomeando o schema. */
    private fun dtoOf(schema: String): Class<*> {
        val candidates = classes[schema].orEmpty()
        withClue("schema $schema: ${candidates.map { it.name }}") { candidates.size shouldBe 1 }
        return candidates.single().reflect()
    }

    /** Os nomes dos schemas que os nós alcançam por `$ref`, com os aninhados. */
    private fun reachable(roots: List<JsonNode>): Set<String> {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            node.findValues("\$ref").map { it.asText() }.filter { it.startsWith(SCHEMA_REF) }.forEach { ref ->
                val name = ref.removePrefix(SCHEMA_REF)
                if (seen.add(name)) pending.add(schemas[name])
            }
        }
        return seen
    }

    private companion object {
        const val SCHEMA_REF = "#/components/schemas/"
        val MANDATORY = setOf(NotNull::class.java, NotBlank::class.java, NotEmpty::class.java)

        /** Um request de escrita de cada módulo que recebe corpo, com o `required` que a regra dá ao DTO dele. */
        val WRITE_SAMPLES: Map<String, Set<String>> =
            mapOf(
                // billing: `quantity` tem padrão 1.
                "PostingRequest" to setOf("transactionCodeId", "unitPrice"),
                // checklists: `weekdays` tem padrão (lista vazia).
                "ChecklistRoutineRequest" to setOf("departmentId", "dueAt", "frequency", "name", "templateId"),
                "StartEndOfDayRunRequest" to setOf("businessDate"),
                "FnrhSettingsRequest" to setOf("enabled", "environment"),
                // frontdesk: `childAges` tem padrão (lista vazia).
                "WalkInRequest" to setOf("adults", "departure", "guests", "ratePlanId", "roomId", "segment"),
                "CreateGuestRequest" to setOf("profile"),
                "HotelRequest" to setOf("address", "cnpj", "legalName", "taxRegime", "timezone", "tradeName"),
                "ChangePasswordRequest" to setOf("currentPassword", "newPassword"),
                "PushDeviceRequest" to setOf("platform", "token"),
                "PrintAgentRequest" to setOf("name"),
                "CancellationPolicyRequest" to setOf("deadlineHours", "name", "penaltyType"),
                "ReservationCancelRequest" to setOf("reason"),
                // rooms: `Int` não anulável sem padrão é obrigatório; `displayOrder` e `featureIds` têm padrão.
                "RoomTypeRequest" to
                    setOf("code", "maxAdults", "maxChildren", "name", "standardAdults", "standardChildren"),
                "SyncCommandsRequest" to setOf("commands"),
                "DepartmentRequest" to setOf("name"),
            )

        /** Regra D1: obrigatório no DTO pela anotação no campo ou no getter, ou não anulável sem valor padrão. */
        fun mandatory(type: Class<*>): Set<String> {
            val kotlinClass = type.kotlin
            val byType =
                kotlinClass.primaryConstructor
                    ?.parameters
                    .orEmpty()
                    .filter { !it.isOptional && !it.type.isMarkedNullable }
                    .mapNotNull { it.name }
            val byAnnotation =
                kotlinClass.memberProperties
                    .filter { property ->
                        listOfNotNull(property.javaField, property.javaGetter)
                            .flatMap { it.annotations.asList() }
                            .any { it.annotationClass.java in MANDATORY }
                    }.map { it.name }
            return (byType + byAnnotation).toSet()
        }
    }
}

package com.zeny.hotel.shared.web

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.shared.ExtensibleEnum
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType
import java.nio.file.Path

/**
 * A convenção `x-extensible-enum` (decisão do usuário em 2026-10-19, ARCHITECTURE §7): toda propriedade de DTO marcada
 * com [ExtensibleEnum] sai no contrato versionado (`api/openapi.yaml`) como `string` com `x-extensible-enum` igual às
 * constantes do `enum`, na ordem, e sem `enum` (um valor novo não é breaking change).
 */
class ExtensibleEnumContractTest {
    private val contract: OpenAPI = Yaml.mapper().readValue(Path.of("api/openapi.yaml").toFile(), OpenAPI::class.java)

    @Test
    fun `every property marked ExtensibleEnum lists the enum constants as x-extensible-enum in the contract`() {
        val marked =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.zeny.hotel")
                .flatMap { it.fields }
                .filter { it.isAnnotatedWith(ExtensibleEnum::class.java) }
        withClue("o lançamento é o primeiro uso") {
            marked.map { "${it.owner.simpleName}.${it.name}" } shouldContain "PostingResponse.type"
        }
        withClue("a lista de canais do plano tarifário (M4 E01) é o primeiro uso numa lista") {
            marked.map { "${it.owner.simpleName}.${it.name}" } shouldContain "RatePlanResponse.channels"
        }
        marked.forEach { field ->
            withClue("${field.owner.simpleName}.${field.name}") {
                val declared =
                    contract.components.schemas[field.owner.simpleName]
                        .shouldNotBeNull()
                        .properties[field.name]
                        .shouldNotBeNull()
                // Numa lista de enum, a marca vale para os itens.
                val (type, property) =
                    if (List::class.java.isAssignableFrom(field.rawType.reflect())) {
                        declared.type shouldBe "array"
                        val argument = (field.reflect().genericType as ParameterizedType).actualTypeArguments.single()
                        (argument as Class<*>) to declared.items.shouldNotBeNull()
                    } else {
                        field.rawType.reflect() to declared
                    }
                type.isEnum shouldBe true
                property.enum.shouldBeNull()
                property.type shouldBe "string"
                @Suppress("UNCHECKED_CAST")
                (property.extensions?.get("x-extensible-enum") as List<String>?).shouldNotBeNull() shouldContainExactly
                    type.enumConstants.map { (it as Enum<*>).name }
            }
        }
    }

    @Test
    fun `the posting type of the contract is exactly PostingType`() {
        @Suppress("UNCHECKED_CAST")
        val listed =
            contract.components.schemas["PostingResponse"]!!
                .properties["type"]!!
                .extensions["x-extensible-enum"] as List<String>
        listed shouldContainExactly PostingType.entries.map { it.name }
    }
}

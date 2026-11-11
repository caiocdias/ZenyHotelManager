package com.zeny.hotel.printing

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** O contrato publicado (`api/openapi.yaml`) da administração de agentes e impressoras (M5 E10, Task 6). */
class PrintingContractAttackTest {
    private val contract: OpenAPI = Yaml.mapper().readValue(Path.of("api/openapi.yaml").toFile(), OpenAPI::class.java)

    private val printingPaths =
        contract.paths.filterKeys {
            it.startsWith("/api/v1/print-agents") ||
                it.startsWith("/api/v1/printers")
        }

    private val operations: List<Triple<String, String, Operation>> =
        printingPaths.flatMap { (uri, item) ->
            item.readOperationsMap().map { (method, op) -> Triple(method.name, uri, op) }
        }

    @Test
    fun `the administration routes are all published with the planned operation ids`() {
        operations.map { it.third.operationId }.shouldContainExactlyInAnyOrder(
            "createPrintAgent",
            "listPrintAgents",
            "getPrintAgent",
            "rotatePrintAgentToken",
            "deactivatePrintAgent",
            "getPrintAgentIdentity",
            "createPrinter",
            "listPrinters",
            "getPrinter",
            "updatePrinter",
            "deactivatePrinter",
            "pollPrintAgentJobs",
            "getPrintJobContent",
            "acknowledgePrintJob",
            "streamPrintAgentJobs",
        )
    }

    @Test
    fun `operation ids are unique across the whole contract`() {
        val ids =
            contract.paths.values
                .flatMap { it.readOperations() }
                .map { it.operationId }
        ids.size shouldBe ids.toSet().size
    }

    @Test
    fun `no printing operation opts out of security with an empty list`() {
        operations.forEach { (method, uri, op) ->
            withClue("$method $uri") { (op.security?.isEmpty() == true) shouldBe false }
        }
    }

    @Test
    fun `the token is a property of PrintAgentTokenResponse and of no other printing schema`() {
        val schemas = contract.components.schemas
        schemas["PrintAgentTokenResponse"]!!.properties.keys.contains("token") shouldBe true
        schemas["PrintAgentTokenResponse"]!!.required.contains("token") shouldBe true
        schemas
            .filterKeys { it.startsWith("Print") && it != "PrintAgentTokenResponse" }
            .forEach { (name, schema) ->
                withClue(name) {
                    schema.properties.orEmpty().keys.none {
                        it.contains(
                            "token",
                            true,
                        ) || it.contains("hash", true)
                    } shouldBe
                        true
                }
            }
    }

    @Test
    fun `only create and rotate return the token schema, with the exact success status`() {
        fun success(operationId: String) =
            operations
                .first { it.third.operationId == operationId }
                .third.responses

        fun ref(responses: Map<String, io.swagger.v3.oas.models.responses.ApiResponse>): String? =
            responses.entries
                .first { it.key.startsWith("2") }
                .value.content["application/json"]
                ?.schema
                ?.let { it.`$ref` ?: it.items?.`$ref` }
        val tokenRef = "#/components/schemas/PrintAgentTokenResponse"
        success("createPrintAgent").let {
            withClue("createPrintAgent") {
                it.keys.filter { k -> k.startsWith("2") } shouldBe listOf("201")
                ref(it) shouldBe tokenRef
            }
        }
        success("createPrinter").keys.filter { it.startsWith("2") } shouldBe listOf("201")
        success("rotatePrintAgentToken").let {
            it.keys.filter { k -> k.startsWith("2") } shouldBe listOf("200")
            ref(it) shouldBe tokenRef
        }
        listOf("getPrintAgent", "listPrintAgents", "deactivatePrintAgent").forEach {
            withClue(it) {
                success(it).keys.filter { k -> k.startsWith("2") } shouldBe listOf("200")
                ref(success(it)) shouldBe "#/components/schemas/PrintAgentResponse"
            }
        }
    }

    @Test
    fun `kind is a closed enum in the request and x-extensible-enum, without enum, in the response`() {
        val schemas = contract.components.schemas
        val request = schemas["PrinterRequest"]!!.properties["kind"]!!
        request.enum.map { it.toString() } shouldBe PrinterKind.entries.map { it.name }

        val response = schemas["PrinterResponse"]!!.properties["kind"]!!
        (response.enum.isNullOrEmpty()) shouldBe true
        (response.extensions["x-extensible-enum"] as List<*>).map { it.toString() } shouldBe
            PrinterKind.entries.map { it.name }
        schemas["PrinterChangeRequest"]!!.properties.keys shouldBe setOf("name", "address", "agentId")
    }

    @Test
    fun `the printer PUT documents the If-Match header and the read documents the ETag in prose`() {
        val put = operations.first { it.third.operationId == "updatePrinter" }.third
        put.parameters.any { it.`in` == "header" && it.name.equals("If-Match", true) } shouldBe true
        operations
            .first { it.third.operationId == "getPrinter" }
            .third.description
            .contains("ETag") shouldBe true
    }

    @Test
    fun `create and rotate document no-store in the contract`() {
        listOf("createPrintAgent", "rotatePrintAgentToken").forEach { id ->
            val op = operations.first { it.third.operationId == id }.third
            val text = (op.description.orEmpty() + op.responses.values.joinToString { it.description.orEmpty() })
            withClue(id) { text.contains("no-store") shouldBe true }
        }
    }
}

package com.zeny.hotel.shared.web

import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gera o contrato `api/openapi.yaml` (ARCHITECTURE §7, D6) a partir da aplicação real: sobe o
 * contexto de produção (profile `local`, banco do Testcontainers) e grava o YAML do springdoc
 * no caminho de `zeny.openapi.output`. Não roda no `test`: só pela tarefa `generateOpenApi`,
 * da qual dependem `checkOpenApi` (no `check`) e `updateOpenApi`.
 */
@Tag(OPENAPI_CONTRACT_TAG)
@IntegrationTest
class OpenApiContractExport(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `exports the openapi contract`() {
        val response =
            mockMvc
                .get("/v3/api-docs.yaml")
                .andExpect { status { isOk() } }
                .andReturn()
                .response
        // O springdoc escreve o YAML em UTF-8, embora a resposta simulada anuncie ISO-8859-1.
        val yaml = response.getContentAsString(Charsets.UTF_8)
        yaml shouldStartWith "openapi: "

        val output = Path.of(System.getProperty("zeny.openapi.output", "build/openapi/openapi.yaml"))
        Files.createDirectories(output.toAbsolutePath().parent)
        Files.writeString(output, yaml.replace("\r\n", "\n").trimEnd() + "\n")
    }
}

/** Tag JUnit do gerador do contrato; o `build.gradle.kts` a exclui do `test`. */
const val OPENAPI_CONTRACT_TAG = "openapi-contract"

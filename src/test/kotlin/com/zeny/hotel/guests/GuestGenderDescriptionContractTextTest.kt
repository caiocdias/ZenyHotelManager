package com.zeny.hotel.guests

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ataque à asserção negativa de `GuestGenderDescriptionApiTest` (contrato): a normalização dela deve devolver o
 * texto lógico mesmo quando o YAML gerado quebra a linha com `\` no fim e `\ ` no começo da seguinte.
 */
class GuestGenderDescriptionContractTextTest {
    private val oldPhrase = "inclusive nas das escritas"

    @Test
    fun `the contract text normalization keeps the space when the old phrase breaks at a space`() {
        val splits =
            oldPhrase.indices.filter { oldPhrase[it] == ' ' }.map { i ->
                "Vem em toda resposta de perfil, " + oldPhrase.substring(0, i) + "\\\n            \\" +
                    oldPhrase.substring(i) + " (criação)"
            }
        splits.forEach { yaml ->
            yamlScalarText(yaml).contains(oldPhrase) shouldBe true
        }
    }

    @Test
    fun `the contract description no longer promises the description in the writes, whatever the line breaks`() {
        val lines = Files.readAllLines(Path.of("api/openapi.yaml"))
        val schema = lines.indexOf("    GuestResponse:")
        val start = lines.drop(schema).indexOfFirst { it.trim() == "genderDescription:" } + schema
        val block =
            lines.drop(start + 1).takeWhile { it.startsWith("          ") }.joinToString("\n", postfix = "\n")
        yamlScalarText(block) shouldNotContain oldPhrase
        yamlScalarText(block).contains("GuestWriteSummaryResponse") shouldBe true
    }
}

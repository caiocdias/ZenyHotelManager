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
    // Cópia literal da normalização de GuestGenderDescriptionApiTest (linha da asserção negativa).
    private fun existingNormalization(block: String) = block.replace(Regex("\\\\\\n\\s*(\\\\ )?"), "")

    // Decodificação fiel do escalar entre aspas duplas do YAML: `\<quebra>` some, o recuo some e `\ ` é um espaço.
    private fun yamlDecode(block: String) = block.replace(Regex("\\\\\\n\\s*"), "").replace("\\ ", " ")

    private val oldPhrase = "inclusive nas das escritas"

    @Test
    fun `the existing normalization keeps the space when the old phrase breaks at a space`() {
        val splits =
            oldPhrase.indices.filter { oldPhrase[it] == ' ' }.map { i ->
                "Vem em toda resposta de perfil, " + oldPhrase.substring(0, i) + "\\\n            \\" +
                    oldPhrase.substring(i) + " (criação)"
            }
        splits.forEach { yaml ->
            existingNormalization(yaml).contains(oldPhrase) shouldBe true
        }
    }

    @Test
    fun `the contract description no longer promises the description in the writes, whatever the line breaks`() {
        val lines = Files.readAllLines(Path.of("api/openapi.yaml"))
        val schema = lines.indexOf("    GuestResponse:")
        val start = lines.drop(schema).indexOfFirst { it.trim() == "genderDescription:" } + schema
        val block =
            lines.drop(start + 1).takeWhile { it.startsWith("          ") }.joinToString("\n", postfix = "\n")
        yamlDecode(block) shouldNotContain oldPhrase
        yamlDecode(block).contains("GuestWriteSummaryResponse") shouldBe true
    }
}

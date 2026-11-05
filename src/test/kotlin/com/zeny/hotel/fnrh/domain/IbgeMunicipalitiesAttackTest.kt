package com.zeny.hotel.fnrh.domain

import com.zeny.hotel.shared.BrazilianState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** Ataques ao recurso do IBGE (D-E07-3): tamanho, proveniência, duplicados e coerência do código com a UF. */
class IbgeMunicipalitiesAttackTest {
    private val lines: List<String> =
        IbgeMunicipalitiesAttackTest::class.java
            .getResourceAsStream("/fnrh/ibge-municipios.csv")!!
            .bufferedReader(Charsets.UTF_8)
            .readLines()
    private val header = lines.filter { it.startsWith("#") }
    private val rows = lines.filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun `the resource has more than 5500 municipalities`() {
        rows.size shouldBeGreaterThan 5500
    }

    @Test
    fun `the header records source, download date and the sha-256 of the json`() {
        header.first() shouldBe "# fonte: https://servicodados.ibge.gov.br/api/v1/localidades/municipios"
        header.any { it.matches(Regex("# baixado em: \\d{4}-\\d{2}-\\d{2}")) } shouldBe true
        header.any { it.matches(Regex("# sha256 do json: [0-9a-f]{64}")) } shouldBe true
    }

    @Test
    fun `no municipality code is repeated and every row is code semicolon uf`() {
        rows.forEach { it shouldMatch Regex("\\d{7};[A-Z]{2}") }
        rows.map { it.substringBefore(';') }.toSet().size shouldBe rows.size
    }

    @Test
    fun `the four reference municipalities are present`() {
        rows shouldContain "3550308;SP"
        rows shouldContain "3304557;RJ"
        rows shouldContain "4303509;RS"
        rows shouldContain "5300108;DF"
    }

    @Test
    fun `the first two digits of every code match the IBGE code of its state`() {
        val municipalities = IbgeMunicipalities()
        rows.forEach { row ->
            val (code, uf) = row.split(';')
            municipalities.ufOf(code.toInt()) shouldBe uf
            code shouldStartWith BrazilianState.valueOf(uf).ibgeCode
        }
    }

    @Test
    fun `every state has at least one municipality`() {
        val ufs = rows.map { it.substringAfter(';') }.toSet()
        BrazilianState.entries.forEach { ufs shouldContain it.name }
    }

    @Test
    fun `lookups stay stable and answer null for zero and negative codes`() {
        val municipalities = IbgeMunicipalities()
        repeat(3) { municipalities.ufOf(3550308) shouldBe "SP" }
        municipalities.ufOf(0) shouldBe null
        municipalities.ufOf(-3550308) shouldBe null
        municipalities.ufOf(355030) shouldBe null
    }
}

package com.zeny.hotel.reporting

import com.zeny.hotel.reporting.application.ReportColumn
import com.zeny.hotel.reporting.application.ReportColumnType.INTEGER
import com.zeny.hotel.reporting.application.ReportColumnType.TEXT
import com.zeny.hotel.reporting.application.ReportTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** As validações do `init` do [ReportTable]: chave de coluna repetida e linha com número errado de células. */
class ReportTableTest {
    private val a = ReportColumn("a", "A", TEXT)
    private val b = ReportColumn("b", "B", INTEGER)

    @Test
    fun `a repeated column key is refused`() {
        shouldThrow<IllegalArgumentException> { ReportTable(listOf(a, ReportColumn("a", "Outra", TEXT)), emptyList()) }
            .message shouldContain "chave repetida"
    }

    @Test
    fun `a row with fewer cells than columns is refused, naming its index`() {
        shouldThrow<IllegalArgumentException> { ReportTable(listOf(a, b), listOf(listOf("x", 1), listOf("y"))) }
            .message shouldContain "A linha 1 tem 1 células para 2 colunas"
    }

    @Test
    fun `a row with more cells than columns is refused`() {
        shouldThrow<IllegalArgumentException> { ReportTable(listOf(a), listOf(listOf("x", 1))) }
            .message shouldContain "A linha 0"
    }

    @Test
    fun `a table with matching rows and null cells is accepted`() {
        ReportTable(listOf(a, b), listOf(listOf("x", null), listOf(null, 2)))
    }
}

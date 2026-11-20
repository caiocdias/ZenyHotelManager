package com.zeny.hotel.reporting

import com.zeny.hotel.reporting.application.ReportColumn
import com.zeny.hotel.reporting.application.ReportColumnType.DATE
import com.zeny.hotel.reporting.application.ReportColumnType.DECIMAL
import com.zeny.hotel.reporting.application.ReportColumnType.INSTANT
import com.zeny.hotel.reporting.application.ReportColumnType.INTEGER
import com.zeny.hotel.reporting.application.ReportColumnType.TEXT
import com.zeny.hotel.reporting.application.ReportDocument
import com.zeny.hotel.reporting.application.ReportExport
import com.zeny.hotel.reporting.application.ReportSheet
import com.zeny.hotel.reporting.application.ReportTable
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * O formato da exportação (M6 E09, D6, Review Focus 4): CSV em UTF-8 com BOM, `;`, decimal com vírgula, RFC 4180 e
 * neutralização só em texto; XLSX com célula numérica para número, mesmo negativo.
 */
class ReportExportFormatTest {
    private val bom = Char(0xFEFF).toString()

    private fun document(
        columns: List<ReportColumn>,
        vararg rows: List<Any?>,
    ) = ReportDocument("teste", listOf(ReportSheet("Aba", ReportTable(columns, rows.toList()))))

    private fun csv(document: ReportDocument) = String(ReportExport.csv(document), Charsets.UTF_8)

    private val textColumn = listOf(ReportColumn("v", "Valor", TEXT))

    @Test
    fun `the CSV starts with the BOM, uses the semicolon and CRLF, and writes the header in pt-BR`() {
        val out =
            csv(
                document(
                    listOf(ReportColumn("a", "Código", TEXT), ReportColumn("n", "Quantidade", INTEGER)),
                    listOf("X1", 3L),
                ),
            )

        ReportExport.csv(document(textColumn)).take(3) shouldBe listOf(0xEF, 0xBB, 0xBF).map { it.toByte() }
        // Uma só tabela: o cabeçalho e as linhas, sem título de aba.
        out shouldBe "${bom}Código;Quantidade\r\nX1;3\r\n"
    }

    @Test
    fun `decimals use the comma and keep their scale, and a negative balance is a plain number`() {
        val out =
            csv(
                document(
                    listOf(ReportColumn("d", "Saldo", DECIMAL)),
                    listOf(BigDecimal("-12.50")),
                    listOf(BigDecimal("1234.5")),
                    listOf(BigDecimal("0.0000")),
                    listOf(null),
                ),
            )

        out shouldBe "${bom}Saldo\r\n-12,50\r\n1234,5\r\n0,0000\r\n\r\n"
    }

    @Test
    fun `dates and instants are ISO`() {
        val out =
            csv(
                document(
                    listOf(ReportColumn("d", "Data", DATE), ReportColumn("i", "Instante", INSTANT)),
                    listOf(LocalDate.of(2031, 4, 2), Instant.parse("2031-04-02T10:15:30Z")),
                ),
            )

        out shouldBe "${bom}Data;Instante\r\n2031-04-02;2031-04-02T10:15:30Z\r\n"
    }

    @Test
    fun `text with a semicolon, a quote or a line break is quoted and the quote is doubled`() {
        val out =
            csv(
                document(
                    textColumn,
                    listOf("a;b"),
                    listOf("diz \"oi\""),
                    listOf("linha1\nlinha2"),
                    listOf("linha1\r\nlinha2"),
                    listOf("simples, com vírgula"),
                ),
            )

        out shouldBe
            "${bom}Valor\r\n\"a;b\"\r\n\"diz \"\"oi\"\"\"\r\n\"linha1\nlinha2\"\r\n\"linha1\r\nlinha2\"\r\n" +
            "simples, com vírgula\r\n"
    }

    @Test
    fun `text that starts with a formula character is neutralized with a quote prefix, only in text columns`() {
        val prefixed = listOf("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx")
        val out = csv(document(textColumn, *prefixed.map { listOf<Any?>(it) }.toTypedArray()))

        out shouldBe
            "${bom}Valor\r\n'=1+1\r\n'+1\r\n'-1\r\n'@SUM(A1)\r\n'\tx\r\n\"'\rx\"\r\n"
        // O texto que só contém o caractere no meio, e o vazio, ficam como estão.
        csv(document(textColumn, listOf("a=b"), listOf(""))) shouldBe "${bom}Valor\r\na=b\r\n\r\n"
        // O número negativo e o decimal negativo não são texto: nada de apóstrofo.
        csv(
            document(
                listOf(ReportColumn("n", "N", INTEGER), ReportColumn("d", "D", DECIMAL)),
                listOf(-5L, BigDecimal("-0.01")),
            ),
        ) shouldBe "${bom}N;D\r\n-5;-0,01\r\n"
    }

    @Test
    fun `a report with several tables writes each one under its title, separated by an empty line`() {
        val a = ReportTable(listOf(ReportColumn("x", "X", INTEGER)), listOf(listOf(1L)))
        val b = ReportTable(listOf(ReportColumn("y", "Y", TEXT)), listOf(listOf("p"), listOf("q")))

        val out = csv(ReportDocument("duas", listOf(ReportSheet("Primeira", a), ReportSheet("Segunda", b))))

        out shouldBe "${bom}Primeira\r\nX\r\n1\r\n\r\nSegunda\r\nY\r\np\r\nq\r\n"
    }

    @Test
    fun `the XLSX has one sheet per table, numbers as numeric cells (negative too) and text as text`() {
        val a =
            ReportTable(
                listOf(
                    ReportColumn("t", "Texto", TEXT),
                    ReportColumn("n", "Inteiro", INTEGER),
                    ReportColumn("d", "Saldo", DECIMAL),
                    ReportColumn("dt", "Data", DATE),
                ),
                listOf(
                    listOf("=1+1", -7L, BigDecimal("-12.50"), LocalDate.of(2031, 4, 2)),
                    listOf(null, 0L, null, null),
                ),
            )
        val b = ReportTable(listOf(ReportColumn("y", "Y", TEXT)), listOf(listOf("p")))

        val bytes =
            ReportExport.xlsx(
                ReportDocument("duas", listOf(ReportSheet("Primeira", a), ReportSheet("Segunda", b))),
            )

        ReadableWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            workbook.sheets.map { it.name }.toList() shouldBe listOf("Primeira", "Segunda")
            val rows = workbook.sheets.toList()[0].read()
            rows[0].map { it.text } shouldBe listOf("Texto", "Inteiro", "Saldo", "Data")
            val first = rows[1]
            first.getCell(0).type shouldBe CellType.STRING
            // No XLSX o texto é célula de texto: não precisa do apóstrofo, e fica como veio.
            first.getCell(0).text shouldBe "=1+1"
            first.getCell(1).type shouldBe CellType.NUMBER
            first.getCell(1).asNumber() shouldBe BigDecimal("-7")
            first.getCell(2).type shouldBe CellType.NUMBER
            first.getCell(2).asNumber().compareTo(BigDecimal("-12.50")) shouldBe 0
            first.getCell(3).text shouldBe "2031-04-02"
            // Valor nulo é célula vazia (nada gravado).
            rows[2].getOptionalCell(0).isPresent shouldBe false
            rows[2].getOptionalCell(2).isPresent shouldBe false
            rows[2].getCell(1).asNumber() shouldBe BigDecimal.ZERO
        }
        String(bytes.copyOf(2), Charsets.ISO_8859_1) shouldStartWith "PK"
    }
}

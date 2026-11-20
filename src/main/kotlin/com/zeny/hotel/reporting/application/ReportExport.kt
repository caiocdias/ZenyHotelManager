package com.zeny.hotel.reporting.application

import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Uma tabela do relatório com o nome da aba (XLSX) ou o título da seção (CSV). */
data class ReportSheet(
    val name: String,
    val table: ReportTable,
)

/** O relatório pronto para exportar: o nome-base do arquivo e as tabelas, na ordem em que saem. */
data class ReportDocument(
    val baseName: String,
    val sheets: List<ReportSheet>,
) {
    init {
        require(sheets.isNotEmpty()) { "Um documento precisa de ao menos uma tabela" }
        require(sheets.map { it.name }.toSet().size == sheets.size) { "Abas com nome repetido" }
    }
}

private val BOM = Char(0xFEFF).toString()
private const val SEPARATOR = ";"
private const val EOL = "\r\n"
private const val APP_NAME = "Zeny Hotel Manager"
private const val APP_VERSION = "1.0"

/** Os caracteres que obrigam a pôr o campo do CSV entre aspas (RFC 4180, mais o separador). */
private val QUOTING_CHARS = setOf(';', '"', '\r', '\n')

/** Os caracteres com que uma planilha entende um texto como fórmula (injeção em CSV). */
private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')

/**
 * A serialização dos relatórios (M6 E09, D6). O CSV é UTF-8 com BOM, separado por `;`, com decimal em vírgula (pt-BR),
 * datas e instantes em ISO e escape da RFC 4180 (campo com `;`, `"` ou quebra de linha entre aspas, aspas dobradas,
 * linhas terminadas em CRLF); um texto que começa com `=`, `+`, `-`, `@`, TAB ou CR ganha o prefixo `'` (só coluna de
 * texto: número negativo sai como número). Um relatório de várias tabelas escreve cada uma sob uma linha com o título
 * dela, separadas por uma linha vazia. O XLSX tem uma aba por tabela, número em célula numérica e texto em célula de
 * texto (que a planilha nunca avalia como fórmula).
 */
object ReportExport {
    fun csv(document: ReportDocument): ByteArray {
        val out = StringBuilder(BOM)
        val several = document.sheets.size > 1
        document.sheets.forEachIndexed { index, sheet ->
            if (index > 0) out.append(EOL)
            if (several) out.append(escape(sheet.name)).append(EOL)
            out.append(sheet.table.columns.joinToString(SEPARATOR) { escape(it.label) }).append(EOL)
            sheet.table.rows.forEach { row ->
                out.append(row.indices.joinToString(SEPARATOR) { csvCell(sheet.table.columns[it], row[it]) })
                out.append(EOL)
            }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun xlsx(document: ReportDocument): ByteArray {
        val bytes = ByteArrayOutputStream()
        Workbook(bytes, APP_NAME, APP_VERSION).use { workbook ->
            document.sheets.forEach { sheet ->
                val worksheet = workbook.newWorksheet(sheet.name)
                sheet.table.columns.forEachIndexed { c, column -> worksheet.value(0, c, column.label) }
                sheet.table.rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, value -> xlsxCell(worksheet, r + 1, c, sheet.table.columns[c], value) }
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun xlsxCell(
        worksheet: Worksheet,
        row: Int,
        column: Int,
        definition: ReportColumn,
        value: Any?,
    ) {
        when {
            value == null -> {
                return
            }

            definition.type == ReportColumnType.INTEGER -> {
                worksheet.value(row, column, (value as Number).toLong())
            }

            definition.type == ReportColumnType.DECIMAL -> {
                worksheet.value(row, column, value as BigDecimal)
            }

            else -> {
                worksheet.value(row, column, plain(definition, value))
            }
        }
    }

    private fun csvCell(
        column: ReportColumn,
        value: Any?,
    ): String =
        when {
            value == null -> ""
            column.type == ReportColumnType.TEXT -> escape(neutralize(value.toString()))
            column.type == ReportColumnType.DECIMAL -> (value as BigDecimal).toPlainString().replace('.', ',')
            else -> escape(plain(column, value))
        }

    /** O valor como texto, sem formato de planilha: o texto como veio, o número, a data e o instante em ISO. */
    private fun plain(
        column: ReportColumn,
        value: Any,
    ): String =
        when (column.type) {
            ReportColumnType.TEXT -> value.toString()
            ReportColumnType.INTEGER -> (value as Number).toLong().toString()
            ReportColumnType.DECIMAL -> (value as BigDecimal).toPlainString()
            ReportColumnType.DATE -> (value as LocalDate).toString()
            ReportColumnType.INSTANT -> (value as Instant).toString()
        }

    private fun neutralize(text: String): String = if (text.firstOrNull() in FORMULA_PREFIXES) "'$text" else text

    private fun escape(text: String): String =
        if (text.any { it in QUOTING_CHARS }) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            text
        }
}

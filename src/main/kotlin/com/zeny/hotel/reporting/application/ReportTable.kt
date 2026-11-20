package com.zeny.hotel.reporting.application

/** O tipo de uma coluna do [ReportTable]: decide o formato no CSV e o tipo da célula no XLSX. */
enum class ReportColumnType { TEXT, INTEGER, DECIMAL, DATE, INSTANT }

/** Uma coluna do [ReportTable]: a chave estável, o rótulo em pt-BR do cabeçalho e o tipo. */
data class ReportColumn(
    val key: String,
    val label: String,
    val type: ReportColumnType,
)

/**
 * O modelo tabular interno de um relatório (colunas tipadas e linhas), que a exportação CSV/XLSX serializa. Nunca vai
 * ao contrato: a resposta JSON é um DTO tipado, e este modelo nasce do mesmo resultado só para a exportação. Cada
 * linha tem uma célula por coluna (`null` é célula vazia).
 */
data class ReportTable(
    val columns: List<ReportColumn>,
    val rows: List<List<Any?>>,
) {
    init {
        require(columns.map { it.key }.toSet().size == columns.size) { "Colunas com chave repetida" }
        rows.forEachIndexed { index, row ->
            require(row.size == columns.size) { "A linha $index tem ${row.size} células para ${columns.size} colunas" }
        }
    }
}

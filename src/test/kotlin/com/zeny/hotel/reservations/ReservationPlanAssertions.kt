// O arquivo leva o nome do apoio (a leitura do plano e a asserção), não o da classe de dados que ele devolve.
@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.zeny.hotel.reservations

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** Como o plano lê `reservation r`: o [indexName] do índice e se o nó dele tem uma linha `Index Cond:`. */
data class ReservationIndexRead(
    val indexName: String,
    val hasIndexCond: Boolean,
)

private val INDEX_SCAN = Regex("""Index (?:Only )?Scan(?: Backward)? using (\S+) on reservation r(?:\s|$)""")
private val BITMAP_HEAP_SCAN = Regex("""Bitmap Heap Scan on reservation r(?:\s|$)""")
private val BITMAP_INDEX_SCAN = Regex("""Bitmap Index Scan on (\S+)""")

/**
 * O índice que lê `reservation r` no [plan] (texto do `EXPLAIN`) e se o nó dele tem `Index Cond`; `null` quando
 * nenhum índice a lê (varredura sequencial ou ausente). O nó é isolado pela indentação: as linhas dele vão até o
 * próximo nó irmão ou de nível acima, e a condição de outra tabela não conta. No bitmap, vale o `Bitmap Index Scan`
 * filho (o primeiro) do `Bitmap Heap Scan on reservation r`; o `Recheck Cond` do heap não é condição de índice.
 */
internal fun reservationIndexNode(plan: String): ReservationIndexRead? {
    val lines = plan.lines()
    val at = lines.indexOfFirst { INDEX_SCAN.containsMatchIn(it) || BITMAP_HEAP_SCAN.containsMatchIn(it) }
    if (at < 0) return null
    val node = nodeLines(lines, at)
    return INDEX_SCAN.find(lines[at])?.let { indexRead(it.groupValues[1], node) }
        ?: node.withIndex().firstNotNullOfOrNull { (child, text) ->
            BITMAP_INDEX_SCAN.find(text)?.let { indexRead(it.groupValues[1], nodeLines(node, child)) }
        }
}

/**
 * O plano lê `reservation r` por um índice com condição útil (D-E16-1): um `Index Cond` no nó dela, ou um índice
 * parcial da situação ([isStatusPartial], pelo nome do índice); nunca `Seq Scan on reservation r`.
 */
internal fun String.shouldReadReservationsUsefully(isStatusPartial: (String) -> Boolean) {
    withClue(this) {
        this shouldNotContain "Seq Scan on reservation r"
        val read = withClue("reservation r lida por um índice") { reservationIndexNode(this).shouldNotBeNull() }
        withClue("condição útil sobre reservation r: Index Cond ou índice parcial da situação (${read.indexName})") {
            (read.hasIndexCond || isStatusPartial(read.indexName)) shouldBe true
        }
    }
}

private fun indexRead(
    indexName: String,
    node: List<String>,
): ReservationIndexRead = ReservationIndexRead(indexName, node.any { it.trimStart().startsWith("Index Cond:") })

/** As linhas abaixo do nó em [at] (detalhes e filhos), até a primeira que não esteja mais indentada que ele. */
private fun nodeLines(
    lines: List<String>,
    at: Int,
): List<String> {
    val node = lines[at]
    val arrow = node.indexOf("->")
    val depth = if (arrow >= 0 && node.substring(0, arrow).isBlank()) arrow else indentOf(node)
    return lines.drop(at + 1).takeWhile { it.isNotBlank() && indentOf(it) > depth }
}

private fun indentOf(line: String): Int = line.length - line.trimStart().length

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
private val CHILD_PLAN = Regex("""^(?:->|InitPlan\b|SubPlan\b|CTE\b)""")

/**
 * O índice que lê `reservation r` no [plan] (texto do `EXPLAIN`) e se o nó dele tem `Index Cond`; `null` quando
 * nenhum índice a lê (varredura sequencial ou ausente). Só contam as linhas de detalhe do próprio nó, antes do primeiro
 * filho (outro nó, `InitPlan`, `SubPlan` ou `CTE`): a condição de outra tabela ou de um subplano não conta. No bitmap,
 * vale o `Bitmap Index Scan` filho direto do `Bitmap Heap Scan on reservation r` (o de um `InitPlan` dele não); num
 * `BitmapAnd`/`BitmapOr`, este é o primeiro ramo (a asserção lê todos). O `Recheck Cond` do heap não é condição de
 * índice.
 */
internal fun reservationIndexNode(plan: String): ReservationIndexRead? = reservationIndexAccess(plan)?.reads()?.first()

/**
 * O plano lê `reservation r` por um índice com condição útil (D-E16-1): um `Index Cond` no nó dela, ou um índice
 * parcial da situação ([isStatusPartial], pelo nome do índice); nunca `Seq Scan on reservation r`. Num `BitmapAnd`,
 * basta um ramo útil (a interseção fica restrita); num `BitmapOr`, todos precisam ser (um ramo sem condição devolve o
 * índice inteiro na união).
 */
internal fun String.shouldReadReservationsUsefully(isStatusPartial: (String) -> Boolean) {
    withClue(this) {
        this shouldNotContain "Seq Scan on reservation r"
        val access = withClue("reservation r lida por um índice") { reservationIndexAccess(this).shouldNotBeNull() }
        withClue("condição útil sobre reservation r: Index Cond ou índice parcial da situação (${access.reads()})") {
            access.isUseful(isStatusPartial) shouldBe true
        }
    }
}

/** Como os índices alimentam a leitura de `reservation r`: um índice só, ou a combinação de bitmaps. */
private sealed interface IndexAccess {
    fun reads(): List<ReservationIndexRead>

    fun isUseful(isStatusPartial: (String) -> Boolean): Boolean
}

private data class SingleIndex(
    val read: ReservationIndexRead,
) : IndexAccess {
    override fun reads() = listOf(read)

    override fun isUseful(isStatusPartial: (String) -> Boolean) = read.hasIndexCond || isStatusPartial(read.indexName)
}

/** Um `BitmapAnd` (útil se um ramo for) ou um `BitmapOr` ([everyBranch]: útil só se todos forem). */
private data class BitmapCombination(
    val branches: List<IndexAccess>,
    val everyBranch: Boolean,
) : IndexAccess {
    override fun reads() = branches.flatMap(IndexAccess::reads)

    override fun isUseful(isStatusPartial: (String) -> Boolean): Boolean {
        val useful = branches.map { it.isUseful(isStatusPartial) }
        return if (everyBranch) useful.all { it } else useful.any { it }
    }
}

private fun reservationIndexAccess(plan: String): IndexAccess? {
    val lines = plan.lines()
    val at = lines.indexOfFirst { INDEX_SCAN.containsMatchIn(it) || BITMAP_HEAP_SCAN.containsMatchIn(it) }
    if (at < 0) return null
    val block = nodeLines(lines, at)
    return INDEX_SCAN.find(lines[at])?.let { SingleIndex(indexRead(it.groupValues[1], block)) }
        ?: directChildren(block).firstNotNullOfOrNull { bitmapAccess(block, it) }
}

/** O `Bitmap Index Scan`, `BitmapAnd` ou `BitmapOr` em [at], com os ramos diretos dele; `null` para outro nó. */
private fun bitmapAccess(
    lines: List<String>,
    at: Int,
): IndexAccess? {
    val line = lines[at]
    val block = nodeLines(lines, at)
    val index = BITMAP_INDEX_SCAN.find(line)
    return when {
        index != null -> {
            SingleIndex(indexRead(index.groupValues[1], block))
        }

        "BitmapAnd" in line || "BitmapOr" in line -> {
            directChildren(block)
                .mapNotNull { bitmapAccess(block, it) }
                .takeIf { it.isNotEmpty() }
                ?.let { BitmapCombination(it, everyBranch = "BitmapOr" in line) }
        }

        else -> {
            null
        }
    }
}

/** O `Index Cond` conta só nas linhas de detalhe do próprio nó, antes do primeiro filho. */
private fun indexRead(
    indexName: String,
    block: List<String>,
): ReservationIndexRead = ReservationIndexRead(indexName, ownDetails(block).any { it.startsWith("Index Cond:") })

/** As linhas de detalhe do nó, sem a indentação, até o primeiro filho (outro nó, `InitPlan`, `SubPlan` ou `CTE`). */
private fun ownDetails(block: List<String>): List<String> =
    block.map(String::trimStart).takeWhile { !CHILD_PLAN.containsMatchIn(it) }

/** As posições, em [block], dos nós filhos diretos (`->` no nível dos detalhes; os de um subplano ficam mais fundo). */
private fun directChildren(block: List<String>): List<Int> {
    val level = block.minOfOrNull(::indentOf) ?: return emptyList()
    return block.indices.filter { indentOf(block[it]) == level && block[it].trimStart().startsWith("->") }
}

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

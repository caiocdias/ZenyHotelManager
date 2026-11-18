package com.zeny.hotel.housekeeping.application

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** O texto do aviso da publicação do plano do dia (M6 E03, D8-E03): o título com a data e o corte com "e mais N". */
class AssignmentNoticeTest {
    @Test
    fun `the title has the day and month of the plan`() {
        AssignmentNotice.title(LocalDate.of(2026, 3, 7)) shouldBe "Suas tarefas de 07/03 mudaram"
    }

    @Test
    fun `the text lists the included and the removed, each section only when it has tasks`() {
        AssignmentNotice.body(listOf("UH 101", "Piscina"), listOf("UH 7")) shouldBe
            "Incluídas: UH 101, Piscina. Removidas: UH 7."
        AssignmentNotice.body(emptyList(), listOf("UH 7")) shouldBe "Removidas: UH 7."
        AssignmentNotice.body(listOf("UH 101"), emptyList()) shouldBe "Incluídas: UH 101."
    }

    @Test
    fun `a text over the limit is cut from the end of the longer list with e mais N`() {
        val included = List(20) { "Área ${it.toString().padStart(2, '0')} ".padEnd(80, 'x') }
        val removed = List(3) { "UH $it" }

        val text = AssignmentNotice.body(included, removed)

        text.length shouldBeLessThanOrEqual AssignmentNotice.MAX_BODY
        text shouldStartWith "Incluídas: Área 00"
        val shown = text.substringBefore(" e mais ").split(", ").size
        text.substringAfter(" e mais ").substringBefore(".").toInt() shouldBe 20 - shown
        text shouldEndWith "Removidas: UH 0, UH 1, UH 2."
    }

    @Test
    fun `labels that never fit leave only the counts`() {
        val huge = List(3) { "x".repeat(1000) }

        AssignmentNotice.body(huge, huge) shouldBe "Incluídas: 3 tarefas. Removidas: 3 tarefas."
        AssignmentNotice.body(listOf("x".repeat(1200)), emptyList()) shouldBe "Incluídas: 1 tarefa."
    }
}

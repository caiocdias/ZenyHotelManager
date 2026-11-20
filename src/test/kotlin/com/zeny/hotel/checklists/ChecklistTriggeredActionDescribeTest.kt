package com.zeny.hotel.checklists

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * O texto da ocorrência aberta por uma ação (M6 E08, D2): rótulo, categoria e observação, sem NUL, aparados, unidos por
 * " - " e cortados por code points (nunca no meio de um par surrogate).
 */
class ChecklistTriggeredActionDescribeTest {
    private fun action(
        category: String? = null,
        note: String? = null,
    ) = ChecklistTriggeredAction(
        "tap",
        ItemActionType.OPEN_OCCURRENCE,
        occurrenceKind = OccurrenceKind.MAINTENANCE,
        category = category,
        note = note,
    )

    @Test
    fun `the label, the category and the note are joined in this order and the missing ones are skipped`() {
        action("PLUMBING", "Pinga").describe("Torneira", LIMIT) shouldBe "Torneira - PLUMBING - Pinga"
        action(null, "Pinga").describe("Torneira", LIMIT) shouldBe "Torneira - Pinga"
        action("  ", " ").describe("Torneira", LIMIT) shouldBe "Torneira"
    }

    @Test
    fun `NUL is stripped and the parts are trimmed`() {
        action(" PLUMBING ", "Pi\u0000nga ").describe(" Torne\u0000ira ", LIMIT) shouldBe "Torneira - PLUMBING - Pinga"
    }

    @Test
    fun `a text over the limit is cut at exactly the limit in code points`() {
        val text = action(null, "x".repeat(LIMIT)).describe("Torneira", LIMIT)

        text.codePointCount(0, text.length) shouldBe LIMIT
        text shouldBe ("Torneira - " + "x".repeat(LIMIT)).take(LIMIT)
    }

    @Test
    fun `a text of exactly the limit is kept whole`() {
        val note = "y".repeat(LIMIT - "Torneira - ".length)

        action(null, note).describe("Torneira", LIMIT) shouldBe "Torneira - $note"
    }

    @Test
    fun `the cut never splits a surrogate pair at the border`() {
        // 4 code points antes do emoji; o limite 5 cai depois do emoji (que ocupa dois chars), e o 4 antes dele.
        val emoji = "😀"
        val within = action(null, null).describe("abcd$emoji" + "efg", 5)
        within shouldBe "abcd$emoji"
        within.codePointCount(0, within.length) shouldBe 5

        val before = action(null, null).describe("abcd$emoji" + "efg", 4)
        before shouldBe "abcd"

        val allEmoji = action(null, null).describe(emoji.repeat(10), 3)
        allEmoji shouldBe emoji.repeat(3)
        allEmoji.any { Character.isHighSurrogate(it) } shouldBe true
        allEmoji.length shouldBe 6
    }

    private companion object {
        const val LIMIT = 40
    }
}

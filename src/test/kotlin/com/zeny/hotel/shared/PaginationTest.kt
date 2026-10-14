package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PaginationTest {
    @Test
    fun `cursor round-trips its keys and is opaque`() {
        val id = UUID.fromString("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d")
        val createdAt = Instant.parse("2026-03-10T01:30:00.123Z")

        val token = PageCursor.of(createdAt.toString(), id.toString(), "a,b%c|d").encode()

        token shouldMatch Regex("[A-Za-z0-9_-]+")
        token shouldNotContain id.toString()
        val decoded = PageCursor.decode(token)
        decoded.keys shouldContainExactly listOf(createdAt.toString(), id.toString(), "a,b%c|d")
        decoded.instant(0) shouldBe createdAt
        decoded.uuid(1) shouldBe id
        decoded.string(2) shouldBe "a,b%c|d"
    }

    @Test
    fun `invalid or tampered cursors are invalid input with a stable code`() {
        val valid = PageCursor.of("1").encode()
        val invalidTokens =
            listOf(
                "",
                "abc",
                "%%%",
                "not base64!",
                valid.dropLast(2) + "@@",
                "A".repeat(PageCursor.MAX_TOKEN_LENGTH + 1),
            )

        invalidTokens.forEach { token ->
            val error = shouldThrow<InvalidInputException> { PageCursor.decode(token) }
            error.code shouldBe ErrorCodes.INVALID_CURSOR
        }
    }

    @Test
    fun `typed accessors reject keys of the wrong type or position`() {
        val cursor = PageCursor.decode(PageCursor.of("not-a-uuid", "x").encode())

        listOf<() -> Unit>(
            { cursor.uuid(0) },
            { cursor.instant(0) },
            { cursor.long(1) },
            { cursor.string(2) },
        ).forEach { access ->
            shouldThrow<InvalidInputException> { access() }.code shouldBe ErrorCodes.INVALID_CURSOR
        }
    }

    @Test
    fun `page from rows returns at most limit items and a cursor only when there are more`() {
        val rows = (1..5).toList()

        val first = CursorPage.fromRows(rows.take(3), limit = 2) { PageCursor.of(it.toString()) }
        first.items shouldContainExactly listOf(1, 2)
        PageCursor.decode(first.nextCursor.shouldNotBeNull()).long(0) shouldBe 2L

        val last = CursorPage.fromRows(rows.drop(4), limit = 2) { PageCursor.of(it.toString()) }
        last.items shouldContainExactly listOf(5)
        last.nextCursor.shouldBeNull()

        val exact = CursorPage.fromRows(rows.take(2), limit = 2) { PageCursor.of(it.toString()) }
        exact.items shouldContainExactly listOf(1, 2)
        exact.nextCursor.shouldBeNull()

        first.map { it * 10 }.items shouldContainExactly listOf(10, 20)
    }

    @Test
    fun `page parameters default to the standard limit and decode the cursor`() {
        PageParams().limit shouldBe PageParams.DEFAULT_LIMIT
        PageParams().decodedCursor().shouldBeNull()
        PageParams(cursor = PageCursor.of("7").encode(), limit = 3).decodedCursor().shouldNotBeNull().long(0) shouldBe
            7L
        shouldThrow<InvalidInputException> { PageParams(cursor = "???").decodedCursor() }
    }
}

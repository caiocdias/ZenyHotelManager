package com.zeny.hotel.shared

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ConcurrencyTest {
    @Test
    fun `etag is the aggregate version as a strong entity tag`() {
        ETags.of(0) shouldBe "\"0\""
        ETags.of(42) shouldBe "\"42\""
    }

    @Test
    fun `if-match with a strong etag yields the expected version`() {
        ETags.expectedVersion("\"7\"") shouldBe 7L
        ETags.expectedVersion("  \"7\"  ") shouldBe 7L
    }

    @Test
    fun `missing if-match requires a precondition`() {
        listOf(null, "", "   ").forEach { header ->
            val error = shouldThrow<PreconditionRequiredException> { ETags.expectedVersion(header) }
            error.code shouldBe ErrorCodes.PRECONDITION_REQUIRED
            error.kind shouldBe ErrorKind.PRECONDITION_REQUIRED
        }
    }

    @Test
    fun `if-match that can never match the current version fails the precondition`() {
        // Etag fraca nunca casa em If-Match (RFC 9110 §13.1.1); `*` e listas não dizem a versão.
        listOf("W/\"7\"", "*", "\"1\", \"2\"", "7", "\"-1\"", "\"abc\"", "\"99999999999999999999\"").forEach { header ->
            val error = shouldThrow<PreconditionFailedException> { ETags.expectedVersion(header) }
            error.code shouldBe ErrorCodes.PRECONDITION_FAILED
        }
    }

    @Test
    fun `stale version is a precondition failure`() {
        shouldNotThrowAny { requireCurrentVersion(expected = 3, current = 3) }

        val error = shouldThrow<StaleVersionException> { requireCurrentVersion(expected = 2, current = 3) }
        error.code shouldBe ErrorCodes.PRECONDITION_FAILED
        error.kind shouldBe ErrorKind.PRECONDITION_FAILED
        error.expected shouldBe 2L
        error.current shouldBe 3L
    }
}

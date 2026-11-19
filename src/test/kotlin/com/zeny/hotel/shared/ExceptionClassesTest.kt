package com.zeny.hotel.shared

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.sql.SQLException

class ExceptionClassesTest {
    @Test
    fun `a failure with no cause is its own class`() {
        ExceptionClasses.chain(IllegalStateException(SENTINEL)) shouldBe IllegalStateException::class.java.name
    }

    @Test
    fun `the chain lists the failure and its causes in order, without messages`() {
        val failure =
            IllegalStateException("a $SENTINEL", SQLException("b $SENTINEL", IllegalArgumentException(SENTINEL)))

        val chain = ExceptionClasses.chain(failure)

        chain shouldBe
            "${IllegalStateException::class.java.name} <- ${SQLException::class.java.name} <- " +
            IllegalArgumentException::class.java.name
        chain shouldNotContain SENTINEL
    }

    @Test
    fun `a cycle of causes stops at the first repeated cause`() {
        val cause = SQLException("causa")
        val failure = IllegalStateException("corpo", cause)
        cause.initCause(failure)

        ExceptionClasses.chain(failure) shouldBe
            "${IllegalStateException::class.java.name} <- ${SQLException::class.java.name}"
    }

    @Test
    fun `a very long chain is cut at the link limit`() {
        val failure = (1..LONG_CHAIN).fold<Int, Throwable?>(null) { cause, _ -> RuntimeException("x", cause) }!!

        ExceptionClasses.chain(failure).split(" <- ").size shouldBe ExceptionClasses.MAX_LINKS
    }

    private companion object {
        const val SENTINEL = "SENTINELA-e21-chain"
        const val LONG_CHAIN = 500
    }
}

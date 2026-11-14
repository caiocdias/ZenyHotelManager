package com.zeny.hotel.testsupport

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class LogMessagesTest {
    private val prefix =
        "2026-11-14T18:23:28.100-03:00  WARN 3010000 --- [zeny-hotel-manager] [    Test worker] [,,,] " +
            "c.z.h.f.i.SerproFnrhGateway : "

    @Test
    fun `a PID and millisecond stamp holding the value do not count when the message lacks it`() {
        val log = prefix + "FNRH registerPerson corr-3 status=400"

        log shouldContain "100"
        messagesOf(log) shouldNotContain "100"
        messagesOf(log) shouldBe "FNRH registerPerson corr-3 status=400"
    }

    @Test
    fun `the value in the message part is still found`() {
        val log = prefix + "FNRH registerPerson corr-3 cep 01310-100"

        messagesOf(log) shouldContain "100"
    }

    @Test
    fun `continuation lines without prefix are kept whole and a message with colon separator is kept`() {
        val log = prefix + "falhou : detalhe 1578\n\tat com.x.Y(Y.kt:100)\n" + prefix + "outra"

        val messages = messagesOf(log)
        messages shouldContain "falhou : detalhe 1578"
        messages shouldContain "Y.kt:100"
        messages shouldNotContain "3010000"
    }
}

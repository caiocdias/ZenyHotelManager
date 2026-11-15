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

    private val ecsLine =
        """{"@timestamp":"2026-11-14T21:30:18.039123595Z","log":{"level":"INFO","logger":"org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean"},"process":{"pid":3010000,"thread":{"name":"SpringApplicationShutdownHook"}},"service":{"name":"zeny-hotel-manager","node":{}},"message":"Closing JPA EntityManagerFactory for persistence unit 'default'","ecs":{"version":"8.11"}}"""

    @Test
    fun `an ECS JSON line drops only the known noise fields`() {
        val messages = messagesOf(ecsLine)

        messages shouldNotContain "2026-11-14T21:30:18"
        messages shouldNotContain "3010000"
        messages shouldNotContain "8.11"
        messages shouldNotContain "zeny-hotel-manager"
        messages shouldNotContain "INFO"
        messages shouldNotContain "SpringApplicationShutdownHook"
        messages shouldContain "Closing JPA EntityManagerFactory for persistence unit 'default'"
        messages shouldContain "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean"
    }

    @Test
    fun `a personal value in an MDC field of an ECS line stays visible`() {
        val line = ecsLine.dropLast(1) + ""","guestDocument":"52998224725"}"""

        messagesOf(line) shouldContain "52998224725"
    }

    @Test
    fun `an ECS error keeps its type and message`() {
        val line =
            ecsLine.dropLast(1) +
                ""","error":{"type":"java.lang.IllegalStateException","message":"cep 01310-100","stack_trace":"at X"}}"""

        val messages = messagesOf(line)
        messages shouldContain "java.lang.IllegalStateException"
        messages shouldContain "cep 01310-100"
        messages shouldContain "at X"
    }

    @Test
    fun `an ECS message with escaped quotes and newline is decoded`() {
        val line = """{"log":{"level":"WARN","logger":"x.Y"},"message":"disse \"oi\"\nlinha 2"}"""

        val messages = messagesOf(line)
        messages shouldContain "disse \"oi\"\nlinha 2"
        messages shouldNotContain "\\"
    }

    @Test
    fun `a console line with the prefix but no logger separator keeps the text after the prefix`() {
        val log =
            "2026-11-14T18:23:28.100-03:00  WARN 3010000 --- [zeny-hotel-manager] [    Test worker] [,,,] " +
                "texto solto 77"

        messagesOf(log) shouldBe "texto solto 77"
    }

    @Test
    fun `a line that is not valid JSON follows the console rules`() {
        val broken = """{"message":"cortada 4242","log":"""

        messagesOf(broken) shouldBe broken
        messagesOf(prefix + "{\"a\":1}") shouldBe "{\"a\":1}"
    }
}

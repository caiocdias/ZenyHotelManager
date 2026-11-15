package com.zeny.hotel.testsupport

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Configuration

/** Ataque ao `messagesOf` (E16 Task 3): o helper sustenta asserções negativas de dado pessoal. */
@ExtendWith(OutputCaptureExtension::class)
class LogMessagesAttackTest {
    private val base =
        """"@timestamp":"2026-11-14T21:30:18.039Z","log":{"level":"INFO","logger":"x.Y"},"process":{"pid":3010000,"thread":{"name":"main"}},"service":{"name":"zeny-hotel-manager"},"ecs":{"version":"8.11"}"""

    private fun ecs(extra: String) = "{$base,$extra}"

    @Test
    fun `a personal value in the log logger or in a log sibling key stays visible`() {
        val line = """{"message":"m","log":{"level":"INFO","logger":"x.Y","origin":"52998224725"}}"""

        messagesOf(line) shouldContain "52998224725"
    }

    @Test
    fun `numbers and booleans in values are kept and numbers in key names are not`() {
        val messages = messagesOf(ecs(""""message":"m","qty":4242,"ok":true,"4343":"v","ratio":1.5"""))

        messages shouldContain "4242"
        messages shouldContain "true"
        messages shouldContain "1.5"
        messages shouldNotContain "4343"
        messages shouldNotContain "3010000"
    }

    @Test
    fun `arrays null and empty objects are walked without losing the other values`() {
        val messages =
            messagesOf(ecs(""""message":"m","tags":["a1",{"k":"b2"},[ "c3" ]],"nothing":null,"empty":{},"arr":[]"""))

        messages shouldContain "a1"
        messages shouldContain "b2"
        messages shouldContain "c3"
        messages shouldNotContain "null"
        messages shouldNotContain "nothing"
    }

    @Test
    fun `a JSON root that is not an object keeps the line whole`() {
        messagesOf("""["52998224725"]""") shouldBe """["52998224725"]"""
        messagesOf("12345") shouldBe "12345"
        messagesOf("null") shouldBe "null"
        messagesOf("{}") shouldBe ""
    }

    @Test
    fun `a stack trace continuation after an ECS line is kept whole`() {
        val log =
            ecs(""""message":"falhou"""") + "\njava.lang.IllegalStateException: cpf 52998224725\n\tat x.Y(Y.kt:3010000)"

        val messages = messagesOf(log)
        messages shouldContain "falhou"
        messages shouldContain "cpf 52998224725"
        messages shouldContain "Y.kt:3010000"
    }

    @Test
    fun `a truncated ECS line is kept whole so the personal value is not hidden`() {
        val broken = """{"message":"cpf 52998224725","log":{"level":"IN"""

        messagesOf(broken) shouldContain "52998224725"
    }

    @Test
    fun `leading spaces before the brace still parse as ECS`() {
        val messages = messagesOf("   \t" + ecs(""""message":"oi 52998224725""""))

        messages shouldContain "52998224725"
        messages shouldNotContain "3010000"
    }

    @Test
    fun `a BOM before the brace never hides a personal value`() {
        val messages = messagesOf("" + ecs(""""message":"oi 52998224725""""))

        messages shouldContain "52998224725"
    }

    @Test
    fun `a console line without separator whose text starts with brackets keeps that text`() {
        val log = "2026-11-14T18:23:28.100-03:00  WARN 3010000 --- [    Test worker] [,,,] [cpf] 52998224725"

        messagesOf(log) shouldContain "[cpf] 52998224725"
    }

    @Test
    fun `a console line without separator and with only bracket text keeps it`() {
        val log = "2026-11-14T18:23:28.100-03:00  WARN 3010000 --- [app] [thr] [,,,] [52998224725]"

        messagesOf(log) shouldContain "52998224725"
    }

    @Test
    fun `a console line with separator whose message starts with brackets keeps that text`() {
        val log = "2026-11-14T18:23:28.100-03:00  WARN 3010000 --- [app] [thr] [,,,] c.z.X : [52998224725] feito"

        messagesOf(log) shouldBe "[52998224725] feito"
    }

    @Test
    fun `a real prod profile ECS log shows sentinels in the message and in the MDC`(output: CapturedOutput) {
        val ctx =
            SpringApplicationBuilder(Probe::class.java)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.main.banner-mode=off", "spring.application.name=zeny-hotel-manager")
                .run()
        try {
            val logger = LoggerFactory.getLogger("com.zeny.hotel.testsupport.Sentinel")
            MDC.put("guestDocument", "SENTINELA-MDC-5299")
            logger.warn("mensagem SENTINELA-MSG-4711")
        } finally {
            MDC.clear()
            ctx.close()
        }

        val raw = output.all
        raw shouldContain """"message":"mensagem SENTINELA-MSG-4711""""
        val messages = messagesOf(raw)
        messages shouldContain "SENTINELA-MSG-4711"
        messages shouldContain "SENTINELA-MDC-5299"
    }

    @Test
    fun `a prod ECS line never carries an MDC key under the noise namespaces so the noise filter hides no value`(
        output: CapturedOutput,
    ) {
        val ctx =
            SpringApplicationBuilder(Probe::class.java)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.main.banner-mode=off", "spring.application.name=zeny-hotel-manager")
                .run()
        try {
            val logger = LoggerFactory.getLogger("com.zeny.hotel.testsupport.Sentinel")
            MDC.put("service.guest", "SENTINELA-SVC-1")
            MDC.put("process.owner", "SENTINELA-PRC-2")
            logger.warn("mensagem SENTINELA-NS-9")
        } finally {
            MDC.clear()
            ctx.close()
        }

        // O escritor ECS do Spring Boot rejeita a colisão (a linha some), logo `service` e `process` do ECS
        // nunca trazem valor de MDC: se uma versão futura passar a emiti-los, este teste fica vermelho e o
        // filtro de ruído do `messagesOf` precisa mudar.
        messagesOf(output.all) shouldNotContain "SENTINELA-SVC-1"
        messagesOf(output.all) shouldNotContain "SENTINELA-PRC-2"
        messagesOf(output.all) shouldNotContain "SENTINELA-NS-9"
    }

    @Configuration(proxyBeanMethods = false)
    class Probe
}

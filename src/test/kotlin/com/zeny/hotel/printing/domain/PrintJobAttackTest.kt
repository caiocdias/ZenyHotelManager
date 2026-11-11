package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

/** Ataque à máquina de estados do job (M5 E10, Task 3): sequências, bordas e invariantes do banco no domínio. */
class PrintJobAttackTest {
    private val t0 = Instant.parse("2026-11-11T10:00:00Z")
    private val agent = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun pending(content: ByteArray = byteArrayOf(9, 8, 7)) =
        PrintJob.create(UUID.randomUUID(), UUID.randomUUID(), PrinterKind.A4_PDF, "x.y", content, 10, t0)

    private fun sent(who: UUID = agent) = pending().also { it.claim(who, t0.plusSeconds(1)) }

    private fun printed() = sent().also { it.acknowledge(agent, true, null, t0.plusSeconds(2)) }

    private fun failed() = sent().also { it.acknowledge(agent, false, "E", t0.plusSeconds(2)) }

    private fun assertConsistent(job: PrintJob) {
        val done = job.status == PrintJobStatus.PRINTED || job.status == PrintJobStatus.FAILED
        assertThat(job.finishedAt != null).isEqualTo(done)
        if (job.status == PrintJobStatus.SENT) {
            assertThat(job.deliveredTo).isNotNull()
            assertThat(job.sentAt).isNotNull()
        }
        assertThat(job.content == null || job.contentPurgedAt == null).isTrue()
    }

    @Test
    fun `claim, expireAck, claim, acknowledge termina PRINTED com 2 tentativas`() {
        val job = pending()
        job.claim(agent, t0.plusSeconds(1))
        job.expireAck(t0.plusSeconds(200), 3, 99)
        job.claim(other, t0.plusSeconds(201))
        job.acknowledge(other, true, null, t0.plusSeconds(202))

        assertThat(job.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(job.attempts).isEqualTo(2)
        assertThat(job.position).isEqualTo(99)
        assertThat(job.deliveredTo).isEqualTo(other)
        assertThat(job.failureCode).isNull()
        assertConsistent(job)
    }

    @Test
    fun `ack tardio do primeiro agente depois do expireAck e recusado e nada muda`() {
        val job = sent()
        job.expireAck(t0.plusSeconds(200), 3, 99)

        assertThatThrownBy { job.acknowledge(agent, true, null, t0.plusSeconds(300)) }
            .isInstanceOf(ConflictException::class.java)

        assertThat(job.status).isEqualTo(PrintJobStatus.PENDING)
        assertThat(job.finishedAt).isNull()
    }

    @Test
    fun `ack do agente antigo depois de reivindicado por outro e recusado`() {
        val job = sent()
        job.expireAck(t0.plusSeconds(200), 3, 99)
        job.claim(other, t0.plusSeconds(201))

        assertThatThrownBy { job.acknowledge(agent, true, null, t0.plusSeconds(202)) }
            .isInstanceOf(ConflictException::class.java)

        assertThat(job.status).isEqualTo(PrintJobStatus.SENT)
        assertThat(job.isDeliveredTo(other)).isTrue()
        assertThat(job.isDeliveredTo(agent)).isFalse()
    }

    @Test
    fun `ack duplicado do mesmo agente depois de PRINTED e recusado e preserva o fim`() {
        val job = printed()
        val finished = job.finishedAt

        assertThatThrownBy { job.acknowledge(agent, false, "LATE", t0.plusSeconds(50)) }
            .isInstanceOf(ConflictException::class.java)

        assertThat(job.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(job.finishedAt).isEqualTo(finished)
        assertThat(job.failureCode).isNull()
    }

    @Test
    fun `expireAck volta a PENDING e limpa a entrega mantendo a contagem`() {
        val job = sent()

        job.expireAck(t0.plusSeconds(200), 3, 55)

        assertThat(job.status).isEqualTo(PrintJobStatus.PENDING)
        assertThat(job.attempts).isEqualTo(1)
        assertThat(job.sentAt).isNull()
        assertThat(job.deliveredTo).isNull()
        assertThat(job.finishedAt).isNull()
        assertThat(job.failureCode).isNull()
        assertThat(job.content).isNotNull()
        assertConsistent(job)
    }

    @Test
    fun `expireAck exatamente na tentativa max falha e na anterior volta`() {
        val job = pending()
        repeat(2) { n ->
            job.claim(agent, t0.plusSeconds(10L * n))
            job.expireAck(t0.plusSeconds(10L * n + 5), 3, 100L + n)
            assertThat(job.status).isEqualTo(PrintJobStatus.PENDING)
        }
        job.claim(agent, t0.plusSeconds(30))

        job.expireAck(t0.plusSeconds(40), 3, 200)

        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo(PrintJob.ACK_TIMEOUT)
        assertThat(job.attempts).isEqualTo(3)
        assertThat(job.finishedAt).isEqualTo(t0.plusSeconds(40))
        assertThat(job.position).isEqualTo(101)
        assertConsistent(job)
    }

    @Test
    fun `expireAck com maxAttempts 1 falha logo na primeira reivindicacao`() {
        val job = sent()

        job.expireAck(t0.plusSeconds(5), 1, 77)

        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo("ACK_TIMEOUT")
    }

    @Test
    fun `expireAck fora de SENT falha em PENDING, PRINTED e FAILED sem mudar o job`() {
        listOf(pending(), printed(), failed()).forEach { job ->
            val before = job.status
            val position = job.position
            assertThatThrownBy { job.expireAck(t0, 3, 5) }.isInstanceOf(IllegalStateException::class.java)
            assertThat(job.status).isEqualTo(before)
            assertThat(job.position).isEqualTo(position)
        }
    }

    @Test
    fun `fail em SENT limpa nada alem do estado e respeita o invariante de fim`() {
        val job = sent()

        job.fail(PrintJob.PRINTER_INACTIVE, t0.plusSeconds(9))

        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo("PRINTER_INACTIVE")
        assertThat(job.finishedAt).isEqualTo(t0.plusSeconds(9))
        assertConsistent(job)
    }

    @Test
    fun `fail em PENDING com EXPIRED deixa deliveredTo e sentAt nulos`() {
        val job = pending()

        job.fail(PrintJob.EXPIRED, t0.plusSeconds(9))

        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo("EXPIRED")
        assertThat(job.attempts).isZero()
        assertThat(job.deliveredTo).isNull()
        assertConsistent(job)
    }

    @Test
    fun `fail em FAILED nao sobrescreve o codigo nem o fim`() {
        val job = failed()

        assertThatThrownBy { job.fail("EXPIRED", t0.plusSeconds(99)) }.isInstanceOf(IllegalStateException::class.java)

        assertThat(job.failureCode).isEqualTo("E")
        assertThat(job.finishedAt).isEqualTo(t0.plusSeconds(2))
    }

    @Test
    fun `fail com codigo invalido em estado invalido nao muda nada`() {
        val job = printed()
        assertThatThrownBy { job.fail("bad", t0) }.isInstanceOf(RuntimeException::class.java)
        assertThat(job.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(job.failureCode).isNull()
    }

    @Test
    fun `purgeContent duas vezes mantem o primeiro instante de expurgo`() {
        val job = printed()
        job.purgeContent(t0.plusSeconds(100))

        runCatching { job.purgeContent(t0.plusSeconds(500)) }

        assertThat(job.content).isNull()
        assertThat(job.contentPurgedAt).isEqualTo(t0.plusSeconds(100))
        assertConsistent(job)
    }

    @Test
    fun `purgeContent em PENDING e SENT falha e preserva conteudo e instante`() {
        listOf(pending(), sent()).forEach { job ->
            assertThatThrownBy { job.purgeContent(t0) }.isInstanceOf(IllegalStateException::class.java)
            assertThat(job.content).containsExactly(9, 8, 7)
            assertThat(job.contentPurgedAt).isNull()
        }
    }

    @Test
    fun `purgeContent em FAILED apaga o conteudo e mantem o estado`() {
        val job = failed()

        job.purgeContent(t0.plusSeconds(60))

        assertThat(job.content).isNull()
        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo("E")
        assertConsistent(job)
    }

    @Test
    fun `operacoes depois do expurgo continuam recusadas`() {
        val job = printed().also { it.purgeContent(t0.plusSeconds(60)) }

        assertThatThrownBy { job.claim(agent, t0) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { job.acknowledge(agent, true, null, t0) }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { job.expireAck(t0, 3, 1) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { job.fail("EXPIRED", t0) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `claim de um job ja SENT nao troca o agente nem conta tentativa`() {
        val job = sent()

        assertThatThrownBy { job.claim(other, t0.plusSeconds(50)) }.isInstanceOf(IllegalStateException::class.java)

        assertThat(job.deliveredTo).isEqualTo(agent)
        assertThat(job.attempts).isEqualTo(1)
        assertThat(job.sentAt).isEqualTo(t0.plusSeconds(1))
    }

    @Test
    fun `acknowledge de outro agente com codigo invalido e conflito, nao validacao`() {
        val job = sent()

        assertThatThrownBy { job.acknowledge(other, false, "bad", t0) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `acknowledge impresso ignora o codigo de falha valido e nao o guarda`() {
        val job = sent()

        job.acknowledge(agent, true, "NO_PAPER", t0.plusSeconds(3))

        assertThat(job.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(job.failureCode).isNull()
    }

    @Test
    fun `acknowledge recusado por codigo invalido deixa o job em SENT sem finishedAt`() {
        val job = sent()

        assertThatThrownBy { job.acknowledge(agent, false, "a", t0) }
            .isInstanceOf(InvalidInputException::class.java)
            .extracting { (it as InvalidInputException).code }
            .isEqualTo(PrintingErrorCodes.INVALID_FAILURE_CODE)

        assertThat(job.status).isEqualTo(PrintJobStatus.SENT)
        assertThat(job.failureCode).isNull()
        assertConsistent(job)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "a", "aBC", "0ABC", "_ABC", "A B", "A-B", "A.B", " A", "A ", "A\n", "A\u0000", "ÁBC", "AÁ",
            "АBC", "ＡBC", "A​B", "A‮B", "A B", "١ABC", "A１",
        ],
    )
    fun `acknowledge e fail recusam codigos fora de ASCII maiusculo`(code: String) {
        assertThatThrownBy { sent().acknowledge(agent, false, code, t0) }
            .isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy { pending().fail(code, t0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["A", "Z", "A0", "A_", "A__9", "NO_PAPER", "ACK_TIMEOUT"])
    fun `acknowledge e fail aceitam codigos validos`(code: String) {
        val a = sent().also { it.acknowledge(agent, false, code, t0) }
        val b = pending().also { it.fail(code, t0) }
        assertThat(a.failureCode).isEqualTo(code)
        assertThat(b.failureCode).isEqualTo(code)
    }

    @Test
    fun `codigo de 50 caracteres passa e de 51 e recusado em acknowledge e fail`() {
        val ok = "A" + "9".repeat(49)
        val big = "A" + "9".repeat(50)
        assertThat(sent().also { it.acknowledge(agent, false, ok, t0) }.failureCode).hasSize(50)
        assertThat(pending().also { it.fail(ok, t0) }.failureCode).hasSize(50)
        assertThatThrownBy { sent().acknowledge(agent, false, big, t0) }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy { pending().fail(big, t0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `toString nunca mostra bytes do conteudo em nenhum estado`() {
        val secret = "SEGREDO-HOSPEDE-123".toByteArray()
        val jobs =
            listOf(
                pending(secret),
                pending(secret).also { it.claim(agent, t0) },
                pending(secret).also {
                    it.claim(agent, t0)
                    it.acknowledge(agent, true, null, t0)
                },
                pending(secret).also { it.fail("EXPIRED", t0) },
            )
        jobs.forEach { job ->
            val text = job.toString()
            assertThat(text)
                .doesNotContain("SEGREDO")
                .doesNotContain(secret.contentToString())
                .doesNotContain("content")
                .doesNotContain(String(secret))
        }
    }

    @Test
    fun `create nao troca o tipo de midia entre os tipos de impressora`() {
        PrinterKind.entries.forEach {
            val job = PrintJob.create(UUID.randomUUID(), UUID.randomUUID(), it, "a.b", byteArrayOf(1), 1, t0)
            assertThat(job.mediaType).isEqualTo(it.mediaType)
        }
    }
}

package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.PrinterKind
import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PrintJobTest {
    private val t0 = Instant.parse("2026-11-11T10:00:00Z")
    private val agent = UUID.randomUUID()
    private val printerId = UUID.randomUUID()

    private fun pending(kind: PrinterKind = PrinterKind.A4_PDF) =
        PrintJob.create(UUID.randomUUID(), printerId, kind, "frontdesk.registration-card", byteArrayOf(1, 2, 3), 10, t0)

    private fun sent() = pending().also { it.claim(agent, t0.plusSeconds(1)) }

    private fun printed() = sent().also { it.acknowledge(agent, true, null, t0.plusSeconds(2)) }

    private fun failed() = sent().also { it.acknowledge(agent, false, "NO_PAPER", t0.plusSeconds(2)) }

    @Test
    fun `cria PENDING com o tipo de midia da impressora`() {
        val job = pending(PrinterKind.THERMAL_ESCPOS)

        assertThat(job.status).isEqualTo(PrintJobStatus.PENDING)
        assertThat(job.mediaType).isEqualTo("application/octet-stream")
        assertThat(job.attempts).isZero()
        assertThat(job.position).isEqualTo(10)
        assertThat(job.createdAt).isEqualTo(t0)
        assertThat(job.sentAt).isNull()
        assertThat(job.deliveredTo).isNull()
        assertThat(job.finishedAt).isNull()
        assertThat(job.content).containsExactly(1, 2, 3)
    }

    @Test
    fun `claim leva PENDING a SENT e conta a tentativa`() {
        val job = pending()

        job.claim(agent, t0.plusSeconds(1))

        assertThat(job.status).isEqualTo(PrintJobStatus.SENT)
        assertThat(job.attempts).isEqualTo(1)
        assertThat(job.sentAt).isEqualTo(t0.plusSeconds(1))
        assertThat(job.isDeliveredTo(agent)).isTrue()
        assertThat(job.isDeliveredTo(UUID.randomUUID())).isFalse()
    }

    @Test
    fun `claim fora de PENDING falha`() {
        listOf(sent(), printed(), failed()).forEach {
            assertThatThrownBy { it.claim(agent, t0) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `acknowledge impresso leva SENT a PRINTED`() {
        val job = sent()

        job.acknowledge(agent, true, null, t0.plusSeconds(5))

        assertThat(job.status).isEqualTo(PrintJobStatus.PRINTED)
        assertThat(job.finishedAt).isEqualTo(t0.plusSeconds(5))
        assertThat(job.failureCode).isNull()
    }

    @Test
    fun `acknowledge com falha leva SENT a FAILED com o codigo`() {
        val job = sent()

        job.acknowledge(agent, false, "NO_PAPER", t0.plusSeconds(5))

        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo("NO_PAPER")
        assertThat(job.finishedAt).isEqualTo(t0.plusSeconds(5))
    }

    @Test
    fun `acknowledge com falha sem codigo e aceito`() {
        val job = sent()

        job.acknowledge(agent, false, null, t0)

        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isNull()
    }

    @Test
    fun `acknowledge fora de SENT e PRINT_JOB_NOT_ACKNOWLEDGEABLE`() {
        listOf(pending(), printed(), failed()).forEach { job ->
            assertThatThrownBy { job.acknowledge(agent, true, null, t0) }
                .isInstanceOf(ConflictException::class.java)
                .extracting { (it as ConflictException).code }
                .isEqualTo(PrintingErrorCodes.PRINT_JOB_NOT_ACKNOWLEDGEABLE)
        }
    }

    @Test
    fun `acknowledge de outro agente falha e nada muda`() {
        val job = sent()

        assertThatThrownBy { job.acknowledge(UUID.randomUUID(), true, null, t0) }
            .isInstanceOf(ConflictException::class.java)

        assertThat(job.status).isEqualTo(PrintJobStatus.SENT)
        assertThat(job.finishedAt).isNull()
    }

    @Test
    fun `acknowledge recusa codigo de falha fora do padrao`() {
        listOf("", "no_paper", "1ABC", "A B", "A-B", "A\n", "A".repeat(51), "ÁBC").forEach { code ->
            val job = sent()
            assertThatThrownBy { job.acknowledge(agent, false, code, t0) }
                .isInstanceOf(InvalidInputException::class.java)
                .extracting { (it as InvalidInputException).code }
                .isEqualTo(PrintingErrorCodes.INVALID_FAILURE_CODE)
            assertThat(job.status).isEqualTo(PrintJobStatus.SENT)
        }
    }

    @Test
    fun `acknowledge aceita codigo de 1 e de 50 caracteres`() {
        listOf("A", "A" + "B".repeat(49)).forEach { code ->
            val job = sent()
            job.acknowledge(agent, false, code, t0)
            assertThat(job.failureCode).isEqualTo(code)
        }
    }

    @Test
    fun `acknowledge impresso com codigo invalido tambem e recusado`() {
        assertThatThrownBy { sent().acknowledge(agent, true, "x", t0) }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `expireAck antes do limite volta a PENDING com a posicao nova`() {
        val job = sent()

        job.expireAck(t0.plusSeconds(200), 3, 99)

        assertThat(job.status).isEqualTo(PrintJobStatus.PENDING)
        assertThat(job.position).isEqualTo(99)
        assertThat(job.attempts).isEqualTo(1)
        assertThat(job.sentAt).isNull()
        assertThat(job.deliveredTo).isNull()
        assertThat(job.isDeliveredTo(agent)).isFalse()
    }

    @Test
    fun `expireAck na ultima tentativa vira FAILED ACK_TIMEOUT`() {
        val job = pending()
        repeat(2) {
            job.claim(agent, t0)
            job.expireAck(t0, 3, 20L + it)
        }
        job.claim(agent, t0)

        job.expireAck(t0.plusSeconds(300), 3, 99)

        assertThat(job.attempts).isEqualTo(3)
        assertThat(job.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(job.failureCode).isEqualTo("ACK_TIMEOUT")
        assertThat(job.finishedAt).isEqualTo(t0.plusSeconds(300))
        assertThat(job.position).isEqualTo(21)
    }

    @Test
    fun `expireAck fora de SENT falha`() {
        listOf(pending(), printed(), failed()).forEach {
            assertThatThrownBy { it.expireAck(t0, 3, 1) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `fail de PENDING e de SENT`() {
        val fromPending = pending()
        fromPending.fail("EXPIRED", t0.plusSeconds(9))
        val fromSent = sent()
        fromSent.fail("PRINTER_INACTIVE", t0.plusSeconds(9))

        assertThat(fromPending.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(fromPending.failureCode).isEqualTo("EXPIRED")
        assertThat(fromPending.finishedAt).isEqualTo(t0.plusSeconds(9))
        assertThat(fromSent.status).isEqualTo(PrintJobStatus.FAILED)
        assertThat(fromSent.failureCode).isEqualTo("PRINTER_INACTIVE")
        assertThat(fromSent.sentAt).isNotNull()
        assertThat(fromSent.deliveredTo).isEqualTo(agent)
    }

    @Test
    fun `fail em estado final falha`() {
        listOf(printed(), failed()).forEach {
            assertThatThrownBy { it.fail("EXPIRED", t0) }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `fail recusa codigo fora do padrao`() {
        assertThatThrownBy { pending().fail("expired", t0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `purgeContent apaga o conteudo de job terminado`() {
        listOf(printed(), failed()).forEach {
            it.purgeContent(t0.plusSeconds(100))

            assertThat(it.content).isNull()
            assertThat(it.contentPurgedAt).isEqualTo(t0.plusSeconds(100))
        }
    }

    @Test
    fun `purgeContent fora de PRINTED ou FAILED falha e mantem o conteudo`() {
        listOf(pending(), sent()).forEach {
            assertThatThrownBy { it.purgeContent(t0) }.isInstanceOf(IllegalStateException::class.java)
            assertThat(it.content).isNotNull()
            assertThat(it.contentPurgedAt).isNull()
        }
    }

    @Test
    fun `toString nao mostra o conteudo`() {
        val job =
            PrintJob.create(
                UUID.randomUUID(),
                printerId,
                PrinterKind.A4_PDF,
                "x.y",
                "SEGREDO".toByteArray(),
                1,
                t0,
            )

        assertThat(job.toString()).doesNotContain("SEGREDO").doesNotContain("[B@")
        assertThat(job.toString()).contains(job.id.toString())
    }
}

package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintJobStatus
import com.zeny.hotel.printing.PrinterKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class PrinterTest {
    private val agentId = UUID.randomUUID()

    @Test
    fun `cria ativa com o tipo e o agente`() {
        val printer = Printer.create("Balcao", PrinterKind.THERMAL_ESCPOS, "tcp://10.0.0.5:9100", agentId)

        assertThat(printer.active).isTrue()
        assertThat(printer.kind).isEqualTo(PrinterKind.THERMAL_ESCPOS)
        assertThat(printer.agentId).isEqualTo(agentId)
    }

    @Test
    fun `os tipos de midia e os estados do job`() {
        assertThat(PrinterKind.THERMAL_ESCPOS.mediaType).isEqualTo("application/octet-stream")
        assertThat(PrinterKind.A4_PDF.mediaType).isEqualTo("application/pdf")
        assertThat(PrintJobStatus.entries.map { it.name }).containsExactly("PENDING", "SENT", "PRINTED", "FAILED")
    }

    @Test
    fun `update troca nome, endereco e agente, nao o tipo`() {
        val printer = Printer.create("Balcao", PrinterKind.A4_PDF, "HP-01", agentId)
        val other = UUID.randomUUID()

        printer.update("Gerencia", "HP-02", other)

        assertThat(printer.name).isEqualTo("Gerencia")
        assertThat(printer.address).isEqualTo("HP-02")
        assertThat(printer.agentId).isEqualTo(other)
        assertThat(printer.kind).isEqualTo(PrinterKind.A4_PDF)
    }

    @Test
    fun `deactivate desativa`() {
        val printer = Printer.create("Balcao", PrinterKind.A4_PDF, "HP-01", agentId)

        printer.deactivate()

        assertThat(printer.active).isFalse()
    }

    @Test
    fun `nome e endereco invalidos sao recusados`() {
        listOf("", " ", "a".repeat(101), "a\nb").forEach {
            assertThatThrownBy { Printer.create(it, PrinterKind.A4_PDF, "HP", agentId) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf("", " ", "a".repeat(256), "a b", "a\nb", "a\u0007b", "a\tb").forEach {
            assertThatThrownBy { Printer.create("P", PrinterKind.A4_PDF, it, agentId) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(Printer.create("P", PrinterKind.A4_PDF, "a".repeat(255), agentId).address).hasSize(255)
    }

    @Test
    fun `update invalido nao muda nada`() {
        val printer = Printer.create("Balcao", PrinterKind.A4_PDF, "HP-01", agentId)

        assertThatThrownBy { printer.update("Novo", "com espaco", UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(printer.name).isEqualTo("Balcao")
        assertThat(printer.address).isEqualTo("HP-01")
        assertThat(printer.agentId).isEqualTo(agentId)
    }

    @Test
    fun `toString nao mostra o endereco`() {
        val printer = Printer.create("Balcao", PrinterKind.A4_PDF, "tcp://10.0.0.5:9100", agentId)

        assertThat(printer.toString()).doesNotContain("10.0.0.5")
    }
}

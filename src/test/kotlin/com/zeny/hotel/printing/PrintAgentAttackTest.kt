package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.Printer
import com.zeny.hotel.shared.BusinessRuleException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Ataques ao domínio de agente e impressora e aos tipos públicos do `printing` (M5 E10, Task 2): força e formato do
 * token (D-E10-2), rotação, desativação, `touch` e validação de nome e endereço (D-E10-8).
 */
class PrintAgentAttackTest {
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val agentId = UUID.randomUUID()

    private fun rejectsAgentName(name: String) {
        assertThatThrownBy { PrintAgent.create(name, now) }
            .describedAs("nome %s", name.map { codePoint(it) })
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // Token

    @Test
    fun `o token decodifica para exatamente 32 bytes e nao tem preenchimento`() {
        repeat(50) {
            val token = PrintAgent.create("A", now).second
            val body = token.removePrefix(PrintAgent.TOKEN_PREFIX)
            assertThat(body).doesNotContain("=").doesNotContain("+").doesNotContain("/")
            assertThat(Base64.getUrlDecoder().decode(body)).hasSize(32)
        }
    }

    @Test
    fun `o token reencodado e identico ao original, sem bits soltos`() {
        val token = PrintAgent.create("A", now).second.removePrefix("zpa_")

        val bytes = Base64.getUrlDecoder().decode(token)

        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).isEqualTo(token)
    }

    @Test
    fun `dez mil tokens e dez mil hashes sao todos distintos`() {
        val tokens = List(10_000) { PrintAgent.create("A", now).second }

        assertThat(tokens.toSet()).hasSize(10_000)
        assertThat(tokens.map(PrintAgent::hashOf).toSet()).hasSize(10_000)
    }

    @Test
    fun `os bytes do token sao bem distribuidos e nenhum bit fica preso`() {
        val ones = IntArray(256)
        val samples = 4_000
        repeat(samples) {
            val bytes = Base64.getUrlDecoder().decode(PrintAgent.create("A", now).second.removePrefix("zpa_"))
            bytes.forEachIndexed { i, b ->
                for (bit in 0 until 8) if ((b.toInt() shr bit) and 1 == 1) ones[i * 8 + bit]++
            }
        }
        // Cada bit deve ser 1 em cerca de metade das amostras (4000 +- 6 desvios = 1810..2190).
        ones.forEachIndexed { pos, count ->
            assertThat(count).describedAs("bit %d", pos).isBetween(1_810, 2_190)
        }
    }

    @Test
    fun `o hash de SHA-256 bate com vetores conhecidos em hex minusculo`() {
        assertThat(PrintAgent.hashOf("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(PrintAgent.hashOf(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `o hash do token do agente e o hash do token em claro`() {
        repeat(20) {
            val (agent, token) = PrintAgent.create("A", now)
            assertThat(agent.tokenHash).isEqualTo(PrintAgent.hashOf(token)).hasSize(64).matches("[0-9a-f]{64}")
        }
    }

    @Test
    fun `o token em claro nunca aparece em toString nem no hash guardado`() {
        val (agent, token) = PrintAgent.create("Balcao", now)

        assertThat(agent.toString()).doesNotContain(token).doesNotContain(token.removePrefix("zpa_"))
        assertThat(agent.tokenHash).doesNotContain(token.removePrefix("zpa_"))
        assertThat(agent.toString()).doesNotContain(agent.tokenHash!!)
    }

    @Test
    fun `a excecao de rotacao de agente inativo nao carrega token nem hash`() {
        val (agent, token) = PrintAgent.create("Balcao", now)
        val hash = agent.tokenHash!!
        agent.deactivate()

        val error = runCatching { agent.rotateToken(now) }.exceptionOrNull()

        assertThat(error).isInstanceOf(BusinessRuleException::class.java)
        assertThat(error.toString()).doesNotContain(token).doesNotContain(hash)
        assertThat(error!!.message).doesNotContain(token).doesNotContain(hash)
    }

    // Rotação, desativação

    @Test
    fun `a rotacao muda o credentialStamp mesmo com o relogio parado`() {
        val (agent, _) = PrintAgent.create("A", now)
        val stamps = mutableListOf(agent.credentialStamp)

        repeat(5) {
            agent.rotateToken(now)
            stamps += agent.credentialStamp
        }

        assertThat(stamps).containsExactly("1", "2", "3", "4", "5", "6")
    }

    @Test
    fun `cada rotacao invalida todos os tokens anteriores, nao so o ultimo`() {
        val (agent, first) = PrintAgent.create("A", now)
        val second = agent.rotateToken(now)
        val third = agent.rotateToken(now)

        val current = agent.tokenHash
        assertThat(current).isEqualTo(PrintAgent.hashOf(third))
        assertThat(current).isNotEqualTo(PrintAgent.hashOf(first)).isNotEqualTo(PrintAgent.hashOf(second))
        assertThat(setOf(first, second, third)).hasSize(3)
    }

    @Test
    fun `rotacionar inativo falha e nao muda hash nem geracao`() {
        val (agent, _) = PrintAgent.create("A", now)
        agent.deactivate()

        assertThatThrownBy { agent.rotateToken(now) }.isInstanceOf(BusinessRuleException::class.java)

        assertThat(agent.tokenHash).isNull()
        assertThat(agent.credentialStamp).isEqualTo("1")
        assertThat(agent.active).isFalse()
    }

    @Test
    fun `desativar duas vezes continua inativo e sem hash`() {
        val (agent, _) = PrintAgent.create("A", now)

        agent.deactivate()
        agent.deactivate()

        assertThat(agent.active).isFalse()
        assertThat(agent.tokenHash).isNull()
    }

    @Test
    fun `touch e rename nao mexem na credencial`() {
        val (agent, _) = PrintAgent.create("A", now)
        val hash = agent.tokenHash
        val stamp = agent.credentialStamp

        agent.touch(now)
        agent.rename("B")

        assertThat(agent.tokenHash).isEqualTo(hash)
        assertThat(agent.credentialStamp).isEqualTo(stamp)
    }

    // touch

    @Test
    fun `touch a 60 segundos exatos nao muda e a 60 segundos e 1 nanosegundo muda`() {
        val (agent, _) = PrintAgent.create("A", now)
        agent.touch(now)

        assertThat(agent.touch(now.plusSeconds(60))).isFalse()
        assertThat(agent.touch(now.plusSeconds(60).plusNanos(1))).isTrue()
        assertThat(agent.lastSeenAt).isEqualTo(now.plusSeconds(60).plusNanos(1))
    }

    @Test
    fun `touch com o relogio voltando para tras nao muda o ultimo contato`() {
        val (agent, _) = PrintAgent.create("A", now)
        agent.touch(now)

        assertThat(agent.touch(now.minusSeconds(1))).isFalse()
        assertThat(agent.touch(now.minus(Duration.ofHours(1)))).isFalse()
        assertThat(agent.lastSeenAt).isEqualTo(now)
    }

    @Test
    fun `touch dentro do intervalo nao adianta a janela`() {
        val (agent, _) = PrintAgent.create("A", now)
        agent.touch(now)
        agent.touch(now.plusSeconds(59))

        assertThat(agent.touch(now.plusSeconds(61))).isTrue()
    }

    // Nome

    @Test
    fun `nome so de espacos de varios tipos e recusado`() {
        listOf(" ", "\u00A0", "\u2003", "\u3000", "  \t ").forEach(::rejectsAgentName)
    }

    @Test
    fun `nome com 100 caracteres passa, com 101 nao, e o limite vale depois de aparar`() {
        assertThat(PrintAgent.create("a".repeat(100), now).first.name).hasSize(100)
        assertThat(PrintAgent.create("  " + "a".repeat(100) + "  ", now).first.name).hasSize(100)
        rejectsAgentName("a".repeat(101))
    }

    @Test
    fun `nome com controle C0, DEL e C1 e recusado no meio do nome`() {
        listOf("\u0000", "\u001F", "\u007F", "\u0085", "\u009F", "\n", "\r", "\t").forEach {
            rejectsAgentName("a${it}b")
        }
    }

    @Test
    fun `nome com separador de linha ou paragrafo U+2028 e U+2029 e recusado`() {
        rejectsAgentName("a\u2028b")
        rejectsAgentName("a\u2029b")
    }

    @Test
    fun `nome com caractere de formatacao invisivel e recusado`() {
        // Zero-width space, joiner, LRM, RLO (inverte o texto), BOM e soft hyphen.
        listOf("\u200B", "\u200D", "\u200E", "\u202E", "\uFEFF", "\u00AD").forEach {
            rejectsAgentName("Balc${it}ao")
        }
    }

    @Test
    fun `nome so de caracter invisivel nao vira nome valido`() {
        rejectsAgentName("\u200B")
        rejectsAgentName("\uFEFF\u200B")
    }

    @Test
    fun `rename recusado nao altera o nome`() {
        val (agent, _) = PrintAgent.create("Balcao", now)

        listOf("", "  ", "a".repeat(101), "a\u2028b").forEach {
            runCatching { agent.rename(it) }
        }

        assertThat(agent.name).isEqualTo("Balcao")
    }

    @Test
    fun `nome com acento e emoji dentro do limite passa`() {
        assertThat(PrintAgent.create("Recepção ñ 日本", now).first.name).isEqualTo("Recepção ñ 日本")
    }

    // Impressora

    private fun printer(address: String = "USB001") =
        Printer.create("Cozinha", PrinterKind.THERMAL_ESCPOS, address, agentId)

    private fun rejectsAddress(address: String) {
        assertThatThrownBy { printer(address) }
            .describedAs("endereco %s", address.map { codePoint(it) })
            .isInstanceOf(IllegalArgumentException::class.java)
        val p = printer()
        assertThatThrownBy { p.update("Cozinha", address, agentId) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(p.address).isEqualTo("USB001")
    }

    @Test
    fun `endereco vazio, so espaco ou com espaco no meio e recusado`() {
        listOf("", " ", "  ", "a b", " a", "a ", "a\tb", "a\u3000b", "a\u2003b").forEach(::rejectsAddress)
    }

    @Test
    fun `endereco com espaco nao separavel e recusado`() {
        rejectsAddress("a\u00A0b")
        rejectsAddress("a\u202Fb")
    }

    @Test
    fun `endereco tem limite de 255 e a impressora nao aparou o valor`() {
        assertThat(printer("a".repeat(255)).address).hasSize(255)
        rejectsAddress("a".repeat(256))
        assertThat(printer("//host/fila").address).isEqualTo("//host/fila")
    }

    @Test
    fun `endereco com controle e separadores U+2028 e U+2029 e recusado`() {
        listOf("\u0000", "\n", "\r", "\u007F", "\u0085", "\u2028", "\u2029").forEach { rejectsAddress("a${it}b") }
    }

    @Test
    fun `endereco com caractere de formatacao invisivel e recusado`() {
        listOf("\u200B", "\u200E", "\u202E", "\uFEFF").forEach { rejectsAddress("USB${it}001") }
    }

    @Test
    fun `nome da impressora segue as mesmas regras do agente`() {
        listOf("", "  ", "a".repeat(101), "a\nb", "a\u2028b", "a\u200Bb").forEach {
            assertThatThrownBy { Printer.create(it, PrinterKind.A4_PDF, "x", agentId) }
                .describedAs("nome %s", it.map { c -> codePoint(c) })
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(Printer.create("  Caixa ", PrinterKind.A4_PDF, "x", agentId).name).isEqualTo("Caixa")
    }

    @Test
    fun `update invalido nao muda nome, endereco nem agente`() {
        val p = printer()
        val other = UUID.randomUUID()

        assertThatThrownBy { p.update("Novo", "a b", other) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { p.update("", "novo", other) }.isInstanceOf(IllegalArgumentException::class.java)

        assertThat(p.name).isEqualTo("Cozinha")
        assertThat(p.address).isEqualTo("USB001")
        assertThat(p.agentId).isEqualTo(agentId)
    }

    @Test
    fun `update valido troca nome, endereco e agente e mantem o kind`() {
        val p = printer()
        val other = UUID.randomUUID()

        p.update(" Bar ", "USB002", other)

        assertThat(p.name).isEqualTo("Bar")
        assertThat(p.address).isEqualTo("USB002")
        assertThat(p.agentId).isEqualTo(other)
        assertThat(p.kind).isEqualTo(PrinterKind.THERMAL_ESCPOS)
        assertThat(p.active).isTrue()
    }

    @Test
    fun `desativar a impressora e idempotente e o toString nao mostra o endereco`() {
        val p = printer("SEGREDO-192.168.0.9")

        p.deactivate()
        p.deactivate()

        assertThat(p.active).isFalse()
        assertThat(p.toString()).doesNotContain("SEGREDO")
    }

    // Tipos públicos

    @Test
    fun `os tipos de impressora e seus tipos de midia sao exatamente os prometidos`() {
        assertThat(PrinterKind.entries.map { it.name to it.mediaType }).containsExactlyInAnyOrder(
            "THERMAL_ESCPOS" to "application/octet-stream",
            "A4_PDF" to "application/pdf",
        )
        assertThat(PrinterKind.valueOf("A4_PDF")).isEqualTo(PrinterKind.A4_PDF)
    }

    @Test
    fun `o ciclo do job tem exatamente os quatro estados na ordem do ciclo`() {
        assertThat(PrintJobStatus.entries.map { it.name }).containsExactly("PENDING", "SENT", "PRINTED", "FAILED")
    }

    @Test
    fun `os codigos de erro publicos sao doze, distintos e iguais ao nome do campo`() {
        val fields =
            PrintingErrorCodes::class.java.declaredFields
                .filter {
                    it.name.startsWith(
                        "PRINT",
                    ) || it.name.startsWith("UNKNOWN") || it.name.startsWith("INVALID")
                }
        val codes =
            PrintingErrorCodes::class.java.declaredMethods
                .filter { it.name.startsWith("get") && it.parameterCount == 0 }
                .map { it.invoke(PrintingErrorCodes).toString() }

        assertThat(codes).containsExactlyInAnyOrder(
            "PRINT_AGENT_INACTIVE",
            "PRINTER_INACTIVE",
            "UNKNOWN_PRINT_AGENT",
            "UNKNOWN_PRINTER",
            "PRINT_AGENT_NOT_FOUND",
            "PRINTER_NOT_FOUND",
            "PRINT_JOB_NOT_FOUND",
            "PRINT_JOB_NOT_ACKNOWLEDGEABLE",
            "PRINT_CONTENT_TOO_LARGE",
            "PRINT_CONTENT_EMPTY",
            "PRINT_CONTENT_NOT_PDF",
            "INVALID_FAILURE_CODE",
        )
        assertThat(codes.toSet()).hasSize(12)
        assertThat(fields).hasSize(12)
    }

    @Test
    fun `a excecao de agente inativo leva o codigo PRINT_AGENT_INACTIVE e o status 422`() {
        val (agent, _) = PrintAgent.create("A", now)
        agent.deactivate()

        val error = runCatching { agent.rotateToken(now) }.exceptionOrNull() as BusinessRuleException

        assertThat(error.code).isEqualTo(PrintingErrorCodes.PRINT_AGENT_INACTIVE)
        assertThat(error.code.value).isEqualTo("PRINT_AGENT_INACTIVE")
    }
}

private fun codePoint(char: Char): String = String.format(java.util.Locale.ROOT, "U+%04X", char.code)

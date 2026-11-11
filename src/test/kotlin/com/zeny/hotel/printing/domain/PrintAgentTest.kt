package com.zeny.hotel.printing.domain

import com.zeny.hotel.printing.PrintingErrorCodes
import com.zeny.hotel.shared.BusinessRuleException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PrintAgentTest {
    private val now = Instant.parse("2026-11-11T10:00:00Z")

    @Test
    fun `o token tem o prefixo, 47 caracteres e so base64url`() {
        val (_, token) = PrintAgent.create("Balcao", now)

        assertThat(token).startsWith(PrintAgent.TOKEN_PREFIX).hasSize(47)
        assertThat(token.removePrefix("zpa_")).matches("[A-Za-z0-9_-]{43}")
    }

    @Test
    fun `dois tokens diferem`() {
        val first = PrintAgent.create("A", now).second
        val second = PrintAgent.create("B", now).second

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `hashOf tem 64 caracteres hex e e deterministico`() {
        val hash = PrintAgent.hashOf("zpa_abc")

        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(PrintAgent.hashOf("zpa_abc")).isEqualTo(hash)
        assertThat(PrintAgent.hashOf("zpa_abd")).isNotEqualTo(hash)
    }

    @Test
    fun `o agente novo guarda so o hash do token e comeca na geracao 1`() {
        val (agent, token) = PrintAgent.create("Balcao", now)

        assertThat(agent.tokenHash).isEqualTo(PrintAgent.hashOf(token))
        assertThat(agent.active).isTrue()
        assertThat(agent.credentialStamp).isEqualTo("1")
        assertThat(agent.lastSeenAt).isNull()
    }

    @Test
    fun `a rotacao troca o hash e o credentialStamp e o token anterior deixa de casar`() {
        val (agent, old) = PrintAgent.create("Balcao", now)

        val new = agent.rotateToken(now)

        assertThat(new).isNotEqualTo(old)
        assertThat(agent.tokenHash).isEqualTo(PrintAgent.hashOf(new)).isNotEqualTo(PrintAgent.hashOf(old))
        assertThat(agent.credentialStamp).isEqualTo("2")
    }

    @Test
    fun `desativar apaga o hash`() {
        val (agent, _) = PrintAgent.create("Balcao", now)

        agent.deactivate()

        assertThat(agent.active).isFalse()
        assertThat(agent.tokenHash).isNull()
    }

    @Test
    fun `rotacionar agente inativo falha com PRINT_AGENT_INACTIVE`() {
        val (agent, _) = PrintAgent.create("Balcao", now)
        agent.deactivate()

        assertThatThrownBy { agent.rotateToken(now) }
            .isInstanceOf(BusinessRuleException::class.java)
            .extracting { (it as BusinessRuleException).code }
            .isEqualTo(PrintingErrorCodes.PRINT_AGENT_INACTIVE)
    }

    @Test
    fun `touch so muda se o ultimo contato tem mais de 1 minuto`() {
        val (agent, _) = PrintAgent.create("Balcao", now)

        assertThat(agent.touch(now)).isTrue()
        assertThat(agent.touch(now.plusSeconds(30))).isFalse()
        assertThat(agent.touch(now.plus(Duration.ofMinutes(1)))).isFalse()
        assertThat(agent.lastSeenAt).isEqualTo(now)
        assertThat(agent.touch(now.plusSeconds(61))).isTrue()
        assertThat(agent.lastSeenAt).isEqualTo(now.plusSeconds(61))
    }

    @Test
    fun `nome de 1 a 100 caracteres sem controle`() {
        assertThat(PrintAgent.create("a".repeat(100), now).first.name).hasSize(100)
        listOf("", "   ", "a".repeat(101), "a\nb", "a\u0000b", "a\tb").forEach {
            assertThatThrownBy { PrintAgent.create(it, now) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rename valida e aparar o nome`() {
        val (agent, _) = PrintAgent.create("Balcao", now)

        agent.rename("  Cozinha ")
        assertThat(agent.name).isEqualTo("Cozinha")
        assertThatThrownBy { agent.rename("x\ry") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(agent.name).isEqualTo("Cozinha")
    }

    @Test
    fun `toString nunca mostra hash nem token`() {
        val (agent, token) = PrintAgent.create("Balcao", now)

        assertThat(agent.toString()).doesNotContain(token).doesNotContain(agent.tokenHash!!).doesNotContain("zpa_")
    }
}

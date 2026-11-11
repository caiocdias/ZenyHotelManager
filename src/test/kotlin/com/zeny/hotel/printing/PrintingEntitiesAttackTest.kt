package com.zeny.hotel.printing

import com.zeny.hotel.printing.domain.PrintAgent
import com.zeny.hotel.printing.domain.Printer
import com.zeny.hotel.testsupport.IntegrationTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * As entidades do `printing` contra o banco real (M5 E10, Task 2), com `ddl-auto: validate`: persistir, reler com o
 * contexto limpo e conferir o que o domínio promete. Dados próprios (nome com marca única), apagados no fim.
 */
@IntegrationTest
class PrintingEntitiesAttackTest(
    @Autowired private val em: EntityManager,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
) {
    private val tag = "ent-attack-" + UUID.randomUUID()
    private val now = Instant.parse("2026-11-11T10:00:00Z").truncatedTo(ChronoUnit.MICROS)

    @AfterEach
    fun cleanUp() {
        val agents = "SELECT id FROM printing.print_agent WHERE name LIKE '$tag%'"
        jdbc.update("DELETE FROM printing.printer WHERE agent_id IN ($agents)")
        jdbc.update("DELETE FROM printing.print_agent WHERE name LIKE '$tag%'")
    }

    private fun <T> inTx(block: () -> T): T = tx.execute { block() }!!

    private fun reloadAgent(id: UUID): PrintAgent =
        inTx {
            em.clear()
            em.find(PrintAgent::class.java, id)!!
        }

    @Test
    fun `agente persistido e relido guarda hash, geracao, ativo e datas`() {
        val (agent, token) = PrintAgent.create("$tag-a", now)
        inTx { em.persist(agent) }

        val loaded = reloadAgent(agent.id)

        assertThat(loaded.name).isEqualTo("$tag-a")
        assertThat(loaded.tokenHash).isEqualTo(PrintAgent.hashOf(token)).hasSize(64)
        assertThat(loaded.tokenGeneration).isEqualTo(1)
        assertThat(loaded.credentialStamp).isEqualTo("1")
        assertThat(loaded.active).isTrue()
        assertThat(loaded.createdAt).isEqualTo(now)
        assertThat(loaded.lastSeenAt).isNull()
        assertThat(loaded.version).isEqualTo(0L)
        assertThat(
            jdbc.queryForObject(
                "SELECT token_hash FROM printing.print_agent WHERE id = ?",
                String::class.java,
                agent.id,
            ),
        ).isEqualTo(PrintAgent.hashOf(token))
    }

    @Test
    fun `rotacao, touch e desativacao sobrevivem a ida ao banco`() {
        val (agent, _) = PrintAgent.create("$tag-b", now)
        inTx { em.persist(agent) }

        val newToken =
            inTx {
                em.clear()
                val managed = em.find(PrintAgent::class.java, agent.id)!!
                managed.touch(now)
                managed.rotateToken(now)
            }
        val rotated = reloadAgent(agent.id)
        assertThat(rotated.tokenHash).isEqualTo(PrintAgent.hashOf(newToken))
        assertThat(rotated.credentialStamp).isEqualTo("2")
        assertThat(rotated.lastSeenAt).isEqualTo(now)
        assertThat(rotated.version).isEqualTo(1L)

        inTx {
            em.clear()
            em.find(PrintAgent::class.java, agent.id)!!.deactivate()
        }
        val off = reloadAgent(agent.id)
        assertThat(off.active).isFalse()
        assertThat(off.tokenHash).isNull()
        assertThat(
            jdbc.queryForObject(
                "SELECT token_hash IS NULL FROM printing.print_agent WHERE id = ?",
                Boolean::class.java,
                agent.id,
            ),
        ).isTrue()
    }

    @Test
    fun `dois agentes tem hashes diferentes no banco e a tabela nao guarda o token em claro`() {
        val (a, tokenA) = PrintAgent.create("$tag-c1", now)
        val (b, _) = PrintAgent.create("$tag-c2", now)
        inTx {
            em.persist(a)
            em.persist(b)
        }

        val rows =
            jdbc.queryForList(
                "SELECT token_hash, name FROM printing.print_agent WHERE name LIKE ?",
                "$tag-c%",
            )
        assertThat(rows.map { it["token_hash"] }.toSet()).hasSize(2)
        val dump = jdbc.queryForList("SELECT t::text AS r FROM printing.print_agent t WHERE name LIKE ?", "$tag-c%")
        assertThat(dump.joinToString { it["r"].toString() }).doesNotContain(tokenA.removePrefix("zpa_"))
    }

    @Test
    fun `impressora persistida e relida guarda tipo, endereco, agente e ativo`() {
        val (agent, _) = PrintAgent.create("$tag-d", now)
        val printer = Printer.create("$tag-p", PrinterKind.A4_PDF, "//srv/fila 1".replace(" ", "_"), agent.id)
        inTx {
            em.persist(agent)
            em.flush()
            em.persist(printer)
        }

        val loaded =
            inTx {
                em.clear()
                em.find(Printer::class.java, printer.id)!!
            }

        assertThat(loaded.name).isEqualTo("$tag-p")
        assertThat(loaded.kind).isEqualTo(PrinterKind.A4_PDF)
        assertThat(loaded.address).isEqualTo("//srv/fila_1")
        assertThat(loaded.agentId).isEqualTo(agent.id)
        assertThat(loaded.active).isTrue()
        assertThat(
            jdbc.queryForObject("SELECT kind FROM printing.printer WHERE id = ?", String::class.java, printer.id),
        ).isEqualTo("A4_PDF")
    }

    @Test
    fun `update e desativacao da impressora chegam ao banco e o tipo continua`() {
        val (agent, _) = PrintAgent.create("$tag-e1", now)
        val (other, _) = PrintAgent.create("$tag-e2", now)
        val printer = Printer.create("$tag-p2", PrinterKind.THERMAL_ESCPOS, "USB001", agent.id)
        inTx {
            em.persist(agent)
            em.persist(other)
            em.flush()
            em.persist(printer)
        }

        inTx {
            em.clear()
            val p = em.find(Printer::class.java, printer.id)!!
            p.update("$tag-p3", "USB002", other.id)
            p.deactivate()
        }

        val loaded =
            inTx {
                em.clear()
                em.find(Printer::class.java, printer.id)!!
            }
        assertThat(loaded.name).isEqualTo("$tag-p3")
        assertThat(loaded.address).isEqualTo("USB002")
        assertThat(loaded.agentId).isEqualTo(other.id)
        assertThat(loaded.active).isFalse()
        assertThat(loaded.kind).isEqualTo(PrinterKind.THERMAL_ESCPOS)
        assertThat(loaded.version).isEqualTo(1L)
    }
}
